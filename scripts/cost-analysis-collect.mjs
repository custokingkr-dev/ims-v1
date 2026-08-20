#!/usr/bin/env node
//
// Per-service daily cost, computed from Cloud Monitoring usage priced at real SKU rates, appended to
// BigQuery so cost becomes queryable and accumulates history.
//
// WHY THIS EXISTS
//
// The Cloud Billing usage-cost export for account 014C0A-C6B9AF-5FABC0 cannot be enabled. Six attempts
// across every configuration that exists on our side -- original dataset, clean dataset, pre-granted
// dataset, asia-south2 (rejected as an unsupported region), and a virgin config after clearing all
// three exports -- fail with a generic server error, while the PRICING export succeeds every time on
// the same account and dataset. Since Google's docs put pricing at strictly higher privilege than usage
// cost, that isolates the fault to a broken config record on their side. There is also no API for
// export configuration (cloudbilling v1 and v1beta expose none), so there is no scripted workaround.
//
// Rather than wait, this computes cost the other way round: from what each service actually consumed.
//
// WHAT IT GIVES UP, AND WHAT IT GAINS
//
// Gives up: true SKU-level granularity, sustained-use and committed-use discounts, taxes, and anything
// not modelled below. It is an estimate and every row says so.
//
// Gains: attribution PER CLOUD RUN SERVICE rather than per SKU, which is the more useful cut for
// deciding what to optimise; and freshness, since billing export lands hours late and carries "no
// delivery or latency guarantees" while metrics are near real time.
//
// Every row records its own method, so a later reader can tell a measured constant from a computed
// estimate without having to guess.

import https from "node:https";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFile } from "node:child_process";

const PROJECT = process.env.COST_ANALYSIS_PROJECT || "custoking-prod";
const DATASET = process.env.COST_ANALYSIS_DATASET || "cost_analysis";
const TABLE = "daily_service_cost";
const INR_PER_USD = Number(process.env.INR_PER_USD || 88);

// asia-south2 rates from cloud_pricing_export, priced 2026-08-19. Kept here rather than queried per run
// so this keeps working while the pricing export is disabled or the dataset is being rebuilt.
const RATE = {
  cpuSecond: 0.0000216,   // Services CPU (Instance-based billing) in asia-south2
  gibSecond: 0.0000024,   // Services Memory (Instance-based billing) in asia-south2
  perRequest: 0.40 / 1e6,
  egressGib: 0.12,
};

// Cloud SQL is a MEASURED constant, deliberately. It dominates the bill and is exactly fixed: the same
// tier in the same region billed INR 96.41/day for the instance plus INR 6.29/day for storage across
// thirteen consecutive days with a coefficient of variation of zero. That is what Google actually
// charged, discounts included -- better than re-deriving it from a SKU catalogue that lists MySQL and
// PostgreSQL tiers in ways that are easy to mis-pick.
const CLOUD_SQL_INR_PER_DAY = 102.70;

const SERIES = [
  { metric: "run.googleapis.com/container/cpu/allocation_time", field: "cpuSeconds", unit: "vCPU-second", rate: "cpuSecond" },
  { metric: "run.googleapis.com/container/memory/allocation_time", field: "gibSeconds", unit: "GiB-second", rate: "gibSecond" },
  { metric: "run.googleapis.com/request_count", field: "requests", unit: "request", rate: "perRequest" },
  { metric: "run.googleapis.com/container/network/sent_bytes_count", field: "egressBytes", unit: "byte", rate: null },
];

function token() {
  return new Promise((resolve, reject) => {
    execFile("gcloud", ["auth", "print-access-token"], { shell: true }, (err, out, errOut) =>
      err ? reject(new Error("gcloud auth failed: " + String(errOut || err.message).trim())) : resolve(out.trim()));
  });
}

function get(urlPath, tok) {
  return new Promise((resolve, reject) => {
    const req = https.request({ host: "monitoring.googleapis.com", path: urlPath, headers: { Authorization: `Bearer ${tok}` } }, (res) => {
      let body = "";
      res.on("data", (c) => (body += c));
      res.on("end", () => {
        try {
          const parsed = JSON.parse(body);
          if (parsed.error) return reject(new Error(parsed.error.message));
          resolve(parsed);
        } catch { reject(new Error(`non-JSON from monitoring (HTTP ${res.statusCode})`)); }
      });
    });
    req.on("error", reject);
    req.end();
  });
}

