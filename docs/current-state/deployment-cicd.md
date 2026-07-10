# Deployment and CI/CD

Last verified: 2026-07-09.

## Deployment Model

The project uses a build-once-promote deployment model:

```text
push to main
  -> CI
  -> CD deploy-dev: build images tagged with the commit SHA, deploy dev
  -> CD promote-prod: deploy the same image SHA to prod with _SKIP_BUILD=true
```

The prod job binds to the GitHub `prod` Environment and is expected to be protected by required reviewers.

## Local Developer Setup And CI Parity

Local setup is documented in `docs/LOCAL-SETUP.md`.

Current local build requirements:

- JDK 25 or newer for Java services.
- Node.js 20 or newer for frontend and API gateway.
- Docker Desktop / Docker Compose for the local service stack.
- Root Maven wrapper `mvnw.cmd` / `mvnw`; no global Maven install is required.

Useful local commands:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\setup-local-dev.ps1
powershell -ExecutionPolicy Bypass -File scripts\setup-local-dev.ps1 -ComposeProfile core -SkipDependencyInstall
powershell -ExecutionPolicy Bypass -File scripts\setup-local-dev.ps1 -ComposeProfile core -SkipDependencyInstall -ResetData -RemoveOrphans
powershell -ExecutionPolicy Bypass -File scripts\invoke-microservice-tests.ps1
```

Use `-ResetData -RemoveOrphans` only when a laptop has stale local Custoking containers or
an old compose Postgres volume; it deletes disposable local database data.

When `setup-local-dev.ps1` starts `core` or `full`, it runs `ensure-app-rt-local.ps1`
after migrations so local runtime DB grants match the split-service schema layout. Local
compose disables OTLP export by default because no collector is part of the laptop stack;
dev/prod Cloud Run deployments set the real Cloud Trace OTLP endpoint.

The local test runner now resolves a JDK 25+ install before invoking Maven, which matches GitHub Actions' Temurin Java 25 service-test jobs.

## GitHub Actions

### `.github/workflows/ci.yml`

Triggers:

- Push to `main` or `master`
- Pull request
- Daily schedule at `30 20 * * *`

Jobs:

- `detect-changes`
  - Uses `scripts/resolve-affected-ci-targets.ps1`.
  - Produces service and Docker matrices.
- `service-test`
  - Java services: Temurin Java 25, Maven tests.
  - Node gateway: Node 20, `node --test server.test.js`.
  - Frontend: Node 20, `npm ci --include=dev`, `npm audit --audit-level=critical`, `npm test`, `npm run build`.
- `docker-build`
  - Builds changed Docker images with Buildx.
  - Runs Trivy on changed images.
  - Fails on unfixed critical CVEs.
- `secret-scan`
  - Runs Gitleaks.
- `integration`
  - Runs on push/schedule.
  - Starts local split-service stack.
  - Ensures `app_rt`.
  - Recreates gateway in enforce overlay.
  - Seeds local dev users.
  - Runs migration verification, gateway smoke, microservice feature smoke, and BOLA tenant isolation audit.

### `.github/workflows/release.yml`

Triggers:

- Push to `main`
- Manual dispatch with `deploy_services`

Jobs:

- `deploy-dev`
  - Uses `deploy.yml`.
  - `environment=dev`
  - `commit_sha=${{ github.sha }}`
  - `deploy_services=all` unless overridden
  - `run_direct_smoke=true`
  - `run_gateway_smoke=true`
  - `skip_build=false`
- `promote-prod`
  - Needs successful dev deploy.
  - Uses the same commit SHA.
  - `environment=prod`
  - `run_direct_smoke=true`
  - `run_gateway_smoke=true`
  - `skip_build=true`

Concurrency:

- Dev deploys use group `cd-dev`, `cancel-in-progress=false`.
- Prod promotion uses group `cd-prod`, `cancel-in-progress=true`.
- This prevents a waiting prod approval from starving newer dev deploys.

### `.github/workflows/deploy.yml`

Reusable deployment engine and manual `GCP / Deploy` workflow.

Inputs:

- `environment`: `dev` or `prod`
- `commit_sha`: optional image tag
- `deploy_services`: `all`, a single service, or comma list
- `run_direct_smoke`
- `run_gateway_smoke`
- `skip_build`

Uses keyless auth:

- `google-github-actions/auth@v2`
- Workload Identity Provider from repo variable `WORKLOAD_IDENTITY_PROVIDER`
- Service account from repo variable `DEPLOY_SERVICE_ACCOUNT`

Required variables:

- Repo variables:
  - `GCP_PROJECT_ID`
  - `GCP_REGION`
  - `WORKLOAD_IDENTITY_PROVIDER`
  - `DEPLOY_SERVICE_ACCOUNT`
  - `DIRECT_SMOKE_SERVICE_ACCOUNT`
  - `CLOUD_BUILD_SOURCE_STAGING_DIR`
- Environment variables on GitHub `dev` / `prod` environments:
  - `DB_HOST`
  - `DB_NAME`

Deployment submits `cloudbuild.yaml` with substitutions and polls Cloud Build until terminal status.

After Cloud Build deploys the target environment, the main-line release workflow now runs:

- A private direct-service smoke job using `ims-direct-service-smoke`.
- A deployed gateway smoke against `custoking-api-gateway-<env>`.

The smoke steps use env-suffixed secrets such as `catalog-read-token-dev`, `tenant-school-read-token-prod`, and `db-password-<env>`. Direct-service smoke normalizes secret values before using them as HTTP headers, so a trailing CR/LF in Secret Manager does not break the smoke job before the service call is made.

The deployed gateway smoke provisions temporary smoke users through a private Cloud Run SQL job and reads that job's stdout from Cloud Logging to obtain the selected school/student context. The GitHub deploy service account therefore needs log-entry read permission; live IAM currently grants `roles/logging.viewer`. The final real-environment preflight is environment-aware and validates `custoking-*-dev` / `custoking-*-prod` Cloud Run services plus `-dev` / `-prod` secrets.

### `.github/workflows/security-scan.yml`

Weekly/manual informational Trivy scan across all build units. It reports HIGH and CRITICAL findings to the GitHub Security tab but does not fail the run.

## Cloud Build

Source: `cloudbuild.yaml`.

Important substitutions:

| Substitution | Current purpose |
| --- | --- |
| `_ENV` | `dev` or `prod`, suffixes Cloud Run services, secrets, topics |
| `_SKIP_BUILD` | `true` for prod promotion, skips image build/push |
| `_REGION` | Defaults to `asia-south2` |
| `_AR_REPO` | Defaults to `custoking` |
| `_COMMIT_SHA` | Docker tag, same tag promoted dev to prod |
| `_DEPLOY_SERVICES` | `frontend`, `api-gateway`, individual services, comma list, or `all` |
| `_DB_HOST` | Env-specific Cloud SQL private host |
| `_DB_NAME` | `custoking_dev` or `custoking_prod` |
| `_OTEL_SAMPLE` | `1.0` for dev, `0.2` for prod |
| `_APP_DB_USER` | `app_rt` |
| `_FLYWAY_DB_USER` | `appuser` |
| `_VPC_NETWORK` | `default` |
| `_VPC_SUBNET` | `default` |
| `_VPC_EGRESS` | `private-ranges-only` |
| `_REPORTING_TOPIC` | Base `ims-reporting-events-v1` |

Build units:

- `identity-service` -> `custoking-identity-service`
- `school-core-service` -> `custoking-school-core-service`
- `operations-service` -> `custoking-operations-service`
- `platform-service` -> `custoking-platform-service`
- `billing-service` -> `custoking-billing-service`
- `frontend` -> `custoking-frontend`
- `api-gateway` -> `custoking-api-gateway`

Cloud Build deploys selected Spring services first, then frontend/gateway. When deploying the gateway, it resolves current env-specific upstream Cloud Run URLs and writes them into gateway env vars.

## Deployed Service Config From Cloud Build

Spring domain services:

- `--no-allow-unauthenticated`
- `--port=8080`
- `--memory=768Mi`
- `--cpu-boost`
- `--max-instances=2`
- direct VPC network/subnet with `private-ranges-only`

Exceptions visible in source:

- `school-core-service`, `identity-service`, `operations-service`, and `billing-service` set `--min-instances=1`.
- `platform-service` does not set min instances in `cloudbuild.yaml`.

Gateway:

- `--allow-unauthenticated`
- `--port=80`
- `--max-instances=3`
- `--min-instances=1`
- `GATEWAY_AUTH_MODE=enforce`
- `GATEWAY_CLOUD_RUN_AUTH=auto`
- `GATEWAY_LOCAL_JWT_VERIFY=enabled`

Frontend:

- `--allow-unauthenticated`
- `--port=80`
- `--max-instances=2`
- `API_UPSTREAM` is updated to the current gateway URL after gateway deploy.

## Latest Verified CI/CD Evidence

GitHub Actions:

| Run | Workflow | Head SHA | Conclusion |
| --- | --- | --- | --- |
| `29007132778` | `ci` | `200d6731a32fb286983f85aa55e0f486933f90c1` | success |
| `29007133041` | `CD / Build once, promote dev -> prod` | `200d6731a32fb286983f85aa55e0f486933f90c1` | success |

Cloud Build latest successes:

| Build ID | Env | SHA | Services | Status |
| --- | --- | --- | --- | --- |
| `82d27c23-46d7-4211-92b2-bad99f646e3b` | dev | `200d6731a32fb286983f85aa55e0f486933f90c1` | all | SUCCESS |
| `99c1fa04-c0b9-407e-aba3-c5114eaf4db8` | prod | `200d6731a32fb286983f85aa55e0f486933f90c1` | all | SUCCESS |

There were earlier failed/cancelled runs on older SHAs, but the latest CI and CD for `200d6731...` were green.

## Smoke Evidence

Latest prod smoke artifact:

```text
artifacts/prod-deployment-smoke-final.json
generatedAtUtc: 2026-07-09T09:50:41.8361112Z
gatewayBaseUrl: https://custoking-api-gateway-prod-l7mhms5c2a-em.a.run.app
total: 39
passed: 39
failures: 0
```

The smoke covered gateway health and read-oriented routes across:

- tenant/school
- identity/RBAC
- workspace/dashboard
- student
- attendance
- fee
- catalog
- workflow
- firefighting
- reporting
- notification broadcasts
- audit logs
- billing invoices
- superadmin orders/schools

It did not prove every write-path or external notification provider delivery.

## Manual Deployment Commands

Manual Cloud Build example:

```powershell
gcloud.cmd builds submit `
  --config=cloudbuild.yaml `
  --substitutions=_ENV=dev,_SKIP_BUILD=false,_COMMIT_SHA=<sha>,_REGION=asia-south2,_DB_HOST=<host>,_DB_NAME=custoking_dev,_DEPLOY_SERVICES=all,_OTEL_SAMPLE=1.0 `
  --project=custoking `
  --gcs-source-staging-dir=gs://custoking-github-deploy-source/source `
  .
```

Prod promotion should use the same `_COMMIT_SHA` as dev and `_SKIP_BUILD=true`.

## Direct Service Smoke State

The reusable deploy workflow supports direct-service smoke, and `release.yml` now sets `run_direct_smoke=true` for both dev and prod.

The live job `ims-direct-service-smoke` exists. The workflow replaces it from `deploy/gcp/direct-service-smoke-job.template.yaml` on each deploy and injects env-suffixed secrets for the target environment.

## Artifact Warning

`artifacts/real-environment-readiness-final.json` is for older project `custoking-ims`, not the current greenfield project `custoking`. Do not use that artifact as proof for the current deployment.
