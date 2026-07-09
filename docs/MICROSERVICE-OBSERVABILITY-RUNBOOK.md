# Microservice Observability Runbook

Use this runbook after dev or production deploys, rollbacks, smoke failures, or
incidents. The current Cloud Run environment is project `custoking`, region
`asia-south2`, with services named `custoking-<service>-<env>`.

## Health Checks

Gateway:

```bash
curl -fsS https://<gateway-url>/gateway-health
```

Backend/domain services:

```bash
curl -fsS https://<backend-url>/actuator/health
```

The domain services are private in Cloud Run. Prefer the gateway smoke scripts
unless you are using an operator identity with private invocation rights:

```powershell
$token = gcloud auth print-identity-token
curl -fsS -H "Authorization: Bearer $token" https://<private-service-url>/actuator/health
```

Check deployed service health and the latest Cloud Run revision:

```powershell
$envName = "dev"
$services = @(
  "custoking-api-gateway-$envName",
  "custoking-identity-service-$envName",
  "custoking-school-core-service-$envName",
  "custoking-operations-service-$envName",
  "custoking-platform-service-$envName",
  "custoking-billing-service-$envName"
)

$services | ForEach-Object {
  gcloud run services describe $_ `
    --project=custoking `
    --region=asia-south2 `
    --format="table(metadata.name,status.latestReadyRevisionName,status.conditions[0].type,status.conditions[0].status,status.url)"
}
```

## Read-Only Deployment Smoke

Run this after staging/dev, production promotion, rollback, or Cloud Run IAM
changes:

```powershell
$env:IMS_SMOKE_SUPERADMIN_TOKEN = "<superadmin-access-token>"
$env:IMS_SMOKE_ADMIN_TOKEN = "<school-admin-access-token>"
powershell -ExecutionPolicy Bypass -File scripts\smoke-deployment-readiness.ps1 `
  -GatewayBaseUrl https://<gateway-url> `
  -SchoolId <known-school-id> `
  -StudentId <known-student-id> `
  -AdminUserId <known-admin-user-id> `
  -ClassId <known-class-id> `
  -SectionId <known-section-id> `
  -AttendanceDate <yyyy-mm-dd> `
  -OutputJson deployment-readiness-smoke.json
```

Use bearer tokens for production checks. Login-credential mode can create
auth/session/audit records and is for local or staging-only convenience.

## Live Logs

Tail all Cloud Run logs for an environment:

```bash
gcloud logging tail \
  'resource.type="cloud_run_revision" AND resource.labels.service_name=~"custoking-.*-dev"' \
  --project=custoking \
  --format=json
```

Tail one service:

```bash
gcloud logging tail \
  'resource.type="cloud_run_revision" AND resource.labels.service_name="custoking-api-gateway-dev"' \
  --project=custoking \
  --format=json
```

Read recent errors:

```bash
gcloud logging read \
  'resource.type="cloud_run_revision" AND resource.labels.service_name="custoking-platform-service-dev" AND severity>=ERROR' \
  --project=custoking \
  --freshness=30m \
  --limit=100 \
  --format=json
```

Logs should be JSON and should include `requestId` plus Cloud Logging trace-link
fields when a span is active:

- `logging.googleapis.com/trace`
- `logging.googleapis.com/spanId`

## Correlation

Every gateway and service request must preserve:

- `X-Request-ID`
- `traceparent`

Use `requestId` to find all logs for one browser/API call, then use the trace
field to jump from Cloud Logging into Cloud Trace. If a log line has no trace
field, check that the request entered through the Node gateway and that
OpenTelemetry env vars were present on the Cloud Run revision.

Useful log query:

```text
resource.type="cloud_run_revision"
jsonPayload.requestId="<request-id>"
```

Trace Explorer:

```text
https://console.cloud.google.com/traces/list?project=custoking
```

For a healthy async application flow, one Cloud Trace waterfall should connect:

```text
api-gateway -> owning Spring service -> JDBC spans -> Pub/Sub publish -> platform-service Pub/Sub receive -> projection/inbox processing
```

If the trace splits at Pub/Sub, inspect message attributes for `traceparent` and
`tracestate`, then check platform-service receive/projection logs.

## OTLP Exporter Authentication

Java services export OTLP traces to Cloud Trace through:

```text
https://telemetry.googleapis.com/v1/traces
```

The Cloud Trace OTLP endpoint requires an authenticated Google bearer token and
quota project header. Each Spring service includes
`GcpOtlpTraceExporterAuthConfig`, which customizes the OTLP HTTP span exporter
with Application Default Credentials and sets:

- `Authorization: Bearer <access-token>`
- `x-goog-user-project: <GOOGLE_CLOUD_QUOTA_PROJECT|GCP_PROJECT|GOOGLE_CLOUD_PROJECT>`

Cloud Run must also provide `GOOGLE_CLOUD_QUOTA_PROJECT=custoking` and the OTLP
endpoint/protocol env vars from `cloudbuild.yaml`. Terraform grants the runtime
service account `roles/cloudtrace.agent`, `roles/telemetry.tracesWriter`, and
`roles/serviceusage.serviceUsageConsumer`.

If final-revision logs show `HTTP status code 403`, `unregistered callers`, or
missing trace exports, verify the customizer is packaged in the service image,
the Cloud Run env vars are present, and the Terraform runtime IAM has been
applied.

## Dashboards, Alerts, And SLOs

Monitoring resources live in Terraform under:

```text
deploy/gcp/observability/
```

Apply dev observability after the dev services have deployed at least once:

```powershell
terraform -chdir=deploy/gcp/observability init
terraform -chdir=deploy/gcp/observability plan -var="env=dev"
terraform -chdir=deploy/gcp/observability apply -var="env=dev"
```

Production uses the same root with `-var="env=prod"` and production notification
channels:

```powershell
terraform -chdir=deploy/gcp/observability plan `
  -var="env=prod" `
  -var='notification_channel_ids=["projects/custoking/notificationChannels/<id>"]'
