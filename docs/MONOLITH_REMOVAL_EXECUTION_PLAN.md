# Monolith Removal Execution Plan

Date: 2026-06-26

This document is an execution playbook for safely and completely removing the remaining monolithic backend code from this repository after the domain services have been physically extracted. It is written for a high-context coding agent and should be followed as an implementation plan, not as background reading.

## Objective

Remove the legacy Spring Boot monolith from the repo without breaking the product:

- Frontend continues to work through the API gateway.
- All domain features continue to be served by extracted services.
- No runtime dependency remains on `backend/` for normal application flows.
- CI, Docker Compose, Cloud Build, GitHub Actions, and GCP deployment no longer build or deploy the monolith.
- Historical database migrations remain safe for existing environments.
- The final repository has a clear service-only architecture.

## Current State Snapshot

The repo is already partially migrated.

Active extracted services live under `services/`:

- `services/identity-service`
- `services/tenant-school-service`
- `services/student-service`
- `services/attendance-service`
- `services/fee-service`
- `services/catalog-service`
- `services/workflow-service`
- `services/audit-service`
- `services/notification-service`
- `services/reporting-service`
- `services/firefighting-service`
- `services/billing-service`
- `services/api-gateway`

The frontend lives under `frontend/`.

The legacy/compatibility backend still lives under `backend/`. It has already had many direct monolith domain classes deleted, but it still contains compatibility code:

- Controllers that preserve old `/api/v1/...` contracts.
- Service clients that call extracted services.
- Security/JWT/tenant filters.
- Outbox/event publisher code.
- Compatibility health indicators.
- Legacy migration history.
- Some tests and configuration.

The recent successful local verification before this plan:

- Gateway route smoke: `30/30` passed.
- Microservice feature smoke: `58/58` passed.
- Outbox to reporting projection smoke passed.
- PowerShell script parse check: `51` scripts passed.
- Backend compile, all service compiles, frontend tests, and frontend build passed earlier in the cleanup sequence.

## Context-Sized Execution Phases

Use this section as the primary execution structure for a high-context implementation worker. Do not try to perform the entire removal in one reasoning pass. Execute one phase packet at a time, write a short handoff note at the end of each phase, then start the next phase from that handoff and this document.

Each phase packet has:

- `Goal`: what must be true when the phase ends.
- `Load into context`: files/directories that should be read for that phase.
- `Do not load unless needed`: large areas that should be searched with `rg` first instead of opened wholesale.
- `Edits allowed`: files that may be changed in the phase.
- `Validation`: commands that prove the phase is complete.
- `Handoff`: what to record before moving on.

### Phase A: Baseline Inventory and Route Ownership Map

Goal: create a precise map of every public API route and determine whether it is already owned by an extracted service or still depends on `backend/`.

Load into context:

- `services/api-gateway/nginx.conf.template`
- `services/api-gateway/nginx.conf` if present
- `docker-compose.yml`
- `frontend/src/services/api.ts`
- `frontend/src/api/dashboardCommandCenterApi.ts`
- `frontend/src/contexts/AuthContext.tsx`
- `scripts/smoke-gateway-routes.ps1`
- `scripts/smoke-microservice-features.ps1`
- `scripts/smoke-deployment-readiness.ps1`
- Backend controller file list from `rg`, not all controller contents initially.

Do not load unless needed:

- Entire `backend/src/main/java`
- Entire `services/*/src/main/java`
- Entire `frontend/src`

Commands:

```powershell
rg -n "@(Get|Post|Put|Patch|Delete|Request)Mapping|@RestController" backend/src/main/java services/*/src/main/java
rg -n "proxy_pass|location |upstream|BACKEND|IDENTITY|REPORTING|STUDENT|ATTENDANCE|FEE|CATALOG|WORKFLOW|AUDIT|NOTIFICATION|TENANT|BILLING|FIREFIGHTING" services/api-gateway
rg -n "api/v1|/api/" frontend/src scripts -g "*.ts" -g "*.tsx" -g "*.ps1"
```

Edits allowed:

- `docs/MONOLITH_ROUTE_OWNERSHIP_MAP.md` only.
- Do not delete code in this phase.

Create `docs/MONOLITH_ROUTE_OWNERSHIP_MAP.md` with a table:

```text
| Public path | Method | Current gateway target | Backend controller if any | Target service | Status | Required action |
```

Use these statuses:

- `service-owned`: route already served directly by extracted service.
- `backend-proxy`: route is in backend only as a pass-through/facade.
- `backend-logic`: route has business logic still only in backend.
- `obsolete`: no frontend/script/smoke caller found.
- `unclear`: needs inspection.

Validation:

```powershell
Test-Path docs\MONOLITH_ROUTE_OWNERSHIP_MAP.md
rg -n "unclear|backend-logic|backend-proxy|obsolete|service-owned" docs\MONOLITH_ROUTE_OWNERSHIP_MAP.md
```

Handoff:

- Count each status.
- List the highest-risk `backend-logic` routes.
- List routes that block backend deletion.

Do not proceed to Phase B until every `unclear` route is resolved or explicitly documented with the file/path that must be inspected.

### Phase B: Close Backend-Only API Gaps in Services

Goal: every route needed by frontend/smoke/deployment readiness is implemented in an extracted service.

Load into context:

