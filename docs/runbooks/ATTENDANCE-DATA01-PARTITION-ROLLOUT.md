# DATA-01 attendance partition rollout

Status: operator tooling; not a Flyway migration and not approved for unattended deployment.

## Decision and compatibility boundary

`attendance.attendance_student_records` currently has global uniqueness on `id`, on
`(attendance_daily_id, student_id)`, and on `(student_id, attendance_date, academic_year_id)`.
PostgreSQL cannot enforce the first two constraints as native unique constraints on a table
partitioned only by `attendance_date`, because every partitioned unique key must include the
partition key. DATA-01 therefore uses:

- annual range partitions plus a default partition;
- local partition-compatible primary/unique constraints; and
- `attendance.attendance_student_record_identity`, maintained by security-definer triggers, to
  retain the three existing global uniqueness contracts.

The service currently treats record `id` and `attendance_date` as immutable. DATA-01 makes that
contract explicit in the registry update trigger. Normal status, remarks, audit fields, daily-row,
and student updates remain supported.

This is an **offline maintenance-window migration**. It is not an online or zero-downtime design.
The freeze phase installs a database trigger that rejects every source-table write, so a missed
application drain fails visibly instead of producing a divergent copy.

## Required gates

Before setting the approval marker:

1. Take and record a recoverable Cloud SQL backup/PITR checkpoint.
2. Scale/drain school-core writers and stop attendance jobs, imports, and operator SQL sessions.
3. Confirm no non-operator attendance transactions remain in `pg_stat_activity`/`pg_locks`.
4. Confirm the 25-million-row rehearsal evidence and the current restore drill are accepted.
5. Run the complete operator sequence on a current production clone and record the measured source,
   partition target, identity registry, index, database, and peak WAL bytes.
6. Provision free storage for the larger of: `1.25 × (measured target + registry + peak WAL)`
   from that clone run, or `2.25 × source total bytes + the approved WAL budget`. The low-peak
   rehearsal is not a storage estimate for this rollout: production build deliberately retains the
   source while target, child indexes, and registry coexist. This is a required disk-headroom gate,
   not an advisory estimate.
7. Run `Preflight` and archive its JSON evidence. It reports source heap/index/total bytes, database
   bytes, and a conservative additional-headroom estimate that excludes WAL.

Mutating phases require the explicit `-MaintenanceApproved` switch. The runner translates this to
the session-only `app.data01_maintenance_approved=DATA-01` marker; it does not persist approval.

## Execution sequence

Run each phase separately and archive the generated artifact path. `DATABASE_URL` may be used in
place of `-ConnectionString`; use a short-lived owner credential and do not place passwords in
shell history.

```powershell
./scripts/invoke-attendance-data01-partition.ps1 -Phase Preflight
./scripts/invoke-attendance-data01-partition.ps1 -Phase Freeze -MaintenanceApproved
./scripts/invoke-attendance-data01-partition.ps1 -Phase Build -MaintenanceApproved
./scripts/invoke-attendance-data01-partition.ps1 -Phase Verify -MaintenanceApproved
./scripts/invoke-attendance-data01-partition.ps1 -Phase Cutover -MaintenanceApproved
```

`Freeze`, `Build`, `Verify`, and `Cutover` are phase-guarded. Build is single-shot inside one
transaction: a failure removes all partial build objects and leaves phase `FROZEN`, so it can be
retried. Cutover takes bounded (`10s`) access-exclusive locks and rolls back on timeout.

Do not resume service writes until all pre-resume checks pass:

- active `attendance_student_records` has `relkind = 'p'`;
- row count/checksum and registry parity evidence passed;
- all target constraints are validated;
- RLS and `tenant_isolation` are present;
- runtime-role tenant visibility and cross-tenant rejection pass;
- representative date predicates prune unrelated annual partitions; and
- duplicate `id`, daily/student, and student/date/year attempts fail.

The original table remains as `attendance_student_records_data01_unpartitioned` during the
observation window. Its write-freeze trigger remains attached.

Do not deploy the application, run Flyway, or change attendance persistence code while phase is
`CUTOVER`. The active table intentionally has temporary DATA-01 constraint/index names until
`Finalize`; deployments resume only after finalization or an approved rollback.
Operational shorthand: **no app/Flyway deployment during CUTOVER observation**.

## Rollback boundary

Before any partitioned-table write, rollback is deterministic:

```powershell
./scripts/invoke-attendance-data01-partition.ps1 `
  -Phase RollbackBeforeResume -MaintenanceApproved
```

The cutover installs a statement-level write counter. Rollback locks both tables, rechecks that the
counter is zero, and rechecks row count/checksum parity. It aborts after any committed post-cutover
write. Once writes resume, do not use the pre-resume rollback script; follow the approved restore or
forward-repair procedure.

## Finalization

After the observation window, backup checkpoint, query-plan review, and explicit change approval,
finalization removes the legacy table and therefore removes the pre-resume rollback path. It also
restores the original Flyway constraint/index names on the active parent table and removes the
temporary write counter. The permanent identity registry and its three integrity triggers remain.

```powershell
./scripts/invoke-attendance-data01-partition.ps1 `
  -Phase Finalize -MaintenanceApproved -FinalizeApproved
```

`Finalize` requires two independent session markers and is the only destructive phase.

## Ongoing operations

- Create the next annual partition before the current `+2 year` horizon is reached.
- Alert when rows enter `attendance_student_records_default`; move them into a bounded partition
  during a separate approved operation.
- Include the identity registry in table bloat, index, backup, and restore checks.
- Keep the DATA-01 schema test green on PostgreSQL 16 whenever attendance migrations change.
