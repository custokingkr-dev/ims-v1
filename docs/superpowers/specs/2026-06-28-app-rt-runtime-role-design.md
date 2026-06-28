# Design — Unprivileged Runtime Role `app_rt` (Phase 1, Task 1.1)

> Review IDs: `MT-P0-2` / `SEC-P0-3`. Source of truth: `ARCHITECTURE_REVIEW.md`.
> Parent program plan: `docs/superpowers/plans/2026-06-28-architecture-remediation-program.md`.
> Status: **approved design** → next step is the bite-sized implementation plan.

## Problem

Today both the application runtime **and** Flyway connect to the shared Cloud SQL
database `custoking_ims_v1` as a single role, `appuser`. `appuser`:

- **owns** all 12 service schemas (so it has implicit DDL + full DML), and
- is a member of `cloudsqlsuperuser` (a Cloud SQL default that **cannot** be revoked —
  no role holds `ADMIN OPTION` on it). It is currently `NOINHERIT` / `NOCREATEROLE` /
  `NOCREATEDB`, but membership + ownership still mean it **bypasses Row-Level Security**.

Postgres RLS is **never enforced for a table's owner or for superusers**. Therefore RLS
(Phase 1, Task 1.3) is structurally impossible while the runtime connects as `appuser`.
Closing the tenant-isolation hole requires a **separate, unprivileged runtime role** that
is *subject to* RLS.

This task introduces that role, `app_rt`, and cuts the runtime over to it. It is the
foundation for 1.2 (TenantContext) and 1.3 (RLS) — it does **not** itself add any RLS
policy or tenant filtering.

## Goals / Non-goals

**Goals**
- Introduce `app_rt`: `LOGIN`, `NOINHERIT`, `NOCREATEROLE`, `NOCREATEDB`, `NOBYPASSRLS`,
  **not** a member of `cloudsqlsuperuser`, owns nothing.
- `app_rt` can run all application DML (`SELECT/INSERT/UPDATE/DELETE`) and use sequences
  across the 12 schemas, on existing **and** future objects.
- `app_rt` **cannot** perform DDL and is **subject to** RLS (verified).
- Cut the application runtime over to `app_rt` in prod and local; keep `appuser` as the
  **owner / Flyway-migration** role.
- Provide repeatable provisioning (prod runbook + idempotent SQL) and a regression audit.

**Non-goals (handled by later tasks)**
- RLS policies and the `app.current_school_id` GUC — Task 1.3.
- `TenantContext` request filter and per-query tenant scoping — Task 1.2.
- Connection pooler (PgBouncer / transaction mode) — Task 3.3 (RLS GUC must be
  transaction-local; this design only notes the constraint, see Open Items).

## Decisions (resolved during brainstorming)

1. **Local parity:** the local docker-compose stack mirrors prod — runtime connects as
   `app_rt`, Flyway as the owner role — so RLS (1.3) and the BOLA suite (1.5) can be
   verified locally as a non-owner before staging/prod.
2. **Prod rollout:** manual pre-deploy provisioning (create the `app-rt-password` secret,
   run `create-app-rt-role.sql` via the established one-off Cloud Run Job pattern), then a
   normal pipeline deploy flips `_APP_DB_USER` to `app_rt`. Privileged DDL stays out of
   the per-build pipeline.
3. **Privilege model:** owner-grants + global default privileges (approach **A**).
   `appuser` keeps ownership/DDL; `app_rt` receives USAGE/DML grants plus
   `ALTER DEFAULT PRIVILEGES FOR ROLE <owner>` so future migration-created objects
   auto-grant DML to `app_rt`. No group role, no per-migration grant pass in prod.
4. **Role creation mechanism:** SQL `CREATE ROLE` (so `app_rt` does **not** inherit the
   `cloudsqlsuperuser` membership that `gcloud sql users create` would attach). The
   connecting `appuser` gains `CREATEROLE` for the duration via `SET ROLE cloudsqlsuperuser`.

## Components

### 1. `scripts/create-app-rt-role.sql` (idempotent)

