# RLS Extension to the Tenant-Key (Task 1.4) Tables — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the RLS backstop established in Task 1.3 onto the 10 tenant tables that Task 1.4 made `NOT NULL school_id` — adding the per-request-GUC `TenantAwareDataSource` to the four services that lack it, an `enable_rls` Flyway migration per service, and an `app_rt` Testcontainers isolation proof per service.

**Architecture:** Reuse the proven Task 1.3 mechanism verbatim. Each in-scope service gets (1) a `TenantAwareDataSource` (copied) that sets `app.current_school_id` + `app.bypass_rls` from `TenantContext` on every connection borrow — already present in attendance/reporting, copied into catalog/firefighting/workflow/fee; (2) a forward Flyway `V<n>__enable_rls.sql` adding `ENABLE ROW LEVEL SECURITY` + a `tenant_isolation` policy on its NOT-NULL `school_id` tables; (3) a Testcontainers integration test proving isolation **as `app_rt`**. Data-layer NOT NULL + denormalization is already done (Task 1.4); this plan only adds the DB-enforced policy.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring `JdbcClient`, `DelegatingDataSource` + a `BeanPostProcessor`, Flyway, Postgres 15/16, Testcontainers (`org.testcontainers:postgresql` + `:junit-jupiter`, BOM-managed — **already present in all six poms**), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-06-29-rls-design.md` (the Task 1.3 design — this plan applies its "Open items → extend RLS after 1.4" follow-up). Seed notes: `docs/superpowers/plans/2026-06-30-rls-backstop.md` → "Follow-up — RLS extension to the Task-1.4 tables".

## Global Constraints

- **GUC contract:** `app.current_school_id` = caller's school id text (empty/unset ⇒ no tenant); `app.bypass_rls` = `'on'` only for a gateway-verified superadmin, else `'off'`.
- **Policy form (every in-scope table), verbatim:**
  ```sql
  ALTER TABLE <schema>.<table> ENABLE ROW LEVEL SECURITY;
  DROP POLICY IF EXISTS tenant_isolation ON <schema>.<table>;
  CREATE POLICY tenant_isolation ON <schema>.<table>
    USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
                OR current_setting('app.bypass_rls', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
                OR current_setting('app.bypass_rls', true) = 'on');
  ```
  Use `ENABLE ROW LEVEL SECURITY` (NOT `FORCE`) so the owner (`appuser`) bypasses for Flyway/seed; `app_rt` (non-owner, `NOBYPASSRLS`) is subject.
- **GUC set on every connection borrow** by `TenantAwareDataSource` (already designed; copied per service). No repo/controller changes.
- **Copied per-service — no `services/common` module.** Security files live in `com.custoking.ims.<service>.security` alongside the existing `TenantContext`.
- **Forward-only Flyway, per owning service, continue that service's sequence.** Never edit an applied migration. Migrations run as `appuser` (owner) — `FLYWAY_*` creds, separate from the app pool.
- **`TenantContext` API (already present in all six services):** `TenantContext.get()` (never null), `.schoolId()` (Long, nullable), `.isSuperAdmin()` (boolean), `.set(...)`, `.clear()`. Constructor used by tests: `new TenantContext(Long userId, String email, String role, Long schoolId, Object extra)`.
- **RLS integration tests run as `app_rt`** (never the owner — owner silently bypasses and falsely passes), guarded by `Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), ...)` so `mvn test` stays green without Docker and runs where present.
- **Every in-scope table gets its OWN isolation assertion** (decided 2026-06-30, overriding the earlier "one count-target table per service"). For a multi-table service, the test must seed AND assert each table — at minimum a `schoolA_seesOnlyItsRows`-style count proving the policy filters (and, for the parent table, the `noContext→0` + `WITH CHECK` fail-closed proofs). Rationale: a malformed/missing policy on the *second* (denormalized child) table — `ff_quotations`, `workflow_actions`, `payment_records`, `annual_plan_items` — would otherwise pass the suite silently. The child assertion can reuse the same `app_rt` datasource and seed in `setUp()` (respect the child's FK to its parent).
- **CONTEXTLESS-WRITER GATE (per table, mandatory):** RLS breaks any write path that runs without a `TenantContext` (school or superadmin) — the GUC is unset, so `WITH CHECK` rejects the INSERT and reads return 0 rows. Before enabling RLS on a table, the task MUST verify every writer of that table runs under a request `TenantContext` or an explicit system context. **Pub/Sub event consumers and background projections are the prime suspects.** If a contextless writer exists and cannot trivially be given a context, DEFER that table (escalate) rather than forcing RLS — exactly as `command_center_feed`/`reporting_event_inbox` were deferred.
- **In-scope tables (all `NOT NULL school_id` after Task 1.4):**

  | Service | Schema | Tables | Latest Flyway → new | Has datasource? |
  |---|---|---|---|---|
  | catalog | `catalog` | `catalog_orders`, `annual_plan_items` | V3 → `V4__enable_rls.sql` | **No — copy in** |
  | firefighting | `firefighting` | `firefighting_requests`, `ff_quotations` | V4 → `V5__enable_rls.sql` | **No — copy in** |
  | workflow | `workflow` | `workflow_instances`, `workflow_actions` | V4 → `V5__enable_rls.sql` | **No — copy in** |
  | fee | `fee` | `fee_assignments`, `payment_records` | V6 → `V7__enable_rls.sql` | **No — copy in** |
  | attendance | `attendance` | `attendance_daily` | V5 → `V6__enable_rls.sql` | Yes (V3 RLS exists) |
  | reporting | `reporting` | `command_center_actions` | V7 → `V8__enable_rls.sql` | Yes (V6 RLS exists) |

- **Stay out of scope:** `reporting.command_center_feed`, `reporting.reporting_event_inbox` (NULLABLE = platform-wide); `fee.fee_bands`, `fee.fee_items` (global, no `school_id`). Already-RLS tables from 1.3 (student ×3, attendance_student_records, reporting academic_events/event_student_contributions) are untouched.
- **Two-phase prod rollout** (datasource release, then migration release) — Task 7 runbook. Local/Testcontainers is atomic.

---

### Task 1: catalog-service — datasource + RLS on catalog_orders & annual_plan_items (pilot the new-service pattern)

**Files:**
- Create: `services/catalog-service/src/main/java/com/custoking/ims/catalogservice/security/TenantAwareDataSource.java`
- Create: `services/catalog-service/src/main/java/com/custoking/ims/catalogservice/security/TenantDataSourceConfig.java`
- Create: `services/catalog-service/src/main/resources/db/migration/V4__enable_rls.sql`
- Test: `services/catalog-service/src/test/java/com/custoking/ims/catalogservice/security/CatalogRlsIntegrationTest.java`

**Interfaces:**
- Consumes: existing `com.custoking.ims.catalogservice.security.TenantContext` (`.get()`, `.schoolId()`, `.isSuperAdmin()`, `.clear()`, constructor `(Long,String,String,Long,Object)`).
- Produces: `TenantAwareDataSource(DataSource)` and a `TenantDataSourceConfig` BeanPostProcessor (copied unchanged by Tasks 2–4).

- [ ] **Step 1: Contextless-writer gate (verify before writing code)**

Read `CatalogReadRepository` (and any Pub/Sub/event listener in catalog-service) for every INSERT/UPDATE into `catalog_orders` and `annual_plan_items`. Confirm each runs under a request `TenantContext` (the controllers set it via `TenantContextFilter`; superadmin approval paths set `isSuperAdmin`). There must be NO event-consumer/projection writer to these two tables without a context. If one exists, STOP and report (defer that table). Expected: catalog orders/annual-plan are user-request driven (Task 1.4 confirmed they ARE the tenant row) — gate passes.

- [ ] **Step 2: Write the failing RLS integration test**

Create `CatalogRlsIntegrationTest.java`, modeled exactly on `services/attendance-service/src/test/java/com/custoking/ims/attendanceservice/security/AttendanceRlsIntegrationTest.java`, changing:
- package → `com.custoking.ims.catalogservice.security`; schema → `catalog` (Flyway `.schemas("catalog").defaultSchema("catalog")`; grants on schema `catalog`).
- Seed `catalog.catalog_orders` as owner: **read `V1__catalog_schema.sql` for the NOT NULL columns first**, then insert 2 rows with `school_id=10` (A) and 1 row with `school_id=20` (B). `countRows()` counts `catalog.catalog_orders`.
- Five assertions (unchanged shape): A→2, B→1, superadmin→3, no-context→0, `withCheck_blocksCrossTenantInsert` (as `app_rt` with school 10, INSERT a `catalog_orders` row with `school_id=20` → `SQLException` containing "row-level security").

- [ ] **Step 3: Run it — verify it fails**

Run: `./mvnw.cmd -q -f services/catalog-service/pom.xml test -Dtest=CatalogRlsIntegrationTest` (Docker up)
Expected: FAIL — compile error (`TenantAwareDataSource` missing) and/or, once compiled, no policy yet so `app_rt` with a school context still sees all 3 rows (assertion `A→2` fails because RLS isn't on).

- [ ] **Step 4: Create `TenantAwareDataSource.java`** (copy verbatim from attendance, only the package line differs)

```java
package com.custoking.ims.catalogservice.security;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Sets the per-request tenant GUCs on every borrowed connection so PostgreSQL RLS
 * resolves to the authenticated school. Session-level set_config (false) so the value
 * applies to autocommit reads and transactional writes alike; overwritten on each borrow.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return applyTenantGucs(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return applyTenantGucs(super.getConnection(username, password));
    }

    private Connection applyTenantGucs(Connection connection) throws SQLException {
        TenantContext ctx = TenantContext.get();
        String schoolId = ctx.schoolId() == null ? "" : ctx.schoolId().toString();
        String bypass = ctx.isSuperAdmin() ? "on" : "off";
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT set_config('app.current_school_id', ?, false), set_config('app.bypass_rls', ?, false)")) {
            ps.setString(1, schoolId);
            ps.setString(2, bypass);
            ps.execute();
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}
```

- [ ] **Step 5: Create `TenantDataSourceConfig.java`** (copy verbatim from attendance, only the package line differs)

```java
package com.custoking.ims.catalogservice.security;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Wraps the auto-configured application DataSource (Hikari, connecting as app_rt) in a
 * TenantAwareDataSource. Flyway uses its own separate datasource (spring.flyway.* → appuser),
 * which is NOT a registered bean and so is not wrapped — migrations run as the owner and
 * bypass RLS.
 */