- `docs/MONOLITH_ROUTE_OWNERSHIP_MAP.md`
- Only the backend controller/service files for `backend-logic` and `backend-proxy` rows.
- The target service controller/repository/application files for those routes.
- The frontend caller file for each affected route.
- Relevant smoke script section.

Do not load unless needed:

- Unrelated services.
- Full backend package.

Edits allowed:

- `services/<owning-service>/src/main/java/**`
- `services/<owning-service>/src/main/resources/**`
- `scripts/smoke-gateway-routes.ps1`
- `scripts/smoke-microservice-features.ps1`
- `scripts/smoke-deployment-readiness.ps1`
- `docs/MONOLITH_ROUTE_OWNERSHIP_MAP.md`
- Focused docs if endpoint behavior changes.

Execution loop for each route:

1. Open old backend route implementation.
2. Open frontend/smoke caller.
3. Open owning service API package.
4. Implement equivalent route in owning service.
5. Preserve request/response shape used by frontend.
6. Prefer service-owned schema/API/event projection over direct cross-service tables.
7. Add/update smoke coverage.
8. Compile the owning service.
9. Mark route `service-owned` in the ownership map.

Compile command template:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -f services\<service-name>\pom.xml -DskipTests compile
```

Validation:

```powershell
rg -n "backend-logic|backend-proxy|unclear" docs\MONOLITH_ROUTE_OWNERSHIP_MAP.md
```

Expected: no matches, except routes intentionally marked `obsolete` with proof.

Handoff:

- List services edited.
- List routes moved.
- List any intentionally obsolete routes.
- Record compile status per edited service.

Do not proceed to Phase C until no required route depends on backend implementation.

### Phase C: Gateway Cutover Away From Backend

Goal: API gateway routes all public API traffic directly to extracted services.

Load into context:

- `docs/MONOLITH_ROUTE_OWNERSHIP_MAP.md`
- `services/api-gateway/nginx.conf.template`
- `services/api-gateway/nginx.conf` if maintained
- `docker-compose.yml`
- `scripts/smoke-gateway-routes.ps1`

Do not load unless needed:

- Backend code.
- Service internals.

Edits allowed:

- `services/api-gateway/**`
- `docker-compose.yml` only for gateway environment variables in this phase
- `scripts/smoke-gateway-routes.ps1`
- `docs/MONOLITH_ROUTE_OWNERSHIP_MAP.md`

Required route target pattern:

```text
/api/v1/auth/** -> identity-service
/api/v1/rbac/** -> identity-service
/api/v1/users/** -> identity-service
/api/v1/schools/** -> tenant-school-service
/api/v1/zones/** -> tenant-school-service
/api/v1/students/** -> student-service
/api/v1/attendance/** -> attendance-service
/api/v1/classes/** -> fee-service or student-service, based on ownership map
/api/v1/fee-structure/** -> fee-service
/api/v1/fees/** -> fee-service
/api/v1/supply/** -> catalog-service
/api/v1/workflows/** -> workflow-service
/api/v1/audit-logs/** -> audit-service
/api/v1/notifications/** -> notification-service
/api/v1/command-centre/** -> reporting-service
/api/v1/dashboard/** -> reporting-service or mapped owner
/api/v1/ff/** -> firefighting-service
/api/v1/sa/invoices/** -> billing-service
/api/v1/sa/orders/** -> catalog-service or mapped owner
```

Validation:

```powershell
rg -n "custoking-backend|backend:8080|BACKEND_|BACKEND_SERVICE|localhost:8080" services/api-gateway docker-compose.yml
```

Expected: no gateway/backend route dependency remains.

Runtime validation, only after service compiles from Phase B passed:

```powershell
docker compose up -d --build
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-gateway-routes.ps1 -GatewayBaseUrl http://localhost
docker compose stop
```

Handoff:

- Confirm gateway smoke count and failures.
- Confirm no gateway backend references remain.
- Note whether Docker was stopped.

Do not proceed to Phase D if any gateway route still targets backend.

### Phase D: Remove Backend From Local Runtime

Goal: Docker Compose no longer starts `custoking-backend`, and the full feature smoke passes without it.

Load into context:

- `docker-compose.yml`
- `.env.example`
- `frontend/.env.example`
- `services/api-gateway/nginx.conf.template`
- Smoke scripts.

Do not load unless needed:

- Backend code.

Edits allowed:

- `docker-compose.yml`
- `.env.example`
- `frontend/.env.example`
- `README.md` local runtime section
- `scripts/smoke-*.ps1` if they assume backend container/port

Steps:

1. Remove `backend:` service block from `docker-compose.yml`.
2. Remove dependencies on `backend`.
3. Remove backend host port mapping, especially `8080:8080`.
4. Remove backend-only env vars.
5. Ensure gateway only depends on extracted services.
6. Ensure frontend still targets gateway.

Validation:

```powershell
rg -n "custoking-backend|backend:|backend\\b|8080:8080|BACKEND_" docker-compose.yml .env.example frontend/.env.example README.md scripts
docker compose config
docker compose up -d --build
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-gateway-routes.ps1 -GatewayBaseUrl http://localhost
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-microservice-features.ps1 -GatewayBaseUrl http://localhost
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-outbox-reporting-projection.ps1 -GatewayBaseUrl http://localhost
docker compose stop
```

Expected:

- No `custoking-backend` container.
- Gateway smoke passes.
- Microservice feature smoke passes.
- Outbox/reporting projection smoke passes.

Handoff:

- Container list proving backend is absent.
- Smoke result counts.
- Any Compose warnings.

Do not proceed to Phase E until local runtime works without backend.

### Phase E: Move Build Tooling Out of Backend

Goal: deleting `backend/` will not remove the Maven wrapper used by service builds.

Load into context:

- `mvnw.cmd`
- `mvnw`
- `backend/.mvn/**`
- All scripts/docs that mention `mvnw` or `mvnw`.

Do not load unless needed:

- Backend Java source.

Edits allowed:

- Root `mvnw`
- Root `mvnw.cmd`
- Root `.mvn/**`
- `scripts/**`
- `.github/**`
- `cloudbuild.yaml`
- `deploy/**`
- `README.md`
- `docs/**`

Steps:

1. If root wrapper does not exist, copy Maven wrapper from `backend/` to repo root.
2. Replace wrapper references:

```powershell
rg -n "backend\\\\mvnw|mvnw|\\.\\\\backend\\\\mvnw\\.cmd|\\./mvnw" scripts docs .github cloudbuild.yaml README.md deploy
```

3. Update commands to use:

```powershell
.\mvnw.cmd -f services\<service>\pom.xml -DskipTests compile
```

Validation:

```powershell
.\mvnw.cmd -f services\identity-service\pom.xml -DskipTests compile
.\mvnw.cmd -f services\reporting-service\pom.xml -DskipTests compile
rg -n "backend\\\\mvnw|mvnw|\\.\\\\backend\\\\mvnw\\.cmd|\\./mvnw" scripts docs .github cloudbuild.yaml README.md deploy
```

Expected: no backend wrapper references remain.

Handoff:

- Confirm root wrapper works.
- Confirm all wrapper references updated.

Do not proceed to Phase F until root Maven wrapper is verified.

### Phase F: Remove Backend From CI/CD and GCP Deployment

Goal: no CI/CD or GCP deployment path builds, pushes, deploys, grants IAM to, or waits for backend.

Load into context:

- `.github/workflows/ci.yml`
- `.github/workflows/deploy.yml`
- `cloudbuild.yaml`
- `deploy/gcp/README.md`
- `scripts/deploy-gcp-microservices.ps1`
- `scripts/*deployment*`
- `scripts/*readiness*`
- `scripts/*cloud-run*`
- `scripts/*iam*`
- `scripts/*secret*`

Do not load unless needed:

- Frontend source.
- Service internals.
- Backend source.

Edits allowed:

- `.github/**`
- `cloudbuild.yaml`
- `deploy/**`
- `scripts/**`
- `README.md`
- `docs/**`

Steps:

1. Remove backend Docker build/push.
2. Remove backend Cloud Run deploy.
3. Remove backend service URL discovery.
4. Remove backend IAM invoker grants.
5. Remove backend Secret Manager requirements.
6. Remove backend readiness checks.
7. Remove backend rollback/evidence export.
8. Ensure frontend, gateway, and all extracted services remain.

Validation:

```powershell
rg -n "backend|custoking-backend|backend-service|BACKEND|backend/Dockerfile" .github cloudbuild.yaml deploy scripts README.md docs
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\audit-deployment-boundaries.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\audit-microservice-build-catalog.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\audit-microservice-test-catalog.ps1
```

Expected:

- No live backend deploy/build reference remains.
- Any remaining docs mention backend only as removed historical architecture.

Handoff:

- List deployment files changed.
- List audit script results.
- Confirm no live backend CI/CD references.

Do not proceed to Phase G until deployment references are clean.

### Phase G: Frontend and API Contract Verification

Goal: frontend builds and tests against service-only API contracts.

Load into context:

- `frontend/src/services/api.ts`
- `frontend/src/contexts/AuthContext.tsx`
- `frontend/src/api/**`
- `frontend/src/pages/workspace/config.ts`
- Any frontend file flagged by `rg`.
- Gateway template.

Do not load unless needed:

- Backend code.
- Unrelated service internals.

Edits allowed:

- `frontend/**`
- `services/api-gateway/**` only if route mismatch is found
- Service API files only if response shape bug is proven

Commands:

```powershell
rg -n "localhost:8080|8080|backend|BACKEND|VITE_API|apiBase|baseURL" frontend/src frontend/.env.example frontend/README.md frontend/Dockerfile frontend/nginx.conf
Push-Location frontend
npm ci
npm test -- --run
npm run build
Pop-Location
```

Runtime validation:

```powershell
docker compose up -d --build
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-microservice-features.ps1 -GatewayBaseUrl http://localhost
docker compose stop
```

Handoff:

- Frontend test/build result.
- Any frontend API changes made.
- Smoke result.

Do not proceed to Phase H if frontend still assumes backend.

### Phase H: Physical Backend Deletion

Goal: delete `backend/` safely after every live dependency is gone.

Load into context:

- Output of all previous handoffs.
- Root directory listing.
- `git status --short`.
- Search results for backend references.

Do not load unless needed:

- Backend source. At this point it should not be needed.

Edits allowed:

- Delete `backend/`.
- Small docs cleanup for references discovered after deletion.

Pre-delete validation:

```powershell
rg -n "backend|custoking-backend|BACKEND_|backend-service|backend/Dockerfile|backend\\\\mvnw|mvnw" .github cloudbuild.yaml deploy docker-compose.yml frontend services scripts README.md docs
Test-Path .\mvnw.cmd
Test-Path .\.mvn
```

Allowed before deletion:

- Historical docs saying the monolith was removed.
- No live command, build, runtime, deployment, or script should require `backend/`.

Delete safely:

```powershell
$target = Resolve-Path .\backend
if ($target.Path -ne "D:\Projects\ims-v1\backend") { throw "Unexpected backend path: $($target.Path)" }
Remove-Item -LiteralPath $target.Path -Recurse -Force
```

Post-delete validation:

```powershell
Test-Path .\backend
rg -n "backend|custoking-backend|BACKEND_|backend-service|backend/Dockerfile|backend\\\\mvnw|mvnw" .github cloudbuild.yaml deploy docker-compose.yml frontend services scripts README.md docs
```

Handoff:

- Confirm `backend/` is deleted.
- List any remaining historical doc references.
- Do not mark complete until Phase I passes.

### Phase I: Full Service-Only Stabilization Test

Goal: prove the repository works end to end after backend deletion.

Load into context:

- `docker-compose.yml`
- `scripts/smoke-*.ps1`
- Failing service logs only if a test fails.
- Relevant service code only for failures.

Do not load unless needed:

- All services at once.
- Docs.

Validation commands:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'
$env:Path="$env:JAVA_HOME\bin;$env:Path"

$services = @(
  "identity-service",
  "tenant-school-service",
  "student-service",
  "attendance-service",
  "fee-service",
  "catalog-service",
  "workflow-service",
  "audit-service",
  "notification-service",
  "reporting-service",
  "firefighting-service",
  "billing-service"
)

foreach ($service in $services) {
  .\mvnw.cmd -f "services\$service\pom.xml" -DskipTests compile
  if ($LASTEXITCODE -ne 0) { throw "$service compile failed" }
}

Push-Location frontend
npm ci
npm test -- --run
npm run build
Pop-Location

docker compose up -d --build
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-gateway-routes.ps1 -GatewayBaseUrl http://localhost
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-microservice-features.ps1 -GatewayBaseUrl http://localhost
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-outbox-reporting-projection.ps1 -GatewayBaseUrl http://localhost
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-deployment-readiness.ps1 -GatewayBaseUrl http://localhost
docker compose stop
```

Expected:

- All service compiles pass.
- Frontend tests/build pass.
- No `custoking-backend` container exists.
- Gateway smoke passes.
- Microservice feature smoke passes.
- Outbox/reporting projection smoke passes.
- Deployment readiness smoke passes.

Handoff:

- Final result summary.
- Any residual risks.
- Confirmation Docker stack stopped.

### Phase J: Documentation and Final Cleanup

Goal: repository documentation and generated artifacts are clean after the physical removal.

Load into context:

- `README.md`
- `docs/ARCHITECTURE-HLD.md`
- `docs/ARCHITECTURE-LLD.md`
- `docs/MICROSERVICES-MIGRATION-ROADMAP.md`
- `docs/MICROSERVICES-COMPLETION-PLAN.md`
- `deploy/gcp/README.md`
- `frontend/README.md`
- `docs/MONOLITH_ROUTE_OWNERSHIP_MAP.md`
- This file.

Edits allowed:

- Docs only, unless cleanup finds generated artifacts.

Steps:

1. Update docs to say monolith is removed.
2. Update local run commands.
3. Update deploy commands.
4. Update architecture diagrams/text.
5. Remove stale generated outputs.

Cleanup commands:

```powershell
Get-ChildItem -Path . -Directory -Recurse -Force -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -in @('target','node_modules','dist','.tools') } |
  Select-Object -ExpandProperty FullName

Get-ChildItem -Path . -File -Force -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -match 'deployment-readiness-smoke.*\.json|smoke.*\.json' } |
  Select-Object -ExpandProperty FullName
```

Delete generated outputs only after verifying resolved paths are inside `D:\Projects\ims-v1`.

Final validation:

```powershell
git status --short
rg -n "custoking-backend|backend-service|BACKEND_|backend/Dockerfile|backend\\\\mvnw|mvnw" .github cloudbuild.yaml deploy docker-compose.yml frontend services scripts README.md docs
```

Handoff:

- Final status.
- Files changed.
- Tests run.
- Remaining risks, if any.

## Non-Negotiable Safety Rules

1. Do not rewrite or delete applied migration files casually.
   - Files under `backend/src/main/resources/db/migration` and `services/*/src/main/resources/db/migration` are historical migration records.
   - If an environment may already have applied them, replacing or renumbering them can break Flyway validation.
   - Prefer forward-only migrations or archival notes.

2. Do not delete `backend/` until all of these are true:
   - API gateway no longer routes any production path to `backend`.
   - Frontend has no endpoint that requires `backend`.
   - Docker Compose does not start `backend`.
   - CI/CD does not build or deploy `backend`.
   - Smoke tests pass with `backend` absent.

3. Treat `backend/` as compatibility code until proven otherwise.
   - A class being under `backend/` does not automatically mean it is obsolete.
   - Some `backend/` code currently maps old public API contracts to extracted service APIs.

4. Keep deletion mechanical and auditable.
   - Remove references first.
   - Run targeted checks.
   - Delete code only after callers are gone.
   - Verify with `rg` and builds after each deletion batch.

5. Prefer service ownership over shared database shortcuts.
   - No new direct reads from another service schema unless explicitly accepted as a temporary read model.
   - No new FKs across service-owned schemas.
   - Reporting projections should consume events/read models, not call private writes.

## Target Architecture After Removal

Runtime topology:

```text
Browser
  -> frontend static app
  -> api-gateway
      -> identity-service
      -> tenant-school-service
      -> student-service
      -> attendance-service
      -> fee-service
      -> catalog-service
      -> workflow-service
      -> audit-service
      -> notification-service
      -> reporting-service
      -> firefighting-service
      -> billing-service