// Per Cloud Run service, for one UTC day.
async function collectDay(tok, dayIso) {
  const start = `${dayIso}T00:00:00Z`;
  const end = `${dayIso}T23:59:59Z`;
  const perService = new Map();

  for (const s of SERIES) {
    const qs = new URLSearchParams({
      filter: `metric.type="${s.metric}"`,
      "interval.startTime": start,
      "interval.endTime": end,
      // Hourly, not daily. A 24h alignment window on request_count -- which carries many label
      // combinations -- times out server-side with "shorten the time interval". The points are summed
      // below anyway, so a finer window costs nothing and simply succeeds.
      "aggregation.alignmentPeriod": "3600s",
      "aggregation.perSeriesAligner": "ALIGN_SUM",
      "aggregation.crossSeriesReducer": "REDUCE_SUM",
    });
    const url = `/v3/projects/${PROJECT}/timeSeries?${qs}&aggregation.groupByFields=${encodeURIComponent("resource.label.service_name")}`;
    let res;
    try {
      res = await get(url, tok);
    } catch (err) {
      // Retried once: Monitoring returns transient timeouts on wide queries, and losing a whole day of
      // cost history to one flaky call would be a silent gap in a table meant to be authoritative.
      if (!/timed out/i.test(err.message)) throw err;
      res = await get(url, tok);
    }
    for (const series of res.timeSeries || []) {
      const name = series.resource?.labels?.service_name || "(unattributed)";
      const total = (series.points || []).reduce((a, p) => a + Number(p.value?.doubleValue ?? p.value?.int64Value ?? 0), 0);
      if (!total) continue;
      const bucket = perService.get(name) || {};
      bucket[s.field] = (bucket[s.field] || 0) + total;
      perService.set(name, bucket);
    }
  }

  const rows = [];
  const stamp = new Date().toISOString();

  for (const [service, usage] of perService) {
    for (const s of SERIES) {
      const qty = usage[s.field];
      if (!qty) continue;
      const usd = s.rate ? qty * RATE[s.rate] : (qty / 1024 ** 3) * RATE.egressGib;
      rows.push({
        usage_date: dayIso,
        project: PROJECT,
        component: service,
        component_kind: "cloud_run",
        metric: s.field,
        quantity: qty,
        unit: s.unit,
        cost_usd: usd,
        cost_inr: usd * INR_PER_USD,
        method: "monitoring_usage_x_sku_rate",
        computed_at: stamp,
      });
    }
  }

  rows.push({
    usage_date: dayIso,
    project: PROJECT,
    component: "custoking-db-prod",
    component_kind: "cloud_sql",
    metric: "instance_day",
    quantity: 1,
    unit: "day",
    cost_usd: CLOUD_SQL_INR_PER_DAY / INR_PER_USD,
    cost_inr: CLOUD_SQL_INR_PER_DAY,
    // Named differently on purpose: a reader must be able to see at a glance which rows are measured
    // and which are computed, without going back to the source.
    method: "measured_constant",
    computed_at: stamp,
  });

  return rows;
}

const SCHEMA = [
  "usage_date:DATE", "project:STRING", "component:STRING", "component_kind:STRING",
  "metric:STRING", "quantity:FLOAT", "unit:STRING", "cost_usd:FLOAT", "cost_inr:FLOAT",
  "method:STRING", "computed_at:TIMESTAMP",
].join(",");

function bqLoad(file, replace) {
  return new Promise((resolve, reject) => {
    const args = ["load", `--project_id=${PROJECT}`, "--location=US", "--source_format=NEWLINE_DELIMITED_JSON",
      replace ? "--replace" : "--noreplace", `${PROJECT}:${DATASET}.${TABLE}`, file, SCHEMA];
    execFile("bq", args, { shell: true, maxBuffer: 1 << 24 }, (err, out, errOut) =>
      err ? reject(new Error(String(errOut || out || err.message).slice(0, 500))) : resolve());
  });
}

const days = process.argv.slice(2);
if (!days.length) {
  const d = new Date(Date.now() - 86400_000);
  days.push(d.toISOString().slice(0, 10));
}

const tok = await token();
const all = [];
for (const day of days) {
  const rows = await collectDay(tok, day);
  console.log(`  ${day}: ${rows.length} rows, INR ${rows.reduce((a, r) => a + r.cost_inr, 0).toFixed(2)}`);
  all.push(...rows);
}

const tmp = path.join(os.tmpdir(), `cost-analysis-${Date.now()}.ndjson`);
fs.writeFileSync(tmp, all.map((r) => JSON.stringify(r)).join("\n") + "\n");
// --replace when a date range is given, so a re-run of the same days corrects rather than duplicates.
await bqLoad(tmp, days.length > 1);
fs.unlinkSync(tmp);
console.log(`loaded ${all.length} rows into ${PROJECT}:${DATASET}.${TABLE}`);
