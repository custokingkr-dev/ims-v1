# Attendance — Daily Marking Redesign + Richer Statuses Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Late/Leave attendance statuses and rebuild the daily-marking UI as a design-system master-detail (section rail + roster) with segmented P/L/Ex/A pills, keeping the existing 5 endpoints (extended additively).

**Architecture:** `school-core-service` (`attendance` schema) gains two per-student statuses (`LATE`, `LEAVE`) via a widened CHECK constraint plus `late_count`/`leave_count` aggregate columns on `attendance_daily`. The percent formula becomes `(Present + Late) / (Present + Late + Absent)` — Leave is excused and excluded from the denominator. The 665-line `AttendancePanel.tsx` monolith is split into a container plus three focused components (`SectionRail`, `SectionRoster`, `StudentAttendanceRow`) styled by a new `styles/attendance.css`, with a two-pane desktop layout that stacks on mobile via CSS.

**Tech Stack:** Spring Boot 4.0.7 / Java 25, `JdbcClient`, Flyway (per-service `attendance` history), Testcontainers `postgres:16`; React 18 + Vite + TypeScript, Vitest + `@testing-library/react`.

## Global Constraints

- **Statuses (exactly four):** `PRESENT`, `ABSENT`, `LATE`, `LEAVE`. No Half-day, no custom statuses.
- **Percent formula (single source of truth):** `attended = present + late`; `denominator = present + late + absent` (LEAVE excluded); `presentPercent = denominator == 0 ? 0 : round(attended * 100.0 / denominator)`, rounded to one decimal. One each of P/Late/Leave/Absent → `2/3 = 66.7%`.
- **Buckets surfaced separately:** `presentCount` = PRESENT only (Late is its own bucket); plus `lateCount`, `leaveCount`, `absentCount`.
- **Colors (reuse existing tokens in `frontend/src/styles.css`):** Present = green `--g` (#1a6840), Late = amber `--am` (#b35c00), Leave/Excused = blue `--b` (#1a4fa8), Absent = red `--re` (#c0312b). Light backgrounds `--g1/--am1/--b1/--re1`.
- **Additive only:** the 5 endpoints keep their existing request/response shapes; new fields are added, none removed or renamed.
- **Layout:** master-detail two-pane on desktop (≥900px); stacks to single-pane on mobile (<900px) driven by selected-section state + a CSS breakpoint, never JS user-agent sniffing.
- **No inline styles in new frontend components** — all styling via `ck-att-*` classes in `frontend/src/styles/attendance.css`.
- **Migration is forward-only.** New migration is `V7` in the `attendance` history (V1–V6 already exist). Never edit an applied migration.
- **`schoolScopedParams` continues to scope non-platform-admins** on every attendance call (superadmin passes no `schoolId`).

---

### Task 1: V7 migration — widen status CHECK + add late/leave aggregate columns

**Files:**
- Create: `services/school-core-service/src/main/resources/db/migration/attendance/V7__attendance_late_leave.sql`
- Create (test): `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/AttendanceLateLeaveMigrationTest.java`

**Interfaces:**
- Consumes: existing `attendance.attendance_student_records.status` (inline default CHECK named `attendance_student_records_status_check`, currently `IN ('PRESENT','ABSENT')`) and `attendance.attendance_daily` (has `present_count`, `absent_count`).
- Produces: `status` CHECK now allows `PRESENT|ABSENT|LATE|LEAVE`; `attendance_daily` has `late_count INTEGER NOT NULL DEFAULT 0` and `leave_count INTEGER NOT NULL DEFAULT 0`.

- [ ] **Step 1: Write the migration**

Create `V7__attendance_late_leave.sql`:

```sql
-- Allow the two new per-student statuses (was IN ('PRESENT','ABSENT')).
-- The inline CHECK from V1 has Postgres's default name; drop-if-exists then re-add is robust.
ALTER TABLE attendance.attendance_student_records
    DROP CONSTRAINT IF EXISTS attendance_student_records_status_check;
ALTER TABLE attendance.attendance_student_records
    ADD CONSTRAINT attendance_student_records_status_check
    CHECK (status IN ('PRESENT', 'ABSENT', 'LATE', 'LEAVE'));

-- Aggregate late/leave alongside present/absent on the day rollup.
ALTER TABLE attendance.attendance_daily
    ADD COLUMN IF NOT EXISTS late_count  INTEGER NOT NULL DEFAULT 0;
ALTER TABLE attendance.attendance_daily
    ADD COLUMN IF NOT EXISTS leave_count INTEGER NOT NULL DEFAULT 0;
```

- [ ] **Step 2: Write the failing migration test**

Create `AttendanceLateLeaveMigrationTest.java` (mirror the Testcontainers bootstrap in `security/AttendanceRlsIntegrationTest.java` — migrate as `owner`, which bypasses RLS):

```java
package com.custoking.ims.schoolcoreservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.*;

class AttendanceLateLeaveMigrationTest {

    static PostgreSQLContainer<?> PG;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("attendance").defaultSchema("attendance")
                .locations("classpath:db/migration/attendance")
                .load().migrate();
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    private void seedDaily(Statement st) throws SQLException {
        st.execute("INSERT INTO attendance.attendance_daily " +
                "(id, attendance_date, total_enrolled, present_count, absent_count, locked, " +
                " school_class_id, section_id, academic_year_id, school_id) VALUES " +
                "('d-late','2024-02-01',4,1,1,false,'c1','s1','y1',10)");
    }

    @Test
    void lateLeaveColumnsExistAndDefaultToZero() throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            seedDaily(st);
            try (ResultSet rs = st.executeQuery(
                    "SELECT late_count, leave_count FROM attendance.attendance_daily WHERE id = 'd-late'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("late_count")).isZero();
                assertThat(rs.getInt("leave_count")).isZero();
            }
            st.execute("DELETE FROM attendance.attendance_daily WHERE id = 'd-late'");
        }
    }

    @Test
    void statusCheckAcceptsAllFourValues() throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            seedDaily(st);
            for (String status : new String[] {"PRESENT", "ABSENT", "LATE", "LEAVE"}) {
                st.execute("INSERT INTO attendance.attendance_student_records " +
                        "(id, attendance_daily_id, student_id, school_id, attendance_date, " +
                        " academic_year_id, class_id, section_id, status) VALUES " +
                        "('r-" + status + "','d-late'," + status.hashCode() + ",10,'2024-02-01','y1','c1','s1','" + status + "')");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM attendance.attendance_student_records WHERE attendance_daily_id = 'd-late'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(4);
            }
        }
    }

    @Test
    void statusCheckRejectsUnknownValue() throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            seedDaily(st);
            assertThatThrownBy(() -> st.execute("INSERT INTO attendance.attendance_student_records " +
                    "(id, attendance_daily_id, student_id, school_id, attendance_date, " +
                    " academic_year_id, class_id, section_id, status) VALUES " +
                    "('r-bad','d-late',777,10,'2024-02-01','y1','c1','s1','HALF_DAY')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("attendance_student_records_status_check");
        }
    }
}
```

- [ ] **Step 3: Run the test — verify it passes**

Run:
```bash
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -f services\school-core-service\pom.xml test -Dtest=AttendanceLateLeaveMigrationTest
```
Expected: PASS (3 tests). If Docker is unavailable the test self-skips via `Assumptions.assumeTrue` — that is acceptable but note it in the report.

- [ ] **Step 4: Commit**

```bash
git add services/school-core-service/src/main/resources/db/migration/attendance/V7__attendance_late_leave.sql \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/AttendanceLateLeaveMigrationTest.java
git commit -m "feat(attendance): V7 migration — allow LATE/LEAVE statuses + late/leave day counts"
```

---

### Task 2: Backend counting logic — 4-status persistence, buckets, and new percent

**Files:**
- Modify: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/AttendanceReadRepository.java`
- Create (test): `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/AttendancePercentTest.java`
- Create (test): `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/AttendanceLateLeaveRoundTripTest.java`

**Interfaces:**
- Consumes: Task 1's columns/constraint; existing repo helpers `countStudents`, `dailyRecord`, `sectionRecord`, `requireSectionSchool`, `records(Object)`, `str`, `longNum`, `round`, `row`, and fields `dailyTable`, `recordsTable`.
- Produces:
  - New static helper `static double attendancePercent(int present, int late, int absent)` — the formula in one place.
  - New private helper `int countStatus(LocalDate date, String sectionId, String academicYearId, String status)`.
  - `upsertDaily` new signature: `(LocalDate, String classId, String sectionId, String academicYearId, int total, int present, int absent, int late, int leave, Long actorId, boolean locked, Long schoolId) -> String id`.
  - `sectionRegister(...)` map now includes keys `lateCount`, `leaveCount` and the new `presentPercent`.
  - `dailySummary(...)` section maps include `lateCount`, `leaveCount`; `overallPercent` uses the aggregated attended/denominator rule.

- [ ] **Step 1: Add the `java.util.Set` import and the two helpers + `ALLOWED_STATUSES`**

In `AttendanceReadRepository.java`, add to the imports block (after `import java.util.Map;`):

```java
import java.util.Set;
```

Add this constant just below the field declarations (after `private final String recordsTable;`... i.e. inside the class, before the constructor):

```java
    static final Set<String> ALLOWED_STATUSES = Set.of("PRESENT", "ABSENT", "LATE", "LEAVE");
```

Add these two methods near the other private helpers (e.g. just above the existing `private double round(double value)`):

```java
    /** The one place the attended/denominator percent rule lives. LEAVE is excused and never passed here. */
    static double attendancePercent(int present, int late, int absent) {
        int attended = present + late;
        int denom = present + late + absent;
        return denom == 0 ? 0.0 : Math.round(attended * 100.0 / denom * 10.0) / 10.0;
    }

    private int countStatus(LocalDate date, String sectionId, String academicYearId, String status) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM %s
                WHERE attendance_date = :date AND section_id = :sectionId
                  AND academic_year_id = :academicYearId AND status = :status
                """.formatted(recordsTable))
                .param("date", date)
                .param("sectionId", sectionId)
                .param("academicYearId", academicYearId)
                .param("status", status)
                .query(Long.class)
                .single()
                .intValue();
    }
```

- [ ] **Step 2: Widen `upsertDaily` to write late/leave and take an explicit absent count**

Replace the entire existing `upsertDaily` method (currently starting `private String upsertDaily(LocalDate date, String classId, String sectionId, String academicYearId, int total, int present, Long actorId, boolean locked, Long schoolId)`) with:

```java
    private String upsertDaily(LocalDate date, String classId, String sectionId, String academicYearId,
                               int total, int present, int absent, int late, int leave,
                               Long actorId, boolean locked, Long schoolId) {
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, Object> existing = dailyRecord(date, sectionId, academicYearId);
        String id = existing == null ? UUID.randomUUID().toString() : String.valueOf(existing.get("id"));
        jdbc.sql("""
                INSERT INTO %s(id, attendance_date, total_enrolled, present_count, absent_count,
                               late_count, leave_count, recorded_by, recorded_at, updated_by, updated_at,
                               locked, school_class_id, section_id, academic_year_id, school_id)
                VALUES (:id, :date, :total, :present, :absent, :late, :leave, :actorId, :recordedAt,
                        :actorId, :updatedAt, :locked, :classId, :sectionId, :academicYearId, :schoolId)
                ON CONFLICT (attendance_date, section_id, academic_year_id) DO UPDATE SET
                    total_enrolled = EXCLUDED.total_enrolled,
                    present_count = EXCLUDED.present_count,
                    absent_count = EXCLUDED.absent_count,
                    late_count = EXCLUDED.late_count,
                    leave_count = EXCLUDED.leave_count,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at,
                    locked = EXCLUDED.locked
                """.formatted(dailyTable))
                .param("id", id)
                .param("date", date)
                .param("total", total)
                .param("present", present)
                .param("absent", absent)
                .param("late", late)
                .param("leave", leave)
                .param("actorId", actorId)
                .param("recordedAt", now)
                .param("updatedAt", now)
                .param("locked", locked)
                .param("classId", classId)
                .param("sectionId", sectionId)
                .param("academicYearId", academicYearId)
                .param("schoolId", schoolId)
                .update();
        return jdbc.sql("""
                SELECT id FROM %s
                WHERE attendance_date = :date AND section_id = :sectionId AND academic_year_id = :academicYearId
                """.formatted(dailyTable))
                .param("date", date)
                .param("sectionId", sectionId)
                .param("academicYearId", academicYearId)
                .query(String.class)
                .single();
    }
```

- [ ] **Step 3: Update `saveSectionRegister` — 4-value validation + 4 buckets to the day row**

Replace the entire `saveSectionRegister` method with:

```java
    @Transactional
    public Map<String, Object> saveSectionRegister(Map<String, Object> request) {
        LocalDate date = LocalDate.parse(str(request.get("date"), LocalDate.now().toString()));
        String classId = requireText(request.get("classId"), "Class not found");
        String sectionId = requireText(request.get("sectionId"), "Section not found");
        Long actorId = request.containsKey("actorId") ? longNum(request.get("actorId"), 0) : null;
        String academicYearId = currentAcademicYearId();
        Map<String, Object> section = sectionRecord(sectionId);
        if (!classId.equals(section.get("classId"))) {
            throw new IllegalArgumentException("Section does not belong to class");
        }
        Long sectionSchoolId = requireSectionSchool(section, sectionId);
        Long requestedSchoolId = request.containsKey("schoolId") ? longNum(request.get("schoolId"), 0) : null;
        if (requestedSchoolId != null && !requestedSchoolId.equals(sectionSchoolId)) {
            throw new SecurityException("You do not have access to this section");
        }
        Map<String, Object> existingDaily = dailyRecord(date, sectionId, academicYearId);
        if (existingDaily != null && Boolean.TRUE.equals(existingDaily.get("locked"))) {
            throw new IllegalArgumentException("Attendance is locked for this section");
        }
        List<Map<String, Object>> records = records(request.get("records"));
        int total = (int) countStudents(sectionId);
        int subPresent = (int) records.stream().filter(r -> "PRESENT".equals(str(r.get("status"), ""))).count();
        int subLate = (int) records.stream().filter(r -> "LATE".equals(str(r.get("status"), ""))).count();
        int subLeave = (int) records.stream().filter(r -> "LEAVE".equals(str(r.get("status"), ""))).count();
        int subAbsent = (int) records.stream().filter(r -> "ABSENT".equals(str(r.get("status"), ""))).count();
        String dailyId = upsertDaily(date, classId, sectionId, academicYearId, total,
                subPresent, subAbsent, subLate, subLeave, actorId, false, sectionSchoolId);
        OffsetDateTime now = OffsetDateTime.now();
        for (Map<String, Object> record : records) {
            Long studentId = longNum(record.get("studentId"), 0);
            String status = requireText(record.get("status"), "Status is required");
            if (!ALLOWED_STATUSES.contains(status)) {
                throw new IllegalArgumentException("Invalid attendance status");
            }
            ensureStudentInSection(studentId, sectionSchoolId, classId, sectionId);
            jdbc.sql("""
                    INSERT INTO %s(id, attendance_daily_id, student_id, school_id, attendance_date,
                                   academic_year_id, class_id, section_id, status, remarks,
                                   recorded_by, recorded_at, updated_by, updated_at)
                    VALUES (:id, :dailyId, :studentId, :schoolId, :date, :academicYearId, :classId, :sectionId,
                            :status, :remarks, :actorId, :recordedAt, :actorId, :updatedAt)
                    ON CONFLICT (student_id, attendance_date, academic_year_id) DO UPDATE SET
                        attendance_daily_id = EXCLUDED.attendance_daily_id,
                        school_id = EXCLUDED.school_id,
                        class_id = EXCLUDED.class_id,
                        section_id = EXCLUDED.section_id,
                        status = EXCLUDED.status,
                        remarks = EXCLUDED.remarks,
                        updated_by = EXCLUDED.updated_by,
                        updated_at = EXCLUDED.updated_at
                    """.formatted(recordsTable))
                    .param("id", UUID.randomUUID().toString())
                    .param("dailyId", dailyId)
                    .param("studentId", studentId)
                    .param("schoolId", sectionSchoolId)
                    .param("date", date)
                    .param("academicYearId", academicYearId)
                    .param("classId", classId)
                    .param("sectionId", sectionId)
                    .param("status", status)
                    .param("remarks", str(record.get("remarks"), ""))
                    .param("actorId", actorId)
                    .param("recordedAt", now)
                    .param("updatedAt", now)
                    .update();
        }
        int present = countStatus(date, sectionId, academicYearId, "PRESENT");
        int late = countStatus(date, sectionId, academicYearId, "LATE");
        int leave = countStatus(date, sectionId, academicYearId, "LEAVE");
        int absent = countStatus(date, sectionId, academicYearId, "ABSENT");
        upsertDaily(date, classId, sectionId, academicYearId, total, present, absent, late, leave,
                actorId, false, sectionSchoolId);
        return sectionRegister(date, classId, sectionId, sectionSchoolId);
    }
```

- [ ] **Step 4: Update `submitAttendanceSection` — recompute all four buckets before locking**

In `submitAttendanceSection`, replace the block that computes `present` and calls `upsertDaily` (currently the `int total = ...; int present = (int) jdbc.sql(...)...; upsertDaily(date, classId, sectionId, academicYearId, total, present, ... , true, sectionSchoolId);`) with:

```java
        int total = (int) countStudents(sectionId);
        int present = countStatus(date, sectionId, academicYearId, "PRESENT");
        int late = countStatus(date, sectionId, academicYearId, "LATE");
        int leave = countStatus(date, sectionId, academicYearId, "LEAVE");
        int absent = countStatus(date, sectionId, academicYearId, "ABSENT");
        upsertDaily(date, classId, sectionId, academicYearId, total, present, absent, late, leave,
                request.containsKey("actorId") ? longNum(request.get("actorId"), 0) : null, true, sectionSchoolId);
        return sectionRegister(date, classId, sectionId, sectionSchoolId);
```

- [ ] **Step 5: Update `saveDailyAttendance` (aggregate quick-entry path) to call the new `upsertDaily` signature**

`saveDailyAttendance` uses its own inline INSERT/UPDATE, not `upsertDaily`, so it does not need signature changes — but confirm it still compiles. It writes only `present_count`/`absent_count`; `late_count`/`leave_count` default to 0 there (that path has no per-student statuses). Leave its body unchanged. (No edit required in this step — it is a verification checkpoint. If a compile error surfaces because a stray call passes the old `upsertDaily` arity, fix that call site to the new signature with `late=0, leave=0` and `absent = Math.max(total - present, 0)`.)

- [ ] **Step 6: Update `sectionRegister` (read) — four buckets + new percent**

Replace the tail of `sectionRegister` (from `int present = students.stream()...` through the `return row(...)`) with:

```java
        int present = (int) students.stream().filter(s -> "PRESENT".equals(s.get("status"))).count();
        int late = (int) students.stream().filter(s -> "LATE".equals(s.get("status"))).count();
        int leave = (int) students.stream().filter(s -> "LEAVE".equals(s.get("status"))).count();
        int absent = (int) students.stream().filter(s -> "ABSENT".equals(s.get("status"))).count();
        int total = students.size();
        return row("date", date.toString(),
                "classId", classId,
                "sectionId", sectionId,
                "sectionName", section.get("className") + "-" + section.get("name"),
                "locked", daily != null && Boolean.TRUE.equals(daily.get("locked")),
                "totalStudents", total,
                "presentCount", present,
                "lateCount", late,
                "leaveCount", leave,
                "absentCount", absent,
                "presentPercent", attendancePercent(present, late, absent),
                "students", students);
```

- [ ] **Step 7: Update `dailySummary` (read) — select late/leave, new per-section percent, aggregate overall**

(a) In the `dailySummary` SELECT, add the two columns. Change the select line
`ad.total_enrolled, ad.present_count, ad.absent_count, ad.recorded_at, ad.updated_at, ad.locked` to:

```java
                       ad.total_enrolled, ad.present_count, ad.absent_count,
                       ad.late_count, ad.leave_count, ad.recorded_at, ad.updated_at, ad.locked
```

(b) In the row mapper, after `Integer absentCount = rs.getObject("absent_count", Integer.class);` add:

```java
                    Integer lateCount = rs.getObject("late_count", Integer.class);
                    Integer leaveCount = rs.getObject("leave_count", Integer.class);
```

(c) Replace the `presentPercent` computation block (currently `Double presentPercent = null; if (emptySection) {...} else if (presentCount != null && totalEnrolled != null && totalEnrolled > 0) { presentPercent = round(presentCount * 100.0 / totalEnrolled); }`) with:

```java
                    Double presentPercent = null;
                    if (emptySection) {
                        presentPercent = 0.0;
                    } else if (presentCount != null) {
                        presentPercent = attendancePercent(
                                presentCount,
                                lateCount == null ? 0 : lateCount,
                                absentCount == null ? 0 : absentCount);
                    }
```

(d) In the returned per-section `row(...)`, add `lateCount`/`leaveCount` (e.g. after the `"absentCount", ...` entry):

```java
                            "lateCount", lateCount == null ? 0 : lateCount,
                            "leaveCount", leaveCount == null ? 0 : leaveCount,
```

(e) Replace the `overallPercent` computation (currently `double overall = sections.stream().filter(...presentPercent != null).mapToDouble(...).average().orElse(0);`) with the aggregated attended/denominator rule:

```java
        int overallAttended = 0;
        int overallDenom = 0;
        for (Map<String, Object> section : sections) {
            int p = (int) longNum(section.get("presentCount"), 0);
            int l = (int) longNum(section.get("lateCount"), 0);
            int a = (int) longNum(section.get("absentCount"), 0);
            overallAttended += p + l;
            overallDenom += p + l + a;
        }
        double overall = overallDenom == 0 ? 0 : round(overallAttended * 100.0 / overallDenom);
```

Leave the `return row("date", ..., "overallPercent", round(overall), ...)` as-is (it already references `overall`).

- [ ] **Step 8: Write the failing pure-formula unit test**

Create `AttendancePercentTest.java`:

```java
package com.custoking.ims.schoolcoreservice.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttendancePercentTest {

    @Test
    void oneEach_leaveExcluded_isTwoThirds() {
        // P=1, Late=1, Absent=1 (+ Leave excluded) -> attended 2 / denom 3 = 66.7
        assertThat(AttendanceReadRepository.attendancePercent(1, 1, 1)).isEqualTo(66.7);
    }

    @Test
    void lateCountsAsAttended() {
        // P=0, Late=2, Absent=0 -> 2/2 = 100
        assertThat(AttendanceReadRepository.attendancePercent(0, 2, 0)).isEqualTo(100.0);
    }

    @Test
    void allPresent_isHundred() {
        assertThat(AttendanceReadRepository.attendancePercent(3, 0, 0)).isEqualTo(100.0);
    }

    @Test
    void emptyDenominator_isZero() {
        // e.g. only Leave marked, or nobody marked -> denom 0
        assertThat(AttendanceReadRepository.attendancePercent(0, 0, 0)).isEqualTo(0.0);
    }
}
```

- [ ] **Step 9: Run the pure-formula test — verify it passes**

Run:
```bash
.\mvnw.cmd -f services\school-core-service\pom.xml test -Dtest=AttendancePercentTest
```
Expected: PASS (4 tests).

- [ ] **Step 10: Write the failing round-trip integration test**

Create `AttendanceLateLeaveRoundTripTest.java` (mirrors `FeeBandDeleteIntegrationTest` — direct repo construction, multi-schema migrate as `owner` which bypasses RLS; `StudentPhotoStorage` mocked):

```java
package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

class AttendanceLateLeaveRoundTripTest {

    static PostgreSQLContainer<?> PG;
    static DataSource ds;
    static AttendanceReadRepository repo;
    static final LocalDate DAY = LocalDate.parse("2024-03-04");

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        for (String schema : new String[] {"tenant_school", "student", "attendance"}) {
            Flyway.configure()
                    .dataSource(PG.getJdbcUrl(), "owner", "owner")
                    .schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/" + schema)
                    .load().migrate();
        }
        ds = new DriverManagerDataSource(PG.getJdbcUrl(), "owner", "owner");
        StudentPhotoStorage photo = Mockito.mock(StudentPhotoStorage.class);
        Mockito.when(photo.toDisplayUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        repo = new AttendanceReadRepository(JdbcClient.create(ds), photo, "attendance");
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM attendance.attendance_student_records");
            st.execute("DELETE FROM attendance.attendance_daily");
            st.execute("DELETE FROM student.students");
            st.execute("DELETE FROM tenant_school.school_sections");
            st.execute("DELETE FROM tenant_school.school_classes");
            st.execute("DELETE FROM tenant_school.schools");
            st.execute("DELETE FROM tenant_school.academic_years");

            st.execute("INSERT INTO tenant_school.academic_years(id, label, active) VALUES ('AY','2024-25',true)");
            st.execute("INSERT INTO tenant_school.schools(id, name, short_code, active, created_at) " +
                    "VALUES (1,'Test School','TST',true, now())");
            st.execute("INSERT INTO tenant_school.school_classes(id, name, sort_order) VALUES ('c1','Class 1',1)");
            st.execute("INSERT INTO tenant_school.school_sections(id, name, teacher_name, active, school_class_id, school_id) " +
                    "VALUES ('s1','A','Ms Rao',true,'c1',1)");
            // 4 students in c1/s1 — one for each status.
            for (int i = 1; i <= 4; i++) {
                st.execute("INSERT INTO student.students" +
                        "(id, admission_no, roll_no, full_name, school_id, class_id, section_id, academic_year_id) VALUES " +
                        "(" + i + ",'ADM" + i + "','" + i + "','Student " + i + "',1,'c1','s1','AY')");
            }
        }
    }

    @Test
    void saveThenRead_computesBuckets_andWritesLateLeaveToDaily() {
        // P, Late, Leave, Absent — one each.
        repo.saveSectionRegister(Map.of(
                "date", DAY.toString(), "classId", "c1", "sectionId", "s1", "schoolId", 1,
                "records", List.of(
                        Map.of("studentId", 1, "status", "PRESENT", "remarks", ""),
                        Map.of("studentId", 2, "status", "LATE", "remarks", "bus late"),
                        Map.of("studentId", 3, "status", "LEAVE", "remarks", "sick"),
                        Map.of("studentId", 4, "status", "ABSENT", "remarks", ""))));

        Map<String, Object> reg = repo.sectionRegister(DAY, "c1", "s1", 1L);
        assertThat(reg.get("presentCount")).isEqualTo(1);
        assertThat(reg.get("lateCount")).isEqualTo(1);
        assertThat(reg.get("leaveCount")).isEqualTo(1);
        assertThat(reg.get("absentCount")).isEqualTo(1);
        assertThat(reg.get("totalStudents")).isEqualTo(4);
        // attended (P+Late)=2 / denom (P+Late+Absent)=3 -> 66.7; Leave excluded.
        assertThat(((Number) reg.get("presentPercent")).doubleValue()).isEqualTo(66.7);

        // late_count / leave_count persisted on the day rollup.
        Map<String, Object> daily = JdbcClient.create(ds).sql(
                "SELECT present_count, absent_count, late_count, leave_count FROM attendance.attendance_daily " +
                "WHERE section_id = 's1' AND attendance_date = :d AND academic_year_id = 'AY'")
                .param("d", DAY)
                .query((rs, n) -> Map.<String, Object>of(
                        "present", rs.getInt("present_count"),
                        "absent", rs.getInt("absent_count"),
                        "late", rs.getInt("late_count"),
                        "leave", rs.getInt("leave_count")))
                .single();
        assertThat(daily).containsEntry("present", 1).containsEntry("absent", 1)
                .containsEntry("late", 1).containsEntry("leave", 1);
    }

    @Test
    void dailySummary_surfacesBucketsAndAggregatePercent() {
        repo.saveSectionRegister(Map.of(
                "date", DAY.toString(), "classId", "c1", "sectionId", "s1", "schoolId", 1,
                "records", List.of(
                        Map.of("studentId", 1, "status", "PRESENT", "remarks", ""),
                        Map.of("studentId", 2, "status", "LATE", "remarks", ""),
                        Map.of("studentId", 3, "status", "LEAVE", "remarks", ""),
                        Map.of("studentId", 4, "status", "ABSENT", "remarks", ""))));

        Map<String, Object> summary = repo.dailySummary(DAY, 1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) summary.get("sections");
        Map<String, Object> s1 = sections.stream()
                .filter(s -> "s1".equals(s.get("sectionId"))).findFirst().orElseThrow();
        assertThat(s1.get("lateCount")).isEqualTo(1);
        assertThat(s1.get("leaveCount")).isEqualTo(1);
        assertThat(((Number) s1.get("presentPercent")).doubleValue()).isEqualTo(66.7);
        assertThat(((Number) summary.get("overallPercent")).doubleValue()).isEqualTo(66.7);
    }

    @Test
    void unknownStatus_isRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repo.saveSectionRegister(Map.of(
                "date", DAY.toString(), "classId", "c1", "sectionId", "s1", "schoolId", 1,
                "records", List.of(Map.of("studentId", 1, "status", "HALF_DAY", "remarks", "")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid attendance status");
    }
}
```

- [ ] **Step 11: Run the round-trip test — verify it passes**

Run:
```bash
.\mvnw.cmd -f services\school-core-service\pom.xml test -Dtest=AttendanceLateLeaveRoundTripTest
```
Expected: PASS (3 tests). Then run the whole attendance test set to confirm no regression in existing tests:
```bash
.\mvnw.cmd -f services\school-core-service\pom.xml test -Dtest="Attendance*"
```
Expected: PASS (all attendance tests). If an existing test asserted the old `presentCount/total` percent, update its expected value to the new attended/denominator result and note it in the report.

- [ ] **Step 12: Commit**

```bash
git add services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/AttendanceReadRepository.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/AttendancePercentTest.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/AttendanceLateLeaveRoundTripTest.java
git commit -m "feat(attendance): 4-status buckets + (P+Late)/(P+Late+Absent) percent, Leave excluded"
```

---

### Task 3: Frontend foundation — types + attendance.css + import

**Files:**
- Modify: `frontend/src/types/attendance.ts`
- Create: `frontend/src/styles/attendance.css`
- Modify: `frontend/src/main.tsx:11` (add the CSS import after `import './styles/drawers.css';`)

**Interfaces:**
- Produces: `AttendanceStatus`, `EditableAttendanceStatus`, `StudentEditRecord` types; `lateCount`/`leaveCount` on `SectionRegisterResponse` and `AttendanceDailySummarySection`; the `ck-att-*` CSS classes consumed by Tasks 4–7.

- [ ] **Step 1: Update `types/attendance.ts`**

Replace the file contents with:

```ts
/**
 * Attendance-related TypeScript types for frontend API contracts.
 */

export type AttendanceStatus = 'PRESENT' | 'ABSENT' | 'LATE' | 'LEAVE';

/** Local editable state also allows null = unmarked. */
export type EditableAttendanceStatus = AttendanceStatus | null;

export interface StudentEditRecord {
  studentId: number;
  status: EditableAttendanceStatus;
  remarks: string;
}

export interface StudentAttendanceRecord {
  studentId: number;
  fullName: string;
  admissionNo: string;
  rollNo: string;
  photoUrl: string | null;
  status: AttendanceStatus | null;
  remarks: string;
}

export interface SectionRegisterResponse {
  date: string;
  classId: string;
  sectionId: string;
  sectionName: string;
  locked: boolean;
  totalStudents: number;
  presentCount: number;
  lateCount: number;
  leaveCount: number;
  absentCount: number;
  presentPercent: number;
  students: StudentAttendanceRecord[];
}

export interface SaveSectionRegisterRequest {
  date: string;
  classId: string;
  sectionId: string;
  records: Array<{
    studentId: number;
    status: AttendanceStatus;
    remarks: string;
  }>;
}

export interface SubmitSectionRequest {
  date: string;
  classId: string;
  sectionId: string;
}

export interface AttendanceDailySummarySection {
  sectionId: string;
  classId: string;
  sectionName: string;
  totalStudents: number;
  presentCount: number;
  lateCount: number;
  leaveCount: number;
  absentCount: number;
  presentPercent: number;
  teacherName: string;
  status: 'Pending' | 'Saved' | 'Submitted';
  locked: boolean;
}

export interface AttendanceDailySummaryResponse {
  date: string;
  dateLabel: string;
  overallPercent: number;
  sections: AttendanceDailySummarySection[];
  allSubmitted: boolean;
  nonWorkingDay: boolean;
}
```

- [ ] **Step 2: Create `frontend/src/styles/attendance.css`**

```css
/* Attendance daily-marking — master-detail layout + segmented status pills. */

.ck-att-layout {
  display: grid;
  grid-template-columns: 260px 1fr;
  gap: 16px;
  align-items: start;
}

.ck-att-rail {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.ck-att-rail-item {
  text-align: left;
  width: 100%;
  border: 1px solid var(--border);
  background: var(--white);
  border-radius: 8px;
  padding: 12px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ck-att-rail-item:hover { border-color: var(--border2); }
.ck-att-rail-item--selected { border-color: var(--b); box-shadow: 0 0 0 1px var(--b) inset; }
.ck-att-rail-item--locked { opacity: 0.65; cursor: not-allowed; }

.ck-att-rail-name { font-weight: 600; font-size: 13px; }
.ck-att-rail-teacher { font-size: 11px; color: var(--ink3); }
.ck-att-rail-figures { display: flex; justify-content: space-between; align-items: baseline; margin-top: 4px; }
.ck-att-rail-pct { font-size: 18px; font-weight: 700; font-variant-numeric: tabular-nums; }
.ck-att-counts { font-size: 11px; color: var(--ink2); font-variant-numeric: tabular-nums; }

.ck-att-roster { min-width: 0; display: flex; flex-direction: column; gap: 16px; }

.ck-att-summary {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 8px;
}
.ck-att-summary-cell {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
  background: var(--white);
}
.ck-att-summary-label { font-size: 10px; text-transform: uppercase; letter-spacing: 0.04em; color: var(--ink3); }
.ck-att-summary-value { font-size: 20px; font-weight: 700; font-variant-numeric: tabular-nums; }

.ck-att-rows { display: flex; flex-direction: column; gap: 8px; }

.ck-att-row {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
  background: var(--white);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.ck-att-row-main { display: flex; gap: 12px; align-items: center; }
.ck-att-avatar { width: 36px; height: 36px; font-size: 12px; flex-shrink: 0; }
.ck-att-row-info { flex: 1; min-width: 0; }
.ck-att-row-name { font-weight: 500; font-size: 13px; }
.ck-att-row-meta { font-size: 11px; color: var(--ink3); margin-top: 2px; }

.ck-att-pills { display: flex; gap: 4px; flex-shrink: 0; }
.ck-att-pill {
  min-width: 34px;
  padding: 6px 8px;
  font-size: 12px;
  font-weight: 600;
  border-radius: 6px;
  border: 1px solid var(--border2);
  background: var(--white);
  color: var(--ink2);
  cursor: pointer;
}
.ck-att-pill:disabled, span.ck-att-pill { cursor: default; }
.ck-att-pill--present.ck-att-pill--active { background: var(--g); border-color: var(--g); color: #fff; }
.ck-att-pill--late.ck-att-pill--active { background: var(--am); border-color: var(--am); color: #fff; }
.ck-att-pill--leave.ck-att-pill--active { background: var(--b); border-color: var(--b); color: #fff; }
.ck-att-pill--absent.ck-att-pill--active { background: var(--re); border-color: var(--re); color: #fff; }

.ck-att-remarks {
  width: 100%;
  padding: 6px 8px;
  font-size: 11px;
  border: 1px solid var(--border2);
  border-radius: 4px;
}

.ck-att-roster-footer { display: flex; gap: 8px; justify-content: flex-end; flex-wrap: wrap; }
.ck-att-roster-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.ck-att-back { display: none; }

/* Mobile: single-pane stack. Show rail by default; when a section is selected the
   container gets --detail and the roster takes over full width. */
@media (max-width: 899px) {
  .ck-att-layout { grid-template-columns: 1fr; }
  .ck-att-layout .ck-att-roster { display: none; }
  .ck-att-layout--detail .ck-att-rail { display: none; }
  .ck-att-layout--detail .ck-att-roster { display: flex; }
  .ck-att-back { display: inline-flex; }
  .ck-att-summary { grid-template-columns: repeat(3, 1fr); }
}
```

- [ ] **Step 3: Import the stylesheet**

In `frontend/src/main.tsx`, after line `import './styles/drawers.css';`, add:

```tsx
import './styles/attendance.css';
```

- [ ] **Step 4: Typecheck / build**

Run:
```bash
cd frontend
npm run build
```
Expected: build succeeds (TypeScript compiles; the CSS import resolves). Note: `AttendancePanel.tsx` still uses the old status union in a couple of spots — those are updated in Task 7. If `npm run build` fails only inside `AttendancePanel.tsx` on the `'PRESENT' | 'ABSENT'` casts, that is expected and resolved in Task 7; confirm no *other* file errors and proceed. (If you prefer a green build at every task, run `npx tsc --noEmit -p tsconfig.json 2>&1 | grep -v AttendancePanel` and confirm no other errors.)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/types/attendance.ts frontend/src/styles/attendance.css frontend/src/main.tsx
git commit -m "feat(attendance-ui): shared types (Late/Leave) + attendance.css foundation"
```

---

### Task 4: `StudentAttendanceRow` component

**Files:**
- Create: `frontend/src/pages/workspace/panels/attendance/StudentAttendanceRow.tsx`
- Create (test): `frontend/src/pages/workspace/panels/attendance/StudentAttendanceRow.test.tsx`

**Interfaces:**
- Consumes: `StudentAttendanceRecord`, `EditableAttendanceStatus` from `../../../../types/attendance`.
- Produces: `StudentAttendanceRow` — props `{ student: StudentAttendanceRecord; status: EditableAttendanceStatus; remarks: string; locked: boolean; onStatusChange: (s: EditableAttendanceStatus) => void; onRemarksChange: (r: string) => void }`.

- [ ] **Step 1: Write the component**

```tsx
import type { StudentAttendanceRecord, EditableAttendanceStatus } from '../../../../types/attendance';

const PILLS: Array<{ code: Exclude<EditableAttendanceStatus, null>; label: string; mod: string }> = [
  { code: 'PRESENT', label: 'P', mod: 'present' },
  { code: 'LATE', label: 'L', mod: 'late' },
  { code: 'LEAVE', label: 'Ex', mod: 'leave' },
  { code: 'ABSENT', label: 'A', mod: 'absent' },
];

interface Props {
  student: StudentAttendanceRecord;
  status: EditableAttendanceStatus;
  remarks: string;
  locked: boolean;
  onStatusChange: (status: EditableAttendanceStatus) => void;
  onRemarksChange: (remarks: string) => void;
}

export function StudentAttendanceRow({
  student,
  status,
  remarks,
  locked,
  onStatusChange,
  onRemarksChange,
}: Props) {
  const initials = student.fullName
    .split(' ')
    .map((n) => n[0])
    .join('')
    .toUpperCase()
    .slice(0, 2);

  return (
    <div className="ck-att-row">
      <div className="ck-att-row-main">
        <div className="ck-user-avatar ck-att-avatar">{initials}</div>
        <div className="ck-att-row-info">
          <div className="ck-att-row-name">{student.fullName}</div>
          <div className="ck-att-row-meta">
            {student.admissionNo}
            {student.rollNo ? ` · Roll ${student.rollNo}` : ''}
          </div>
        </div>
        <div className="ck-att-pills" role="group" aria-label={`Attendance for ${student.fullName}`}>
          {PILLS.map((p) => {
            const active = status === p.code;
            const className = `ck-att-pill ck-att-pill--${p.mod}${active ? ' ck-att-pill--active' : ''}`;
            if (locked) {
              return (
                <span key={p.code} className={className} aria-pressed={active}>
                  {p.label}
                </span>
              );
            }
            return (
              <button
                key={p.code}
                type="button"
                className={className}
                aria-pressed={active}
                aria-label={p.code}
                onClick={() => onStatusChange(active ? null : p.code)}
              >
                {p.label}
              </button>
            );
          })}
        </div>
      </div>
      {!locked && (
        <input
          type="text"
          className="ck-att-remarks"
          placeholder="Remarks"
          value={remarks}
          onChange={(e) => onRemarksChange(e.target.value)}
        />
      )}
    </div>
  );
}
```

- [ ] **Step 2: Write the failing test**

```tsx
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { afterEach, describe, it, expect, vi } from 'vitest';
import { StudentAttendanceRow } from './StudentAttendanceRow';
import type { StudentAttendanceRecord } from '../../../../types/attendance';

afterEach(cleanup);

const student: StudentAttendanceRecord = {
  studentId: 1,
  fullName: 'Asha Rao',
  admissionNo: 'ADM1',
  rollNo: '1',
  photoUrl: null,
  status: null,
  remarks: '',
};

describe('StudentAttendanceRow', () => {
  it('sets the tapped status', () => {
    const onStatusChange = vi.fn();
    render(
      <StudentAttendanceRow student={student} status={null} remarks="" locked={false}
        onStatusChange={onStatusChange} onRemarksChange={vi.fn()} />
    );
    fireEvent.click(screen.getByRole('button', { name: 'LATE' }));
    expect(onStatusChange).toHaveBeenCalledWith('LATE');
  });

  it('clears the status when the active pill is re-tapped', () => {
    const onStatusChange = vi.fn();
    render(
      <StudentAttendanceRow student={student} status="PRESENT" remarks="" locked={false}
        onStatusChange={onStatusChange} onRemarksChange={vi.fn()} />
    );
    fireEvent.click(screen.getByRole('button', { name: 'PRESENT' }));
    expect(onStatusChange).toHaveBeenCalledWith(null);
  });

  it('renders read-only pills (no buttons) when locked', () => {
    render(
      <StudentAttendanceRow student={student} status="ABSENT" remarks="" locked
        onStatusChange={vi.fn()} onRemarksChange={vi.fn()} />
    );
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByPlaceholderText('Remarks')).toBeNull();
  });
});
```

- [ ] **Step 3: Run the test — verify it passes**

Run:
```bash
cd frontend
npm test -- StudentAttendanceRow
```
Expected: PASS (3 tests).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/workspace/panels/attendance/StudentAttendanceRow.tsx \
        frontend/src/pages/workspace/panels/attendance/StudentAttendanceRow.test.tsx
git commit -m "feat(attendance-ui): StudentAttendanceRow with segmented P/L/Ex/A pills"
```

---

### Task 5: `SectionRoster` component

**Files:**
- Create: `frontend/src/pages/workspace/panels/attendance/SectionRoster.tsx`
- Create (test): `frontend/src/pages/workspace/panels/attendance/SectionRoster.test.tsx`

**Interfaces:**
- Consumes: `SectionRegisterResponse`, `StudentEditRecord`, `EditableAttendanceStatus` from `../../../../types/attendance`; `StudentAttendanceRow` from `./StudentAttendanceRow`.
- Produces: `SectionRoster` — presentational; parent owns state and API. Props:

```ts
interface SectionRosterProps {
  register: SectionRegisterResponse | null;
  records: StudentEditRecord[] | null;
  loading: boolean;
  saving: '' | 'save' | 'submit';
  onStatusChange: (studentId: number, status: EditableAttendanceStatus) => void;
  onRemarksChange: (studentId: number, remarks: string) => void;
  onMarkAllPresent: () => void;
  onReset: () => void;
  onSave: () => void;
  onSubmit: () => void;
  onBack: () => void;
}
```

- [ ] **Step 1: Write the component**

```tsx
import type {
  SectionRegisterResponse,
  StudentEditRecord,
  EditableAttendanceStatus,
} from '../../../../types/attendance';
import { StudentAttendanceRow } from './StudentAttendanceRow';

interface Props {
  register: SectionRegisterResponse | null;
  records: StudentEditRecord[] | null;
  loading: boolean;
  saving: '' | 'save' | 'submit';
  onStatusChange: (studentId: number, status: EditableAttendanceStatus) => void;
  onRemarksChange: (studentId: number, remarks: string) => void;
  onMarkAllPresent: () => void;
  onReset: () => void;
  onSave: () => void;
  onSubmit: () => void;
  onBack: () => void;
}

export function SectionRoster({
  register,
  records,
  loading,
  saving,
  onStatusChange,
  onRemarksChange,
  onMarkAllPresent,
  onReset,
  onSave,
  onSubmit,
  onBack,
}: Props) {
  const locked = register?.locked ?? false;
  const list = records ?? [];
  const total = list.length;
  const present = list.filter((r) => r.status === 'PRESENT').length;
  const late = list.filter((r) => r.status === 'LATE').length;
  const leave = list.filter((r) => r.status === 'LEAVE').length;
  const absent = list.filter((r) => r.status === 'ABSENT').length;
  const allMarked = total > 0 && list.every((r) => r.status !== null);

  const cells = [
    { label: 'Total', value: total },
    { label: 'Present', value: present },
    { label: 'Late', value: late },
    { label: 'Leave', value: leave },
    { label: 'Absent', value: absent },
  ];

  return (
    <div className="ck-att-roster">
      <div className="ck-att-roster-actions">
        <button type="button" className="ck-btn ck-btn-sm ck-btn-ghost ck-att-back" onClick={onBack}>
          ← Sections
        </button>
        {!locked && (
          <>
            <button type="button" className="ck-btn ck-btn-sm" onClick={onMarkAllPresent}>
              Mark all Present
            </button>
            <button type="button" className="ck-btn ck-btn-sm ck-btn-ghost" onClick={onReset}>
              Reset
            </button>
          </>
        )}
      </div>

      <div className="ck-att-summary">
        {cells.map((c) => (
          <div key={c.label} className="ck-att-summary-cell">
            <div className="ck-att-summary-label">{c.label}</div>
            <div className="ck-att-summary-value">{c.value}</div>
          </div>
        ))}
      </div>

      {locked && (
        <div className="ck-alert ck-alert-am">
          <span>🔒</span>
          <div>This attendance is locked and cannot be edited.</div>
        </div>
      )}

      {loading ? (
        <div style={{ textAlign: 'center', padding: '24px 0', color: 'var(--ink3)' }}>Loading students…</div>
      ) : total === 0 ? (
        <div className="ck-alert ck-alert-am">
          <span>i</span>
          <div>No students enrolled in this section.</div>
        </div>
      ) : (
        <div className="ck-att-rows">
          {(register?.students ?? []).map((student) => {
            const record = list.find((r) => r.studentId === student.studentId);
            return (
              <StudentAttendanceRow
                key={student.studentId}
                student={student}
                status={record?.status ?? null}
                remarks={record?.remarks ?? ''}
                locked={locked}
                onStatusChange={(s) => onStatusChange(student.studentId, s)}
                onRemarksChange={(r) => onRemarksChange(student.studentId, r)}
              />
            );
          })}
        </div>
      )}

      {!locked && total > 0 && (
        <div className="ck-att-roster-footer">
          <button type="button" className="ck-btn ck-btn-b" onClick={onSave} disabled={saving === 'save'}>
            {saving === 'save' ? 'Saving…' : 'Save'}
          </button>
          <button
            type="button"
            className="ck-btn ck-btn-g"
            onClick={onSubmit}
            disabled={saving === 'submit' || !allMarked}
          >
            {saving === 'submit' ? 'Submitting…' : 'Submit Section'}
          </button>
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Write the failing test**

```tsx
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { afterEach, describe, it, expect, vi } from 'vitest';
import { SectionRoster } from './SectionRoster';
import type { SectionRegisterResponse, StudentEditRecord } from '../../../../types/attendance';

afterEach(cleanup);

const register: SectionRegisterResponse = {
  date: '2024-03-04', classId: 'c1', sectionId: 's1', sectionName: 'Class 1-A',
  locked: false, totalStudents: 2, presentCount: 0, lateCount: 0, leaveCount: 0,
  absentCount: 0, presentPercent: 0,
  students: [
    { studentId: 1, fullName: 'A One', admissionNo: 'ADM1', rollNo: '1', photoUrl: null, status: null, remarks: '' },
    { studentId: 2, fullName: 'B Two', admissionNo: 'ADM2', rollNo: '2', photoUrl: null, status: null, remarks: '' },
  ],
};

function records(overrides: Partial<StudentEditRecord>[] = []): StudentEditRecord[] {
  const base: StudentEditRecord[] = [
    { studentId: 1, status: null, remarks: '' },
    { studentId: 2, status: null, remarks: '' },
  ];
  return base.map((r, i) => ({ ...r, ...(overrides[i] ?? {}) }));
}

describe('SectionRoster', () => {
  it('shows live summary counts from local records', () => {
    render(
      <SectionRoster register={register} records={records([{ status: 'PRESENT' }, { status: 'LATE' }])}
        loading={false} saving="" onStatusChange={vi.fn()} onRemarksChange={vi.fn()}
        onMarkAllPresent={vi.fn()} onReset={vi.fn()} onSave={vi.fn()} onSubmit={vi.fn()} onBack={vi.fn()} />
    );
    // Present cell value 1, Late cell value 1.
    const present = screen.getByText('Present').parentElement!;
    const late = screen.getByText('Late').parentElement!;
    expect(present.querySelector('.ck-att-summary-value')!.textContent).toBe('1');
    expect(late.querySelector('.ck-att-summary-value')!.textContent).toBe('1');
  });

  it('disables Submit until every student is marked, enables when all marked', () => {
    const { rerender } = render(
      <SectionRoster register={register} records={records([{ status: 'PRESENT' }])} loading={false} saving=""
        onStatusChange={vi.fn()} onRemarksChange={vi.fn()} onMarkAllPresent={vi.fn()} onReset={vi.fn()}
        onSave={vi.fn()} onSubmit={vi.fn()} onBack={vi.fn()} />
    );
    expect(screen.getByRole('button', { name: /Submit Section/ })).toBeDisabled();

    rerender(
      <SectionRoster register={register} records={records([{ status: 'PRESENT' }, { status: 'LEAVE' }])}
        loading={false} saving="" onStatusChange={vi.fn()} onRemarksChange={vi.fn()} onMarkAllPresent={vi.fn()}
        onReset={vi.fn()} onSave={vi.fn()} onSubmit={vi.fn()} onBack={vi.fn()} />
    );
    // Leave counts as marked -> all marked -> enabled.
    expect(screen.getByRole('button', { name: /Submit Section/ })).not.toBeDisabled();
  });

  it('fires callbacks for mark-all-present and save', () => {
    const onMarkAllPresent = vi.fn();
    const onSave = vi.fn();
    render(
      <SectionRoster register={register} records={records()} loading={false} saving=""
        onStatusChange={vi.fn()} onRemarksChange={vi.fn()} onMarkAllPresent={onMarkAllPresent}
        onReset={vi.fn()} onSave={onSave} onSubmit={vi.fn()} onBack={vi.fn()} />
    );
    fireEvent.click(screen.getByRole('button', { name: 'Mark all Present' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(onMarkAllPresent).toHaveBeenCalled();
    expect(onSave).toHaveBeenCalled();
  });
});
```

- [ ] **Step 3: Run the test — verify it passes**

Run:
```bash
cd frontend
npm test -- SectionRoster
```
Expected: PASS (3 tests).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/workspace/panels/attendance/SectionRoster.tsx \
        frontend/src/pages/workspace/panels/attendance/SectionRoster.test.tsx
git commit -m "feat(attendance-ui): SectionRoster — summary buckets, mark-all, submit gating"
```

---

### Task 6: `SectionRail` component

**Files:**
- Create: `frontend/src/pages/workspace/panels/attendance/SectionRail.tsx`
- Create (test): `frontend/src/pages/workspace/panels/attendance/SectionRail.test.tsx`

**Interfaces:**
- Consumes: `AttendanceDailySummarySection` from `../../../../types/attendance`.
- Produces: `SectionRail` — props `{ sections: AttendanceDailySummarySection[]; selectedSectionId: string | null; loading: boolean; onSelect: (s: AttendanceDailySummarySection) => void }`.

- [ ] **Step 1: Write the component**

```tsx
import type { AttendanceDailySummarySection } from '../../../../types/attendance';

interface Props {
  sections: AttendanceDailySummarySection[];
  selectedSectionId: string | null;
  loading: boolean;
  onSelect: (section: AttendanceDailySummarySection) => void;
}

export function SectionRail({ sections, selectedSectionId, loading, onSelect }: Props) {
  if (loading) {
    return (
      <div className="ck-att-rail">
        {[1, 2, 3, 4].map((i) => (
          <div key={i} className="ck-att-rail-item" style={{ animationDelay: `${(i - 1) * 60}ms` }}>
            <div className="ck-skeleton ck-skeleton-title" />
            <div className="ck-skeleton ck-skeleton-text" style={{ width: '60%' }} />
          </div>
        ))}
      </div>
    );
  }

  if (sections.length === 0) {
    return (
      <div className="ck-att-rail">
        <div style={{ padding: '24px 8px', color: 'var(--ink3)', textAlign: 'center' }}>
          No sections found for this date.
        </div>
      </div>
    );
  }

  return (
    <div className="ck-att-rail" role="list">
      {sections.map((section) => {
        const selected = section.sectionId === selectedSectionId;
        const pending = section.status === 'Pending';
        const statusClass =
          section.status === 'Submitted' ? 'sapproved' : section.status === 'Saved' ? 'spending' : 'sneutral';
        const className =
          'ck-att-rail-item' +
          (selected ? ' ck-att-rail-item--selected' : '') +
          (section.locked ? ' ck-att-rail-item--locked' : '');
        return (
          <button
            key={section.sectionId}
            type="button"
            role="listitem"
            className={className}
            aria-current={selected}
            disabled={section.locked}
            onClick={() => !section.locked && onSelect(section)}
          >
            <div className="ck-att-rail-name">{section.sectionName}</div>
            <div className="ck-att-rail-teacher">{section.teacherName}</div>
            <div className="ck-att-rail-figures">
              <span className="ck-att-rail-pct">
                {pending ? '—' : `${Math.round(section.presentPercent)}%`}
              </span>
              <span className={`ck-status ${statusClass}`}>{section.status}</span>
            </div>
            <div className="ck-att-counts">
              {`P ${section.presentCount} · L ${section.lateCount} · Ex ${section.leaveCount} · A ${section.absentCount}`}
            </div>
          </button>
        );
      })}
    </div>
  );
}
```

- [ ] **Step 2: Write the failing test**

```tsx
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { afterEach, describe, it, expect, vi } from 'vitest';
import { SectionRail } from './SectionRail';
import type { AttendanceDailySummarySection } from '../../../../types/attendance';

afterEach(cleanup);

const sections: AttendanceDailySummarySection[] = [
  { sectionId: 's1', classId: 'c1', sectionName: 'Class 1-A', totalStudents: 4, presentCount: 1,
    lateCount: 1, leaveCount: 1, absentCount: 1, presentPercent: 66.7, teacherName: 'Ms Rao',
    status: 'Saved', locked: false },
  { sectionId: 's2', classId: 'c1', sectionName: 'Class 1-B', totalStudents: 3, presentCount: 3,
    lateCount: 0, leaveCount: 0, absentCount: 0, presentPercent: 100, teacherName: 'Mr Das',
    status: 'Submitted', locked: true },
];

describe('SectionRail', () => {
  it('renders the P·L·Ex·A counts and percent', () => {
    render(<SectionRail sections={sections} selectedSectionId={null} loading={false} onSelect={vi.fn()} />);
    expect(screen.getByText('P 1 · L 1 · Ex 1 · A 1')).toBeTruthy();
    expect(screen.getByText('67%')).toBeTruthy();
  });

  it('calls onSelect for an unlocked section but not a locked one', () => {
    const onSelect = vi.fn();
    render(<SectionRail sections={sections} selectedSectionId={null} loading={false} onSelect={onSelect} />);
    fireEvent.click(screen.getByText('Class 1-A'));
    expect(onSelect).toHaveBeenCalledWith(sections[0]);
    fireEvent.click(screen.getByText('Class 1-B'));
    expect(onSelect).toHaveBeenCalledTimes(1); // locked section is disabled
  });

  it('marks the selected item', () => {
    render(<SectionRail sections={sections} selectedSectionId="s1" loading={false} onSelect={vi.fn()} />);
    const selected = document.querySelector('.ck-att-rail-item--selected');
    expect(selected?.textContent).toContain('Class 1-A');
  });
});
```

- [ ] **Step 3: Run the test — verify it passes**

Run:
```bash
cd frontend
npm test -- SectionRail
```
Expected: PASS (3 tests).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/workspace/panels/attendance/SectionRail.tsx \
        frontend/src/pages/workspace/panels/attendance/SectionRail.test.tsx
git commit -m "feat(attendance-ui): SectionRail — section list with buckets + selection"
```

---

### Task 7: Refactor `AttendancePanel` into the master-detail container

**Files:**
- Modify (rewrite): `frontend/src/pages/workspace/panels/AttendancePanel.tsx`
- Create (test): `frontend/src/pages/workspace/panels/AttendancePanel.test.tsx`

**Interfaces:**
- Consumes: `SectionRail`, `SectionRoster` (from `./attendance/…`); `AttendanceDailySummaryResponse`, `AttendanceDailySummarySection`, `SectionRegisterResponse`, `StudentEditRecord`, `EditableAttendanceStatus` types; `ModuleShell`, `Field` from `../ui`; `todayIso` from `../utils`; `api` from `../../../services/api`.
- Produces: unchanged `AttendancePanel` public props `{ onRefresh: () => Promise<void>; schoolScopedParams?: { schoolId: number } }`. Same 5 endpoints, now sending/receiving the 4-value statuses.

- [ ] **Step 1: Rewrite `AttendancePanel.tsx`**

Replace the entire file with:

```tsx
import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field } from '../ui';
import { todayIso } from '../utils';
import { SectionRail } from './attendance/SectionRail';
import { SectionRoster } from './attendance/SectionRoster';
import type {
  AttendanceDailySummaryResponse,
  AttendanceDailySummarySection,
  SectionRegisterResponse,
  StudentEditRecord,
  EditableAttendanceStatus,
} from '../../../types/attendance';

interface Props {
  onRefresh: () => Promise<void>;
  schoolScopedParams?: { schoolId: number };
}

const EMPTY_SUMMARY: AttendanceDailySummaryResponse = {
  date: '',
  dateLabel: '',
  overallPercent: 0,
  sections: [],
  allSubmitted: false,
  nonWorkingDay: false,
};

function errMessage(err: unknown, fallback: string): string {
  if (err instanceof Error && err.message) return err.message;
  return (err as { response?: { data?: { message?: string } } })?.response?.data?.message || fallback;
}

export function AttendancePanel({ onRefresh, schoolScopedParams }: Props) {
  const [summary, setSummary] = useState<AttendanceDailySummaryResponse>(EMPTY_SUMMARY);
  const [currentDate, setCurrentDate] = useState(todayIso());

  const [selectedSection, setSelectedSection] = useState<AttendanceDailySummarySection | null>(null);
  const [register, setRegister] = useState<SectionRegisterResponse | null>(null);
  const [records, setRecords] = useState<StudentEditRecord[] | null>(null);

  const [summaryLoading, setSummaryLoading] = useState(true);
  const [rosterLoading, setRosterLoading] = useState(false);
  const [saving, setSaving] = useState<'' | 'save' | 'submit'>('');
  const [submittingDay, setSubmittingDay] = useState(false);
  const [toast, setToast] = useState('');
  const [error, setError] = useState('');

  const scoped = schoolScopedParams || {};

  const loadSummary = async (dateValue: string) => {
    setSummaryLoading(true);
    try {
      const res = await api.get<AttendanceDailySummaryResponse>('/attendance/daily-summary', {
        params: { date: dateValue || 'today', ...scoped },
      });
      setSummary(res.data || { ...EMPTY_SUMMARY, date: dateValue });
    } catch (err) {
      setError(errMessage(err, 'Failed to load attendance summary'));
    } finally {
      setSummaryLoading(false);
    }
  };

  useEffect(() => {
    void loadSummary(currentDate);
  }, [currentDate]);

  const handleDateChange = (dateValue: string) => {
    setCurrentDate(dateValue);
    setSelectedSection(null);
    setRegister(null);
    setRecords(null);
    setToast('');
    setError('');
  };

  const toRecords = (reg: SectionRegisterResponse): StudentEditRecord[] =>
    reg.students.map((s) => ({ studentId: s.studentId, status: s.status ?? null, remarks: s.remarks || '' }));

  const openSection = async (section: AttendanceDailySummarySection) => {
    setSelectedSection(section);
    setRosterLoading(true);
    setError('');
    setToast('');
    try {
      const res = await api.get<SectionRegisterResponse>('/attendance/section-register', {
        params: { date: currentDate, classId: section.classId, sectionId: section.sectionId, ...scoped },
      });
      setRegister(res.data);
      setRecords(res.data?.students ? toRecords(res.data) : []);
    } catch (err) {
      setError(errMessage(err, 'Failed to load section attendance'));
      setSelectedSection(null);
      setRegister(null);
      setRecords(null);
    } finally {
      setRosterLoading(false);
    }
  };

  const backToRail = () => {
    setSelectedSection(null);
    setRegister(null);
    setRecords(null);
    setError('');
  };

  const setStatus = (studentId: number, status: EditableAttendanceStatus) =>
    setRecords((prev) => (prev ? prev.map((r) => (r.studentId === studentId ? { ...r, status } : r)) : prev));

  const setRemarks = (studentId: number, remarks: string) =>
    setRecords((prev) => (prev ? prev.map((r) => (r.studentId === studentId ? { ...r, remarks } : r)) : prev));

  const markAllPresent = () => {
    setRecords((prev) => (prev ? prev.map((r) => ({ ...r, status: 'PRESENT' as const })) : prev));
    setToast('All students marked as present');
  };

  const resetChanges = () => {
    if (register) {
      setRecords(toRecords(register));
      setToast('Changes reset');
    }
  };

  const putRegister = async (payload: StudentEditRecord[]) => {
    await api.put('/attendance/section-register', {
      date: currentDate,
      classId: selectedSection!.classId,
      sectionId: selectedSection!.sectionId,
      records: payload
        .filter((r) => r.status !== null)
        .map((r) => ({ studentId: r.studentId, status: r.status, remarks: r.remarks || '' })),
      ...scoped,
    });
  };

  const save = async () => {
    if (!selectedSection || !records) return;
    setSaving('save');
    setError('');
    setToast('');
    try {
      await putRegister(records);
      await openSection(selectedSection);
      await loadSummary(currentDate);
      await onRefresh();
      setToast('Attendance saved successfully');
    } catch (err) {
      setError(errMessage(err, 'Failed to save attendance'));
    } finally {
      setSaving('');
    }
  };

  const submitSection = async () => {
    if (!selectedSection || !records) return;
    if (records.some((r) => r.status === null)) {
      setError('Every student must be marked (Present, Late, Leave or Absent) before submitting');
      return;
    }
    setSaving('submit');
    setError('');
    setToast('');
    try {
      await putRegister(records);
      await api.post('/attendance/submit-section', {
        date: currentDate,
        classId: selectedSection.classId,
        sectionId: selectedSection.sectionId,
        ...scoped,
      });
      setToast('Section attendance locked');
      backToRail();
      await loadSummary(currentDate);
      await onRefresh();
    } catch (err) {
      setError(errMessage(err, 'Failed to submit section'));
    } finally {
      setSaving('');
    }
  };

  const submitDay = async () => {
    setSubmittingDay(true);
    setError('');
    setToast('');
    try {
      await api.post('/attendance/submit-day', { date: currentDate, ...scoped });
      setToast(`Submitted attendance for ${summary.dateLabel}`);
      await loadSummary(currentDate);
      await onRefresh();
    } catch (err) {
      setError(errMessage(err, 'Could not submit attendance day'));
    } finally {
      setSubmittingDay(false);
    }
  };

  return (
    <ModuleShell
      title="Attendance"
      subtitle={`${summary.dateLabel || '—'} · ${Number(summary.overallPercent || 0).toFixed(1)}% overall`}
      actions={
        <button
          className="ck-btn ck-btn-g"
          disabled={!summary.allSubmitted || submittingDay}
          onClick={submitDay}
        >
          Submit today's attendance
        </button>
      }
    >
      {summary.nonWorkingDay && (
        <div className="ck-alert ck-alert-am" style={{ marginBottom: 16 }}>
          <span>⚠</span>
          <div>This is a non-working day. Attendance can still be recorded.</div>
        </div>
      )}
      {toast && (
        <div className="ck-alert ck-alert-g" style={{ marginBottom: 16 }}>
          <span>✓</span>
          <div>{toast}</div>
        </div>
      )}
      {error && (
        <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}>
          <span>!</span>
          <div>{error}</div>
        </div>
      )}

      <div style={{ marginBottom: 16 }}>
        <Field label="Date">
          <input type="date" value={currentDate} onChange={(e) => handleDateChange(e.target.value)} />
        </Field>
      </div>

      <div className={`ck-att-layout${selectedSection ? ' ck-att-layout--detail' : ''}`}>
        <SectionRail
          sections={summary.sections || []}
          selectedSectionId={selectedSection?.sectionId ?? null}
          loading={summaryLoading}
          onSelect={openSection}
        />
        {selectedSection ? (
          <SectionRoster
            register={register}
            records={records}
            loading={rosterLoading}
            saving={saving}
            onStatusChange={setStatus}
            onRemarksChange={setRemarks}
            onMarkAllPresent={markAllPresent}
            onReset={resetChanges}
            onSave={save}
            onSubmit={submitSection}
            onBack={backToRail}
          />
        ) : (
          <div className="ck-att-roster">
            <div style={{ padding: '32px 0', textAlign: 'center', color: 'var(--ink3)' }}>
              Select a section to mark attendance.
            </div>
          </div>
        )}
      </div>
    </ModuleShell>
  );
}
```

- [ ] **Step 2: Write the failing test**

```tsx
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { AttendancePanel } from './AttendancePanel';
import api from '../../../services/api';

vi.mock('../../../services/api');

afterEach(cleanup);

const summary = {
  date: '2024-03-04', dateLabel: '04 Mar 2024', overallPercent: 66.7, allSubmitted: false,
  nonWorkingDay: false,
  sections: [
    { sectionId: 's1', classId: 'c1', sectionName: 'Class 1-A', totalStudents: 2, presentCount: 0,
      lateCount: 0, leaveCount: 0, absentCount: 0, presentPercent: 0, teacherName: 'Ms Rao',
      status: 'Pending', locked: false },
  ],
};

const register = {
  date: '2024-03-04', classId: 'c1', sectionId: 's1', sectionName: 'Class 1-A', locked: false,
  totalStudents: 2, presentCount: 0, lateCount: 0, leaveCount: 0, absentCount: 0, presentPercent: 0,
  students: [
    { studentId: 1, fullName: 'A One', admissionNo: 'ADM1', rollNo: '1', photoUrl: null, status: null, remarks: '' },
    { studentId: 2, fullName: 'B Two', admissionNo: 'ADM2', rollNo: '2', photoUrl: null, status: null, remarks: '' },
  ],
};

describe('AttendancePanel', () => {
  beforeEach(() => {
    vi.mocked(api.get).mockReset();
    vi.mocked(api.put).mockReset();
    vi.mocked(api.get).mockImplementation((url: string) => {
      if (url === '/attendance/daily-summary') return Promise.resolve({ data: summary });
      if (url === '/attendance/section-register') return Promise.resolve({ data: register });
      return Promise.resolve({ data: {} });
    });
    vi.mocked(api.put).mockResolvedValue({ data: register });
  });

  it('selecting a rail section loads and shows its roster', async () => {
    render(<AttendancePanel onRefresh={vi.fn().mockResolvedValue(undefined)} />);
    await waitFor(() => expect(screen.getByText('Class 1-A')).toBeTruthy());
    fireEvent.click(screen.getByText('Class 1-A'));
    await waitFor(() =>
      expect(api.get).toHaveBeenCalledWith('/attendance/section-register', {
        params: { date: expect.any(String), classId: 'c1', sectionId: 's1' },
      })
    );
    // Roster now shows the students.
    await waitFor(() => expect(screen.getByText('A One')).toBeTruthy());
  });

  it('PUTs 4-value statuses on Save', async () => {
    render(<AttendancePanel onRefresh={vi.fn().mockResolvedValue(undefined)} />);
    await waitFor(() => screen.getByText('Class 1-A'));
    fireEvent.click(screen.getByText('Class 1-A'));
    await waitFor(() => screen.getByText('A One'));

    // Mark student 1 LATE via that row's L pill.
    const lateButtons = screen.getAllByRole('button', { name: 'LATE' });
    fireEvent.click(lateButtons[0]);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(api.put).toHaveBeenCalled());
    const putBody = vi.mocked(api.put).mock.calls[0][1] as { records: Array<{ studentId: number; status: string }> };
    expect(putBody.records).toContainEqual(expect.objectContaining({ studentId: 1, status: 'LATE' }));
  });
});
```

- [ ] **Step 3: Run the test — verify it passes**

Run:
```bash
cd frontend
npm test -- AttendancePanel
```
Expected: PASS (2 tests).

- [ ] **Step 4: Full frontend build + test sweep**

Run:
```bash
cd frontend
npm run build
npm test
```
Expected: build succeeds; the whole Vitest suite passes (including the pre-existing panel tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/workspace/panels/AttendancePanel.tsx \
        frontend/src/pages/workspace/panels/AttendancePanel.test.tsx
git commit -m "feat(attendance-ui): master-detail AttendancePanel (rail + roster, mobile stack)"
```

---

## Self-Review Notes

- **Spec coverage:** statuses (T1/T2), percent formula + Leave-excluded (T2 helper + round-trip), `late_count`/`leave_count` columns (T1) and writes (T2), DTO/status rejection (T2 repo guard → 400 via controller `execute`), response additions `lateCount`/`leaveCount` (T2 read methods + T3 types), component split rail/roster/row (T4–T7), segmented pills (T4), mark-all-present + submit gating (T5), two-pane→mobile-stack via CSS (T3 css + T7 `--detail` class), reuse of the 5 endpoints (T7). All spec sections map to a task.
- **Percent name consistency:** `presentPercent` used identically in `sectionRegister`, `dailySummary` section maps, and both TS interfaces; `overallPercent` remains the day-level key.
- **`upsertDaily` arity:** every call site updated in T2 (Steps 3, 4); Step 5 is the explicit checkpoint for `saveDailyAttendance`, the one path that keeps its own inline SQL.
- **DTO validation location:** kept in the repo loop (matches the existing pattern; `records` is an untyped `Object` on the DTO so bean-validation of list items isn't available) — it throws `IllegalArgumentException`, which the compat controller's `execute(...)` maps to 400. This satisfies the spec's "reject unknown status with 400" without a shape change.
- **Out of scope (unchanged):** monthly/history/export (sub-project 2), absentee notifications (sub-project 3), Half-day, backfilling historical rows.
```