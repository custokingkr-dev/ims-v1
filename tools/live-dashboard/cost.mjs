// Live cost estimation from resource usage, independent of the billing export.
//
// WHY THIS EXISTS
//
// The Cloud Billing BigQuery export for account 014C0A-C6B9AF-5FABC0 cannot be enabled: it registered
// on 2026-08-19, delivered zero rows, and now fails to save with a server-side error against three
// different target datasets. Google owns that fault. Until they fix it there is no billing data for
// this project at all -- no spend panels, no attribution, no unit economics.
//
// So this computes cost the other way round: from what the resources actually did, priced with real
// SKU rates. That is not merely a substitute. Billing export lands HOURS after the usage it describes
// and Google states it carries "no delivery or latency guarantees", so it can never answer "what are we
// spending right now" -- only "what did we spend". Resource metrics are near real time, which is the
// question an operator actually has.
//
// WHAT IT IS NOT
//
// An estimate, and it should always be labelled one. It omits anything not modelled below, ignores
// sustained-use and committed-use discounts, and applies a fixed USD->INR rate. Treat it as a
// run-rate indicator accurate to a few percent, not as an invoice. The authority is always the
// console's billing report.

// Rates from `cloud_pricing_export` for asia-south2, priced 2026-08-19. Fetched from BigQuery rather
// than a blog: the memory rate in particular was only ever a derived guess in the research, and the
// real SKU confirms it exactly.
const USD = {
  cpuSecond: 0.0000216, // "Services CPU (Instance-based billing) in asia-south2"
  gibSecond: 0.0000024, // "Services Memory (Instance-based billing) in asia-south2"
  perMillionRequests: 0.40,
  egressGib: 0.12, // internet egress, approximate; small at this scale
};

// Cloud SQL is deliberately a MEASURED constant, not a computed one.
//
// It is ~81% of the bill and it is exactly fixed: the same instance tier in the same region billed
// INR 96.41/day for the instance and INR 6.29/day for storage across thirteen consecutive days, with a
// coefficient of variation of zero. A measured figure beats a derived one here because it is what
// Google actually charged, discounts included -- and because the SKU catalogue lists MySQL and
// PostgreSQL tiers separately in ways that are easy to mis-pick.
const INR_PER_DAY_CLOUD_SQL = 102.70;

// Stated explicitly so a reader can re-derive every number here. Not fetched live: an FX lookup would
// add a network dependency and a failure mode to a panel whose whole point is a rough run-rate.
const INR_PER_USD = 88;

// Exported so server.mjs appends it to every cost query. Without it the dashboard's own CPU and
// memory land in the estimate, so looking at the spend figure would increase the spend figure.
export const COST_FILTER_EXCLUDE_SELF =
  'resource.label.service_name != monitoring.regex.full_match(".*-dashboard-.*")';

export const COST_INPUTS = [
  { key: "cpuSeconds", metric: "run.googleapis.com/container/cpu/allocation_time", aligner: "ALIGN_SUM" },
  { key: "gibSeconds", metric: "run.googleapis.com/container/memory/allocation_time", aligner: "ALIGN_SUM" },
  { key: "requests", metric: "run.googleapis.com/request_count", aligner: "ALIGN_SUM" },
  { key: "egressBytes", metric: "run.googleapis.com/container/network/sent_bytes_count", aligner: "ALIGN_SUM" },
];

export function estimateDailyInr(totals) {
  const cloudRunUsd =
    (totals.cpuSeconds || 0) * USD.cpuSecond +
    (totals.gibSeconds || 0) * USD.gibSecond +
    ((totals.requests || 0) / 1_000_000) * USD.perMillionRequests +
    ((totals.egressBytes || 0) / 1024 ** 3) * USD.egressGib;

  const cloudRunInr = cloudRunUsd * INR_PER_USD;

  return {
    cloudRunInr,
    cloudSqlInr: INR_PER_DAY_CLOUD_SQL,
    totalInr: cloudRunInr + INR_PER_DAY_CLOUD_SQL,
    // The share is the interesting part, not the total. A fixed cost that dominates means growth is
    // nearly free and the floor is the whole problem -- which is exactly what the unit-economics work
    // concluded, and it is worth showing rather than restating.
    fixedSharePct: (INR_PER_DAY_CLOUD_SQL / (cloudRunInr + INR_PER_DAY_CLOUD_SQL)) * 100,
  };
}
