# Tenant-Key Hardening (NOT NULL `school_id` + tenant-leading indexes) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `school_id` `NOT NULL` on the in-scope tenant tables — backfilling where the column exists, denormalizing `school_id` (incl. 3 cross-schema sources) onto the tables that lack it, populating it on new inserts, and adding tenant-leading composite indexes.

**Architecture:** Per-service forward Flyway migrations (run as `appuser`, owner of all schemas, so cross-schema backfill `UPDATE`s work). Group A (column exists) → `SET NOT NULL`. Group B (no column) → add NULLABLE column + backfill from FK + index + app populates new rows, then a second migration `SET NOT NULL` (two-phase, prod-rolling-deploy-safe). Data-layer only — RLS extension is a separate follow-up.

**Tech Stack:** Postgres 15/16, Flyway, Spring `JdbcClient`, Testcontainers (`org.testcontainers:postgresql`+`:junit-jupiter`, BOM-managed), JUnit 5, Java 21 / Spring Boot 3.5.16.

**Spec:** `docs/superpowers/specs/2026-06-30-tenant-keys-design.md`

## Global Constraints

- Forward-only Flyway, per owning service, continue that service's sequence; never edit an applied migration. Migrations run as `appuser` (owns all schemas).
- Group B two-phase: `Vn` adds the column **NULLABLE** + backfill + index; the app insert change ships with `Vn`; `Vn+1` does `SET NOT NULL`. (Local/test is atomic; prod stages via Flyway `target` per the runbook.)
- Group A `SET NOT NULL` migrations **fail loudly** if any NULL remains (no backfill source) — the runbook pre-checks `count(*) WHERE school_id IS NULL` per table.
- Cross-schema backfill `UPDATE`s (attendance_daily←tenant_school.school_sections; fee_assignments/payment_records←student.students) are one-time appuser reads — comment them as such.
- App insert changes: each of the 5 methods already computes `school_id` before the INSERT — add the column + bind the in-scope value. No new lookups.
- Tenant-leading indexes: new `(school_id, …)` per the table list; leave already-tenant-leading indexes alone.
- Stay nullable (do NOT touch): `reporting.command_center_feed`, `reporting.reporting_event_inbox`. Excluded (global): `fee.fee_bands`, `fee.fee_items`.
- Testcontainers tests run as the container owner (these are migration/constraint/backfill tests, not RLS) with the Docker-availability assumption + `-Duser.timezone=UTC` surefire argLine (Windows/pgjdbc). Model the harness on `services/student-service/src/test/java/com/custoking/ims/studentservice/security/StudentRlsIntegrationTest.java` (container + Flyway setup).
- No RLS in this task. Existing Mockito suites stay green.

---

### Task 1: catalog-service — Group A NOT NULL (pilot the Group-A test harness)

**Files:**
- Create: `services/catalog-service/src/main/resources/db/migration/V3__tenant_key_not_null.sql`
- Modify: `services/catalog-service/pom.xml` (Testcontainers deps + surefire UTC)
- Test: `services/catalog-service/src/test/java/com/custoking/ims/catalogservice/persistence/CatalogTenantKeyMigrationTest.java`

`catalog_orders` and `annual_plan_items` already have a (nullable) `school_id`, the app already
sets it, and the indexes already lead with `school_id` — so this is a `SET NOT NULL` only.

- [ ] **Step 1: Write the failing migration test**

Create `CatalogTenantKeyMigrationTest.java` (Testcontainers; model the container+Flyway setup on `StudentRlsIntegrationTest`):
```java
package com.custoking.ims.catalogservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

class CatalogTenantKeyMigrationTest {
    static PostgreSQLContainer<?> PG;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        Flyway.configure().dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("catalog").defaultSchema("catalog").locations("classpath:db/migration").load().migrate();
    }

    @AfterAll static void tearDown() { if (PG != null) PG.stop(); }

    private boolean isNotNull(String table, String column) throws SQLException {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             PreparedStatement ps = c.prepareStatement(
                "SELECT is_nullable FROM information_schema.columns WHERE table_schema='catalog' AND table_name=? AND column_name=?")) {
            ps.setString(1, table); ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return "NO".equals(rs.getString(1)); }
        }
    }

    @Test void catalogOrders_schoolId_isNotNull() throws Exception { assertTrue(isNotNull("catalog_orders", "school_id")); }
    @Test void annualPlanItems_schoolId_isNotNull() throws Exception { assertTrue(isNotNull("annual_plan_items", "school_id")); }
}
```

