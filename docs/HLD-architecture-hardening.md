# Custoking IMS HLD: Architecture Hardening

Date: 2026-06-16
Branch: architecture-hardening-local-dev

## 1. Executive Summary

Custoking IMS should stay a modular monolith for the current stage. The product still has tightly coupled workflows across schools, students, fees, attendance, supply orders, firefighting procurement, workflows, and audit. Splitting these into microservices now would add distributed transaction, deployment, observability, and data consistency overhead before the internal boundaries are mature.

This branch hardens the monolith so it behaves more like a well-structured platform:

- Local development runs through Tilt and Docker Compose.
- Backend startup has explicit local secrets and health readiness.
- Authentication now uses server-side refresh sessions with rotation and replay protection.
- Tenant scope resolution is centralized and applied to the highest-risk school-scoped services.
- High-traffic controller request bodies now use typed DTOs instead of raw request maps.
- Workflow-sensitive firefighting actions have server-side transition checks.
- Frontend route and workspace panel code is lazy-loaded to reduce initial bundle pressure.
- CI is restored as a build gate and now includes a GCP deployment-config check.
- The manual GitHub Actions / Cloud Build / Cloud Run deployment path is preserved.
- Architecture guardrail tests now protect key package dependency directions.

## 2. Target Architecture

```text
Browser SPA
  |
  | HTTPS in production, localhost in dev
  v
Nginx static frontend
  |
  | /api/v1 reverse proxy
  v
Spring Boot modular monolith API
  |
  | JDBC, Flyway migrations
  v
PostgreSQL

Supporting concerns:
  - Secrets manager in production
  - Object storage for uploads
  - Metrics, logs, traces
  - CI build and deployment-config gate
  - GitHub Actions to Cloud Build / Cloud Run for production deploys
  - Tilt/Docker Compose for local development
```

## 3. Backend Module View

Current code has a mix of feature packages and legacy root packages. The intended module map is:

- auth: login, refresh, logout, token/session lifecycle
- tenancy: TenantContext, TenantScope, school/zone access resolution
- schools: school onboarding, admins, operations users
- students: enrollment, bulk import, student profile/photo
- attendance: daily attendance capture and summary
- fees: fee structure, assignments, collection, receipts
- supply: catalog orders, annual plans, approvals, invoices
- firefighting: non-catalog procurement and approval flow
- workflow: reusable approval instances/actions
- audit: immutable event and access logging
- common: cross-module domain constants and shared value objects

The near-term goal is not a physical split. The goal is enforcing direction:

```text
controller -> dto -> service/application -> domain -> repo/entity
                         |
                         v
                       audit/common
```

Controllers should not import repositories or entities. DTOs should remain transport-only. Domain packages should not depend on web, config, or security packages.

## 4. Authentication And Sessions

### Implemented Flow

```text
POST /api/v1/auth/login
  -> verify password
  -> issue access JWT with jti
  -> issue refresh JWT with jti and type=refresh
  -> store SHA-256 digests in auth_sessions
  -> send refresh token as HttpOnly cookie

POST /api/v1/auth/refresh
  -> require refresh cookie
  -> validate JWT type and expiry
  -> find digest in auth_sessions
  -> rotate access and refresh tokens
  -> update same auth_sessions row
  -> old refresh token becomes invalid

POST /api/v1/auth/logout
  -> delete matching refresh digest from auth_sessions
  -> clear cookie
```

### Benefits

- Refresh token replay is rejected.
- Logout invalidates the server-side refresh session.
- Raw tokens are not stored in PostgreSQL.
- Access tokens are short-lived by default.
- Refresh tokens cannot be used as bearer access tokens.
- JWT `jti` prevents duplicate token collisions during concurrent logins.

### Trade-offs

- Refresh requires a database lookup.
- Logout only revokes the refresh session; an already issued access token remains valid until expiry.
- Full device/session management is not implemented yet.
- Access-token revocation on every request would require either DB checks or a cache-backed denylist.

## 5. Tenancy And Data Isolation

### Implemented Model

`TenantResolverFilter` builds a `TenantScope` for each authenticated request. `TenantAccess` now centralizes school resolution:

- SUPERADMIN can remain global or choose any school explicitly.
- school-level users always resolve to their own school.
- ZONE_ADMIN users resolve only to active mapped schools.
- explicit access to an unmapped school returns 403.

This branch applies the centralized resolver to:

- workspace
- students
- fees
- attendance
- supply
- firefighting

### Benefits

- Reduces inconsistent `schoolId` handling across services.
- Prevents branch users from using query/body `schoolId` to hop tenants.
- Prevents zone admins from accessing unmapped schools.
- Provides a single place to evolve tenancy rules.

### Trade-offs

- App-level tenant checks still require discipline in every service.
- PostgreSQL RLS remains disabled by migration V117, so database-level defense is not active.
- Superadmin global views still need careful endpoint-by-endpoint handling.

### Recommended Next Step

Re-enable PostgreSQL RLS only after:

- all school-scoped repositories have explicit tenant predicates or tested scope resolution
- the app role is low privilege
- Flyway/migration role is separate
- integration tests prove cross-school isolation for ADMIN, ZONE_ADMIN, and SUPERADMIN

## 6. API Contract Strategy

This branch converts the highest-risk request-map controller inputs to typed request DTOs:

- attendance daily entry and day submission
- fee assignment, payment, reminders
- firefighting request/quotation/decision commands
- supply order, status update, return, annual-plan item commands

