# Architecture Remediation Program — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:executing-plans (or superpowers:subagent-driven-development) to execute. This is a **program-level plan**: it sequences the whole of `ARCHITECTURE_REVIEW.md` into phase-gated workstreams. Each workstream marked **[EXPAND]** must be turned into its own bite-sized task-by-task plan (one file under `docs/superpowers/plans/`) before its code is written — they are too large to safely inline here. Steps use `- [ ]` for tracking.

**Goal:** Take Custoking IMS from a vulnerable distributed-monolith on EOL Spring Boot to a secure, tenant-isolated, right-sized system that matches 2026 best practice — executed as a sequenced program with hard phase gates.

**Architecture:** Phased remediation. Phase 0 stops the bleeding (EOL upgrade, gateway hardening, cold-start). Phase 1 closes the live cross-tenant data leak (tenant-context + RLS). Phase 2 consolidates 12 nanoservices → ~5. Phase 3 decouples data. Phases 4–6 run CI/CD, frontend, compliance, and optimization in parallel tracks.

**Tech Stack:** Spring Boot (3.4→3.5→4.0), Java (21→25), Node gateway, React/Vite SPA, Cloud Run, Cloud SQL Postgres 15, Pub/Sub, Cloud Build + GitHub Actions, Workload Identity Federation.

**Source of truth:** `ARCHITECTURE_REVIEW.md` (§1–8). Every task below cites its review ID (e.g. `MT-P0-1`).

## Global Constraints

- **Do not break the public `/api/v1/**` contract.** The SPA is unchanged; all routing changes preserve compat controllers and gateway routes.
- **Forward-only Flyway migrations**, per owning service; never edit an applied migration.
- **Production DB is shared Cloud SQL `custoking-db` / `custoking_ims_v1`, Postgres 15, private IP.** Schema-per-service. Apply DDL via the established in-VPC one-off Cloud Run Job pattern (`scripts/audit-legacy-compatibility-cloudsql.ps1`).
- **Every change ships behind the existing audit/verifier gates** (`scripts/verify-microservice-migration.ps1`) which must stay green.
- **No secrets in code or logs.** Secret Manager + WIF only. `db-password` = `appuser`.
- **Java services are Spring Boot, currently 3.4.13 / Java 21** — pin versions consistently across all 12.
- **Test cadence:** unit (`mvn test`/`node --test`/`vitest`) per change; full catalog (`scripts/invoke-microservice-tests.ps1`) per phase gate; staging deploy before production.

## Hard Sequencing Decisions (resolve before coding)

1. **RLS requires reversing part of the single-`appuser` consolidation.** Today runtime **and** migrations both connect as `appuser`, which **owns every schema and is a `cloudsqlsuperuser` member → it bypasses RLS**. Phase 1 MUST re-introduce a **separate unprivileged runtime role `app_rt`** (LOGIN, no ownership, `NOINHERIT`, not a `cloudsqlsuperuser` member) for the application, while `appuser` stays the **migration/owner** role. RLS policies only bite for `app_rt`. This is the single most important structural decision in the program.
2. **Fix tenant isolation BEFORE consolidation.** The BOLA leak (§MT-P0-1) is a *live* student-PII exposure; it cannot wait for a multi-week service merge. Phase 1 enforces tenant scope per-service first; consolidation (Phase 2) then shrinks the surface.
3. **Consolidate BEFORE decoupling reporting.** Merging services (Phase 2) collapses several of reporting's 8 cross-schema sources into 2–3, shrinking the outbox surface (Phase 3).
4. **Spring Boot 3.5 is a 6-month bridge** (OSS EOL 2026-06-30). Do it in Phase 0 for patches; schedule SB 4.0 / Java 25 in Phase 2.

---

## Phase 0 — Stop the Bleeding (low effort, high value, no architecture change)

**Gate:** all 12 services on SB 3.5 green; gateway returns CORS/security headers + rejects oversized/over-rate requests; cold-start visibly reduced in staging.

### Task 0.1 — Upgrade Spring Boot 3.4.13 → 3.5.16 (all 12 services) — `P0-1`
- **Area:** every `services/*/pom.xml` parent version; `docs/PRODUCTION_READINESS_OVERHAUL_PLAN.md`.
- **Deliverable:** parent bumped to 3.5.16; deprecations resolved; stay on Java 21.
- **Acceptance:** `scripts/invoke-microservice-tests.ps1` green (14 entries); staging deploy healthy; no `3.4` references remain.
- **Effort:** Low–Med · **Risk:** Low · **Deps:** none. **[EXPAND if deprecations bite]**

