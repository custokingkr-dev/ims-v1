# Isolation Follow-ups Round A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`).

**Goal:** Two independent isolation follow-ups — resolve the command-center academic year dynamically (platform), and add RLS backstops to the `tenant_school` tables (school-core).

**Architecture:** Task 1 = reuse an existing helper for 2 query predicates. Task 2 = one Flyway migration enabling RLS + a `tenant_isolation` policy per table, mirroring the schema's existing timetable RLS, plus an integration test. Independent tasks (different services).

**Tech Stack:** Spring Boot 4.0.7 / Java 25 (platform-service, school-core-service), Postgres RLS.

## Global Constraints

- Specs: `docs/superpowers/specs/2026-07-08-academic-year-resolution-design.md`, `docs/superpowers/specs/2026-07-08-tenant-school-rls-backstop-design.md`.
- RLS policy shape is copied verbatim from the spec; `app_rt` already has grants (no new GRANT needed). Datasource (`TenantAwareDataSource`) is already live in school-core.
- Backend TDD. Do NOT commit `.claude/settings.local.json`.
- Build/test (Windows Bash tool): `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/<svc>/pom.xml -q -Dtest=<T> test`.

---

### Task 1: platform-service — dynamic academic-year in command-center summary

**Files:**
- Modify: `services/platform-service/src/main/java/com/custoking/ims/platformservice/persistence/ReportingReadRepository.java` (`commandCenterSummary`, the `overdueCount` ~:756 and `attendanceSections` ~:783 queries)
- Test: extend the command-center summary test (or `ReportingFactReadIntegrationTest`).

- [ ] **Step 1: Write the failing test**

Extend the platform command-center test: seed `reporting.dim_academic_year` with an active year `Y`, and `fact_fee_assignment` + `fact_attendance_daily` rows tagged with `Y` under school A (plus a row tagged with a DIFFERENT year to prove the filter binds the resolved year, not a literal). Call `commandCenterSummary(schoolA, false)`; assert the "Attendance Today" and fees-overdue KPIs reflect only the active-year rows. Add a second case: with NO active `dim_academic_year` row, the KPIs are `0` (no error). Read `ReportingFactReadIntegrationTest`/any `*CommandCenter*` test first for the seeding harness.

- [ ] **Step 2: Run — verify failure**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/platform-service/pom.xml -q -Dtest='*CommandCenter*,*ReportingReadRepository*,*ReportingFactRead*' test`
Expected: the new assertions FAIL (queries currently filter the hardcoded `'ay_2025_26'`, not the seeded active year).

- [ ] **Step 3: Resolve the year dynamically**

In `commandCenterSummary`'s per-school branch (after the `schoolId == null` early return), add `String yearId = activeAcademicYearId();`. Replace `academic_year_id = 'ay_2025_26'` at both the `overdueCount` and `attendanceSections` queries with `academic_year_id = :yearId`, binding `yearId`. Guard like the sibling methods (`dashboardCommandCenter`/`feeDefaulters`): if `yearId == null`, set `overdueCount`/`attendanceSections` to `0` without running the query (avoids null-bind). Keep the existing `school_id = :schoolId` and `attendance_date = CURRENT_DATE` predicates.

- [ ] **Step 4: Run — GREEN + full platform suite**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/platform-service/pom.xml -q test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add services/platform-service/src/main/java/com/custoking/ims/platformservice/persistence/ReportingReadRepository.java \
        services/platform-service/src/test/java/com/custoking/ims/platformservice/
git commit -m "fix(platform): resolve command-center academic year dynamically (drop hardcoded ay_2025_26)"
```

---

### Task 2: school-core-service — RLS backstop on tenant_school tables

**Files:**
- Create: `services/school-core-service/src/main/resources/db/migration/tenant_school/V10__enable_rls_tenant_school.sql` (confirm V10 is the next free version in that folder first)
- Test: Create `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/security/TenantSchoolRlsIntegrationTest.java`

- [ ] **Step 1: Confirm the next migration version + read the mirror**

