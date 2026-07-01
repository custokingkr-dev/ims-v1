# Phase 1 Deployment Plan (Tenant-Isolation Hardening)

Consolidated cutover plan for Phase 1 (Tasks 1.1–1.7). It **orchestrates** the
per-domain runbooks in the correct order — it does not repeat their SQL. Deploy
mechanics: `deploy.yml` (`workflow_dispatch`) → `cloudbuild.yaml` → Cloud Run +
Cloud SQL. `cloudbuild.yaml` already runs services as **`app_rt`**
(`_APP_DB_USER`), Flyway as **`appuser`** (`_FLYWAY_DB_USER`), and deploys the
gateway with **`GATEWAY_AUTH_MODE=enforce`**.

> **The single most important invariant:** once RLS is enabled, the runtime role
> (`app_rt`) only sees rows when the per-request GUC is set — which requires
> (a) the `TenantAwareDataSource` live in the service and (b) the gateway in
> `enforce` mode injecting `X-Authenticated-School-Id`. Enabling RLS before both
> are live returns **0 rows** for every tenant. Every ordering rule below exists
> to preserve this.

## What ships in Phase 1

| Task | Change | Migration? | Runbook |
|---|---|---|---|
| 1.1 | `app_rt` unprivileged runtime role | role/grants (DBA) | `MICROSERVICE-APP-RT-PROVISIONING-RUNBOOK.md` |
| 1.2 | `TenantContext` (JWT-derived scope) | no | — |
| 1.3 | RLS + per-request GUC on clean tables | `*__enable_rls` (student/attendance/reporting) | `MICROSERVICE-RLS-ROLLOUT-RUNBOOK.md` §1–9 |
| 1.4 | NOT NULL tenant keys + indexes | denormalize/backfill + `SET NOT NULL` (two-phase) | `MICROSERVICE-TENANT-KEY-ROLLOUT-RUNBOOK.md` |
| 1.3-ext | RLS on the 1.4 tables | `*__enable_rls` (catalog/ff/wf/fee/attendance/reporting) | `MICROSERVICE-RLS-ROLLOUT-RUNBOOK.md` §10 |
| 1.6 | Refresh-token rotation + reuse detection | identity `V2` (additive, backfilled) | — |
| 1.7 | Validated DTOs (identity pilot) | no (API-layer) | — |

PRs (merge in stack order): **#14** tenant-keys → **#15** RLS-extension → **#16** BOLA gate → **#17** refresh-rotation → **#18** input-validation → **#19** CI enforce-mode smokes. (1.1/1.2/1.3-clean landed earlier.)

## Pre-flight (once, before the first Phase-1 release)

1. **`app_rt` provisioning in prod Cloud SQL** — per `MICROSERVICE-APP-RT-PROVISIONING-RUNBOOK.md`: create the `app_rt` role (`NOINHERIT`, `NOBYPASSRLS`, not a `cloudsqlsuperuser` member), grant `USAGE` + DML on all schemas + default privileges, and ensure the **`app-rt-password`** Secret Manager secret exists. Run `scripts/audit-app-rt-privileges.ps1` (or the SQL) to confirm `app_rt` is subject to RLS and cannot DDL. **If prod already runs as `app_rt` today, this is a no-op — confirm it.**
2. **Confirm gateway `enforce`** is what prod runs (it is, per `cloudbuild.yaml` line ~204). No permissive gateway may remain once RLS is on.
3. **Backups / PITR** — confirm Cloud SQL automated backup + a restore point immediately before the cutover.

## Release sequence

The RLS and tenant-key `SET NOT NULL` migrations are **held back** in Release A
via Flyway `target` (or `SPRING_FLYWAY_ENABLED=false`) so the datasource +
`enforce` gateway are proven live first; Release B lets them apply. Per-service
`target` values are in the two runbooks (RLS-ROLLOUT §10.2, TENANT-KEY §two-phase).

### Release A — datasource + enforce live, migrations held

- **Deploy** all services + gateway at the Phase-1 image tag, with Flyway gated so `enable_rls` / `SET NOT NULL` do **not** run yet:
  ```
  gh workflow run deploy.yml -f environment=<env> -f service=all -f tag=<phase1-sha> -f run_smoke=true
  ```
  Set the per-service Flyway hold (env override) as the runbooks specify (e.g. `SPRING_FLYWAY_TARGET=<pre-RLS version>` or `SPRING_FLYWAY_ENABLED=false` for the services whose `enable_rls`/NOT-NULL is being held). The **denormalize/backfill** migrations (nullable column + backfill + index) DO run in Release A — only the `SET NOT NULL` / `enable_rls` are held.
