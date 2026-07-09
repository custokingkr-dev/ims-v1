# Custoking IMS Current-State Documentation

Last verified: 2026-07-09 from repository files and live GCP project `custoking`.

This documentation bundle captures the current project state after the dev/prod greenfield deployment and the CI/CD/observability rebuild. It is intentionally evidence-led: if a fact could not be verified from code, deployment configuration, local artifacts, or live GCP inventory, it is listed in [gaps-and-drift.md](gaps-and-drift.md) instead of being assumed.

## Document Map

- [project-architecture.md](project-architecture.md) - service topology, runtime flow, data ownership, auth, RLS, and frontend/gateway boundaries.
- [gcp-infrastructure.md](gcp-infrastructure.md) - live GCP services, Cloud Run, Cloud SQL, Pub/Sub, IAM, WIF, Artifact Registry, buckets, secrets, and drift.
- [deployment-cicd.md](deployment-cicd.md) - GitHub Actions, Cloud Build, build-once-promote flow, deployment substitutions, evidence, and smoke gates.
- [codebase-conventions.md](codebase-conventions.md) - repository layout, Java/Node/React conventions, service config, route ownership, testing, and local dev.
- [event-models.md](event-models.md) - event envelope, transactional outbox, Pub/Sub push ingress, projection projectors, event types, and idempotency.
- [observability-operations.md](observability-operations.md) - dashboards, uptime, alerts, log metrics, traces, runtime evidence, and operations checks.
- [gaps-and-drift.md](gaps-and-drift.md) - verified missing items, stale docs, and follow-up work.

## Source Trail

Primary repository files used:

- `README.md`
- `deploy/gcp/README.md`
- `docs/GREENFIELD-DEPLOYMENT-PLAN.md`
- `docs/EVENT-ENVELOPE-CONTRACT.md`
- `docs/MICROSERVICE-OBSERVABILITY-RUNBOOK.md`
- `docs/MICROSERVICE-RLS-ROLLOUT-RUNBOOK.md`
- `.github/workflows/ci.yml`
- `.github/workflows/deploy.yml`
- `.github/workflows/release.yml`
- `.github/workflows/security-scan.yml`
- `cloudbuild.yaml`
- `deploy/gcp/observability/*.tf`
- `docker-compose.yml`
- service `application.yml`, controllers, outbox, security, and projector source files under `services/`
- frontend API and route source under `frontend/src/`

Live GCP inventory was queried with `gcloud.cmd` because PowerShell blocked the `gcloud.ps1` shim under the current execution policy.

## Non-Goals

This bundle does not include secret values, generated tokens, database passwords, JWT secrets, Pub/Sub push tokens, or production user passwords. It documents secret names and secret references only.

This bundle does not claim every business workflow has been freshly mutation-tested on 2026-07-09. Where the latest verification was read-only or configuration-level, that is stated explicitly.