```

The monolith `backend` is not present in Docker Compose, GCP Cloud Run, or CI/CD.

Cross-service integration:

- Synchronous reads/writes go through service APIs via the gateway or internal service URLs.
- Asynchronous projections use the outbox/event contract.
- `identity-service` owns authentication and JWT issuance.
- `reporting-service` owns command center/dashboard projections.
- `notification-service` owns MSG91 integration and delivery logs.
- `audit-service` owns audit event storage.

## Phase 0: Baseline and Guardrails

Goal: make the next deletion work measurable.

Steps:

1. Confirm the working tree and branch.

```powershell
git status --short
git branch --show-current
```

2. Confirm Docker is stopped before static cleanup.

```powershell
docker ps --format "table {{.Names}}\t{{.Status}}"
```

3. Capture the current service catalog.

```powershell
Get-ChildItem services -Directory | Select-Object -ExpandProperty Name
```

4. Run static reference scans and save findings mentally before edits.

```powershell
rg -n "custoking-backend|backend:|backend/Dockerfile|backend\\\\|backend/" docker-compose.yml cloudbuild.yaml .github deploy scripts README.md docs frontend services
rg -n "localhost:8080|:8080|BACKEND|VITE_API|api/v1" frontend services/api-gateway docker-compose.yml .github cloudbuild.yaml deploy scripts
rg -n "com\\.custoking\\.ims\\.(entity|repo|service|model)|backend" services frontend scripts docs .github deploy
```

5. Establish the validation commands that must pass at the end.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'
$env:Path="$env:JAVA_HOME\bin;$env:Path"

.\mvnw.cmd -f services\identity-service\pom.xml -DskipTests compile
.\mvnw.cmd -f services\tenant-school-service\pom.xml -DskipTests compile
.\mvnw.cmd -f services\student-service\pom.xml -DskipTests compile
.\mvnw.cmd -f services\attendance-service\pom.xml -DskipTests compile
.\mvnw.cmd -f services\fee-service\pom.xml -DskipTests compile
.\mvnw.cmd -f services\catalog-service\pom.xml -DskipTests compile
.\mvnw.cmd -f services\workflow-service\pom.xml -DskipTests compile
.\mvnw.cmd -f services\audit-service\pom.xml -DskipTests compile
.\mvnw.cmd -f services\notification-service\pom.xml -DskipTests compile
.\mvnw.cmd -f services\reporting-service\pom.xml -DskipTests compile
.\mvnw.cmd -f services\firefighting-service\pom.xml -DskipTests compile
.\mvnw.cmd -f services\billing-service\pom.xml -DskipTests compile

Push-Location frontend
npm ci
npm test -- --run
npm run build
Pop-Location
```

