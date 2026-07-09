# Observability and Operations

Last verified: 2026-07-09.

## Observability Architecture

The current observability stack is GCP-native:

- Cloud Run request metrics.
- Cloud Logging JSON logs.
- Cloud Trace through OpenTelemetry OTLP export.
- Cloud Monitoring dashboards.
- Cloud Monitoring alert policies.
- Cloud Monitoring uptime checks.
- Log-based metrics for async health.
- Monitoring services and SLOs from Terraform source.

Terraform root:

```text
deploy/gcp/observability
```

## Runtime Trace Configuration

Verified live Cloud Run env:

- `OTEL_TRACES_EXPORTER=otlp`
- `OTEL_EXPORTER_OTLP_ENDPOINT=https://telemetry.googleapis.com`
- `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=https://telemetry.googleapis.com/v1/traces`
- `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`
- `OTEL_TRACES_SAMPLER=parentbased_traceidratio`
- dev sample ratio: `1.0`
- prod sample ratio: `0.2`
- `OTEL_LOGS_EXPORTER=none`
- `OTEL_METRICS_EXPORTER=none`
- `OTEL_RESOURCE_ATTRIBUTES=gcp.project_id=custoking`

Spring services package `GcpOtlpTraceExporterAuthConfig` to add ADC bearer token auth and `x-goog-user-project` for OTLP writes to Google telemetry.

IAM grants verified on the default compute service account:

- `roles/cloudtrace.agent`
- `roles/telemetry.tracesWriter`
- `roles/serviceusage.serviceUsageConsumer`

## Dashboards

Live dashboards found:

- `Custoking dev - API Gateway`
- `Custoking prod - API Gateway`
- `Custoking dev - Identity Service`
- `Custoking prod - Identity Service`
- `Custoking dev - School Core Service`
- `Custoking prod - School Core Service`
- `Custoking dev - Operations Service`
- `Custoking prod - Operations Service`
- `Custoking dev - Platform Service`
- `Custoking prod - Platform Service`
- `Custoking dev - Billing Service`
- `Custoking prod - Billing Service`
- `Custoking dev - Async Health`
- `Custoking prod - Async Health`

Per-service dashboards include:

- p50/p95/p99 request latency
- request rate
- 5xx rate
- active instances
- CPU utilization
- memory utilization

Async dashboards include:

- outbox pending count
- outbox dead-letter count
- oldest pending outbox age
- notification inbox backlog

## Uptime Checks

Live uptime checks:

- `custoking-dev-api-gateway-health`
- `custoking-dev-billing-service-health`
- `custoking-dev-identity-service-health`
- `custoking-dev-operations-service-health`
- `custoking-dev-platform-service-health`
- `custoking-dev-school-core-service-health`
- `custoking-prod-api-gateway-health`
- `custoking-prod-billing-service-health`
- `custoking-prod-identity-service-health`
- `custoking-prod-operations-service-health`
- `custoking-prod-platform-service-health`
- `custoking-prod-school-core-service-health`

Period: `300s`.

Timeout: `10s`.

Gateway health path:

```text
/gateway-health
```

Spring service health path:

```text
/actuator/health
```

Private service uptime checks use Monitoring service-agent OIDC and Cloud Run revision monitored resources.

## Alert Policies

80 enabled Custoking alert policies were verified.

Policy families:

- Service uptime.
- Service 5xx rate.
- Service p95 latency.
- Service max-instance saturation.
- Availability SLO burn rate.
- Latency SLO burn rate.
- Outbox pending.
- Outbox dead-letter.
- Outbox oldest pending age.
- Notification inbox backlog.

Default thresholds from Terraform source:

- 5xx ratio: `0.02`
- p95 latency: `2000` ms
- max instance saturation: `0.9` of configured max instances
- outbox pending: `100`
- outbox dead-letter: `0`
- outbox oldest pending age: `900` seconds
- notification inbox backlog: `100`
- availability SLO goal: `0.995`
- latency SLO goal: `0.95`
- latency SLO threshold: `2s`
- SLO rolling period: 30 days
- burn rate lookback: `60m`
- burn rate threshold: `2`

Verified gap: no Cloud Monitoring notification channels were listed by `gcloud beta monitoring channels list --project=custoking`. Alert policies exist and are enabled, but no live notification channels were verified.

