# Runbook — Provisioning the `app_rt` Runtime Role (Phase 1, Task 1.1)

> Historical deployment note, updated 2026-08-04: the `app_rt` role remains current, but references below to `cloudbuild.yaml` describe the retired deployment path. Current deployments use GitHub Actions plus Cloud Deploy.

Cuts the application runtime over to the unprivileged `app_rt` role. `appuser` stays the
owner + Flyway role.

## What a run of `create-app-rt-role.sql` does (updated 2026-11-14)

The script is idempotent. A re-run on a migrated database now leaves what the migrations set alone.

- **It grants per relation, minus a narrowing list.** `app_rt` gets SELECT, INSERT, UPDATE and DELETE on every
  relation of the twelve service schemas, except the table-level privileges on the narrowing list at the top of the
  script.
  - *Before.* One `GRANT ... ON ALL TABLES IN SCHEMA` per schema handed back everything a migration had taken away.
    Measured on all 12 units migrated: 45 table-level grants on 18 relations.
  - *Now.* A listed privilege is never granted. Column grants a migration made (for example UPDATE on four columns of
    `firefighting.quotation_documents`) stay exactly as they are.
  - It grants only what `app_rt` does not already hold from the owner. A re-run on a database that is already right
    writes no table or sequence ACL at all.
  - *Why it matters twice.* Versioned migrations narrow once and never run again. And every migration job copies
    `app_rt` onto its dedicated role (`afterMigrate__dedicated_runtime_acl.sql`), so a widened `app_rt` reached
    `ims_school_core_rt`, `ims_operations_rt` and `ims_platform_rt` on their next release, even with nothing pending.
- **The list.** One row per relation and the privileges `app_rt` must not hold there at table level:
  - catalog: `legacy_catalog_migration_map` (all four, V8), `annual_plan_confirmations` (U, D, V24),
    `order_number_counters` (D, TRUNCATE, V37), `product_order_workflows` and `_steps` (I, U, D, TRUNCATE, V38).
  - student: `guardian_legacy_parity` (a view, all four, V24), `guardian_safe_create_repair_runs` and `_actions`
    (all four, V26), `erasure_journal_receipts` (U, D, V39), `students` (I, V39: see the column rule below).
  - tenant_school: `photo_cleanup_outbox` (D, V30).
  - firefighting: `quotation_documents` (U, D, V12), `creation_replays` (U, D, V13).
  - workflow: `workflow_internal_notes` and `workflow_follow_ups` (U, D, V11), `workflow_call_logs` (U, D, TRUNCATE, V20).
  - notification: `notification_broadcast_recipients` (V11), `broadcast_live_submissions` and `broadcast_live_reports`
    (V12), `generic_submissions` (V15 and V16): U, D. `generic_delivery_results`, `generic_delivery_reports` (V17) and
    `generic_unknown_report_assertions` (V18): I, U, D.
  - reporting: `trusted_command_audit` (U, D, V32).
  - Every Flyway history table, `tenant_school.flyway_schema_history_tenant_school` included: all four. Migrations run
    as the owner and no runtime code reads them; the script's own default privileges hand them to `app_rt` when Flyway
    creates them, so a run takes them back.
- **Column grants it makes itself.** Seven listed relations get a privilege on some columns from their migration:
  `firefighting.quotation_documents` (UPDATE, V12), `notification_broadcast_recipients` (UPDATE, V11),
  `broadcast_live_submissions` (UPDATE, V12), `generic_submissions` (UPDATE, V15 and V16), `generic_delivery_results`
  (INSERT and UPDATE, V17), `generic_delivery_reports` (INSERT, V17), `generic_unknown_report_assertions` (INSERT, V18).
  The list names those columns too.
  - *Before.* The migrations grant them inside `IF EXISTS (app_rt)`. On a database migrated before `app_rt` existed (a
    restore from a dump, a cutover onto a migrated database, GREENFIELD 1.3 run late), nobody granted them: the script
    left out the listed table-level privilege and granted no columns. `app_rt` could not UPDATE or INSERT there at all,
    and the script still exited 0.
  - *Now.* The script grants any listed column `app_rt` cannot use, with
    `NOTICE: create-app-rt-role: gave app_rt <privilege> on <relation>(<columns>), as the migration does`. The final
    check and the audit fail if one is still missing (`app_rt lacks column privileges a migration grants it: ...`).
  - So the order no longer matters: `app_rt` created before or after the migrations ends with the same privileges
    (`ProdShapeTests` in `scripts/tests/create_app_rt_role_migrated_test.py`).