@Configuration
public class TenantDataSourceConfig {

    @Bean
    public static BeanPostProcessor tenantDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && !(bean instanceof TenantAwareDataSource)) {
                    return new TenantAwareDataSource(ds);
                }
                return bean;
            }
        };
    }
}
```

- [ ] **Step 6: Create the migration `V4__enable_rls.sql`**

```sql
-- RLS backstop on the catalog tenant tables (NOT NULL school_id after V3).
-- ENABLE (not FORCE): owner (appuser) bypasses for Flyway/seed; app_rt is subject.
ALTER TABLE catalog.catalog_orders ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON catalog.catalog_orders;
CREATE POLICY tenant_isolation ON catalog.catalog_orders
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE catalog.annual_plan_items ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON catalog.annual_plan_items;
CREATE POLICY tenant_isolation ON catalog.annual_plan_items
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');
```

- [ ] **Step 7: Run the test — verify it passes**

Run: `./mvnw.cmd -q -f services/catalog-service/pom.xml test -Dtest=CatalogRlsIntegrationTest` (Docker up) → PASS (5 tests). Then full `…/catalog-service/pom.xml test` → green (existing Mockito + tenant-key migration tests unaffected; they don't hit a real DB except the migration test which runs as owner and is unaffected by RLS).

- [ ] **Step 8: Commit**

```bash
git add services/catalog-service/src/main/java/com/custoking/ims/catalogservice/security/TenantAwareDataSource.java \
        services/catalog-service/src/main/java/com/custoking/ims/catalogservice/security/TenantDataSourceConfig.java \
        services/catalog-service/src/main/resources/db/migration/V4__enable_rls.sql \
        services/catalog-service/src/test/java/com/custoking/ims/catalogservice/security/CatalogRlsIntegrationTest.java