Services still consume maps in several places. This is intentional for this step: the edge contract is now typed without forcing a risky service rewrite in the same branch.

### Benefits

- Bad payloads fail earlier with validation errors.
- OpenAPI schemas become clearer.
- Frontend/backend field expectations are easier to see.
- Future service refactors can move from `Map<String,Object>` to command objects one feature at a time.

### Trade-offs

- The service layer still has legacy map parsing.
- Some DTOs keep flexible `Object` fields for category-specific supply data.
- Validation is stronger than before but not yet complete domain validation.

## 7. Frontend Architecture

The frontend remains a Vite React SPA. This branch adds route and panel lazy loading:

```text
App route chunks:
  - login in main bundle
  - dashboard workspace lazy
  - school management lazy
  - zone management lazy

Workspace panel chunks:
  - students
  - fees
  - fee structure
  - attendance
  - catalog
  - firefighting panels
  - superadmin panels
```

### Benefits

- The browser does not download all workspace panels before first render.
- Large panels such as catalog and fee structure become separate chunks.
- Nginx now serves a more cacheable asset graph.

### Trade-offs

- The first open of a panel may incur a small network delay.
- `UnifiedWorkspacePage.tsx` is still a large orchestration component and should be reduced further.
- `xlsx` remains a large dependency chunk and should be loaded only where import/export is needed.

## 8. Local Development And Tilt

Local architecture is:

```text
Tiltfile
  -> docker-compose.yml
      -> postgres healthcheck
      -> backend waits for postgres
      -> backend exposes actuator health
      -> frontend waits for backend healthy
      -> frontend Nginx proxies /api/v1 to backend
```

### Benefits

- `tilt up` reflects the same container path used by Compose.
- Backend readiness is based on `/actuator/health`, not container start.
- Frontend avoids booting against a backend that is still migrating or starting.

### Trade-offs

- Backend image installs curl for healthchecks, which adds a little image size.
- Current Tilt setup is Compose-based and does not yet use file-sync live update.

## 9. CI And Deployment

CI now has four jobs:

- Backend verify: `mvn -B -Pci verify`
- Frontend build: `npm ci`, critical dependency audit, and `npm run build`
- GCP deployment config check: verifies `.github/workflows/deploy.yml`, `cloudbuild.yaml`, `deploy/gcp/README.md`, Cloud Build submission, Cloud Run deploy markers, frontend backend URL build arg, production cookie settings, and required secrets.
- Container validation: `docker compose config`, backend image build, frontend image build

Manual production deployment is preserved in `.github/workflows/deploy.yml`. It authenticates to GCP with Workload Identity and submits `cloudbuild.yaml`. The workflow waits for Cloud Build to finish so GitHub reports deployment failure instead of only reporting that submission succeeded.

`cloudbuild.yaml` builds and pushes backend/frontend images, deploys both Cloud Run services, builds the frontend with `VITE_API_BASE_URL=${_BACKEND_PUBLIC_URL}/api/v1`, and configures the backend with production CORS, secure cross-site refresh cookies, JWT secret, Aadhaar secret, database password, and Flyway password from Secret Manager.

### Benefits

- Local development remains Tilt/Compose-first without losing the production deployment path.
- CI fails if the GCP deployment workflow or key Cloud Build production settings disappear again.
- CI fails if the GitHub deploy workflow is changed back to asynchronous Cloud Build submission.
- Frontend API routing is explicit for Cloud Run, where the frontend and backend are separate origins.
- Refresh-token cookies work in production's separate Cloud Run origin model through `SameSite=None; Secure`.

### Trade-offs

- The deployment check is a lightweight configuration guard, not a real Cloud Build dry run.
- Cloud Run public URLs are currently substitutions in `cloudbuild.yaml`; service URL changes require updating the file or overriding substitutions.
- Cross-site refresh cookies depend on HTTPS and careful CORS configuration.

## 10. Observability And Operations

Current baseline:

- Actuator health, metrics, Prometheus endpoint
- structured logging via logstash encoder
- request correlation filter
- Docker healthcheck for backend

Recommended next:

- add OpenTelemetry tracing
- ship logs to a centralized store
- add dashboards for login failures, refresh replay, tenant access denied, request latency, DB pool saturation
- add alerting on health, error rate, and auth anomaly signals

## 11. Migration Path

1. Keep this modular monolith as the deployable unit.
2. Move one feature at a time from legacy `service/repo/entity` roots into feature modules.
3. Replace map-based service methods with typed command/result objects.
4. Add stricter Spring Modulith verification when feature packages are moved.
5. Reintroduce PostgreSQL RLS after app-level tenant tests are comprehensive.
6. Consider service extraction only when a module has independent scaling, ownership, data, and release needs.

## 12. References

- Spring Modulith: https://docs.spring.io/spring-modulith/reference/fundamentals.html
- Spring Boot external config: https://docs.spring.io/spring-boot/reference/features/external-config.html
- Spring Boot container images: https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html
- PostgreSQL row security: https://www.postgresql.org/docs/current/ddl-rowsecurity.html
- PostgreSQL EXPLAIN: https://www.postgresql.org/docs/current/using-explain.html
- OWASP Authentication Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html
- OWASP Secrets Management Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html
- React lazy: https://react.dev/reference/react/lazy
- Vite build: https://vite.dev/guide/build
- Tilt Docker Compose: https://docs.tilt.dev/docker_compose.html
- Docker Compose services: https://docs.docker.com/reference/compose-file/services/
