# Project Architecture

Last verified: 2026-07-09.

## Product Scope

Custoking IMS is a multi-tenant school operations platform. The current deployed architecture is a Cloud Run split-service system with:

- React/Vite frontend.
- Programmable Node API gateway.
- Five Spring Boot domain/runtime services.
- PostgreSQL on Cloud SQL.
- Pub/Sub-backed asynchronous reporting and notification ingress.
- GCP-native observability.

## Current Runtime Topology

```text
Browser
  -> custoking-frontend-<env>     (Cloud Run, public)
  -> custoking-api-gateway-<env>  (Cloud Run, public)
  -> private Cloud Run services   (Cloud Run IAM protected)
  -> Cloud SQL PostgreSQL         (private IP over default VPC)

Async:
domain services -> transactional outbox -> Pub/Sub topic -> platform-service push ingress
```

Both environments live in the single GCP project `custoking` and region `asia-south2`.

Environment separation is by suffix:

- Cloud Run services: `custoking-<service>-dev` and `custoking-<service>-prod`
- Secret Manager secrets: `<secret>-dev` and `<secret>-prod`
- Pub/Sub topics/subscriptions: `...-dev` and `...-prod`
- Cloud SQL instances/databases: `custoking-db-dev` / `custoking_dev`, `custoking-db-prod` / `custoking_prod`

## Current Service Topology

The deployed topology is seven Cloud Run services per environment:

| Service | Runtime | Public | Responsibility |
| --- | --- | --- | --- |
| `custoking-frontend-<env>` | nginx serving built React app | Yes | SPA shell and static frontend assets |
| `custoking-api-gateway-<env>` | Node HTTP proxy | Yes | Route ownership, auth enforcement, local JWT verification, service token injection, CORS/security headers, Cloud Run ID-token upstream auth |
| `custoking-identity-service-<env>` | Spring Boot 4 / Java 25 | No | Login, refresh/logout, token introspection, users, RBAC, roles, permissions, assignments |
| `custoking-school-core-service-<env>` | Spring Boot 4 / Java 25 | No | Tenant/school, zones, classes, sections, staff, timetable, students, attendance, fees, catalog/supply |
| `custoking-operations-service-<env>` | Spring Boot 4 / Java 25 | No | Workflow and firefighting workflows |
| `custoking-platform-service-<env>` | Spring Boot 4 / Java 25 | No | Reporting, command center, notification, audit, Pub/Sub push receivers, projections |
| `custoking-billing-service-<env>` | Spring Boot 4 / Java 25 | No | Superadmin invoices, customers, billing payments, billing outbox |

Older documentation in the repo still contains the pre-consolidation twelve-service topology. The current code and deployment pipeline use the five merged Spring services plus gateway and frontend.

## Data Ownership

| Schema | Owning runtime service | Main responsibility |
| --- | --- | --- |
| `identity` | identity-service | Users, auth sessions, roles, permissions, user role assignments, RBAC audit |
| `tenant_school` | school-core-service | Schools, classes, sections, zones, staff, modules, timetable, shared outbox |
| `student` | school-core-service | Students, imports, photo metadata, review campaigns/items |
| `attendance` | school-core-service | Daily attendance, student records, absentee notifications |
| `fee` | school-core-service | Fee bands, fee items, assignments, payment records |
| `catalog` | school-core-service | Catalog items, supply orders, catalog orders, annual plans |
| `workflow` | operations-service | Workflow definitions, steps, instances, actions |
| `firefighting` | operations-service | Firefighting requests, quotations, firefighting outbox |
| `reporting` | platform-service | Command center, read projections, event inbox, facts/dimensions |
| `notification` | platform-service | Notification inbox, broadcasts, delivery attempts/logs, sender profiles, WhatsApp onboarding |
| `audit` | platform-service | Audit events |
| `billing` | billing-service | Superadmin invoices, order sequence, school billing tables, billing outbox |

Runtime code must not reintroduce reads or writes to retired monolithic `public` domain tables.

## Request Flow

1. The browser calls the gateway through `/api/v1/**`.
2. The gateway matches the path to a logical service route.
3. For protected API paths and `GATEWAY_AUTH_MODE=enforce`, the gateway authenticates the bearer token.
4. The gateway stamps trusted identity/tenant headers, injects the target service's internal token header, and proxies to the owning Cloud Run service.
5. Private Spring services validate their internal service token and use the gateway-stamped tenant headers to establish request context.
6. Spring services query Cloud SQL as `app_rt`; Flyway runs migrations as `appuser`.

## Gateway Responsibilities

Source: `services/api-gateway/server.js`.

The gateway owns:

- CORS allowlist (`GATEWAY_CORS_ALLOWED_ORIGINS`).
- Security headers including CSP, HSTS, and referrer policy.
- Request body size protection.
- Token-bucket rate limiting.
- Local HS512 JWT verification when `GATEWAY_LOCAL_JWT_VERIFY` is enabled and `APP_JWT_SECRET` is present.
- Fallback token introspection through identity-service.
- Cloud Run ID-token upstream auth when `GATEWAY_CLOUD_RUN_AUTH=auto`.
- Per-service token header injection:
  - `X-Identity-Service-Token`
  - `X-Tenant-School-Token`
  - `X-Student-Service-Token`
  - `X-Attendance-Service-Token`
  - `X-Fee-Service-Token`
  - `X-Catalog-Service-Token`
  - `X-Workflow-Service-Token`
  - `X-Firefighting-Service-Token`
  - `X-Reporting-Service-Token`
  - `X-Billing-Service-Token`
  - `X-Audit-Service-Token`
  - `X-Notification-Service-Token`

