# RLS Backstop (transaction GUC) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable PostgreSQL Row-Level Security as a database-enforced tenant backstop on the cleanly-scoped (`NOT NULL school_id`) tables, with a per-request session GUC set on connection-borrow, enforced for the unprivileged `app_rt` runtime role.

**Architecture:** Each in-scope service gets (1) a `TenantAwareDataSource` that sets `app.current_school_id` + `app.bypass_rls` from `TenantContext` on every connection borrow (covers autocommit reads + transactional writes), (2) a forward Flyway migration enabling RLS + a `tenant_isolation` policy on its `NOT NULL school_id` tables, and (3) a Testcontainers integration test proving isolation **as `app_rt`**. Superadmin sets `bypass_rls=on`; the owner (`appuser`) bypasses RLS for Flyway/seed (`ENABLE`, not `FORCE`).

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring `JdbcClient`, `DelegatingDataSource` + a `BeanPostProcessor`, Flyway, Postgres 15/16, Testcontainers (`org.testcontainers:postgresql` + `:junit-jupiter`, versions managed by the Spring Boot BOM), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-06-29-rls-design.md`

## Global Constraints

- RLS bites only for a non-owner, non-superuser role: enforce as `app_rt` (Task 1.1: `NOBYPASSRLS`, DML grants, not a `cloudsqlsuperuser` member). `appuser` owns schemas / runs Flyway and bypasses RLS.
- GUC contract: `app.current_school_id` = caller's school id text (empty/unset ⇒ no tenant); `app.bypass_rls` = `'on'` only for a gateway-verified superadmin, else `'off'`.
- Policy form (every in-scope table): `USING` and `WITH CHECK` = `(school_id = nullif(current_setting('app.current_school_id', true),'')::bigint OR current_setting('app.bypass_rls', true) = 'on')`. Use `ENABLE ROW LEVEL SECURITY` (NOT `FORCE`) so the owner bypasses.
- GUC set on **every connection borrow** via `SELECT set_config('app.current_school_id', ?, false), set_config('app.bypass_rls', ?, false)` (session-level, parameterised — no injection; overwrite-on-borrow prevents pool leakage). Empty school id ⇒ deny-by-default.
- Copied per-service (no `services/common` module). New files in package `com.custoking.ims.<service>.security` (alongside `TenantContext`).
- Flyway: forward-only, per owning service, continue that service's sequence. Never edit an applied migration.
- `TenantContext` (Task 1.2) API: `TenantContext.get()` (never null), `.schoolId()` (Long, nullable), `.isSuperAdmin()` (boolean), `.set(...)`, `.clear()`.
- In-scope tables (NOT NULL `school_id`, user-request-driven): student (`students`, `student_review_campaigns`, `student_review_items`), attendance (`attendance_student_records`), reporting (`academic_events`, `event_student_contributions`). Everything else deferred to Task 1.4.
- RLS integration tests run as `app_rt`, guarded by a Docker-availability assumption so `mvn test` stays green where Docker is absent and runs where present (CI has Docker).
- Prod rollout is two-phase (datasource release, then migration release) — see Task 4 runbook. Local/Testcontainers is atomic.

---

### Task 1: student-service pilot — TenantAwareDataSource + RLS migration + Testcontainers proof

**Files:**
- Create: `services/student-service/src/main/java/com/custoking/ims/studentservice/security/TenantAwareDataSource.java`
- Create: `services/student-service/src/main/java/com/custoking/ims/studentservice/security/TenantDataSourceConfig.java`
- Create: `services/student-service/src/main/resources/db/migration/V4__enable_rls.sql`
- Modify: `services/student-service/pom.xml` (add Testcontainers test deps)
- Test: `services/student-service/src/test/java/com/custoking/ims/studentservice/security/StudentRlsIntegrationTest.java`

**Interfaces (produced — copied by Tasks 2–3):**
- `TenantAwareDataSource(javax.sql.DataSource target)` — wraps a pool; on `getConnection()` sets the two GUCs from `TenantContext`.
- `TenantDataSourceConfig` — a `BeanPostProcessor` that wraps the app `DataSource` bean in `TenantAwareDataSource`.

- [ ] **Step 1: Write the failing RLS integration test**

Create `StudentRlsIntegrationTest.java`:

```java
package com.custoking.ims.studentservice.security;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class StudentRlsIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource appRt; // app_rt pool wrapped by TenantAwareDataSource

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping RLS integration test");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();

        // Migrate as the owner (owns tables → bypasses RLS, like appuser in prod).
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("student").defaultSchema("student")
                .locations("classpath:db/migration")
                .load().migrate();

        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            // Unprivileged runtime role, subject to RLS.
            st.execute("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
            st.execute("GRANT USAGE ON SCHEMA student TO app_rt");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA student TO app_rt");
            st.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA student TO app_rt");
            // Seed: school 10 (A) x2, school 20 (B) x1 — as owner (bypasses RLS).
            st.execute("INSERT INTO student.students (admission_no, full_name, school_id, class_id, section_id, academic_year_id) VALUES " +
                    "('A1','Alice',10,'c1','s1','y1'),('A2','Amy',10,'c1','s1','y1'),('B1','Bob',20,'c1','s1','y1')");
        }

        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(PG.getJdbcUrl());
        pool.setUsername("app_rt");
        pool.setPassword("app_rt");
        pool.setMaximumPoolSize(2);
        appRt = new TenantAwareDataSource(pool);
    }

    @AfterAll
    static void tearDown() {
        TenantContext.clear();
        if (PG != null) PG.stop();
    }

    @AfterEach
    void clearCtx() { TenantContext.clear(); }

    private long countStudents() throws SQLException {
        try (Connection c = appRt.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM student.students")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void schoolA_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(2, countStudents());
    }

    @Test
    void schoolB_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(2L, "b@x", "ADMIN", 20L, null));
        assertEquals(1, countStudents());
    }

    @Test
    void superadmin_seesAll() throws Exception {
        TenantContext.set(new TenantContext(3L, "s@x", "SUPERADMIN", null, null));
        assertEquals(3, countStudents());
    }

    @Test
    void noContext_seesNothing() throws Exception {
        TenantContext.clear();
        assertEquals(0, countStudents());
    }

    @Test
    void withCheck_blocksCrossTenantInsert() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        try (Connection c = appRt.getConnection(); Statement st = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO student.students (admission_no, full_name, school_id, class_id, section_id, academic_year_id) " +
                    "VALUES ('X1','Mallory',20,'c1','s1','y1')"));
            assertTrue(ex.getMessage().toLowerCase().contains("row-level security"), ex.getMessage());
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw.cmd -q -f services/student-service/pom.xml test -Dtest=StudentRlsIntegrationTest`
Expected: FAIL — compilation error (`TenantAwareDataSource` missing) and/or Testcontainers deps missing. (If Docker is unavailable the test would *skip*, not prove anything — ensure Docker is running for this task.)

- [ ] **Step 3: Add Testcontainers test dependencies to the pom**

In `services/student-service/pom.xml`, inside `<dependencies>`, after the existing `spring-boot-starter-test` dependency, add (versions are managed by the Spring Boot parent BOM — no `<version>`):
```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 4: Create `TenantAwareDataSource.java`**