Expected result: services and frontend pass before removing the monolith. If they do not pass before deletion, fix baseline breakages first.

## Phase 1: Identify Every Remaining Backend Runtime Dependency

Goal: produce a deletion map for `backend/`.

Run:

```powershell
rg -n "custoking-backend|BACKEND_URL|backend|8080|/api/v1" docker-compose.yml cloudbuild.yaml .github deploy scripts frontend services README.md docs
rg -n "proxy_pass|upstream|BACKEND|IDENTITY|REPORTING|STUDENT|ATTENDANCE|FEE|CATALOG|WORKFLOW|AUDIT|NOTIFICATION|TENANT|BILLING|FIREFIGHTING" services/api-gateway
rg -n "ReportingServiceClient|AuditServiceClient|ServiceClient|RestClient|WebClient" backend/src/main/java
rg -n "@RestController|@RequestMapping|@GetMapping|@PostMapping|@PutMapping|@PatchMapping|@DeleteMapping" backend/src/main/java
```

Classify each remaining `backend/` controller into one of these buckets:

- `delete-now`: endpoint already exists in extracted service and gateway routes directly to that service.
- `move-contract`: endpoint exists only in backend but the behavior belongs in an extracted service.
- `gateway-only`: endpoint exists only to proxy requests and should be replaced by nginx route config.
- `obsolete`: no frontend/smoke/script caller exists.