### Task 0.2 — Cloud Run cold-start quick wins — `P1-3`
- **Area:** `cloudbuild.yaml` `common_flags`.
- **Deliverable:** add `--cpu-boost` to all services; `--min-instances=1` on gateway + identity (extend to `school-core` after Phase 2); keep `--max-instances` sized.
- **Acceptance:** cold p95 latency drops in staging; min-instance billing reviewed.
- **Effort:** Low · **Risk:** Low · **Deps:** none.

### Task 0.3 — Gateway security controls — `SEC-P0-2`
- **Area:** `services/api-gateway/server.js` (+ `server.test.js`).
- **Deliverable:** strict CORS allowlist (no `*`+credentials); security headers (HSTS, `X-Content-Type-Options`, `X-Frame-Options`/`frame-ancestors`, CSP for SPA, `Referrer-Policy`); max body size; global IP/token rate limit (token-bucket). Cloud Armor evaluated separately.
- **Acceptance:** `node --test server.test.js` covers: disallowed origin blocked, headers present, oversized body → 413, burst → 429.
- **Effort:** Low–Med · **Risk:** Low · **Deps:** none.

### Task 0.4 — Remove dev-default DB creds; fail fast — `SEC-P2-2`
- **Area:** all `services/*/src/main/resources/application.yml` (`${SPRING_DATASOURCE_PASSWORD:root}` → no default).
- **Deliverable:** boot fails if password env unset (mirror `APP_JWT_SECRET` behavior); local dev sets it via compose.
- **Acceptance:** service refuses to start with unset password; local stack still boots.
- **Effort:** Low · **Risk:** Low · **Deps:** none.

---

## Phase 1 — Close the Tenant-Isolation Hole **[HIGHEST PRIORITY]**

**Gate:** a tenant-A token returns **403/empty** for tenant-B object IDs on **every** list/detail endpoint (automated BOLA suite green), AND RLS blocks cross-tenant rows even on a hand-crafted query as `app_rt`.

### Task 1.1 — Introduce the unprivileged runtime role `app_rt` — `MT-P0-2`/`SEC-P0-3` **[EXPAND]**
- **Area:** new `scripts/create-app-rt-role.sql`; `cloudbuild.yaml` (`_APP_DB_USER=app_rt`, `SPRING_DATASOURCE_PASSWORD=app-rt-password`); new Secret Manager `app-rt-password`. `_FLYWAY_DB_USER` stays `appuser`.
- **Deliverable:** `CREATE ROLE app_rt LOGIN NOINHERIT;` granted USAGE + DML (no DDL, no ownership, not `cloudsqlsuperuser`) on all 12 schemas; default privileges so future `appuser`-created objects grant DML to `app_rt`.
- **Acceptance:** services connect/read/write as `app_rt`; `app_rt` cannot DDL; verified it is **subject to** RLS (owner `appuser` is not).
- **Effort:** Med · **Risk:** Med (reverses the single-user consolidation — update `ARCHITECTURE_REVIEW.md`/runbook) · **Deps:** Phase 0.

### Task 1.2 — `TenantContext` filter: derive tenant from verified JWT, not client — `MT-P0-1`/`SEC-P0-1` **[EXPAND per service group]**
- **Area:** a shared filter in each service (or `common` module): read gateway headers `X-Authenticated-School-Id`/`-Zone-Id`/`-Role`/`-User-Id`, populate a request-scoped `TenantContext`. Repositories take the scope from `TenantContext`, not from `@RequestParam`. Client `schoolId` becomes a *within-scope filter only*, intersected server-side. Superadmin is the only role that may widen scope, via explicit check. Remove every optional `if (schoolId != null)` tenant predicate.
- **Deliverable:** all tenant-scoped queries filtered by authenticated scope; deny-by-default.
- **Acceptance:** new per-service tests: omitting `schoolId` returns only the caller's tenant; passing another tenant's id returns 403/empty; superadmin sees cross-tenant.
- **Effort:** Med (High across 12; shrinks after Phase 2) · **Risk:** Med (must cover every query path — RLS in 1.3 backstops) · **Deps:** 1.1.

