# Legacy GCP Deployment Runbook

Status: retired on 2026-08-03.

The previous day-to-day deployment path used GitHub Actions plus `cloudbuild.yaml`. That active implementation has been removed from this branch while CI/CD v2 is redesigned around GitHub Actions, Workload Identity Federation, Artifact Registry, Cloud Deploy, Skaffold-rendered Cloud Run manifests, canary rollout, and release evidence.

Do not run old Cloud Build deployment commands from saved notes or terminal history. The repository no longer contains `cloudbuild.yaml`.

Current CI/CD replacement plan:

- [CI/CD v2 Architecture Plan](../../docs/current-state/deployment-cicd.md)

Historical GCP bootstrap and operations documents can still be useful for infrastructure facts, secrets, Google Drive intake setup, cost guardrails, and smoke scripts, but they are not an active deployment procedure until the v2 workflow files and Cloud Deploy manifests are added.