Likely `backend/` areas to classify:

- Auth facade: should be owned by `identity-service`.
- RBAC/user facade: should be owned by `identity-service`.
- School/module/zone facade: should be owned by `tenant-school-service`.
- Student import/photo/list/details facade: should be owned by `student-service`.
- Attendance facade: should be owned by `attendance-service`.
- Fee/payment/fee-structure facade: should be owned by `fee-service` and/or `billing-service`.
- Supply/catalog facade: should be owned by `catalog-service`.
- Workflow facade: should be owned by `workflow-service`.
- Command center/dashboard/reporting facade: should be owned by `reporting-service`.
- Notification broadcast/status facade: should be owned by `notification-service`.
- Audit facade: should be owned by `audit-service`.
- Firefighting facade: should be owned by `firefighting-service`.

Do not delete yet. First close any `move-contract` gaps.

## Phase 2: Move Any Remaining Backend-Only API Contracts to Services

Goal: every frontend/smoke/API path is served by an extracted service without `backend`.

For each `move-contract` controller method in `backend/src/main/java/com/custoking/ims/controller` or related packages:

1. Find the exact route and response shape.

```powershell
rg -n "METHOD_OR_PATH_FRAGMENT" backend/src/main/java frontend/src scripts services
```

2. Implement the route in the owning service.
   - Use the service's existing API package.
   - Preserve response fields used by frontend.
   - Preserve status codes where scripts/frontend rely on them.
   - Do not import monolith entities/repos into services.

