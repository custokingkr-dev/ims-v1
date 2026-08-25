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
import { PANELS, GROUPS, AUDIENCE } from "./panels.mjs";
import { COST_INPUTS, estimateDailyInr, COST_FILTER_EXCLUDE_SELF } from "./cost.mjs";
import * as auth from "./auth.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PROJECT = process.env.DASHBOARD_PROJECT || "custoking-prod";
const PORT = Number(process.env.PORT || 8787);
const ON_CLOUD_RUN = Boolean(process.env.K_SERVICE);
const configuredUpstreamTimeout = Number(process.env.DASHBOARD_UPSTREAM_TIMEOUT_MS || 10_000);
const UPSTREAM_TIMEOUT_MS = Number.isFinite(configuredUpstreamTimeout) && configuredUpstreamTimeout > 0
  ? configuredUpstreamTimeout : 10_000;
const MAX_API_RESPONSE_BYTES = 10 * 1024 * 1024;

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
        res.on("data", (c) => {
          body += c;
          if (Buffer.byteLength(body) > MAX_API_RESPONSE_BYTES) {
            req.destroy(new Error("metadata token response exceeded the size limit"));
          }
        });
        res.on("end", () => {
          try {
            const parsed = JSON.parse(body);
            if (res.statusCode < 200 || res.statusCode >= 300 || !parsed.access_token) {
              return reject(new Error(`metadata token request returned HTTP ${res.statusCode}`));
            }
            resolve(parsed.access_token);
          } catch {
            reject(new Error("metadata server returned a token response that did not parse"));
          }
        });
      },
    );
    req.on("error", reject);
    req.setTimeout(UPSTREAM_TIMEOUT_MS, () =>
      req.destroy(new Error(`metadata token request timed out after ${UPSTREAM_TIMEOUT_MS}ms`)));
    req.end();
  });
}

