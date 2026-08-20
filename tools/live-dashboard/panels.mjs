// Panel definitions for the live dashboard.
//
// Everything here is a real metric that exists in custoking-prod. Nothing is aspirational: a panel whose
// metric has never emitted renders as an explicit "never reported" state rather than as an empty chart,
// because an empty chart that looks healthy is the exact failure this project has hit repeatedly.
//
// A note on reading gauges. Five of the log-based metrics carry a GAUGE through a DISTRIBUTION, because
// a log-based metric can only be a counter or a distribution. They must be read at ALIGN_PERCENTILE_50:
// a percentile is interpolated within its bucket, so reading these at p99 reports the top of whichever
// bucket the value landed in. That is not a hypothetical -- it rendered a true 5 as 9.95.

const ENV = process.env.DASHBOARD_ENV || "prod";
const L = (name) => `logging.googleapis.com/user/custoking/${ENV}/${name}`;
const C = (name) => `custom.googleapis.com/custoking/${name}`;
const svc = (name) => `custoking-${name}-${ENV}`;

// Distribution-carried gauges: median, then the max across emitting instances.
//
// `zeroBelow` is the other half of the bucket story. The bounds start at 0.5 so that an integer N falls
// mid-bucket and a percentile recovers it -- but that puts a true ZERO in the underflow bucket (-inf, 0.5),
// and a percentile over an unbounded bucket reports its upper bound. So an empty queue reads as 0.5, not 0.
// Measured, not assumed: with every queue drained, all five async gauges returned exactly 0.5.
//
// Distributions cannot represent an exact gauge, so this is a floor rather than a fix. For a non-negative
// integer gauge the underflow bucket contains only zero, which makes the mapping unambiguous.
const GAUGE = { aligner: "ALIGN_PERCENTILE_50", reducer: "REDUCE_MAX", zeroBelow: 0.5 };
// Counters: per-second rate, summed across series.
const RATE = { aligner: "ALIGN_RATE", reducer: "REDUCE_SUM" };
// Counters where the absolute count over the window is what matters, not the rate.
const COUNT = { aligner: "ALIGN_DELTA", reducer: "REDUCE_SUM" };

export const GROUPS = [
  { id: "now", title: "Right now", blurb: "Who is using the product this minute, and is it answering them." },
  { id: "usage", title: "What it is used for", blurb: "Which parts of the product carry real traffic." },
  { id: "async", title: "Work in flight", blurb: "Queues that fail quietly. A backlog here is invisible to users until it is not." },
  { id: "platform", title: "The business", blurb: "Schools, students and reach. Inventory versus actual use." },
  { id: "infra", title: "What it runs on", blurb: "Capacity and cold starts — the parts that cost money." },
  { id: "money", title: "What it costs", blurb: "Billing export, several hours behind by nature. Spend, not spending." },
];

