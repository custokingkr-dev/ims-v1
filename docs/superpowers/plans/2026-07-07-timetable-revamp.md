# Timetable Revamp Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the append-only free-text timetable with a grid-based, per-academic-year timetable backed by proper master data (per-grade bell schedules, per-class-per-year subjects), with CRUD, teacher/section conflict warnings, and year-locking of past years.

**Architecture:** All data lives in `tenant_school` (school-core-service). New master tables (bell schedules, periods, class→schedule map, per-class-per-year subjects) plus a reworked `school_timetable_entries`. A new `TimetableRepository` + `TimetableController` (compat `/api/v1/timetable/**`) own all reads/writes; the gateway routes that prefix to school-core ("tenant"). The frontend gets two Setup panels (bell schedules, subjects master) and a grid panel replacing `TimetablePanel`. The legacy `/api/v1/workspace/timetable` write and the `timetable` key in the `/api/v1/workspace` bundle (platform-service) are removed.

**Tech Stack:** Java 25 / Spring Boot 4 / `JdbcClient` / Flyway (school-core); React 18 + Vite + TS (frontend); Node http gateway.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-07-timetable-revamp-design.md` — this plan implements it verbatim.
- Schema owner: `tenant_school` (school-core-service). New Flyway migrations continue that history from **V5** (highest existing is `V4__school_timetable.sql`).
- Every new/reworked table gets the standard RLS policy (USING + WITH CHECK on `school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on'`), ENABLE (not FORCE) so the Flyway owner/appuser bypasses.
- **Active year** = `SELECT id FROM tenant_school.academic_years ORDER BY active DESC, id DESC LIMIT 1`. A year is editable **iff** it equals the active year; all writes to a non-active year return HTTP 409.
- **Backend authz pattern (verified):** controllers gate on the internal token via `requireToken(token, "tenant-school:write"|"tenant-school:read")` and scope via `TenantScope`. Reads use `TenantScope.resolveSchoolId(requested)`; **admin-only writes call `TenantScope.requireSchoolAdmin()`** (accepts ADMIN/SCHOOL_ADMIN, superadmin bypasses). There is NO backend end-user-permission-code check — that gating is frontend-only.
- **Frontend permission gating:** nav/panels gate on permission codes via `usePermissions()` `can('timetable:manage')` / `can('timetable:read')` (codes already exist in `frontend/src/shared/permissions/permissions.ts`). Superadmin/admin have manage; teacher has read.
- **No frontend tests** (user decision). Frontend tasks verify via `cd frontend && npm run build`. Backend tasks include Mockito unit tests and Testcontainers integration tests, run with:
  `$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; .\mvnw.cmd -f services\school-core-service\pom.xml -q test`
- Gateway routes are added in `services/api-gateway/server.js` near the other `route('tenant', …)` entries (~line 99-111). `route(name, prefix)` proxies any path starting with `prefix` to that upstream.
- Days set: `["Mon","Tue","Wed","Thu","Fri","Sat"]` (validated app-side).
- DTO validation via `@Valid` where a typed DTO is used; untyped `Map<String,Object>` bodies validate inline (matching existing controllers).
- Testcontainers run as an RLS-exempt role — integration tests assert app-level `TenantScope` behavior and CRUD correctness, NOT RLS row-filtering (RLS is defense-in-depth).

---

## File Structure

**school-core-service (create):**
- `…/db/migration/tenant_school/V5__timetable_masters.sql` — bell schedules, periods, class→schedule map, class subjects (+RLS).
- `…/db/migration/tenant_school/V6__timetable_entries_rework.sql` — drop+recreate `school_timetable_entries` (+RLS).
- `…/persistence/TimetableRepository.java` — all timetable reads/writes (JdbcClient).
- `…/api/TimetableController.java` — compat `/api/v1/timetable/**` endpoints.
- `…/api/TimetableControllerTest.java`, `…/persistence/TimetableRepositoryIntegrationTest.java` — tests.

**school-core-service (modify):**
- `…/api/compat/TenantSchoolPublicCompatibilityController.java` — remove `addTimetableFromWorkspace` (`/api/v1/workspace/timetable`).
- `…/persistence/SchoolStructureReadRepository.java` — remove `addTimetableEntry` + `timetableRow`.

**platform-service (modify):**
- `…/persistence/ReportingReadRepository.java` — remove `timetable(...)`.
- `…/api/compat/ReportingPublicCompatibilityController.java` — remove `response.put("timetable", …)` from `workspace`.

**api-gateway (modify):** `server.js` — add `route('tenant', '/api/v1/timetable/')`; remove `route('tenant', '/api/v1/workspace/timetable')`.

**frontend (create):**
- `src/pages/workspace/panels/setup/BellSchedulesPanel.tsx`
- `src/pages/workspace/panels/setup/SubjectsMasterPanel.tsx`
- `src/pages/workspace/panels/TimetableGrid.tsx` (the new grid; replaces the body of `TimetablePanel.tsx`)
- `src/services/timetableApi.ts` — typed client for `/timetable/**`.

**frontend (modify):**
- `src/pages/workspace/panels/TimetablePanel.tsx` — becomes a thin host rendering `TimetableGrid`.
- `src/pages/workspace/config.ts` — add `bellschedules` + `subjectsmaster` to admin Setup; keep `timetable` in Academics/teacher Classroom.
- `src/pages/UnifiedWorkspacePage.tsx` — render the two new Setup panels + pass role (isTeacher) to the grid.
- `src/types/workspace.ts` — drop the bundle `timetable` type; add timetable API types (or in timetableApi.ts).

---

## PHASE A — master data + Setup

### Task 1: Migrations — master tables + timetable rework (+RLS)

