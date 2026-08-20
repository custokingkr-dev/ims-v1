# Observability and Operations

Last reconciled: 2026-08-14 against live Monitoring, Logging, health, and Pub/Sub inventory.

## 2026-08-14 OpenTelemetry Delta

- The background-flush correction is deployed to all five Java services in dev and production. It prevents
  scheduled/background trace flushing from running without a request-scoped Google authentication context.
- Production commit `754f0417` and CD run `31820051376` passed. Fresh authenticated checks returned HTTP 200
  for all seven services and produced exact current-revision traces for the gateway and all five Java services.
- The post-deployment 30-minute exporter-error query returned zero errors.
- The primary human operator confirmed alert-email receipt. Backup-recipient delivery remains unproven.
- Production promotion is complete; a one-school-day stability window remains OBS-01 acceptance work.

## 2026-08-12 Live Reconciliation

- 110 alert policies are enabled, eight uptime checks exist, and all policies reference the one enabled
  operator email channel. Human receipt/verification is still required.
- Production gateway and frontend return HTTP 200. No production HTTP 5xx was observed in the last-day
  review; the nine ERROR log entries were OTLP span-export timeouts.
- Both reporting push subscriptions use dedicated OIDC identities. Dev reporting has a DLQ; production
  reporting does not.
- Dev notification has a push subscription and DLQ. Production notification has neither and remains
  logging/dry-run only.
- Logging retention remains seven days in `_Default`, 400 days locked in `_Required`, and 180 days in the
  India compliance bucket.

## Observability Architecture

The current observability stack is GCP-native:

- Cloud Run request metrics.
- Cloud Logging JSON logs.
- Cloud Trace through OpenTelemetry OTLP export.
- Cloud Monitoring dashboards.
- Cloud Monitoring alert policies.
- Cloud Monitoring uptime checks run at a cost-controlled 900-second period.
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
- dev sample ratio: `0.05`
- prod sample ratio: `0.05`
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

Six production uptime checks were restored from Terraform on 2026-08-05 at a
900-second period. Two obsolete five-minute production checks were removed.
Terraform can manage their enabled state with:

```powershell
terraform -chdir=deploy/gcp/observability apply `
  -var="env=<dev|prod>" `
  -var="enable_uptime_checks=true"
```

Default period when re-enabled: `900s`.

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

Production Terraform manages 53 alert policies, including uptime, service health,
SLO burn, asynchronous backlog, and dead-letter policies. A live email channel is
attached to every production-managed policy. Mailbox verification and a received
test incident still require the mailbox owner.

Policy families:

- Service 5xx rate.
- Service p95 latency.
- Service max-instance saturation.
- Availability SLO burn rate.
- Latency SLO burn rate.
- Outbox pending.
- Outbox dead-letter.
- Outbox oldest pending age.
- Notification inbox backlog.
- Notification inbox dead-letter.

Default thresholds from Terraform source:

- 5xx ratio: `0.02`
- p95 latency: `2000` ms
- max instance saturation: `0.9` of configured max instances
- outbox pending: `100`
- outbox dead-letter: `0`
- outbox oldest pending age: `900` seconds
- notification inbox backlog: `100`
- notification inbox dead-letter: `0`
- availability SLO goal: `0.995`
- latency SLO goal: `0.95`
- latency SLO threshold: `2s`
- SLO rolling period: 30 days
- fast burn: `14.4x` across both `60m` and `5m`, sustained for `180s`
- sustained burn: `6x` across both `360m` and `30m`, sustained for `300s`
- SLO burn-rate email prompts: incident opened only; recovery remains visible in Cloud Monitoring

The paired windows prevent a historical spike from keeping an incident active
after the service has recovered. The retest windows also prevent one evaluation
from opening an incident. Fast-burn policies are `ERROR`; sustained-burn and
latency policies are `WARNING` unless the fast-burn threshold is met.

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
- `custoking/prod/notification_inbox_dead_letter_count`

Expected structured log fields:

- `jsonPayload.health.outbox.pendingCount`
- `jsonPayload.health.outbox.deadLetterCount`
- `jsonPayload.health.outbox.oldestPendingAgeSeconds`
- `jsonPayload.health.notificationInbox.backlogCount`
- `jsonPayload.health.notificationInbox.deadLetterCount`

## Compliance Logging

Production owns `custoking-compliance-india` in `asia-south2` with 180-day
retention. The project sink routes Cloud Audit logs plus selected Cloud Run request,
error, security, audit, authentication, and authorization logs. The broad `_Default`
bucket remains short-lived to control cost.

Routing was proven on 2026-08-05 by issuing a production gateway health request and
reading the resulting HTTP 200 Cloud Run request log from the bucket's `_AllLogs`
view.

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

Originally verified on 2026-07-09:

- Dev/prod topics exist.
- Dev/prod reporting push subscriptions are ACTIVE.
- Reporting push subscriptions use OIDC service account auth.
- Platform-service retains a legacy default-Compute invoker binding in addition to dedicated push/runtime identities.
- Real `PubSubDomainEventPublisher` startup logs were found for dev/prod billing, operations, and school-core.
- Prod DB audit showed outbox and inbox backlog/open/error counts at 0.

Dev reporting push was reverified on 2026-08-10 after its OIDC cutover:

- Dedicated push identity: `ims-reporting-push-dev@custoking.iam.gserviceaccount.com`.
- OIDC audience exactly equals the platform-service dev Cloud Run URL.
- The push path contains no query string.
- A canonical reporting envelope reached revision `custoking-platform-service-dev-msnjqtb0` and was
  acknowledged with HTTP 204.
- The post-cutover authenticated gateway suite passed 40/40 and environment preflight had zero
  blockers.
- Production reporting now uses `ims-reporting-push-prod`; its remaining event-delivery gate is an observed
  real event plus a production DLQ/replay posture.
- Dev now has a dedicated OIDC notification push subscription with retry/DLQ. Canonical duplicate delivery
  was stored once and a poison event reached the DLQ under the logging/dry-run provider. Production still
  has no notification subscription, and real consented provider delivery remains gated.

Dev runtime IAM was reverified later on 2026-08-10:

- All seven services are Ready at 100% traffic with their expected dedicated runtime account.
- Effective IAM comparison found zero missing bindings across 44 secret grants, 12 project-role
  grants, 9 Cloud Run invoker edges, 3 Pub/Sub publishers, the photo bucket, and self-signing.
- Authenticated gateway smoke passed 40/40 and preflight had zero blockers.
- No post-cutover `PERMISSION_DENIED` log was found.
- A new reporting probe reached the dedicated platform revision through OIDC and returned HTTP 204.

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

**That guidance is stale and must not be followed.** `custoking-terraform-state` lives in the
pre-split `custoking` project, which is being deleted. Live state is per-environment, in each
environment's own project: `custoking-prod-terraform-state` (prefix `observability/prod`) and
`custoking-dev-terraform-state` (prefix `observability/dev`). Both the old and new buckets currently
exist and both carry an `observability/` prefix, so pointing at the wrong one fails silently rather
than erroring -- it reads stale state and then plans to create resources that already exist.

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