## Log-Based Metrics

Live log-based metrics:

- `custoking/dev/outbox_pending_count`
- `custoking/dev/outbox_oldest_pending_age_seconds`
- `custoking/dev/outbox_dead_letter_count`
- `custoking/dev/notification_inbox_backlog_count`
- `custoking/prod/outbox_pending_count`
- `custoking/prod/outbox_oldest_pending_age_seconds`
- `custoking/prod/outbox_dead_letter_count`
- `custoking/prod/notification_inbox_backlog_count`

Expected structured log fields:

- `jsonPayload.health.outbox.pendingCount`
- `jsonPayload.health.outbox.deadLetterCount`
- `jsonPayload.health.outbox.oldestPendingAgeSeconds`
- `jsonPayload.health.notificationInbox.backlogCount`

The metrics are distribution metrics using p95/max alignment over five-minute windows.

## Trace Verification Evidence

Fresh authenticated gateway requests were generated on 2026-07-09:

Dev:

- Gateway: `https://custoking-api-gateway-dev-l7mhms5c2a-em.a.run.app`
- Request paths:
  - `GET /api/v1/rbac/roles`
  - `GET /api/v1/schools`
- Status: 200 for both
- Trace found in Cloud Trace API.
- Trace-linked logs spanned API gateway, identity-service, and school-core-service.

Prod:

- Gateway: `https://custoking-api-gateway-prod-l7mhms5c2a-em.a.run.app`
- Request paths:
  - `GET /api/v1/rbac/roles`
  - `GET /api/v1/schools`
- Status: 200 for both
- Trace found in Cloud Trace API.
- Trace-linked logs spanned API gateway, identity-service, and school-core-service.

Recent error checks at that time:

- Cloud Run errors in the checked 15-minute window: 0.
- OTEL export errors in the checked 30-minute window: 0 using local-filtered log query.

## Async Observability Verification

Verified on 2026-07-09:

- Dev/prod topics exist.
- Dev/prod push subscriptions are ACTIVE.
- Push subscriptions use OIDC service account auth.
- Platform-service has required invoker binding for default compute service account.
- Real `PubSubDomainEventPublisher` startup logs were found for dev/prod billing, operations, and school-core.
- Prod DB audit showed outbox and inbox backlog/open/error counts at 0.

Not freshly verified in the latest check:

- A new prod write-path event through producer -> Pub/Sub -> reporting/notification consumer.
- Real external MSG91 delivery.

## SLOs

Terraform source declares Cloud Monitoring services and SLOs for each logical service:

- identity-service
- school-core-service
- operations-service
- platform-service
- billing-service
- api-gateway

For each service:

- availability SLO
- latency SLO
- availability burn-rate alert
- latency burn-rate alert

The current local `gcloud` SDK did not expose `gcloud monitoring services list`, so SLO existence was verified from Terraform source and alert policy presence, not from a direct `services list` command.

## Terraform State and CLI

Terraform source exists under:

```text
deploy/gcp/observability
```

State guidance in the source README uses:

```text
bucket = "custoking-terraform-state"
prefix = "observability/dev"
```

Live bucket `custoking-terraform-state` exists.

In the current PowerShell environment, `Get-Command terraform` returned no command, so Terraform was not currently discoverable on PATH during this documentation pass.

## Operational Checks

Useful commands:

```powershell
gcloud.cmd run services list --project=custoking --region=asia-south2
gcloud.cmd builds list --project=custoking --limit=10
gcloud.cmd pubsub topics list --project=custoking
gcloud.cmd pubsub subscriptions list --project=custoking
gcloud.cmd monitoring dashboards list --project=custoking
gcloud.cmd monitoring policies list --project=custoking
gcloud.cmd monitoring uptime list-configs --project=custoking
gcloud.cmd logging metrics list --project=custoking
```

Do not print Pub/Sub push endpoints without redacting token query parameters.

## Runbook Notes

- Use `gcloud.cmd` in this PowerShell environment unless execution policy is changed.
- Do not use `gcloud` without `.cmd` if the `gcloud.ps1` shim is blocked.
- Do not read Secret Manager values into logs or docs.
- Treat `prod` write smokes as mutating tests. Run only when approved.
- Before declaring notification delivery production-ready, verify provider config and real/dry-run status.