Parameterized via `psql -v` for reuse across prod and local:
- `:owner` — the schema-owning / Flyway role (`appuser` in prod, `postgres` in local).
- `:app_rt_password` — `app_rt`'s login password (from Secret Manager in prod, compose
  env in local).

Behavior:
1. `SET ROLE cloudsqlsuperuser;` — gives the connecting role `CREATEROLE`. (In local,
   where the connecting role is `postgres`, this `SET ROLE` is skipped/guarded.)
2. Create the role if absent:
   `CREATE ROLE app_rt LOGIN NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS PASSWORD :'app_rt_password';`
   — explicitly never `GRANT cloudsqlsuperuser TO app_rt`.
   If the role already exists, `ALTER ROLE` to assert the same attributes and rotate the
   password.
3. For each of the 12 schemas (`identity, tenant_school, student, attendance, fee,
   catalog, workflow, firefighting, reporting, notification, audit, billing`):
   - `GRANT USAGE ON SCHEMA <s> TO app_rt;`
   - `GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA <s> TO app_rt;`
   - `GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA <s> TO app_rt;`
   - `ALTER DEFAULT PRIVILEGES FOR ROLE :owner IN SCHEMA <s>
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_rt;`
   - `ALTER DEFAULT PRIVILEGES FOR ROLE :owner IN SCHEMA <s>
        GRANT USAGE, SELECT ON SEQUENCES TO app_rt;`
4. Final assertions (raise/`\echo` on violation): `app_rt` is `NOSUPERUSER`, `NOBYPASSRLS`,
   `NOCREATEROLE`, `NOCREATEDB`, and not a member of `cloudsqlsuperuser`.

The schema list is the single source of truth in this script; it must track the 12
service schemas.

Because the per-schema `GRANT … ON ALL TABLES` and `ALTER DEFAULT PRIVILEGES … IN SCHEMA`
statements require the schemas to **already exist**, this script runs against an
already-migrated database: in prod the schemas exist and are populated (owned by
`appuser`); locally it runs **after** Flyway has created the schemas (the post-migration
grant pass in component 4). The pre-schema window on a fresh local DB is handled
separately by the init-time **global** default privileges in component 4.

### 2. Prod provisioning runbook (`docs/`)

A runbook (e.g. `docs/MICROSERVICE-APP-RT-PROVISIONING-RUNBOOK.md`) covering, in order:
1. `gcloud secrets create app-rt-password` with a generated strong value; grant the
   runtime service accounts access. Add `app-rt-password` to `scripts/secret-manager-catalog.ps1`.
2. Run `create-app-rt-role.sql` against prod Cloud SQL via a one-off Cloud Run Job — a
   `postgres:16-alpine` + `psql` job modeled on `audit-legacy-compatibility-cloudsql.ps1`,
   connecting as `appuser` (`db-password`), passing `-v owner=appuser` and
   `-v app_rt_password=<from secret>`. New script: `scripts/invoke-create-app-rt-role-cloudsql.ps1`.
3. Verify with `scripts/audit-app-rt-privileges.ps1` (component 5).
4. Only then merge the cloudbuild cutover (component 3) and deploy.
5. **Rollback:** revert `_APP_DB_USER` to `appuser` and redeploy; `app_rt` and grants can
   remain (harmless) or be dropped.

### 3. cloudbuild cutover (`cloudbuild.yaml`)

- `_APP_DB_USER: app_rt` (runtime user). `_FLYWAY_DB_USER: appuser` unchanged.
- `common_env` already sets `SPRING_DATASOURCE_USERNAME=${_APP_DB_USER}` and
  `FLYWAY_USERNAME=${_FLYWAY_DB_USER}` — so the username split is automatic once
  `_APP_DB_USER` flips.
- Split the password secrets per service: runtime
  `SPRING_DATASOURCE_PASSWORD=app-rt-password:latest`, Flyway
  `FLYWAY_PASSWORD=db-password:latest`. (Today both map to `db-password`.)
- This is a breaking change that only succeeds after components 1–2 are live in prod;
  the runbook enforces the ordering.

### 4. Local parity (`docker-compose.yml` + local setup)

