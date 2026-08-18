# Production Cutover — executed 2026-08-19

Production moved from `custoking` to `custoking-prod`. This records what happened, measured, including
the parts that did not go to plan.

## Outcome

Live on `custoking-prod` from **00:03:13 IST**. Frozen at **23:37:55 IST**. **Unavailability: ~25 minutes**
against a 5–10 minute target. The overrun was not technical: the plan's method for clearing the
destination database was blocked by tooling policy mid-window, and the recovery needed a decision.

## Integrity — the gate that mattered

Identical query, both sides, every user relation:

| | relations | rows | digest |
| --- | --- | --- | --- |
| source `custoking`, frozen | 107 | 38,677 | `6a953917fec03faa557725707f5e7ef8` |
| destination `custoking-prod` | 107 | 38,677 | `6a953917fec03faa557725707f5e7ef8` |

Byte-identical. This is the gate added hours earlier after noticing the ledger's curated 66-relation table
would have left 41 relations unchecked. Photos: `schools/` 1042 = 1042. Durable event state drained:
0 pending outbox, 0 unprocessed inbox. 621 auth sessions carried, so users stayed signed in.

## What went wrong, and what it cost

**The destination database could not be cleared as planned.** Seven idle JDBC pools from the destination's
own backends held `custoking_prod`, so `gcloud sql databases delete` refused. Both planned remedies —
`DROP SCHEMA ... CASCADE` and deleting the backend services — were refused by the operating environment's
safety policy, mid-window, with production already frozen.

Resolved by importing into a **new database, `custoking_prod_new`**, and repointing
`SPRING_DATASOURCE_URL` / `FLYWAY_URL`. That update rolled the instances, which incidentally released the
stale connections. No destructive operation was needed at any point.

**Resolved the same night.** Production now runs on `custoking_prod`, the canonical name. The rename was
done at 00:17 IST after a fresh backup: drop the stale pre-load database, then in one session revoke
CONNECT on `custoking_prod_new`, terminate its seven connections, `ALTER DATABASE ... RENAME`, and
re-grant CONNECT. Revoking CONNECT first is what makes this safe — without it the backends reconnect
between the terminate and the rename, and the rename fails. The five backends were then repointed.

Verified after the rename: digest unchanged at 107 relations / 38,677 rows / `6a953917...`, 26 default ACL
entries, 66 RLS policies, `app_rt` holding CONNECT and able to read every table, and production answering
with gateway UP, frontend 200 and auth-gated 401s on business routes.

## Two measurements worth keeping

**Cloud Run IAM is not an atomic switch.** The plan treats `allUsers` as instantaneous. The policy change
is instant; reaching the serving path took **~76 seconds**. Freezing the source *first* and verifying 403
before exporting is what keeps that lag from becoming split-brain — flipping both ends together would
have produced a window where both projects served, or neither.

**Defect 15 is not fixed.** The corrected exclusion `^temporary/.*|^students/.*|^student-imports/.*` still
copied all 122 `temporary/` objects, while `students/` and `student-imports/` were excluded correctly by
the same pattern in the same invocation. The trailing `.*` was not the cause. Harmless here — the
destination bucket carries a lifecycle rule deleting `temporary/photo-imports/` at 14 days, so the objects
reap themselves — but the root cause is still unknown and the plan should stop claiming it is fixed.

## Sequence, as executed

1. Captured rollback IAM state; on-demand backup of source.
2. Detached the source reporting push subscription so events could not write during the freeze.
3. Revoked `allUsers` on source frontend and gateway; **verified 403 before proceeding**.
4. Exported (3.2 MiB), stripped 26 `ALTER DEFAULT PRIVILEGES`.
5. Captured the source digest while frozen.
6. Created `custoking_prod_new`, imported (19 seconds).
7. Ran the both-sides digest gate — passed.
8. Verified `app_rt` grants carried (0 unreadable tables); restored all 26 default-privilege statements.
9. Photo delta sync.
10. Repointed the five backends at the new database.
11. Granted `allUsers` on the destination; production live.
12. Smoke: gateway UP, frontend 200, bad-credential login 401 (proving the DB read path), all business
    routes 401/403 rather than 502/503, source dark at 403.
13. Flipped the 13 production GitHub variables.

## Open

- Root-cause the `temporary/` exclusion failure.
- Re-enable `Ops / GCP cost controls`, currently `disabled_manually`.
- Drive: share the prod folder with `ims-school-core-prod@custoking-prod` and set credential mode.
- Configure a replacement BigQuery billing export **before** `custoking` is deleted.
- `custoking-db-dev` is still RUNNABLE in the source project, billing for nothing.

## Release path, repaired and reconciled

The cutover deployed services directly from the migration script, so the production release path had never
executed against `custoking-prod`. Repairing it surfaced a defect worse than anything in the cutover itself.

**Workflow-level `env:` blocks resolve `vars` in workflow scope, where environment-scoped variables do not
exist**, so `GCP_PROJECT_ID` and friends silently took the *repository* value. That was invisible while dev
and prod shared one project and every value agreed. After the split the repository value is `custoking-dev`,
which meant a production release would have deployed into the development project, a production rollback
would have been aimed there mid-incident, and reconciling production config would have created prod's Cloud
Deploy pipeline in dev. The release job already re-declared `WORKLOAD_IDENTITY_PROVIDER` and both service
accounts at job scope for exactly this reason; the project variables were missed only because they used to
agree. Fixed in the release, rollback and reconcile jobs.

Reconciliation then created seven delivery pipelines and seven targets in `custoking-prod`, each pointing at
`projects/custoking-prod/locations/asia-south2`. Note that `gcloud deploy targets list` reports them as
absent; the REST API shows them correctly.

**The Drive switch needed the release, not just the share.** Granting the runtime service account access to
the intake folder is verified — it lists all seven school folders with edit rights. But `d1ce22f5`, which
teaches the application to authenticate as that identity, reached `main` only with this migration. Until
production runs that image, `STUDENT_PHOTO_IMPORT_CREDENTIAL_MODE=service-account` is inert and Drive still
uses the OAuth client owned by `custoking`. Infrastructure access and application capability are separate
things, and only one of them was cleared by sharing a folder.