**Files:**
- Create: `services/school-core-service/src/main/resources/db/migration/tenant_school/V5__timetable_masters.sql`
- Create: `services/school-core-service/src/main/resources/db/migration/tenant_school/V6__timetable_entries_rework.sql`

**Interfaces — Produces:** tables `school_bell_schedules(id,school_id,name,created_at)`,
`school_bell_periods(id,school_id,schedule_id,sort_order,label,start_time,end_time,is_break)`,
`school_class_bell_map(school_id,class_id,schedule_id)`,
`school_class_subjects(id,school_id,class_id,academic_year_id,subject_name,sort_order,created_at)`,
reworked `school_timetable_entries(id,school_id,academic_year_id,section_id,day_name,bell_period_id,subject_name,teacher_id,updated_at)` with unique slot.

- [ ] **Step 1: Write V5 (masters).** Create `V5__timetable_masters.sql`:

```sql
-- Timetable master data: bell schedules, periods, class->schedule map, per-class-per-year subjects.

CREATE TABLE tenant_school.school_bell_schedules (
    id         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    school_id  BIGINT NOT NULL REFERENCES tenant_school.schools(id),
    name       VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_bell_sched_school_name UNIQUE (school_id, name)
);

CREATE TABLE tenant_school.school_bell_periods (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    school_id   BIGINT NOT NULL REFERENCES tenant_school.schools(id),
    schedule_id BIGINT NOT NULL REFERENCES tenant_school.school_bell_schedules(id) ON DELETE CASCADE,
    sort_order  INTEGER NOT NULL,
    label       VARCHAR(64) NOT NULL,
    start_time  TIME NOT NULL,
    end_time    TIME NOT NULL,
    is_break    BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT uk_bell_period_order UNIQUE (schedule_id, sort_order),
    CONSTRAINT ck_bell_period_time CHECK (end_time > start_time)
);
CREATE INDEX idx_bell_periods_sched ON tenant_school.school_bell_periods (schedule_id, sort_order);

CREATE TABLE tenant_school.school_class_bell_map (
    school_id   BIGINT NOT NULL REFERENCES tenant_school.schools(id),
    class_id    VARCHAR(255) NOT NULL REFERENCES tenant_school.school_classes(id),
    schedule_id BIGINT NOT NULL REFERENCES tenant_school.school_bell_schedules(id) ON DELETE CASCADE,
    PRIMARY KEY (school_id, class_id)
);

CREATE TABLE tenant_school.school_class_subjects (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    school_id        BIGINT NOT NULL REFERENCES tenant_school.schools(id),
    class_id         VARCHAR(255) NOT NULL REFERENCES tenant_school.school_classes(id),
    academic_year_id VARCHAR(255) NOT NULL REFERENCES tenant_school.academic_years(id),
    subject_name     VARCHAR(120) NOT NULL,
    sort_order       INTEGER NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_class_subject UNIQUE (school_id, class_id, academic_year_id, subject_name)
);
CREATE INDEX idx_class_subjects_lookup ON tenant_school.school_class_subjects (school_id, class_id, academic_year_id);

-- RLS (ENABLE not FORCE: Flyway owner/appuser bypasses; app_rt is subject).
ALTER TABLE tenant_school.school_bell_schedules ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_school.school_bell_schedules
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on');
ALTER TABLE tenant_school.school_bell_periods ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_school.school_bell_periods
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on');
ALTER TABLE tenant_school.school_class_bell_map ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_school.school_class_bell_map
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on');
ALTER TABLE tenant_school.school_class_subjects ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_school.school_class_subjects
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on');

DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='app_rt') THEN
  GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_school.school_bell_schedules, tenant_school.school_bell_periods, tenant_school.school_class_bell_map, tenant_school.school_class_subjects TO app_rt;
  GRANT USAGE, SELECT ON SEQUENCE tenant_school.school_bell_schedules_id_seq, tenant_school.school_bell_periods_id_seq, tenant_school.school_class_subjects_id_seq TO app_rt;
END IF; END $$;
```

- [ ] **Step 2: Write V6 (timetable rework).** Create `V6__timetable_entries_rework.sql`:

```sql
-- Rework: the old school_timetable_entries was free-text and had no RLS. Drop and recreate
-- with FK-based, year-scoped columns. Forward-only; the few legacy free-text dev rows are
-- intentionally not migrated.
DROP TABLE IF EXISTS tenant_school.school_timetable_entries;

CREATE TABLE tenant_school.school_timetable_entries (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    school_id        BIGINT NOT NULL REFERENCES tenant_school.schools(id),
    academic_year_id VARCHAR(255) NOT NULL REFERENCES tenant_school.academic_years(id),
    section_id       VARCHAR(255) NOT NULL REFERENCES tenant_school.school_sections(id) ON DELETE CASCADE,
    day_name         VARCHAR(16) NOT NULL,
    bell_period_id   BIGINT NOT NULL REFERENCES tenant_school.school_bell_periods(id) ON DELETE CASCADE,
    subject_name     VARCHAR(120) NOT NULL,
    teacher_id       BIGINT REFERENCES tenant_school.staff_members(id) ON DELETE SET NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_tt_slot UNIQUE (school_id, academic_year_id, section_id, day_name, bell_period_id)
);
CREATE INDEX idx_tt_section_year ON tenant_school.school_timetable_entries (school_id, academic_year_id, section_id);
CREATE INDEX idx_tt_teacher_slot ON tenant_school.school_timetable_entries (school_id, academic_year_id, day_name, bell_period_id, teacher_id);

ALTER TABLE tenant_school.school_timetable_entries ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_school.school_timetable_entries
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on');

DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='app_rt') THEN
  GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_school.school_timetable_entries TO app_rt;
  GRANT USAGE, SELECT ON SEQUENCE tenant_school.school_timetable_entries_id_seq TO app_rt;
END IF; END $$;
```