- A Postgres init script in `docker-entrypoint-initdb.d` (e.g.
  `deploy/local/initdb/00-app-rt.sql` or wired via compose volume) that, at first DB init,
  creates `app_rt` and sets **global** default privileges
  (`ALTER DEFAULT PRIVILEGES FOR ROLE postgres GRANT ... TO app_rt;` — global form, since
  per-schema defaults require the schema to exist and schemas are created later by Flyway).
- The 12 service definitions: `SPRING_DATASOURCE_USERNAME=app_rt`,
  `SPRING_DATASOURCE_PASSWORD=app_rt`; `FLYWAY_USERNAME=postgres`,
  `FLYWAY_PASSWORD=root` (owner stays `postgres` locally).
- A post-migration grant pass (run `create-app-rt-role.sql` with `-v owner=postgres`, or
  folded into the existing local-dev setup flow) covers tables that already exist on a
  reused Postgres volume — defaults only apply to objects created *after* they are set.
- Idempotent so `docker compose up` on an existing volume converges.

### 5. Verification — `scripts/audit-app-rt-privileges.ps1`

Following repo audit-script conventions, runnable locally (against the compose DB) and in
prod (via one-off job). Asserts:
- `app_rt` exists, `LOGIN`, `NOSUPERUSER`, `NOBYPASSRLS`, `NOCREATEROLE`, `NOCREATEDB`,
  and **not** a member of `cloudsqlsuperuser` (query `pg_roles` / `pg_auth_members`).
- As `app_rt`: a representative `SELECT`/`INSERT`/`UPDATE`/`DELETE` on each schema
  succeeds.
- As `app_rt`: a DDL attempt (`CREATE TABLE …`) **fails** (permission denied).
- "Subject to RLS" smoke: create a temp table, `ENABLE ROW LEVEL SECURITY` + a restrictive
  policy; confirm `app_rt` sees only policy-allowed rows while the owner bypasses. (Proves
  the structural property RLS in 1.3 depends on. Cleaned up after.)

Wire the audit into `scripts/verify-microservice-migration.ps1` so it stays green as a
regression gate.

## Data flow (after cutover)

```
runtime request → service → JDBC as app_rt (DML only, subject to RLS)
migration / deploy → Flyway as appuser/owner (DDL, owns objects)
new object created by appuser → ALTER DEFAULT PRIVILEGES auto-grants DML to app_rt
```

## Testing strategy

- **Unit (per change):** existing service unit tests (`invoke-microservice-tests.ps1`) are
  unaffected (Mockito; no live DB) — must stay green.
- **Local integration:** `docker compose --profile full up`, run
  `audit-app-rt-privileges.ps1` against the compose DB — proves connect/DML-as-`app_rt`,
  DDL-denied, and subject-to-RLS, locally before prod.
- **Boundary gate:** `verify-microservice-migration.ps1` (now including the new audit)
  stays green.
- **Staging:** deploy with `_APP_DB_USER=app_rt`; confirm services start and serve traffic;
  run the audit against staging Cloud SQL.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Cutover deploy before role/secret exist → services can't connect | Runbook ordering; rollback = revert `_APP_DB_USER`, redeploy |
| Reused local Postgres volume lacks grants on pre-existing tables | Idempotent post-migration grant pass; document `docker compose down -v` for a clean slate |
| Missing sequence privileges break identity-column inserts | Explicit `GRANT USAGE,SELECT ON ALL SEQUENCES` + default privileges for sequences |
| A future new schema/service not added to the script's schema list | Single schema-list source in the script; audit fails if a runtime schema lacks `app_rt` USAGE |
| `app_rt` accidentally granted `cloudsqlsuperuser` (e.g. created via gcloud) | Create via SQL only; audit asserts non-membership and fails the gate otherwise |

## Open items (deferred, not blocking this task)

- **Pooler GUC isolation:** Task 1.3 sets `app.current_school_id` transaction-locally; the
  pooler (Task 3.3) must be transaction-mode so the GUC never leaks across requests. Noted
  here so 1.3/3.3 carry it; out of scope for 1.1.
- `ARCHITECTURE_REVIEW.md` / hardening runbook note that the single-`appuser` consolidation
  is intentionally partially reversed by this task — update those docs in the implementation.
