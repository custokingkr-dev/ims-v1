# Design — Tenant-Key Hardening: NOT NULL `school_id` + tenant-leading indexes (Phase 1, Task 1.4)

> Review ID: `MT-P1-1`. Source of truth: `ARCHITECTURE_REVIEW.md` §7.3.
> Parent program plan: `docs/superpowers/plans/2026-06-28-architecture-remediation-program.md`.
> Depends on: Task 1.3 RLS (the clean tables are already RLS-enforced). This branch is stacked on `phase1-rls`.
> Status: **approved design** → next step is the bite-sized implementation plan.

## Problem

Several tenant-scoped tables either carry a **nullable** `school_id` (so a missing predicate
silently escapes tenant scope) or have **no `school_id` column at all** (tenant derived via a
FK, sometimes cross-schema). This blocks both reliable application-layer scoping and the RLS
backstop (Task 1.3) — RLS needs a real, NOT NULL `school_id` on the table. This task hardens
the tenant key: backfill + `NOT NULL` where the column exists, **denormalize** `school_id`
onto the tables that lack it, and make the relevant indexes tenant-leading.

This is **data-layer only**. Extending RLS to these newly-hardened tables is the immediate
follow-up (it reuses the Task 1.3 mechanism); the app-layer scoping (Task 1.2) keeps these
tables protected until then.

## Goals / Non-goals

**Goals**
- Make `school_id` `NOT NULL` on the in-scope tenant tables (backfill first).
- Denormalize `school_id` onto the 5 tables that lack it; backfill from the FK source
  (including 3 cross-schema sources); populate it on new inserts in the app.
- Add tenant-leading `(school_id, …)` composite indexes where missing.
- A prod-safe (rolling-deploy-safe) rollout for the `NOT NULL` cutover.

**Non-goals (deferred / excluded)**
- **RLS extension** to these tables — the immediate follow-up task (adds the
  `TenantAwareDataSource` mechanism to catalog/workflow/firefighting/fee + policies).
- `command_center_feed` and `reporting_event_inbox` — **stay nullable** (NULL is a meaningful
  "platform-wide / no-school-context" value).
- `fee_bands`, `fee_items` — **excluded** (global pricing catalog tied only to
  `academic_year_id`; no school scope).
- Partitioning / query tuning beyond tenant-leading indexes — Task 3.2.

## Decisions (resolved during brainstorming)

1. **Scope: data-layer hardening only.** RLS extension is a clean follow-up once columns are
   `NOT NULL`. Lower risk (no new RLS surface this task).
2. **All 10 tables now, including the 3 cross-schema backfills.** Flyway runs as `appuser`
   (owns every schema), so a cross-schema backfill `UPDATE` is permitted; it is a one-time
   migration read, not a runtime cross-schema dependency.
3. **Reporting command-center NULL handling:** `command_center_feed` + `reporting_event_inbox`
   stay nullable; `command_center_actions` → backfill + `NOT NULL` (its rows are school-scoped;
   the platform-NULL branch is superadmin-only and rare — the migration verifies no
   unexpected NULL rows remain before the constraint).
4. **NOT-NULL rollout: two-phase** (approach A) — denorm columns are added NULLABLE +
   backfilled and the populating app code ships first; `SET NOT NULL` is staged so prod
   applies it only after the populating code is fully rolled out (Flyway `target`). Avoids the
   rolling-deploy window where an old instance inserts a row without `school_id`.

## In-scope tables

### Group A — backfill + `NOT NULL` (column already exists; NULL = legacy)

| Schema.table | Backfill | Index (tenant-leading) | Migration |
|---|---|---|---|
| `catalog.catalog_orders` | none (the order *is* the tenant row — no FK source; verify/clean NULLs) | already `(school_id, …)` | catalog V3 |
| `catalog.annual_plan_items` | none (verify/clean NULLs) | already `(school_id, …)` | catalog V3 |
| `workflow.workflow_instances` | none (verify/clean NULLs) | **new** `(school_id, entity_type, entity_id)` | workflow V3 |
| `firefighting.firefighting_requests` | none (verify/clean NULLs) | already `(school_id, …)` | firefighting V3 |
| `reporting.command_center_actions` | none (verify no platform-NULL rows) | already `(school_id, status, urgency)` | reporting V7 |