- [ ] **Step 3: Compile + run existing suite to confirm migrations apply cleanly.**

Run: `.\mvnw.cmd -f services\school-core-service\pom.xml -q test`
Expected: BUILD SUCCESS (Testcontainers boots and runs all tenant_school migrations incl. V5/V6; existing tests unaffected — the old timetable table had no test coverage).

- [ ] **Step 4: Commit.**

```bash
git add services/school-core-service/src/main/resources/db/migration/tenant_school/V5__timetable_masters.sql services/school-core-service/src/main/resources/db/migration/tenant_school/V6__timetable_entries_rework.sql
git commit -m "feat(timetable): master-data + reworked timetable tables with RLS (migrations)"
```

---

### Task 2: Bell schedules + class-schedule backend (repository, controller, gateway)

**Files:**
- Create: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/TimetableRepository.java`
- Create: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/TimetableController.java`
- Create test: `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/TimetableRepositoryIntegrationTest.java`
- Modify: `services/api-gateway/server.js`

**Interfaces — Produces (used by later tasks + frontend):**
- Repo methods:
  - `List<Map<String,Object>> bellSchedules(Long schoolId)` → `[{id,name,periods:[{id,label,start,end,isBreak,sortOrder}]}]`
  - `Map<String,Object> createSchedule(Long schoolId, String name)` → the created schedule row
  - `void renameSchedule(Long schoolId, long id, String name)` / `void deleteSchedule(Long schoolId, long id)`
  - `Map<String,Object> addPeriod(Long schoolId, long scheduleId, String label, String start, String end, boolean isBreak, int sortOrder)`
  - `void updatePeriod(Long schoolId, long periodId, String label, String start, String end, boolean isBreak, int sortOrder)` / `void deletePeriod(Long schoolId, long periodId)`
  - `List<Map<String,Object>> classSchedules(Long schoolId)` → `[{classId,className,scheduleId}]` (all classes that have sections in this school; scheduleId null if unmapped)
  - `void setClassSchedule(Long schoolId, String classId, long scheduleId)` (upsert)
- Controller endpoints (see steps).

- [ ] **Step 1: Write the failing integration test** `TimetableRepositoryIntegrationTest` for bell-schedule CRUD. Follow the existing Testcontainers integration-test pattern in the repo (find one, e.g. a `*IntegrationTest` that `@Autowired`s a repository and migrates `tenant_school`; copy its class/annotation setup). Seed a school + a class + a section via SQL, then:

```java
@Test
void createsScheduleWithPeriodsAndMapsClass() {
    long schoolId = seedSchool();               // helper inserts into tenant_school.schools, returns id
    String classId = seedClass(schoolId, "6");  // inserts school_classes + a school_section for this school
    var sched = repo.createSchedule(schoolId, "Primary");
    long schedId = ((Number) sched.get("id")).longValue();
    repo.addPeriod(schoolId, schedId, "P1", "08:00", "08:45", false, 1);
    repo.addPeriod(schoolId, schedId, "P2", "08:45", "09:30", false, 2);
    repo.setClassSchedule(schoolId, classId, schedId);

    var schedules = repo.bellSchedules(schoolId);
    assertThat(schedules).hasSize(1);
    assertThat((List<?>) schedules.get(0).get("periods")).hasSize(2);

    var classMaps = repo.classSchedules(schoolId);
    assertThat(classMaps).anySatisfy(m -> {
        assertThat(m.get("classId")).isEqualTo(classId);
        assertThat(((Number) m.get("scheduleId")).longValue()).isEqualTo(schedId);
    });
}
```

