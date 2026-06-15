# Operations Runbook

Procedures for on-call engineers managing the Custoking IMS production environment.

---

## Table of contents

1. [Health checks](#1-health-checks)
2. [Service restart / rollback](#2-service-restart--rollback)
3. [Database](#3-database)
4. [Authentication issues](#4-authentication-issues)
5. [Login lockout](#5-login-lockout)
6. [Secrets rotation](#6-secrets-rotation)
7. [Flyway migration failures](#7-flyway-migration-failures)
8. [Log access](#8-log-access)
9. [Alerts and escalation](#9-alerts-and-escalation)

---

## 1. Health checks

### Backend liveness / readiness (Kubernetes / Cloud Run probes)

```
GET /actuator/health/liveness
GET /actuator/health/readiness
```

Both return `{"status":"UP"}` when healthy.

### Full health (requires `system:actuator` permission)

```bash
curl -H "Authorization: Bearer <superadmin_token>" \
     https://<backend-host>/actuator/health
```

Fields to check: `db.status`, `diskSpace.status`.

### Prometheus metrics

```
GET /actuator/prometheus
```

Key metrics:

| Metric | Alert threshold |
|--------|----------------|
| `jvm_memory_used_bytes` | > 80 % of limit |
| `hikaricp_connections_active` | near `DB_POOL_MAX` for > 5 min |
| `http_server_requests_seconds_count` error rate | > 1 % over 5 min |

---

## 2. Service restart / rollback

### Cloud Run restart

```bash
# Redeploy the current image (effectively restarts all instances)
gcloud run services update custoking-ims-backend \
  --region=<REGION> \
  --project=<PROJECT_ID>

# Roll back to a previous revision
gcloud run services update-traffic custoking-ims-backend \
  --to-revisions=<REVISION_NAME>=100 \
  --region=<REGION> \
  --project=<PROJECT_ID>

# List recent revisions
gcloud run revisions list \
  --service=custoking-ims-backend \
  --region=<REGION>
```

### Docker Compose (staging / local)

```bash
docker compose pull backend
docker compose up -d --no-deps backend
docker compose logs -f backend
```

---

## 3. Database

### Connection check

```bash
psql "$SPRING_DATASOURCE_URL" -U "$SPRING_DATASOURCE_USERNAME" -c "SELECT 1;"
```

### Pool exhaustion

Symptoms: `HikariPool timeout` errors in logs; health endpoint shows `db.status: DOWN`.

1. Check active connections:
   ```sql
   SELECT count(*), state FROM pg_stat_activity
   WHERE datname = 'your_db_name'
   GROUP BY state;
   ```
2. Kill idle connections if needed:
   ```sql
   SELECT pg_terminate_backend(pid)
   FROM pg_stat_activity
   WHERE datname = 'your_db_name'
     AND state = 'idle'
     AND query_start < now() - interval '10 minutes';
   ```
3. Increase `DB_POOL_MAX` in Cloud Run env-vars if traffic has grown (restart required).

### Slow query investigation

```sql
SELECT pid, query_start, state, query
FROM pg_stat_activity
WHERE state != 'idle'
ORDER BY query_start;
```

Enable `pg_stat_statements` for historical data.

---

## 4. Authentication issues

### JWT validation failure (401 on valid-looking requests)

1. Confirm `APP_JWT_SECRET` in Cloud Run matches the key used to sign existing tokens.
2. If the secret was rotated, all existing tokens are invalid — users must log in again. This is expected.
3. Check clock skew: tokens are signed with `iat` / `exp`; a >5 s clock difference between server and client can cause spurious rejections.

### Refresh token not working

The refresh token is an HttpOnly cookie at path `/api/v1/auth`.
Check:
- Is the `APP_COOKIE_SECURE=true` in prod? (It must be — HTTPS only.)
- Is the frontend domain the same as the backend domain, or is SameSite causing rejection?
- Has the token expired? Default lifetime equals the JWT expiry (`APP_JWT_EXPIRATION_MS`).

---

## 5. Login lockout

`LoginRateLimiter` blocks an email address after 5 failed attempts within 15 minutes (in-memory, resets on restart).

### Symptoms

Users see `429 Too Many Requests` on `/api/v1/auth/login`.

### Resolution

**Option A — wait:** The lock clears automatically after 15 minutes.

**Option B — restart backend:** The in-memory counter resets on startup. Only do this if the wait is unacceptable.

**Option C — user resets their password:** If the lockout was triggered by a forgotten password, guide the user through the reset flow. No admin action required.

> **Note:** There is currently no admin endpoint to clear the rate-limit counter directly.
> If this becomes a recurring issue, add a persistent rate-limit store (Redis).

---

## 6. Secrets rotation

### APP_JWT_SECRET rotation

1. Generate a new secret:
   ```bash
   openssl rand -hex 32
   ```
2. Update the secret in GCP Secret Manager:
   ```bash
   echo -n "<new_secret>" | \
     gcloud secrets versions add APP_JWT_SECRET --data-file=-
   ```
3. Update the Cloud Run service to reference the new version:
   ```bash
   gcloud run services update custoking-ims-backend \
     --update-secrets=APP_JWT_SECRET=APP_JWT_SECRET:latest \
     --region=<REGION>
   ```
4. **All existing access tokens are invalidated immediately.**
   Refresh tokens will fail too — users will be logged out on their next request.
   Notify users of expected downtime if coordinating with them.

### APP_AADHAR_SECRET rotation

> ⚠️ **Danger:** Rotating this key without re-encrypting existing Aadhaar data will render all stored data unreadable.

1. Export + decrypt all Aadhaar data using the old key.
2. Re-encrypt with the new key.
3. Write the new ciphertext back to the DB in a migration.
4. Only then rotate the secret in Secret Manager.

This is a planned, coordinated operation — never rotate ad hoc.

### Database password rotation

1. Change the password in PostgreSQL:
   ```sql
   ALTER USER ims_app PASSWORD 'new_password';
   ```
2. Update `SPRING_DATASOURCE_PASSWORD` in Secret Manager.
3. Deploy a new Cloud Run revision (connection pool will reconnect).

---

## 7. Flyway migration failures

### Symptoms

Backend fails to start with `FlywayException` or `MigrationVersionMismatch`.

### Investigation

```bash
# Connect as the Flyway DBA user and inspect the schema_version table
psql "$FLYWAY_URL" -U "$FLYWAY_USERNAME" \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;"
```

### Failed migration (success = false)

1. Identify the failing migration script under `backend/src/main/resources/db/migration/`.
2. Fix the SQL (in a **new migration file**, never edit the existing one).
3. Manually remove the failed row from `flyway_schema_history`:
   ```sql
   DELETE FROM flyway_schema_history WHERE version = '<failed_version>' AND success = false;
   ```
4. Redeploy.

### Checksum mismatch

An existing migration file was modified after it was applied. This violates Flyway's immutability contract.

1. Find the original content in git: `git show <commit>:backend/src/main/resources/db/migration/V<N>__...sql`
2. Restore the original file **or** use `flyway:repair` (DBA decision):
   ```bash
   mvn flyway:repair -Dflyway.url="..." -Dflyway.user="..." -Dflyway.password="..."
   ```

---

## 8. Log access

### Cloud Run (GCP Logging / Cloud Logging)

```bash
gcloud logging read \
  'resource.type="cloud_run_revision" AND resource.labels.service_name="custoking-ims-backend"' \
  --limit=200 \
  --project=<PROJECT_ID> \
  --format="value(textPayload)"
```

### Log format

Production logs are JSON-structured (Logback).
Key fields: `timestamp`, `level`, `thread`, `logger`, `message`, `requestId`, `schoolId`.

`requestId` is the `X-Request-Id` correlation header — use it to trace a request through all log lines.

### Docker Compose

```bash
docker compose logs -f backend --tail=200
```

---

## 9. Alerts and escalation

| Severity | Definition | Response SLA |
|----------|-----------|-------------|
| P1 — Critical | All users cannot log in; DB is down; data loss risk | 15 min |
| P2 — High | A school module is unavailable; > 10 % error rate | 1 h |
| P3 — Medium | Degraded performance; non-blocking errors in logs | Next business day |

### Escalation path

1. On-call engineer — first responder
2. Backend lead — if P1 unresolved after 15 min
3. CTO — if P1 unresolved after 30 min or data loss confirmed

All incidents must be documented in the incident log (Notion / GitHub Issues) with:
- Incident start time
- Root cause
- Resolution steps taken
- Prevention action items
