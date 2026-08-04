# GCP Support Files

Status: mixed. Legacy Cloud Build deployment notes are retired; support files in this directory can still be active.

The previous day-to-day deployment path used GitHub Actions plus `cloudbuild.yaml`. That path was retired on 2026-08-03. The active deployment path is now `.github/workflows/build-release.yml` plus Google Cloud Deploy.

Do not run old Cloud Build deployment commands from saved notes or terminal history. The repository no longer contains `cloudbuild.yaml`.

Current CI/CD implementation:

- [CI/CD Current State](../../docs/current-state/deployment-cicd.md)

Files in this directory are not one single deployment procedure. Treat them by purpose:

- `observability/*.tf`: active observability Terraform source.
- `artifact-registry-cleanup-policies.json`: active cleanup-policy source.
- `db-snapshot-bucket-lifecycle.json` and `github-deploy-source-bucket-lifecycle.json`: lifecycle policy source.
- `direct-service-smoke-job.template.yaml`: support template; not currently invoked by `build-release.yml`.
- `github-deploy-runtime-operator-role.yaml`: source for a custom IAM posture that is not currently verified live.

Historical GCP bootstrap and operations documents can still be useful for infrastructure facts, secrets, Google Drive intake setup, cost guardrails, and smoke scripts. Do not treat older Cloud Build commands as current deployment instructions.