- **One column rule.** `student.students`: INSERT on every column but `erasure_incarnation`, as student V39 grants it.
  Until V39 has added that column, the table keeps its table-level INSERT, which V39 converts. Without that, a run
  landing between student V1 and V39 (locally, `ensure-app-rt-local.ps1` runs during migrations) left `app_rt` with no
  INSERT on `students` at all.
- **It repairs a widened database.** If `app_rt` holds a listed privilege at table level from the owner, the run takes
  that grant back and gives back the owner's column grants of that privilege. Each repair prints
  `NOTICE: create-app-rt-role: took table-level <privilege> on <relation> back from app_rt`. A grant another role made
  is not touched and not reported as repaired; the final check names it.
- **One transaction.** The grants and the repair commit together. A failure part way leaves `app_rt` as it was, never
  wider. In prod `app_rt` is the live role.
  - *Locks.* Each relation whose ACL the run changes keeps its `pg_class` row locked until COMMIT (well under a second).
    Reads and writes are not blocked; DDL on that relation (a migration's `ALTER TABLE`) waits, and if both touch the
    same row at once one side can fail with `tuple concurrently updated`. Run the script after migrations settle, not
    during them. `ensure-app-rt-local.ps1` runs it while local services migrate and retries its own side; a service
    whose startup migration lost the race fails its start and has to be started again.
- **The final check names what is wrong.** It reads what `app_rt` can actually do (any grantor, PUBLIC included) and
  fails with relation, privilege and grantor, for example
  `tenant_school.photo_cleanup_outbox DELETE (granted to app_rt by <role>)`. A grant another role made cannot be taken
  back by the owner; the owner's narrowing is committed before the check, so it is kept.
- **A relation that does not exist yet is skipped**, so a first run before the services migrate is unchanged.
- **It needs TEMP on the database.** The list lives in session temp tables. PUBLIC has TEMP by default, and the
  database owner always has it (on Cloud SQL, databases created through the Admin API belong to `cloudsqlsuperuser`,
  whose rights `appuser` inherits; not checked live). Without it the script stops at the list, after the role step and
  before any grant: `permission denied to create temporary tables`.
- **Keeping it current.** A migration that takes a privilege away from `app_rt` adds a row to the list, in both
  `scripts/create-app-rt-role.sql` and `scripts/audit-app-rt-privileges.sql` (the Cloud Run job ships each file on its
  own, so they cannot share an include). If it then grants that privilege on some columns, it also adds a column-grant
  row naming them. `scripts/tests/create_app_rt_role_migrated_test.py` (CI job `app-rt-role-invariants`, every pull
  request) fails if the two blocks differ, if a row cannot be read, if a listed relation or column does not exist, if
  the column grants differ from what the migrations grant, or if a re-run changes any ACL the migrations set. A
  misspelt privilege or schema fails the script itself before any grant (CHECK constraints on the list).

### The audit (`audit-app-rt-privileges.sql`)

- It no longer requires SELECT on relations the list bars from reading (the legacy ledger, the repair tables, the
  Flyway history tables). Before, it failed on a correctly narrowed database:
  `app_rt lacks SELECT on catalog.legacy_catalog_migration_map`.
- It asserts the whole list, the column grants and the column rule. The check is one function defined inside the
  shared block (`pg_temp.app_rt_check_narrowing()`), so the script's final check and the audit cannot drift apart.
- **The first audit after this change can fail on an untouched database.** Until the new script has run once, `app_rt`
  still holds DML on the Flyway history tables, and on anything an earlier re-run widened. Run the script without
  `-AuditOnly` (it repairs both), then audit.
- To check a database read-only, run the audit alone (`-AuditOnly`). It creates only session temp objects.

### How the Cloud Run job carries the SQL (updated 2026-11-14)

- *Before.* `invoke-create-app-rt-role-cloudsql.ps1` put both files inline as base64 in `gcloud run jobs create
  --args`. With the narrowing list in both files that was about 45,500 characters (18,700 for `-AuditOnly`). From
  Windows, `gcloud.cmd` goes through `cmd.exe`, which stops at 8,191 (`The command line is too long.`), and Windows
  refuses any command line over 32,767. Neither the full run nor `-AuditOnly` could start from Windows. It also never
  checked gcloud's exit code, so a failed execution still printed `job completed`.
- *Now.* The script writes a job definition and runs `gcloud run jobs replace <file>`, then `execute --wait`, then
  `delete`. Each SQL file travels gzip compressed and base64 encoded in its own environment variable
  (`APP_RT_CREATE_SQL_GZ_B64` about 9K, `APP_RT_AUDIT_SQL_GZ_B64` about 6K; Cloud Run allows 32 KB per variable).
  Passwords still come from Secret Manager references; `-AuditOnly` carries neither the create script nor
  `app-rt-password`. TLS is required (`PGSSLMODE=require`, exported by the job's shell too).
- The job's shell checks each decoded file against the SHA-256 of the file the PowerShell script read, and prints it:
  `create-app-rt-role.sql sha256 <hash>`. Compare it with `sha256sum scripts/create-app-rt-role.sql` in the checkout
  you ran from.
- A failed `replace` or `execute` now throws and exits non-zero. The job and the definition file are removed either way.
- `-RenderOnly` writes the definition (`-JobFile <path>`) and prints the gcloud commands without calling gcloud.
  `CloudRunJobTests` and `ProdShapeTests` render it and run its shell against PostgreSQL 16 over TLS, as a
  non-superuser owner.

### Only this script provisions `app_rt`

- Run `create-app-rt-role.sql` only through `scripts/invoke-create-app-rt-role-cloudsql.ps1` (Cloud SQL) or
  `scripts/ensure-app-rt-local.ps1` (local), from a current checkout.
- Do not execute a persistent job or a stored copy of the SQL. Older inventories list a long-lived job
  `ims-app-rt-prod` and an unsuffixed secret `create-app-rt-role-sql` ("operational SQL held as secrets",
  `docs/GCP-SOURCE-DELETION-CONTINUITY-2026-08-18.md`). Either one holds a pre-2026-11-14 script: its blanket
  `GRANT ... ON ALL TABLES` widens all 18 narrowed relations again, and the next migration job copies that onto the
  dedicated roles.
- Those inventories describe the deleted `custoking` project; whether either exists in `custoking-prod` has not been
  checked. Check read-only (`gcloud run jobs describe ims-app-rt-prod --project custoking-prod`,
  `gcloud secrets describe create-app-rt-role-sql --project custoking-prod`) and retire what exists.

### Dev: the script forces LOGIN

- On dev the services connect as `ims_*_rt`. `app_rt` is retired: NOLOGIN
  (`scripts/security/retire-dev-shared-runtime-role.ps1`), kept as the ACL template the migration callbacks copy.
- The script's role step runs `ALTER ROLE app_rt LOGIN ... PASSWORD`, so running it on dev turns login back on. Its
  final check and the audit both require LOGIN, so `-AuditOnly` fails its first check on dev by design.
- Do not run it on dev unless you mean to. If you do, run `retire-dev-shared-runtime-role.ps1 -Apply` afterwards.

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
   then the structural audit. Confirm the job logs show `create-app-rt-role: role ready` and
   `app_rt audit: all checks passed`. Any `took table-level ... back from app_rt` notice means the
   database had been widened and is now repaired.

3. **Re-verify any time** without re-creating:
   ```
   powershell -ExecutionPolicy Bypass -File scripts\invoke-create-app-rt-role-cloudsql.ps1 -AuditOnly
   ```
   Both commands take `-Project` (or `GCP_PROJECT_ID`) and, for an instance other than the default, `-HostAddress`
   and `-Database`. They work from Windows PowerShell since 2026-11-14 (see "How the Cloud Run job carries the SQL").

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
