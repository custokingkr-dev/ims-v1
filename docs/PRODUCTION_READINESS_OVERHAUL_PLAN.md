# Production Readiness Review And Overhaul Plan

Generated: 2026-06-26

## Verification Evidence

This review separates verified facts from recommendations.

Verified local checks:

- `scripts/verify-microservice-migration.ps1` passed.
- `scripts/invoke-microservice-tests.ps1` passed for 13 entries.
- Frontend tests passed: 53 tests.
- Frontend production build passed.
- `docker compose config --quiet` passed.
- Docker daemon was not available from this shell, so local container E2E could not be rerun in this pass.

Verified GCP checks:

- Active project: `custoking-ims`.
- Active account: `shubhambagrodia2609@gmail.com`.
- Cloud Run services are present in `asia-south2`.
- `custoking-api-gateway` health endpoint returned 200.
- `ims-direct-service-smoke` Cloud Run Job completed successfully as execution `ims-direct-service-smoke-nn4cq`.
- Real environment preflight failed with 4 blockers because deployment smoke artifact, legacy compatibility artifact, superadmin smoke credentials/token, and admin smoke credentials/token were missing.
- Current deployed GCP state still includes `custoking-backend`.
- Current deployed `custoking-api-gateway` revision routes service upstreams to `custoking-backend`, not directly to the extracted services.
- `gh` CLI is not installed locally, so GitHub workflow dispatch or GitHub run inspection was not executed from this machine.

Official references used:

- Cloud Run private service-to-service calls require authenticated requests with Google-signed ID tokens: https://docs.cloud.google.com/run/docs/authenticating/service-to-service
- Cloud Run services use service identity for Google API access and runtime identity: https://docs.cloud.google.com/run/docs/securing/service-identity
- GitHub and other CI/CD systems should use Workload Identity Federation rather than service account keys: https://docs.cloud.google.com/iam/docs/workload-identity-federation-with-deployment-pipelines
- Cloud Build substitutions are supported for build-time values and reused build configs: https://docs.cloud.google.com/build/docs/build-config-file-schema

## Current Production Readiness Verdict

Not production-ready yet.

The repository is mostly service-only, but the deployed GCP environment is not. The highest-risk mismatch is that production still has `custoking-backend`, and the deployed gateway still points extracted-service routes at that backend. The direct service smoke proves that selected private services can be called with Cloud Run ID tokens and internal service tokens, but it does not prove the public gateway path is service-only.

## Principal Architecture Findings

### 1. Gateway And Cloud Run Authentication

The current repo deploys private downstream Cloud Run services with `--no-allow-unauthenticated`. That is sound only if the caller sends a Google-signed ID token with the downstream service URL as audience. Plain nginx does not mint Cloud Run identity tokens by itself.

Target decision:

- Replace nginx with a programmable gateway that can validate user JWTs and mint downstream Cloud Run ID tokens, or
- Keep nginx but make downstream services public and rely on internal service tokens, ingress rules, Cloud Armor, and strict app-level auth.

Recommended target: programmable gateway. This keeps downstream services private and aligns with Cloud Run IAM.

### 2. User Authentication Boundary

The gateway injects service tokens. That proves internal routing, but it is not sufficient as user authorization. Protected routes must validate user JWT/RBAC before service tokens are added.

Target:

- Public routes: login, refresh, health.
- Protected routes: gateway validates JWT with identity service or local public key.
- Gateway forwards verified user id, role, school id, zone scope, permissions, and correlation id.
- Services reject missing internal token and also enforce tenant/scope where they own data.

### 3. Data Ownership Boundary

Services have separate schemas, but the database is still transitional:

- Several migrations read from `public.*`.
- Some service schemas still reference `public.schools`, `public.students`, `public.app_users`, and similar legacy tables.
- Some services default schema config to `public` if env vars are missing.

Target:

- No runtime dependency on `public` legacy tables.
- No cross-service foreign keys.
- Each service owns its schema and publishes events or read models for other services.
- Reporting owns projections, not direct OLTP joins across service-owned tables.
- Production startup must fail if a service schema env var is missing.

### 4. Deployment Path Mismatch

Repo `cloudbuild.yaml` is service-only, but current GCP state is not service-only. GitHub deploy calls Cloud Build, so GitHub deployment cannot be certified until a fresh GitHub-triggered run proves it deploys the service-only topology.

Target:

- Local direct deployment and GitHub deployment both call the same deploy contract.
- No backend service appears in Cloud Run inventory after retirement.
- Gateway env vars point to extracted service URLs.
- Direct smoke job is parameterized, not hard-coded to specific generated URLs.

### 5. Test Coverage

Most Java services have zero test files. Current green test status is mostly compile-level verification, except notification-service tests and frontend tests.

Target:

- Contract tests per service.
- Gateway route tests.
- Data migration tests.
- End-to-end smoke with real role-based login.
- Playwright UI tests for the main admin and superadmin workflows.

### 6. Naming And Structure

The service list is understandable, but naming is not fully normalized:

- `tenant-school-service` owns schools, zones, classes, sections, staff, module entitlements, and school onboarding.
- `catalog-service` still carries supply/order terminology.
- Compatibility controllers mix legacy public paths with service-native paths.
- Token names alternate between `READ_TOKEN`, `SERVICE_TOKEN`, `INGEST_TOKEN`, and `STATUS_TOKEN`.

Target:

- Keep service names stable for deployment, but document bounded-context ownership clearly.
- Standardize internal token naming by intent: `INTERNAL_API_TOKEN`, `INGEST_TOKEN`, `PUBSUB_PUSH_TOKEN`.
- Separate compatibility API controllers from native API controllers by package and naming.
- Remove compatibility controllers only after frontend and external clients no longer depend on legacy routes.

## Phased Execution Plan

### Phase 0: Freeze And Evidence Baseline

Goal: prevent accidental production drift.

Tasks:

- Save current Cloud Run service inventory, IAM policies, latest revisions, image digests, and secret-name inventory.
- Save current local verification outputs.
- Record current production mismatch: backend still deployed and gateway points to backend.
- Do not retire backend until service-only gateway smoke passes.

Exit criteria:

- Evidence artifacts exist for service inventory, preflight, gateway env, direct smoke execution, and latest build list.

### Phase 1: Fix Deployment Truth

Goal: make deployed GCP topology match the repo topology.

Tasks:

- Parameterize `deploy/gcp/direct-service-smoke-job.template.yaml`; remove hard-coded Cloud Run URLs and default compute service account from generated jobs.
- Add a local deploy wrapper that runs:
  - static verification,
  - frontend build,
  - service tests,
  - Cloud Build submit,
  - Cloud Run service inventory check,
  - gateway smoke,
  - direct service smoke.
- Update Cloud Build substitutions so application DB user and Flyway DB user are explicit and not conflated.
- Deploy the service-only gateway to GCP.
- Verify gateway upstreams point to extracted services.
- Only then quarantine or remove `custoking-backend`.

Exit criteria:

- GCP service inventory has no active gateway dependency on `custoking-backend`.
- Gateway feature smoke passes against the deployed gateway.
- Direct service smoke passes.
- Production preflight has zero blockers.

### Phase 2: Production Gateway And Auth Hardening

Goal: make ingress security correct for Cloud Run.

Tasks:

- Decide final gateway architecture.
- Recommended: replace nginx with a small programmable gateway.
- Gateway must:
  - validate JWT,
  - call identity introspection or verify signing keys,
  - enforce route policy,
  - mint Cloud Run ID tokens for downstream private services,
  - inject internal service tokens only after user auth succeeds,
  - propagate correlation and tenant context.
- Keep downstream services private with `roles/run.invoker` only for gateway service account and approved push/job identities.

Exit criteria:

