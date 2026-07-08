# Runbook — Provisioning the `app_rt` Runtime Role (Phase 1, Task 1.1)

Cuts the application runtime over to the unprivileged `app_rt` role. `appuser` stays the
owner + Flyway role. See `docs/superpowers/specs/2026-06-28-app-rt-runtime-role-design.md`.

## Order of operations (MUST be done before the cloudbuild cutover deploys)

1. **Create the secret** (one-time):
   ```
   <generate a strong value> | gcloud secrets create app-rt-password --data-file=- --project=custoking
   ```
   Grant each runtime service account `roles/secretmanager.secretAccessor` on `app-rt-password`
   (same accessors that hold `db-password`).

2. **Create the role + grants in prod Cloud SQL** (idempotent):
   ```
   powershell -ExecutionPolicy Bypass -File scripts\invoke-create-app-rt-role-cloudsql.ps1
   ```
   This runs `create-app-rt-role.sql` (as `appuser`, elevating via `SET ROLE cloudsqlsuperuser`)
   then the structural audit. Confirm the job logs show `create-app-rt-role: role ready` and the
   audit's attribute/membership checks passing.

3. **Re-verify any time** without re-creating:
   ```
   powershell -ExecutionPolicy Bypass -File scripts\invoke-create-app-rt-role-cloudsql.ps1 -AuditOnly
   ```

4. **Deploy the cutover**: merge the `cloudbuild.yaml` change (`_APP_DB_USER=app_rt`,
   runtime secret `app-rt-password`) and run the normal pipeline. Runtime now connects as
   `app_rt`; Flyway still as `appuser`/`db-password`.

5. **Post-deploy check**: services healthy and serving reads/writes; `-AuditOnly` green
   against prod.

## Rollback

Revert `_APP_DB_USER` to `appuser` (and the runtime secret back to `db-password`) and
redeploy. `app_rt` and its grants are harmless and may stay; drop only if required:
`DROP OWNED BY app_rt; DROP ROLE app_rt;` (run as a `cloudsqlsuperuser` member).

## Notes

- `app_rt` is created via SQL (not `gcloud sql users create`) so it is **not** a member of
  `cloudsqlsuperuser` and is therefore subject to RLS.
- Live RLS/DDL probes are exercised locally (superuser); prod asserts the structural
  attributes, which are sufficient to guarantee RLS enforcement for `app_rt`.