- [ ] **Step 2: Run it — verify it fails**

Run: `./mvnw.cmd -q -f services/catalog-service/pom.xml test -Dtest=CatalogTenantKeyMigrationTest`
Expected: FAIL — testcontainers deps missing (compile) and/or `is_nullable` is `YES` (column still nullable).

- [ ] **Step 3: Add Testcontainers deps + surefire UTC to the pom**

In `services/catalog-service/pom.xml`: add (test scope, no version) `org.testcontainers:postgresql` and `org.testcontainers:junit-jupiter`; and add `-Duser.timezone=UTC` to the surefire `argLine` (preserve the existing mockito javaagent) — copy the exact form from `services/student-service/pom.xml`.

- [ ] **Step 4: Create the migration `V3__tenant_key_not_null.sql`**

```sql
-- Tenant-key hardening: catalog_orders and annual_plan_items already carry school_id
-- (set by the app) and tenant-leading indexes. Enforce NOT NULL. Fails loudly if any
-- legacy NULL remains (no FK source to backfill — these rows ARE the tenant row).
ALTER TABLE catalog.catalog_orders ALTER COLUMN school_id SET NOT NULL;
ALTER TABLE catalog.annual_plan_items ALTER COLUMN school_id SET NOT NULL;
```

- [ ] **Step 5: Run the test — verify it passes**

Run: `./mvnw.cmd -q -f services/catalog-service/pom.xml test -Dtest=CatalogTenantKeyMigrationTest` (Docker up) → PASS. Then full `…pom.xml test` → green.

- [ ] **Step 6: Commit**

```bash
git add services/catalog-service/src/main/resources/db/migration/V3__tenant_key_not_null.sql \
        services/catalog-service/pom.xml \
        services/catalog-service/src/test/java/com/custoking/ims/catalogservice/persistence/CatalogTenantKeyMigrationTest.java
git commit -m "feat(catalog): NOT NULL school_id on catalog_orders and annual_plan_items"
```

---

### Task 2: reporting-service — Group A NOT NULL on command_center_actions

**Files:**
- Create: `services/reporting-service/src/main/resources/db/migration/V7__tenant_key_not_null.sql`
- Test: `services/reporting-service/src/test/java/com/custoking/ims/reportingservice/persistence/ReportingTenantKeyMigrationTest.java`

reporting-service already has Testcontainers deps (from Task 1.3) — no pom change.

- [ ] **Step 1: Migration `V7__tenant_key_not_null.sql`**
```sql
-- command_center_actions rows are school-scoped; the platform-NULL branch is superadmin-only
-- and not expected to persist NULL rows. Enforce NOT NULL (fails loudly if any NULL remains —
-- the runbook pre-checks). command_center_feed and reporting_event_inbox stay NULLABLE (NULL =
-- platform-wide) and are intentionally NOT altered here.
ALTER TABLE reporting.command_center_actions ALTER COLUMN school_id SET NOT NULL;
```

- [ ] **Step 2: Test `ReportingTenantKeyMigrationTest.java`** — model on Task 1's test (schema `reporting`): assert `command_center_actions.school_id` is `NOT NULL` (information_schema), AND assert `command_center_feed.school_id` and `reporting_event_inbox.school_id` REMAIN nullable (`is_nullable='YES'`) — proving the deferred tables are untouched.

- [ ] **Step 3: Run** `…-Dtest=ReportingTenantKeyMigrationTest` (Docker) → PASS; full suite → green.