git commit -m "feat(catalog): RLS backstop on catalog_orders and annual_plan_items (enforced as app_rt)"
```

---

### Task 2: firefighting-service — datasource + RLS on firefighting_requests & ff_quotations

**Files:**
- Create: `services/firefighting-service/src/main/java/com/custoking/ims/firefightingservice/security/TenantAwareDataSource.java`
- Create: `services/firefighting-service/src/main/java/com/custoking/ims/firefightingservice/security/TenantDataSourceConfig.java`
- Create: `services/firefighting-service/src/main/resources/db/migration/V5__enable_rls.sql`
- Test: `services/firefighting-service/src/test/java/com/custoking/ims/firefightingservice/security/FirefightingRlsIntegrationTest.java`
- (carry-forward #3) Modify: `services/firefighting-service/src/test/java/com/custoking/ims/firefightingservice/persistence/FirefightingTenantKeyMigrationTest.java`

**Interfaces:**
- Consumes: `com.custoking.ims.firefightingservice.security.TenantContext`; the `TenantAwareDataSource`/`TenantDataSourceConfig` source from Task 1 (package-renamed).
- Produces: nothing new for later tasks.

- [ ] **Step 1: Contextless-writer gate** — read `FirefightingReadRepository` (and any event listener) for every INSERT/UPDATE into `firefighting_requests` and `ff_quotations`. Confirm all run under a request `TenantContext` (requests are created by school admins; `addQuotation` binds the parent request's school under the actor's context). No contextless projection writer expected. If one exists, STOP and report.

- [ ] **Step 2: Write the failing RLS integration test** — create `FirefightingRlsIntegrationTest.java` modeled on `AttendanceRlsIntegrationTest` (package `…firefightingservice.security`; schema `firefighting`). Seed `firefighting.firefighting_requests` as owner: **read `V1__firefighting_schema.sql` for NOT NULL columns first**, 2 rows `school_id=10`, 1 row `school_id=20`; `countRows()` counts `firefighting.firefighting_requests`. Assert A→2, B→1, superadmin→3, no-context→0, cross-tenant INSERT blocked.

- [ ] **Step 3: Run it — verify it fails** — `./mvnw.cmd -q -f services/firefighting-service/pom.xml test -Dtest=FirefightingRlsIntegrationTest` (Docker up). Expected: FAIL (compile: `TenantAwareDataSource` missing; or no policy → A sees 3).

- [ ] **Step 4: Create `TenantAwareDataSource.java`** — copy the Task 1 Step 4 file verbatim, changing only the first line to `package com.custoking.ims.firefightingservice.security;`.

- [ ] **Step 5: Create `TenantDataSourceConfig.java`** — copy the Task 1 Step 5 file verbatim, changing only the first line to `package com.custoking.ims.firefightingservice.security;`.

- [ ] **Step 6: Create the migration `V5__enable_rls.sql`** — the Global-Constraints policy block for `firefighting.firefighting_requests` and `firefighting.ff_quotations` (the two `ALTER … ENABLE` + `CREATE POLICY tenant_isolation …` stanzas, exact USING/WITH CHECK form).

- [ ] **Step 7: (carry-forward #3) Upgrade the same-schema backfill test to Flyway-driven**

In `FirefightingTenantKeyMigrationTest.java`, the existing backfill method runs the V3 `UPDATE` as a hand-copied string. Add ONE method modeled on attendance's `v4_guard_truePath_backfill_performedByMigration` (see `AttendanceTenantKeyMigrationTest`): on a fresh container, migrate to the version BELOW the denormalize (`target('2')`), seed a `firefighting_requests` parent (school=10) and an `ff_quotations` child with NULL `school_id` referencing it, then migrate to `target('3')` so the V3 migration's own `UPDATE` performs the backfill; assert the child's `school_id` is now 10. Keep the existing hand-copied test. (Low value but folds the review carry-forward into this service while we're here.)

- [ ] **Step 8: Run the tests — verify they pass** — `…-Dtest=FirefightingRlsIntegrationTest` → PASS; `…-Dtest=FirefightingTenantKeyMigrationTest` → PASS (now one more method); then full `…/firefighting-service/pom.xml test` → green.

- [ ] **Step 9: Commit**

```bash
git add services/firefighting-service/src/main/java/com/custoking/ims/firefightingservice/security/TenantAwareDataSource.java \
        services/firefighting-service/src/main/java/com/custoking/ims/firefightingservice/security/TenantDataSourceConfig.java \
        services/firefighting-service/src/main/resources/db/migration/V5__enable_rls.sql \
        services/firefighting-service/src/test/java/com/custoking/ims/firefightingservice/security/FirefightingRlsIntegrationTest.java \
        services/firefighting-service/src/test/java/com/custoking/ims/firefightingservice/persistence/FirefightingTenantKeyMigrationTest.java