List `services/school-core-service/src/main/resources/db/migration/tenant_school/` — confirm the highest existing `V<n>` and use `V<n+1>` (spec assumes V10). Read `tenant_school/V5__timetable_masters.sql` (the RLS mirror in this schema) and `services/school-core-service/src/test/java/.../security/*RlsIntegrationTest.java` (or `firefighting`/`catalog` RLS tests) for the exact policy shape + Testcontainers `app_rt` harness.

- [ ] **Step 2: Write the failing RLS integration test**

Create `TenantSchoolRlsIntegrationTest` mirroring an existing `*RlsIntegrationTest` (Testcontainers, migrate as owner, create `app_rt LOGIN ... NOBYPASSRLS`, wrap in `TenantAwareDataSource`). Seed two schools' rows in `staff_members`, `school_sections`, `school_module_entitlements`, and `schools` (school A id=10, school B id=20). Assert (via the `app_rt` pool): school-A context (`TenantContext` schoolId=10) sees only A's rows in each table; school-B sees only B's; superadmin context sees all; no-context sees none; a cross-tenant `INSERT` (school B row under school-A context) is blocked by `WITH CHECK`. For the `schools` table the policy keys on `id` (not `school_id`) — assert school-A sees only the `id=10` row. Also seed a `zone_school_mappings` row and assert a non-superadmin `app_rt` (school context) sees ZERO rows while superadmin (bypass) sees it (Group C bypass-only).

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/school-core-service/pom.xml -q -Dtest=TenantSchoolRlsIntegrationTest test`
Expected: FAIL — RLS not enabled yet (school-A sees both schools' rows; zone rows visible to app_rt).

- [ ] **Step 3: Write the migration**

Create `V10__enable_rls_tenant_school.sql`. For **Group A** (`staff_members`, `school_sections`, `school_module_entitlements`): `ENABLE ROW LEVEL SECURITY` + `tenant_isolation` policy with the standard `school_id = nullif(current_setting('app.current_school_id', true),'')::bigint OR current_setting('app.bypass_rls', true)='on'` on both `USING` and `WITH CHECK`. For **Group B** (`schools`): same but keyed on `id` instead of `school_id`. For **Group C** (`zones`, `zone_school_mappings`, `zone_admin_assignments`): `ENABLE ROW LEVEL SECURITY` + policy `USING (current_setting('app.bypass_rls', true)='on') WITH CHECK (same)` (bypass-only). Use `DROP POLICY IF EXISTS tenant_isolation ON tenant_school.<t>;` before each `CREATE POLICY` (idempotent, matches V5 style). Add a top comment noting reporting fact/dim tables are intentionally excluded (projector-write posture). Reference the exact policy SQL from the spec.

- [ ] **Step 4: Run — GREEN + full school-core suite**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/school-core-service/pom.xml -q test`
Expected: BUILD SUCCESS — the new RLS test passes AND every existing school-core test still passes (the existing app-level filtering means RLS is redundant-but-consistent for current paths; if any existing test seeds `tenant_school` rows under a mismatched/absent context and reads them via `app_rt`, it will now correctly see nothing — investigate any such failure: it is either a test that ran without setting `TenantContext` (fix the test to set context/superadmin) or a real cross-tenant read the RLS just closed).

- [ ] **Step 5: Commit**

```bash
git add services/school-core-service/src/main/resources/db/migration/tenant_school/V10__enable_rls_tenant_school.sql \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/security/TenantSchoolRlsIntegrationTest.java
git commit -m "feat(school-core): RLS backstop on tenant_school tables (schools/sections/staff/entitlements + zone bypass-only)"
```

---

## Self-Review

**Spec coverage:** academic-year (2 sites → dynamic + null fallback) → Task 1. RLS Groups A/B/C + integration test → Task 2. Reporting-facts deferral documented in the migration comment. **Placeholder scan:** the "confirm V10 / read the mirror" and "read the harness" steps are read-first instructions pointing at named files. **Consistency:** policy SQL identical between spec and Task 2 Step 3; `activeAcademicYearId()` (existing) reused in Task 1.