export const PANELS = [
  // ---------------------------------------------------------------- Right now
  {
    id: "active_users",
    group: "now",
    title: "Active users",
    note: "Distinct people holding an unexpired session. One person on two devices counts once.",
    filter: `metric.type="${L("session_active_users")}"`,
    ...GAUGE,
    kind: "scorecard",
    format: "int",
    emphasis: true,
  },
  {
    id: "logins_recent",
    group: "now",
    title: "Sign-ins, last 15 min",
    note: "The single best liveness signal you have. Zero during school hours is a real incident; zero at 03:00 is Tuesday.",
    filter: `metric.type="${L("session_logins_recent")}"`,
    ...GAUGE,
    kind: "scorecard",
    format: "int",
  },
  {
    id: "active_sessions",
    group: "now",
    title: "Open sessions",
    note: "Sessions, not people. The gap against active users is multi-device use.",
    filter: `metric.type="${L("session_active_sessions")}"`,
    ...GAUGE,
    kind: "scorecard",
    format: "int",
  },
  {
    id: "errors_5xx",
    group: "now",
    title: "Server errors",
    note: "5xx only. A 4xx is usually a client sending something invalid; a 5xx is the product failing someone mid-task.",
    filter: [`metric.type="${L("gateway_requests_by_feature")}"`, 'metric.label.status_class="5"'].join(" AND "),
    ...COUNT,
    kind: "scorecard",
    format: "int",
    severity: (v) => (v > 0 ? "bad" : "ok"),
    // No series exists until the first 5xx ever occurs. Absence here means "none have happened", which is
    // the outcome we want, not a dead metric -- so it must not render as one.
    absentMeansZero: true,
  },

  // ---------------------------------------------------------------- Usage
  {
    id: "requests_by_feature",
    group: "usage",
    title: "Requests by feature",
    note: "A flat line on a feature you built is more actionable than anything on an infrastructure chart.",
    filter: `metric.type="${L("gateway_requests_by_feature")}"`,
    ...COUNT,
    groupBy: ["metric.label.feature"],
    kind: "breakdown",
    format: "int",
  },
  {
    id: "latency_by_feature",
    group: "usage",
    title: "Latency by feature, p95",
    note: "Read at p95 deliberately — this one really is a distribution, not a gauge in disguise.",
    filter: `metric.type="${L("gateway_latency_by_feature")}"`,
    aligner: "ALIGN_PERCENTILE_95",
    reducer: "REDUCE_MAX",
    groupBy: ["metric.label.feature"],
    kind: "breakdown",
    format: "ms",
  },
  {
    id: "requests_by_tenant",
    group: "usage",
    title: "Requests by school",
    note: "The question every other number here was unable to answer. Renders as 'never reported' until a release carries the gateway change that adds schoolId to the request log.",
    filter: `metric.type="${L("gateway_requests_by_tenant")}"`,
    ...COUNT,
    groupBy: ["metric.label.school_id"],
    kind: "breakdown",
    format: "int",
  },
  {
    id: "requests_total",
    group: "usage",
    title: "Total request volume",
    note: "Includes uptime-probe traffic, which at this scale is most of it overnight.",
    filter: `metric.type="run.googleapis.com/request_count"`,
    ...RATE,
    kind: "series",
    format: "rate",
  },

  // ---------------------------------------------------------------- Async
  {
    id: "outbox_pending",
    group: "async",
    title: "Outbox pending",
    note: "Steady-state should hover near zero. A rising floor means the relay is behind, not busy.",
    filter: `metric.type="${L("outbox_pending_count")}"`,
    ...GAUGE,
    kind: "scorecard",
    format: "int",
  },
  {
    id: "outbox_dead",
    group: "async",
    title: "Outbox dead letters",
    note: "Anything above zero is a message that will never be delivered without a human.",
    filter: `metric.type="${L("outbox_dead_letter_count")}"`,
    ...GAUGE,
    kind: "scorecard",
    format: "int",
    severity: (v) => (v > 0 ? "bad" : "ok"),
  },
  {
    id: "outbox_age",
    group: "async",
    title: "Oldest pending",
    note: "Age beats depth as a backlog signal: a queue of 200 draining in seconds is fine, one message stuck for an hour is not.",
    filter: `metric.type="${L("outbox_oldest_pending_age_seconds")}"`,
    ...GAUGE,
    kind: "scorecard",
    format: "duration",
    severity: (v) => (v > 900 ? "bad" : v > 120 ? "warn" : "ok"),
  },
  {
    id: "inbox_backlog",
    group: "async",
    title: "Notification backlog",
    note: "Unprocessed inbound events on the platform service.",
    filter: `metric.type="${L("notification_inbox_backlog_count")}"`,
    ...GAUGE,
    kind: "scorecard",
    format: "int",
  },
  {
    id: "inbox_dead",
    group: "async",
    title: "Notification dead letters",
    note: "Same rule as the outbox: above zero needs a person.",
    filter: `metric.type="${L("notification_inbox_dead_letter_count")}"`,
    ...GAUGE,
    kind: "scorecard",
    format: "int",
    severity: (v) => (v > 0 ? "bad" : "ok"),
  },

  // ---------------------------------------------------------------- Platform
  {
    id: "schools",
    group: "platform",
    title: "Schools provisioned",
    note: "Inventory. Counted cross-tenant with an RLS bypass — without it this reads a plausible, wrong zero.",
    filter: `metric.type="${L("platform_schools")}"`,
    ...GAUGE,
    kind: "scorecard",
    format: "int",
  },
  {
    id: "schools_live",
    group: "platform",
    title: "Schools holding students",
    note: "Reach. The gap against provisioned is sold-but-not-onboarded, and it is the number worth watching.",
    filter: `metric.type="${L("platform_schools_with_students")}"`,
    ...GAUGE,
    kind: "scorecard",
    format: "int",
    emphasis: true,
  },
  {
    id: "students",
    group: "platform",
    title: "Students",
    note: "Live records, excluding soft-deleted.",
    filter: `metric.type="${L("platform_students")}"`,
    ...GAUGE,
    kind: "scorecard",
    format: "int",
  },
  {
    id: "sections",
    group: "platform",
    title: "Class sections",
    note: "Configured sections across all tenants.",
    filter: `metric.type="${L("platform_sections")}"`,
    ...GAUGE,
    kind: "scorecard",
    format: "int",
  },

  // ---------------------------------------------------------------- Infra
  {
    id: "instances",
    group: "infra",
    title: "Instances running",
    note: "Billed by instance-time, so this line is the shape of your Cloud Run bill.",
    filter: `metric.type="run.googleapis.com/container/instance_count"`,
    aligner: "ALIGN_MEAN",
    reducer: "REDUCE_SUM",
    kind: "series",
    format: "float1",
  },
  {
    id: "cold_starts",
    group: "infra",
    title: "Cold start, p95",
    note: "With min-instances at zero this is what a first user after a quiet period actually waits.",
    filter: `metric.type="run.googleapis.com/container/startup_latencies"`,
    aligner: "ALIGN_PERCENTILE_95",
    reducer: "REDUCE_MAX",
    kind: "scorecard",
    format: "ms",
  },
  {
    id: "sql_cpu",
    group: "infra",
    title: "Database CPU",
    note: "Expect a nightly excursion at 02:00 IST — that is the scheduled backup, not a problem.",
    filter: `metric.type="cloudsql.googleapis.com/database/cpu/utilization"`,
    aligner: "ALIGN_MEAN",
    reducer: "REDUCE_MAX",
    kind: "series",
    format: "pct",
  },
  {
    id: "sql_conns",
    group: "infra",
    title: "Database connections",
    note: "On a shared-core tier with seven services, this is a pool-sizing signal, not a capacity one.",
    filter: `metric.type="cloudsql.googleapis.com/database/postgresql/num_backends"`,
    aligner: "ALIGN_MEAN",
    reducer: "REDUCE_SUM",
    kind: "scorecard",
    format: "int",
  },

  // ---------------------------------------------------------------- Money
  {
    id: "cost_mtd",
    group: "money",
    title: "Spend, month to date",
    note: "Gross, not net. Net reads near zero while trial credit lasts and hides what becomes payable.",
    filter: `metric.type="${C("cost/gross_month_to_date")}"`,
    aligner: "ALIGN_MEAN",
    reducer: "REDUCE_SUM",
    kind: "scorecard",
    format: "inr",
    emphasis: true,
  },
  {
    id: "cost_yesterday",
    group: "money",
    title: "Spend yesterday",
    note: "Billing export lands hours after the usage it describes. This answers what you spent, never what you are spending.",
    filter: `metric.type="${C("cost/gross_yesterday")}"`,
    aligner: "ALIGN_MEAN",
    reducer: "REDUCE_SUM",
    kind: "scorecard",
    format: "inr",
  },
  {
    id: "cost_net_mtd",
    group: "money",
    title: "Net, month to date",
    note: "After trial credit. The distance between this and gross is your October cliff.",
    filter: `metric.type="${C("cost/net_month_to_date")}"`,
    aligner: "ALIGN_MEAN",
    reducer: "REDUCE_SUM",
    kind: "scorecard",
    format: "inr",
  },
];

export const SERVICES = [
  "identity-service", "school-core-service", "operations-service",
  "platform-service", "billing-service", "api-gateway", "frontend",
].map(svc);
