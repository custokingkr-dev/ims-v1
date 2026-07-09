# GCP Deployment Runbook

Custoking IMS deploys to Cloud Run in a single GCP project, **`custoking`**, region
`asia-south2`. Both environments (`dev` and `prod`) live in that one project, separated
by an `-${_ENV}` suffix on every Cloud Run service, Secret Manager secret, and Pub/Sub
topic. Images are env-agnostic — built once (tagged by commit SHA) and the same tag is
promoted `dev -> prod` (`_SKIP_BUILD=true` on the prod promotion so nothing rebuilds).

For the full bootstrap runbook (project creation, IAM, WIF, Secret Manager seeding,
Cloud SQL, Artifact Registry) see `docs/GREENFIELD-DEPLOYMENT-PLAN.md`. This file covers
day-to-day deploy/validate/promote operations only.

```text
custoking-frontend-<env> -> custoking-api-gateway-<env> -> private domain services -> Cloud SQL
```

## Services

Public:

- `custoking-frontend-<env>`
- `custoking-api-gateway-<env>`

Private (5 merged domain services — the original 12 per-domain services were merged in
Phase 2):

- `custoking-identity-service-<env>` — login, JWT, users, roles, RBAC
- `custoking-school-core-service-<env>` — tenant/school + student + attendance + fee + catalog
- `custoking-operations-service-<env>` — workflow + firefighting
- `custoking-platform-service-<env>` — reporting + notification + audit
- `custoking-billing-service-<env>` — superadmin invoices, order sequences

Artifact Registry repo: `custoking` (env-agnostic images, region `asia-south2`).

## Required Secrets

Secret Manager holds one secret per name below, each suffixed `-<env>` (e.g.
`db-password-dev`, `db-password-prod`). This list is the exact set `cloudbuild.yaml`
wires via `--set-secrets`/`availableSecrets` — keep it in sync if the build changes:

- `db-password` — Flyway migration DB user password
- `app-rt-password` — runtime app DB user (`app_rt`) password
- `jwt-secret` — identity-service JWT signing secret
- `identity-introspection-token` — gateway/service-to-service token for identity-service
- `tenant-school-read-token` — read token for the tenant/school domain (school-core-service)
- `student-read-token` — read token for the student domain (school-core-service)
- `attendance-read-token` — read token for the attendance domain (school-core-service)
- `fee-read-token` — read token for the fee domain (school-core-service)
- `catalog-read-token` — read token for the catalog domain (school-core-service)
- `workflow-read-token` — read token for the workflow domain (operations-service)
- `firefighting-read-token` — read token for the firefighting domain (operations-service)
- `reporting-read-token` — read token for the reporting domain (platform-service)
- `audit-ingest-token` — audit ingestion token (platform-service)
- `notification-status-token` — notification status token (platform-service)
- `billing-service-token` — billing-service token
- `msg91-auth-key` — MSG91 email/SMS/WhatsApp API key (platform-service)

## Deploy

GitHub Actions:

1. Open **Actions -> Deploy to GCP**.
2. Select environment (`dev` or `prod`).
3. Leave `commit_sha` empty to deploy the workflow SHA, or provide a tag.
4. Select `deploy_services`. The default is `frontend`, which builds and deploys only that service.
5. Select `all` only when an approved full fleet rollout is required.
6. Promotion to `prod` reuses the image already built and validated in `dev`
   (`_SKIP_BUILD=true`) and is gated by the `prod` GitHub Environment approval.

Manual Cloud Build:

```powershell
gcloud builds submit --config=cloudbuild.yaml --substitutions=_COMMIT_SHA=<tag>,_REGION=asia-south2,_ENV=dev,_DEPLOY_SERVICES=frontend --project=custoking .
```

Cloud Build builds and deploys only the selected service by default. Use `_DEPLOY_SERVICES=all` for the full fleet, or pass a comma-separated list such as `_DEPLOY_SERVICES=frontend,api-gateway` for a small coordinated release. When `api-gateway` is selected, Cloud Build resolves the currently deployed service URLs (for the same `_ENV`) and rewires the gateway upstreams.

GitHub Actions stages Cloud Build source archives in `gs://custoking-github-deploy-source/source`. Keep the bucket lifecycle policy applied so old source archives are removed automatically:

```powershell
gcloud storage buckets update gs://custoking-github-deploy-source --lifecycle-file=deploy/gcp/github-deploy-source-bucket-lifecycle.json
```

GitHub Actions runtime permissions are defined by the project custom roles and bucket-scoped IAM, not broad project admin roles (deploy is keyless via Workload Identity Federation — no service-account keys). Keep the runtime custom role synchronized from source:

```powershell
gcloud iam roles update githubDeployRuntimeOperator --project=custoking --file=deploy/gcp/github-deploy-runtime-operator-role.yaml
```

## Validate

```powershell
powershell -ExecutionPolicy Bypass -File scripts\ensure-direct-service-smoke-identity.ps1 -ProjectId custoking -Region asia-south2
powershell -ExecutionPolicy Bypass -File scripts\new-direct-service-smoke-job.ps1 -ProjectId custoking -Region asia-south2 -OutputPath artifacts\direct-service-smoke-job.generated.yaml
gcloud run jobs replace artifacts\direct-service-smoke-job.generated.yaml --project=custoking --region=asia-south2
gcloud run jobs execute ims-direct-service-smoke --project=custoking --region=asia-south2 --wait
```

For local gateway validation:

```powershell
docker compose up -d --build
powershell -ExecutionPolicy Bypass -File scripts\smoke-gateway-routes.ps1
powershell -ExecutionPolicy Bypass -File scripts\smoke-microservice-features.ps1
```

## Promotion Evidence

Before production promotion, run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\invoke-promotion-preflight.ps1
powershell -ExecutionPolicy Bypass -File scripts\export-cloud-run-revisions.ps1
powershell -ExecutionPolicy Bypass -File scripts\export-image-digests.ps1 -ImageDigestJson image-digests.json
powershell -ExecutionPolicy Bypass -File scripts\export-cloud-build-evidence.ps1 -CloudBuildJson cloud-build-evidence.json
powershell -ExecutionPolicy Bypass -File scripts\export-secret-manager-evidence.ps1 -SecretManagerEvidenceJson secret-manager-evidence.json
powershell -ExecutionPolicy Bypass -File scripts\new-legacy-retirement-evidence.ps1
powershell -ExecutionPolicy Bypass -File scripts\new-rollback-drill-evidence.ps1
powershell -ExecutionPolicy Bypass -File scripts\invoke-production-readiness-bundle.ps1 -OutputDirectory promotion-artifacts
powershell -ExecutionPolicy Bypass -File scripts\invoke-real-environment-readiness-preflight.ps1
powershell -ExecutionPolicy Bypass -File scripts\new-promotion-bundle-manifest.ps1 -PromotionBundleManifestJson promotion-bundle-manifest.json
```

Archive these evidence artifacts with the release:

- `secret-manager-evidence.json`
- `cloud-run-iam-evidence.json`
- `legacy-retirement-evidence.json`
- `rollback-drill-evidence.json`
- `deployment-readiness-smoke.json`
- `legacy-compatibility-audit.json`
- `cloud-run-revisions.json`
- `image-digests.json`
- `cloud-build-evidence.json`
- `promotion-artifacts`
- `production-readiness-report.json`
- `production-readiness-report.md`
- `real-environment-readiness-preflight.json`
- `real-environment-readiness-preflight.md`
- `promotion-bundle-manifest.json`