```

Dashboard names:

- `Custoking <env> - API Gateway`
- `Custoking <env> - Identity Service`
- `Custoking <env> - School Core Service`
- `Custoking <env> - Operations Service`
- `Custoking <env> - Platform Service`
- `Custoking <env> - Billing Service`
- `Custoking <env> - Async Health`

Cloud Monitoring dashboard list:

```text
https://console.cloud.google.com/monitoring/dashboards?project=custoking
```

Primary Cloud Run signals:

- `5xx rate`
- `request latency`
- request rate
- active instance count
- max-instance saturation
- container CPU utilization
- container memory utilization
- private invocation failures

SLOs are managed for availability and latency per Cloud Run service. Burn-rate
alerts should be investigated from the service dashboard first, then Cloud Trace
for representative failing or slow requests.

## Async Health

Outbox-owning services:

- `custoking-school-core-service-<env>`
- `custoking-operations-service-<env>`
- `custoking-billing-service-<env>`

Platform async consumers:

- `custoking-platform-service-<env>`

The Terraform log-based metrics expect structured health logs with these fields:

- `jsonPayload.health.outbox.pendingCount`
- `jsonPayload.health.outbox.deadLetterCount`
- `jsonPayload.health.outbox.oldestPendingAgeSeconds`
- `jsonPayload.health.notificationInbox.backlogCount`

Alert on rising `outbox` pending count, non-zero dead-letter count, oldest
pending age, notification inbox backlog, failed inbox rows, and repeated provider
delivery failures. If MSG91 is degraded, keep inbox retry state intact and pause
Pub/Sub push or enable dry-run only when duplicate provider sends are possible.

Async triage order:

1. Check `Custoking <env> - Async Health`.
2. Inspect outbox relay logs in the owning service.
3. Inspect Pub/Sub publish/ack errors.
4. Inspect platform-service Pub/Sub push receive logs.
5. Confirm platform projection spans join the original trace.

## Uptime Checks

Terraform creates uptime checks for services whose Cloud Run hosts can be
discovered or supplied through `service_hosts`.

Private service checks use Monitoring service-agent OIDC authentication. If a
private check fails with 401/403, verify the Monitoring service agent and grant
`roles/run.invoker` on that service:

```powershell
$projectNumber = gcloud projects describe custoking --format='value(projectNumber)'
$monitoringSa = "service-$projectNumber@gcp-sa-monitoring-notification.iam.gserviceaccount.com"
gcloud run services add-iam-policy-binding custoking-platform-service-dev `
  --project=custoking `
  --region=asia-south2 `
  --member="serviceAccount:$monitoringSa" `
  --role=roles/run.invoker
```

## Error Reporting

Error Reporting is populated automatically from structured Cloud Run error logs.
No Terraform resource is required. If an exception is visible in logs but not in
Error Reporting, confirm the log severity is `ERROR` or higher and the stack
trace is present in the JSON payload.

## Required Promotion Artifacts

Keep these artifacts for staging/dev and production promotions:

- `deployment-readiness-smoke.json`
- `legacy-compatibility-audit.json`
- `cloud-build-evidence.json`
- `image-digests.json`
- `cloud-run-revisions.json`
- `secret-manager-evidence.json`
- `cloud-run-iam-evidence.json`
- `legacy-retirement-evidence.json`
- `rollback-drill-evidence.json`
- Cloud Run revision names for gateway, frontend, and domain services
- Rollback target revision list

For incident follow-up, attach the relevant Cloud Run revision, trace URL, log
query, dashboard screenshot or export, and alert incident link.
