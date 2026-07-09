# CI/CD Rebuild + GCP-Native Observability — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consolidate the 5 GitHub Actions workflows into 4 (keep the working keyless-WIF build-once-promote deploy chain, delete stale artifacts, fold real tests onto the pipeline, add a Trivy CVE gate), and add GCP-native observability — OpenTelemetry request-flow tracing across all 5 Spring Boot services + the Node gateway (incl. Pub/Sub hops), log/trace correlation, and a Terraform module for dashboards/uptime/alerts/SLOs.

**Architecture:** GitHub Actions → keyless WIF → Cloud Build → Cloud Run (`custoking`, env-suffixed `-dev`/`-prod`). Telemetry: services/gateway emit OTLP traces → Cloud Trace, JSON logs (trace-correlated) → Cloud Logging, Cloud Run metrics → Cloud Monitoring. Monitoring config as Terraform in `deploy/gcp/observability/`.

**Tech Stack:** GitHub Actions, Cloud Build, `gcloud`, Trivy, Docker Buildx (GHA cache), Spring Boot 4.0.7 `spring-boot-starter-opentelemetry` (Java 25), OpenTelemetry JS SDK, `@google-cloud/opentelemetry-cloud-trace-exporter`, Terraform (google provider), PowerShell smoke scripts.

## Progress Update - 2026-07-09

- Phase 1 implementation is present in the working tree: workflow inventory is now 4 files (`ci.yml`, `deploy.yml`, `release.yml`, `security-scan.yml`), the stale `whole-application-validation.yml` and `security-container-scan.yml` workflows are gone, `ci.yml` has Buildx + Trivy + integration gating, and `deploy.yml` resolves the env-suffixed gateway before running the whole-application smoke.
- Phase 2 implementation is present and deployed to dev: all five Spring services have the Spring Boot OpenTelemetry starter, OTLP exporter, GCP OTLP auth customizer, trace-correlated JSON logging, Pub/Sub trace-context propagation, and tracing tests; the Node gateway has OTel bootstrap, Cloud Trace export, trace-correlated logs, and propagation tests.
- Final dev deployment is Cloud Build `73d4d086-9fd6-4df2-a117-4296b1604369`, image tag `0e2f59c`, with live revisions `school-core 00064-cx9`, `identity 00055-4mq`, `operations 00053-zjr`, `platform 00052-l78`, `billing 00053-cbx`, `api-gateway 00053-ll4`, and `frontend 00104-5wk`.
- Runtime verification passed for the observability acceptance path: `/gateway-health` returned 200, `scripts\smoke-gateway-routes.ps1` passed 31/31 after the `0e2f59c` deploy, authenticated gateway smoke generated real traffic with 36/39 route checks passing before the final commit deployment, Cloud Trace returned 498 recent traces, and trace `fb369bec85534b5f6ebbccd16ba815b2` links gateway logs/spans to `custoking-school-core-service-dev` logs/spans for `/api/v1/fee-structure`.
- Final-revision log scan after the `0e2f59c` deploy shows `auth403=0` for all Java services and the gateway. Remaining exporter noise is intermittent timeout warnings only (`school-core=2`, `platform=3`, `billing=1` in the recent window).
- Phase 3 Terraform is applied to dev under `deploy/gcp/observability/`: dashboards, uptime checks, alert policies, async log-based metrics, Monitoring services, availability/latency SLOs, burn-rate alerts, Monitoring OIDC invoker grants, and runtime trace-writer IAM. Dev state uses `gs://custoking-terraform-state` with prefix `observability/dev`.
- Validation completed on 2026-07-09: Terraform `fmt -recursive -check`, `validate`, and token-backed `plan -var env=dev` all passed with `No changes`; `scripts\audit-observability-runbook.ps1` passed; duplicate Flyway source-version scan passed; active legacy-project reference scan found 0 active/unclassified hits; `git diff --check` had no whitespace errors; gateway tests passed 55/55.
- Remaining non-code/non-observability follow-ups: install/run `actionlint` locally or rely on CI-side workflow parse validation, open draft PR validation, promote/apply to prod when ready, and decide whether the three authenticated-smoke route mismatches (`GET /api/v1/schools/{id}/admin` 405, notifications 403, audit logs 401) need route/permission fixes.

