# Custoking IMS Current-State Documentation

Last reconciled: 2026-08-12 from repository files, GitHub configuration, and live GCP project `custoking`.

This documentation bundle captures the current project state after the dev/prod greenfield deployment and the CI/CD/observability rebuild. It is intentionally evidence-led: if a fact could not be verified from code, deployment configuration, local artifacts, or live GCP inventory, it is listed in [gaps-and-drift.md](gaps-and-drift.md) instead of being assumed.

## Document Map

- [project-architecture.md](project-architecture.md) - service topology, runtime flow, data ownership, auth, RLS, and frontend/gateway boundaries.
- [gcp-infrastructure.md](gcp-infrastructure.md) - live GCP services, Cloud Run, Cloud SQL, Pub/Sub, IAM, WIF, Artifact Registry, buckets, secrets, and drift.
- [deployment-cicd.md](deployment-cicd.md) - active branch-owned GitHub Actions and Cloud Deploy implementation.
- [codebase-conventions.md](codebase-conventions.md) - repository layout, Java/Node/React conventions, service config, route ownership, testing, and local dev.
- [event-models.md](event-models.md) - event envelope, transactional outbox, Pub/Sub push ingress, projection projectors, event types, and idempotency.
- [school-student-lifecycle.md](school-student-lifecycle.md) - implemented school onboarding readiness, localization lineage, student date handling, and import semantics.
- [observability-operations.md](observability-operations.md) - dashboards, uptime, alerts, log metrics, traces, runtime evidence, and operations checks.
- [gaps-and-drift.md](gaps-and-drift.md) - verified missing items, stale docs, and follow-up work.
- [../architecture/custoking-architecture.html](../architecture/custoking-architecture.html) - visual application and live GCP architecture with verified gaps.
- [../REMAINING-WORK-2026-08-12.md](../REMAINING-WORK-2026-08-12.md) - authoritative prioritized launch gates and acceptance criteria.

## Source Precedence

Use this bundle for the current system shape, the dated production/scale evidence for what happened at a
specific time, and [../DOCUMENTATION-INDEX.md](../DOCUMENTATION-INDEX.md) when documents conflict. Plans,
spike reports, migration roadmaps, and dated workstream narratives are retained as historical evidence;
they are not statements of current live state unless a current-state document explicitly adopts them.

## Source Trail

Primary repository files used:

- `README.md`
- `deploy/gcp/README.md`
- `docs/GREENFIELD-DEPLOYMENT-PLAN.md`
- `docs/EVENT-ENVELOPE-CONTRACT.md`
- `docs/MICROSERVICE-OBSERVABILITY-RUNBOOK.md`
- `docs/MICROSERVICE-RLS-ROLLOUT-RUNBOOK.md`
- Active CI/CD entrypoints: `.github/workflows/ci-pr.yml`, `.github/workflows/build-release.yml`, `.github/workflows/rollback.yml`, and `.github/workflows/security-scan.yml`
- Former active CI/CD entrypoints, now retired: `.github/workflows/ci.yml`, `.github/workflows/deploy.yml`, `.github/workflows/release.yml`, `.github/workflows/promote.yml`, and `cloudbuild.yaml`
- `deploy/gcp/observability/*.tf`
- `docker-compose.yml`
- service `application.yml`, controllers, outbox, security, and projector source files under `services/`
- frontend API and route source under `frontend/src/`

Live GCP inventory was queried with `gcloud.cmd` because PowerShell blocked the `gcloud.ps1` shim under the current execution policy.

## Non-Goals

This bundle does not include secret values, generated tokens, database passwords, JWT secrets, Pub/Sub push tokens, or production user passwords. It documents secret names and secret references only.

This bundle does not claim every business workflow has been freshly mutation-tested on 2026-08-04. Where the latest verification was read-only, configuration-level, or limited to `/gateway-health`, that is stated explicitly.
