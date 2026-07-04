# Connection Pooling Finalize — Design (Phase 3, Task 3.3 right-sized)

**Source task:** `docs/superpowers/plans/2026-06-28-architecture-remediation-program.md` § Phase 3, Task 3.3 (`P2-1`/`MT-P1-3`) — "PgBouncer transaction-mode (or Cloud SQL connector pooling) sized for fewer services; de-dupe pool config." **Right-sized:** a pooler is premature at current scale; the real, valuable work is certifying the RLS-GUC-under-pooling safety and de-duplicating the pool config, with the pooler + GUC-hardening deferred as documented prerequisites.

## Findings that shape this task (measured/read 2026-07-04)

- **RLS tenant isolation is session-level.** `TenantAwareDataSource.getConnection()` (identical in school-core / operations / platform) runs `SELECT set_config('app.current_school_id', ?, false), set_config('app.bypass_rls', ?, false)` — the `false` = **session-level** — on **every** connection borrow, overwriting the previous value; it fails closed (empty context → `''` → RLS returns 0 rows) and closes the connection if the set fails.
- **This is safe with direct HikariCP** (current runtime): every logical borrow re-sets the GUC to the current request's tenant before any query, so a reused physical connection always carries the borrower's tenant. The Task 1.5 BOLA suite + per-service `*RlsIntegrationTest` already prove cross-tenant isolation holds.
- **It is NOT transaction-mode-pooler-ready.** PgBouncer transaction mode multiplexes physical backends per transaction; a session-level GUC set at Hikari-borrow time is not bound to the query's transaction and would be unsafe/incorrect. Transaction-mode pooling requires the GUC to be transaction-local (`SET LOCAL` / `set_config(..., true)`) set inside each transaction — a non-trivial change (the autocommit read path is why session-level was chosen).
- **Pool config is duplicated with conflicting values.** Each service's `application.yml` has `spring.datasource.hikari.maximum-pool-size: ${DB_POOL_MAX:5}` and `minimum-idle: ${DB_POOL_MIN:1}`, AND `cloudbuild.yaml` `common_env` sets `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5,SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=0`, AND `docker-compose.yml` sets `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` (3 or 5, per service). Two mechanisms for one setting; the `SPRING_DATASOURCE_HIKARI_*` env wins via Spring relaxed binding, so **effective prod values are max=5, min-idle=0**.
- **Scale reality:** peak ≈ 5 domain services × Hikari max 5 × ≤2 `max-instances` ≈ **~50** connections, plus identity/gateway (min-instances 1) and transient per-schema Flyway migration pools — all well under `max_connections=200` (already bumped in Phase 2). **A pooler is not needed** for connection pressure.

## Decisions (locked during brainstorming)

1. **Right-size:** certify the no-GUC-leak property with a test + de-duplicate pool config to one source + document headroom/threshold/prerequisite. **Defer** PgBouncer/Cloud SQL connector pooling (premature) and the GUC transaction-local conversion (only needed with a pooler; risky to the autocommit path).
2. **Single source of truth for pool config = the `application.yml` placeholders** (`${DB_POOL_MAX}`/`${DB_POOL_MIN}`); remove the `SPRING_DATASOURCE_HIKARI_*` env from `cloudbuild.yaml` + `docker-compose.yml`.
3. **Behavior-preserving:** reconcile to the current effective prod values (max=5, min-idle=0) so nothing changes at runtime.

## Components

### Component 1 — Certify "no GUC leak across requests" (integration test)
- Add a focused Testcontainers integration test in **school-core-service** (richest RLS: 5 schemas), alongside the existing `*RlsIntegrationTest`, that pins the acceptance property:
  - Configure Hikari `maximum-pool-size=1` for the test so both borrows reuse the **same physical connection**.
  - As the unprivileged runtime role (`app_rt`, subject to RLS), via the real `TenantAwareDataSource`: set `TenantContext` = school A, borrow + read a tenant-scoped table → sees A's row(s); then set `TenantContext` = school B, borrow (same physical connection) + read → sees **only** B's row(s), **not** A's.
  - Empty/no-tenant `TenantContext` → borrow + read → **0 rows** (fail-closed).
- Expected: **no production code change** — the test certifies the current session-level-set-on-every-borrow mechanism is leak-safe under connection reuse. If (unexpectedly) it reveals a leak, that is a Critical finding to escalate, not to paper over.

### Component 2 — De-duplicate pool config to one source
- **Keep** the `application.yml` Hikari placeholders as the single mechanism; change the min-idle default to preserve current prod behavior:
  - `maximum-pool-size: ${DB_POOL_MAX:5}` (unchanged) and `minimum-idle: ${DB_POOL_MIN:0}` (was `:1` → `:0`, matching prod's effective min-idle=0), in all 5 service `application.yml`.
- **Remove** `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` and `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE` from `cloudbuild.yaml` `common_env` and from every `docker-compose.yml` service block.
- Prod/compose may still override via `DB_POOL_MAX`/`DB_POOL_MIN` env if a service needs a different size (none currently does at this scale). Reconcile the compose values (some were 3): standardize on the yml default (5) unless a service documents a reason — set `DB_POOL_MAX` explicitly in compose only where a non-default is intended.
- **Out of scope:** the per-schema Flyway migration pools in `*FlywayConfig.java` (max 3 / min-idle 0) — those are a separate, already-right-sized concern from Phase 2; do not touch.

### Component 3 — Document headroom, threshold, and the pooler prerequisite
- Extend `docs/DB-SCALING-THRESHOLDS.md` (created in Task 3.2) with a **"Connection pooling"** section:
  - Current peak-connection math (~50 vs `max_connections=200`, ≈25% utilization) and where the single-source pool config now lives (the yml placeholders + `DB_POOL_MAX`/`DB_POOL_MIN`).
  - Pooler threshold: introduce a pooler when peak approaches ~150/200 (e.g., as `max-instances` or per-service pool sizes grow).
  - **Prerequisite for transaction-mode pooling:** PgBouncer/Cloud SQL connector transaction-mode requires converting the RLS GUC in `TenantAwareDataSource` from session-level (`set_config(..., false)`) to transaction-local (`SET LOCAL` / `set_config(..., true)`) set inside each transaction, and reworking the autocommit read path accordingly — reference the file and the reason.

## Testing / verification

- **Component 1:** the new integration test is the verification (real Postgres via Testcontainers, `app_rt` role, pool size 1). The owning service's full suite must stay green at its baseline count + the new test.
- **Component 2:** each touched service's suite stays green (the yml change is picked up at boot); the removal from cloudbuild/compose is validated by `docker compose config -q` parsing and by a post-merge redeploy of one representative service (school-core) confirming it boots healthy with the reconciled pool config. The rest apply on their next natural deploy (behavior-preserving — same effective values).
- **Component 3:** docs-only.

## Rollback / risk

- Component 1 adds a test only — no runtime risk.
- Component 2 is behavior-preserving (effective values unchanged: max=5, min-idle=0). If the min-idle default reconciliation were wrong, it surfaces at boot/CI; per-service deploy/rollback applies. The change is caught by CI before merge.
- No pooler introduced, no GUC semantics changed — the risky operations are explicitly deferred.

## Non-goals (YAGNI)

Introducing PgBouncer or Cloud SQL connector transaction-mode pooling now; converting the RLS GUC to transaction-local now; changing the per-schema Flyway migration pools; Task 3.1 (reporting outbox); any change to `max_connections` (stays 200).