- Anonymous protected requests fail before reaching services.
- Authenticated authorized requests succeed.
- Authenticated unauthorized requests fail with 403.
- Downstream services are not invokable by `allUsers`.

### Phase 3: Data Architecture Cleanup

Goal: complete real service-owned data boundaries.

Tasks:

- Replace runtime cross-schema joins with service APIs, events, or reporting projections.
- Remove cross-service foreign keys to `public.*`.
- Convert legacy backfill migrations into one-time migration records and document post-cutover behavior.
- Make schema env vars mandatory in production.
- Create explicit DB users per service with least privilege to each owned schema.
- Define event contracts for identity, school, student, fee, attendance, catalog/order, notification, audit, and reporting projection updates.

Exit criteria:

- Runtime code has no dependency on `public.*`.
- Service DB users cannot read/write unrelated service schemas except approved read-model schemas.
- Reporting data comes from projections or approved analytical views.

### Phase 4: End-To-End Functional Certification

Goal: prove every business feature works as before.

Tasks:

- Create seeded production-like smoke users through identity-service or a controlled smoke fixture.
- Run read smoke and write smoke for:
  - login/refresh/logout,
  - RBAC,
  - school onboarding,
  - zones,
  - staff and HR,
  - timetable,
  - student import/list/detail/photo/review,
  - attendance,
  - fee setup/assignment/payment/reminder,
  - catalog/annual plan/orders/design/superadmin approval,
  - firefighting,
  - billing,
  - reporting dashboards,
  - audit logs,
  - notification dry-run and provider integration.
- Add Playwright tests for critical admin and superadmin UI flows.

Exit criteria:

- Automated smoke produces JSON artifacts with zero failures.
- UI workflow tests pass in CI.
- Failed route screenshots/logs are captured.

### Phase 5: CI/CD And GitHub Deployment Certification

Goal: make GitHub deploy safe, repeatable, and auditable.

Tasks:

- Install or provision GitHub CLI for local workflow inspection, or use GitHub API with an approved token.
- Verify GitHub environment protection and required secrets.
- Verify Workload Identity Federation provider and deploy service account IAM.
- Trigger staging GitHub deployment first, not production.
- Require post-deploy smoke before promotion.
- Add image digest pinning and deployment evidence export.
- Add rollback drill evidence to the release gate.

Exit criteria:

- GitHub staging deployment completes from workflow.
- GitHub production deployment is approved and completes.
- Cloud Build logs, image digests, revisions, and smoke artifacts are linked in a release evidence bundle.

### Phase 6: Code Structure And Naming Normalization

Goal: make the repo maintainable after migration.

Tasks:

- Standardize package layers per service:
  - `api`,
  - `application`,
  - `domain`,
  - `persistence`,
  - `infrastructure`,
  - `config`.
- Move compatibility controllers into `api.compat`.
- Move native service APIs into `api.internal` or `api.public` based on exposure.
- Standardize DTO naming: `Request`, `Response`, `Command`, `Event`.
- Standardize token/env naming.
- Remove obsolete historical docs or move them under `docs/history`.

Exit criteria:

- All services follow the same package shape.
- Public API compatibility surface is documented.
- No active docs tell operators to deploy or debug the removed monolith.

### Phase 7: Observability, Resilience, And Cost

Goal: operate this as a production SaaS platform.

Tasks:

- Propagate `X-Request-Id` and trace ids through gateway and all services.
- Add structured JSON logs.
- Add service-level health and readiness endpoints.
- Add retry/timeouts/circuit breaker policies for service calls.
- Add alerting for 5xx, auth failures, DB pool saturation, queue failures, and notification failures.
- Tune Cloud Run min/max instances and DB pool sizes per service.
- Split local Docker profiles into `core`, `full`, and `infra` to reduce WSL memory usage.

Exit criteria:

- Operators can diagnose a failed user action across gateway and services.
- Local runtime can start a minimal stack without exhausting memory.
- Production alerting catches degraded service behavior before users report it.

## Immediate Next Work Order

