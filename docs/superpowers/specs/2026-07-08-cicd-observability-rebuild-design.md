# CI/CD Rebuild + GCP-Native Observability — Design

**Date:** 2026-07-08
**Status:** Implemented for dev as of 2026-07-09; production promotion,
draft-PR workflow validation, and local `actionlint` validation remain as
follow-ups. See
`docs/superpowers/plans/2026-07-08-cicd-observability-rebuild.md` for the live
progress evidence.

**Author:** (pairing) Shubham + Claude

---

## Goal

Replace the current, partly-stale GitHub Actions setup with a clean, consolidated
CI/CD workflow set, and add GCP-native observability so a single request can be
watched flowing across every service (distributed tracing), with live logs,
golden-signal dashboards, and alerting — all versioned in-repo.

## Context — current state (verified 2026-07-08)

The greenfield project `custoking` is **bootstrapped and dev is actively deploying**.
GitHub config validated via `gh`: Variables (`GCP_PROJECT_ID=custoking`,
`GCP_REGION=asia-south2`, `WORKLOAD_IDENTITY_PROVIDER=projects/305630109861/…/github-provider`,
`DEPLOY_SERVICE_ACCOUNT`, `DIRECT_SMOKE_SERVICE_ACCOUNT`, `CLOUD_BUILD_SOURCE_STAGING_DIR`),
Environments `dev` (open) and `prod` (required reviewers). App secrets live in GCP
Secret Manager (keyless WIF — no GitHub secrets).

Five workflows exist today:

| Workflow | Verdict |
|---|---|
| `ci.yml` | **Keep + modernize.** Path-filtered per-service test + docker-build (no push) + gitleaks. Good bones; caching + CVE gate + integration folding needed. |
| `deploy.yml` | **Keep, working.** Reusable WIF → `gcloud builds submit cloudbuild.yaml` build-once; env-parameterized; direct-service smoke. Powers live dev deploys. |
| `release.yml` | **Keep, working.** push main → deploy-dev → gated promote-prod (same digest). Per-job concurrency fix already in place. |
| `security-container-scan.yml` | **Fold in.** Weekly/manual Trivy, one service at a time, rebuilds from scratch. Merge into the new security workflow + PR gate. |
| `whole-application-validation.yml` | **Delete.** Hardcodes the deleted legacy project and old project number, triggers on 15 workflows that no longer exist, resolves an un-suffixed `custoking-api-gateway` name. Its *valuable* content (full-stack integration smoke, tenant-isolation BOLA gate, migration boundary audit) moves onto the pipeline. |

Observability today: a manual runbook only (`curl` health, PowerShell smokes). **No**
structured-log/trace correlation config, **no** tracing instrumentation, **no**
dashboards/alerts/uptime/SLOs, **no** IaC for monitoring.

Build units (7): `identity-service`, `school-core-service`, `operations-service`,
`platform-service`, `billing-service` (Java 25 / Spring Boot 4.0.7), `api-gateway`
(Node http), `frontend` (Vite/React). Async decoupling over Pub/Sub
(`ims-reporting-events-v1-${_ENV}`) into platform projections.

## Decisions (locked with user 2026-07-08)

1. **Rebuild clean, keep what works** — replace the workflow *set*, but preserve the
   proven keyless-WIF build-once-promote chain rather than re-derive it.
2. **GCP-native observability** — Cloud Logging + Cloud Trace + Cloud Monitoring +
   Error Reporting; config as Terraform in-repo. Not Grafana/Datadog.
3. **Full request-flow tracing** — OpenTelemetry in all 5 Spring Boot services + the
   Node gateway; trace context across Pub/Sub hops.
4. **Practical supply-chain** — Trivy CVE gate (fail on CRITICAL) + GCP Artifact
   Analysis. No cosign/Binary Authorization for now.

## Out of scope

- Google Cloud Deploy adoption (plain gcloud/Cloud Build promotion is sufficient at 7
  services / one project).
- cosign image signing + Binary Authorization (revisit later).
- Grafana/Datadog.
- Broader Terraform migration of the deploy pipeline (observability gets its own small
  Terraform module; the deploy pipeline stays gcloud/Cloud Build).
- App Hub service-topology view (optional stretch, timeboxed — not committed).

---

