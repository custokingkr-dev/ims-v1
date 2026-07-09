# Codebase and Conventions

Last verified: 2026-07-09.

## Repository Layout

```text
frontend/                 React/Vite SPA
services/api-gateway/     Node HTTP route gateway
services/identity-service/
services/school-core-service/
services/operations-service/
services/platform-service/
services/billing-service/
scripts/                  audits, smoke tests, deployment helpers, DB role helpers
deploy/gcp/               GCP runbook, direct smoke job template, observability Terraform
deploy/local/initdb/      local Postgres init scripts
docs/                     architecture, runbooks, plans, current-state docs
artifacts/                generated deployment/smoke/evidence artifacts
cloudbuild.yaml           build/deploy definition
docker-compose.yml        local split-service stack
Tiltfile                  local Tilt orchestration
```

## Language and Runtime Versions

Verified from package and pom files:

- Java services use Spring Boot parent `4.0.7`.
- Java services set `java.version` to `25`.
- Frontend uses React `18.3.1`, Vite `6.2.0`, TypeScript `5.7.2`, Vitest `3.2.0`.
- Gateway uses Node 20 in CI and CommonJS source.
- PostgreSQL driver is used by Spring services.
- Flyway is used for service-owned schema migrations.
- Testcontainers PostgreSQL is used in Java tests.
- OpenTelemetry OTLP exporter and GCP auth extension are used by Spring services.
- API gateway uses OpenTelemetry Node packages and the Google Cloud Trace exporter.

## Java Service Shape

Common package directories across Spring services:

- `api`
- `application`
- `config`
- `infrastructure`
- `observability`
- `persistence`
- `security`

Services with outbox publishing include an `outbox` package:

- `school-core-service`
- `operations-service`
- `billing-service`

Conventions:

- Controllers expose `/api/v1/**`.
- Internal service tokens are required on private services when configured.
- SQL is generally explicit/JDBC-oriented in merged services.
- Flyway owns database structure.
- Runtime code connects as `app_rt`.
- Flyway connects as `appuser` in deployed environments.
- Java service health endpoints expose `health,info`.
- Spring Boot Actuator probes are enabled.

## API Gateway Conventions

Source: `services/api-gateway/server.js`.

Gateway route ownership is explicit and ordered. New public frontend API routes must be added to the gateway route table and mapped to the owning service.

Gateway behavior:

- `/gateway-health` returns service health directly.
- `/api/v1/**` routes without a configured service return 404.
- Non-API routes fall through to the frontend upstream.
- Local JWT verification is the preferred path when enriched JWT claims are present.
- Introspection remains the fallback path.
- Gateway logs JSON request records with request id, method, path, status, duration, and upstream service.
- Gateway propagates trace context.

Security controls in gateway:

- Strict CORS allowlist.
- Security response headers.
- Body size limit, default 5 MiB.
- Global token-bucket rate limit, default 50 RPS and burst 100.
- Required internal service-token env vars fail startup if missing or placeholder.

## Frontend Conventions

Source: `frontend/src/services/api.ts`, `frontend/src/App.tsx`.

- Frontend API base URL defaults to `/api/v1`.
- Access token is stored only in module memory.
- Refresh token is in an HttpOnly cookie managed by identity-service.
- API calls use Axios with credentials enabled.
- Non-auth 401s trigger refresh and a single retry.
- If refresh fails, frontend redirects to `/login`.

Top-level frontend routes:

- `/login`
- `/dashboard`
- `/schools`
- `/zones`

Most user-facing workflows live under the unified workspace page and workspace panels.

## Service Configuration Conventions

### identity-service

Owns:

- `identity` schema
- auth sessions
- users
- RBAC
- token introspection

Important env/config:

- `APP_JWT_SECRET`
- `APP_JWT_EXPIRATION_MS`
- `APP_REFRESH_TOKEN_EXPIRATION_MS`
- `APP_COOKIE_SECURE`
- `APP_COOKIE_SAME_SITE`
- `IDENTITY_INTROSPECTION_TOKEN`
- `TENANT_SCHOOL_BASE_URL`
- `TENANT_SCHOOL_READ_TOKEN`
- `TENANT_SCHOOL_CLOUD_RUN_AUTH`

### school-core-service

Merged domains:

- tenant-school
- student
- attendance
- fee
- catalog

Important env/config:

- `SCHOOL_CORE_MERGED=true`
- `SCHOOL_CORE_DATASOURCE_SCHEMAS=tenant_school,student,attendance,fee,catalog`
- `STUDENT_PHOTO_BUCKET`
- `SCHOOL_CORE_OUTBOX_PUBSUB_TOPIC_ID`
- `SCHOOL_CORE_OUTBOX_PUBSUB_PROJECT_ID`
- `TENANT_SCHOOL_READ_TOKEN`
- `STUDENT_READ_TOKEN`
- `ATTENDANCE_READ_TOKEN`
- `FEE_READ_TOKEN`
- `CATALOG_READ_TOKEN`

