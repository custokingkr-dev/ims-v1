# Tenant-Key Hardening Rollout Runbook — Custoking IMS

**Status:** Phase 1 complete (6 services, 10 tables — `school_id` NOT NULL enforced)
**Branch:** `phase1-tenant-keys`
**Date:** 2026-06-30

---

## 1. Overview

This runbook covers the two-phase production rollout for the `school_id` NOT NULL
hardening shipped on branch `phase1-tenant-keys`. It covers 10 tables across 6 services:

- **Group A** — `school_id` column already existed; SET NOT NULL after verifying zero NULLs.
- **Group B** — `school_id` column added, backfilled from a parent/source row, indexed,
  then SET NOT NULL in a subsequent migration.

Migrations are run by `appuser` (schema DDL owner) via Flyway. The application runtime
uses `app_rt` (or the equivalent unprivileged role) for DML.

**Out of scope for this runbook:**
- `reporting.command_center_feed` and `reporting.reporting_event_inbox` — intentionally
  stay nullable (NULL = platform-wide; not per-tenant).
- `fee.fee_bands` and `fee.fee_items` — no per-tenant row scope; excluded.
- RLS extension to the now-NOT-NULL tables — this is the immediate follow-up task; the
  NOT NULL precondition (required for RLS) is now satisfied.

---

## 2. Table Inventory

### Group A — SET NOT NULL on existing `school_id` column

These tables already had a `school_id` column. The migration verifies zero NULLs exist
(pre-check below) and then applies SET NOT NULL atomically in a single migration.

| Service | Schema | Table | Migration |
|---|---|---|---|
| catalog-service | `catalog` | `catalog_orders` | `V3__tenant_key_not_null.sql` |
| catalog-service | `catalog` | `annual_plan_items` | `V3__tenant_key_not_null.sql` |
| workflow-service | `workflow` | `workflow_instances` | `V3__tenant_key_denormalize.sql` |
| firefighting-service | `firefighting` | `firefighting_requests` | `V3__tenant_key_denormalize.sql` |
| reporting-service | `reporting` | `command_center_actions` | `V7__tenant_key_not_null.sql` |

> **catalog-service** and **reporting-service** are Group-A-only services (no Group-B
> tables). Their migration applies the SET NOT NULL directly and atomically after a
> verified-zero pre-check. No Flyway `target` staging is needed for these two.

### Group B — Denormalize `school_id`, backfill, index, then SET NOT NULL

These tables required `school_id` to be added, backfilled from a related row, and indexed
before the NOT NULL constraint could be set. The two-phase rollout (§4) applies the
denormalize/backfill/index migration in Release 1 and the SET NOT NULL in Release 2.

| Service | Schema | Table | Backfill source | Backfill type | Denormalize migration | NOT NULL migration |
|---|---|---|---|---|---|---|
| firefighting-service | `firefighting` | `ff_quotations` | `firefighting.firefighting_requests` (same schema, join on `r.code = q.request_id`) | Plain `UPDATE` (same schema; no guard needed) | `V3__tenant_key_denormalize.sql` | `V4__tenant_key_not_null.sql` |
| workflow-service | `workflow` | `workflow_actions` | `workflow.workflow_instances` (same schema, join on `i.id = a.instance_id`) | Plain `UPDATE` (same schema; no guard needed) | `V3__tenant_key_denormalize.sql` | `V4__tenant_key_not_null.sql` |
| attendance-service | `attendance` | `attendance_daily` | `tenant_school.school_sections` (cross-schema) | DO-block guard (see §2.1) | `V4__tenant_key_denormalize.sql` | `V5__tenant_key_not_null.sql` |
| fee-service | `fee` | `fee_assignments` | `student.students` (cross-schema) | DO-block guard (see §2.1) | `V5__tenant_key_denormalize.sql` | `V6__tenant_key_not_null.sql` |
| fee-service | `fee` | `payment_records` | `student.students` (cross-schema) | DO-block guard (see §2.1) | `V5__tenant_key_denormalize.sql` | `V6__tenant_key_not_null.sql` |

#### Tenant-leading composite indexes added