Group A tables have no FK to a parent school, so legacy NULLs cannot be backfilled. In this
codebase the app has set `school_id` since the microservice split, so NULL rows are not
expected. The `SET NOT NULL` migration **fails loudly** if any NULL remains (a deploy blocker,
not data loss); the runbook includes a pre-cutover `SELECT count(*) … WHERE school_id IS NULL`
check per table so operators clean/assign before deploying.

### Group B — denormalize: add `school_id` + backfill + `NOT NULL` + index

| Schema.table | Backfill source | Cross-schema? | App insert site (school_id already in scope) | New index | Migration |
|---|---|---|---|---|---|
| `attendance.attendance_daily` | `section_id → tenant_school.school_sections.school_id` | **yes** | `AttendanceReadRepository.saveDailyAttendance`, `upsertDaily` | `(school_id, attendance_date, academic_year_id)` | attendance V4/V5 |
| `firefighting.ff_quotations` | `request_id → firefighting.firefighting_requests.school_id` | no | `FirefightingReadRepository.addQuotation` | `(school_id, request_id)` | firefighting V3/V4 |
| `workflow.workflow_actions` | `instance_id → workflow.workflow_instances.school_id` | no | `WorkflowReadRepository.recordAction` | `(school_id, instance_id)` | workflow V3/V4 |
| `fee.fee_assignments` | `student_id → student.students.school_id` | **yes** | `FeeReadRepository.assignFeePlan` | `(school_id, academic_year_id, student_id)` | fee V5/V6 |
| `fee.payment_records` | `student_id → student.students.school_id` | **yes** | `FeeReadRepository.recordPayment` | `(school_id, paid_at DESC)` | fee V5/V6 |

Each Group-B table uses **two** migrations: `Vn` (add NULLABLE `school_id` + backfill from the
FK source + create the index) and `Vn+1` (`SET NOT NULL`). The app insert change ships with
`Vn`. Prod applies `Vn` first (rolls out), then `Vn+1`.

## Components / changes

### 1. Flyway migrations (forward-only, per owning service, run as `appuser`)

- **Group A** (one migration per service block): `UPDATE`-clean is not applicable (no source);
  `ALTER TABLE <schema>.<table> ALTER COLUMN school_id SET NOT NULL;` Continue each service's
  sequence (catalog V3, workflow V3, firefighting V3, reporting V7).
- **Group B** (two migrations per table):
  - `Vn`: `ALTER TABLE … ADD COLUMN school_id BIGINT;` then
    `UPDATE <t> SET school_id = (SELECT <src>.school_id FROM <src> WHERE <fk-join>)` (cross-schema
    where noted — comment the cross-schema read); then
    `CREATE INDEX … ON <t> (school_id, …);`.
  - `Vn+1`: `ALTER TABLE … ALTER COLUMN school_id SET NOT NULL;`.
- Cross-schema backfill UPDATEs are explicitly commented as one-time, appuser-owned reads.

### 2. Application insert-site changes (5 methods, 3 services)

