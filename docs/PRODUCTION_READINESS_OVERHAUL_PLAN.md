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

1. Add real service-level test coverage to Java services that currently only compile.
2. Continue Phase 6 package normalization by splitting broad controllers into clearer `api.public`, `api.internal`, and `api.compat` surfaces where risk is low.
3. Audit and reduce remaining runtime data-boundary risks: cross-schema reads, legacy compatibility tables, and service DB privileges.
4. Split local Docker profiles into `core`, `full`, and `infra` so local runtime does not exhaust WSL memory.
5. Add request/correlation id propagation and structured logging checks across gateway and services.

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

### 2026-06-26: GitHub Deployment Certified

Completed:

- Installed/used GitHub CLI for workflow inspection and dispatch.
- Pushed branch `microservices-boundary-foundation` with service-only cutover commits:
  - `05348b1` - service-only microservice cutover.
  - `98ceb3f` - GitHub deploy workflow Cloud Build polling fix.
- Updated GitHub deploy workflow to submit Cloud Build asynchronously and poll build status instead of relying on fragile log streaming from the runner.
- Granted `github-actions-sa@custoking-ims.iam.gserviceaccount.com` the missing GCP IAM required for direct smoke and service URL listing:
  - `roles/run.admin`
  - `roles/secretmanager.admin`
  - `roles/iam.serviceAccountAdmin`

Verified:

- GitHub Actions deploy run `28240126402` completed successfully.
- GitHub workflow used Workload Identity Federation and deployed commit `98ceb3f137a4022ccaac6545a163e9db70a3a1c6`.
- Workflow steps succeeded:
  - Cloud Build submit/poll/deploy.
  - Direct private service smoke.
  - Cloud Run URL inventory print.
- Production Cloud Run inventory remains 14 services after GitHub deployment.
- Gateway upstream audit passed after GitHub deployment with zero `custoking-backend` upstreams.
- Role-based production gateway smoke passed after GitHub deployment: 39/39 checks, 0 failures.
- Real-environment readiness preflight after GitHub deployment passed with 0 blockers.

Remaining:

- Consider replacing broad GitHub deploy IAM grants with a custom least-privilege role once the deployment process stabilizes.
- Add GitHub-hosted artifact upload for deployment smoke/preflight evidence so every release has immutable evidence attached to the workflow run.

### 2026-06-26: GitHub Deployment IAM Reduced And Evidence Attached

Completed:

- Removed per-deployment IAM mutation from `.github/workflows/deploy.yml`.
- Direct service smoke now requires the pre-provisioned `direct-service-smoke@custoking-ims.iam.gserviceaccount.com` identity and fails fast if it is missing.
- GitHub deploy workflow now uploads `gcp-deployment-evidence-<run_id>` with:
  - Cloud Build id and build JSON,
  - generated direct smoke job YAML,
  - direct smoke job JSON,
  - direct smoke replace/execute logs,
  - Cloud Run service inventory in text and JSON.
- Reduced `github-actions-sa@custoking-ims.iam.gserviceaccount.com` project roles by removing:
  - `roles/run.admin`,
  - `roles/secretmanager.admin`,
  - `roles/iam.serviceAccountAdmin`.
- Added narrower roles needed by the workflow:
  - `roles/run.developer`,
  - `roles/iam.serviceAccountViewer`,
  - `roles/secretmanager.viewer`.

Verified:

- GitHub Actions deploy run `28246375561` completed successfully under reduced IAM.
- GitHub workflow deployed commit `de0a74d9c406bcddeb5fc767d614dd6b237845b0`.
- Cloud Build id from GitHub evidence: `2f8d18f5-5c3b-486e-9704-0b7033bf7811`.
- GitHub workflow evidence artifact downloaded successfully and contains Cloud Build, direct smoke, and Cloud Run inventory evidence.
- Production Cloud Run inventory remains 14 services.
- Gateway upstream audit passed with zero `custoking-backend` upstreams.
- Role-based production gateway smoke passed after the reduced-IAM GitHub deployment: 39/39 checks, 0 failures.
- Real-environment readiness preflight after the reduced-IAM GitHub deployment passed with 0 blockers.

Remaining:

- `github-actions-sa` still has broad `roles/storage.admin` and `roles/cloudbuild.builds.editor`; replace these with narrower storage/build permissions or a custom project role in the next IAM hardening pass.
- Attach role-based gateway smoke/preflight artifacts directly from CI once a non-mutating production smoke credential strategy is available for GitHub-hosted runners.