function gcloudToken() {
  return new Promise((resolve, reject) => {
    // shell:true because gcloud is a .cmd shim on Windows and will not exec directly.
    execFile("gcloud", ["auth", "print-access-token"], {
      shell: true,
      timeout: UPSTREAM_TIMEOUT_MS,
      maxBuffer: MAX_API_RESPONSE_BYTES,
    }, (err, stdout, stderr) => {
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
        res.on("data", (c) => {
          body += c;
          if (Buffer.byteLength(body) > MAX_API_RESPONSE_BYTES) {
            req.destroy(new Error("monitoring API response exceeded the size limit"));
          }
        });
        res.on("end", () => {
          let parsed;
          try {
            parsed = JSON.parse(body);
          } catch {
            return reject(new Error(`monitoring API returned non-JSON (HTTP ${res.statusCode})`));
          }
          if (res.statusCode < 200 || res.statusCode >= 300 || parsed.error) {
            return reject(new Error(
              parsed.error?.message || `monitoring API returned HTTP ${res.statusCode}`));
          }
          resolve(parsed);
        });
      },
    );
    req.on("error", reject);
    req.setTimeout(UPSTREAM_TIMEOUT_MS, () =>
      req.destroy(new Error(`monitoring API timed out after ${UPSTREAM_TIMEOUT_MS}ms`)));
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

  // Two corrections, both consequences of carrying a gauge through a distribution and reading it at a
  // percentile. The percentile interpolates WITHIN a bucket, so it never returns the recorded value:
  //
  //   a true 0    reports as the underflow bucket's UPPER bound  -> 0.5
  //   a true 1276 reports as its bucket's LOWER bound            -> 1275.5
  //
  // Measured, not theorised: the reporter logged 1276 students and 376 sections while the dashboard
  // rendered 1275.50 and 375.50. Half a student is the kind of number that makes a reader distrust
  // every other figure on the page, which is worse than the 0.5 itself.
  //
  // So: floor anything at or below the zero threshold to a real zero, then round. Rounding alone would
  // turn 0.5 into 1 and invent a phantom item in an empty queue, which is why order matters here.
  const isCount = panel.format === "int" && panel.zeroBelow !== undefined;
  const floor = (v) => {
    if (panel.zeroBelow !== undefined && v <= panel.zeroBelow) return 0;
    return isCount ? Math.round(v) : v;
  };
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

      // A stale marker asks the reader to discount a number. Past some age the number stops being a
      // discountable version of the truth and becomes an answer to a DIFFERENT QUESTION, and no caveat
      // rescues that -- so a panel may declare the age at which its value expires, and past it the
      // value is withheld rather than dressed in a warning.
      //
      // The cost panels are why. They froze on 2026-08-19 and kept rendering, and the frozen figures
      // carry labels project_id="custoking" and billing_account="018AC9-E669C1-2FC9B8": a project that
      // has since been DELETED, billed to an account this one does not run on (it runs on 014C0A). A
      // panel headed "Spend, month to date" was reporting a dead project's bill on somebody else's
      // account. "Last seen 2 days ago" reads as "roughly right, slightly behind", which is precisely
      // the wrong inference.
      //
      // Withholding is also self-repairing. The hourly collector still runs and still fails against the
      // broken billing export; if Google ever fixes it, fresh points arrive and the panels light up on
      // their own with no code change.
      const ageHours = (end.getTime() - new Date(last.t).getTime()) / 3_600_000;
      if (panel.expiresAfterHours && ageHours > panel.expiresAfterHours) {
        return { ...base, state: "expired", lastSeen: last.t };
      }
      return { ...base, state: "stale", lastSeen: last.t, value: last.v };
    } catch (err) {
      return { ...base, state: "failed", error: err.message };
    }
  }

  // How a series collapses to one number depends on WHAT IT IS, and treating them alike was a real bug:
  // two pages open side by side showed different values for the same metric.
  //
  // A DELTA counter is a count PER BUCKET. Taking the last bucket of a three-hour window means showing
  // the last three minutes under a "last 3 hours" label, and every page load samples a different
  // bucket -- so the number moved on each refresh and disagreed between the two dashboards. Counters
  // must be SUMMED across the window to mean what the label says.
  //
  // A gauge is a level, so the most recent reading is the answer and summing would be nonsense: adding
  // up sixty consecutive readings of "1276 students" gives 76,560.
  const isCounter = panel.aligner === "ALIGN_DELTA";
  const collapse = (pts) =>
    isCounter ? pts.reduce((a, p) => a + p.v, 0) : pts[pts.length - 1].v;

  // Carried per series so the client renders the same number the headline is built from, rather than
  // recomputing from raw points and drifting away from it again.
  for (const entry of series) entry.value = collapse(entry.points);

  const value = panel.kind === "breakdown"
    ? series.reduce((a, entry) => a + entry.value, 0)
    : Math.max(...series.map((entry) => entry.value));

  const severity = panel.severity ? panel.severity(value) : null;
  return {
    ...base,
    state: value === 0 ? "zero" : "live",
    value,
    severity,
    series: series.sort((a, b) => b.value - a.value).slice(0, 12),
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
        filter: `metric.type="${input.metric}" AND ${COST_FILTER_EXCLUDE_SELF}`,
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

// Auth is required unless explicitly disabled for local use. Defaulting to REQUIRED matters: a
// misconfigured deployment must refuse people, never admit them. `DASHBOARD_AUTH=off` is only for
// running on a workstation, where the process is already behind the machine's own login.
const AUTH_REQUIRED = process.env.DASHBOARD_AUTH !== "off";

// Where sign-in is allowed to land. An allowlist rather than sanitisation, because this replaced a
// sanitiser that did not work and carried a comment claiming it did.
//
// The old line was:
//
//   (state || "/owner").replace(/^[^/]*/, "") || "/owner"
//
// with a note saying the result was "forced to a relative path so a crafted state cannot turn this
// into an open redirect". It does not. The pattern strips characters up to the FIRST slash, so
// "https://evil.com/x" loses only "https:" and yields "//evil.com/x" -- a scheme-relative URL, which
// every browser follows off-site. CodeQL flagged it (js/server-side-unvalidated-url-redirection) and
// CodeQL was right.
//
// Exploiting it needs a callback that reaches the redirect, so it is not trivially reachable, but the
// value of an open redirect is that the link starts on a domain the victim trusts -- which this one
// does. Not worth reasoning about when the real destination set has two members in it.
//
// So: no parsing, no stripping, no cleverness. Match against the pages that exist, or go to /owner.
// A destination that is not one of these is a bug or an attack, and both want the same answer.
const SIGN_IN_DESTINATIONS = new Set(["/owner", "/ops"]);

function safeDest(candidate) {
  return SIGN_IN_DESTINATIONS.has(candidate) ? candidate : "/owner";
}

// Pinned in production, derived only as a local fallback.
//
// CodeQL did not flag this one, but it is the same family as the open redirect above and was found
// while fixing it. redirect_uri was built from the Host and X-Forwarded-Proto headers, both of which
// the client sends. A spoofed Host would put an attacker's origin into the Google sign-in URL this
// server hands out.
//
// It is not currently exploitable, because Google refuses any redirect_uri that is not registered on
// the OAuth client -- but that is someone else's configuration doing the work, and it is one console
// edit away from not doing it. An explicit value costs one environment variable.
//
// Unset, it falls back to the headers so the same code runs on a workstation. The x-forwarded-proto
// read stays in that path: Cloud Run terminates TLS upstream, so an inbound request looks like plain
// http, and building an http:// callback produces a confusing OAuth mismatch rather than an obvious
// scheme problem.
const PUBLIC_URL = (process.env.DASHBOARD_PUBLIC_URL || "").replace(/\/+$/, "");

function externalOrigin(req) {
  if (PUBLIC_URL) return PUBLIC_URL;
  const proto = req.headers["x-forwarded-proto"] || "http";
  return `${proto}://${req.headers.host}`;
}

export const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);

  if (url.pathname === "/auth/logout") {
    auth.revokeSession(req.headers.cookie);
    res.writeHead(303, {
      "set-cookie": [auth.clearSessionCookie, auth.clearOAuthStateCookie],
      "cache-control": "no-store",
      location: "/owner",
    });
    return res.end();
  }

  if (AUTH_REQUIRED) {
    const redirectUri = `${externalOrigin(req)}/auth/callback`;

    if (!auth.configured) {
      res.writeHead(503, { "content-type": "text/plain" });
      return res.end("Dashboard auth is not configured: OAUTH_CLIENT_ID and OAUTH_CLIENT_SECRET are unset.");
    }

    if (url.pathname === "/auth/callback") {
      try {
        const code = url.searchParams.get("code");
        if (!code) throw new Error("no code");
        const oauth = auth.consumeOAuth(req.headers.cookie, url.searchParams.get("state"));
        const tokens = await auth.exchangeCode(code, redirectUri, oauth.codeVerifier);
        const email = await auth.verifyIdToken(tokens.id_token, oauth.nonce);
        if (!auth.isAllowed(email)) {
          res.writeHead(403, {
            "content-type": "text/html; charset=utf-8",
            "set-cookie": auth.clearOAuthStateCookie,
            "cache-control": "no-store",
          });
          return res.end(`<p style="font:16px system-ui;padding:2rem">
            <strong>${email}</strong> is not on the allowlist for this dashboard.<br>
            Ask the owner to add you, then sign in again.</p>`);
        }
        const dest = safeDest(oauth.destination);
        res.writeHead(302, {
          "set-cookie": [
            `ck_session=${auth.makeSession(email)}; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=43200`,
            auth.clearOAuthStateCookie,
          ],
          "cache-control": "no-store",
          location: dest,
        });
        return res.end();
      } catch (err) {
        res.writeHead(400, {
          "content-type": "text/plain",
          "set-cookie": auth.clearOAuthStateCookie,
          "cache-control": "no-store",
        });
        return res.end(`Sign-in failed: ${err.message}`);
      }
    }

    // CodeQL flags this as js/user-controlled-bypass: a condition guarding a sensitive action, driven
    // by a user-provided value. The value is a cookie, and a session cookie is user-provided by
    // definition -- the query cannot see that readSession verifies an HMAC-SHA256 signature with
    // timingSafeEqual before returning anything, so signature checks read to it as no check at all.
    // Reviewed and dismissed on the alert rather than worked around here; see auth.mjs.
    if (!auth.readSession(req.headers.cookie)) {
      // Constrained on the way OUT as well as on the way back. Putting an arbitrary pathname into
      // state and validating only on return means the check has exactly one place to be wrong in.
      const oauth = auth.beginOAuth(safeDest(url.pathname));
      res.writeHead(302, {
        "set-cookie": oauth.cookie,
        "cache-control": "no-store",
        location: auth.authUrl(redirectUri, oauth.state, oauth.codeChallenge),
      });
      return res.end();
    }
  }

  // Two audiences, one document. The page reads its own path and asks the API for that subset, which
  // keeps a single template rather than two that drift apart.
  if (url.pathname === "/owner" || url.pathname === "/ops" ||
      url.pathname === "/" || url.pathname === "/index.html") {
    const html = fs.readFileSync(path.join(HERE, "public", "index.html"), "utf8");
    res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
    return res.end(html);
  }

  if (url.pathname === "/api/snapshot") {
    const windowMinutes = Math.min(10080, Math.max(15, Number(url.searchParams.get("window") || 180)));
    try {
      const token = await accessToken();
      const audience = url.searchParams.get("audience");
      const allowed = AUDIENCE[audience];
      // An unknown or absent audience returns everything rather than nothing. A dashboard that renders
      // empty because of a typo in a query string looks exactly like a dashboard whose backend is down.
      const selected = allowed ? PANELS.filter((p) => allowed.includes(p.id)) : PANELS;

      const [panels, cost] = await Promise.all([
        Promise.all(selected.map((p) => fetchPanel(p, token, windowMinutes))),
        estimateCost(token).catch(() => null),
      ]);
      res.writeHead(200, { "content-type": "application/json", "cache-control": "no-store" });
      return res.end(JSON.stringify({
        project: PROJECT,
        windowMinutes,
        generatedAt: new Date().toISOString(),
        groups: GROUPS,
        audience: url.searchParams.get("audience") || "all",
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

const IS_MAIN = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (IS_MAIN) {
  server.listen(PORT, () => {
    console.log(`live dashboard  →  http://localhost:${PORT}`);
    console.log(`project ${PROJECT}   auth: ${ON_CLOUD_RUN ? "metadata server" : "gcloud"}`);
  });
}
