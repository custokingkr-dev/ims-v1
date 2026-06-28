# Operations Runbook

Procedures for operators managing the current Custoking IMS split-service production environment.

## 1. Health Checks

### Public Gateway

```bash
curl -fsS https://<api-gateway-host>/gateway-health
```

Expected response:

```json
{"status":"UP"}
```

### Cloud Run Services

```bash
for service in \
  custoking-identity-service \
  custoking-tenant-school-service \
  custoking-student-service \
  custoking-attendance-service \
  custoking-fee-service \
  custoking-catalog-service \
  custoking-workflow-service \
  custoking-firefighting-service \
  custoking-reporting-service \
  custoking-billing-service \
  custoking-audit-service \
  custoking-notification-service \
  custoking-frontend \
  custoking-api-gateway; do
  gcloud run services describe "$service" \
    --region=<REGION> \
    --project=<PROJECT_ID> \
    --format='table(metadata.name,status.latestReadyRevisionName,status.url)'
done
```

Domain service `/actuator/health` endpoints are private in production. Prefer gateway smoke scripts unless you are using an operator identity with Cloud Run private invocation rights.

## 2. Restart And Rollback

### Restart A Cloud Run Service

```bash
gcloud run services update <cloud-run-service-name> \
  --region=<REGION> \
  --project=<PROJECT_ID>
```

### Roll Back A Cloud Run Revision

```bash
gcloud run revisions list \
  --service=<cloud-run-service-name> \
  --region=<REGION> \
  --project=<PROJECT_ID>

gcloud run services update-traffic <cloud-run-service-name> \
  --to-revisions=<REVISION_NAME>=100 \
  --region=<REGION> \
  --project=<PROJECT_ID>
```

Public services:

- `custoking-frontend`
- `custoking-api-gateway`

Private domain services:

- `custoking-identity-service`
- `custoking-tenant-school-service`
- `custoking-student-service`
- `custoking-attendance-service`
- `custoking-fee-service`
- `custoking-catalog-service`
- `custoking-workflow-service`
- `custoking-firefighting-service`
- `custoking-reporting-service`
- `custoking-billing-service`
- `custoking-audit-service`
- `custoking-notification-service`

### Local Compose

```bash
docker compose --profile core up -d --build
docker compose --profile full up -d --build
docker compose --profile full logs -f api-gateway identity-service --tail=200
```

There is no `backend` compose service in the current stack.

## 3. Database

Production uses Cloud SQL PostgreSQL with one schema per service.

Primary schemas:

- `identity`
- `tenant_school`
- `student`
- `attendance`
- `fee`
- `catalog`
- `workflow`
- `firefighting`
- `reporting`
- `billing`
- `audit`
- `notification`

Connection check from an environment that can reach the private Cloud SQL IP:

```bash
psql "$SPRING_DATASOURCE_URL" -U "$SPRING_DATASOURCE_USERNAME" -c "SELECT 1;"
```

Pool exhaustion symptoms include Hikari timeout logs and service health failures.

```sql
SELECT count(*), state
FROM pg_stat_activity
WHERE datname = 'custoking_ims_v1'
GROUP BY state;
```

## 4. Authentication Issues

Auth is owned by `custoking-identity-service`.

For login failures:

1. Confirm the request is going through `/api/v1/auth/login` on the frontend or API gateway URL.
2. Confirm frontend `API_UPSTREAM` points to the API gateway in Cloud Run.
3. Confirm `APP_JWT_SECRET` is present on `custoking-identity-service`.
4. Check identity-service logs for invalid credentials, disabled user, Flyway startup failure, or DB connectivity.

Refresh token details:

- HttpOnly cookie.
- Path: `/api/v1/auth`.
- Production must use `APP_COOKIE_SECURE=true`.
- Cross-origin deployments require the current SameSite/CORS settings to match the gateway/frontend topology.

## 5. Login Lockout

The login rate limiter is in identity-service memory. It resets on identity-service restart.

Symptoms:

```text
429 Too Many Requests on /api/v1/auth/login
```

Resolution:

1. Wait for the lockout window to expire.
2. If urgent, restart `custoking-identity-service`.
3. If caused by an unknown password, reset the user password through the user management flow.

## 6. Secrets Rotation

Important production secrets:

- `db-password` (appuser — single app + Flyway DB user)
- `jwt-secret`
- `identity-introspection-token`
- per-service internal tokens such as `student-read-token`, `fee-read-token`, `catalog-read-token`
- MSG91 secrets when notification delivery is enabled

JWT secret rotation:

```bash
openssl rand -hex 32 | gcloud secrets versions add jwt-secret --data-file=-
gcloud run services update custoking-identity-service \
  --update-secrets=APP_JWT_SECRET=jwt-secret:latest \
  --region=<REGION> \
  --project=<PROJECT_ID>
```

All existing access and refresh tokens become invalid after the signing secret changes.

Database password rotation:

```sql
ALTER USER appuser PASSWORD '<new_password>';
```

Then add a new Secret Manager version and redeploy the services that consume it.

## 7. Flyway Migration Failures

Each service owns its migration files under:

```text
services/<service>/src/main/resources/db/migration/
```

Each service has an independent Flyway history table. Most use `<schema>.flyway_schema_history`; tenant-school uses `tenant_school.flyway_schema_history_tenant_school`.

Rules:

- Do not edit already-applied migrations.
- Add a new `V<N>__description.sql` migration in the owning service.
- If an already-applied migration was changed, use a deliberate Flyway repair plan and record the evidence.

Investigation query:

```sql
SELECT installed_rank, version, description, script, checksum, success
FROM <schema>.flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 20;
```

For tenant-school:

```sql
SELECT installed_rank, version, description, script, checksum, success
FROM tenant_school.flyway_schema_history_tenant_school
ORDER BY installed_rank DESC
LIMIT 20;
```

## 8. Logs

Cloud Run logs:

```bash
gcloud logging read \
  'resource.type="cloud_run_revision" AND resource.labels.service_name="<cloud-run-service-name>"' \
  --limit=200 \
  --project=<PROJECT_ID> \
  --format="value(textPayload)"
```

Local logs:

```bash
docker compose --profile full logs -f api-gateway identity-service --tail=200
```

Use `X-Request-ID` / `traceparent` to follow a request through the gateway and services.

## 9. Verification Scripts

Production gateway smoke:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\invoke-production-gateway-smoke.ps1
```

Local migration/static verification:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify-microservice-migration.ps1
powershell -ExecutionPolicy Bypass -File scripts\audit-compose-profiles.ps1
```

Full local runtime smoke:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\smoke-microservice-features.ps1 -GatewayBaseUrl http://localhost
```

## 10. Alerts And Escalation

| Severity | Definition | Response SLA |
| --- | --- | --- |
| P1 | All users cannot log in, DB unavailable, or data loss risk | 15 min |
| P2 | A school module is unavailable or error rate is above 10 percent | 1 h |
| P3 | Degraded performance or non-blocking errors | Next business day |

Escalation:

1. On-call engineer
2. Backend/platform lead
3. CTO for unresolved P1 or confirmed data loss

Document every incident with start time, root cause, resolution, and prevention items.