### Task 1.3 — Enable RLS with transaction-scoped GUC — `MT-P0-2`/`MT-P1-3` **[EXPAND]**
- **Area:** per-service Flyway migrations adding `ALTER TABLE … ENABLE ROW LEVEL SECURITY` + `CREATE POLICY … USING (school_id = current_setting('app.current_school_id')::bigint) WITH CHECK (…)`; app sets `set_config('app.current_school_id', …, true)` per transaction; PgBouncer transaction-mode (or Cloud SQL connector pooling).
- **Deliverable:** DB-enforced isolation on all tenant-scoped tables; tenant GUC transaction-local.
- **Acceptance:** as `app_rt` with GUC=A, a raw `SELECT * FROM <table>` returns only tenant A; superadmin path uses a bypass policy/role; pooler verified to not leak GUC across requests.
- **Effort:** Med–High · **Risk:** Med (test as `app_rt`, never as owner — owner silently bypasses) · **Deps:** 1.1, 1.2.
- **Status (2026-07-01): DONE (both increments).** Increment 1 (clean `NOT NULL` tables — student/attendance/reporting) — `docs/superpowers/plans/2026-06-30-rls-backstop.md`. Increment 2, RLS extension onto the 10 tenant-key tables 1.4 hardened — `docs/superpowers/plans/2026-06-30-rls-extension.md`, branch `phase1-rls-extension` / **PR #15**: `tenant_isolation` enforced for `app_rt` on catalog_orders, annual_plan_items, firefighting_requests, ff_quotations, workflow_instances, workflow_actions, attendance_daily, fee_assignments, payment_records, command_center_actions (10 tables, 6 services); `TenantAwareDataSource` added to catalog/firefighting/workflow/fee; feed/inbox + fee_bands/fee_items left un-RLS'd by design (gate-verified, no table deferred); per-service `*RlsIntegrationTest` as `app_rt` green (6-service gate). All three 1.4 carry-forwards closed: (1) attendance `school_id` fail-loud (`requireSectionSchool`); (2) orphan/mis-scope pre-check in the RLS runbook §10.3; (3) ff/wf Flyway-driven backfill tests + the four datasource copies. Remaining open from the parent 1.3 spec: PgBouncer transaction-mode pooling = Task 3.3 (GUC already safe via set-on-borrow).

### Task 1.4 — NOT NULL tenant keys + tenant-leading composite indexes — `MT-P1-1`
- **Area:** forward Flyway migrations across services.
- **Deliverable:** backfill the 16 nullable `school_id` columns from their owning rows; `SET NOT NULL`; ensure every tenant-scoped index/PK leads with `school_id` (`(school_id, …)`).
- **Acceptance:** no nullable tenant columns remain on scoped tables; `EXPLAIN` shows index use on tenant-filtered queries (RLS perf: ~0.3ms vs ~120ms).
- **Effort:** Med · **Risk:** Med (online backfill on live tables) · **Deps:** 1.3.
- **Status (2026-06-30):** done — `phase1-tenant-keys` / PR #14 (`docs/superpowers/plans/2026-06-30-tenant-keys.md`). `school_id` now `NOT NULL` + tenant-leading-indexed on the 10 in-scope tables across catalog/reporting/firefighting/workflow/attendance/fee (2 via cross-schema backfill). Runbook: `docs/MICROSERVICE-TENANT-KEY-ROLLOUT-RUNBOOK.md`. RLS extension to these tables tracked under 1.3 Follow-up.

### Task 1.5 — BOLA regression suite in CI — `MT` CI gate / `SEC-P3-1`(partial)
- **Area:** `scripts/smoke-*` + `.github/workflows/ci.yml`; new `scripts/audit-tenant-isolation.ps1` wired into `verify-microservice-migration.ps1`.
- **Deliverable:** automated test that, with a tenant-A token, asserts 403/empty for tenant-B ids on every list/detail endpoint.
- **Acceptance:** suite fails if any endpoint leaks; gate is required for merge.
- **Effort:** Med · **Risk:** Low · **Deps:** 1.2, 1.3.
- **Status (2026-07-01): done** — `phase1-bola` / `docs/superpowers/plans/2026-07-01-bola-suite.md`. `scripts/audit-tenant-isolation.ps1` implements a two-school seeded fixture, positive baseline, and 13 cross-tenant probes (detail-by-id + list-with-param) across students, catalog orders, firefighting requests, and workflow instances. Gate is wired into `verify-microservice-migration.ps1` via `-RunBolaAudit` and runs in `whole-application-validation.yml` CI (gateway in enforce mode via `docker-compose.bola.yml`). **Gate found NO leaks across all covered endpoints.** The **Phase 1 gate is now met**: BOLA suite green (Task 1.5) AND RLS blocks cross-tenant queries on all NOT NULL `school_id` tables (Task 1.3 + 1.4 extension, branch `phase1-rls-extension`).

