# Development Guide

This file provides repository guidance for contributors working with the codebase.

---

## Repository Layout

The original Spring Boot monolith (`backend/`) has been decomposed into per-domain
microservices. There is **no `backend/` directory anymore** — domain logic lives under
`services/`, each service owning its own bounded Postgres schema.

```
ims-v1/
├── services/
│   ├── api-gateway/             Node.js route gateway (entry point; preserves /api/v1/** contract)
│   ├── identity-service/        Login, JWT, refresh/logout, users, roles, RBAC      → identity schema
│   ├── tenant-school-service/   Schools, zones, classes, sections, staff, modules   → tenant_school schema
│   ├── student-service/         Student records, imports, review campaigns          → student schema
│   ├── attendance-service/      Daily attendance summaries & records                → attendance schema
│   ├── fee-service/             Fee bands/items, assignments, payments              → fee schema
│   ├── catalog-service/         Catalog items, orders, annual plans, supply orders  → catalog schema
│   ├── workflow-service/        Workflow definitions, instances, steps, actions     → workflow schema
│   ├── firefighting-service/    Firefighting requests, quotes, approvals            → firefighting schema
│   ├── reporting-service/       Command center & reporting projections             → reporting schema
│   ├── notification-service/    MSG91 email/SMS/WhatsApp delivery, broadcasts       → notification schema
│   ├── audit-service/           Audit ingestion & read models                      → audit schema
│   └── billing-service/         Superadmin invoices, order sequences               → billing schema
├── frontend/                    React 18 + Vite + TypeScript SPA
├── deploy/gcp/                  Cloud Run / GCP deployment assets
├── docs/                        Architecture (HLD/LLD), runbooks, migration roadmaps
├── scripts/                     PowerShell audit/smoke/promotion tooling
├── docker-compose.yml           Local split-service stack (profile-gated)
├── Tiltfile                     Tilt wrapper over docker-compose
├── cloudbuild.yaml              Cloud Build pipeline (builds/deploys all services)
└── PRD.md                       Full product requirements document
```

All Java services use the package root `com.custoking.ims.<servicename>` with layers
`api/` (controllers; `api/compat/` for legacy `/api/v1/**` compatibility controllers),
`application/` (services), `persistence/` (entities + repositories), and `security/`.

---

## Commands

There is **no root `pom.xml` and no per-service Maven wrapper**. Build/test each Java
service by pointing the root wrapper at its `pom.xml`.

### A single Java service (`cd` not required)

```bash
# Windows
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -f services\identity-service\pom.xml -DskipTests compile
.\mvnw.cmd -f services\identity-service\pom.xml test

# POSIX
./mvnw -f services/identity-service/pom.xml -DskipTests compile
./mvnw -f services/identity-service/pom.xml test
```

The identity-service needs `APP_JWT_SECRET` (≥32 chars) and `APP_AADHAR_SECRET`
(≥16 chars) set to start (enforced at boot).

### API gateway (Node.js)

```bash
cd services/api-gateway
node --test server.test.js     # unit tests (no build step; plain Node http server)
```

### Frontend (`cd frontend`)

```bash
npm ci
npm run dev          # dev server (proxies /api → gateway)
npm test             # vitest run
npm run build        # production build to dist/
```

### Local full stack (root) — docker-compose with profiles

```bash
# Database only
docker compose up -d postgres

# Core stack: login, school/student, attendance, fees, frontend, gateway
docker compose --profile core up -d --build

# Full split-service stack (everything) — needed for complete migration smoke
docker compose --profile full up -d --build
```

Local service ports (each container listens on 8080 internally; host mappings differ):
gateway `80`, notification `8081`, audit `8082`, identity `8083`, tenant-school `8084`,
student `8085`, attendance `8086`, fee `8087`, catalog `8088`, workflow `8089`,
firefighting `8090`, reporting `8091`, billing `8092`.

> **Tilt note:** `Tiltfile` drives `docker-compose.yml`. Because every domain service is
> profile-gated, `tilt up` must enable a compose profile (e.g. pass `profiles=['full']`
> to `docker_compose(...)`), otherwise Tilt cannot resolve the profile-gated resources.

### Migration / boundary gate (PowerShell)

```powershell
docker compose --profile full up -d --build
powershell -ExecutionPolicy Bypass -File scripts\verify-microservice-migration.ps1 -RunDbAudit -RunSmoke
powershell -ExecutionPolicy Bypass -File scripts\smoke-deployment-readiness.ps1 -GatewayBaseUrl http://localhost `
  -SuperadminEmail e2e-superadmin@local.test -SuperadminPassword password `
  -AdminEmail e2e-admin@local.test -AdminPassword password
```

---

## Default Credentials

- Superadmin: `superadmin@custoking.com` / set via `SUPERADMIN_PASSWORD` env var
- Demo admin: `admin@demo.custoking.com` / set via `DEMO_ADMIN_PASSWORD` env var (only created when this var is set)

---

## Architecture

### Runtime topology

```text
Browser
  → custoking-frontend (Cloud Run nginx SPA)
  → custoking-api-gateway (Node route gateway; the only public entry point)
  → private domain services (Cloud Run, per-service IAM)
  → Cloud SQL PostgreSQL (one schema per service)
  → Pub/Sub event projections → notification / reporting / audit
```

### API gateway (`services/api-gateway/server.js`)

The gateway is a plain Node `http` server. It:

- Preserves the legacy public `/api/v1/**` contract so the SPA is unchanged.
- Routes each path to the owning private upstream (`*_UPSTREAM` env, default `http://<service>:8080`).
- Injects a per-service internal token (`*_SERVICE_TOKEN`) on every upstream call.
- Honors `GATEWAY_AUTH_MODE` (`enforce` | `permissive`) and `GATEWAY_CLOUD_RUN_AUTH`
  (`auto` | `never`) — Cloud Run identity tokens are added for private invocation in prod.