git commit -m "feat(firefighting): RLS backstop on requests and ff_quotations (enforced as app_rt)"
```

---

### Task 3: workflow-service — datasource + RLS on workflow_instances & workflow_actions

**Files:**
- Create: `services/workflow-service/src/main/java/com/custoking/ims/workflowservice/security/TenantAwareDataSource.java`
- Create: `services/workflow-service/src/main/java/com/custoking/ims/workflowservice/security/TenantDataSourceConfig.java`
- Create: `services/workflow-service/src/main/resources/db/migration/V5__enable_rls.sql`
- Test: `services/workflow-service/src/test/java/com/custoking/ims/workflowservice/security/WorkflowRlsIntegrationTest.java`
- (carry-forward #3) Modify: `services/workflow-service/src/test/java/com/custoking/ims/workflowservice/persistence/WorkflowTenantKeyMigrationTest.java`

**Interfaces:** Consumes `…workflowservice.security.TenantContext` + the Task-1 datasource source (package-renamed).

- [ ] **Step 1: Contextless-writer gate** — read `WorkflowReadRepository` for INSERT/UPDATE into `workflow_instances` and `workflow_actions`. Confirm all run under a request `TenantContext` (`createOrGetInstance` and `recordAction`/callers run under the acting user's context; superadmin approvals set `isSuperAdmin`). **Special check:** if workflow instances are ever created by an event consumer reacting to another service's event (no user context), that path would break under RLS — verify none exists, else STOP and report.

- [ ] **Step 2: Write the failing RLS integration test** — `WorkflowRlsIntegrationTest.java` modeled on `AttendanceRlsIntegrationTest` (package `…workflowservice.security`; schema `workflow`). Seed `workflow.workflow_instances` as owner (**read `V1__workflow_schema.sql` for NOT NULL columns first**), 2 rows `school_id=10`, 1 row `school_id=20`; `countInstances()` counts `workflow.workflow_instances`. Five assertions as usual (A→2, B→1, superadmin→3, noContext→0, WITH CHECK block). **Plus (per the every-table-asserted constraint)** seed `workflow.workflow_actions` (2 rows school_id=10, 1 row school_id=20, each referencing a seeded instance via `instance_id`) and add a `workflowActions_schoolA_seesOnlyItsRows` assertion (count `workflow.workflow_actions` as `app_rt` school 10 → 2), proving the child policy filters.

- [ ] **Step 3: Run it — verify it fails** — `…-Dtest=WorkflowRlsIntegrationTest` (Docker up) → FAIL.

- [ ] **Step 4: Create `TenantAwareDataSource.java`** — Task 1 Step 4 verbatim, package `com.custoking.ims.workflowservice.security;`.

- [ ] **Step 5: Create `TenantDataSourceConfig.java`** — Task 1 Step 5 verbatim, package `com.custoking.ims.workflowservice.security;`.

- [ ] **Step 6: Create the migration `V5__enable_rls.sql`** — the policy block for `workflow.workflow_instances` and `workflow.workflow_actions`.

- [ ] **Step 7: (carry-forward #3) Upgrade the backfill test to Flyway-driven** — in `WorkflowTenantKeyMigrationTest.java`, add ONE method (model on attendance's Flyway-driven guard-path test): fresh container → `target('2')` → seed a `workflow_instances` parent (school=10) + a `workflow_actions` child with NULL `school_id` referencing it → `target('3')` so the V3 migration's own backfill `UPDATE` runs → assert child `school_id=10`. Keep the existing hand-copied test.

- [ ] **Step 8: Run the tests — verify they pass** — `…-Dtest=WorkflowRlsIntegrationTest` → PASS; `…-Dtest=WorkflowTenantKeyMigrationTest` → PASS; full suite → green.

- [ ] **Step 9: Commit**

```bash
git add services/workflow-service/src/main/java/com/custoking/ims/workflowservice/security/TenantAwareDataSource.java \
        services/workflow-service/src/main/java/com/custoking/ims/workflowservice/security/TenantDataSourceConfig.java \
        services/workflow-service/src/main/resources/db/migration/V5__enable_rls.sql \
        services/workflow-service/src/test/java/com/custoking/ims/workflowservice/security/WorkflowRlsIntegrationTest.java \
        services/workflow-service/src/test/java/com/custoking/ims/workflowservice/persistence/WorkflowTenantKeyMigrationTest.java