3. If the route needs data owned by another service:
   - Prefer an existing service API.
   - If read-heavy dashboard data, project it into `reporting-service`.
   - If async side-effect, publish/consume an event.
   - Avoid new cross-schema FK or direct table dependency.

4. Add or update the smoke script entry for that route.

Primary smoke scripts:

- `scripts/smoke-gateway-routes.ps1`
- `scripts/smoke-microservice-features.ps1`
- `scripts/smoke-outbox-reporting-projection.ps1`
- `scripts/smoke-deployment-readiness.ps1`

5. Compile that service.

```powershell
.\mvnw.cmd -f services\<service-name>\pom.xml -DskipTests compile
```

Repeat until `backend/` has no unique route contract.

## Phase 3: Remove Backend From API Gateway Routing

Goal: no gateway path points to the monolith.

Edit:

- `services/api-gateway/nginx.conf.template`
- `services/api-gateway/nginx.conf` if present as generated/local static config
- `docker-compose.yml` gateway environment variables
- GCP deployment templates/scripts that define backend service URL

Required checks:

```powershell
rg -n "custoking-backend|backend:8080|BACKEND_|backend-service|BACKEND_SERVICE|localhost:8080" services/api-gateway docker-compose.yml deploy scripts .github cloudbuild.yaml frontend
```

Expected result: no production route or deploy config points to `backend`.

Gateway route expectations:

- `/api/v1/auth/**` -> `identity-service`
- `/api/v1/rbac/**` -> `identity-service`
- `/api/v1/users/**` -> `identity-service` if present
- `/api/v1/schools/**` -> `tenant-school-service`
- `/api/v1/zones/**` -> `tenant-school-service`
- `/api/v1/students/**` -> `student-service`
- `/api/v1/attendance/**` -> `attendance-service`
- `/api/v1/classes/**` -> `fee-service` or `student-service` depending current ownership
- `/api/v1/fee-structure/**` -> `fee-service`
- `/api/v1/fees/**` -> `fee-service`
- `/api/v1/supply/**` -> `catalog-service`
- `/api/v1/workflows/**` -> `workflow-service`
- `/api/v1/audit-logs/**` -> `audit-service`
- `/api/v1/notifications/**` -> `notification-service`
- `/api/v1/command-centre/**` -> `reporting-service`
- `/api/v1/dashboard/**` -> `reporting-service` or service-specific dashboard endpoint
- `/api/v1/ff/**` -> `firefighting-service`
- `/api/v1/sa/invoices/**` -> `billing-service`
- `/api/v1/sa/orders/**` -> `catalog-service` or `billing-service` based on current route ownership

After editing, build/reload gateway through Docker only after service compiles have passed.

## Phase 4: Remove Backend From Local Runtime

Goal: `docker compose up` starts no monolith container.

Edit `docker-compose.yml`:

- Remove the `backend:` service block.
- Remove `depends_on` references to `backend`.
- Remove backend-specific environment variables from gateway/frontend.
- Remove backend port `8080:8080` exposure.
- Keep Postgres and all extracted services.
- Keep service health checks.

Run:

```powershell
rg -n "custoking-backend|backend:|backend\\b|8080:8080|BACKEND_" docker-compose.yml
```

Expected result: no backend service reference remains. Be careful with service ports: some extracted service may expose host `8080` only if intentionally assigned. Do not accidentally shift ports used by smoke scripts unless updating the scripts.

## Phase 5: Remove Backend From CI/CD and GCP Deployment

Goal: no pipeline builds, pushes, deploys, grants IAM to, or smokes the monolith.

Edit:

- `.github/workflows/ci.yml`
- `.github/workflows/deploy.yml`
- `cloudbuild.yaml`
- `deploy/gcp/README.md`
- `scripts/deploy-gcp-microservices.ps1`
- deployment smoke/readiness scripts if they enumerate services
- any Cloud Run IAM/catalog scripts that include backend

Search:

```powershell
rg -n "backend|custoking-backend|backend-service|BACKEND|8080" .github cloudbuild.yaml deploy scripts README.md docs
```

Expected changes:

- Remove backend Docker build.
- Remove backend image push.
- Remove backend Cloud Run deployment.
- Remove backend Cloud Run URL export.
- Remove backend Secret Manager requirements.
- Remove backend IAM invoker grants.
- Remove backend readiness checks.
- Remove backend rollback evidence collection.
- Update service count documentation.

Keep any script path containing `mvnw.cmd` only if it is used as the Maven wrapper executable to compile services. Long-term, replace it with a root Maven wrapper or service-local wrapper, but do not block monolith removal on that if the wrapper is the only remaining `backend` dependency.

