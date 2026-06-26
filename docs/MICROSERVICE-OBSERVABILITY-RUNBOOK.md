# Microservice Observability Runbook

Use this runbook after staging promotion, production promotion, rollback, or incident mitigation.

## Health Checks

Gateway:

```bash
curl -fsS https://<gateway-url>/gateway-health
```

Backend:

```bash
curl -fsS https://<backend-url>/actuator/health
```

Extracted services are private in production. Check them through backend aggregate health or Cloud Run logs unless using an operator identity with private invocation rights.

Backend readiness includes enabled extracted services. When a migrated route has no backend fallback, an unreachable owning service must make `/actuator/health` unhealthy.

## Read-Only Deployment Smoke

Run this after staging, production, or rollback:

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

Use bearer tokens for production checks. Login-credential mode can create auth/session/audit records and is for local or staging-only convenience.

## Correlation

Every gateway/backend/service request must preserve:

- `X-Request-ID`
- `traceparent`

Operational logs should include `requestId`. Use it to follow one browser request through gateway, backend, service calls, outbox publication, and audit records.

## Async Health

Backend outbox:

- Check `/actuator/health` details for the `outbox` contributor.
- Alert on rising pending count, in-progress count stuck for multiple intervals, dead-letter count, or oldest pending age.

Notification service:

- Check notification-service health details for `notificationInbox`.
- Alert on failed inbox rows, rising oldest-failed age, and repeated provider delivery failures.
- If MSG91 is degraded, keep inbox retry state intact and pause Pub/Sub push or enable dry-run only when duplicate provider sends are possible.

## Cloud Run Signals

Monitor per service:

- 5xx rate
- request latency
- instance restart count
- container memory pressure
- max instance saturation
- private invocation failures

For gateway/backend specifically, also monitor auth failures and CORS errors after frontend or gateway URL changes.

## Required Promotion Artifacts

Keep these artifacts for staging and production promotions:

- `deployment-readiness-smoke.json`
- `legacy-compatibility-audit.json`
- `cloud-build-evidence.json`
- `image-digests.json`
- `cloud-run-revisions.json`
- `secret-manager-evidence.json`
- `cloud-run-iam-evidence.json`
- `legacy-retirement-evidence.json`
- `rollback-drill-evidence.json`
- Cloud Run revision names for backend, frontend, gateway, and extracted services
- Rollback target revision list