| Table | Index name | Columns |
|---|---|---|
| `workflow.workflow_instances` | `idx_wf_instances_school_entity` | `(school_id, entity_type, entity_id)` |
| `workflow.workflow_actions` | `idx_wf_actions_school_instance` | `(school_id, instance_id)` |
| `firefighting.ff_quotations` | `idx_ff_quotations_school_request` | `(school_id, request_id)` |
| `attendance.attendance_daily` | `idx_attendance_daily_school_date` | `(school_id, attendance_date, academic_year_id)` |
| `fee.fee_assignments` | `idx_fee_assignments_school_year_student` | `(school_id, academic_year_id, student_id)` |
| `fee.payment_records` | `idx_payment_records_school_paid` | `(school_id, paid_at DESC)` |

### 2.1 Cross-Schema Backfill Guard

The two cross-schema backfills (attendance V4 ← `tenant_school.school_sections`;
fee V5 ← `student.students`) are wrapped in a `DO $$ BEGIN … END $$` anonymous block
with a `to_regclass(...)` existence check:

```sql
DO $$
BEGIN
    IF to_regclass('tenant_school.school_sections') IS NOT NULL THEN
        UPDATE attendance.attendance_daily ad
        SET school_id = ss.school_id
        FROM tenant_school.school_sections ss
        WHERE ad.section_id = ss.id AND ad.school_id IS NULL;
    END IF;
END $$;
```

In production the source schema always exists so the backfill runs normally. The guard
makes the migration safe to apply in isolated-schema test environments where the source
schema is absent. The subsequent `Vn+1` SET NOT NULL migration is the loud safety net: if
any NULL remained (i.e., the backfill was skipped), the `ALTER TABLE … SET NOT NULL` will
fail loudly at migration time, preventing a broken deployment from proceeding silently.

Same-schema backfills (firefighting `ff_quotations`, workflow `workflow_actions`) use plain
`UPDATE` statements — the source table is always present in the same schema; no guard is
needed.

---

## 3. Pre-Cutover NULL Checks

Before any SET NOT NULL migration runs against a production database, confirm zero NULLs
on every in-scope table. Run these queries directly on the Cloud SQL instance via `psql`
or the Cloud SQL Studio console.

### Group A — run before Release 1 (catalog and reporting) or before Release 2 (workflow and firefighting)

```sql
-- catalog-service
SELECT count(*) FROM catalog.catalog_orders WHERE school_id IS NULL;
SELECT count(*) FROM catalog.annual_plan_items WHERE school_id IS NULL;

-- reporting-service
SELECT count(*) FROM reporting.command_center_actions WHERE school_id IS NULL;

-- workflow-service (run after Release 1 backfill, before Release 2)
SELECT count(*) FROM workflow.workflow_instances WHERE school_id IS NULL;

-- firefighting-service (run after Release 1 backfill, before Release 2)
SELECT count(*) FROM firefighting.firefighting_requests WHERE school_id IS NULL;
```

### Group B — run after Release 1 (backfill applied), before Release 2 (SET NOT NULL)

```sql
-- firefighting-service
SELECT count(*) FROM firefighting.ff_quotations WHERE school_id IS NULL;

-- workflow-service
SELECT count(*) FROM workflow.workflow_actions WHERE school_id IS NULL;

-- attendance-service
SELECT count(*) FROM attendance.attendance_daily WHERE school_id IS NULL;

-- fee-service
SELECT count(*) FROM fee.fee_assignments WHERE school_id IS NULL;
SELECT count(*) FROM fee.payment_records WHERE school_id IS NULL;
```

**All counts must be 0 before proceeding.** If any count is non-zero, investigate and
assign the orphan rows to the correct school before allowing Release 2 to deploy. Do not
attempt a forced data fix — understand why the backfill missed those rows first.

---

## 4. Two-Phase Production Rollout

### Why two phases?

Group-B tables have a nullable window: after Release 1, the app code binds `school_id` on
every new insert, but old rows from before the migration may have NULL. Releasing the SET
NOT NULL migration (Release 2) immediately risks a migration failure if any NULL rows
remain. Splitting into two Cloud Run revisions (with a NULL-count verification gate
between them) prevents silent migration-failure rollbacks.

Group-A single-migration services (catalog, reporting) are simpler — they verify zero
NULLs in staging before Release 1 and deploy atomically. If a NULL is found in production
during the pre-check, deployment is blocked until the data is corrected.

### Flyway `target` configuration

To hold back the SET NOT NULL migrations during Release 1, set the `flyway.target`
property in each Group-B service's Cloud Run environment:

| Service | Flyway `target` for Release 1 (hold here) | Release 2 action |
|---|---|---|
| firefighting-service | `3` (holds at V3; V4 NOT NULL not yet applied) | Remove `target` cap → V4 applies |
| workflow-service | `3` (holds at V3; V4 NOT NULL not yet applied) | Remove `target` cap → V4 applies |
| attendance-service | `4` (holds at V4; V5 NOT NULL not yet applied) | Remove `target` cap → V5 applies |
| fee-service | `5` (holds at V5; V6 NOT NULL not yet applied) | Remove `target` cap → V6 applies |

Set via the Spring Boot property `spring.flyway.target=<version>` (or the env var
`SPRING_FLYWAY_TARGET=<version>`). Removing the cap (Release 2) means unsetting this env
var or setting it to `latest`.

> **catalog-service and reporting-service:** no Flyway `target` staging needed. These are
> Group-A single-migration services. Deploy atomically after confirming zero NULLs in
> the pre-check (§3).

### Release 1 — denormalize + backfill + index + app insert changes

**Services affected:** firefighting, workflow, attendance, fee (with Flyway `target`);
catalog and reporting deploy their complete single migration now.

1. Set `SPRING_FLYWAY_TARGET` per service as described in the table above.
2. Deploy the Release 1 image for each service. Flyway will apply:
   - firefighting: V3 (requests NOT NULL already present; ff_quotations ADD + backfill + index).
   - workflow: V3 (instances NOT NULL already present; workflow_actions ADD + backfill + index).
   - attendance: V4 (attendance_daily ADD + backfill + index).
   - fee: V5 (fee_assignments + payment_records ADD + backfill + indexes).
   - catalog: V3 (atomic catalog_orders + annual_plan_items SET NOT NULL — after pre-check).
   - reporting: V7 (atomic command_center_actions SET NOT NULL — after pre-check).