### 2026-06-26: GitHub Cloud Build IAM Narrowed

Completed:

- Created project custom role `projects/custoking-ims/roles/githubDeployCloudBuildSubmitter`.
- Removed `roles/cloudbuild.builds.editor` from `github-actions-sa@custoking-ims.iam.gserviceaccount.com`.
- Custom role permissions now cover only Cloud Build submit/poll plus project lookup:
  - `cloudbuild.builds.create`
  - `cloudbuild.builds.get`
  - `cloudbuild.builds.list`
  - `cloudbuild.locations.get`
  - `cloudbuild.locations.list`
  - `cloudbuild.operations.get`
  - `cloudbuild.operations.list`
  - `resourcemanager.projects.get`
  - `serviceusage.services.use`

Verified:

- GitHub Actions deploy run `28254999312` completed successfully.
- GitHub workflow deployed commit `4fe1d56e244eac225e73b56bf16d545299f140cc`.
- Cloud Build id from GitHub evidence: `918e5cbc-f462-46cd-a3fe-55efb3a5fc00`.
- Direct service smoke passed in the GitHub workflow.
- Deployment evidence artifact downloaded successfully.
- Production Cloud Run inventory remains 14 services.
- Gateway upstream audit passed with zero `custoking-backend` upstreams.
- Role-based production gateway smoke passed after this deployment: 39/39 checks, 0 failures.
- Real-environment readiness preflight passed with 0 blockers.

IAM Findings:

- Attempts to replace project-level `roles/storage.admin` with `roles/storage.objectAdmin`, bucket-level `roles/storage.legacyBucketReader`, bucket-level `roles/storage.objectAdmin`, and `roles/serviceusage.serviceUsageConsumer` failed at `gcloud builds submit` source upload with access denied on `custoking-ims_cloudbuild`.
- `roles/serviceusage.serviceUsageAdmin` did not resolve that source upload failure either.
- Current working deploy IAM for `github-actions-sa` intentionally keeps `roles/storage.admin` as a residual risk until the build source upload path is redesigned.

Remaining:

- Replace `gcloud builds submit .` with an explicit, controlled source upload path or GitHub-source Cloud Build trigger so `roles/storage.admin` can be removed.
- After that redesign, retest with bucket-scoped storage permissions only and remove project-level `roles/storage.admin`.

### 2026-06-27: GitHub Storage Admin Removed

Completed:

- Created dedicated Cloud Build source staging bucket `gs://custoking-ims-github-deploy-source`.
- Granted `github-actions-sa@custoking-ims.iam.gserviceaccount.com` bucket-scoped source upload permissions only on that bucket:
  - `roles/storage.legacyBucketReader`
  - `roles/storage.objectAdmin`
- Updated `.github/workflows/deploy.yml` to run `gcloud builds submit` with:
  - `--gcs-source-staging-dir=gs://custoking-ims-github-deploy-source/source`
- Removed project-level `roles/storage.admin` from `github-actions-sa`.

Verified:

- GitHub Actions deploy run `28259158128` completed successfully without project-level Storage Admin.
- GitHub workflow deployed commit `fef74369e1ea3455f623823623f32a46db2fbdd2`.
- Cloud Build id from GitHub evidence: `3eaaccf1-ead1-4ff1-8186-8872d8a31482`.
- Direct service smoke passed in the GitHub workflow.
- Deployment evidence artifact downloaded successfully.
- Current `github-actions-sa` project roles are:
  - `projects/custoking-ims/roles/githubDeployCloudBuildSubmitter`
  - `roles/iam.serviceAccountUser`
  - `roles/iam.serviceAccountViewer`
  - `roles/logging.viewer`
  - `roles/run.developer`
  - `roles/secretmanager.viewer`
  - `roles/storage.objectViewer`
- Production Cloud Run inventory remains 14 services.
- Gateway upstream audit passed with zero `custoking-backend` upstreams.
- Role-based production gateway smoke passed after this deployment: 39/39 checks, 0 failures.
- Real-environment readiness preflight passed with 0 blockers.

Follow-up state:

- GitHub deploy IAM consolidation completed on 2026-06-27; only the two project custom roles remain on `github-actions-sa`.
- Lifecycle retention for `gs://custoking-ims-github-deploy-source` is now tracked in `deploy/gcp/github-deploy-source-bucket-lifecycle.json` and applied to delete objects older than 14 days.

### 2026-06-27: GitHub Runtime IAM Consolidated

Completed:

- Added tracked custom runtime role definition `deploy/gcp/github-deploy-runtime-operator-role.yaml`.
- Created project custom role `projects/custoking-ims/roles/githubDeployRuntimeOperator`.
- Scoped `roles/iam.serviceAccountUser` for `github-actions-sa@custoking-ims.iam.gserviceaccount.com` to only `direct-service-smoke@custoking-ims.iam.gserviceaccount.com`.
- Removed these project-level predefined roles from `github-actions-sa`:
  - `roles/run.developer`
  - `roles/iam.serviceAccountUser`
  - `roles/iam.serviceAccountViewer`
  - `roles/logging.viewer`
  - `roles/secretmanager.viewer`
  - `roles/storage.objectViewer`

Verified:

- Current `github-actions-sa` project roles are only:
  - `projects/custoking-ims/roles/githubDeployCloudBuildSubmitter`
  - `projects/custoking-ims/roles/githubDeployRuntimeOperator`
- `github-actions-sa` retains `roles/iam.serviceAccountUser` only on the direct smoke service account resource.
- GitHub Actions deploy run `28261672885` completed successfully.
- GitHub workflow deployed commit `3e90d21a3d85d10f7ac4f7dfb5c2860314aaa918`.
- Cloud Build id from GitHub evidence: `3daec175-084e-489a-9f96-df89b93eb3ba`.
- Direct service smoke passed in the GitHub workflow.
- Deployment evidence artifact downloaded successfully.
- Production Cloud Run inventory remains 14 services.
- Gateway upstream audit passed with zero `custoking-backend` upstreams.
- Role-based production gateway smoke passed after this deployment: 39/39 checks, 0 failures.
- Real-environment readiness preflight passed with 0 blockers.

### 2026-06-27: API Package Shape Normalized

Completed:

- Moved public compatibility controllers out of flat `api` packages into `api.compat`.
- Moved Pub/Sub push receiver controllers into `api.internal`.
- Added `scripts/audit-service-package-shape.ps1`.
- Wired the package-shape audit into `scripts/verify-microservice-migration.ps1`.
- Made the audit event DTO mapper public because compatibility code no longer shares the same package as the native audit controller.

Verified:

- Package-shape audit passed.
- Targeted compile/tests passed for touched services:
  - `audit-service`
  - `billing-service`
  - `catalog-service`
  - `fee-service`
  - `reporting-service`
  - `student-service`
  - `notification-service`
- Full `scripts/verify-microservice-migration.ps1` passed with the new package-shape audit included.

### 2026-06-27: Audit Service Controller Tests Added

Completed:

- Added `AuditIngestControllerTest` for `audit-service`.
- Covered invalid/missing internal token rejection before persistence.
- Covered blank action validation before persistence.
- Covered successful audit event mapping, field trimming, default outcome, and response status.

Verified:

- `audit-service` Maven test suite passed: 3 tests, 0 failures.
- Full `scripts/verify-microservice-migration.ps1` passed after adding the test.

### 2026-06-27: Billing Service Controller Tests Added

Completed:

- Added `BillingInvoiceControllerTest` for `billing-service`.
- Added the missing `spring-boot-starter-test` dependency to `billing-service`.
- Covered invalid internal token rejection before delegation.
- Covered invoice not-found behavior.
- Covered create delegation and list filter delegation with a valid service token.

Verified:

- `billing-service` Maven test suite passed: 4 tests, 0 failures.
- Full `scripts/verify-microservice-migration.ps1` passed after adding the test.
- Full `scripts/invoke-microservice-tests.ps1` passed across all 13 catalogued services/frontend after adding the audit and billing tests.

### 2026-06-27: Catalog Service Controller Tests Added

Completed:

- Added `CatalogReadControllerTest` for `catalog-service`.
- Added the missing `spring-boot-starter-test` dependency to `catalog-service`.
- Covered invalid internal token rejection before repository access.
- Covered order not-found behavior.
- Covered command exception translation for missing school and cross-school vendor payment.
- Covered annual-plan confirmation compatibility payload.

Verified:

- `catalog-service` Maven test suite passed: 5 tests, 0 failures.
- Full `scripts/verify-microservice-migration.ps1` passed after adding the test.

### 2026-06-27: Fee Service Controller Tests Added

Completed:

- Added `FeeReadControllerTest` for `fee-service`.
- Added the missing `spring-boot-starter-test` dependency to `fee-service`.
- Covered invalid internal token rejection before repository access.
- Covered unsupported fee-structure export format handling.
- Covered repository validation exception mapping to `400`.
- Covered delete-band compatibility response and payment filter delegation.

Verified:

- `fee-service` Maven test suite passed: 5 tests, 0 failures.
- Full `scripts/verify-microservice-migration.ps1` passed after adding the test.

### 2026-06-27: Attendance Service Controller Tests Added

Completed:

- Added `AttendanceReadControllerTest` for `attendance-service`.
- Added the missing `spring-boot-starter-test` dependency to `attendance-service`.
- Covered invalid internal token rejection before repository access.
- Covered read delegation with filters.
- Covered validation exception mapping to `400`.
- Covered cross-school section access mapping to `403`.
- Covered submit-day request parsing and delegation.

Verified:

- `attendance-service` Maven test suite passed: 5 tests, 0 failures.
- Full `scripts/verify-microservice-migration.ps1` passed after adding the test.
- Full `scripts/invoke-microservice-tests.ps1` passed across all 13 catalogued services/frontend after adding the fee and attendance tests.

### 2026-06-27: Workflow Service Controller Tests Added

Completed:

- Added `WorkflowReadControllerTest` for `workflow-service`.
- Added the missing `spring-boot-starter-test` dependency to `workflow-service`.
- Covered invalid internal token rejection before repository access.
- Covered instance filter delegation.
- Covered missing workflow instance mapping to `404`.
- Covered repository validation exception mapping to `400`.
- Covered legacy approve route delegation to the canonical approve path.

Verified:

- `workflow-service` Maven test suite passed: 5 tests, 0 failures.
- Full `scripts/verify-microservice-migration.ps1` passed after adding the test.

### 2026-06-27: Student Service Controller Tests Added

Completed:

- Added `StudentReadControllerTest` for `student-service`.
- Covered invalid internal token rejection before repository access.
- Covered list/count response assembly with filters.
- Covered missing student mapping to `404`.
- Covered repository validation exception mapping to `400`.
- Covered import template download response.
- Covered workspace compatibility create and class-section routes.

Verified:

- `student-service` Maven test suite passed: 7 tests, 0 failures.
- Full `scripts/verify-microservice-migration.ps1` passed after adding the test.

### 2026-06-27: Tenant School Service Controller Tests Added

Completed:

- Added `TenantSchoolControllerTest` for `tenant-school-service`.
- Covered invalid internal token rejection before repository access.
- Covered school and zone entity-to-response mapping.
- Covered missing school and zone mapping to `404`.
- Covered school command validation mapping to `400` and not-found command mapping to `404`.
- Covered module entitlement request parsing, invalid date rejection, and delegation.
- Covered school admin compatibility fallback and matching stats response.

Verified:

- `tenant-school-service` Maven test suite passed: 11 tests, 0 failures.
- Full `scripts/verify-microservice-migration.ps1` passed after adding the test.

### 2026-06-27: Reporting Service Controller Tests Added

Completed:

- Added `spring-boot-starter-test` to `reporting-service`.
- Added `ReportingReadControllerTest` for `reporting-service`.
- Covered invalid internal token rejection before repository access.
- Covered reporting read filter delegation and command-center action command delegation.
- Covered command validation exception mapping to `400` and not-found command mapping to `404`.
- Covered public compatibility workspace and command-center action routes.
- Covered Pub/Sub push-token rejection, direct event envelope recording, and Pub/Sub message data decoding/idempotent skip.

Verified:

- `reporting-service` Maven test suite passed: 11 tests, 0 failures.
- Full `scripts/verify-microservice-migration.ps1` passed after adding the test.

### 2026-06-27: Identity Service Controller Tests Added

Completed:

- Added `IdentityControllersTest` for `identity-service`.
- Covered auth login refresh-cookie creation, refresh-token rejection, logout cookie clearing, and token-guarded introspection.
- Covered RBAC token rejection, permission filter delegation, and school-role assignment delegation.
- Covered user-directory missing-user `404` behavior and password-reset actor-field parsing.
- Covered identity provisioning token rejection and zone-admin provisioning delegation.