1. Fix GCP deployed gateway so service upstreams point to extracted services.
2. Run deployed gateway smoke with real superadmin/admin tokens.
3. Make direct smoke job parameterized.
4. Retire `custoking-backend` only after service-only smoke passes.
5. Replace or augment nginx gateway for Cloud Run ID-token auth.
6. Add real test coverage to services with zero test files.

## Progress Log

### 2026-06-26: Programmable Gateway Deployed

Completed:

- Replaced the runtime API gateway image with a small Node HTTP gateway.
- Gateway now validates protected API calls through `identity-service` introspection.
- Gateway now adds Google metadata-server ID tokens when proxying to `.run.app` private Cloud Run services.
- Gateway keeps existing internal service-token headers for each downstream service.
- Local compose runs the gateway in permissive mode for existing diagnostic route smoke.
- Production Cloud Run runs the gateway in enforced mode.
- Direct service smoke job is now generated from `deploy/gcp/direct-service-smoke-job.template.yaml` instead of hard-coded generated URLs.
- Dedicated `direct-service-smoke@custoking-ims.iam.gserviceaccount.com` identity was created and granted the minimal smoke permissions.
- Deployed `custoking-api-gateway-00003-bhj` with direct extracted-service upstreams and no `custoking-backend` upstream.

Verified:

- Gateway health returned 200.
- Anonymous protected route `/api/v1/rbac/roles` returned 401 from the gateway.
- Public login route reached private `identity-service`; invalid credentials returned 401.
- `scripts/audit-gcp-gateway-upstreams.ps1` passed with zero backend upstreams.
- Direct service smoke execution `ims-direct-service-smoke-2lzbb` completed successfully.
- Real environment readiness preflight blocker count reduced from 5 to 4.

Still gated:

- Full deployed gateway feature smoke still needs real superadmin/admin smoke token or credentials.
- `custoking-backend` must not be retired until that deployed gateway smoke passes.
- Legacy compatibility artifact still needs to be generated and reviewed.

### 2026-06-26: Service-Only Production Cutover Verified

Completed:

- Added `scripts/invoke-production-gateway-smoke.ps1` to create temporary production smoke users, run the gateway feature matrix, run the real-environment preflight, and retire the smoke users.
- Updated `scripts/invoke-real-environment-readiness-preflight.ps1` so a successful deployment smoke artifact satisfies the smoke-auth gate without requiring credentials to remain active afterwards.
- Ran a full Cloud Build deploy from `cloudbuild.yaml` after the first production smoke found stale deployed service revisions.
- Fixed the programmable gateway billing route so `/api/v1/sa/invoices/stats` routes to `billing-service`.
- Deleted the deployed `custoking-backend` Cloud Run service after service-only smoke passed.

Verified:

- Full Cloud Build completed successfully: `e442d941-58e3-480e-89f2-934468db008e`.
- Gateway route-fix build completed successfully: `b1e9f2dc-b12c-4f9f-86a4-f4e5eb86f159`.
- Gateway revision `custoking-api-gateway-00005-r7v` is serving 100% traffic.
- Production deployment readiness smoke passed: 39/39 checks, 0 failures. Artifact: `artifacts/deployment-smoke.json`.
- Final real-environment preflight passed with 0 blockers. Artifact: `artifacts/real-environment-readiness-final-after-backend-retirement.json`.
- Direct private service smoke passed after backend deletion: execution `ims-direct-service-smoke-vr8nc`.
- Gateway upstream audit passed after backend deletion: zero upstreams point to `custoking-backend`.
- Cloud Run service inventory no longer includes `custoking-backend`.

Remaining:

- Docker daemon was unavailable locally in this workstream, so local container E2E still needs a rerun once Docker Desktop is healthy.
- Java service test coverage remains thin for most extracted services; current green status is still largely compile/static/smoke coverage.
- GitHub Actions deployment should be triggered and its release evidence captured separately because `gh` CLI is not installed on this machine.
