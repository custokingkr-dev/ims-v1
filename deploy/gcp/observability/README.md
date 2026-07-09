# Custoking Observability Terraform

This Terraform root manages the GCP-native observability layer for one Custoking
environment in project `custoking`:

- Cloud Monitoring dashboards for the 5 domain services plus `api-gateway`.
- Uptime checks against `/actuator/health` or `/gateway-health`; private
  services use Cloud Run revision targets with Monitoring service-agent OIDC.
- Alert policies for 5xx rate, p95 latency, max-instance saturation, uptime, async health, and SLO burn rate.
- Log-based distribution metrics for outbox and notification inbox health.
- Cloud Monitoring services and availability/latency SLOs for Cloud Run.
- trace-writer IAM for the default Cloud Run runtime service account:
  `roles/cloudtrace.agent` for Cloud Trace exporters, plus
  `roles/telemetry.tracesWriter` and `roles/serviceusage.serviceUsageConsumer`
  for OTLP export to `telemetry.googleapis.com`.

## Prerequisites

Apply this after the environment has been deployed at least once. By default the
module reads the existing Cloud Run services to discover their generated hosts.

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

```hcl
bucket = "custoking-terraform-state"
prefix = "observability/dev"
```

The dev state bucket is `gs://custoking-terraform-state` and uses prefix
`observability/dev`. Initialize with:

```powershell
terraform -chdir=deploy/gcp/observability init `
  -backend-config="bucket=custoking-terraform-state" `
  -backend-config="prefix=observability/dev"
```

## Plan and Apply

```powershell
terraform -chdir=deploy/gcp/observability init `
  -backend-config="bucket=custoking-terraform-state" `
  -backend-config="prefix=observability/dev"
terraform -chdir=deploy/gcp/observability plan -var="env=dev"
terraform -chdir=deploy/gcp/observability apply -var="env=dev"
```

For production, pass `-var="env=prod"` and production notification channels:

```powershell
terraform -chdir=deploy/gcp/observability plan `
  -var="env=prod" `
  -var='notification_channel_ids=["projects/custoking/notificationChannels/<id>"]'
```

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

These are distribution metrics, so dashboards and alerts use p95/max alignment
over each five-minute window rather than treating the extracted log values as
true gauges.