- **Roll to 100%** — confirm every Cloud Run revision is the new one (no old revision serving traffic) so the `TenantAwareDataSource` + `TenantContext` are universal and the gateway is `enforce`.
- **Pre-cutover checks (must all pass before Release B):**
  - **NULL pre-check** per Group-A + backfilled table: `SELECT count(*) FROM <schema>.<table> WHERE school_id IS NULL;` → **0** (TENANT-KEY runbook §pre-cutover; all five Group-A checks gate Release B).
  - **Orphan / mis-scope pre-check** for the cross-schema-backfilled tables (attendance_daily←school_sections, fee_assignments/payment_records←students) — the `IS DISTINCT FROM` queries in RLS-ROLLOUT §10.3 → **0**.
  - **app_rt audit** green.
  - Feature smoke green in enforce (the deployed-GCP-gateway smoke, or `whole-application-validation` via `workflow_dispatch`).

### Release B — enable RLS + SET NOT NULL

- **Deploy again** at the same tag with the Flyway hold **removed** (unset `SPRING_FLYWAY_TARGET`/re-enable Flyway). On boot, Flyway (as `appuser`) applies the held migrations: `enable_rls` (owner-bypass, `app_rt` subject) and the tenant-key `SET NOT NULL`.
  ```
  gh workflow run deploy.yml -f environment=<env> -f service=all -f tag=<phase1-sha> -f run_smoke=true
  ```
- **Verify** immediately (see below). RLS is now enforced; because Release A already made the datasource + enforce gateway universal, `app_rt` reads carry the GUC → correct rows.

## Verification (post-Release B)

1. **`whole-application-validation`** (`workflow_dispatch`) — now runs its smokes in **enforce** mode (PR #19): migration-boundary audit, gateway-routes (401=wired+protected), features (57/57), and the **BOLA tenant-isolation gate**. Green = tenant isolation proven end-to-end through the deployed gateway.
2. **Deployed GCP gateway smoke** (`deploy.yml` `run_smoke=true` / `invoke-production-gateway-smoke.ps1`).
3. **Spot-check RLS as `app_rt`** on the prod DB: with a school-A GUC, `SELECT` on an RLS table returns only school-A rows; unset GUC → 0 rows.
4. **Refresh-rotation** sanity: a refresh returns new tokens; replaying a retired token → 401 + a `REFRESH_TOKEN_REUSE_DETECTED` row in `identity.rbac_audit_log`.

## Rollback

Per `MICROSERVICE-ROLLBACK-RUNBOOK.md` + the per-domain runbooks (forward-only):
- **RLS:** forward migration `DROP POLICY tenant_isolation …; ALTER TABLE … DISABLE ROW LEVEL SECURITY;` per table (`app_rt` keeps DML) — or redeploy the prior image (RLS stays enabled but the datasource is unchanged; RLS was already safe).
- **Tenant-key NOT NULL:** `ALTER TABLE … ALTER COLUMN school_id DROP NOT NULL;` per table (denormalized columns/indexes may remain).
- **Refresh-rotation (identity V2):** redeploy the prior identity image; leave the additive `family_id`/`status` columns (harmless if unused).
- **Enforce mode:** do NOT revert the gateway to permissive while RLS is enabled (→ 0 rows). If a full revert is needed, disable RLS first, then flip the gateway.

## Human-gated / owner actions

- Merging the 7 PRs (review + approval).
- Running `deploy.yml` (needs GCP WIF / gcloud auth — owner runs it).
- The Cloud SQL DBA steps (app_rt role/grants, Secret Manager) if not already present.
- Executing the pre-cutover SQL checks against prod Cloud SQL.

## Open items surfaced during Phase-1 local verification

- **`GET /api/v1/schools/{id}/admin` → 405**: the public path collides with identity-compat's POST handler at the gateway; the tenant-school GET handler is unreachable. Pre-existing routing bug (not Phase 1) — fix the gateway route mapping in a follow-up.
- **1.7 is a pilot** — validated DTOs cover identity's RBAC + password-reset write endpoints only; rolling to the remaining identity endpoints + the other services is ongoing (`[EXPAND per service]`).