```java
package com.custoking.ims.studentservice.security;

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

- [ ] **Step 5: Create `TenantDataSourceConfig.java`**

```java
package com.custoking.ims.studentservice.security;

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

- [ ] **Step 6: Create the RLS migration `V4__enable_rls.sql`**

```sql
-- Enable Row-Level Security on the cleanly-scoped (NOT NULL school_id) student tables.
-- ENABLE (not FORCE): the table owner (appuser) bypasses, so Flyway/seed keep working;
-- the unprivileged runtime role app_rt is subject. Policy reads the per-request GUCs.

ALTER TABLE student.students ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.students;
CREATE POLICY tenant_isolation ON student.students
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE student.student_review_campaigns ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.student_review_campaigns;
CREATE POLICY tenant_isolation ON student.student_review_campaigns
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE student.student_review_items ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.student_review_items;
CREATE POLICY tenant_isolation ON student.student_review_items
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');
```

- [ ] **Step 7: Run the integration test — verify it passes**

Run (Docker must be running): `./mvnw.cmd -q -f services/student-service/pom.xml test -Dtest=StudentRlsIntegrationTest`
Expected: PASS (5 tests) — A sees 2, B sees 1, superadmin sees 3, no-context sees 0, cross-tenant INSERT raises a row-level-security error.

- [ ] **Step 8: Run the full student-service suite**

