# GCP Deployment Runbook

Custoking IMS deploys to Cloud Run as a service-only topology:

```text
custoking-frontend -> custoking-api-gateway -> private domain services -> Cloud SQL
```

There is no `custoking-backend` service in the current deployment path.

## Services

Public:

- `custoking-frontend`
- `custoking-api-gateway`

Private:

- `custoking-identity-service`
- `custoking-tenant-school-service`
- `custoking-student-service`
- `custoking-attendance-service`
- `custoking-fee-service`
- `custoking-catalog-service`
- `custoking-workflow-service`
- `custoking-firefighting-service`
- `custoking-reporting-service`
- `custoking-billing-service`
- `custoking-audit-service`
- `custoking-notification-service`

## Required Secrets

Create these in Secret Manager before deployment:

- `db-password`
- `jwt-secret`
- `identity-introspection-token`
- `tenant-school-read-token`
- `student-read-token`
- `attendance-read-token`
- `fee-read-token`
- `catalog-read-token`
- `workflow-read-token`
- `firefighting-read-token`
- `reporting-read-token`
- `billing-service-token`
- `audit-ingest-token`
- `notification-status-token`
- `msg91-auth-key`

## Deploy

GitHub Actions:

1. Open **Actions -> Deploy to GCP**.
2. Select environment.
3. Leave `commit_sha` empty to deploy the workflow SHA, or provide a tag.
4. Select `deploy_services`. The default is `frontend`, which builds and deploys only that service.
5. Select `all` only when an approved full fleet rollout is required.
6. Keep direct smoke enabled for production full/API/domain-service deploys.

Manual Cloud Build:

```powershell
gcloud builds submit --config=cloudbuild.yaml --substitutions=_COMMIT_SHA=<tag>,_REGION=asia-south2,_DEPLOY_SERVICES=frontend --project=custoking-ims .
```

Cloud Build builds and deploys only the selected service by default. Use `_DEPLOY_SERVICES=all` for the previous full rollout behavior, or pass a comma-separated list such as `_DEPLOY_SERVICES=frontend,api-gateway` for a small coordinated release. When `api-gateway` is selected, Cloud Build resolves the currently deployed service URLs and rewires the gateway upstreams.

GitHub Actions stages Cloud Build source archives in `gs://custoking-ims-github-deploy-source/source`. Keep the bucket lifecycle policy applied so old source archives are removed automatically:

```powershell
gcloud storage buckets update gs://custoking-ims-github-deploy-source --lifecycle-file=deploy/gcp/github-deploy-source-bucket-lifecycle.json
```

GitHub Actions runtime permissions are defined by the project custom roles and bucket-scoped IAM, not broad project admin roles. Keep the runtime custom role synchronized from source:

```powershell
gcloud iam roles update githubDeployRuntimeOperator --project=custoking-ims --file=deploy/gcp/github-deploy-runtime-operator-role.yaml
```

## Validate

```powershell
powershell -ExecutionPolicy Bypass -File scripts\ensure-direct-service-smoke-identity.ps1 -ProjectId custoking-ims -Region asia-south2
powershell -ExecutionPolicy Bypass -File scripts\new-direct-service-smoke-job.ps1 -ProjectId custoking-ims -Region asia-south2 -OutputPath artifacts\direct-service-smoke-job.generated.yaml
gcloud run jobs replace artifacts\direct-service-smoke-job.generated.yaml --project=custoking-ims --region=asia-south2
gcloud run jobs execute ims-direct-service-smoke --project=custoking-ims --region=asia-south2 --wait
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