- [ ] **Step 4: Commit** — `git add` the migration + test; `git commit -m "feat(reporting): NOT NULL school_id on command_center_actions (feed/inbox stay nullable)"`

---

### Task 3: firefighting-service — Group A (firefighting_requests) + Group B (ff_quotations denorm)

**Files:**
- Create: `services/firefighting-service/src/main/resources/db/migration/V3__tenant_key_denormalize.sql`
- Create: `services/firefighting-service/src/main/resources/db/migration/V4__tenant_key_not_null.sql`
- Modify: `services/firefighting-service/pom.xml` (Testcontainers — already added in Phase 1.3? confirm; if not, add deps + surefire UTC)
- Modify: `services/firefighting-service/src/main/java/com/custoking/ims/firefightingservice/persistence/FirefightingReadRepository.java` (`addQuotation` INSERT)
- Test: `services/firefighting-service/src/test/java/com/custoking/ims/firefightingservice/persistence/FirefightingTenantKeyMigrationTest.java`

NOTE: firefighting-service did NOT receive Testcontainers in Phase 1.3 — add the deps + surefire UTC (as Task 1 Step 3).

- [ ] **Step 1: Migration `V3__tenant_key_denormalize.sql`**
```sql
-- Group A: firefighting_requests already has a (nullable) school_id set by the app.
ALTER TABLE firefighting.firefighting_requests ALTER COLUMN school_id SET NOT NULL;

-- Group B: ff_quotations derives its tenant from its parent request (same schema).
ALTER TABLE firefighting.ff_quotations ADD COLUMN IF NOT EXISTS school_id BIGINT;
UPDATE firefighting.ff_quotations q
   SET school_id = r.school_id
  FROM firefighting.firefighting_requests r
 WHERE r.code = q.request_id AND q.school_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_ff_quotations_school_request
    ON firefighting.ff_quotations (school_id, request_id);
```
(firefighting_requests has a NOT NULL school_id only after this — but ff_quotations.school_id stays nullable until V4, so the app can populate it during rollout.)

- [ ] **Step 2: Migration `V4__tenant_key_not_null.sql`**
```sql
ALTER TABLE firefighting.ff_quotations ALTER COLUMN school_id SET NOT NULL;
```

- [ ] **Step 3: App change — `FirefightingReadRepository.addQuotation`**

