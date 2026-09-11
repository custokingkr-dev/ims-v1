# Custoking Observability Terraform

This Terraform root manages the GCP-native observability layer for one Custoking
environment in project `custoking`:

- Cloud Monitoring dashboards for the 5 domain services plus `api-gateway`, with dedicated product,
  engineering, live-operations, and billing/cost views.
- Optional uptime checks against `/actuator/health` or `/gateway-health`;
  private services use Cloud Run revision targets with Monitoring service-agent
  OIDC.
- Alert policies for 5xx rate, p95 latency, max-instance saturation, uptime, async health, SLO burn rate,
  Cloud SQL CPU/memory/connections, Pub/Sub backlog age/count, authenticated Scheduler failures,
  trace-export failures, and sustained Cloud Storage growth.
- Managed operator email channels, attached only to production paging policies selected by the environment.
- An optional project-wide `asia-south2` compliance log bucket and sink with 180-day retention.
- Log-based distribution metrics for outbox and notification inbox health.
- Cloud Monitoring services and availability/latency SLOs for Cloud Run.

SLO alerting uses two paired-window policy families:

- Fast burn: `14.4x` over both `60m` and `5m`, with a `180s` retest window.
- Sustained burn: `6x` over both `360m` and `30m`, with a `300s` retest window.

Both conditions in a family must be violated. Burn-rate policies email when an
incident opens but do not email again when it recovers; recovery state remains
available in Cloud Monitoring.
- trace-writer IAM for the default Cloud Run runtime service account:
  `roles/cloudtrace.agent` for Cloud Trace exporters, plus
  `roles/telemetry.tracesWriter` and `roles/serviceusage.serviceUsageConsumer`
  for OTLP export to `telemetry.googleapis.com`.

## Production alert-noise controls

Production keeps generic per-service latency charts but sets
`enable_per_service_latency_incidents=false`. Cloud Run's service-wide metric cannot distinguish interactive
requests from intentionally long-running photo import, export, and recovery work; leaving the policies enabled
created matching frontend, gateway, and upstream incidents for the same successful request. A future path-aware
latency metric can replace this temporary incident suppression without losing the underlying telemetry.

The authenticated async Scheduler policy sums failed attempts over five minutes and opens an incident only when
the sum is greater than one. Scheduler retries remain enabled, so an isolated platform or network failure that
succeeds on retry is retained in Cloud Logging without paging both operators. Persistent target, IAM, or database
failures continue to page.

## Billing and cost reporting

`Custoking <env> - Billing & Cost` keeps authoritative and estimated cost evidence visibly separate:

- **Export available** and **source lag** show whether the BigQuery billing export can be trusted.
- Confirmed gross/net spend comes from the billing-export metric publisher and is grouped by project.
- CPU, memory, requests, billable instance time, and egress remain live per service even while billing
  export is delayed.
- Cloud SQL is shown as a usage/baseline signal; its estimated daily cost remains in BigQuery.

For detailed project/service/SKU rows, query
`<billing-export-project>.billing_export.gcp_billing_export_v1_*`. When the usage-cost export is absent,
use the existing estimated views `custoking-prod.cost_analysis.v_daily_cost` and
`custoking-prod.cost_analysis.v_service_cost`. These are not interchangeable: billing export is the
invoice-grade source, while `cost_analysis` reconstructs selected usage at pinned rates and a measured
Cloud SQL baseline. See `scripts/README-cost-analysis.md` for the model and its limitations.

The dashboard intentionally does not turn missing export data into zero spend. An unavailable or stale
export is shown as a telemetry problem, and the live usage panels continue to show which resources are
driving cost while it is repaired.

## Spend: budgets and anomaly alerts

Three files, three speeds. Read `budget.tf` before touching any number in it.

| Signal | File | Speed | What it catches |
| --- | --- | --- | --- |
| `custoking-<env>-monthly` budget | `budget.tf` | hours to a day | a month drifting: 90% / 100% / 150% of a GROSS envelope, plus a 100% forecast |
| `custoking-trial-credit-runway-to-<date>` budget (prod root only) | `budget.tf` | hours to a day | the free-trial credit being consumed faster than the expiry date -- the trial ends at the EARLIER of the two |
| `custoking-<env>-daily-spend-jump` | `spend_anomaly_alerts.tf` | ~12-24 h | one calendar day's gross spend above `daily_spend_alert_inr`; sees everything the invoice sees, including Artifact Registry egress |
| `custoking-<env>-billing-export-stale` | `spend_anomaly_alerts.tf` | 2 h | the export or the exporter job has stopped -- the only policy here that fires on ABSENCE of data |
| `custoking-<env>-cloud-run-instance-time-runaway` | `spend_anomaly_alerts.tf` | 2 h | project-wide Cloud Run instance time above `cloud_run_instance_seconds_per_hour_alert` per hour; the near-real-time signal |

