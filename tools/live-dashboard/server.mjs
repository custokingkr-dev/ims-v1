#!/usr/bin/env node
//
// Live operations dashboard for Custoking IMS.
//
// Reads the Cloud Monitoring API directly and serves a single page. No dependencies, no build step, no
// container image, no npm install -- it runs on the Node already on the machine, and unchanged on Cloud
// Run if it is ever deployed there.
//
// WHY THIS EXISTS RATHER THAN A CLOUD MONITORING DASHBOARD
//
// The native dashboards are live and unusable: sixty-odd panels with no hierarchy, where an empty chart
// and a healthy chart are the same picture. That last part is not cosmetic. Five log-based metrics
// collected zero series for twenty days behind panels that rendered as flat lines, and nothing anywhere
// errored. So the central rule of this file is:
//
//     A PANEL WITH NO DATA MUST NEVER LOOK LIKE A PANEL REPORTING ZERO.
//
// Every panel resolves to one of five honest states -- live, zero, stale, never, or failed -- and each
// looks different. When a panel returns nothing over the requested window, the server re-queries over
// thirty days to tell "quiet right now" apart from "this metric has never emitted a point in its life",
// which is the difference between a calm Tuesday and a broken telemetry pipeline.
//
// AUTH
//
// On Cloud Run it uses the instance metadata server. Locally it shells out to `gcloud auth
// print-access-token` and caches the result. There is deliberately no service-account key anywhere: this
// project runs entirely on workload identity federation and adding a key would be the only one.

import http from "node:http";
import https from "node:https";
import fs from "node:fs";
import path from "node:path";
import { execFile } from "node:child_process";
import { fileURLToPath } from "node:url";
import { PANELS, GROUPS } from "./panels.mjs";
import { COST_INPUTS, estimateDailyInr } from "./cost.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PROJECT = process.env.DASHBOARD_PROJECT || "custoking-prod";
const PORT = Number(process.env.PORT || 8787);
const ON_CLOUD_RUN = Boolean(process.env.K_SERVICE);

// ---------------------------------------------------------------------------------------------------
// Credentials

let cachedToken = null;

async function accessToken() {
  if (cachedToken && cachedToken.expiresAt > Date.now() + 60_000) return cachedToken.value;

  const value = ON_CLOUD_RUN ? await metadataToken() : await gcloudToken();
  // Both sources issue roughly an hour; re-fetching early is cheap and avoids a mid-request expiry.
  cachedToken = { value, expiresAt: Date.now() + 45 * 60_000 };
  return value;
}

function metadataToken() {
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        host: "metadata.google.internal",
        path: "/computeMetadata/v1/instance/service-accounts/default/token",
        headers: { "Metadata-Flavor": "Google" },
      },
      (res) => {
        let body = "";
        res.on("data", (c) => (body += c));
        res.on("end", () => {
          try {
            resolve(JSON.parse(body).access_token);
          } catch {
            reject(new Error("metadata server returned a token response that did not parse"));
          }
        });
      },
    );
    req.on("error", reject);
    req.end();
  });
}

function gcloudToken() {
  return new Promise((resolve, reject) => {
    // shell:true because gcloud is a .cmd shim on Windows and will not exec directly.
    execFile("gcloud", ["auth", "print-access-token"], { shell: true }, (err, stdout, stderr) => {
      if (err) {
        return reject(
          new Error(
            "could not get a token from gcloud. Run `gcloud auth login` first.\n" +
              String(stderr || err.message).trim(),
          ),
        );
      }
      resolve(stdout.trim());
    });
  });
}

// ---------------------------------------------------------------------------------------------------
// Cloud Monitoring

function monitoringRequest(urlPath, token) {
  return new Promise((resolve, reject) => {
    const req = https.request(
      { host: "monitoring.googleapis.com", path: urlPath, headers: { Authorization: `Bearer ${token}` } },
      (res) => {
        let body = "";
        res.on("data", (c) => (body += c));
        res.on("end", () => {
          let parsed;
          try {
            parsed = JSON.parse(body);
          } catch {
            return reject(new Error(`monitoring API returned non-JSON (HTTP ${res.statusCode})`));
          }
          if (parsed.error) return reject(new Error(parsed.error.message || "monitoring API error"));
          resolve(parsed);
        });
      },
    );
    req.on("error", reject);
    req.end();
  });
}

function buildQuery(panel, startIso, endIso, alignmentPeriod) {
  const params = new URLSearchParams();
  params.set("filter", panel.filter);
  params.set("interval.startTime", startIso);
  params.set("interval.endTime", endIso);
  params.set("aggregation.alignmentPeriod", alignmentPeriod);
  params.set("aggregation.perSeriesAligner", panel.aligner);
  if (panel.reducer) params.set("aggregation.crossSeriesReducer", panel.reducer);
  let qs = params.toString();
  // groupByFields is a repeated parameter; URLSearchParams cannot express repetition via set().
  for (const field of panel.groupBy || []) {
    qs += `&aggregation.groupByFields=${encodeURIComponent(field)}`;
  }
  return `/v3/projects/${PROJECT}/timeSeries?${qs}`;
}

// The API returns points newest-first. Everything downstream wants oldest-first, and getting this
// backwards produces charts that look plausible and run backwards in time.
function readPoints(series) {
  return (series.points || [])
    .map((p) => {
      const v = p.value || {};
      const raw =
        v.doubleValue ??
        (v.int64Value !== undefined ? Number(v.int64Value) : undefined) ??
        v.distributionValue?.mean;
      return { t: p.interval.endTime, v: raw === undefined ? null : Number(raw) };
    })
    .filter((p) => p.v !== null)
    .reverse();
}