### Task 1.6 — Refresh-token rotation + reuse detection — `SEC-P1-1` **[EXPAND]**
- **Area:** identity-service: refresh-token store (family id), rotate-on-use, reuse → invalidate family; keep access ~15 min.
- **Acceptance:** reusing an old refresh token invalidates the family and forces re-login; tests cover rotate + reuse-detect.
- **Effort:** Med · **Risk:** Low–Med · **Deps:** Phase 0.

### Task 1.7 — Input validation: DTOs + `@Valid` (rolling) — `SEC-P1-2`
- **Area:** replace `Map<String,Object>` bodies with validated DTOs across services (start with write endpoints).
- **Acceptance:** invalid payloads → 400 with field errors; controller tests assert validation.
- **Effort:** Med · **Risk:** Low · **Deps:** none (parallelizable). **[EXPAND per service]**

---

## Phase 2 — Architecture Consolidation

**Gate:** deployable count 14 → ~8; cross-schema reads reduced to reporting-only; `/api/v1/**` contract unchanged; all gates green.

### Task 2.1 — Topology ADR + freeze baseline-shrink busywork — `P0-3`
- **Deliverable:** an ADR fixing the target (recommended: `school-core`, `commerce`, `operations`, + keep identity/notification/reporting/audit/gateway). Stop per-query baseline shrinking.
- **Acceptance:** ADR merged; team aligned.
- **Effort:** Low · **Risk:** Low.

### Task 2.2 — Consolidate 12 → ~5 domain services — `P1-1` **[EXPAND — one plan per merge]**
- **Merges:** `school-core` ← tenant-school+student+attendance; `commerce` ← fee+catalog+billing+workflow; `operations` ← firefighting. Keep identity, notification, reporting, audit, gateway, frontend.
- **Deliverable:** module-moves (schemas already separate → mostly code relocation + in-process calls replacing HTTP/cross-schema reads); gateway routes + compat controllers updated.
- **Acceptance:** `/api/v1/**` contract intact; cross-schema baseline drops to reporting-only; tests green per merged service.
- **Effort:** High · **Risk:** Med · **Deps:** 2.1, Phase 1 (tenant enforcement carried into merged services).

### Task 2.3 — Gateway local JWT verification (drop per-request introspection) — `P1-4` **[EXPAND]**
- **Area:** `server.js`: verify JWT against identity JWKS (cached); reserve introspection for revocation; cache permissions briefly.
- **Acceptance:** protected routes succeed without per-request identity call; revoked tokens fail within TTL; `server.test.js` updated.
- **Effort:** Med · **Risk:** Med · **Deps:** 2.1.

### Task 2.4 — Java 21 → 25 + Spring Boot 3.5 → 4.0 spike — `P1-5`/`P0-2` **[EXPAND]**
- **Deliverable:** build/runtime on Java 25; SB 4.0 (Framework 7) upgrade branch validated on one service, then rolled out.
- **Acceptance:** all services green on Java 25 / SB 4.0; native-friendly where possible.
- **Effort:** Med · **Risk:** Med · **Deps:** 2.2 (fewer artifacts to migrate).

---

## Phase 3 — Data Decoupling & Database

**Gate:** reporting owns its read models via events (no cross-schema SQL); attendance partitioned; pool pressure resolved.

### Task 3.1 — Reporting outbox + Pub/Sub projections — `P1-2` **[EXPAND]**
- **Area:** transactional outbox tables in owning services (now ~3 after Phase 2); relay → Pub/Sub (push receivers already exist in reporting); reporting builds read models. No Kafka/Debezium at this scale.
- **Acceptance:** reporting issues zero cross-schema SQL; dashboards eventually-consistent; `RUNTIME_SCHEMA_DEPENDENCY_BASELINE.json` reporting deps removed.
- **Effort:** High · **Risk:** Med · **Deps:** Phase 2.