Run: `./mvnw.cmd -q -f services/student-service/pom.xml test`
Expected: all green (existing Mockito tests + the new RLS test). The app boots with the wrapped DataSource; existing tests are unaffected (they don't hit a DB).

- [ ] **Step 9: Commit**

```bash
git add services/student-service/src/main/java/com/custoking/ims/studentservice/security/TenantAwareDataSource.java \
        services/student-service/src/main/java/com/custoking/ims/studentservice/security/TenantDataSourceConfig.java \
        services/student-service/src/main/resources/db/migration/V4__enable_rls.sql \
        services/student-service/pom.xml \
        services/student-service/src/test/java/com/custoking/ims/studentservice/security/StudentRlsIntegrationTest.java
git commit -m "feat(student): RLS backstop with per-request GUC (enforced as app_rt)"
```

---

### Task 2: attendance-service — RLS on attendance_student_records

**Files:**
- Create: `services/attendance-service/src/main/java/com/custoking/ims/attendanceservice/security/TenantAwareDataSource.java`
- Create: `services/attendance-service/src/main/java/com/custoking/ims/attendanceservice/security/TenantDataSourceConfig.java`
- Create: `services/attendance-service/src/main/resources/db/migration/V3__enable_rls.sql`
- Modify: `services/attendance-service/pom.xml`
- Test: `services/attendance-service/src/test/java/com/custoking/ims/attendanceservice/security/AttendanceRlsIntegrationTest.java`

**Copy step:** copy `TenantAwareDataSource.java` and `TenantDataSourceConfig.java` from `services/student-service/.../security/`, changing ONLY the first `package` line to `com.custoking.ims.attendanceservice.security;`. (No other identifiers differ.) Add the two Testcontainers deps to `services/attendance-service/pom.xml` exactly as in Task 1 Step 3.

- [ ] **Step 1: Migration `V3__enable_rls.sql`** — read the attendance V1/V2 migrations first to confirm `attendance_student_records` has `school_id BIGINT NOT NULL` (the survey confirmed it does). Then:
```sql
ALTER TABLE attendance.attendance_student_records ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON attendance.attendance_student_records;
CREATE POLICY tenant_isolation ON attendance.attendance_student_records
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');
```
(Do NOT add a policy to `attendance_daily` — it has no `school_id`; deferred to Task 1.4.)

- [ ] **Step 2: Test `AttendanceRlsIntegrationTest.java`** — model it on `StudentRlsIntegrationTest` (Task 1 Step 1), changing: schema `attendance`; grants on schema `attendance`; seed `attendance.attendance_student_records` (read its V1 columns first to satisfy NOT NULL columns) with school 10 ×2 and school 20 ×1; assert the same five behaviors (A→2, B→1, superadmin→3, no-context→0, cross-tenant INSERT blocked) counting `attendance.attendance_student_records`. Reuse the same `app_rt` role creation + Docker assumption.

- [ ] **Step 3: Run** `./mvnw.cmd -q -f services/attendance-service/pom.xml test -Dtest=AttendanceRlsIntegrationTest` (Docker up) → PASS; then full `...pom.xml test` → green.

- [ ] **Step 4: Commit** — `git add` the attendance-service files; `git commit -m "feat(attendance): RLS backstop on attendance_student_records (enforced as app_rt)"`

---

### Task 3: reporting-service — RLS on academic_events + event_student_contributions

**Files:**
- Create: `services/reporting-service/src/main/java/com/custoking/ims/reportingservice/security/TenantAwareDataSource.java`
- Create: `services/reporting-service/src/main/java/com/custoking/ims/reportingservice/security/TenantDataSourceConfig.java`
- Create: `services/reporting-service/src/main/resources/db/migration/V6__enable_rls.sql`
- Modify: `services/reporting-service/pom.xml`
- Test: `services/reporting-service/src/test/java/com/custoking/ims/reportingservice/security/ReportingRlsIntegrationTest.java`

**Copy step:** copy the two security files from student-service, changing the `package` line to `com.custoking.ims.reportingservice.security;`. Add the two Testcontainers deps to `services/reporting-service/pom.xml`.

- [ ] **Step 1: Migration `V6__enable_rls.sql`** — confirm from reporting V1 that `academic_events.school_id` and `event_student_contributions.school_id` are `BIGINT NOT NULL` (survey confirmed). Then enable RLS + the `tenant_isolation` policy (same USING/WITH CHECK form as Task 1 Step 6) on `reporting.academic_events` and `reporting.event_student_contributions`. Do NOT touch `command_center_actions`, `command_center_feed`, or `reporting_event_inbox` (NULLABLE / projection — deferred to Task 1.4).

- [ ] **Step 2: Test `ReportingRlsIntegrationTest.java`** — model on `StudentRlsIntegrationTest`; schema `reporting`; seed `reporting.academic_events` (read V1 columns for NOT NULL fields) with school 10 ×2, school 20 ×1; assert A→2, B→1, superadmin→3, no-context→0, cross-tenant INSERT blocked, counting `reporting.academic_events`. Same `app_rt` setup + Docker assumption.

- [ ] **Step 3: Run** `./mvnw.cmd -q -f services/reporting-service/pom.xml test -Dtest=ReportingRlsIntegrationTest` (Docker up) → PASS; then full suite → green.

- [ ] **Step 4: Commit** — `git add` the reporting-service files; `git commit -m "feat(reporting): RLS backstop on academic events (enforced as app_rt)"`

---

### Task 4: Rollout runbook + verification gate

**Files:**
- Create: `docs/MICROSERVICE-RLS-ROLLOUT-RUNBOOK.md`
- Modify: `ARCHITECTURE_REVIEW.md` (note MT-P0-2 RLS partially implemented for clean tables)

- [ ] **Step 1: Write `docs/MICROSERVICE-RLS-ROLLOUT-RUNBOOK.md`** covering:
  - The GUC contract (`app.current_school_id`, `app.bypass_rls`) and that `app_rt` is subject / `appuser` owner bypasses.
  - **Two-phase prod rollout per service:** Release 1 deploys the `TenantAwareDataSource` (GUC-setting) so every live instance sets the GUC; Release 2 ships the `V…__enable_rls.sql` migration. Never ship the migration before the datasource is live (else `app_rt` queries on RLS tables return 0 rows on instances that don't set the GUC).
  - Pre-cutover check: confirm `app_rt` is `NOBYPASSRLS` and not a `cloudsqlsuperuser` member (the Task 1.1 `app_rt` audit).
  - **Rollback:** a forward migration `DROP POLICY tenant_isolation ON <table>; ALTER TABLE <table> DISABLE ROW LEVEL SECURITY;` restores prior behavior (`app_rt` already has DML).
  - Scope note: only the clean NOT-NULL tables are covered now; NULLABLE/derived/cross-schema/fee tables follow after Task 1.4 denormalizes `school_id`.
  - Verification: run each service's `*RlsIntegrationTest` (Docker required) — they prove isolation as `app_rt`.

- [ ] **Step 2: Update `ARCHITECTURE_REVIEW.md`** — near the MT-P0-2 entry, add a dated note that RLS is now enabled on the clean NOT-NULL `school_id` tables (student, attendance, reporting) with a per-request GUC enforced for `app_rt`; remaining tables follow Task 1.4.

- [ ] **Step 3: Verification** — with Docker running:
```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
powershell -ExecutionPolicy Bypass -File scripts\invoke-microservice-tests.ps1 -Services student-service,attendance-service,reporting-service
```
Expected: all three green, including the new RLS integration tests (which run because Docker is present). Then confirm no `3.x`-style regressions elsewhere by running the full catalog if time permits.

- [ ] **Step 4: Commit** — `git add docs/MICROSERVICE-RLS-ROLLOUT-RUNBOOK.md ARCHITECTURE_REVIEW.md`; `git commit -m "docs(ops): RLS rollout runbook + architecture-review update"`

---

## Self-Review

**Spec coverage:**
- `TenantAwareDataSource` session GUC on borrow (autocommit + tx) → Task 1 (copied 2–3). ✓
- `BeanPostProcessor` wrapping the app DataSource; Flyway separate/owner → Task 1 Step 5 + runbook. ✓
- RLS `ENABLE` (not FORCE) + `tenant_isolation` policy (USING + WITH CHECK, nullif + bypass) → Tasks 1–3 migrations. ✓
- Superadmin via `bypass_rls=on`; empty context deny → datasource + tests (`superadmin_seesAll`, `noContext_seesNothing`). ✓
- Testcontainers as `app_rt` (not owner); WITH CHECK insert blocked → Task 1 test (copied 2–3). ✓
- Scope = student/attendance/reporting clean NOT-NULL tables; rest deferred to 1.4 → Global Constraints + migrations exclude derived/nullable tables. ✓
- Two-phase rollout + rollback → Task 4 runbook. ✓
- Docker-availability assumption keeps `mvn test` green without Docker → Task 1 test + Global Constraints. ✓

**Placeholder scan:** none — full code for the datasource, config, migration, and the pilot test; Tasks 2–3 give the exact copy step, the exact migration SQL form, exact file paths, and "read V1 columns first" only for *seed* values (the migration tables/columns are named explicitly and confirmed NOT NULL by the survey). ✓

**Type/name consistency:** `TenantAwareDataSource(DataSource)`, `TenantDataSourceConfig` BeanPostProcessor, GUCs `app.current_school_id`/`app.bypass_rls`, policy `tenant_isolation`, `TenantContext.get()/.schoolId()/.isSuperAdmin()` — consistent across all tasks. Migration versions: student V4, attendance V3, reporting V6 (each = latest+1). ✓

## Risks
- Test-as-owner false pass → tests connect as `app_rt`; a deliberate `noContext_seesNothing` + cross-tenant-INSERT-blocked anchor the distinction.
- `BeanPostProcessor` wrapping the wrong datasource → it wraps any `DataSource` bean; Flyway's datasource is internal (not a bean) so only the app pool is wrapped. If a service ever exposes a second DataSource bean, the guard `!(bean instanceof TenantAwareDataSource)` prevents double-wrap but both would be wrapped — revisit if that arises (none in scope).
- Docker absent in some CI lane → the assumption skips the test (no false failure); CI with docker-compose runs it.
- Deferred tables (fee/derived/nullable) have no DB backstop yet → app-layer (1.2) covers them until Task 1.4.

---

## Follow-up — RLS extension to the Task-1.4 (tenant-key) tables

> Added 2026-06-30 after Task 1.4 (`docs/superpowers/plans/2026-06-30-tenant-keys.md`, branch `phase1-tenant-keys`, PR #14) made `school_id` `NOT NULL` + denormalized + tenant-leading-indexed on the remaining scoped tables. Those tables are now eligible for the same RLS backstop pattern this plan established. This section is the seed for that extension work — it is NOT yet implemented.

**Now-eligible tables** (all have `NOT NULL school_id` after Task 1.4):
- catalog: `catalog_orders`, `annual_plan_items`
- reporting: `command_center_actions` (feed/inbox **stay NULLABLE** — NULL = platform-wide; still excluded)
- firefighting: `firefighting_requests`, `ff_quotations`
- workflow: `workflow_instances`, `workflow_actions`
- attendance: `attendance_daily`
- fee: `fee_assignments`, `payment_records` (`fee_bands`/`fee_items` remain global — excluded)

**Per-service prerequisite — `TenantAwareDataSource`:** Tasks 1–3 of this plan added the GUC-setting `TenantAwareDataSource` + `TenantDataSourceConfig` only to **student, attendance, reporting**. attendance + reporting already have it; **catalog, firefighting, workflow, fee do NOT** and must get the copy step (Task 2 "Copy step" form) before their `enable_rls` migration ships, or `app_rt` queries return 0 rows. Each table then gets the standard `ENABLE ROW LEVEL SECURITY` + `tenant_isolation` policy migration (same USING/WITH CHECK form as Task 1 Step 6) and an `app_rt` `*RlsIntegrationTest`. Two-phase rollout per the RLS runbook (datasource release, then migration release).

### Carry-forward items from the Task 1.4 final review (must be addressed by this extension)

1. **[Important — fix before enabling RLS on `attendance_daily`] Fail-loud instead of defaulting `school_id` to 0.** `services/attendance-service/src/main/java/com/custoking/ims/attendanceservice/persistence/AttendanceReadRepository.java:432` binds `longNum(section.get("schoolId"), 0)` into the `attendance_daily` INSERT. The path is unreachable today (a section always has a school), but once RLS is enabled a stray `school_id = 0` row is invisible to **every** real tenant and never reconciled. Change to fail-loud (throw / `requirePositive`) when a section has no school **before** the `attendance_daily` RLS policy goes live.

2. **[Pre-cutover gate] Reconcile orphaned/mis-scoped children before enabling RLS.** The two cross-schema backfills (attendance_daily ← `tenant_school.school_sections`; fee_assignments/payment_records ← `student.students`) derive `school_id` from a parent that may have been deleted/archived. Task 1.4's `SET NOT NULL` already rejects rows the backfill left NULL, so the tenant-key runbook's orphan pre-check (`docs/MICROSERVICE-TENANT-KEY-ROLLOUT-RUNBOOK.md`, "investigate orphans before Release 2") **must actually be executed**: a child whose `school_id` was backfilled from the wrong/deleted parent would be mis-scoped under RLS (visible to the wrong tenant or to none). Verify zero orphans/mis-scoped rows per table before each `enable_rls` migration.

3. **[Test rigor] Bring firefighting/workflow backfill tests up to the Flyway-driven pattern.** `FirefightingTenantKeyMigrationTest.java:103` and `WorkflowTenantKeyMigrationTest.java:120` exercise their same-schema backfill via a hand-copied `UPDATE` string rather than letting Flyway run the migration (attendance/fee added a Flyway-driven guard-TRUE-path test). When this extension adds the RLS migrations/tests for those tables, follow the attendance/fee pattern so the migration's own backfill/policy is what the test proves. Low-value on its own (trivial same-schema SQL) but cheap to fold in here.