Read the `addQuotation` method (~line 167). It calls `requestMap(code)` first (the request map holds `schoolId`). Add `school_id` to the `ff_quotations` INSERT column list and bind the parent's school: extract `Long schoolId = longValue(current.get("schoolId"), null)` (using the already-loaded `current`/request map and the repo's existing `longValue` helper) and add `school_id`/`:schoolId` to the INSERT. (Match the repo's JdbcClient `.param(...)` style.)

- [ ] **Step 4: Test `FirefightingTenantKeyMigrationTest.java`**

Two parts (model container/Flyway on Task 1):
- **Constraint + index** (full migrate): assert `firefighting_requests.school_id` and `ff_quotations.school_id` are `NOT NULL`; assert index `idx_ff_quotations_school_request` exists (`pg_indexes`).
- **Backfill** (separate method): migrate with Flyway `target('3')` to the add-column version, INSERT a `firefighting_requests` row (school_id=10) and an `ff_quotations` row with `school_id` NULL referencing it, run the backfill `UPDATE` (copy the V3 UPDATE), assert the quotation's `school_id` is now 10. Use a fresh container for this method (or a dedicated schema) so the target-capped migration is isolated.

- [ ] **Step 5: Run** `…-Dtest=FirefightingTenantKeyMigrationTest` (Docker) → PASS; full suite → green (existing firefighting unit tests unaffected).

- [ ] **Step 6: Commit** — `git add` the 2 migrations, pom, repo, test; `git commit -m "feat(firefighting): NOT NULL school_id on requests; denormalize school_id onto ff_quotations"`

---

### Task 4: workflow-service — Group A (workflow_instances + entity index) + Group B (workflow_actions denorm)

**Files:**
- Create: `services/workflow-service/src/main/resources/db/migration/V3__tenant_key_denormalize.sql`
- Create: `services/workflow-service/src/main/resources/db/migration/V4__tenant_key_not_null.sql`
- Modify: `services/workflow-service/pom.xml` (Testcontainers deps + surefire UTC)
- Modify: `services/workflow-service/src/main/java/com/custoking/ims/workflowservice/persistence/WorkflowReadRepository.java` (`recordAction` + its 5 callers)
- Test: `services/workflow-service/src/test/java/com/custoking/ims/workflowservice/persistence/WorkflowTenantKeyMigrationTest.java`

- [ ] **Step 1: Migration `V3__tenant_key_denormalize.sql`**
```sql
-- Group A: workflow_instances already carries a (nullable) school_id set by the app.
ALTER TABLE workflow.workflow_instances ALTER COLUMN school_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_wf_instances_school_entity
    ON workflow.workflow_instances (school_id, entity_type, entity_id);

-- Group B: workflow_actions derives its tenant from its parent instance (same schema).
ALTER TABLE workflow.workflow_actions ADD COLUMN IF NOT EXISTS school_id BIGINT;
UPDATE workflow.workflow_actions a
   SET school_id = i.school_id
  FROM workflow.workflow_instances i
 WHERE i.id = a.instance_id AND a.school_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_wf_actions_school_instance
    ON workflow.workflow_actions (school_id, instance_id);
```

- [ ] **Step 2: Migration `V4__tenant_key_not_null.sql`**
```sql
ALTER TABLE workflow.workflow_actions ALTER COLUMN school_id SET NOT NULL;
```

- [ ] **Step 3: App change — `WorkflowReadRepository.recordAction` + callers**

`recordAction(Long instanceId, int stepOrder, String action, Map<String,Object> request)` (line 225) INSERTs into `workflow_actions(instance_id, step_order, action, actor_id, actor_email, notes)`. Add a `Long schoolId` parameter, add `school_id` to the INSERT column list and `:schoolId` to the VALUES + `.param("schoolId", schoolId)`. Its callers (`submit`/`approve`/`reject`/`cancel`/`complete`, lines ~129/143/168/…) each call `instanceMap(instanceId)`/hold the `instance` map first — pass `longValue(instance.get("schoolId"), null)` to `recordAction(...)`. (Read each caller; the instance map variable is in scope at each call.)

- [ ] **Step 4: Test `WorkflowTenantKeyMigrationTest.java`** — like Task 3's: constraint test (full migrate: `workflow_instances.school_id` + `workflow_actions.school_id` NOT NULL; indexes `idx_wf_instances_school_entity`, `idx_wf_actions_school_instance` exist) + backfill test (Flyway `target('3')`, seed instance school=10 + action with NULL school referencing it, run the V3 backfill UPDATE, assert action.school_id=10).

- [ ] **Step 5: Run** `…-Dtest=WorkflowTenantKeyMigrationTest` (Docker) → PASS; full suite → green.

- [ ] **Step 6: Commit** — `git add` migrations, pom, repo, test; `git commit -m "feat(workflow): NOT NULL school_id on instances (+entity index); denormalize onto workflow_actions"`

---

### Task 5: attendance-service — Group B attendance_daily (cross-schema backfill)

**Files:**
- Create: `services/attendance-service/src/main/resources/db/migration/V4__tenant_key_denormalize.sql`
- Create: `services/attendance-service/src/main/resources/db/migration/V5__tenant_key_not_null.sql`
- Modify: `services/attendance-service/src/main/java/com/custoking/ims/attendanceservice/persistence/AttendanceReadRepository.java` (`saveDailyAttendance`, `upsertDaily`)
- Test: `services/attendance-service/src/test/java/com/custoking/ims/attendanceservice/persistence/AttendanceTenantKeyMigrationTest.java`

attendance-service already has Testcontainers (Phase 1.3) — no pom change.

- [ ] **Step 1: Migration `V4__tenant_key_denormalize.sql`**
```sql
-- attendance_daily has no school_id; derive it from its section's owning school.
-- CROSS-SCHEMA backfill: reads tenant_school.school_sections (one-time, appuser owns all schemas).
ALTER TABLE attendance.attendance_daily ADD COLUMN IF NOT EXISTS school_id BIGINT;
UPDATE attendance.attendance_daily ad
   SET school_id = ss.school_id
  FROM tenant_school.school_sections ss
 WHERE ss.id = ad.section_id AND ad.school_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_attendance_daily_school_date
    ON attendance.attendance_daily (school_id, attendance_date, academic_year_id);
```

- [ ] **Step 2: Migration `V5__tenant_key_not_null.sql`**
```sql
ALTER TABLE attendance.attendance_daily ALTER COLUMN school_id SET NOT NULL;
```

- [ ] **Step 3: App change — `AttendanceReadRepository.saveDailyAttendance` + `upsertDaily`**

`upsertDaily` (~line 563) INSERTs into `attendance_daily`; `saveDailyAttendance` (~line 269) inserts directly. Both call sites already have the section's `schoolId` in scope (`sectionSchoolId`/`requestedSchoolId` from `sectionRecord(sectionId)`). Add `school_id` to the `attendance_daily` INSERT column list + bind the in-scope school. For `upsertDaily`, add a `Long schoolId` param and pass it from `saveSectionRegister` (line ~319) and `submitAttendanceSection` (line ~401), where `sectionSchoolId` is in scope. (Read the methods to match the JdbcClient `.param` style + the ON CONFLICT clause if present — keep it.)

- [ ] **Step 4: Test `AttendanceTenantKeyMigrationTest.java`**
- **Constraint + index** (full migrate): `attendance_daily.school_id` is `NOT NULL`; index `idx_attendance_daily_school_date` exists.
- **Cross-schema backfill** (separate method): Flyway `target('4')` to the add-column version; **create + seed the source** `tenant_school.school_sections` (the schema/table won't exist in the attendance container — create a minimal `tenant_school.school_sections(id VARCHAR PRIMARY KEY, school_id BIGINT)` and insert `('sec1', 10)`); insert an `attendance_daily` row (read its V1 NOT NULL columns) with `section_id='sec1'` and `school_id` NULL; run the V4 backfill `UPDATE`; assert the row's `school_id` is now 10. (Document that in prod `tenant_school.school_sections` already exists; the test creates a minimal stand-in.)

- [ ] **Step 5: Run** `…-Dtest=AttendanceTenantKeyMigrationTest` (Docker) → PASS; full suite → green.

- [ ] **Step 6: Commit** — `git add` migrations, repo, test; `git commit -m "feat(attendance): denormalize school_id onto attendance_daily (cross-schema backfill from tenant_school)"`

---

### Task 6: fee-service — Group B fee_assignments + payment_records (cross-schema backfill)

**Files:**
- Create: `services/fee-service/src/main/resources/db/migration/V5__tenant_key_denormalize.sql`
- Create: `services/fee-service/src/main/resources/db/migration/V6__tenant_key_not_null.sql`
- Modify: `services/fee-service/pom.xml` (Testcontainers deps + surefire UTC)
- Modify: `services/fee-service/src/main/java/com/custoking/ims/feeservice/persistence/FeeReadRepository.java` (`assignFeePlan`, `recordPayment`)
- Test: `services/fee-service/src/test/java/com/custoking/ims/feeservice/persistence/FeeTenantKeyMigrationTest.java`

- [ ] **Step 1: Migration `V5__tenant_key_denormalize.sql`**
```sql
-- fee_assignments and payment_records have no school_id; derive it from the student's school.
-- CROSS-SCHEMA backfill: reads student.students (one-time, appuser owns all schemas).
ALTER TABLE fee.fee_assignments ADD COLUMN IF NOT EXISTS school_id BIGINT;
UPDATE fee.fee_assignments fa
   SET school_id = s.school_id
  FROM student.students s
 WHERE s.id = fa.student_id AND fa.school_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_fee_assignments_school_year_student
    ON fee.fee_assignments (school_id, academic_year_id, student_id);

ALTER TABLE fee.payment_records ADD COLUMN IF NOT EXISTS school_id BIGINT;
UPDATE fee.payment_records pr
   SET school_id = s.school_id
  FROM student.students s
 WHERE s.id = pr.student_id AND pr.school_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_payment_records_school_paid
    ON fee.payment_records (school_id, paid_at DESC);
```

- [ ] **Step 2: Migration `V6__tenant_key_not_null.sql`**
```sql
ALTER TABLE fee.fee_assignments ALTER COLUMN school_id SET NOT NULL;
ALTER TABLE fee.payment_records ALTER COLUMN school_id SET NOT NULL;
```

- [ ] **Step 3: App change — `FeeReadRepository.assignFeePlan` + `recordPayment`**

`assignFeePlan` (~line 489) and `recordPayment` (~line 560) each already compute `Long schoolId = studentSchoolId(studentId)` earlier (lines ~443 / ~524). Add `school_id` to the respective INSERT column list and bind `:schoolId` (`.param("schoolId", schoolId)`), matching the repo's JdbcClient style.

- [ ] **Step 4: Test `FeeTenantKeyMigrationTest.java`** — add Testcontainers pom deps + surefire UTC first (Task 1 Step 3).
- **Constraint + index** (full migrate, schema `fee`): `fee_assignments.school_id` + `payment_records.school_id` are `NOT NULL`; indexes `idx_fee_assignments_school_year_student`, `idx_payment_records_school_paid` exist.
- **Cross-schema backfill** (separate method): Flyway `target('5')`; create minimal source `student.students(id BIGINT PRIMARY KEY, school_id BIGINT)` + insert `(1, 10)`; insert a `fee_assignments` row (read its V1 NOT NULL columns) with `student_id=1`, `school_id` NULL; run the V5 `fee_assignments` backfill `UPDATE`; assert `school_id`=10. (Same for one `payment_records` row.)

- [ ] **Step 5: Run** `…-Dtest=FeeTenantKeyMigrationTest` (Docker) → PASS; full suite → green.

- [ ] **Step 6: Commit** — `git add` migrations, pom, repo, test; `git commit -m "feat(fee): denormalize school_id onto fee_assignments and payment_records (cross-schema backfill from student)"`

---

### Task 7: Rollout runbook + verification gate

**Files:**
- Create: `docs/MICROSERVICE-TENANT-KEY-ROLLOUT-RUNBOOK.md`
- Modify: `ARCHITECTURE_REVIEW.md` (note MT-P1-1 progress)

- [ ] **Step 1: Write `docs/MICROSERVICE-TENANT-KEY-ROLLOUT-RUNBOOK.md`** covering:
  - The table list + per-table action (Group A NOT NULL; Group B denormalize, incl. the 3 cross-schema backfills).
  - **Pre-cutover NULL check** (per Group-A table + each Group-B table after Release 1): `SELECT count(*) FROM <schema>.<table> WHERE school_id IS NULL;` — must be 0 before the `SET NOT NULL` migration; if non-zero, clean/assign first.
  - **Two-phase deploy:** Release 1 applies the denormalize/backfill/index migrations + app insert changes but holds the `SET NOT NULL` migrations back via Flyway `target` (per service: catalog stop-before-V3? — catalog is Group-A single migration, document it deploys atomically with a verified-zero pre-check; firefighting `target=V3`, workflow `target=V3`, attendance `target=V4`, fee `target=V5`). Verify zero NULLs. Release 2 removes the `target` cap so the `SET NOT NULL` migrations (firefighting V4, workflow V4, attendance V5, fee V6) apply.
  - **Rollback:** `ALTER TABLE … ALTER COLUMN school_id DROP NOT NULL;` per table; the denormalized columns + indexes may remain.
  - Scope note: data-layer only; RLS extension to these now-NOT-NULL tables is the immediate follow-up; `command_center_feed`/`reporting_event_inbox` stay nullable; `fee_bands`/`fee_items` excluded.

- [ ] **Step 2: Update `ARCHITECTURE_REVIEW.md`** — near the MT-P1-1 entry (search `MT-P1-1`), append a dated note (2026-06-30): `school_id` is now NOT NULL on the in-scope tenant tables (catalog_orders, annual_plan_items, workflow_instances, firefighting_requests, command_center_actions) and denormalized + NOT NULL on attendance_daily, ff_quotations, workflow_actions, fee_assignments, payment_records, with tenant-leading indexes; RLS extension to follow. Do NOT rewrite existing content.

- [ ] **Step 3: Verification** — Docker up:
```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
powershell -ExecutionPolicy Bypass -File scripts\invoke-microservice-tests.ps1 -Services catalog-service,reporting-service,firefighting-service,workflow-service,attendance-service,fee-service
```
Expected: all 6 green, including the new `*TenantKeyMigrationTest` (they run because Docker is present).

- [ ] **Step 4: Commit** — `git add docs/MICROSERVICE-TENANT-KEY-ROLLOUT-RUNBOOK.md ARCHITECTURE_REVIEW.md`; `git commit -m "docs(ops): tenant-key rollout runbook + architecture-review update"`

---

## Self-Review

**Spec coverage:**
- Group A NOT NULL (5 tables) → Tasks 1 (catalog ×2), 2 (reporting command_center_actions), 3 (firefighting_requests), 4 (workflow_instances). ✓
- Group B denormalize (5 tables) → Tasks 3 (ff_quotations), 4 (workflow_actions), 5 (attendance_daily), 6 (fee_assignments, payment_records). ✓
- Cross-schema backfills (attendance_daily, fee_*) → Tasks 5, 6 (commented; tests seed the source schema). ✓
- Tenant-leading indexes (workflow_instances entity + 5 denorm) → Tasks 3–6. ✓
- 5 app insert changes → Tasks 3 (addQuotation), 4 (recordAction+callers), 5 (saveDailyAttendance+upsertDaily), 6 (assignFeePlan+recordPayment). ✓
- Two-phase (Vn nullable+backfill, Vn+1 SET NOT NULL) + runbook target staging → Tasks 3–6 + Task 7. ✓
- Group-A "fail loudly on NULL" + pre-check → Tasks + Task 7 runbook. ✓
- Stay-nullable feed/inbox asserted untouched → Task 2 test. ✓
- fee_bands/fee_items excluded; no RLS this task → Global Constraints + Task 7 note. ✓
- Testcontainers added to catalog/firefighting/workflow/fee (not yet present); attendance/reporting already have it → Tasks 1/3/4/6. ✓

**Placeholder scan:** none — full migration SQL per table; the app-insert changes name the exact method, the in-scope `school_id` variable, and the recipe (add column + bind); tests give the constraint/index assertions and the Flyway-`target` backfill method. (Repo line numbers are approximate guides — the implementer reads the actual method, which the steps instruct.)

**Type/name consistency:** migration versions (catalog V3; reporting V7; firefighting V3/V4; workflow V3/V4; attendance V4/V5; fee V5/V6) = each service's latest+1/+2; index names unique per table; `school_id BIGINT`; test classes `<Svc>TenantKeyMigrationTest`. Consistent across tasks.

## Risks
- Group-A legacy NULLs block `SET NOT NULL` → runbook pre-check (Task 7).
- Cross-schema test must create a stand-in source schema (the source table isn't in the service's own migrations) → Tasks 5/6 Step 4 spell this out.
- Rolling-deploy NULL window on denorm tables → two-phase via Flyway `target` (Task 7).
- `CREATE INDEX` lock → school-scale; acceptable; not `CONCURRENTLY` (Flyway runs migrations transactionally).