function labelOf(series, groupBy) {
  if (!groupBy || !groupBy.length) return null;
  const labels = series.metric?.labels || {};
  return groupBy.map((f) => labels[f.split(".").pop()] ?? "(unset)").join(" · ");
}

async function fetchPanel(panel, token, windowMinutes) {
  const end = new Date();
  const start = new Date(end.getTime() - windowMinutes * 60_000);
  // Roughly 60 buckets across whatever window was asked for, floored at the API's 60s minimum.
  const alignment = `${Math.max(60, Math.round((windowMinutes * 60) / 60))}s`;

  const base = { id: panel.id, group: panel.group, title: panel.title, note: panel.note,
                 format: panel.format, kind: panel.kind, emphasis: !!panel.emphasis };

  let response;
  try {
    response = await monitoringRequest(buildQuery(panel, start.toISOString(), end.toISOString(), alignment), token);
  } catch (err) {
    return { ...base, state: "failed", error: err.message };
  }

  const floor = (v) => (panel.zeroBelow !== undefined && v <= panel.zeroBelow ? 0 : v);
  const series = (response.timeSeries || [])
    .map((s) => ({
      label: labelOf(s, panel.groupBy),
      points: readPoints(s).map((pt) => ({ ...pt, v: floor(pt.v) })),
    }))
    .filter((s) => s.points.length);

  if (!series.length) {
    // Nothing in the window. Distinguish a quiet system from a dead pipeline by looking much further
    // back -- this is the whole point of the dashboard and it is worth the extra request.
    const wideStart = new Date(end.getTime() - 30 * 24 * 60 * 60_000);
    try {
      const wide = await monitoringRequest(
        buildQuery(panel, wideStart.toISOString(), end.toISOString(), "3600s"),
        token,
      );
      const wideSeries = (wide.timeSeries || []).map((s) => readPoints(s)).filter((p) => p.length);
      if (!wideSeries.length) {
        // For a counter filtered to one label value, no series means that value has never occurred --
        // which for something like a 5xx count is the good outcome, not a broken pipeline.
        return panel.absentMeansZero
          ? { ...base, state: "zero", value: 0, severity: panel.severity ? panel.severity(0) : null, series: [] }
          : { ...base, state: "never" };
      }
      const last = wideSeries.flat().sort((a, b) => new Date(b.t) - new Date(a.t))[0];
      return { ...base, state: "stale", lastSeen: last.t, value: last.v };
    } catch (err) {
      return { ...base, state: "failed", error: err.message };
    }
  }

  const latest = series.map((s) => s.points[s.points.length - 1].v);
  const value = panel.kind === "breakdown"
    ? latest.reduce((a, b) => a + b, 0)
    : Math.max(...latest);

  const severity = panel.severity ? panel.severity(value) : null;
  return {
    ...base,
    state: value === 0 ? "zero" : "live",
    value,
    severity,
    series: series
      .sort((a, b) => b.points[b.points.length - 1].v - a.points[a.points.length - 1].v)
      .slice(0, 12),
  };
}


// ---------------------------------------------------------------------------------------------------
// Live cost estimate
//
// Computed from resource usage rather than read from the billing export, because that export is broken
// at the billing-account level and Google owns the fix. See cost.mjs for the method and its limits.

async function estimateCost(token) {
  const end = new Date();
  const start = new Date(end.getTime() - 24 * 60 * 60_000);
  const totals = {};

  for (const input of COST_INPUTS) {
    try {
      const params = new URLSearchParams({
        filter: `metric.type="${input.metric}"`,
        "interval.startTime": start.toISOString(),
        "interval.endTime": end.toISOString(),
        "aggregation.alignmentPeriod": "86400s",
        "aggregation.perSeriesAligner": input.aligner,
        "aggregation.crossSeriesReducer": "REDUCE_SUM",
      });
      const res = await monitoringRequest(`/v3/projects/${PROJECT}/timeSeries?${params}`, token);
      let sum = 0;
      for (const series of res.timeSeries || []) {
        for (const point of series.points || []) {
          const v = point.value || {};
          sum += Number(v.doubleValue ?? v.int64Value ?? 0);
        }
      }
      totals[input.key] = sum;
    } catch {
      // A missing input understates the estimate rather than failing the page. The panel says
      // "estimate" for exactly this kind of reason.
      totals[input.key] = 0;
    }
  }

  return { ...estimateDailyInr(totals), totals };
}

// ---------------------------------------------------------------------------------------------------
// HTTP

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);

  if (url.pathname === "/" || url.pathname === "/index.html") {
    const html = fs.readFileSync(path.join(HERE, "public", "index.html"), "utf8");
    res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
    return res.end(html);
  }

  if (url.pathname === "/api/snapshot") {
    const windowMinutes = Math.min(10080, Math.max(15, Number(url.searchParams.get("window") || 180)));
    try {
      const token = await accessToken();
      const [panels, cost] = await Promise.all([
        Promise.all(PANELS.map((p) => fetchPanel(p, token, windowMinutes))),
        estimateCost(token).catch(() => null),
      ]);
      res.writeHead(200, { "content-type": "application/json", "cache-control": "no-store" });
      return res.end(JSON.stringify({
        project: PROJECT,
        windowMinutes,
        generatedAt: new Date().toISOString(),
        groups: GROUPS,
        panels,
        cost,
      }));
    } catch (err) {
      res.writeHead(500, { "content-type": "application/json" });
      return res.end(JSON.stringify({ error: err.message }));
    }
  }

  res.writeHead(404, { "content-type": "text/plain" });
  res.end("not found");
});

server.listen(PORT, () => {
  console.log(`live dashboard  →  http://localhost:${PORT}`);
  console.log(`project ${PROJECT}   auth: ${ON_CLOUD_RUN ? "metadata server" : "gcloud"}`);
});