- [ ] **Step 2: Run to verify it fails** (repo class doesn't exist).
Run: `.\mvnw.cmd -f services\school-core-service\pom.xml -q -Dtest=TimetableRepositoryIntegrationTest test` → FAIL (compile: `TimetableRepository` not found).

- [ ] **Step 3: Implement `TimetableRepository` bell-schedule methods.** Constructor takes `JdbcClient jdbc` (`@Repository`, mirror `CatalogReadRepository`). Key SQL:
  - `bellSchedules`: `SELECT id, name FROM tenant_school.school_bell_schedules WHERE school_id=:s ORDER BY name`, then for each load periods `SELECT id, sort_order, label, start_time, end_time, is_break FROM tenant_school.school_bell_periods WHERE schedule_id=:sid ORDER BY sort_order`, map keys to `{id,label,start,end,isBreak,sortOrder}` (format times as `HH:mm` strings).
  - `createSchedule`: `INSERT ... (school_id,name) VALUES (:s,:n) RETURNING id, name`. Catch `DuplicateKeyException` → `IllegalArgumentException("A schedule named '"+name+"' already exists")`.
  - `addPeriod`: `INSERT ... RETURNING ...`; times parsed via `LocalTime.parse` (expects `HH:mm`); catch dup sort_order → `IllegalArgumentException`.
  - `classSchedules`: `SELECT DISTINCT sc.id AS class_id, sc.name AS class_name, m.schedule_id FROM tenant_school.school_sections ss JOIN tenant_school.school_classes sc ON sc.id=ss.school_class_id LEFT JOIN tenant_school.school_class_bell_map m ON m.school_id=ss.school_id AND m.class_id=sc.id WHERE ss.school_id=:s ORDER BY sc.sort_order`.
  - `setClassSchedule`: `INSERT ... (school_id,class_id,schedule_id) VALUES (:s,:c,:sid) ON CONFLICT (school_id,class_id) DO UPDATE SET schedule_id=EXCLUDED.schedule_id`.
  - `deleteSchedule`/`deletePeriod`/`renameSchedule`/`updatePeriod`: parameterized DELETE/UPDATE scoped by `school_id` (and id).
  All writes are `@Transactional`. All reads filter by `school_id`.

- [ ] **Step 4: Run the test → PASS.**
Run: `.\mvnw.cmd -f services\school-core-service\pom.xml -q -Dtest=TimetableRepositoryIntegrationTest test` → PASS.

- [ ] **Step 5: Implement `TimetableController`** (bell-schedule + class-schedule endpoints). Mirror `CatalogPublicCompatibilityController`: constructor injects `TimetableRepository` + `@Value("${tenant-school.read-token:}")`… actually reuse the existing tenant-school token property used by `TenantSchoolPublicCompatibilityController` (find its `@Value` key and `requireToken` helper; copy both). Endpoints (all `@RequestHeader X-Tenant-School-Token`):
  - `GET /api/v1/timetable/bell-schedules` → `requireToken(t,"tenant-school:read"); Long s=TenantScope.resolveSchoolId(schoolIdParam); return repo.bellSchedules(s);`
  - `POST /api/v1/timetable/bell-schedules` (body `{name}`) → `requireToken(t,"tenant-school:write"); TenantScope.requireSchoolAdmin(); Long s=TenantScope.resolveSchoolId(null); return repo.createSchedule(s, name);` wrap `IllegalArgumentException`→400.
  - `PUT /api/v1/timetable/bell-schedules/{id}` (body `{name}`), `DELETE /api/v1/timetable/bell-schedules/{id}` — write pattern (requireToken write + requireSchoolAdmin + resolve own school).
  - `POST /api/v1/timetable/bell-schedules/{id}/periods` (body `{label,start,end,isBreak,sortOrder}`), `PUT …/periods/{periodId}`, `DELETE …/periods/{periodId}`.
  - `GET /api/v1/timetable/class-schedules` (read), `PUT /api/v1/timetable/class-schedules/{classId}` (body `{scheduleId}`, write).

- [ ] **Step 6: Add gateway route.** In `services/api-gateway/server.js`, add near the tenant routes (~line 99): `route('tenant', '/api/v1/timetable/'),`. Run `cd services/api-gateway && node --test server.test.js` → existing tests pass.

- [ ] **Step 7: Full suite green + commit.**
Run: `.\mvnw.cmd -f services\school-core-service\pom.xml -q test` → BUILD SUCCESS.
```bash
git add services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/TimetableRepository.java services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/TimetableController.java services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/TimetableRepositoryIntegrationTest.java services/api-gateway/server.js
git commit -m "feat(timetable): bell-schedule + class-schedule CRUD API"
```

---

### Task 3: Subjects master backend (year-locked)

**Files:**
- Modify: `TimetableRepository.java` (add subjects methods)
- Modify: `TimetableController.java` (add subjects endpoints)
- Modify: `TimetableRepositoryIntegrationTest.java` (add year-lock test)

**Interfaces — Produces:**
- `String activeYearId(Long schoolId)` → the active academic_year id (or null if none).
- `Map<String,Object> classSubjects(Long schoolId, String classId, String yearId)` → `{ editable:boolean, yearId, subjects:[{id,subjectName,sortOrder}] }` (`editable` = yearId equals active year).
- `Map<String,Object> addSubject(Long schoolId, String classId, String yearId, String subjectName)` — throws `YearLockedException` (new runtime exception in this package) if yearId != active year.
- `void deleteSubject(Long schoolId, long subjectId)` — throws `YearLockedException` if the subject's year != active year.

- [ ] **Step 1: Write failing test** in `TimetableRepositoryIntegrationTest`:

```java
@Test
void rejectsSubjectEditsForNonActiveYear() {
    long schoolId = seedSchool();
    String classId = seedClass(schoolId, "6");
    String active = seedYear(schoolId, "ay_2026_27", true);   // helper inserts academic_years
    String past = seedYear(schoolId, "ay_2025_26", false);

    var added = repo.addSubject(schoolId, classId, active, "Mathematics");
    assertThat(added.get("id")).isNotNull();
    assertThat(repo.classSubjects(schoolId, classId, active).get("editable")).isEqualTo(true);
    assertThat(repo.classSubjects(schoolId, classId, past).get("editable")).isEqualTo(false);

    assertThatThrownBy(() -> repo.addSubject(schoolId, classId, past, "History"))
        .isInstanceOf(YearLockedException.class);
}
```

- [ ] **Step 2: Run → FAIL** (methods/exception missing).
Run: `.\mvnw.cmd -f services\school-core-service\pom.xml -q -Dtest=TimetableRepositoryIntegrationTest test` → FAIL.

- [ ] **Step 3: Implement.** Add `public final class YearLockedException extends RuntimeException` (own file in `…/persistence/` or a nested static class) with a message constructor. In `TimetableRepository`:
  - `activeYearId(schoolId)`: `SELECT id FROM tenant_school.academic_years ORDER BY active DESC, id DESC LIMIT 1` (note: academic_years currently has no school_id per V1 — confirm; if it does, filter by school. Use `.optional()` → null when none).
  - `classSubjects`: load `SELECT id, subject_name, sort_order FROM tenant_school.school_class_subjects WHERE school_id=:s AND class_id=:c AND academic_year_id=:y ORDER BY sort_order, subject_name`; return `{editable: y.equals(activeYearId(s)), yearId:y, subjects:[...]}`.
  - `addSubject`: `if(!yearId.equals(activeYearId(schoolId))) throw new YearLockedException("Subjects for "+yearId+" are locked — the year has ended");` else `INSERT ... RETURNING id`; catch dup → `IllegalArgumentException("'"+subjectName+"' already exists for this class/year")`. `@Transactional`.
  - `deleteSubject`: look up the subject's `academic_year_id` (scoped by school_id); if != active → `YearLockedException`; else DELETE.

- [ ] **Step 4: Run → PASS.**

- [ ] **Step 5: Controller endpoints.** In `TimetableController`:
  - `GET /api/v1/timetable/class-subjects?classId=&yearId=` (read; if yearId blank default to active) → `repo.classSubjects(s, classId, yearId)`.
  - `POST /api/v1/timetable/class-subjects` (body `{classId, subjectName}`, write) → resolve own school, `yearId = active`, `repo.addSubject(...)`. Catch `YearLockedException` → 409 (`ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage())`); `IllegalArgumentException` → 400.
  - `DELETE /api/v1/timetable/class-subjects/{id}` (write) → `repo.deleteSubject`; same exception mapping.

- [ ] **Step 6: Suite green + commit.**
Run: `.\mvnw.cmd -f services\school-core-service\pom.xml -q test` → BUILD SUCCESS.
```bash
git add -A services/school-core-service
git commit -m "feat(timetable): per-class-per-year subjects master with year lock"
```

---

### Task 4: Frontend Setup — Bell Schedules panel + nav

**Files:**
- Create: `frontend/src/services/timetableApi.ts`
- Create: `frontend/src/pages/workspace/panels/setup/BellSchedulesPanel.tsx`
- Modify: `frontend/src/pages/workspace/config.ts` (add `bellschedules` to admin Setup; add `PanelKey`)
- Modify: `frontend/src/pages/UnifiedWorkspacePage.tsx` (render panel)

**Interfaces — Consumes:** the Task 2 endpoints. **Produces:** `timetableApi` client used by Tasks 5 & 7.

- [ ] **Step 1: Create `timetableApi.ts`** with typed functions using the shared `api` axios instance (mirror another `src/services/*Api.ts` if present, else use `import api from './api'`). Include all `/timetable/**` calls with TS types:

```ts
import api from './api';
export interface BellPeriod { id: number; label: string; start: string; end: string; isBreak: boolean; sortOrder: number; }
export interface BellSchedule { id: number; name: string; periods: BellPeriod[]; }
export interface ClassScheduleRow { classId: string; className: string; scheduleId: number | null; }
export const getBellSchedules = (p?: object) => api.get<BellSchedule[]>('/timetable/bell-schedules', { params: p });
export const createSchedule = (name: string) => api.post('/timetable/bell-schedules', { name });
export const renameSchedule = (id: number, name: string) => api.put(`/timetable/bell-schedules/${id}`, { name });
export const deleteSchedule = (id: number) => api.delete(`/timetable/bell-schedules/${id}`);
export const addPeriod = (id: number, b: Omit<BellPeriod,'id'>) => api.post(`/timetable/bell-schedules/${id}/periods`, b);
export const updatePeriod = (id: number, pid: number, b: Omit<BellPeriod,'id'>) => api.put(`/timetable/bell-schedules/${id}/periods/${pid}`, b);
export const deletePeriod = (id: number, pid: number) => api.delete(`/timetable/bell-schedules/${id}/periods/${pid}`);
export const getClassSchedules = (p?: object) => api.get<ClassScheduleRow[]>('/timetable/class-schedules', { params: p });
export const setClassSchedule = (classId: string, scheduleId: number) => api.put(`/timetable/class-schedules/${encodeURIComponent(classId)}`, { scheduleId });
// subjects (Task 5) + timetable (Task 7) added in their tasks.
```

- [ ] **Step 2: Build `BellSchedulesPanel.tsx`.** Use `ModuleShell` (import from `../ui` — path `../../ui` from the setup subfolder; verify relative depth). UI:
  - Left: list of schedules (from `getBellSchedules`), select one; "+ New schedule" (prompt/inline input → `createSchedule` → reload); rename/delete actions.
  - Right (selected schedule): a table of periods (label, start `<input type="time">`, end `<input type="time">`, break checkbox), add-row, save-row (`addPeriod`/`updatePeriod`), delete-row, and up/down reorder buttons that swap `sortOrder` and call `updatePeriod` for both.
  - Below: "Class schedules" — `getClassSchedules()` list; each row is `className` + a `<select>` of schedules (value=scheduleId); onChange → `setClassSchedule`. Show a hint for unmapped classes.
  - On mutations, reload; show `ck-alert` errors from `err.response.data.message`.
  - Gate the whole panel behind `usePermissions().can('timetable:manage')` (render an "access denied" note otherwise) — matches how other admin panels gate.

- [ ] **Step 3: Wire nav + render.**
  - `config.ts`: add to `PanelKey` union: `'bellschedules'` and `'subjectsmaster'` (Task 5). In `ADMIN_NAV_SECTIONS` Setup group (after `classsetup`): `{ key: 'bellschedules', label: 'Bell schedules', icon: '⏰' }`.
  - `UnifiedWorkspacePage.tsx`: add `{panel === 'bellschedules' && !isPlatformAdmin && <BellSchedulesPanel />}` (import it). (Admins are non-platform; keep consistent with other Setup panels' gating.)

- [ ] **Step 4: Build.**
Run: `cd frontend && npm run build` → success.

- [ ] **Step 5: Commit.**
```bash
git add frontend/src/services/timetableApi.ts frontend/src/pages/workspace/panels/setup/BellSchedulesPanel.tsx frontend/src/pages/workspace/config.ts frontend/src/pages/UnifiedWorkspacePage.tsx
git commit -m "feat(timetable): Setup — Bell Schedules panel"
```

---

### Task 5: Frontend Setup — Subjects Master panel

**Files:**
- Modify: `frontend/src/services/timetableApi.ts` (add subjects calls)
- Create: `frontend/src/pages/workspace/panels/setup/SubjectsMasterPanel.tsx`
- Modify: `config.ts` (Setup nav entry) + `UnifiedWorkspacePage.tsx` (render)

**Interfaces — Consumes:** Task 3 endpoints + `getClassSchedules`/classes list for the class picker + academic years (fetch from `/classes` or existing academic-years source; if none, reuse the classes list from `getClassSchedules` for the class dropdown and fetch years via existing workspace data or an academic-years endpoint — verify the existing academic-years read; the fee/attendance panels fetch years, reuse that call).

- [ ] **Step 1: Add to `timetableApi.ts`:**
```ts
export interface ClassSubjects { editable: boolean; yearId: string; subjects: { id: number; subjectName: string; sortOrder: number }[]; }
export const getClassSubjects = (classId: string, yearId?: string) => api.get<ClassSubjects>('/timetable/class-subjects', { params: { classId, yearId } });
export const addSubject = (classId: string, subjectName: string) => api.post('/timetable/class-subjects', { classId, subjectName });
export const deleteSubject = (id: number) => api.delete(`/timetable/class-subjects/${id}`);
```

- [ ] **Step 2: Build `SubjectsMasterPanel.tsx`.**
  - Class picker (`getClassSchedules` gives `{classId,className}`); year picker (default active). Find the existing way panels list academic years (search `academicYear`/`/academic-years` in the fee or attendance panels) and reuse it; the active year is the default selection.
  - `getClassSubjects(classId, yearId)` → render the subjects list. If `editable`: show an "Add subject" input + per-row delete. If not editable: render read-only with a banner `Locked — {yearLabel} has ended. Subjects can only be edited for the current year.` (hide add/delete).
  - Add → `addSubject`; on 409, surface the server message. Delete → `deleteSubject`. Reload after mutations.
  - Gate on `can('timetable:manage')`.

- [ ] **Step 3: Nav + render.** `config.ts` Setup group: `{ key: 'subjectsmaster', label: 'Subjects', icon: '📚' }`. `UnifiedWorkspacePage.tsx`: `{panel === 'subjectsmaster' && !isPlatformAdmin && <SubjectsMasterPanel />}`.

- [ ] **Step 4: Build → success. Commit.**
```bash
git add frontend/src/services/timetableApi.ts frontend/src/pages/workspace/panels/setup/SubjectsMasterPanel.tsx frontend/src/pages/workspace/config.ts frontend/src/pages/UnifiedWorkspacePage.tsx
git commit -m "feat(timetable): Setup — Subjects Master panel (year-locked)"
```

---

## PHASE B — timetable grid

### Task 6: Timetable entries backend (read/upsert/delete + conflict) & legacy removal

**Files:**
- Modify: `TimetableRepository.java`, `TimetableController.java`, `TimetableRepositoryIntegrationTest.java`
- Modify: `TenantSchoolPublicCompatibilityController.java`, `SchoolStructureReadRepository.java` (remove legacy write)
- Modify: platform-service `ReportingReadRepository.java`, `ReportingPublicCompatibilityController.java` (remove bundle timetable)
- Modify: `services/api-gateway/server.js` (remove `/api/v1/workspace/timetable` route)

**Interfaces — Produces:**
- `Map<String,Object> timetable(Long schoolId, String sectionId, String yearId)` →
  `{ editable, yearId, sectionId, days:[...], periods:[{id,label,start,end,isBreak}], entries:[{day,periodId,subjectName,teacherId,teacherName}] }`.
  Resolves the section's class → `school_class_bell_map` → periods. `editable` = (yearId==active). If no schedule mapped → `periods:[]` + `noSchedule:true`.
- `Map<String,Object> upsertEntry(Long schoolId, String sectionId, String day, long periodId, String subjectName, Long teacherId)` →
  `{ entry:{...}, conflict: <string|null> }`. Guards (throw): non-active year → `YearLockedException`; break period or period not in the section's class schedule → `IllegalArgumentException`; `subjectName` not in the class's active-year subjects → `IllegalArgumentException`. Conflict = if another section has an entry with the same (school, year, day, periodId, teacherId) → a human string like `"AB already teaches 8-C · Mon P2"` (else null). Non-blocking.
- `void deleteEntry(Long schoolId, String sectionId, String day, long periodId)` — `YearLockedException` if non-active year.

- [ ] **Step 1: Failing tests** (add to integration test): replace-in-place upsert, break/non-member rejection, year lock, and conflict reporting.

```java
@Test
void upsertIsReplaceInPlaceAndReportsTeacherConflict() {
    long schoolId = seedSchool(); String classId = seedClass(schoolId, "6");
    String sec1 = seedSection(schoolId, classId, "6-A"); String sec2 = seedSection(schoolId, classId, "6-B");
    String year = seedYear(schoolId, "ay_2026_27", true);
    var sched = repo.createSchedule(schoolId, "Std");
    long schedId = ((Number) sched.get("id")).longValue();
    var p1 = repo.addPeriod(schoolId, schedId, "P1", "08:00", "08:45", false, 1);
    long pid = ((Number) p1.get("id")).longValue();
    repo.setClassSchedule(schoolId, classId, schedId);
    repo.addSubject(schoolId, classId, year, "Math");
    long teacher = seedStaff(schoolId, "AB");

    var r1 = repo.upsertEntry(schoolId, sec1, "Mon", pid, "Math", teacher);
    assertThat(r1.get("conflict")).isNull();
    // replace-in-place: second upsert on same slot updates, does not duplicate
    repo.upsertEntry(schoolId, sec1, "Mon", pid, "Math", teacher);
    var grid = repo.timetable(schoolId, sec1, year);
    assertThat((List<?>) grid.get("entries")).hasSize(1);
    // teacher double-booked in another section → conflict string, still saved
    var r2 = repo.upsertEntry(schoolId, sec2, "Mon", pid, "Math", teacher);
    assertThat((String) r2.get("conflict")).contains("6-A");
    assertThat((List<?>) repo.timetable(schoolId, sec2, year).get("entries")).hasSize(1);
}

@Test
void upsertRejectsNonActiveYearAndBreakAndUnknownSubject() {
    long schoolId = seedSchool(); String classId = seedClass(schoolId, "6");
    String sec = seedSection(schoolId, classId, "6-A");
    String active = seedYear(schoolId, "ay_2026_27", true);
    var sched = repo.createSchedule(schoolId, "Std"); long schedId=((Number)sched.get("id")).longValue();
    var teach = repo.addPeriod(schoolId, schedId, "P1", "08:00","08:45", false, 1);
    var brk = repo.addPeriod(schoolId, schedId, "Break", "08:45","09:00", true, 2);
    repo.setClassSchedule(schoolId, classId, schedId);
    repo.addSubject(schoolId, classId, active, "Math");
    long teacher = seedStaff(schoolId, "AB");
    long teachPid=((Number)teach.get("id")).longValue(), brkPid=((Number)brk.get("id")).longValue();

    assertThatThrownBy(() -> repo.upsertEntry(schoolId, sec, "Mon", brkPid, "Math", teacher))
        .isInstanceOf(IllegalArgumentException.class);            // break period
    assertThatThrownBy(() -> repo.upsertEntry(schoolId, sec, "Mon", teachPid, "Chemistry", teacher))
        .isInstanceOf(IllegalArgumentException.class);            // subject not in master
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement the three repo methods.**
  - `timetable`: resolve `classId = SELECT school_class_id FROM tenant_school.school_sections WHERE id=:sec AND school_id=:s`; `scheduleId = SELECT schedule_id FROM school_class_bell_map WHERE school_id=:s AND class_id=:class` (optional). If none → return `{editable, yearId, sectionId, days, periods:[], entries:[], noSchedule:true}`. Else load periods (as in Task 2) + entries `SELECT e.day_name, e.bell_period_id, e.subject_name, e.teacher_id, st.name AS teacher_name FROM school_timetable_entries e LEFT JOIN staff_members st ON st.id=e.teacher_id WHERE e.school_id=:s AND e.academic_year_id=:y AND e.section_id=:sec`. `editable = yearId.equals(activeYearId(s))`. `days = List.of("Mon","Tue","Wed","Thu","Fri","Sat")`.
  - `upsertEntry`: `String year = activeYearId(s)` (entries are always written to the active year); if the caller's target year isn't active this path isn't reached (controller uses active). Validate: period belongs to the section's class's schedule and `is_break=false` (`SELECT is_break, schedule_id FROM school_bell_periods WHERE id=:pid AND school_id=:s` + compare schedule_id to the class's mapped schedule) → else `IllegalArgumentException`. Validate subject is a member: `SELECT 1 FROM school_class_subjects WHERE school_id=:s AND class_id=:class AND academic_year_id=:year AND subject_name=:subj` → else `IllegalArgumentException`. Upsert: `INSERT ... ON CONFLICT (school_id,academic_year_id,section_id,day_name,bell_period_id) DO UPDATE SET subject_name=EXCLUDED.subject_name, teacher_id=EXCLUDED.teacher_id, updated_at=now()`. Compute conflict: if `teacherId != null`, `SELECT sec2.name FROM school_timetable_entries e2 JOIN school_sections sec2 ON sec2.id=e2.section_id WHERE e2.school_id=:s AND e2.academic_year_id=:year AND e2.day_name=:day AND e2.bell_period_id=:pid AND e2.teacher_id=:teacher AND e2.section_id<>:sec LIMIT 1` → if present build `<teacherName> already teaches <sec2.name> · <day> <periodLabel>`. `@Transactional`.
  - `deleteEntry`: if `!activeYearId(s)`-guard needed only when a yearId is passed; delete `WHERE school_id=:s AND academic_year_id=:active AND section_id=:sec AND day_name=:day AND bell_period_id=:pid`.

- [ ] **Step 4: Run → PASS.**

- [ ] **Step 5: Controller endpoints.**
  - `GET /api/v1/timetable?sectionId=&yearId=` (read; default yearId=active) → `repo.timetable(s, sectionId, yearId)`; set `editable=false` in response if caller is not admin (teacher read-only) — compute `boolean admin = TenantContext.get().isSuperAdmin() || <role ADMIN/SCHOOL_ADMIN>`; if not admin, override `editable=false`.
  - `PUT /api/v1/timetable/entry` (body `{sectionId,day,periodId,subjectName,teacherId}`, write) → `requireSchoolAdmin()`; `repo.upsertEntry(...)`; map `YearLockedException`→409, `IllegalArgumentException`→400.
  - `DELETE /api/v1/timetable/entry?sectionId=&day=&periodId=` (write) → `requireSchoolAdmin()`; `repo.deleteEntry`.

- [ ] **Step 6: Remove the legacy paths.**
  - `TenantSchoolPublicCompatibilityController.java`: delete `addTimetableFromWorkspace` (the `/api/v1/workspace/timetable` POST).
  - `SchoolStructureReadRepository.java`: delete `addTimetableEntry` + the `timetableRow` helper (grep to confirm no other callers).
  - platform-service `ReportingReadRepository.java`: delete `timetable(Long)`; `ReportingPublicCompatibilityController.workspace`: remove the `response.put("timetable", reporting.timetable(scope))` line.
  - `server.js`: delete `route('tenant', '/api/v1/workspace/timetable'),`.

- [ ] **Step 7: Both suites green + gateway test.**
Run: `.\mvnw.cmd -f services\school-core-service\pom.xml -q test` → SUCCESS.
Run: `.\mvnw.cmd -f services\platform-service\pom.xml -q test` → SUCCESS (update any test referencing the removed `timetable` bundle key or `ReportingReadRepository.timetable` to drop that assertion).
Run: `cd services/api-gateway && node --test server.test.js` → pass.

- [ ] **Step 8: Commit.**
```bash
git add -A services/school-core-service services/platform-service services/api-gateway
git commit -m "feat(timetable): grid read/upsert/delete API with conflict; remove legacy bundle timetable"
```

---

### Task 7: Frontend timetable grid (admin edit + teacher read-only)

**Files:**
- Modify: `frontend/src/services/timetableApi.ts` (add timetable calls)
- Create: `frontend/src/pages/workspace/panels/TimetableGrid.tsx`
- Modify: `frontend/src/pages/workspace/panels/TimetablePanel.tsx` (host → render `TimetableGrid`)
- Modify: `frontend/src/pages/UnifiedWorkspacePage.tsx` (pass `isTeacher`/read-only), `src/types/workspace.ts` (drop bundle `timetable` type)

**Interfaces — Consumes:** Task 6 endpoints.

- [ ] **Step 1: Add to `timetableApi.ts`:**
```ts
export interface TimetableView {
  editable: boolean; yearId: string; sectionId: string; noSchedule?: boolean;
  days: string[]; periods: BellPeriod[];
  entries: { day: string; periodId: number; subjectName: string; teacherId: number | null; teacherName: string | null }[];
}
export const getTimetable = (sectionId: string, yearId?: string) => api.get<TimetableView>('/timetable', { params: { sectionId, yearId } });
export const putEntry = (b: { sectionId: string; day: string; periodId: number; subjectName: string; teacherId: number | null }) => api.put('/timetable/entry', b);
export const deleteEntry = (p: { sectionId: string; day: string; periodId: number }) => api.delete('/timetable/entry', { params: p });
```

- [ ] **Step 2: Build `TimetableGrid.tsx`.** Props: `{ readOnly?: boolean }` (teacher passes true).
  - Controls: class-section dropdown (reuse the sections source used elsewhere — e.g. `/classes/{classId}/sections` chained, or a flat sections list; mirror `AttendanceAbsenteePanel`'s class→section loading), and a year dropdown (default active; reuse the years source). Non-active year OR `readOnly` prop → the grid is view-only.
  - Fetch `getTimetable(sectionId, yearId)`. If `noSchedule` → render an info alert linking to Setup → Bell schedules. If `periods.length===0` otherwise → prompt to add periods.
  - Grid: `<table>`; first column = period `label` + `start–end` (break rows get a muted style + span the day columns with "Break"). Columns = `days`. Each non-break cell shows the entry's `subjectName` + `teacherName` (or empty).
  - Editing (only when `editable && !readOnly`): clicking a cell opens an inline editor (subject `<select>` from the class's subjects via `getClassSubjects(classId, yearId)`; teacher `<select>` from staff — reuse the staff source, e.g. `/workspace/staff` or the staff list used by `StaffPanel`). Save → `putEntry`; on success, if `res.data.conflict` show a non-blocking warning toast (`RK already teaches…`) with the save still applied; reload. A "Clear" button → `deleteEntry`.
  - Errors: 409 (year locked) / 400 → show `err.response.data.message`.
  - Gate on `can('timetable:read')`.

- [ ] **Step 3: Host + nav wiring.**
  - `TimetablePanel.tsx`: replace its body with `return <TimetableGrid readOnly={readOnly} />;` (accept a `readOnly` prop). Delete the old form/list/state.
  - `UnifiedWorkspacePage.tsx`: where `panel === 'timetable'` renders, pass `readOnly={isTeacher}` (admins editable; teachers read-only). Confirm both ADMIN and TEACHER navs still point at `timetable`.
  - `src/types/workspace.ts`: remove the `timetable` field from the workspace bundle type (backend no longer returns it).

- [ ] **Step 4: Build → success.**
Run: `cd frontend && npm run build` → success. Also `cd frontend && npx vitest run` → existing tests still pass (no new FE tests added; if a test referenced the old TimetablePanel form or the bundle `timetable`, update it minimally).

- [ ] **Step 5: Commit.**
```bash
git add frontend/src/services/timetableApi.ts frontend/src/pages/workspace/panels/TimetableGrid.tsx frontend/src/pages/workspace/panels/TimetablePanel.tsx frontend/src/pages/UnifiedWorkspacePage.tsx frontend/src/types/workspace.ts
git commit -m "feat(timetable): grid panel (admin edit + teacher read-only), drop bundle timetable"
```

---

## Final verification (whole-branch, before finishing)

- `.\mvnw.cmd -f services\school-core-service\pom.xml -q test` and `-f services\platform-service\pom.xml -q test` → SUCCESS.
- `cd services/api-gateway && node --test server.test.js` → pass.
- `cd frontend && npm run build` → success; `npx vitest run` → pass.
- Manual smoke checklist (dev, after deploy): admin creates a bell schedule + periods, maps a class, adds subjects for the active year; builds a section's grid (subject+teacher), sees a conflict warning on a double-booked teacher; a past year shows read-only; a teacher sees the grid read-only.
```
