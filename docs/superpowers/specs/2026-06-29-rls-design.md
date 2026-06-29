# Design — Row-Level Security backstop with a transaction GUC (Phase 1, Task 1.3)

> Review IDs: `MT-P0-2` / `MT-P1-3`. Source of truth: `ARCHITECTURE_REVIEW.md` §7.3; `docs/tenant-isolation.md`.
> Parent program plan: `docs/superpowers/plans/2026-06-28-architecture-remediation-program.md`.
> Depends on: Task 1.1 `app_rt` (unprivileged, `NOBYPASSRLS`, DML grants — PR #11) and Task 1.2 `TenantContext` (PR #12). This branch is stacked on both.
> Status: **approved design** → next step is the bite-sized implementation plan.

## Problem

Tenant isolation today is **application-level only** (Task 1.2's `TenantContext`/`resolveSchoolId`).
A missed query path, a future endpoint, or a raw SQL bug can still leak across tenants.
PostgreSQL Row-Level Security was disabled in the monolith (migration `V117`) and was never
re-enabled for the microservices. This task adds RLS as the **database-enforced backstop**:
even a hand-crafted query as the runtime role returns only the caller's tenant rows.

RLS only bites for a role that is **not** the table owner and **not** a superuser. Task 1.1
established exactly that role: `app_rt` (runtime, `NOBYPASSRLS`, not a `cloudsqlsuperuser`
member, DML-only). `appuser` remains the schema owner / Flyway role and bypasses RLS.

## Goals / Non-goals

**Goals**
- Enable RLS + a tenant-isolation policy on the **cleanly-scoped** (`NOT NULL school_id`,
  user-request-driven) tenant tables, enforced for `app_rt`.
- Inject a per-request tenant GUC (`app.current_school_id`) so policies resolve to the
  authenticated school; superadmin bypasses via a second GUC.
- Prove isolation with **Testcontainers integration tests running as `app_rt`** (never as
  the owner — testing as owner falsely passes).
- A safe prod rollout (no window where RLS is on but instances don't set the GUC).

**Non-goals (deferred / out of scope)**
- NULLABLE / FK-derived / cross-schema tables, and **fee-service** (which has *no* direct
  `school_id` column) — deferred until **Task 1.4** denormalizes `school_id` (backfill +
  NOT NULL + tenant-leading indexes). App-layer (1.2) covers these until then.
- Platform services (`identity, tenant_school, audit, billing, notification`) — not
  per-school RLS targets (tenant_school is read cross-service; billing keys on `branch_id`;
  identity users aren't school-scoped). Excluded.
- PgBouncer / transaction-mode pooling — Task 3.3. Not required for GUC safety here (set
  on every connection borrow, see below).
- Performance partitioning/indexing — Task 3.2.

## Decisions (resolved during brainstorming)

1. **GUC injection: session GUC set on connection-borrow** via a per-service
   `TenantAwareDataSource`. Repositories run reads in **autocommit** (only writes are
   `@Transactional`), so a transaction-local `set_config(...,true)` would not cover reads.
   Setting the GUC on every borrow covers autocommit reads and transactional writes
   uniformly, with no repo/controller changes. Mirrors the monolith's `TenantDataSourceConfig`.
2. **Bypass model: GUC bypass flag for superadmin; explicit school for system ops.**
   Policies allow when `school_id` matches the GUC **or** `app.bypass_rls = 'on'`. The
   datasource sets `bypass_rls=on` only for a gateway-verified superadmin. Trusted
   system/projection paths must set the GUC to the specific school they act on (no blanket
   "bypass when no user context" — that is the fail-open pattern rejected in 1.2). Single
   runtime role (`app_rt`); the owner (`appuser`) bypasses RLS for Flyway/seed.
3. **Scope: pilot `student`, then the other clean NOT-NULL `school_id` tables.** Build the
   mechanism + Testcontainers harness on student-service, prove it, then roll out to
   attendance + reporting clean tables. Defer the rest to post-1.4.

## In-scope tables (this increment)

All have a `NOT NULL school_id` and are written by user-request paths (not background
projections):

| Service | Schema | Tables | Latest Flyway → new |
|---|---|---|---|
| student | `student` | `students`, `student_review_campaigns`, `student_review_items` | V3 → `V4__enable_rls.sql` |
| attendance | `attendance` | `attendance_student_records` | V2 → `V3__enable_rls.sql` |
| reporting | `reporting` | `academic_events`, `event_student_contributions` | V5 → `V6__enable_rls.sql` |

(`student_review_items` and `event_student_contributions` also carry a FK to their parent,
but they have their own `NOT NULL school_id`, so the policy is the simple column form — no
subquery.)

## Components (copied per in-scope service — no shared module)

Package `com.custoking.ims.<service>.security` (alongside `TenantContext`).

### 1. `TenantAwareDataSource` + `TenantDataSourceConfig`

- `TenantAwareDataSource extends org.springframework.jdbc.datasource.DelegatingDataSource`.
  Override `getConnection()` (both overloads): obtain the real connection from the Hikari
  delegate, then **apply the GUCs from `TenantContext`** before returning it:
  - If `TenantContext.get().schoolId() != null`:
    `SET app.current_school_id = '<schoolId>'` and
    `SET app.bypass_rls = '<on if isSuperAdmin() else off>'`.
  - Else (empty context — superadmin with no school still has `isSuperAdmin()` true so is
    handled by the first branch; a truly empty/system context): `RESET app.current_school_id`
    and set `app.bypass_rls = '<on if isSuperAdmin() else off>'` (a superadmin with no school
    gets `bypass_rls=on` → sees all; a non-superadmin empty context resets both → RLS denies).
  - Values are written with `java.sql.Statement.execute`, the id via a parameterised
    `set_config('app.current_school_id', ?, false)` to avoid any injection (school id is a
    Long, but use `set_config` for hygiene).
  - Set-on-every-borrow (overwriting) means a pooled connection never carries a stale value
    into a later borrow; no separate reset-on-return needed.
- `TenantDataSourceConfig` (`@Configuration`): define the application `DataSource` `@Bean` as
  a `TenantAwareDataSource` wrapping the Spring-Boot-auto-configured Hikari `DataSource`
  (build the Hikari pool from `DataSourceProperties`/`spring.datasource.*`, wrap it). Flyway
  must keep using the **owner** credentials (`FLYWAY_*` → `appuser`) — Flyway's datasource is
  separate and unaffected, so migrations run as owner and bypass RLS.

Exact GUC contract (used by policies and tests):
- `app.current_school_id` — text of the caller's school id, or unset.
- `app.bypass_rls` — `'on'` for verified superadmin, else `'off'`/unset.

### 2. Flyway migration `V<n>__enable_rls.sql` (run as owner)

Per in-scope table:
```sql
ALTER TABLE <schema>.<table> ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON <schema>.<table>;
CREATE POLICY tenant_isolation ON <schema>.<table>
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');
```
- `ENABLE` (not `FORCE`) ROW LEVEL SECURITY: the table **owner** (`appuser`) bypasses, so
  Flyway data migrations and seed scripts keep working; `app_rt` (non-owner) is subject.
- `current_setting(..., true)` → returns NULL (no error) when the GUC is unset; `nullif(…,'')`
  guards the empty string; an unset/empty school id makes the first predicate false → only the
  bypass branch can match → unset non-superadmin context sees nothing (deny-by-default).
- `WITH CHECK` mirrors `USING` so a cross-tenant `INSERT`/`UPDATE` is rejected too.
- Forward-only; continues the service's own Flyway sequence.

### 3. Testcontainers RLS integration test (new infra)

New test-scoped Maven deps (`org.testcontainers:postgresql`, `:junit-jupiter`) in each
in-scope service's `pom.xml`. A copied `AbstractRlsIntegrationTest` base (per service):
1. Start `postgres:16` (Testcontainers); point Flyway at it as the **owner/superuser** and
   run the service's migrations (creating the schema + the RLS policy).
2. Create the unprivileged `app_rt` role + grants (reuse the `create-app-rt-role.sql` logic
   inline, or a minimal grant for the service's schema) — `app_rt` must be `NOBYPASSRLS`,
   not own the tables.
3. Seed rows for school A and school B as the owner.
4. Open a **separate connection as `app_rt`** and assert:
   - `SET app.current_school_id='A'` → `SELECT` returns only A's rows.
   - `='B'` → only B's rows.
   - `SET app.bypass_rls='on'` → all rows.
   - no GUC set → **0 rows**.
   - `WITH CHECK`: as `app_rt` with GUC='A', `INSERT … school_id=B` → fails
     (`new row violates row-level security policy`).
   - same isolation holds for `UPDATE`/`DELETE`.
   Tests MUST run as `app_rt`; a connection as the owner would bypass RLS and falsely pass.

## Data flow

```
request → TenantContextFilter sets TenantContext (school, isSuperAdmin)  [from Task 1.2]
        → repository query (JdbcClient, autocommit read or @Transactional write)
        → TenantAwareDataSource.getConnection(): SET app.current_school_id / app.bypass_rls from context
        → Postgres evaluates the policy for app_rt → returns only in-scope rows
        → connection returned to pool (next borrow overwrites the GUC)
superadmin → bypass_rls=on → policy passes (app-layer 1.2 still scopes the actual filter)
Flyway / seed (appuser, owner) → bypasses RLS (ENABLE not FORCE)
app_rt op with empty/system context on an RLS table → GUC unset → 0 rows (safe-closed)
```

## Rollout (prod, two-phase)

Enabling RLS while a running instance does not set the GUC would break that instance
(its `app_rt` queries return 0 rows). Therefore:
1. **Release 1** — deploy the `TenantAwareDataSource` (GUC-setting) to each in-scope service.
   No policy exists yet; the `SET`s are harmless no-ops.
2. **Release 2** — add the `V<n>__enable_rls.sql` migration. By the time it runs, every live
   instance already sets the GUC.

Documented in a short runbook (`docs/MICROSERVICE-RLS-ROLLOUT-RUNBOOK.md`). Local/Testcontainers
is atomic (single bring-up) so the two-phase split is a prod-only concern. Rollback: a forward
migration `DROP POLICY … ; ALTER TABLE … DISABLE ROW LEVEL SECURITY;` (RLS off restores prior
behavior; `app_rt` already has DML).

## Testing strategy

- **New: Testcontainers RLS integration tests** per in-scope service, run as `app_rt`
  (the isolation proof). Added to the test catalog (`scripts/microservice-test-catalog.ps1`)
  and CI. These need Docker available in CI (the CI runtime already uses docker-compose).
- **Existing Mockito unit tests** unaffected (no real DB) — stay green.
- **Local**: `docker compose --profile full up` + the boundary verifier; optionally a manual
  `psql` check as `app_rt` against the compose DB (the `app_rt` audit from 1.1 already runs).
- Unit test for `TenantAwareDataSource` GUC-string building where practical (the SQL emitted
  for school / superadmin / empty contexts).

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Tests pass as owner (false negative) | Integration tests connect as `app_rt`, asserted explicitly; a deliberate "owner sees all" sanity case documents the distinction |
| Deploy window: RLS on before instances set GUC | Two-phase rollout (datasource first, migration second) |
| GUC leak across pooled requests | Set on every connection borrow (overwrite); empty context resets → deny |
| A clean table also written by a system/projection path (no context) | None in this increment's scope; such a path must set an explicit tenant context before writing, or that table is deferred |
| `set app.current_school_id` round-trip per autocommit read | Accepted (matches the monolith); a single cheap `SET` per borrow |
| fee / derived / cross-schema tables have no DB backstop yet | App-layer (1.2) remains the control; RLS reaches them after Task 1.4 denormalizes `school_id` |
| Flyway can't seed across tenants once RLS is on | `ENABLE` (not `FORCE`) → owner (`appuser`) bypasses; Flyway uses owner creds |

## Open items (deferred, not blocking this task)

- Task 1.4: backfill + `NOT NULL` + denormalized `school_id` on fee/derived/cross-schema
  tables → then extend RLS to them (and to NULLABLE tables with explicit NULL semantics).
- Task 1.5: the automated BOLA CI suite (asserts 403/empty per endpoint) — complements the
  RLS DB proof.
- Task 3.3: PgBouncer transaction-mode (GUC already safe via set-on-borrow).