### Task 3.2 — Indexing, partition attendance, drop dead objects — `P2-2`
- **Deliverable:** covering indexes on hot read paths; **partition `attendance.attendance_daily` by date**; drop monolith-era dead tables/columns after verification; add missing intra-schema constraints.
- **Acceptance:** `EXPLAIN` improvements on hot queries; partition migration verified; no orphan objects.
- **Effort:** Med · **Risk:** Low–Med · **Deps:** 1.4 (composite indexes).

### Task 3.3 — Connection pooling finalize — `P2-1`/`MT-P1-3`
- **Deliverable:** PgBouncer transaction-mode (or Cloud SQL connector pooling) sized for fewer services; de-dupe pool config (app yml vs cloudbuild env).
- **Acceptance:** peak connections well under instance limit; no GUC leak across requests.
- **Effort:** Low–Med · **Risk:** Med · **Deps:** 1.3, Phase 2.

---

## Phase 4 — CI/CD & Supply Chain (parallel track, start in Phase 0)

### Task 4.1 — Parallelize + cache Cloud Build; build only changed on deploy — `P1-6`
- **Acceptance:** "deploy all" build time cut materially; changed-only deploys; layer cache hits.
- **Effort:** Med · **Risk:** Low.

### Task 4.2 — Collapse 15 deploy workflows → 1 reusable matrix — `P2-4`
- **Acceptance:** one `workflow_call` matrix; per-service files removed; deploys unchanged.
- **Effort:** Low · **Risk:** Low.

### Task 4.3 — SBOM + image CVE scan (all services) + CI security gates — `P2-6`/`SEC-P1-4`
- **Deliverable:** Syft SBOM; Trivy/Grype on every image (not just gateway); SHA-pin actions; Gitleaks required gate; **Spring-Boot-supported-version gate** (fails on EOL drift); dependency-CVE gate; Maven + Docker layer cache.
- **Acceptance:** EOL/CVE drift fails CI; SBOM attached to releases.
- **Effort:** Low–Med · **Risk:** Low.

### Task 4.4 — Distributed tracing (OpenTelemetry → Cloud Trace) — `P2-5`
- **Acceptance:** end-to-end traces across gateway + services using existing `traceparent`.
- **Effort:** Med · **Risk:** Low.

---

## Phase 5 — Frontend, Compliance & Tenant Experience (parallel track)

### Task 5.1 — React 18→19, Vite 6→7/8, refresh deps — `P2-3` **[EXPAND]**
- **Acceptance:** `npm run build` + `vitest` green on React 19 / Vite 7+; codemods applied.
- **Effort:** Med · **Risk:** Low–Med.

### Task 5.2 — PII/Aadhaar encryption + DPDP/FERPA compliance — `SEC-P1-3` **[EXPAND]**
- **Deliverable:** verify/implement field-level encryption for Aadhaar (keys in Secret Manager/KMS, not env); confirm encryption at rest (Cloud SQL CMEK) + enforced TLS; data-retention/erasure; **verifiable parental consent** (under-18 = child); data-minimization.
- **Acceptance:** sensitive fields encrypted at column level; retention/erasure jobs exist; consent recorded; compliance checklist signed.
- **Effort:** Med–High · **Risk:** Med (legal) · **Deps:** Phase 1.

### Task 5.3 — Tenant-aware audit + log redaction — `SEC-P2-1`/`A09`
- **Deliverable:** every audit/log event carries `tenant_id`+actor+action+outcome, append-only, **no secrets/PII** (mask Aadhaar/tokens); audit queries tenant-scoped.
- **Acceptance:** redaction tests; audit rows tenant-scoped.
- **Effort:** Low–Med · **Risk:** Low · **Deps:** Phase 1.

### Task 5.4 — Tenant onboarding/provisioning + tenant-scoped config — `MT-P2-1`/`MT-P1-2`
- **Deliverable:** tenant registry lifecycle (`provisioning→active→suspended→offboarding`); provisioning flow; unified tenant config (branding, feature flags, plan limits); suspended tenants hard-stopped at gateway.
- **Acceptance:** onboarding script provisions a tenant end-to-end; suspended tenant blocked.
- **Effort:** Med · **Risk:** Low · **Deps:** Phase 1.