## Global Constraints

- **Do NOT regress live dev deploys.** `release.yml`/`deploy.yml`/`cloudbuild.yaml` are keep-and-verify; validate any deploy-path change against dev before prod.
- Project `custoking`; region `asia-south2`; WIF provider `projects/305630109861/locations/global/workloadIdentityPools/github-pool/providers/github-provider`; deploy SA `github-actions-sa@custoking.iam.gserviceaccount.com`; source bucket `gs://custoking-github-deploy-source/source`. Read identity from repo **Variables**, never hardcode.
- Cloud Run service names: `custoking-<service>-<env>` (env-suffixed) — never un-suffixed.
- Keyless WIF only — **no** service-account key JSON, no GitHub app secrets (app secrets live in GCP Secret Manager).
- 7 build units: `identity-service`, `school-core-service`, `operations-service`, `platform-service`, `billing-service`, `api-gateway`, `frontend`.
- Spring logs `jsonPayload` (not `textPayload`) — log queries and correlation use `jsonPayload.*`.
- Trace sampling: **100% dev, 20% prod** (env-driven).
- Integration/BOLA/migration suites run on **push-to-main + nightly**, not per-PR.
- `prevent_self_review` stays **off** on prod.
- Commit messages should not include tool-specific co-author trailers; never stage local tool settings.
- Java build: `$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'`; `.\mvnw.cmd -f services\<svc>\pom.xml test`.
- Workflows validated with `actionlint`; Terraform with `terraform validate` + `terraform plan` (no apply in CI without approval).

---

## Phase 1 — CI consolidation (no runtime change; low risk)

### Task 1: Trivy CVE gate + modern Docker caching in `ci.yml`

**Files:**
- Modify: `.github/workflows/ci.yml`
- Reference: `scripts/resolve-affected-ci-targets.ps1`, `scripts/microservice-build-catalog.ps1`

**Interfaces:**
- Produces: a `scan` job that fails on CRITICAL fixable CVEs; `build` job emits a local image the `scan` job consumes (or Trivy builds from the same context).

- [ ] **Step 1 (test-first):** add `actionlint` validation. Install actionlint locally (`go install github.com/rhysd/actionlint/cmd/actionlint@latest` or the pinned binary) and run `actionlint .github/workflows/ci.yml`. Expected: clean before AND after edits (catches YAML/expression errors).
- [ ] **Step 2:** In `docker-build` job, replace any Kaniko/plain build with `docker/setup-buildx-action@v3` + `docker/build-push-action@v6` using `cache-from: type=gha` / `cache-to: type=gha,mode=max`, `push: false`, `load: true`, tag `:ci`. Keep the per-service matrix + path filtering.
- [ ] **Step 3:** Add a `scan` step/job after build: `aquasecurity/trivy-action@0.28.0` (or pinned) `image-ref: <local :ci image>`, `severity: CRITICAL`, `ignore-unfixed: true`, `exit-code: 1`, `vuln-type: os,library`. Run per built image (matrix).
- [ ] **Step 4:** Ensure `ci-result` gate requires `scan` success (or skip when no services changed).
- [ ] **Step 5:** `actionlint .github/workflows/ci.yml` → clean. Push to a scratch branch, open a draft PR, confirm the workflow parses and the matrix runs (GitHub-side validation).
- [ ] **Step 6:** Commit.

### Task 2: `security-scan.yml` (deep weekly scan) replaces `security-container-scan.yml`

**Files:**
- Create: `.github/workflows/security-scan.yml`
- Delete: `.github/workflows/security-container-scan.yml`