Critical convention: the Pub/Sub topic env var must be `SCHOOL_CORE_OUTBOX_PUBSUB_TOPIC_ID`. The shorter `SCHOOL_CORE_OUTBOX_PUBSUB_TOPIC` does not bind to the property that activates the real publisher.

### operations-service

Merged domains:

- workflow
- firefighting

Important env/config:

- `OPERATIONS_MERGED=true`
- `OPERATIONS_DB_SCHEMA=workflow,firefighting`
- `OPERATIONS_OUTBOX_PUBSUB_TOPIC_ID`
- `OPERATIONS_OUTBOX_PUBSUB_PROJECT_ID`
- `OPERATIONS_TENANT_SCHOOL_BASE_URL`
- `OPERATIONS_TENANT_SCHOOL_TOKEN`
- `OPERATIONS_TENANT_SCHOOL_CLOUD_RUN_AUTH`
- `WORKFLOW_READ_TOKEN`
- `FIREFIGHTING_READ_TOKEN`

Critical convention: the Pub/Sub topic env var must be `OPERATIONS_OUTBOX_PUBSUB_TOPIC_ID`.

### platform-service

Merged domains:

- reporting
- notification
- audit

Important env/config:

- `PLATFORM_DB_SCHEMA=reporting,notification,audit`
- `REPORTING_READ_TOKEN`
- `REPORTING_PUBSUB_PUSH_TOKEN`
- `REPORTING_EVENT_PROJECTION_ENABLED`
- `NOTIFICATION_PUBSUB_PUSH_TOKEN`
- `NOTIFICATION_STATUS_TOKEN`
- `NOTIFICATION_DELIVERY_PROVIDER`
- `MSG91_DRY_RUN`
- `MSG91_AUTH_KEY`
- `SCHOOL_CORE_URL`
- `OPERATIONS_URL`
- `CATALOG_READ_TOKEN`
- `FIREFIGHTING_READ_TOKEN`
- `AUDIT_INGEST_TOKEN`

Verified live prod config did not show `NOTIFICATION_DELIVERY_PROVIDER`, so the default `logging` provider applies unless set elsewhere.

### billing-service

Owns:

- `billing` schema
- superadmin invoices
- school billing tables
- billing outbox

Important env/config:

- `BILLING_DB_SCHEMA=billing`
- `BILLING_OUTBOX_PUBSUB_TOPIC_ID`
- `BILLING_OUTBOX_PUBSUB_PROJECT_ID`
- `BILLING_SERVICE_TOKEN`

Critical convention: the Pub/Sub topic env var must be `BILLING_OUTBOX_PUBSUB_TOPIC_ID`.

## Database Conventions

- Use schema-qualified SQL for owned tables.
- Keep Flyway migration history per schema/service.
- Use forward migrations only after deployment.
- Do not edit already-applied migrations unless there is a documented Flyway repair/baseline plan.
- Runtime role is `app_rt`.
- Migration/owner role is `appuser`.
- RLS must be tested as `app_rt`, not as owner/superuser.

## Local Development

Source: `docker-compose.yml`.

Local services:

- `postgres`: PostgreSQL 16 on port 5432.
- `identity-service`: port 8083, profile `core`/`full`.
- `school-core-service`: port 8084, profile `core`/`full`.
- `operations-service`: port 8089, profile `full`.
- `platform-service`: port 8091, profile `full`.
- `billing-service`: port 8092, profile `full`.
- `frontend`: profile `core`/`full`.
- `api-gateway`: port 80, profile `core`/`full`.

Local compose uses `SPRING_PROFILES_ACTIVE=prod` for services. Local secrets are dev placeholders in compose, not production secrets.

Core profile:

```powershell
docker compose --profile core up -d --build
```

Full profile:

```powershell
docker compose --profile full up -d --build
```

Local outbox real-publisher requirements are disabled with service-specific `*_OUTBOX_REQUIRE_REAL_PUBLISHER=false`.

## Testing and Audits

Important scripts:

- `scripts/resolve-affected-ci-targets.ps1`
- `scripts/verify-microservice-migration.ps1`
- `scripts/audit-microservice-runtime-boundaries.ps1`
- `scripts/audit-microservice-db-boundaries.ps1`
- `scripts/audit-service-authorization-boundaries.ps1`
- `scripts/audit-tenant-isolation.ps1`
- `scripts/audit-app-rt-privileges.ps1`
- `scripts/smoke-gateway-routes.ps1`
- `scripts/smoke-microservice-features.ps1`
- `scripts/invoke-production-gateway-smoke.ps1`
- `scripts/smoke-production-write-paths.ps1`

CI runs Maven tests, frontend tests/build, gateway Node tests, Docker builds, Trivy critical scan, Gitleaks, local integration smokes, and tenant isolation audit.

## Documentation Drift Convention

If an older plan or runbook conflicts with live code/deploy config, prefer:

1. `cloudbuild.yaml`
2. `.github/workflows/*.yml`
3. service source/config
4. live GCP inventory
5. latest smoke artifacts
6. older docs/plans

Known stale docs are listed in [gaps-and-drift.md](gaps-and-drift.md).