git commit -m "feat(workflow): RLS backstop on workflow_instances and workflow_actions (enforced as app_rt)"
```

---

### Task 4: fee-service — datasource + RLS on fee_assignments & payment_records

**Files:**
- Create: `services/fee-service/src/main/java/com/custoking/ims/feeservice/security/TenantAwareDataSource.java`
- Create: `services/fee-service/src/main/java/com/custoking/ims/feeservice/security/TenantDataSourceConfig.java`
- Create: `services/fee-service/src/main/resources/db/migration/V7__enable_rls.sql`
- Test: `services/fee-service/src/test/java/com/custoking/ims/feeservice/security/FeeRlsIntegrationTest.java`

**Interfaces:** Consumes `…feeservice.security.TenantContext` + the Task-1 datasource source (package-renamed).

- [ ] **Step 1: Contextless-writer gate** — read `FeeReadRepository` for INSERT/UPDATE into `fee_assignments` and `payment_records`. Confirm `assignFeePlan` and `recordPayment` (and any other writer) run under a request `TenantContext`. **Note:** `studentSchoolId()` reads `student.students` cross-schema at runtime — that read is by the owner/app pool and is unaffected by RLS (no policy on student.students from this service); the write to fee tables is what RLS gates and it runs under the request context. Confirm no contextless batch/projection writer; else STOP and report.

- [ ] **Step 2: Write the failing RLS integration test** — `FeeRlsIntegrationTest.java` modeled on `AttendanceRlsIntegrationTest` (package `…feeservice.security`; schema `fee`). Seed `fee.fee_assignments` as owner (**read `V1__fee_schema.sql` for NOT NULL columns first**), 2 rows `school_id=10`, 1 row `school_id=20`; `countAssignments()` counts `fee.fee_assignments`. Five assertions as usual (A→2, B→1, superadmin→3, noContext→0, WITH CHECK block). **Plus (per the every-table-asserted constraint)** seed `fee.payment_records` (2 rows school_id=10, 1 row school_id=20; read its V1 NOT NULL columns) and add a `paymentRecords_schoolA_seesOnlyItsRows` assertion (count `fee.payment_records` as `app_rt` school 10 → 2), proving the second table's policy filters.

- [ ] **Step 3: Run it — verify it fails** — `…-Dtest=FeeRlsIntegrationTest` (Docker up) → FAIL.

- [ ] **Step 4: Create `TenantAwareDataSource.java`** — Task 1 Step 4 verbatim, package `com.custoking.ims.feeservice.security;`.

- [ ] **Step 5: Create `TenantDataSourceConfig.java`** — Task 1 Step 5 verbatim, package `com.custoking.ims.feeservice.security;`.

- [ ] **Step 6: Create the migration `V7__enable_rls.sql`** — the policy block for `fee.fee_assignments` and `fee.payment_records`.

- [ ] **Step 7: Run the test — verify it passes** — `…-Dtest=FeeRlsIntegrationTest` → PASS; full `…/fee-service/pom.xml test` → green.

- [ ] **Step 8: Commit**

```bash
git add services/fee-service/src/main/java/com/custoking/ims/feeservice/security/TenantAwareDataSource.java \
        services/fee-service/src/main/java/com/custoking/ims/feeservice/security/TenantDataSourceConfig.java \
        services/fee-service/src/main/resources/db/migration/V7__enable_rls.sql \
        services/fee-service/src/test/java/com/custoking/ims/feeservice/security/FeeRlsIntegrationTest.java