- Propagates `X-Request-ID` / `traceparent` correlation headers.

### Data ownership & cross-service access

Each service owns a bounded schema; **the old monolithic `public` domain tables have been
retired in production.** Runtime code must never read/write retired public domain tables.
Cross-service access is allowed **only** through:

- service HTTP APIs (request/response), or
- Pub/Sub event projection (async read models, shared event envelope — see
  `docs/EVENT-ENVELOPE-CONTRACT.md`).

No shared tables, no cross-service foreign keys.

### Service-to-service authorization

Cloud Run services are private (IAM-gated transport). As application-level
defense-in-depth, each service also requires its internal token
(`IDENTITY_INTROSPECTION_TOKEN`, `FEE_READ_TOKEN`, `AUDIT_INGEST_TOKEN`, etc.). Services
**fail closed** when the expected token is missing or mismatched. The gateway holds the
matching tokens and injects them. Keep
`scripts/audit-service-authorization-boundaries.ps1` green as the regression gate.

### Multi-Tenancy

Isolation is **application-level**: tenant (school/zone) scoping is enforced inside each
service from the authenticated user's scoped role assignments. Platform admins
(superadmins) bypass tenant filters. PostgreSQL Row-Level Security is not relied upon.

### RBAC

**Never use `hasRole()` or check a legacy `role` column in business logic.** Authorization
flows through permission codes:

- Identity-service issues the JWT and owns users/roles/permissions and scoped assignments.
- Permission codes are loaded for the user at login; controllers gate on permission codes.
- The legacy `role` column on app users is display-only.

### Module Entitlements

Schools subscribe to modules (STUDENTS, FEES, ATTENDANCE, ORDERS, FIREFIGHTING, PAYMENTS,
INVOICES, REPORTS). Module-gated endpoints check the school's entitlement before running
business logic; platform admins bypass entitlement checks.

### Frontend Auth

- Access token: **memory only** (`let accessToken` in `api.ts`) — never localStorage.
- Refresh token: HttpOnly `Secure SameSite=Strict` cookie, scoped to the auth path.
- On mount, `AuthProvider` calls `refreshToken()` to restore session from the cookie.
- 401 interceptor auto-retries once with a fresh token; on failure redirects to `/login`.
- Permissions loaded from `/api/v1/auth/me`; `usePermissions()` hook exposes
  `can(code)`, `canAny(codes[])`, `canAll(codes[])`.

---

## Flyway Migrations

Each service has its **own independent Flyway history** under
`services/<svc>/src/main/resources/db/migration`, restarting at `V1` per service (e.g.
`identity-service` starts at `V1__identity_schema.sql`). Migration versions are **not**
shared across services.

- New migrations go in the owning service and continue that service's sequence.
- Never modify an already-applied migration without a Flyway repair/baseline plan.
- Already-applied historical migrations may still contain backfill SQL referencing old
  public tables; leave those files alone — forward-only migrations for all new changes.
- No cross-service foreign keys in migrations (services own separate schemas).

---

## Adding a New Endpoint (Checklist)

1. Work inside the owning service under `services/<svc>/`.
2. Add the controller method in `api/` (or `api/compat/` if it backs a legacy `/api/v1/**` path).
3. Gate it on the end-user permission code and (for module-gated domains) the module entitlement.
4. Require/validate the internal service token so the endpoint fails closed.
5. If schema changes are needed, add the next `V…__….sql` migration **in that service's** history.
6. Validate request DTOs (`@Valid`).
7. If the frontend reaches it via `/api/v1/**`, confirm the gateway routes that path to this service.
8. Record an audit event where the domain requires it.

---

## Testing

- **Java unit tests**: `mvn test` per service (`*Test.java`, Mockito).
- **Gateway tests**: `node --test server.test.js`.
- **Frontend**: `npm test` (Vitest).
- **Integration / boundary gate**: `docker compose --profile full up` + the PowerShell
  `verify-microservice-migration.ps1` / `smoke-*` scripts.

---

## CI/CD

`.github/workflows/ci.yml` runs on push/PR:

1. **microservice-runtime-test** — boots the split-service stack via docker-compose, runs
   the migration boundary audit and gateway/feature smokes.
2. **frontend-build** — `npm ci`, `npm audit`, `npm test`, `npm run build`.
3. **service-test** — matrix: `mvn test` per Java service, `node --test` for the gateway, `npm test` for frontend.
4. **secret-scan** — Gitleaks.
5. **docker-build** — matrix builds every service + gateway + frontend image.
6. **trivy-scan** — image vulnerability scan (gateway).

`.github/workflows/deploy.yml` authenticates to GCP via Workload Identity Federation and
delegates to `cloudbuild.yaml`, which builds all service images, pushes to Artifact
Registry, deploys Cloud Run services, and wires Secret Manager values.

---

## Migration State (where this branch is)

This branch (`microservices-boundary-foundation`) is mid-decomposition. The detailed
plan and acceptance gates live in:

- `docs/ARCHITECTURE-HLD.md` / `docs/ARCHITECTURE-LLD.md` — current target architecture
- `docs/MICROSERVICES-COMPLETION-PLAN.md` — phased completion order & cutover checklist
- `docs/MICROSERVICE-ROLLBACK-RUNBOOK.md` / `docs/MICROSERVICE-OBSERVABILITY-RUNBOOK.md`

Historical `docs/MONOLITH_*` and `docs/*MIGRATION*` files describe the pre-split state and
the removal path — they are intentionally historical; do not treat them as current.