Recommended improvement:

- Move Maven wrapper out of `backend/` to repo root before deleting `backend/`.
- Copy `mvnw.cmd`, `mvnw`, and `backend/.mvn/` to repo root if licensing/contents are standard.
- Update all scripts from `.\mvnw.cmd -f services\...` to `.\mvnw.cmd -f services\...`.
- Then `backend/` can be physically deleted without losing the build wrapper.

## Phase 6: Remove Backend From Frontend Assumptions

Goal: frontend only depends on the gateway API base URL.

Check:

```powershell
rg -n "localhost:8080|8080|backend|BACKEND|VITE_API|apiBase|baseURL" frontend/src frontend/.env.example frontend/README.md frontend/Dockerfile frontend/nginx.conf
```

Expected:

- Frontend uses relative `/api` or a configurable gateway URL.
- No direct backend host or port.
- No UI route depends on a backend-only API path.

Run:

```powershell
Push-Location frontend
npm ci
npm test -- --run
npm run build
Pop-Location
```

## Phase 7: Move Build Wrapper Out of Backend

Goal: make `backend/` removable without losing Maven build capability.

If repo root does not already have Maven wrapper files:

1. Copy wrapper files from `backend/` to repo root.
2. Update scripts and docs to use root wrapper.
3. Verify all services compile through root wrapper.

Files to create/keep at root:

- `mvnw`
- `mvnw.cmd`
- `.mvn/wrapper/...`

Update references:

```powershell
rg -n "backend\\\\mvnw|mvnw|\\.\\\\backend\\\\mvnw\\.cmd|\\./mvnw" scripts docs .github cloudbuild.yaml README.md
```

Replace with:

```powershell
.\mvnw.cmd -f services\<service>\pom.xml -DskipTests compile
```

Validation:

```powershell
.\mvnw.cmd -f services\identity-service\pom.xml -DskipTests compile
.\mvnw.cmd -f services\reporting-service\pom.xml -DskipTests compile
```

Then run all service compiles.

## Phase 8: Delete the Physical Monolith

Goal: remove `backend/` entirely after references are gone.

Pre-delete proof:

```powershell
rg -n "backend|custoking-backend|BACKEND_|backend-service" .github cloudbuild.yaml deploy docker-compose.yml frontend services scripts README.md docs
```

Allowed exceptions before deletion:

- Historical prose in docs explaining that the monolith was removed.
- Git history is not relevant.
- No live build/runtime/script reference should remain.

Delete:

- `backend/`

Use PowerShell safely:

```powershell
$target = Resolve-Path .\backend
if ($target.Path -ne "D:\Projects\ims-v1\backend") { throw "Unexpected backend path: $($target.Path)" }
Remove-Item -LiteralPath $target.Path -Recurse -Force
```

Do not delete:

- `services/`
- `frontend/`
- `scripts/`
- `docs/`
- root Maven wrapper if moved there
- deployment files

After deletion:

```powershell
Test-Path .\backend
rg -n "backend|custoking-backend|BACKEND_|backend-service" .github cloudbuild.yaml deploy docker-compose.yml frontend services scripts README.md docs
```

There should be no live reference requiring `backend/`.

## Phase 9: Database Cleanup and Legacy Public Schema Retirement

Goal: avoid confusing old public monolith tables with active service-owned schemas.

Current service migrations may still contain references like:

- `public.app_users`
- `public.schools`
- `public.students`
- `public.command_center_feed`
- `public.notification_logs`

These are mostly historical migration imports or old compatibility constraints. Do not delete applied migration files to remove these references.

Instead:

1. Keep historical migrations immutable.
2. Add forward migrations only where needed.
3. Confirm services at runtime use service schemas:
   - `identity.*`
   - `tenant_school.*`
   - `student.*`
   - `attendance.*`
   - `fee.*`
   - `catalog.*`
   - `workflow.*`
   - `audit.*`
   - `notification.*`
   - `reporting.*`
   - `firefighting.*`
   - `billing.*`
4. Use existing retirement scripts:

```powershell
.\scripts\audit-microservice-db-boundaries.ps1
.\scripts\generate-legacy-public-retirement-sql.ps1
.\scripts\generate-legacy-public-retirement-compact-sql.ps1
```

5. For production:
   - Take backup first.
   - Run audit.
   - Run retirement SQL only after no application points at public monolith tables.
   - Keep rollback evidence.

## Phase 10: Update Documentation

Goal: docs match the service-only architecture.

Update:

- `README.md`
- `docs/ARCHITECTURE-HLD.md`
- `docs/ARCHITECTURE-LLD.md`
- `docs/MICROSERVICES-MIGRATION-ROADMAP.md`
- `docs/MICROSERVICES-COMPLETION-PLAN.md`
- `deploy/gcp/README.md`
- `frontend/README.md`

Required documentation changes:

- State monolith is removed.
- List active services and ownership.
- List local run commands.
- List production deploy flow.
- Remove backend service references.
- Explain root Maven wrapper usage.
- Explain smoke tests.
- Explain public schema retirement posture.

## Phase 11: Final Validation Matrix

Run in this order.

### Static Checks