git commit -m "feat(fee): RLS backstop on fee_assignments and payment_records (enforced as app_rt)"
```

---

### Task 5: attendance-service — fail-loud school resolution, then RLS on attendance_daily

attendance-service ALREADY has `TenantAwareDataSource` + `V3__enable_rls.sql` (on `attendance_student_records`). This task does carry-forward #1 (fail-loud) FIRST, then adds RLS to `attendance_daily`.

**Files:**
- Modify: `services/attendance-service/src/main/java/com/custoking/ims/attendanceservice/persistence/AttendanceReadRepository.java` (5 sites + 1 helper)
- Create: `services/attendance-service/src/main/resources/db/migration/V6__enable_rls.sql`
- Modify: `services/attendance-service/src/test/java/com/custoking/ims/attendanceservice/security/AttendanceRlsIntegrationTest.java` (add attendance_daily coverage)

- [ ] **Step 1: Contextless-writer gate** — confirm `upsertDaily`/`saveDailyAttendance` (the only writers of `attendance_daily`) run under a request `TenantContext`. They do (section register / submit endpoints). No projection writer. Gate passes.

- [ ] **Step 2: (carry-forward #1) Make section school resolution fail-loud**

`AttendanceReadRepository.java` resolves the section's school at 5 sites as `Long sectionSchoolId = longNum(section.get("schoolId"), 0);` (lines ~135, ~197, ~228, ~309, ~386). Defaulting to `0` would write a bogus tenant-`0` row that satisfies the new NOT NULL constraint but is invisible to every real tenant once RLS is on. Add a private helper and replace all 5 default-to-0 calls with it:

```java
private Long requireSectionSchool(Map<String, Object> section, String sectionId) {
    Long schoolId = longNum(section.get("schoolId"), 0);
    if (schoolId == null || schoolId <= 0) {
        throw new IllegalStateException("Section " + sectionId + " has no owning school_id; cannot scope attendance");
    }
    return schoolId;
}
```
Replace each `Long sectionSchoolId = longNum(section.get("schoolId"), 0);` with `Long sectionSchoolId = requireSectionSchool(section, sectionId);` (the `sectionId` variable is in scope at each site — confirm while editing; if a site uses a differently-named id var, pass that). Existing equality checks (`sectionSchoolId.equals(...)`) keep working — `sectionSchoolId` is now guaranteed positive.

Add a focused unit test asserting `requireSectionSchool` throws when `school_id` is absent/0 (a Mockito-style test on the repo, OR fold into an existing repo test class — model on the nearest existing `AttendanceReadRepository` unit test).

- [ ] **Step 3: Run the fail-loud test + existing suite** — `./mvnw.cmd -q -f services/attendance-service/pom.xml test` → green (the 5 substitutions are behavior-preserving for valid sections; the new test covers the throw). Confirm no existing test relied on the 0 default.

- [ ] **Step 4: Write the failing attendance_daily RLS assertions**

In `AttendanceRlsIntegrationTest.java`, add a `countDaily()` helper (`SELECT count(*) FROM attendance.attendance_daily`) and 5 new `@Test` methods mirroring the existing ones but counting `attendance_daily` (the seed already inserts `d1` school 10 and `d2` school 20 — add one more school-10 daily row in `@BeforeAll` so the A→2 / B→1 / superadmin→3 / none→0 shape holds for `attendance_daily` too; adjust the existing `attendance_student_records` FK seed if needed so totals stay correct, OR use a separate count baseline and assert A-sees-only-A by school rather than fixed totals). For `withCheck`, attempt an `attendance_daily` INSERT with a cross-tenant `school_id` as `app_rt` school 10 → expect RLS error.

Run: `…-Dtest=AttendanceRlsIntegrationTest` → the new `attendance_daily` methods FAIL (no policy yet).

- [ ] **Step 5: Create the migration `V6__enable_rls.sql`** — the policy block for `attendance.attendance_daily` only (do NOT re-alter `attendance_student_records` — V3 already did).

- [ ] **Step 6: Run the test — verify it passes** — `…-Dtest=AttendanceRlsIntegrationTest` → PASS (both the original `attendance_student_records` methods and the new `attendance_daily` methods). Full `…/attendance-service/pom.xml test` → green.

- [ ] **Step 7: Commit**

```bash
git add services/attendance-service/src/main/java/com/custoking/ims/attendanceservice/persistence/AttendanceReadRepository.java \
        services/attendance-service/src/main/resources/db/migration/V6__enable_rls.sql \
        services/attendance-service/src/test/java/com/custoking/ims/attendanceservice/security/AttendanceRlsIntegrationTest.java \
        <the fail-loud unit test file you touched>
