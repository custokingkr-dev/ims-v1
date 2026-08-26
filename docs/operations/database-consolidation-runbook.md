# Database Consolidation Runbook

This runbook operates the non-destructive consolidation controls introduced after the 2026-08-25 deep
analysis. It does not authorize table or column deletion. Destructive DDL requires a separate reviewed
change after the observation and retention gates below pass.

## Safety rules

- Run migrations through the normal release path; never edit an applied Flyway migration.
- Take a fresh backup and record the restore point before production migration.
- Keep legacy tables readable throughout the dual-read observation window.
- Never infer a school or academic year for a legacy catalog row.
- Do not use an unused-index report as a drop list.
- Evidence output contains aggregate IDs/counts only; do not publish row-level personal data.

## 1. Billing invoices

Migration `billing/V7__legacy_invoice_consolidation.sql` mirrors every legacy invoice with a non-null
`school_id` into the canonical customer, invoice, item, and payment model. The trigger keeps later
compatibility writes synchronized. Null-school rows remain unmigrated deliberately.

Gate query:

```sql
SELECT * FROM billing.legacy_invoice_migration_summary;
SELECT issue, count(*)
FROM billing.legacy_invoice_migration_issues
GROUP BY issue;
```

Required before changing reads:

- `issue_rows = 0`;
- legacy and canonical totals match exactly;
- paid legacy invoices have a canonical migration-owned payment;
- compatibility-route traffic is measured; and
- export/PDF fixtures agree on ID, dates, status, quantity, tax, and totals.

## 2. Catalog orders and annual plans

The old catalog rows have no tenant key. Populate the owner-only mapping ledger explicitly:

```sql
UPDATE catalog.legacy_catalog_migration_map
SET school_id = :reviewed_school_id,
    academic_year_id = CASE
        WHEN source_table = 'annual_plan_entries' THEN :reviewed_academic_year_id
        ELSE NULL
    END,
    mapped_by = :operator_identifier,
    mapped_at = now(),
    notes = :evidence_reference
WHERE source_table = :source_table
  AND source_id = :source_id
  AND migrated_at IS NULL;
```

After a second-person review of every mapping:

```sql
SELECT * FROM catalog.legacy_catalog_migration_readiness;
SELECT * FROM catalog.apply_legacy_catalog_mappings();
SELECT * FROM catalog.legacy_catalog_migration_readiness;
```

The apply function is idempotent. It never overwrites a canonical row after provenance is established.

## 3. Guardian compatibility columns

The normalized guardians and relationships were already backfilled in student migration V14. Migration
V24 adds owner-only parity evidence. Compatibility columns stay until all readers, exports, and imports use
the normalized model.

```sql
SELECT
  count(*) FILTER (WHERE NOT father_name_matches) AS father_name_mismatches,
  count(*) FILTER (WHERE NOT father_contact_matches) AS father_contact_mismatches,
  count(*) FILTER (WHERE NOT mother_name_matches) AS mother_name_mismatches
FROM student.guardian_legacy_parity;
```

All three counts must remain zero for the full compatibility-route observation window.

### Guarded missing-link creation

V25 exposes one owner-only, versioned planner for the narrow case where an active student has legacy
father/mother data and zero normalized guardian links. V26 can create only the exact reviewed plan. It never
merges, updates or reactivates an identity; never changes consent; grants no notification, academic, fee or
pickup authority; and records its domain writes, profile-review invalidation and outbox events atomically.

Validate the disposable runner locally first:

```powershell
./scripts/invoke-guardian-repair-cloudsql.ps1
```

This dry run contacts neither GCP nor PostgreSQL. Production execution remains prohibited until V25/V26 are
deployed, a new read-only evidence run supplies the contract-aware hash, the pinned approval gate is updated
through review, and the exact write is separately authorized. The runner's current old plan hash is an
intentional fail-closed control, not an instruction to bypass the new evidence run.

The database ledger is operational evidence and includes the approval reference, deployed source revision,
runner payload digest, job name and database role. Preserve the corresponding Cloud Audit/Run logs and
repository approval record as the immutable audit source; the PostgreSQL owner can administer owner-held
ledger tables.

## 4. Evidence bundle

Run `scripts/database-consolidation-evidence.sql` with the owner/migration identity. Capture its output in
the approved evidence store, not in a public repository when it contains production identifiers.

The unused-index section is observational. Collect at least 30 representative days and check constraint,
foreign-key, query-plan, and Query Insights evidence before proposing a concurrent index removal.

The reporting migration V29 compares `dim_student` with the newest successfully processed durable event.
If a reviewed mismatch is approved, use the fingerprint-bound disposable runner rather than exposing a
student identifier or issuing an ad hoc database update:

```powershell
# Local validation only; contacts neither GCP nor PostgreSQL.
./scripts/invoke-student-projection-requeue-cloudsql.ps1

# Production execution additionally requires the exact recorded approval and deployed source revision.
./scripts/invoke-student-projection-requeue-cloudsql.ps1 `
  -ApprovalReference '<approved-projection-reference>' `
  -SourceRevision '<40-character-deployed-git-revision>' `
  -Apply -ConfirmProductionWrite
```

The runner is serializable, locks the inbox against concurrent writers, requires exactly one current issue
and exactly one approved fingerprint, invokes only the owner-held requeue function, verifies the clean
`RECEIVED` state, deletes its disposable job, and disables the migration identity. This is not a
cross-schema overwrite. The normal projection processor applies the event and retains its retry, tombstone,
and dead-letter behavior. Re-run read-only parity and require zero issues before closeout.

## 5. Retirement gate

A legacy table/column may be proposed for removal only when:

1. backfill and ongoing synchronization reconcile exactly;
2. canonical APIs and exports are deployed;
3. legacy route and database-read telemetry is zero for the approved window;
4. rollback applications no longer require the legacy shape;
5. the retention owner approves the deletion date; and
6. a forward-only repair plan and restore evidence are attached to the change.