**Interfaces:**
- Consumes: the 7 build units from `microservice-build-catalog.ps1` naming.
- Produces: weekly + `workflow_dispatch` Trivy scan over all images; informational (non-blocking).

- [ ] **Step 1:** Author `security-scan.yml`: `on: schedule (weekly cron), workflow_dispatch`; matrix over all 7 build units; build each (buildx, no push) and Trivy scan `severity: CRITICAL,HIGH`, `exit-code: 0` (report, don't block); upload SARIF to the Security tab (`github/codeql-action/upload-sarif`).
- [ ] **Step 2:** Add a short note documenting that GCP **Artifact Analysis** auto-scans images on push to Artifact Registry (no workflow needed) as the second layer.
- [ ] **Step 3:** `git rm .github/workflows/security-container-scan.yml`.
- [ ] **Step 4:** `actionlint .github/workflows/security-scan.yml` → clean. Commit.

### Task 3: Fold integration/BOLA/migration onto the pipeline; delete `whole-application-validation.yml`

**Files:**
- Modify: `.github/workflows/ci.yml` (add `integration` job)
- Modify: `.github/workflows/deploy.yml` (post-deploy gateway smoke, env-suffixed)
- Delete: `.github/workflows/whole-application-validation.yml`
- Reference: `scripts/smoke-gateway-routes.ps1`, `scripts/smoke-microservice-features.ps1`, `scripts/verify-microservice-migration.ps1`, `scripts/audit-tenant-isolation.ps1`, `scripts/invoke-production-gateway-smoke.ps1`, `docker-compose.yml`, `docker-compose.bola.yml`

**Interfaces:**
- Produces: `integration` job on `push:main` + `schedule` (nightly) that runs the full docker-compose stack + smokes + BOLA + migration audit (migrated verbatim from the deleted workflow, minus stale project refs).

- [ ] **Step 1:** In `ci.yml` add job `integration` with `if: github.event_name == 'push' || github.event_name == 'schedule'` (add `schedule` cron to `ci.yml` `on:`). Port the `whole-application-runtime-test` job body (compose up, `ensure-app-rt-local.ps1`, `ensure-local-dev-users.ps1`, the 4 smoke/audit scripts, BOLA overlay).
- [ ] **Step 2:** In `deploy.yml`, add/repair a post-deploy step that resolves the **env-suffixed** gateway `custoking-api-gateway-${{ inputs.environment }}` and runs `invoke-production-gateway-smoke.ps1` against it (replaces the deleted workflow's un-suffixed `deployed-gcp-gateway-smoke`).
- [ ] **Step 3:** `git rm .github/workflows/whole-application-validation.yml`.
- [ ] **Step 4:** `actionlint` both workflows → clean. Draft-PR validation that `integration` is skipped on PR and present on push.
- [ ] **Step 5:** Commit.

### Task 4: Repo hygiene — scrub stale legacy-project references, fix `deploy/gcp/README.md`

**Files:**
- Modify: `deploy/gcp/README.md`
- Grep-and-fix: any remaining legacy project ID / legacy project number references

**Interfaces:** Produces: zero stale legacy-project references in active source/config; any retained historical references are explicitly marked.

- [x] **Step 1 (test-first):** Scan for legacy project ID / legacy project number references and record all hits.
- [x] **Step 2:** Confirm `deploy/gcp/README.md` documents the 5 merged services (identity, school-core, operations, platform, billing) + gateway + frontend; correct 17-secret `-${_ENV}` list (incl. `app-rt-password`); source bucket `gs://custoking-github-deploy-source`; project `custoking`.
- [x] **Step 3:** Fix non-historical legacy project hits and remove stale ignored preflight artifacts. Leave only explicitly marked historical docs.
- [x] **Step 4:** Re-run the scan → only intentional historical hits remain. Commit.

---

## Phase 2 — Application instrumentation (tracing + log correlation)

### Task 5: identity-service — OTel tracing + trace-correlated JSON logs (PILOT)

**Files:**
- Modify: `services/identity-service/pom.xml`
- Create/Modify: `services/identity-service/src/main/resources/application.yaml` (or `.properties`)
- Create: `services/identity-service/src/main/resources/logback-spring.xml` (JSON encoder w/ trace fields) if not already JSON
- Test: `services/identity-service/src/test/java/.../observability/TracingAutoConfigTest.java`

**Interfaces:**
- Produces: the **canonical instrumentation recipe** Tasks 6-9 reuse verbatim: (a) `spring-boot-starter-opentelemetry` dependency; (b) OTLP exporter config keyed by env vars `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_TRACES_SAMPLER`, `OTEL_TRACES_SAMPLER_ARG`, `OTEL_SERVICE_NAME`; (c) logback JSON encoder emitting `severity`, `logging.googleapis.com/trace` = `projects/${GCP_PROJECT}/traces/%X{trace_id}`, `logging.googleapis.com/spanId` = `%X{span_id}`.

- [ ] **Step 1 (test-first):** Write `TracingAutoConfigTest` — `@SpringBootTest` (or a slice) asserting the OpenTelemetry `Tracer`/`OpenTelemetry` bean is present and that a started span populates MDC `trace_id`/`span_id`. Run → FAIL (dependency absent).
- [ ] **Step 2:** Add to `pom.xml`: `org.springframework.boot:spring-boot-starter-opentelemetry`. (Confirm it resolves under the SB4.0.7 BOM; if the starter's exporter needs the OTLP module explicitly, add `io.opentelemetry:opentelemetry-exporter-otlp`.)
- [ ] **Step 3:** Config: set `management.tracing.sampling.probability` bound to `${OTEL_TRACES_SAMPLER_ARG:1.0}`, OTLP endpoint `${OTEL_EXPORTER_OTLP_ENDPOINT:}`, service name `${OTEL_SERVICE_NAME:identity-service}`. Add `logback-spring.xml` JSON encoder with the three correlation fields (see Interfaces).
- [ ] **Step 4:** Run `TracingAutoConfigTest` → PASS. Run full `.\mvnw.cmd -f services\identity-service\pom.xml test` → all green (no regression).
- [ ] **Step 5:** Commit.

### Task 6: school-core-service — apply the Task 5 recipe
**Files:** `services/school-core-service/pom.xml`, `.../application.yaml`, `.../logback-spring.xml`, test `.../observability/TracingAutoConfigTest.java`.
- [ ] Step 1: Copy the Task 5 test (service name `school-core-service`). Run → FAIL.
- [ ] Step 2: Apply the exact Task 5 recipe (dependency + config + logback), `OTEL_SERVICE_NAME` default `school-core-service`.
- [ ] Step 3: `TracingAutoConfigTest` → PASS; full `mvn test` → green. Commit.

### Task 7: operations-service — apply the Task 5 recipe
**Files:** `services/operations-service/pom.xml`, config, logback, test.
- [ ] Same 3 steps as Task 6, service name `operations-service`. Commit.

### Task 8: platform-service — apply the Task 5 recipe
**Files:** `services/platform-service/pom.xml`, config, logback, test.
- [ ] Same 3 steps as Task 6, service name `platform-service`. (Platform is the Pub/Sub *consumer* — Task 11 adds context extraction here.) Commit.

### Task 9: billing-service — apply the Task 5 recipe
**Files:** `services/billing-service/pom.xml`, config, logback, test.
- [ ] Same 3 steps as Task 6, service name `billing-service`. Commit.

### Task 10: api-gateway — OTel SDK + trace-correlated JSON logs

**Files:**
- Modify: `services/api-gateway/package.json`
- Create: `services/api-gateway/tracing.js` (OTel bootstrap, required before `server.js`)
- Modify: `services/api-gateway/server.js` (require tracing first; JSON log lines carry `logging.googleapis.com/trace`/`spanId` from active context)
- Test: `services/api-gateway/tracing.test.js` (or extend `server.test.js`)

**Interfaces:**
- Consumes: incoming `traceparent` (already forwarded per gateway design).
- Produces: gateway spans + W3C context propagation to upstreams; JSON logs with trace fields.

- [ ] **Step 1 (test-first):** `node --test` test asserting: tracing module initializes an OTel SDK; a simulated request with a `traceparent` header yields an active span context whose trace id is logged. Run → FAIL.
- [ ] **Step 2:** Add deps: `@opentelemetry/sdk-node`, `@opentelemetry/auto-instrumentations-node`, `@google-cloud/opentelemetry-cloud-trace-exporter`. Create `tracing.js` (start SDK, http auto-instrumentation, Cloud Trace exporter gated on `OTEL_EXPORTER_OTLP_ENDPOINT`/GCP project env; sampler from `OTEL_TRACES_SAMPLER_ARG`). Require it as the very first line of `server.js`.
- [ ] **Step 3:** Update gateway logging to include the active trace/span ids in each JSON log line.
- [ ] **Step 4:** `node --test services/api-gateway/*.test.js` → PASS (existing + new). Commit.

### Task 11: Pub/Sub trace-context propagation (async flow continuity)

**Files:**
- Modify: outbox publishers in `services/school-core-service`, `services/operations-service`, `services/billing-service` (inject W3C context into Pub/Sub message attributes)
- Modify: the projector/consumer path in `services/platform-service` (extract context, continue the trace)
- Test: a per-side unit test asserting attribute inject/extract round-trips the trace id

**Interfaces:**
- Consumes: the active span context at publish time.
- Produces: message attributes `traceparent` (+ `tracestate`) on published events; extracted context on consume → same trace spans the async hop.

- [ ] **Step 1 (test-first):** Publisher test: publishing an event within an active span sets a `traceparent` attribute encoding the current trace id. Consumer test: a message with a `traceparent` attribute produces a child span under that trace. Run → FAIL.
- [ ] **Step 2:** Implement inject in the 3 publishers (OTel `TextMapPropagator` → message attributes) and extract in the platform consumer (attributes → `Context` → span parent).
- [ ] **Step 3:** Run the new tests + full `mvn test` for each touched service → green. Commit (may be per-service commits).

### Task 12: Wire OTel runtime env in `cloudbuild.yaml` (per env)

**Files:**
- Modify: `cloudbuild.yaml` (add OTel env vars to each service + gateway deploy step)

**Interfaces:**
- Produces: at runtime each service/gateway gets `OTEL_EXPORTER_OTLP_ENDPOINT` (Cloud Trace OTLP), `OTEL_SERVICE_NAME=<svc>`, `OTEL_TRACES_SAMPLER=parentbased_traceidratio`, `OTEL_TRACES_SAMPLER_ARG=${_OTEL_SAMPLE}` where `_OTEL_SAMPLE` defaults `1.0` and is set to `0.2` for `_ENV=prod`, plus `GCP_PROJECT=custoking`.

- [ ] **Step 1:** Add `_OTEL_SAMPLE` substitution (default `1.0`); document that `deploy.yml`/`release.yml` pass `0.2` when `environment=prod`.
- [ ] **Step 2:** Add the OTel env vars to all 7 deploy steps (services + gateway). Do NOT change the build/`_SKIP_BUILD`/`selected()` logic.
- [ ] **Step 3 (verify):** `gcloud builds submit --config=cloudbuild.yaml --no-source --dry-run` isn't available; instead validate YAML with `gcloud builds submit --config=cloudbuild.yaml . --substitutions=... ` against a scratch — OR lint the file and rely on the dev deploy. Deploy to **dev** via the pipeline, then confirm a request produces a trace in Cloud Trace (manual verification against dev). Commit.

---

## Phase 3 — Terraform observability module

### Task 13: Scaffold `deploy/gcp/observability/` Terraform

**Files:**
- Create: `deploy/gcp/observability/main.tf`, `variables.tf`, `providers.tf`, `README.md`

**Interfaces:**
- Produces: a Terraform root parameterized by `var.project` (`custoking`), `var.region` (`asia-south2`), `var.env` (`dev`/`prod`), `var.services` (list of the 5 + gateway), consumed by Tasks 14-17.

- [ ] **Step 1 (test-first):** `terraform init && terraform validate` in the module → FAIL/empty initially; establish the harness.
- [ ] **Step 2:** Author `providers.tf` (google provider, no state backend committed with secrets — document GCS backend), `variables.tf`, and `main.tf` with `locals` for env-suffixed names.
- [ ] **Step 3:** `terraform validate` → clean; `terraform plan -var project=custoking -var env=dev` → succeeds (no resources yet or trivial). Commit.

### Task 14: Golden-signal dashboards per service

**Files:** `deploy/gcp/observability/dashboards.tf`
- [ ] Step 1: `google_monitoring_dashboard` (one per service via `for_each`) with tiles for request latency (p50/p95/p99), request count, 5xx rate, instance count, CPU + memory utilization — all from Cloud Run built-in metrics filtered by the env-suffixed service name.
- [ ] Step 2: `terraform validate` + `plan` (dev) clean. Commit.

### Task 15: Uptime checks + core alert policies

**Files:** `deploy/gcp/observability/uptime.tf`, `deploy/gcp/observability/alerts.tf`
- [ ] Step 1: `google_monitoring_uptime_check_config` per service health endpoint; `google_monitoring_alert_policy` for 5xx rate, p95 latency, and max-instance saturation, with a notification channel variable.
- [ ] Step 2: `terraform validate` + `plan` clean. Commit.

### Task 16: Log-based metrics for async health + alerts

**Files:** `deploy/gcp/observability/log_metrics.tf`
- [ ] Step 1: `google_logging_metric` for outbox pending / dead-letter / oldest-pending-age and notification inbox backlog (parse the structured log fields the observability runbook prescribes); alert policies on rising counts.
- [ ] Step 2: `terraform validate` + `plan` clean. Commit.

### Task 17: SLOs + burn-rate alerts

**Files:** `deploy/gcp/observability/slo.tf`
- [ ] Step 1: `google_monitoring_slo` for request availability + latency per critical service; burn-rate `google_monitoring_alert_policy`.
- [ ] Step 2: `terraform validate` + `plan` clean. Commit.

### Task 18: Runbook + observability doc rewrite

**Files:** `docs/MICROSERVICE-OBSERVABILITY-RUNBOOK.md`, `deploy/gcp/observability/README.md`
- [ ] Step 1: Rewrite the runbook: how to live-tail logs (`gcloud logging tail`, Logs Explorer streaming), how to read a request-flow trace in Cloud Trace, links/how-to for the dashboards, and how to apply the Terraform module (`terraform apply -var env=dev|prod`).
- [ ] Step 2: Commit.

---

## Self-Review notes

- **Spec coverage:** every spec item (A1-A6, B1-B4) maps to a task (Phase 1 = A; Phase 2 = B1/B2; Phase 3 = B3/B4). ✓
- **Multi-subsystem:** three independently-shippable phases; a reviewer can accept Phase 1 without Phase 2/3. Consider merging phase-by-phase.
- **Verification honesty:** workflow and Terraform tasks can't be fully unit-tested; their "tests" are `actionlint` / `terraform validate`+`plan` plus a manual dev-deploy trace check (Task 12 Step 3). This is called out, not hidden.
- **Risk:** the only deploy-path edits are Task 3 (post-deploy smoke) and Task 12 (OTel env) — both verified against dev before any prod promotion, per the Global Constraint.