git commit -m "feat(attendance): fail-loud section school + RLS backstop on attendance_daily (enforced as app_rt)"
```

---

### Task 6: reporting-service — RLS on command_center_actions (verify no projection writer)

reporting-service ALREADY has `TenantAwareDataSource` + `V6__enable_rls.sql` (academic_events, event_student_contributions). This task adds `command_center_actions` ONLY — and its contextless-writer gate is the highest-risk one in this plan, because reporting is projection-heavy.

**Files:**
- Create: `services/reporting-service/src/main/resources/db/migration/V8__enable_rls.sql`
- Test: `services/reporting-service/src/test/java/com/custoking/ims/reportingservice/security/ReportingCommandCenterRlsIntegrationTest.java`

- [ ] **Step 1: Contextless-writer gate (CRITICAL — may force a defer)**

Read every writer of `reporting.command_center_actions`: the controller/service path AND any Pub/Sub event consumer / projection in reporting-service. `command_center_feed` and `reporting_event_inbox` are the projection tables (deferred, nullable); `command_center_actions` is meant to be the user-action table. CONFIRM that `command_center_actions` is written ONLY under a request `TenantContext` (a superadmin or a school user acting). If ANY event-consumer/projection writes `command_center_actions` without a `TenantContext`, enabling RLS will break that consumer (`WITH CHECK` rejects the insert). In that case STOP and report — recommend deferring `command_center_actions` (leave it NOT NULL but un-RLS'd) rather than forcing it. Do not proceed to Step 2 until this gate is satisfied.

- [ ] **Step 2: Write the failing RLS integration test** — `ReportingCommandCenterRlsIntegrationTest.java` modeled on the existing `services/reporting-service/src/test/java/com/custoking/ims/reportingservice/security/ReportingRlsIntegrationTest.java` (same `app_rt` harness, schema `reporting`). Seed `reporting.command_center_actions` as owner (**read the schema migration for NOT NULL columns first**: `V1__reporting_command_center_schema.sql`), 2 rows `school_id=10`, 1 row `school_id=20`; count `reporting.command_center_actions`. Five assertions. Run → FAIL (no policy yet).

- [ ] **Step 3: Create the migration `V8__enable_rls.sql`** — the policy block for `reporting.command_center_actions` ONLY. Add a comment: `command_center_feed` and `reporting_event_inbox` intentionally remain WITHOUT RLS (NULLABLE school_id = platform-wide projection rows).

- [ ] **Step 4: Run the test — verify it passes** — `…-Dtest=ReportingCommandCenterRlsIntegrationTest` → PASS. Full `…/reporting-service/pom.xml test` → green (existing `ReportingRlsIntegrationTest` and the tenant-key migration test unaffected).

- [ ] **Step 5: Commit**

```bash
git add services/reporting-service/src/main/resources/db/migration/V8__enable_rls.sql \
        services/reporting-service/src/test/java/com/custoking/ims/reportingservice/security/ReportingCommandCenterRlsIntegrationTest.java