Each method already computes `school_id` before the INSERT (from `sectionRecord`/`requestMap`/
`instanceMap`/`studentSchoolId`). The change adds the `school_id` column + bind to the INSERT
(and, for `WorkflowReadRepository.recordAction`, a `schoolId` parameter passed by its 5 callers
which each already hold the instance's school). These ship with the Group-B `Vn` (column-add)
migration so new rows populate `school_id` while it is still nullable.

### 3. Tenant-leading indexes

The new `(school_id, …)` indexes above. Existing already-tenant-leading indexes are left as-is.

## Data flow (unchanged at runtime)

```
request → resolveSchoolId (Task 1.2) → repo INSERT now includes school_id (Group B) / already did (Group A)
migration (appuser, owns all schemas) → backfill school_id from the FK source (incl. cross-schema) → SET NOT NULL (phase 2)
```

## Rollout (prod, two-phase) — runbook `docs/MICROSERVICE-TENANT-KEY-ROLLOUT-RUNBOOK.md`

1. **Pre-check (Group A):** per table, `SELECT count(*) FROM <schema>.<table> WHERE school_id IS NULL`.
   If non-zero, clean/assign those rows before deploying (a `SET NOT NULL` migration will
   otherwise fail the deploy). Document the expected-zero result.
2. **Release 1:** deploy the branch up to the **column-add/backfill/index** migrations and the
   app insert changes — *excluding* the `SET NOT NULL` migrations. Use Flyway `target` (e.g.
   `_FLYWAY_TARGET=<last-backfill-version>` per service) so the `SET NOT NULL` migrations are
   not yet applied. New rows now populate `school_id`; existing rows are backfilled.
3. **Verify:** `SELECT count(*) … WHERE school_id IS NULL` returns 0 on every in-scope table.
4. **Release 2:** remove the Flyway `target` cap (or deploy the follow-up) so the `SET NOT NULL`
   migrations apply. Group A constraints apply here too.
5. **Rollback:** `ALTER TABLE … ALTER COLUMN school_id DROP NOT NULL;` (forward migration)
   restores nullability; the denormalized column + index can remain (harmless).

Local/Testcontainers is atomic (single bring-up) — the two-phase split is a prod-only concern.

## Testing strategy

- **New: Testcontainers migration/backfill tests** per touched service. The infra exists in
  attendance/reporting (Task 1.3); add it (testcontainers deps + `-Duser.timezone=UTC`) to
  **catalog, workflow, firefighting, fee**. Each test:
  - runs the service's Flyway against a fresh `postgres:16` (as owner);
  - for **same-schema** backfills (ff_quotations, workflow_actions): seed a parent row +
    child row (pre-denorm) is N/A on a fresh DB (migrations run before seed) — instead, after
    migrate, INSERT a parent + child via the app pattern and assert the child's `school_id`
    matches the parent; and assert the column is `NOT NULL` (a NULL insert is rejected) and the
    index exists (`pg_indexes`);
  - for **cross-schema** backfills (attendance_daily, fee_assignments, payment_records): the
    test must create the **source** schema/table too (`tenant_school.school_sections` /
    `student.students`) and seed it, then exercise the backfill `UPDATE` directly on a seeded
    pre-denorm row to assert it populates `school_id` from the cross-schema source. (Run the
    backfill SQL against a manually-seeded legacy row, since fresh-migrate has no legacy rows.)
  - assert the new index is present and tenant-leading.
- **Unit tests** for the changed repository insert methods (Mockito where they already are) —
  assert the INSERT binds `school_id`.
- Existing Mockito suites stay green.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Rolling-deploy NULL-insert window when adding `NOT NULL` | Two-phase rollout (column nullable + code first, `SET NOT NULL` second via Flyway `target`) |
| Group-A legacy NULL rows block the `NOT NULL` migration | Runbook pre-check + clean before deploy; migration fails loudly (no data loss) |
| Cross-schema backfill couples schemas | One-time migration read as `appuser` (owns all schemas); not a runtime dependency; commented |
| `command_center_actions` has intentional platform-NULL rows | Verify count of NULL rows in the runbook pre-check; if any are legitimate platform actions, keep it nullable (fall back to the feed/inbox treatment) |
| Backfill picks wrong school via a stale/missing FK | Backfill joins on the existing FK; rows with a missing parent surface as remaining NULL → caught by the verify step before `SET NOT NULL` |
| Index build lock on large tables | School-scale data; build in a low-traffic window; `CREATE INDEX` (optionally `CONCURRENTLY` outside a transaction — note Flyway transactional behavior) |

## Open items (deferred, not blocking)

- **RLS extension** to the now-NOT-NULL tables (immediate follow-up): add `TenantAwareDataSource`
  + `RuntimeDbRoleGuard` to catalog/workflow/firefighting/fee, RLS policies on Group A+B tables,
  and a NULL-allowing policy on `command_center_feed`.
- `command_center_feed` / `reporting_event_inbox` remain nullable by design.