## Route Ownership

Source: gateway route table in `services/api-gateway/server.js`.

| Route group | Gateway target |
| --- | --- |
| `/api/v1/auth/**`, `/api/v1/rbac/**`, `/api/v1/users`, selected school/zone admin user creation routes | identity |
| `/api/v1/classes/**`, `/api/v1/schools/**`, `/api/v1/zones/**`, `/api/v1/academic-years`, `/api/v1/workspace/staff`, `/api/v1/timetable/**`, `/api/v1/sa/schools` | school-core tenant/school |
| `/api/v1/students/**`, `/api/v1/workspace/students`, `/api/v1/student-review-items/**`, class-section-students roster route | school-core student |
| `/api/v1/attendance/**` | school-core attendance |
| `/api/v1/fee-structure`, `/api/v1/fee-assignments`, `/api/v1/payments`, `/api/v1/fees/**`, `/api/v1/receipts/**`, fee reminder dashboard action | school-core fee |
| `/api/v1/supply/**`, `/api/v1/sa/orders`, catalog vendor-paid dashboard action | school-core catalog |
| `/api/v1/workflows/**` | operations workflow |
| `/api/v1/ff/**`, `/api/v1/workspace/firefighting`, firefighting vendor-paid dashboard action | operations firefighting |
| `/api/v1/dashboard/**`, `/api/v1/dashboard`, `/api/v1/workspace`, `/api/v1/command-centre/**`, `/api/v1/approvals/**` | platform reporting |
| `/api/v1/notifications/**` | platform notification |
| `/api/v1/audit-logs` | platform audit |
| `/api/v1/sa/invoices/**`, `/api/v1/customers/**`, `/api/v1/invoices/**`, `/api/v1/billing-payments/**` | billing |

Diagnostic service prefixes also exist, such as `/identity-api/v1/`, `/tenant-api/v1/`, `/reporting-api/v1/`, and `/billing-api/v1/`.

## Authentication Model

Source: `JwtService.java`, `AuthController.java`, gateway source, frontend API source.

- Access tokens are JWTs signed by identity-service with a shared HS512 secret from Secret Manager.
- Identity requires `APP_JWT_SECRET` to be at least 32 characters.
- Access token TTL defaults to `900000` ms.
- Refresh token TTL defaults to `604800000` ms.
- Refresh token is stored in an HttpOnly cookie named `refresh_token`.
- In deployed dev/prod Cloud Run, identity sets `APP_COOKIE_SECURE=true` and `APP_COOKIE_SAME_SITE=None`.
- Frontend stores the access token only in module memory, not localStorage/sessionStorage.
- On frontend 401 for non-auth endpoints, the frontend calls `/auth/refresh` once per concurrent burst and retries.

Enriched JWT claims used by gateway local verification:

- `uid` - user id
- `sid` - school id when present
- `zid` - zone id when present
- `role` - role name
- `perms` - permission codes
- `ver` - currently `3`
- `ops_schools` - assigned operator school ids

If a token is valid but not enriched enough for gateway local verification, the gateway falls back to identity introspection.

## Tenant Isolation and RLS

Source: `TenantContextFilter`, `TenantAwareDataSource`, `RuntimeDbRoleGuard`, RLS migrations.

Tenant isolation has two layers:

- Application scope checks through `TenantScope`.
- PostgreSQL RLS backstop on tenant-scoped tables.

Runtime DB role conventions:

- Application runtime user: `app_rt`
- Flyway/DDL owner: `appuser`
- `RuntimeDbRoleGuard` fails startup in the `prod` profile if a Spring service connects as anything other than `app_rt`.

Gateway-stamped request headers populate `TenantContext`:

- `X-Authenticated-User-Id`
- `X-Authenticated-Email`
- `X-Authenticated-Role`
- `X-Authenticated-School-Id`
- `X-Authenticated-Zone-Id`
- `X-Authenticated-Operator-Schools`

`TenantAwareDataSource` sets PostgreSQL GUCs on every borrowed connection:

- `app.current_school_id`
- `app.bypass_rls`
- `app.operator_schools` in school-core

Superadmin requests set `app.bypass_rls=on`. Normal school users are scoped to the authenticated school. Operations users can be bounded to an assigned operator school set.

RLS is enabled by migrations on many tenant-scoped tables across billing, school-core, operations, platform reporting, and notification. Some async/contextless tables intentionally do not use RLS, including `reporting.reporting_event_inbox` and `reporting.command_center_feed`.

## Frontend Architecture

Source: `frontend/package.json`, `frontend/src/App.tsx`, `frontend/src/services/api.ts`.

- React 18, Vite 6, TypeScript 5.7, React Router 6.
- Top-level routes:
  - `/login`
  - `/`
  - `/dashboard`
  - `/schools`
  - `/zones`
  - wildcard redirect to `/dashboard`
- API base URL defaults to `/api/v1`.
- Axios timeout is `30000` ms.
- `withCredentials=true` is set for refresh-cookie flows.

## Architecture Constraints

- Do not add new direct cross-service database reads for runtime behavior.
- Do not edit already-applied Flyway migrations without a repair/baseline plan.
- Use forward migrations for schema changes.
- Keep gateway route ownership explicit.
- Keep service-to-service tokens in Secret Manager and injected at deploy time.
- Keep async integrations idempotent by `eventId`.
- Keep local dev credentials and test tokens distinct from production secrets.