git commit -m "feat(reporting): RLS backstop on command_center_actions (feed/inbox stay un-RLS'd)"
```

---

### Task 7: RLS-extension rollout runbook update + verification gate

**Files:**
- Modify: `docs/MICROSERVICE-RLS-ROLLOUT-RUNBOOK.md` (extend with the new tables + orphan pre-check)
- Modify: `ARCHITECTURE_REVIEW.md` (note MT-P0-2/MT-P1-3 extended to the 1.4 tables)

- [ ] **Step 1: Extend `docs/MICROSERVICE-RLS-ROLLOUT-RUNBOOK.md`** (append a dated 2026-06-30 "RLS extension to Task-1.4 tables" section — do NOT rewrite the existing 1.3 content) covering:
  - The new in-scope table list per service with the migration version (catalog V4, firefighting V5, workflow V5, fee V7, attendance V6, reporting V8) and which services newly received `TenantAwareDataSource` (catalog, firefighting, workflow, fee).
  - **Two-phase rollout (same hazard as 1.3):** Release 1 deploys `TenantAwareDataSource` to catalog/firefighting/workflow/fee (attendance/reporting already have it) so every live instance sets the GUC; Release 2 ships the `enable_rls` migrations. Never ship a service's `enable_rls` migration before its datasource release is fully rolled out, or that service's `app_rt` queries on the RLS table return 0 rows.
  - **(carry-forward #2) Pre-cutover orphan / mis-scope check (run before each `enable_rls` migration):** for the cross-schema-backfilled tables, confirm no row has a `school_id` that points at a deleted/mismatched parent — a mis-scoped row becomes visible to the wrong tenant under RLS:
    ```sql
    -- attendance_daily: school_id must match its section's owning school
    SELECT count(*) FROM attendance.attendance_daily ad
      LEFT JOIN tenant_school.school_sections ss ON ss.id = ad.section_id
     WHERE ad.school_id IS DISTINCT FROM ss.school_id;            -- must be 0
    -- fee_assignments / payment_records: school_id must match the student's school
    SELECT count(*) FROM fee.fee_assignments fa
      LEFT JOIN student.students s ON s.id = fa.student_id
     WHERE fa.school_id IS DISTINCT FROM s.school_id;             -- must be 0
    SELECT count(*) FROM fee.payment_records pr
      LEFT JOIN student.students s ON s.id = pr.student_id
     WHERE pr.school_id IS DISTINCT FROM s.school_id;             -- must be 0
    ```
    Investigate/repair any non-zero count before enabling RLS on that table.
  - **Rollback:** forward migration per table — `DROP POLICY tenant_isolation ON <schema>.<table>; ALTER TABLE <schema>.<table> DISABLE ROW LEVEL SECURITY;` (app_rt retains DML).
  - **Scope note:** `command_center_feed`/`reporting_event_inbox` remain un-RLS'd (nullable = platform-wide); `fee_bands`/`fee_items` excluded; if any service's contextless-writer gate (Tasks 1–6 Step 1) forced a deferral, record which table and why.

- [ ] **Step 2: Update `ARCHITECTURE_REVIEW.md`** — near the MT-P0-2 / MT-P1-3 entry, append a dated note (2026-06-30): RLS now enforced (as `app_rt`, per-request GUC) on the Task-1.4 tables — catalog_orders, annual_plan_items, firefighting_requests, ff_quotations, workflow_instances, workflow_actions, attendance_daily, fee_assignments, payment_records, command_center_actions — extending the 1.3 backstop; feed/inbox/fee_bands/fee_items remain excluded by design. Append only; do not rewrite.

- [ ] **Step 3: Verification gate** — Docker up:
```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
powershell -ExecutionPolicy Bypass -File scripts\invoke-microservice-tests.ps1 -Services catalog-service,firefighting-service,workflow-service,fee-service,attendance-service,reporting-service
```
Expected: all 6 green, including the new `*RlsIntegrationTest` classes (they run because Docker is present) and the existing RLS/migration tests.

- [ ] **Step 4: Commit** — `git add docs/MICROSERVICE-RLS-ROLLOUT-RUNBOOK.md ARCHITECTURE_REVIEW.md`; `git commit -m "docs(ops): RLS-extension rollout runbook + architecture-review update"`

---

## Self-Review

**Spec coverage (against the 1.3 design's "extend RLS after 1.4" follow-up + the three carry-forwards):**
- Datasource added to the 4 services lacking it (catalog/firefighting/workflow/fee) → Tasks 1–4 Steps 4–5. ✓
- `enable_rls` migration on all 10 tables → Tasks 1 (×2), 2 (×2), 3 (×2), 4 (×2), 5 (attendance_daily), 6 (command_center_actions). ✓
- `app_rt` Testcontainers isolation proof per service → Tasks 1–6 (one count-target table per service proves the mechanism; multi-table services share the identical policy form, covered by migration + Task 7 gate). ✓
- Contextless-writer gate per table (the real RLS risk) → every task Step 1, with explicit defer/escalate guidance; reporting (Task 6 Step 1) flagged CRITICAL. ✓
- Carry-forward #1 (attendance fail-loud before RLS on attendance_daily) → Task 5 Steps 2–3, ordered before the migration. ✓
- Carry-forward #2 (orphan/mis-scope pre-check) → Task 7 Step 1 with concrete SQL. ✓
- Carry-forward #3 (ff/wf Flyway-driven backfill test) → Tasks 2 & 3 Step 7. ✓
- Two-phase rollout + rollback + ENABLE-not-FORCE + GUC contract → Global Constraints + Task 7. ✓
- feed/inbox/fee_bands/fee_items stay excluded → Global Constraints + Task 6 Step 3 comment + Task 7 scope note. ✓

**Placeholder scan:** datasource + config files are given in full (Task 1; Tasks 2–4 say "copy verbatim, change package line" — the source is in Task 1, not a placeholder); policy SQL is fully specified in Global Constraints and reproduced per migration; the fail-loud helper is given in full; tests say "model on AttendanceRlsIntegrationTest" + "read V1 columns for the seed NOT NULL fields" — the established pattern from the 1.3/1.4 plans (the count-target table, school assignments, and 5 assertions ARE specified). The only deliberately implementer-resolved detail is each table's full NOT NULL seed column list, which must be read from that table's V1 (naming them here would risk drift from the real schema).

**Type/name consistency:** `TenantAwareDataSource(DataSource)`, `TenantDataSourceConfig` BeanPostProcessor, GUCs `app.current_school_id`/`app.bypass_rls`, policy `tenant_isolation`, `TenantContext.get()/.schoolId()/.isSuperAdmin()`, test classes `<Svc>RlsIntegrationTest` (reporting's second one `ReportingCommandCenterRlsIntegrationTest` to avoid colliding with the existing `ReportingRlsIntegrationTest`). Migration versions: catalog V4, firefighting V5, workflow V5, fee V7, attendance V6, reporting V8 — each = that service's latest+1 after Task 1.4. Consistent.

## Risks

- **Contextless writer on an in-scope table (esp. `command_center_actions`)** → would break under RLS. Mitigated by the per-table gate (every task Step 1) with a defer/escalate path; not assumed away.
- **Two-phase deploy window** (RLS on before datasource live) → Task 7 runbook two-phase staging; the 4 new-datasource services are the sensitive ones.
- **Mis-scoped backfilled rows become cross-tenant-visible under RLS** → Task 7 orphan/mis-scope pre-check before each migration.
- **Child-table writes (`ff_quotations`, `workflow_actions`) under a cross-school actor** → app already binds the parent's school AND the actor's context matches (or superadmin bypass); WITH CHECK enforces it. If a legitimate flow writes a child under a different school than the actor's context, that flow must set the correct context — surface in the Task 2/3 gate.
- **Test seed totals drift** (attendance_daily shares a container seed with attendance_student_records) → Task 5 Step 4 calls this out and offers a per-school assertion instead of fixed totals.