```powershell
rg -n "custoking-backend|backend-service|BACKEND_|backend:8080|backend/Dockerfile" .github cloudbuild.yaml deploy docker-compose.yml services frontend scripts README.md docs
rg -n "from public\\.|public\\.(app_users|students|schools|command_center|notification|fee_|attendance)" services backend scripts -g "*.java" -g "*.sql" -g "*.ps1"
```

If `backend/` has been deleted, the second command must not include `backend`. Historical service migration references may remain, but runtime Java/script references must be understood and documented.

### Build Checks

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'
$env:Path="$env:JAVA_HOME\bin;$env:Path"

$services = @(
  "identity-service",
  "tenant-school-service",
  "student-service",
  "attendance-service",
  "fee-service",
  "catalog-service",
  "workflow-service",
  "audit-service",
  "notification-service",
  "reporting-service",
  "firefighting-service",
  "billing-service"
)

foreach ($service in $services) {
  .\mvnw.cmd -f "services\$service\pom.xml" -DskipTests compile
  if ($LASTEXITCODE -ne 0) { throw "$service compile failed" }
}

Push-Location frontend
npm ci
npm test -- --run
npm run build
Pop-Location
```

### Local Runtime Checks

```powershell
docker compose up -d --build
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
```

Expected: no `custoking-backend` container.

Then run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-gateway-routes.ps1 -GatewayBaseUrl http://localhost
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-microservice-features.ps1 -GatewayBaseUrl http://localhost
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-outbox-reporting-projection.ps1 -GatewayBaseUrl http://localhost
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-deployment-readiness.ps1 -GatewayBaseUrl http://localhost
```

After testing:

```powershell
docker compose stop
```

### CI/CD Checks

Dry-run or inspect:

```powershell
rg -n "backend|custoking-backend|BACKEND" .github cloudbuild.yaml deploy scripts
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\audit-deployment-boundaries.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\audit-microservice-build-catalog.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\audit-microservice-test-catalog.ps1
```

Expected: deploy and audit scripts enumerate only extracted services plus gateway/frontend where relevant.

## Removal Checklist

Use this as the final gate.

- [ ] All backend-only routes moved to services.
- [ ] API gateway routes all API paths to extracted services.
- [ ] Frontend works through gateway only.
- [ ] Docker Compose has no backend service.
- [ ] GitHub Actions has no backend build/deploy job.
- [ ] Cloud Build has no backend build/deploy step.
- [ ] GCP deploy scripts do not deploy backend.
- [ ] IAM/Secret Manager scripts do not reference backend.
- [ ] Maven wrapper exists outside `backend/`.
- [ ] `backend/` physically deleted.
- [ ] `rg` finds no live backend references.
- [ ] All services compile.
- [ ] Frontend tests/build pass.
- [ ] Gateway route smoke passes.
- [ ] Microservice feature smoke passes.
- [ ] Outbox/reporting projection smoke passes.
- [ ] Deployment readiness smoke passes.
- [ ] Docker stack stopped after local verification.
- [ ] Docs updated to service-only architecture.

## Expected Problems and Fix Strategy

### Problem: A frontend page fails after backend removal

Likely cause: gateway route still points to backend, or service response shape differs.

Fix:

1. Find failing API call in browser/network or smoke output.
2. Search endpoint in frontend and old backend.
3. Implement missing route/shape in owning service.
4. Route gateway to that service.
5. Add smoke coverage.

### Problem: Auth/login fails after backend removal

Likely cause: frontend/gateway still calls backend auth path, or JWT claims differ.

Fix:

1. Confirm `/api/v1/auth/login` routes to `identity-service`.
2. Confirm `identity-service` returns `accessToken` and expected user/role/school fields.
3. Confirm frontend `AuthContext` reads the same fields.
4. Confirm downstream services accept identity-issued JWT.

### Problem: RBAC/permissions fail

Likely cause: permissions still expected from backend facade.

Fix:

1. Move exact RBAC endpoints to `identity-service`.
2. Ensure roles/permissions/user assignments are seeded in `identity` schema.
3. Run feature smoke entries for roles, permissions, and user roles.

### Problem: Dashboard/command center data fails

Likely cause: backend dashboard facade had aggregation logic not present in `reporting-service`.

Fix:

1. Move aggregation to `reporting-service`.
2. Prefer projections/events over cross-service direct reads.
3. Preserve response shape used by `frontend/src/api/dashboardCommandCenterApi.ts`.

### Problem: Deployment script still expects backend URL

Fix:

1. Remove backend from deploy service catalog.
2. Remove backend from gateway template substitution.
3. Remove backend from IAM invoker grants.
4. Remove backend from readiness JSON/report expectations.

### Problem: Build scripts used `mvnw.cmd`

Fix:

1. Move Maven wrapper to repo root.
2. Replace script references with `.\mvnw.cmd`.
3. Delete `backend/` only after wrapper move is validated.

## Principal Architect Notes

The safest endpoint is not "delete every file containing old names"; it is "prove there is no runtime, build, deploy, or test dependency on the monolith, then remove it." This repo is close, but `backend/` still appears to have been used as a compatibility facade during extraction. Removing it safely means first making the gateway and extracted services own every public API contract.

The service migrations can still mention `public.*` as historical imports. That is not automatically a runtime monolith dependency. The dangerous references are Java code, gateway routes, frontend URLs, Docker services, CI/CD jobs, and deployment scripts that still require `backend`.

The final proof is local Docker running without `custoking-backend` and passing the same smoke matrix that passed before removal.
