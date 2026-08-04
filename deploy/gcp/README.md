# GCP Support Files

Status: mixed. Legacy Cloud Build deployment notes are retired; support files in this directory can still be active.

The previous day-to-day deployment path used GitHub Actions plus `cloudbuild.yaml`. That path was retired on 2026-08-03. The active path is `.github/workflows/build-release.yml`: affected-service direct Cloud Run releases for normal dev changes and Google Cloud Deploy for production canaries or deployment-configuration changes.

Do not run old Cloud Build deployment commands from saved notes or terminal history. The repository no longer contains `cloudbuild.yaml`.

Current CI/CD implementation:

- [CI/CD Current State](../../docs/current-state/deployment-cicd.md)

Files in this directory are not one single deployment procedure. Treat them by purpose:

- `observability/*.tf`: active observability Terraform source.
- `artifact-registry-cleanup-policies.json`: active cleanup-policy source.
- `db-snapshot-bucket-lifecycle.json` and `github-deploy-source-bucket-lifecycle.json`: lifecycle policy source.
- `direct-service-smoke-job.template.yaml`: optional authenticated business-smoke support. The release workflow performs Cloud Run readiness/digest/traffic checks, frontend HTTP verification when changed, and final gateway health; it does not invoke this job.
- `github-deploy-runtime-operator-role.yaml`: source for a custom IAM posture that is not currently verified live.

Historical GCP bootstrap and operations documents can still be useful for infrastructure facts, secrets, Google Drive intake setup, cost guardrails, and smoke scripts. Do not treat older Cloud Build commands as current deployment instructions.

## Manual Production Readiness Bundle

The branch release workflow creates its own `release-evidence` artifact. A broader operator-led production readiness review remains available through `scripts\invoke-promotion-preflight.ps1` and the bundle scripts documented in `docs/MICROSERVICES-COMPLETION-PLAN.md`.

That separate review can consume:

- `deployment-readiness-smoke.json`
- `legacy-compatibility-audit.json`
- `secret-manager-evidence.json`
- `cloud-run-iam-evidence.json`
- `legacy-retirement-evidence.json`
- `rollback-drill-evidence.json`

These are not generated automatically by every branch deployment. Use them for a deliberate production-readiness or migration review where the larger infrastructure and data-retirement evidence set is required.

Canonical operator commands and artifact names:

```text
scripts\export-cloud-run-revisions.ps1       -> cloud-run-revisions.json
scripts\export-image-digests.ps1             -> image-digests.json
scripts\export-secret-manager-evidence.ps1   -> secret-manager-evidence.json
scripts\new-legacy-retirement-evidence.ps1   -> legacy-retirement-evidence.json
scripts\new-rollback-drill-evidence.ps1      -> rollback-drill-evidence.json
scripts\new-promotion-bundle-manifest.ps1    -> promotion-bundle-manifest.json
scripts\invoke-real-environment-readiness-preflight.ps1
                                             -> real-environment-readiness-preflight.json
                                             -> real-environment-readiness-preflight.md
scripts\invoke-production-readiness-bundle.ps1
                                             -> promotion-artifacts
                                             -> production-readiness-report.json
                                             -> production-readiness-report.md
```

Example preflight artifact arguments:

```text
-ImageDigestJson image-digests.json
-SecretManagerEvidenceJson secret-manager-evidence.json
-CloudBuildJson cloud-build-evidence.json
-PromotionBundleManifestJson promotion-bundle-manifest.json
```

`scripts\export-cloud-build-evidence.ps1` and `cloud-build-evidence.json` are retained only for reviewing historical Cloud Build evidence. They do not make Cloud Build an active release path.