**Budgets must measure GROSS.** `credit_types_treatment` defaults to `INCLUDE_ALL_CREDITS`, which on a
free-trial account is a budget that reads INR 0 forever. The first version of `budget.tf` omitted it
and both live budgets were structurally unable to fire (measured 2026-09-11: net September-to-date
was INR -0.0015 in prod). The runway budget instead nets off every credit type EXCEPT the promotion,
because the Cloud Run free tier does not consume trial credit and gross would overstate the draw by
roughly INR 500/month.

Spend signals notify `local.spend_notification_channel_ids` -- the operator emails plus
`budget_notification_channel_ids` -- regardless of `enable_alert_notifications`. Dev may not page about
health and must still be able to say it is burning money.

Applying anything in `budget.tf` needs the Cloud Billing quota project set (`USER_PROJECT_OVERRIDE`,
`GOOGLE_BILLING_PROJECT`; see State below). The runway budget's amount is read from the Console
(Billing -> Credits) -- the balance is not exposed by any API -- and its start date should match the day
it was read. Set `manage_trial_runway_budget = false` after the account is upgraded off the trial.

After applying, confirm the anomaly policies can see their metrics: query
`custom.googleapis.com/custoking/cost/gross_yesterday` and `export_lag_hours` for the last day and
require non-empty results before trusting either policy. A policy on a metric with no series is
indistinguishable from a healthy one.

## Prerequisites

Apply this after the environment has been deployed at least once. The module
reads the existing Cloud Run services to discover their generated hosts when
uptime checks are enabled.

The operator applying this root needs Monitoring, Logging, and Cloud Run read
permissions, plus permission to create alert policies, dashboards, uptime checks,
logs-based metrics, Monitoring services, and SLOs.

For authenticated uptime checks on private Cloud Run services, the Monitoring
service agent must be allowed to invoke those services. Verify the service agent:

```powershell
$projectNumber = gcloud projects describe custoking --format='value(projectNumber)'
"service-$projectNumber@gcp-sa-monitoring-notification.iam.gserviceaccount.com"
```

Terraform grants that identity `roles/run.invoker` on private services that use
authenticated uptime checks.

## Trace Export Authentication

The Spring services export OTLP traces to `telemetry.googleapis.com/v1/traces`.
That endpoint does not accept unauthenticated OTLP writes, so each Java service
packages `GcpOtlpTraceExporterAuthConfig` to add an ADC bearer token and
`x-goog-user-project` header to the OTLP HTTP exporter.

This Terraform root grants the default Cloud Run runtime service account:

- `roles/cloudtrace.agent`
- `roles/telemetry.tracesWriter`
- `roles/serviceusage.serviceUsageConsumer`

Keep `GOOGLE_CLOUD_QUOTA_PROJECT`, `GCP_PROJECT`, and `GOOGLE_CLOUD_PROJECT`
set to `custoking` in the Cloud Run environment. If logs show OTLP exporter 403
errors or `unregistered callers`, verify these IAM grants and runtime env vars
before changing application tracing code.

## State

Do not commit a backend with credentials. Use a GCS backend from a local backend
file, for example:

State lives in the bucket **inside each environment's own project**, not in a shared one:

| Environment | Bucket | Prefix |
| --- | --- | --- |
| dev | `gs://custoking-dev-terraform-state` | `observability/dev` |
| prod | `gs://custoking-prod-terraform-state` | `observability/prod` |

`gs://custoking-terraform-state` belonged to the pre-split `custoking` project and is gone. Do not use
it: while both buckets existed they each carried an `observability/` prefix, so pointing at the wrong
one did not error -- it read stale state and then planned to create resources that already existed.

There is no Application Default Credentials on the operator workstation, so the backend needs an
access token as well as the provider environment variable, and the token expires after about an hour.