## Design — Part A: CI/CD

Target workflow set (4 files replace 5):

### A1. `ci.yml` — PR + push-to-main quality gate
- `changes`: path-filter → per-service matrix (reuse `resolve-affected-ci-targets.ps1`;
  keep the "force all" triggers for `cloudbuild.yaml`, compose, catalogs, workflow files).
- `test` (matrix, changed only): `mvn test` (Java, `setup-java` maven cache),
  `node --test` (gateway), `vitest` + `npm run build` + `npm audit --audit-level=critical`
  (frontend, `setup-node` npm cache).
- `build` (matrix, changed only): `docker/build-push-action` with `cache-from/to: type=gha`
  (**replaces archived Kaniko caching**), `push: false` — validates images.
- `scan`: **Trivy** CVE scan (CRITICAL, `--ignore-unfixed`, `--exit-code 1`) against the
  images built in `build`; **gitleaks** secret scan (whole repo, unconditional).
  → CRITICAL vuln or leaked secret **blocks the merge**.
- `integration` (**push-to-main only**, plus nightly `schedule`): docker-compose full
  stack (Postgres + 5 services + gateway + frontend) → `smoke-gateway-routes.ps1`,
  `smoke-microservice-features.ps1`, `verify-microservice-migration.ps1 -RunDbAudit`,
  `audit-tenant-isolation.ps1` (BOLA). Kept off the per-PR critical path for speed;
  runs on every main merge + nightly. (Open question O1 below.)
- `ci-result`: single required gate job.
- Trigger hardening: `paths-ignore` for docs-only pushes where safe.

### A2. `deploy.yml` — reusable deploy engine (keep, cleaned)
- Unchanged mechanism: WIF auth from Variables → `gcloud builds submit --config=cloudbuild.yaml`
  with `_ENV`, `_SKIP_BUILD`, `_COMMIT_SHA`, `_REGION`, `_DB_HOST`, `_DB_NAME`, `_DEPLOY_SERVICES`.
- Cleanup: remove any lingering stale defaults; post-deploy **gateway smoke uses the
  env-suffixed name** `custoking-api-gateway-${env}` (the bug that lived in the deleted
  validation workflow must not reappear here).

### A3. `release.yml` — push main → dev → gated prod (keep)
- Unchanged: `deploy-dev` (env `dev`) → `promote-prod` (env `prod`, `skip_build:true`,
  same digest). Keep the per-job concurrency scoping (`cd-dev` no-cancel / `cd-prod` cancel).

### A4. `security-scan.yml` — scheduled deep scan (consolidates `security-container-scan.yml`)
- Weekly cron + `workflow_dispatch`: Trivy over **all 7** images (matrix, not one-at-a-time),
  plus rely on **GCP Artifact Analysis** auto-scanning on push to Artifact Registry.