3. Wait for all instances of each service to reach the new revision (full rolling replace).
4. Smoke-test: confirm new inserts populate `school_id` correctly (check a sample row via
   the service's own read endpoint or directly in the database).
5. Run all NULL checks from §3. **All must return 0 before proceeding to Release 2.**

### Release 2 — apply SET NOT NULL (remove Flyway `target` cap)

**Services affected:** firefighting, workflow, attendance, fee only.

1. Unset `SPRING_FLYWAY_TARGET` (or set to `latest`) for each Group-B service.
2. Deploy Release 2 images. Flyway will apply:
   - firefighting: V4 (`ALTER TABLE ff_quotations ALTER COLUMN school_id SET NOT NULL`).
   - workflow: V4 (`ALTER TABLE workflow_actions ALTER COLUMN school_id SET NOT NULL`).
   - attendance: V5 (`ALTER TABLE attendance_daily ALTER COLUMN school_id SET NOT NULL`).
   - fee: V6 (`ALTER TABLE fee_assignments ALTER COLUMN school_id SET NOT NULL; ALTER TABLE payment_records ALTER COLUMN school_id SET NOT NULL`).
3. If any migration fails with a NOT NULL violation, **do not force-retry** — investigate
   which rows have NULL (run §3 queries) and fix them first.
4. Smoke-test: confirm tenant-filtered reads return the expected row counts; confirm
   superadmin reads return all rows.

**Rollout order:** services are independent; they can be deployed in parallel if the
pipeline supports it, or in any sequence.

---

## 5. Rollback

Rollback is done via a **forward-only migration** (do not modify already-applied files).
Add the next version in the affected service's Flyway history.

### If Release 2 has not yet been applied (SET NOT NULL not yet active)

Simply revert the app code (prior `school_id` binding changes) if needed — the column is
still nullable and the constraint has not been set. No DDL rollback required.

### If Release 2 has been applied (SET NOT NULL is active)

Add a new forward migration to DROP NOT NULL. The denormalized column and its index may
remain — they cause no harm and avoid re-backfilling if the constraint is later re-added.

```sql
-- firefighting-service: Vn__drop_tenant_key_not_null.sql
ALTER TABLE firefighting.ff_quotations ALTER COLUMN school_id DROP NOT NULL;

-- workflow-service: Vn__drop_tenant_key_not_null.sql
ALTER TABLE workflow.workflow_actions ALTER COLUMN school_id DROP NOT NULL;

-- attendance-service: Vn__drop_tenant_key_not_null.sql
ALTER TABLE attendance.attendance_daily ALTER COLUMN school_id DROP NOT NULL;

-- fee-service: Vn__drop_tenant_key_not_null.sql
ALTER TABLE fee.fee_assignments ALTER COLUMN school_id DROP NOT NULL;
ALTER TABLE fee.payment_records ALTER COLUMN school_id DROP NOT NULL;

-- catalog-service: Vn__drop_tenant_key_not_null.sql
ALTER TABLE catalog.catalog_orders ALTER COLUMN school_id DROP NOT NULL;
ALTER TABLE catalog.annual_plan_items ALTER COLUMN school_id DROP NOT NULL;

-- reporting-service: Vn__drop_tenant_key_not_null.sql
ALTER TABLE reporting.command_center_actions ALTER COLUMN school_id DROP NOT NULL;
```

After dropping NOT NULL: application-layer tenant filtering (from the service's own
`school_id` WHERE clause) remains in effect. The dropped constraint does not revert to
zero isolation — it only removes the database-enforced hard guarantee. Re-apply the
constraint following this runbook once the root cause is resolved.

**Do NOT drop the denormalized `school_id` columns or the tenant-leading indexes**
during rollback — these are required when the constraint is re-introduced and they improve
query performance regardless.

---

## 6. Verification (CI and Pre-Prod)

Each of the 6 services ships a `*TenantKeyMigrationTest` that runs the complete Flyway
migration chain (including the two-phase NOT NULL migrations) against a Testcontainers
PostgreSQL 16 instance.

| Service | Test class |
|---|---|
| catalog-service | `com.custoking.ims.catalogservice.persistence.CatalogTenantKeyMigrationTest` |
| reporting-service | `com.custoking.ims.reportingservice.persistence.ReportingTenantKeyMigrationTest` |
| firefighting-service | `com.custoking.ims.firefightingservice.persistence.FirefightingTenantKeyMigrationTest` |
| workflow-service | `com.custoking.ims.workflowservice.persistence.WorkflowTenantKeyMigrationTest` |
| attendance-service | `com.custoking.ims.attendanceservice.persistence.AttendanceTenantKeyMigrationTest` |
| fee-service | `com.custoking.ims.feeservice.persistence.FeeTenantKeyMigrationTest` |

Tests use Testcontainers (Docker required). They are automatically skipped if Docker is
not available, so `mvn test` is always safe in environments without Docker.

**Run all 6 services:**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
$services = @('catalog-service','reporting-service','firefighting-service','workflow-service','attendance-service','fee-service')
& scripts\invoke-microservice-tests.ps1 -Services $services
```

All 6 must be green before shipping Release 2 to production.

---

## 7. Scope Boundary — What Is NOT Covered

- `reporting.command_center_feed` and `reporting.reporting_event_inbox` are intentionally
  left nullable. NULL `school_id` in these tables means a platform-wide event (not tied
  to a specific school). Altering these to NOT NULL would break the platform-wide event
  semantics.
- `fee.fee_bands` and `fee.fee_items` are excluded — these are configuration tables
  without per-tenant row scope in the current schema design.
- RLS extension to the tables listed in §2 — the NOT NULL precondition is now satisfied
  on all 10 tables; applying RLS policies is the immediate follow-up work. See
  `docs/MICROSERVICE-RLS-ROLLOUT-RUNBOOK.md` for the RLS rollout procedure on the earlier
  clean tables, and follow the same pattern for the tables hardened here.
- Any table in `identity`, `tenant_school`, `student`, `billing`, `notification`, or
  `audit` schemas — not in scope for this branch.

---

## 8. Related Runbooks and Scripts

- `docs/MICROSERVICE-RLS-ROLLOUT-RUNBOOK.md` — Phase 1 RLS rollout (student, attendance, reporting clean tables)
- `docs/MICROSERVICE-ROLLBACK-RUNBOOK.md` — general service rollback playbook
- `docs/MICROSERVICE-OBSERVABILITY-RUNBOOK.md` — structured-log and alert playbook
- `scripts/audit-app-rt-privileges.ps1` — verify `app_rt` privilege posture
- `scripts/verify-microservice-migration.ps1 -RunDbAudit` — full boundary + DB audit
- `ARCHITECTURE_REVIEW.md` § MT-P1-1 — review note on tenant key standardization
