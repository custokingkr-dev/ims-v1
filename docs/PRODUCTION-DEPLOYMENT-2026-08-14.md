# Production Deployment Evidence — 2026-08-14

Evidence captured: 2026-08-14 22:47 IST

Scope: promotion of the complete verified `dev` state to `main` and the existing `custoking` production
environment. This deployment did not perform the planned split-project migration to `custoking-dev` and
`custoking-prod`; those destination projects and their data-integrity gates remain separate NO-GO work.

This document contains no credential values or student-level data.

## Release identity

| Item | Verified value |
| --- | --- |
| Production promotion PR | GitHub PR `#108` |
| Production commit | `754f04179534365f56da501c72615c1b3370e67e` |
| Production CD run | `31820051376` — `success` |
| Production CodeQL run | `31820051149` — `success` |
| Dev source commit before promotion | `af33545fbdb567fbe2128d4df30533d29aac42d0` |
| Dev release run for the final runtime change | `31817443657` — `success` |
| Production environment approval | Approved for run `31820051376` after required checks passed |

## Promoted change groups

- permanent student deletion behavior and its tests;
- operator-scoped student-details/photo export workflow;
- OpenTelemetry exporter/background-flush correction and live verification tooling;
- GCP cost-control and guarded dev Cloud SQL lifecycle changes;
- Java, Node, frontend, GitHub Actions, and container dependency updates;
- exact-digest image promotion and Trivy evidence controls; and
- reconciled architecture, migration, cost, and operations documentation.

## Pre-production gates

Promotion PR `#108` passed:

- all seven affected service test jobs;
- all seven affected container build jobs;
- Java/Kotlin and JavaScript/TypeScript CodeQL analyses;
- the strict Gitleaks scan across the complete promotion history;
- secret-scan summary; and
- the required CI summary.

The one Gitleaks result found during the first promotion attempt was verified as documentation prose, not a
credential. Its original commit/file/rule/line fingerprint is the only entry in `.gitleaksignore`; the
official Gitleaks container then scanned the complete `origin/main..HEAD` range with no remaining finding.

## Deployment gates

Production CD run `31820051376` completed successfully after 32 minutes 44 seconds in the release job.
It passed:

- dev-approved immutable image resolution;
- exact-digest Trivy HIGH/CRITICAL gates and SARIF evidence for all seven services;
- serialized Cloud Deploy release creation and promotion;
- terminal rollout confirmation;
- changed Cloud Run revision/digest/traffic verification;
- gateway health smoke; and
- release evidence upload.

All seven production rollouts for release prefix `rel-prod-754f04179534` reached `SUCCEEDED`.

## Live production verification

Independent authenticated checks after the workflow completed returned HTTP 200:

| Cloud Run service | Path | Ready revision |
| --- | --- | --- |
| `custoking-identity-service-prod` | `/actuator/health` | `custoking-identity-service-prod-mst6q2c6` |
| `custoking-school-core-service-prod` | `/actuator/health` | `custoking-school-core-service-prod-mst6l395` |
| `custoking-operations-service-prod` | `/actuator/health` | `custoking-operations-service-prod-mst6uctz` |
| `custoking-platform-service-prod` | `/actuator/health` | `custoking-platform-service-prod-mst747gj` |
| `custoking-billing-service-prod` | `/actuator/health` | `custoking-billing-service-prod-mst6z4wp` |
| `custoking-api-gateway-prod` | `/gateway-health` | `custoking-api-gateway-prod-mst78ymz` |
| `custoking-frontend-prod` | `/` | `custoking-frontend-prod-mst7dbqm` |

Each revision was `latestReady` and served 100 percent of its service traffic.

## OpenTelemetry verification

`scripts/verify-cloud-trace.ps1` passed for production after fresh authenticated requests. Every traced
service reported version `754f04179534365f56da501c72615c1b3370e67e`:

| Service | Matching current-revision traces in the verification window |
| --- | ---: |
| API gateway | 20 |
| Billing | 14 |
| Identity | 11 |
| Operations | 18 |
| Platform | 20 |
| School core | 20 |

The 30-minute exporter-error query returned `exporterErrorCount = 0`. This closes production promotion of
the exporter fix. OBS-01 still requires a complete school-day stability window and a proven backup alert
recipient; this point-in-time verification does not replace those acceptance gates.

## Cost-control and database state

- Dev release `31817443657` selected only `frontend` and logged that it skipped the development Cloud SQL
  start because no database-backed service was affected.
- `custoking-db-dev` remained `STOPPED` with activation policy `NEVER`.
- A fresh `status` dispatch of `Ops / GCP cost controls`, run `31823448382`, passed on production commit
  `754f0417`, including dedicated configuration validation and Workload Identity authentication.
- `custoking-db-prod` remained `RUNNABLE` with activation policy `ALWAYS`; no production database stop or
  tier change was performed.

## Repository state and remaining authority gates

- `origin/dev` is an ancestor of `origin/main`; no dev runtime change is missing from production.
- There were zero open pull requests after promotion.
- The deliberate documentation branch `codex/repository-docs-cost-20260814` remains preserved and is already
  contained in both `dev` and `main` history.
- Dependabot alerts remain disabled. The authenticated repository identity has write but not repository-admin
  authority; the enable endpoint returned HTTP 404 and the alert-list endpoint confirmed the disabled state.
- Branch/ruleset protection, disabling production self-review, repository visibility, and Dependabot-alert
  enablement therefore still require a repository administrator.

Broad multi-school onboarding remains NO-GO until the authoritative checklist in
`REMAINING-WORK-2026-08-12.md` is complete. In particular, the split-project migration, capacity/query
evidence, production database decision, data/notification governance, recovery evidence, and named-school
full-day canary were not inferred or marked complete by this deployment.