- Higher-severity/policy reporting than the blocking PR gate (informational, doesn't block).

### A5. `cloudbuild.yaml` — keep, cleaned
- Migrate Docker layer caching off Kaniko (BuildKit `--cache-to/--cache-from` to Artifact
  Registry, or accept GHA-side caching in `ci.yml` and keep Cloud Build simple).
- No behavioural change to the `_ENV`/`_SKIP_BUILD`/`selected()` logic.

### A6. Deletions / doc fixes
- Delete `whole-application-validation.yml` and `security-container-scan.yml`.
- Rewrite `deploy/gcp/README.md` to the 5-merged-service reality + correct 17-secret,
  `-${_ENV}`-suffixed list; drop all stale legacy-project references.

---

## Design — Part B: Observability (GCP-native)

### B1. Structured logging + trace correlation (all services + gateway)
- Emit JSON to stdout (Spring already logs `jsonPayload`); ensure each line carries
  `severity` (uppercase) and the trace-link fields `logging.googleapis.com/trace`
  (`projects/custoking/traces/<traceId>`) and `logging.googleapis.com/spanId`.
- Node gateway: JSON logger emitting the same trace/span fields from the active OTel context.

### B2. Distributed tracing — the "application flow"
- **5 Spring Boot services:** add the first-party `spring-boot-starter-opentelemetry`
  (Spring Boot 4.0.7 clears the ≥4.0.1 floor). Configure OTLP exporter → Cloud Trace.
  No Java agent. Auto-instruments HTTP + JDBC + messaging.
- **Node gateway:** `@opentelemetry/sdk-node` + `@opentelemetry/auto-instrumentations-node`
  + `@google-cloud/opentelemetry-cloud-trace-exporter`. It already forwards `traceparent`;
  now it records its own spans and propagates W3C context to services.
- **Pub/Sub hops:** inject/extract trace context via message attributes so the async
  reporting/notification/audit projections join the same trace.
- Runtime wiring via env (OTLP endpoint / sampling) injected in `cloudbuild.yaml` per env;
  sampling ratio configurable (e.g. 100% dev, lower prod).

### B3. Monitoring config as Terraform — new `deploy/gcp/observability/`
Env-parameterized (`-dev`/`-prod`) module defining:
- **Dashboards** (`google_monitoring_dashboard`): golden signals per service (latency,
  traffic, 5xx rate, instance/CPU/mem saturation) from Cloud Run built-in metrics.
- **Uptime checks** (`google_monitoring_uptime_check_config`) on each service health endpoint.
- **Alert policies** (`google_monitoring_alert_policy`): 5xx rate, p95 latency, max-instance
  saturation, plus the async-health signals the runbook already prescribes (outbox pending /
  dead-letter / oldest-age, notification inbox backlog) via **log-based metrics**
  (`google_logging_metric`).
- **Error Reporting**: automatic from structured error logs (no resource needed; validated).
- **SLOs**: request-latency + availability SLOs with burn-rate alerts.
- Stretch (timeboxed, not committed): App Hub registration for the native topology graph.

### B4. Live viewing (documented, no build)
- Live logs: Logs Explorer streaming / `gcloud logging tail 'resource.type=cloud_run_revision'`.
- Request flow: Cloud Trace Explorer waterfall; click a log's trace id → its trace.
- Update `docs/MICROSERVICE-OBSERVABILITY-RUNBOOK.md` to point at the real dashboards/URLs.

---

## Sequencing

Everything is now activatable (project bootstrapped). Suggested order:
1. **CI consolidation** (`ci.yml` modernize, delete stale workflows, `security-scan.yml`).
   Immediate value, low risk, no runtime change.
2. **App instrumentation** (logging trace-correlation + OTel) per service + gateway,
   unit-tested per service; wire runtime env in `cloudbuild.yaml`.
3. **Terraform observability module**, applied to dev, then prod.
4. **Runbook + README** doc rewrites.

Deploy-chain changes are surgical (dev is live — must not regress deploys).

## Risks & mitigations

- **Breaking live dev deploys** — treat `release/deploy/cloudbuild` as keep-and-verify;
  every deploy-path change validated against dev before prod.
- **Trace overhead / cost** — sample below 100% in prod; Cloud Trace/Logging free tiers are
  generous at this scale.
- **OTel + Spring Boot 4.0.7 specifics** — first-party starter is new; validate on one
  service first (identity) before rolling to all five.
- **Kaniko cache migration** — verify build times don't regress after switching cache backend.
- **Pub/Sub context propagation** — manual; cover with a projection trace test.

## Acceptance criteria

- 4 workflows only; `whole-application-validation.yml` + `security-container-scan.yml` gone;
  zero stale legacy-project references in active source/config.
- PR gate: per-service tests + docker build (cached) + Trivy CVE (blocks on CRITICAL) +
  gitleaks. Integration/BOLA/migration run on main + nightly.
- A request through the dev gateway produces a **single Cloud Trace waterfall** spanning
  gateway → service(s) → DB, and its logs are linked to that trace.
- Cloud Monitoring shows golden-signal dashboards per service; uptime checks + alert
  policies + SLOs exist as Terraform and are applied to dev.
- Live log tail + trace explorer documented in the runbook against real URLs.

## Open questions

- **O1 — Integration tests on every PR, or main+nightly only?** Recommendation: main +
  nightly (keeps PRs fast; full stack is heavy). Switchable to per-PR (or label-gated) if
  you want maximum PR safety.
- **O2 — Prod trace sampling ratio?** Recommendation: 100% dev, 10–20% prod (tune later).
- **O3 — Turn on `prevent_self_review` for prod?** Currently off (you're effectively sole
  approver). Leave off unless a second approver exists.