```powershell
$tok = gcloud auth print-access-token
$env:GOOGLE_OAUTH_ACCESS_TOKEN = $tok
terraform -chdir=deploy/gcp/observability init -reconfigure `
  -backend-config="bucket=custoking-dev-terraform-state" `
  -backend-config="prefix=observability/dev" `
  -backend-config="access_token=$tok"
```

Anything touching Cloud Billing (the budget resources) additionally needs
`$env:USER_PROJECT_OVERRIDE = "true"` and `$env:GOOGLE_BILLING_PROJECT = "<project>"`, or it fails with
a 403 naming an unrelated project number.

## Plan and Apply

Uptime checks are disabled by default while the project is in a cost-controlled
shutdown posture. To recreate uptime checks after an environment is restored,
pass `-var="enable_uptime_checks=true"`. Keep `uptime_period=900s` unless the
extra probe volume from five-minute checks is intentional.

```powershell
terraform -chdir=deploy/gcp/observability init -reconfigure `
  -backend-config="bucket=custoking-dev-terraform-state" `
  -backend-config="prefix=observability/dev" `
  -backend-config="access_token=$(gcloud auth print-access-token)"
terraform -chdir=deploy/gcp/observability plan -var-file=custoking-dev.tfvars
terraform -chdir=deploy/gcp/observability apply -var="env=dev"
```

For production, pass `-var="env=prod"` and production notification channels:

```powershell
terraform -chdir=deploy/gcp/observability plan `
  -var="env=prod" `
  -var="enable_uptime_checks=true" `
  -var='notification_email_addresses={primary="operator@example.com"}' `
  -var="manage_compliance_logging=true"
```

Only the production state may manage the single project-wide compliance bucket.
Existing externally managed channels can still be supplied through
`notification_channel_ids`.

The Cloud SQL memory policy intentionally uses the `Usage` component of
`cloudsql.googleapis.com/database/memory/components`, expressed as a percentage,
instead of `database/memory/utilization`. On a shared-core instance, the latter
can stay at 100% when server RAM usage equals the quota even though process usage
is low and most memory is free or cache. The default threshold is 90%, matching
Google Cloud's OOM-risk guidance. Storage growth defaults to 100 GiB sustained
for a full day; override `storage_total_bytes_threshold` from the approved
retention and commercial limit rather than treating that default as a budget cap.

## Planning Without Cloud Run Discovery

If Cloud Run data-source reads are not available in the current environment,
disable discovery and provide hosts explicitly:

```powershell
terraform -chdir=deploy/gcp/observability plan `
  -var="env=dev" `
  -var="discover_cloud_run_urls=false" `
  -var='service_hosts={api-gateway="custoking-api-gateway-dev-abc.a.run.app"}'
```

Services without a discoverable or supplied host skip uptime check creation. All
dashboards, alert policies, log-based metrics, and SLOs still plan from the
env-suffixed Cloud Run service names.

## Structured Async Health Logs

The async-health log metrics expect periodic structured JSON health logs with
these fields when the values are available:

- `jsonPayload.health.outbox.pendingCount`
- `jsonPayload.health.outbox.deadLetterCount`
- `jsonPayload.health.outbox.oldestPendingAgeSeconds`
- `jsonPayload.health.notificationInbox.backlogCount`
- `jsonPayload.health.notificationInbox.deadLetterCount`

These are distribution metrics, so dashboards and alerts use p95/max alignment
over each five-minute window rather than treating the extracted log values as
true gauges.

## Attendance DATA-01 Growth Monitoring

School-core emits `jsonPayload.health.attendanceStorage` with approximate rows, relation/index bytes, and
interval scan counters. The DATA-01 log metrics and alert policies are intentionally opt-in:

```powershell
terraform -chdir=deploy/gcp/observability plan `
  -var-file=custoking-dev.tfvars `
  -var="enable_attendance_growth_monitoring=true"
```

Review the plan and verify development logs before any apply. Production activation is a separate approved
plan/apply; crossing a row or scan threshold does not authorize the partition operator scripts. Defaults are
10M rows for preparation, 20M for scheduling execution before 25M, 8 GiB of indexes for review, and one
full-table sequential-read equivalent per five-minute sample sustained for 15 minutes. See
`docs/DB-SCALING-THRESHOLDS.md` for statistics-reset, small-table suppression, and response guidance.