Verified:

- `identity-service` Maven test suite passed: 12 tests, 0 failures.
- Full `scripts/verify-microservice-migration.ps1` passed after adding the test.

### 2026-06-27: Firefighting Service Controller Tests Added

Completed:

- Added `spring-boot-starter-test` to `firefighting-service`.
- Added `FirefightingReadControllerTest` for `firefighting-service`.
- Covered invalid internal token rejection before repository access.
- Covered request list filter delegation and request creation delegation.
- Covered request detail and quotation validation exception mapping to `400`.
- Covered quotation list delegation.
- Covered approval and vendor-payment command routing, including default empty request bodies.

Verified:

- `firefighting-service` Maven test suite passed: 8 tests, 0 failures.
- Full `scripts/verify-microservice-migration.ps1` passed after adding the test.

### 2026-06-27: API Gateway Node Tests Added

Completed:

- Made `services/api-gateway/server.js` import-safe by only listening when run as the main module.
- Exported gateway route/auth/header helper functions for direct tests.
- Added `services/api-gateway/server.test.js` using Node's built-in test runner.
- Covered gateway health, protected route `401`, unknown API route `404`, route matching, diagnostic rewrites, auth classification, hop-header filtering, forwarding metadata, and nullable principal field normalization.
- Added `api-gateway` to the shared microservice test catalog.
- Updated the local test runner audit and GitHub Actions service-test matrix to run `node --test` for the gateway.

Verified:

- `api-gateway` Node test suite passed: 9 tests, 0 failures.
- Full `scripts/verify-microservice-migration.ps1` passed after adding the gateway test catalog entry.

### 2026-06-27: Full Microservice Test Catalog Baseline Expanded to Gateway

Completed:

- Ran the full shared test catalog after adding API gateway coverage.
- Confirmed the catalog now executes 14 entries: 12 Java services, `api-gateway`, and `frontend`.

Verified:

- Full `scripts/invoke-microservice-tests.ps1` passed for 14 service entries.
- Java service controller/provider suites passed with the known Mockito dynamic-agent warning.
- `api-gateway` Node test suite passed: 9 tests, 0 failures.
- `frontend` Vitest suite passed: 4 test files, 53 tests.

### 2026-06-27: Java Test Runtime Agent Hardening

Completed:

- Added explicit `maven-surefire-plugin` configuration to all 12 Java service POMs.
- Configured Mockito as a Java agent instead of relying on runtime self-attachment.
- Added `-Xshare:off` to the Java test argLine to remove the class-sharing warning emitted when the agent is active.

Verified:

- Full `scripts/invoke-microservice-tests.ps1` passed for 14 service entries.
- Mockito dynamic-agent and JVM class-sharing warnings no longer appear in the Java service test runs.
- Full `scripts/verify-microservice-migration.ps1` passed after the Surefire configuration change.

### 2026-06-27: Runtime Schema Dependency Baseline Gate Added

Completed:

- Added `docs/RUNTIME_SCHEMA_DEPENDENCY_BASELINE.json` to make transitional runtime cross-service schema dependencies explicit.
- Added `scripts/audit-runtime-schema-dependency-baseline.ps1`.
- Wired the new audit into `scripts/verify-microservice-migration.ps1`.
- The gate fails if a service gains a new external schema dependency or if a dependency is removed without shrinking the baseline.

Current allowed transitional external schema dependencies:

- `attendance-service` -> `student`, `tenant_school`
- `catalog-service` -> `student`, `tenant_school`
- `fee-service` -> `student`, `tenant_school`
- `identity-service` -> `tenant_school`
- `reporting-service` -> `attendance`, `billing`, `catalog`, `fee`, `firefighting`, `notification`, `student`, `tenant_school`
- `student-service` -> `tenant_school`

Verified:

- `scripts/audit-runtime-schema-dependency-baseline.ps1` passed.
- Full `scripts/verify-microservice-migration.ps1` passed with the new runtime schema dependency gate included.

### 2026-06-27: Service DB Schema Defaults Removed From Public

Completed:

- Replaced remaining service DB schema fallback defaults from `public` to owned schemas.
- Updated `attendance-service`, `billing-service`, `workflow-service`, `firefighting-service`, and `reporting-service` `application.yml` schema defaults.
- Updated injected repository schema defaults for `attendance-service`, `billing-service`, and `workflow-service`.
- Added `scripts/audit-service-schema-defaults.ps1`.
- Wired the schema-default audit into `scripts/verify-microservice-migration.ps1`.

Verified:

- Static scan found no remaining `${*_DB_SCHEMA:public}` or `.db.schema:public` defaults in service Java/YAML sources.
- Targeted affected service tests passed:
  - `attendance-service`
  - `billing-service`
  - `workflow-service`
  - `firefighting-service`
  - `reporting-service`
- Full `scripts/verify-microservice-migration.ps1` passed with the new schema-default audit included.

### 2026-06-27: Data Boundary And Local Runtime Cleanup

Completed:

- Removed the `tenant-school-service` runtime read of `catalog.catalog_orders` from superadmin school stats.
- Shrunk `docs/RUNTIME_SCHEMA_DEPENDENCY_BASELINE.json` so `tenant-school-service -> catalog` is no longer allowed.
- Added `scripts/audit-legacy-public-retirement-readiness.ps1` to verify that legacy public-table mapping and archive-first retirement SQL generation remain usable without a live database.
- Added `scripts/audit-compose-profiles.ps1` and wired it into the migration verifier.
- Split local Docker runtime into `core` and `full` profiles, and removed the gateway dependency on the entire service graph so local operators can avoid starting every JVM.

Verified:

- `docker compose --profile core config --quiet` passed.
- `docker compose --profile full config --quiet` passed.
- `tenant-school-service` Maven tests passed: 11 tests, 0 failures.
- `api-gateway` Node tests passed: 10 tests, 0 failures.
- Full `scripts/verify-microservice-migration.ps1` passed with the new audits included.

### 2026-06-27: Request Correlation And Structured Logging Gate Added

Completed:

- Added `RequestCorrelationFilter` to all 12 Spring services.
- Each service now accepts or creates `X-Request-Id`, stores it in MDC as `requestId`, and returns it on the response.
- Added JSON `logback-spring.xml` configuration for all Spring services using the existing logstash encoder dependency.
- Added `scripts/audit-request-correlation-and-logging.ps1`.
- Wired the request-correlation and structured-logging audit into `scripts/verify-microservice-migration.ps1`.
- Updated affected CI target resolution to use the shared build/test catalogs instead of duplicating service matrix entries.
- Updated build/test catalog audits to validate the dynamic resolver contract.
- Fixed scoped internal-token guard calls in compatibility controllers flagged by the authorization audit.

Verified:

- `scripts/audit-request-correlation-and-logging.ps1` passed.
- Full `scripts/invoke-microservice-tests.ps1` passed for 14 service entries.
- Full `scripts/verify-microservice-migration.ps1` passed with the new observability gate included.

### 2026-06-27: Tenant-School Identity Runtime Dependency Removed

Completed:

- Removed tenant-school runtime joins to `identity.app_users` from zone admin reads, zone command responses, and superadmin school stats.
- Tenant-school now returns tenant-owned IDs and blank display fields where identity-owned user details previously leaked through direct SQL joins.
- Shrunk `docs/RUNTIME_SCHEMA_DEPENDENCY_BASELINE.json` so `tenant-school-service -> identity` is no longer an allowed transitional dependency.

Verified:

- `scripts/audit-runtime-schema-dependency-baseline.ps1` passed.
- `tenant-school-service` Maven test suite passed: 16 tests, 0 failures.
- Full `scripts/verify-microservice-migration.ps1` passed after shrinking the dependency baseline.

### 2026-06-28: Firefighting Tenant-School Runtime Dependency Removed

Completed:

- Removed the `firefighting-service` runtime read of `tenant_school.schools` from firefighting request creation.
- Firefighting request creation now validates only that `schoolId` is supplied; school ownership/existence remains a tenant-school responsibility instead of a direct cross-schema query.
- Shrunk `docs/RUNTIME_SCHEMA_DEPENDENCY_BASELINE.json` so `firefighting-service -> tenant_school` is no longer an allowed transitional dependency.

Verified:

- `scripts/audit-runtime-schema-dependency-baseline.ps1` passed.
- `firefighting-service` Maven test suite passed.
- Full `scripts/verify-microservice-migration.ps1` passed after shrinking the dependency baseline.