### Task 5.5 — Per-tenant rate limiting / noisy-neighbor — `MT-P2-2`
- **Deliverable:** gateway token-bucket keyed by `school_id`; caps on expensive endpoints (imports, reports).
- **Acceptance:** one tenant's burst doesn't starve others; per-tenant 429s.
- **Effort:** Med · **Risk:** Low · **Deps:** 0.3 (gateway rate-limit base), Phase 1.

---

## Phase 6 — Optimization & Hardening (P3, after the above)

| Task | Review ID | Effort | Risk |
|---|---|---|---|
| 6.1 GraalVM native / CRaC for hot services | `P3-1` | High | Med |
| 6.2 Prune firefighting/workflow; unify billing+fee | `P3-2` | Low–Med | Low |
| 6.3 Archive migration docs/scripts; remove `artifacts/` | `P3-3` | Low | Low |
| 6.4 Cost sweep (memory right-size, AR cleanup, min-instances) | `P3-4` | Low | Low |
| 6.5 Threat model + BOLA pen-test + Cloud Armor WAF | `SEC-P3-1` | Med | Low |
| 6.6 Backend re-validates tenant; rotate internal tokens | `SEC-P2-3` | Med | Low |
| 6.7 Optional DB-per-tenant tier (defer until demanded) | `MT-P3-1` | High | Med |

---

## Dependency Graph (critical path)

```
Phase 0 (0.1 SB3.5, 0.2 cold-start, 0.3 gateway-sec, 0.4 creds)
        │
        ▼
Phase 1: 1.1 app_rt ─► 1.2 TenantContext ─► 1.3 RLS+GUC ─► 1.4 NOT NULL/indexes ─► 1.5 BOLA gate
                        1.6 refresh-rotation (parallel)   1.7 DTO validation (rolling)
        │  (live leak closed)
        ▼
Phase 2: 2.1 ADR ─► 2.2 consolidate ─► 2.3 gateway JWT ─► 2.4 Java25/SB4
        │
        ▼
Phase 3: 3.1 reporting outbox ─► 3.2 DB/partition ─► 3.3 pooling
Phase 4 (CI/CD) and Phase 5 (frontend/compliance/tenant-UX) run PARALLEL from Phase 0/1 onward.
Phase 6 last.
```

---

## Self-Review — Coverage of `ARCHITECTURE_REVIEW.md`

| Review ID | Covered by |
|---|---|
| P0-1, P0-2, P0-3 | 0.1, 2.4, 2.1 |
| P1-1..P1-6 | 2.2, 3.1, 0.2, 2.3, 2.4, 4.1 |
| P2-1..P2-6 | 3.3, 3.2, 5.1, 4.2, 4.4, 4.3 |
| P3-1..P3-4 | 6.1, 6.2, 6.3, 6.4 |
| MT-P0-1/2, MT-P1-1/2/3, MT-P2-1/2, MT-P3-1 | 1.2, 1.3/1.1, 1.4, 5.4, 1.3/3.3, 5.4, 5.5, 6.7 |
| SEC-P0-1/2, SEC-P1-1/2/3/4, SEC-P2-1/2/3, SEC-P3-1 | 1.2, 0.3, 1.6, 1.7, 5.2, 4.3, 5.3, 0.4, 6.6, 6.5 |

**Every** review recommendation maps to a task. No orphan recommendations.

## Risk Register (top 5)

1. **RLS bypass via owner role** → enforce runtime as `app_rt`; CI test that runs as `app_rt`, never owner.
2. **Missed query path in TenantContext** → RLS backstop (1.3) + BOLA suite (1.5) catch leaks app-layer misses.
3. **Online NOT NULL/partition migrations on live tables** → do in low-traffic windows, batched backfill, tested on staging clone.
4. **Consolidation breaking `/api/v1` contract** → compat controllers + gateway route tests; staged per-merge.
5. **Java 25 / SB 4.0 dependency breakage** → spike on one service first; keep 3.5 fallback branch.

---

## Execution Order (TL;DR)

**Now:** Phase 0 (days) → **Phase 1 (the priority — closes the live student-data leak)** → Phase 2 (consolidation) → Phase 3 (data). Run **Phase 4 (CI/CD)** and **Phase 5 (frontend/compliance/tenant-UX)** as parallel tracks. **Phase 6** last. Expand each **[EXPAND]** workstream into its own task-by-task plan before writing its code.
