# Attendance — Reporting & History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three read-only attendance reports (monthly register grid, per-student history, section/class summary) with CSV + PDF export, reusing sub-project 1's four buckets and percent rule.

**Architecture:** New read-only aggregation methods on `AttendanceReadRepository` + new `GET /attendance/report/**` endpoints on `AttendanceReadController` (no schema change — aggregate the existing `attendance_student_records` and `attendance_daily`). Export endpoints return CSV (hand-built) and PDF (OpenPDF tables) as blobs, matching the fee-structure/receipt download pattern. Frontend adds a `Mark | Reports` in-panel tab host and a reports panel with three views + a blob-download control.

**Tech Stack:** Spring Boot 4.0.7 / Java 25, `JdbcClient`, OpenPDF 1.3.35, Testcontainers `postgres:16`; React 18 + Vite + TS, Vitest + `@testing-library/react`.

## Global Constraints

- **Read-only.** No mutation, no new tables, no marking. Aggregate existing data only.
- **Counting reuses sub-project 1:** four buckets (`presentCount`=PRESENT-only, `lateCount`, `leaveCount`, `absentCount`) and `AttendanceReadRepository.attendancePercent(present, late, absent)` — `(P+Late)/(P+Late+Absent)`, Leave excluded, one decimal. Never a second formula.
- **Scoping:** every endpoint calls `requireToken(token, "attendance:read")` then `TenantScope.resolveSchoolId(schoolId)`; a scoped caller requesting another school's section/student → 403 (`SecurityException`). Superadmin passes `schoolId=null` and is unscoped.
- **Export = server-side blob:** `text/csv` or `application/pdf` with `Content-Disposition: attachment`, fetched client-side via `responseType: 'blob'`. Both formats for all three views.
- **PDF via OpenPDF** `com.github.librepdf:openpdf:1.3.35` (LGPL/MPL). Do NOT touch `FeeReadRepository.simplePdf` (fees keep their generator).
- **Status letters (grid/CSV):** P=PRESENT, L=LATE, E=LEAVE, A=ABSENT, empty=unmarked. On-screen pills keep sub-project 1's `P/L/Ex/A` labels.
- **Additive:** existing endpoints, types, and the controller constructor are unchanged. CSV/PDF formatters are **static** utilities (no DI) so the controller constructor and its existing tests are untouched.
- **UI:** an in-panel `Mark | Reports` switch; the left-nav is unchanged.
- **Active academic year** scopes all reports (via `currentAcademicYearId()`).
- Build/test with **JDK 25** (`C:\Program Files\Java\jdk-25.0.3`); the module targets `--release 25`. Backend commands: `.\mvnw.cmd -f services\school-core-service\pom.xml …`.

---

### Task 1: Register report query + endpoint

**Files:**
- Modify: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/AttendanceReadRepository.java`
- Modify: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/AttendanceReadController.java`
- Test: `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/AttendanceRegisterReportTest.java`

**Interfaces:**
- Consumes: existing repo helpers `currentAcademicYearId()`, `sectionRecord(String)`, `requireSectionSchool(Map, String)`, `attendancePercent(int,int,int)` (static), `row(Object...)`, `str`, `longNum`, and field `recordsTable`.
- Produces: `Map<String,Object> registerReport(String month, String classId, String sectionId, Long schoolId)` and `GET /api/v1/attendance/report/register`.

- [ ] **Step 1: Add imports to `AttendanceReadRepository.java`**

Add to the import block (after the existing `java.time` imports):

```java
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
```

(If any are already present, skip the duplicate.)

- [ ] **Step 2: Write the failing test**

Create `AttendanceRegisterReportTest.java` (mirrors `AttendanceLateLeaveRoundTripTest`'s Testcontainers bootstrap — migrate `tenant_school`, `student`, `attendance` as `owner`; mock `StudentPhotoStorage`):

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

class AttendanceRegisterReportTest {

    static PostgreSQLContainer<?> PG;
    static DataSource ds;
    static AttendanceReadRepository repo;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        for (String schema : new String[] {"tenant_school", "student", "attendance"}) {
            Flyway.configure().dataSource(PG.getJdbcUrl(), "owner", "owner")
                    .schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/" + schema).load().migrate();
        }
        ds = new DriverManagerDataSource(PG.getJdbcUrl(), "owner", "owner");
        StudentPhotoStorage photo = Mockito.mock(StudentPhotoStorage.class);
        Mockito.when(photo.toDisplayUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        repo = new AttendanceReadRepository(JdbcClient.create(ds), photo, "attendance");
    }

    @AfterAll
    static void tearDown() { if (PG != null) PG.stop(); }

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
            st.execute("INSERT INTO tenant_school.schools(id, name, short_code, active, created_at) VALUES (1,'S','S',true, now())");
            st.execute("INSERT INTO tenant_school.school_classes(id, name, sort_order) VALUES ('c1','Class 1',1)");
            st.execute("INSERT INTO tenant_school.school_sections(id, name, teacher_name, active, school_class_id, school_id) VALUES ('s1','A','Ms Rao',true,'c1',1)");
            for (int i = 1; i <= 2; i++) {
                st.execute("INSERT INTO student.students(id, admission_no, roll_no, full_name, school_id, class_id, section_id, academic_year_id) VALUES " +
                        "(" + i + ",'ADM" + i + "','" + i + "','Student " + i + "',1,'c1','s1','AY')");
            }
            // Student 1: 2024-07-01 PRESENT, 07-02 LATE, 07-03 LEAVE, 07-04 ABSENT.
            String[][] rows = {
                {"r1","1","2024-07-01","PRESENT"}, {"r2","1","2024-07-02","LATE"},
                {"r3","1","2024-07-03","LEAVE"},   {"r4","1","2024-07-04","ABSENT"},
                {"r5","2","2024-07-01","PRESENT"}, {"r6","2","2024-07-02","PRESENT"},
            };
            for (String[] r : rows) {
                st.execute("INSERT INTO attendance.attendance_student_records" +
                        "(id, student_id, school_id, attendance_date, academic_year_id, class_id, section_id, status) VALUES " +
                        "('" + r[0] + "'," + r[1] + ",1,'" + r[2] + "','AY','c1','s1','" + r[3] + "')");
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void registerReport_pivotsCellsAndComputesBuckets() {
        Map<String, Object> report = repo.registerReport("2024-07", "c1", "s1", 1L);
        assertThat(report.get("sectionName")).isEqualTo("Class 1-A");
        List<Map<String, Object>> days = (List<Map<String, Object>>) report.get("days");
        assertThat(days).hasSize(31);                                  // July has 31 days
        assertThat(days.get(0)).containsEntry("date", "2024-07-01").containsEntry("dayOfMonth", 1);

        List<Map<String, Object>> students = (List<Map<String, Object>>) report.get("students");
        Map<String, Object> s1 = students.stream().filter(s -> "ADM1".equals(s.get("admissionNo"))).findFirst().orElseThrow();
        List<Map<String, Object>> cells = (List<Map<String, Object>>) s1.get("cells");
        assertThat(cells).hasSize(31);
        assertThat(cells.get(0)).containsEntry("status", "PRESENT");   // 07-01
        assertThat(cells.get(1)).containsEntry("status", "LATE");      // 07-02
        assertThat(cells.get(2)).containsEntry("status", "LEAVE");     // 07-03
        assertThat(cells.get(3)).containsEntry("status", "ABSENT");    // 07-04
        assertThat(cells.get(4).get("status")).isNull();              // 07-05 unmarked
        assertThat(s1.get("presentCount")).isEqualTo(1);
        assertThat(s1.get("lateCount")).isEqualTo(1);
        assertThat(s1.get("leaveCount")).isEqualTo(1);
        assertThat(s1.get("absentCount")).isEqualTo(1);
        // (P+Late)/(P+Late+Absent) = 2/3 = 66.7; Leave excluded.
        assertThat(((Number) s1.get("presentPercent")).doubleValue()).isEqualTo(66.7);

        List<Map<String, Object>> dayTotals = (List<Map<String, Object>>) report.get("dayTotals");
        assertThat(dayTotals.get(0)).containsEntry("presentCount", 2); // both students PRESENT on 07-01
    }

    @Test
    void registerReport_crossSchoolIsForbidden() {
        assertThatThrownBy(() -> repo.registerReport("2024-07", "c1", "s1", 999L))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void registerReport_badMonthThrows() {
        assertThatThrownBy(() -> repo.registerReport("2024-13", "c1", "s1", 1L))
                .isInstanceOf(RuntimeException.class);  // DateTimeParseException (→ 400 at controller)
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run:
```bash
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -f services\school-core-service\pom.xml test -Dtest=AttendanceRegisterReportTest
```
Expected: compile error / FAIL — `registerReport` does not exist yet.

- [ ] **Step 4: Implement `registerReport` in `AttendanceReadRepository.java`**

Add this method (e.g. after `dailySummary`):

```java
    public Map<String, Object> registerReport(String month, String classId, String sectionId, Long schoolId) {
        YearMonth ym = YearMonth.parse(month);   // invalid → DateTimeParseException → 400 at controller
        LocalDate first = ym.atDay(1);
        LocalDate last = ym.atEndOfMonth();
        String academicYearId = currentAcademicYearId();
        Map<String, Object> section = sectionRecord(sectionId);
        if (!classId.equals(section.get("classId"))) {
            throw new IllegalArgumentException("Section does not belong to class");
        }
        Long sectionSchoolId = requireSectionSchool(section, sectionId);
        if (schoolId != null && !schoolId.equals(sectionSchoolId)) {
            throw new SecurityException("You do not have access to this section");
        }

        List<Map<String, Object>> days = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            days.add(row("date", d.toString(),
                    "dayOfMonth", d.getDayOfMonth(),
                    "weekday", d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    "nonWorkingDay", d.getDayOfWeek() == DayOfWeek.SUNDAY));
        }

        List<Map<String, Object>> students = jdbc.sql("""
                SELECT id, admission_no, roll_no, full_name
                FROM student.students
                WHERE school_id = :schoolId AND class_id = :classId AND section_id = :sectionId
                  AND deleted_at IS NULL
                ORDER BY NULLIF(regexp_replace(COALESCE(roll_no, ''), '[^0-9]', '', 'g'), '')::int NULLS LAST,
                         roll_no NULLS LAST, full_name
                """)
                .param("schoolId", sectionSchoolId)
                .param("classId", classId)
                .param("sectionId", sectionId)
                .query((rs, n) -> row("studentId", rs.getLong("id"),
                        "admissionNo", rs.getString("admission_no"),
                        "rollNo", rs.getString("roll_no"),
                        "fullName", rs.getString("full_name")))
                .list();

        // studentId -> (date -> status)
        Map<Long, Map<String, String>> byStudent = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT student_id, attendance_date, status
                FROM %s
                WHERE section_id = :sectionId AND academic_year_id = :academicYearId
                  AND attendance_date BETWEEN :first AND :last
                """.formatted(recordsTable))
                .param("sectionId", sectionId)
                .param("academicYearId", academicYearId)
                .param("first", first)
                .param("last", last)
                .query((rs, n) -> {
                    byStudent.computeIfAbsent(rs.getLong("student_id"), k -> new LinkedHashMap<>())
                            .put(rs.getObject("attendance_date", LocalDate.class).toString(), rs.getString("status"));
                    return null;
                })
                .list();

        int[] totP = new int[days.size()], totL = new int[days.size()], totE = new int[days.size()], totA = new int[days.size()];
        List<Map<String, Object>> studentRows = new ArrayList<>();
        int sumP = 0, sumL = 0, sumE = 0, sumA = 0;
        for (Map<String, Object> student : students) {
            Long sid = longNum(student.get("studentId"), 0);
            Map<String, String> byDate = byStudent.getOrDefault(sid, Map.of());
            List<Map<String, Object>> cells = new ArrayList<>();
            int p = 0, l = 0, e = 0, a = 0;
            for (int i = 0; i < days.size(); i++) {
                String date = str(days.get(i).get("date"), "");
                String status = byDate.get(date);
                cells.add(row("date", date, "status", status));
                if ("PRESENT".equals(status)) { p++; totP[i]++; }
                else if ("LATE".equals(status)) { l++; totL[i]++; }
                else if ("LEAVE".equals(status)) { e++; totE[i]++; }
                else if ("ABSENT".equals(status)) { a++; totA[i]++; }
            }
            sumP += p; sumL += l; sumE += e; sumA += a;
            studentRows.add(row("studentId", sid,
                    "admissionNo", student.get("admissionNo"),
                    "rollNo", student.get("rollNo"),
                    "fullName", student.get("fullName"),
                    "cells", cells,
                    "presentCount", p, "lateCount", l, "leaveCount", e, "absentCount", a,
                    "presentPercent", attendancePercent(p, l, a)));
        }

        List<Map<String, Object>> dayTotals = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            dayTotals.add(row("date", str(days.get(i).get("date"), ""),
                    "presentCount", totP[i], "lateCount", totL[i], "leaveCount", totE[i], "absentCount", totA[i]));
        }

        return row("month", month,
                "monthLabel", ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear(),
                "classId", classId,
                "sectionId", sectionId,
                "sectionName", section.get("className") + "-" + section.get("name"),
                "teacherName", section.get("teacherName"),
                "days", days,
                "students", studentRows,
                "dayTotals", dayTotals,
                "totals", row("presentCount", sumP, "lateCount", sumL, "leaveCount", sumE, "absentCount", sumA,
                        "presentPercent", attendancePercent(sumP, sumL, sumA)));
    }
```

- [ ] **Step 5: Add the controller endpoint**

In `AttendanceReadController.java`, after the `section-register` GET handler (before the PUT section), add:

```java
    @GetMapping("/report/register")
    public Map<String, Object> reportRegister(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam String month,
            @RequestParam String classId,
            @RequestParam String sectionId) {
        requireToken(token, "attendance:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return execute(() -> attendance.registerReport(month, classId, sectionId, scope));
    }
```

- [ ] **Step 6: Run the test to verify it passes**

Run:
```bash
.\mvnw.cmd -f services\school-core-service\pom.xml test -Dtest=AttendanceRegisterReportTest
```
Expected: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
git add services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/AttendanceReadRepository.java \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/AttendanceReadController.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/AttendanceRegisterReportTest.java
git commit -m "feat(attendance): monthly register report query + endpoint"
```

---

### Task 2: Student history + section summary queries + endpoints

**Files:**
- Modify: `services/school-core-service/.../persistence/AttendanceReadRepository.java`
- Modify: `services/school-core-service/.../api/AttendanceReadController.java`
- Test: `services/school-core-service/.../persistence/AttendanceHistorySummaryReportTest.java`

**Interfaces:**
- Consumes: repo helpers as in Task 1, plus `dailyTable`.
- Produces: `Map<String,Object> studentHistory(Long studentId, LocalDate from, LocalDate to, Long schoolId)`, `Map<String,Object> sectionSummary(LocalDate from, LocalDate to, Long schoolId)`, and `GET /attendance/report/student`, `GET /attendance/report/summary`.

- [ ] **Step 1: Write the failing test**

Create `AttendanceHistorySummaryReportTest.java` — reuse the exact `@BeforeAll/@BeforeEach` seed harness from `AttendanceRegisterReportTest` (copy the setUp/seed, but also seed `attendance_daily` rollups for the summary):

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

class AttendanceHistorySummaryReportTest {

    static PostgreSQLContainer<?> PG;
    static DataSource ds;
    static AttendanceReadRepository repo;
    static final LocalDate FROM = LocalDate.parse("2024-07-01");
    static final LocalDate TO = LocalDate.parse("2024-07-31");

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        for (String schema : new String[] {"tenant_school", "student", "attendance"}) {
            Flyway.configure().dataSource(PG.getJdbcUrl(), "owner", "owner")
                    .schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/" + schema).load().migrate();
        }
        ds = new DriverManagerDataSource(PG.getJdbcUrl(), "owner", "owner");
        StudentPhotoStorage photo = Mockito.mock(StudentPhotoStorage.class);
        Mockito.when(photo.toDisplayUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        repo = new AttendanceReadRepository(JdbcClient.create(ds), photo, "attendance");
    }

    @AfterAll
    static void tearDown() { if (PG != null) PG.stop(); }

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String t : new String[] {"attendance.attendance_student_records", "attendance.attendance_daily",
                    "student.students", "tenant_school.school_sections", "tenant_school.school_classes",
                    "tenant_school.schools", "tenant_school.academic_years"}) {
                st.execute("DELETE FROM " + t);
            }
            st.execute("INSERT INTO tenant_school.academic_years(id, label, active) VALUES ('AY','2024-25',true)");
            st.execute("INSERT INTO tenant_school.schools(id, name, short_code, active, created_at) VALUES (1,'S','S',true, now())");
            st.execute("INSERT INTO tenant_school.school_classes(id, name, sort_order) VALUES ('c1','Class 1',1)");
            st.execute("INSERT INTO tenant_school.school_sections(id, name, teacher_name, active, school_class_id, school_id) VALUES " +
                    "('s1','A','Ms Rao',true,'c1',1),('s2','B','Mr Das',true,'c1',1)");
            st.execute("INSERT INTO student.students(id, admission_no, roll_no, full_name, school_id, class_id, section_id, academic_year_id) VALUES " +
                    "(1,'ADM1','1','Asha Rao',1,'c1','s1','AY')");
            // student 1 history: P, LATE, LEAVE, ABSENT.
            String[][] recs = {{"h1","2024-07-01","PRESENT","ok"},{"h2","2024-07-02","LATE","bus"},
                    {"h3","2024-07-03","LEAVE","sick"},{"h4","2024-07-04","ABSENT",""}};
            for (String[] r : recs) {
                st.execute("INSERT INTO attendance.attendance_student_records" +
                        "(id, student_id, school_id, attendance_date, academic_year_id, class_id, section_id, status, remarks) VALUES " +
                        "('" + r[0] + "',1,1,'" + r[1] + "','AY','c1','s1','" + r[2] + "','" + r[3] + "')");
            }
            // attendance_daily rollups for summary: s1 two days, s2 one day.
            st.execute("INSERT INTO attendance.attendance_daily" +
                    "(id, attendance_date, total_enrolled, present_count, absent_count, late_count, leave_count, locked, school_class_id, section_id, academic_year_id, school_id) VALUES " +
                    "('d1','2024-07-01',3,2,1,0,0,false,'c1','s1','AY',1)," +
                    "('d2','2024-07-02',3,1,0,1,1,false,'c1','s1','AY',1)," +
                    "('d3','2024-07-01',2,2,0,0,0,false,'c1','s2','AY',1)");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void studentHistory_listsDaysAndComputesPercent() {
        Map<String, Object> h = repo.studentHistory(1L, FROM, TO, 1L);
        Map<String, Object> student = (Map<String, Object>) h.get("student");
        assertThat(student).containsEntry("fullName", "Asha Rao").containsEntry("sectionName", "Class 1-A");
        List<Map<String, Object>> days = (List<Map<String, Object>>) h.get("days");
        assertThat(days).hasSize(4);
        assertThat(days.get(0)).containsEntry("status", "PRESENT").containsEntry("remarks", "ok");
        assertThat(h.get("presentCount")).isEqualTo(1);
        assertThat(h.get("lateCount")).isEqualTo(1);
        assertThat(h.get("leaveCount")).isEqualTo(1);
        assertThat(h.get("absentCount")).isEqualTo(1);
        assertThat(h.get("daysRecorded")).isEqualTo(4);
        assertThat(((Number) h.get("presentPercent")).doubleValue()).isEqualTo(66.7);
    }

    @Test
    void studentHistory_crossSchoolIsForbidden() {
        assertThatThrownBy(() -> repo.studentHistory(1L, FROM, TO, 999L)).isInstanceOf(SecurityException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sectionSummary_aggregatesRanksAndOveralls() {
        Map<String, Object> s = repo.sectionSummary(FROM, TO, 1L);
        List<Map<String, Object>> sections = (List<Map<String, Object>>) s.get("sections");
        assertThat(sections).hasSize(2);
        // s2 = 2/2 = 100%; s1 = (2+1 present+late)/(2+1+1 = 4) = 3/4 = 75% → s2 ranks first.
        assertThat(sections.get(0)).containsEntry("sectionName", "Class 1-B");
        assertThat(((Number) sections.get(0).get("presentPercent")).doubleValue()).isEqualTo(100.0);
        Map<String, Object> s1 = sections.stream().filter(x -> "s1".equals(x.get("sectionId"))).findFirst().orElseThrow();
        assertThat(((Number) s1.get("presentPercent")).doubleValue()).isEqualTo(75.0);
        assertThat(s1.get("daysRecorded")).isEqualTo(2);
        // overall attended = (2+2 present) + (0+1 late) = 5; denom = 5 + (1 absent) = 6 → 83.3
        assertThat(((Number) ((Map<String, Object>) s.get("overall")).get("presentPercent")).doubleValue()).isEqualTo(83.3);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
.\mvnw.cmd -f services\school-core-service\pom.xml test -Dtest=AttendanceHistorySummaryReportTest
```
Expected: FAIL — `studentHistory` / `sectionSummary` not defined.

- [ ] **Step 3: Implement both methods in `AttendanceReadRepository.java`**

Add after `registerReport`:

```java
    public Map<String, Object> studentHistory(Long studentId, LocalDate from, LocalDate to, Long schoolId) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must be on or before to");
        }
        String academicYearId = currentAcademicYearId();
        Map<String, Object> student = jdbc.sql("""
                SELECT s.id, s.admission_no, s.roll_no, s.full_name, s.school_id,
                       sec.name AS section_name, sc.name AS class_name
                FROM student.students s
                LEFT JOIN tenant_school.school_sections sec ON sec.id = s.section_id
                LEFT JOIN tenant_school.school_classes sc ON sc.id = s.class_id
                WHERE s.id = :id AND s.deleted_at IS NULL
                """)
                .param("id", studentId)
                .query((rs, n) -> row("studentId", rs.getLong("id"),
                        "admissionNo", rs.getString("admission_no"),
                        "rollNo", rs.getString("roll_no"),
                        "fullName", rs.getString("full_name"),
                        "schoolId", rs.getLong("school_id"),
                        "sectionName", (rs.getString("class_name") == null ? "" : rs.getString("class_name") + "-")
                                + (rs.getString("section_name") == null ? "" : rs.getString("section_name"))))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
        Long studentSchoolId = longNum(student.get("schoolId"), 0);
        if (schoolId != null && !schoolId.equals(studentSchoolId)) {
            throw new SecurityException("You do not have access to this student");
        }

        int[] buckets = new int[4]; // P, L, E, A
        List<Map<String, Object>> days = jdbc.sql("""
                SELECT attendance_date, status, remarks
                FROM %s
                WHERE student_id = :id AND academic_year_id = :academicYearId
                  AND attendance_date BETWEEN :from AND :to
                ORDER BY attendance_date
                """.formatted(recordsTable))
                .param("id", studentId)
                .param("academicYearId", academicYearId)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> {
                    LocalDate d = rs.getObject("attendance_date", LocalDate.class);
                    String status = rs.getString("status");
                    if ("PRESENT".equals(status)) buckets[0]++;
                    else if ("LATE".equals(status)) buckets[1]++;
                    else if ("LEAVE".equals(status)) buckets[2]++;
                    else if ("ABSENT".equals(status)) buckets[3]++;
                    return row("date", d.toString(),
                            "weekday", d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                            "status", status,
                            "remarks", rs.getString("remarks") == null ? "" : rs.getString("remarks"),
                            "nonWorkingDay", d.getDayOfWeek() == DayOfWeek.SUNDAY);
                })
                .list();

        Map<String, Object> studentInfo = row("studentId", student.get("studentId"),
                "admissionNo", student.get("admissionNo"),
                "rollNo", student.get("rollNo"),
                "fullName", student.get("fullName"),
                "sectionName", student.get("sectionName"));
        return row("student", studentInfo,
                "from", from.toString(), "to", to.toString(),
                "days", days,
                "presentCount", buckets[0], "lateCount", buckets[1], "leaveCount", buckets[2], "absentCount", buckets[3],
                "presentPercent", attendancePercent(buckets[0], buckets[1], buckets[3]),
                "daysRecorded", days.size());
    }

    public Map<String, Object> sectionSummary(LocalDate from, LocalDate to, Long schoolId) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must be on or before to");
        }
        String academicYearId = currentAcademicYearId();
        String scopeFilter = schoolId == null ? "" : " AND ad.school_id = :schoolId";
        var spec = jdbc.sql("""
                SELECT ss.id AS section_id, ss.school_class_id AS class_id, ss.name AS section_name,
                       ss.teacher_name, sc.name AS class_name,
                       COALESCE(SUM(ad.present_count), 0) AS p,
                       COALESCE(SUM(ad.late_count), 0)    AS l,
                       COALESCE(SUM(ad.leave_count), 0)   AS e,
                       COALESCE(SUM(ad.absent_count), 0)  AS a,
                       COUNT(ad.id) AS days_recorded
                FROM %s ad
                JOIN tenant_school.school_sections ss ON ss.id = ad.section_id
                JOIN tenant_school.school_classes sc ON sc.id = ss.school_class_id
                WHERE ad.academic_year_id = :academicYearId
                  AND ad.attendance_date BETWEEN :from AND :to
                  %s
                GROUP BY ss.id, ss.school_class_id, ss.name, ss.teacher_name, sc.name
                """.formatted(dailyTable, scopeFilter))
                .param("academicYearId", academicYearId)
                .param("from", from)
                .param("to", to);
        if (schoolId != null) {
            spec = spec.param("schoolId", schoolId);
        }
        List<Map<String, Object>> sections = new ArrayList<>(spec
                .query((rs, n) -> {
                    int p = rs.getInt("p"), l = rs.getInt("l"), e = rs.getInt("e"), a = rs.getInt("a");
                    return row("classId", rs.getString("class_id"),
                            "sectionId", rs.getString("section_id"),
                            "sectionName", rs.getString("class_name") + "-" + rs.getString("section_name"),
                            "teacherName", rs.getString("teacher_name"),
                            "presentCount", p, "lateCount", l, "leaveCount", e, "absentCount", a,
                            "presentPercent", attendancePercent(p, l, a),
                            "daysRecorded", rs.getInt("days_recorded"));
                })
                .list());

        sections.sort((x, y) -> {
            int cmp = Double.compare(doubleNum(y.get("presentPercent")), doubleNum(x.get("presentPercent")));
            return cmp != 0 ? cmp : str(x.get("sectionName"), "").compareTo(str(y.get("sectionName"), ""));
        });

        int sp = 0, sl = 0, se = 0, sa = 0;
        for (Map<String, Object> s : sections) {
            sp += (int) longNum(s.get("presentCount"), 0);
            sl += (int) longNum(s.get("lateCount"), 0);
            se += (int) longNum(s.get("leaveCount"), 0);
            sa += (int) longNum(s.get("absentCount"), 0);
        }
        return row("from", from.toString(), "to", to.toString(),
                "sections", sections,
                "overall", row("presentCount", sp, "lateCount", sl, "leaveCount", se, "absentCount", sa,
                        "presentPercent", attendancePercent(sp, sl, sa)));
    }
```

> Note: `doubleNum` was removed in sub-project 1's cleanup. Re-add a tiny private helper if it is absent:
> ```java
> private double doubleNum(Object value) {
>     if (value instanceof Number number) return number.doubleValue();
>     return Double.parseDouble(String.valueOf(value));
> }
> ```
> (Check first — if a `doubleNum` already exists, reuse it; do not duplicate.)

- [ ] **Step 4: Add the two controller endpoints**

In `AttendanceReadController.java`, after `reportRegister`, add (note the `@DateTimeFormat` import already exists):

```java
    @GetMapping("/report/student")
    public Map<String, Object> reportStudent(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam Long studentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireToken(token, "attendance:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return execute(() -> attendance.studentHistory(studentId, from, to, scope));
    }

    @GetMapping("/report/summary")
    public Map<String, Object> reportSummary(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireToken(token, "attendance:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return execute(() -> attendance.sectionSummary(from, to, scope));
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run:
```bash
.\mvnw.cmd -f services\school-core-service\pom.xml test -Dtest=AttendanceHistorySummaryReportTest
```
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/AttendanceReadRepository.java \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/AttendanceReadController.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/AttendanceHistorySummaryReportTest.java
git commit -m "feat(attendance): per-student history + section summary report queries + endpoints"
```

---

### Task 3: CSV export (formatter + export endpoints)

**Files:**
- Create: `services/school-core-service/.../application/report/AttendanceReportCsv.java`
- Modify: `services/school-core-service/.../api/AttendanceReadController.java`
- Test: `services/school-core-service/.../application/report/AttendanceReportCsvTest.java`

**Interfaces:**
- Produces: `AttendanceReportCsv` (static) with `byte[] register(Map)`, `byte[] student(Map)`, `byte[] summary(Map)`; and `GET /attendance/report/{register|student|summary}/export?format=csv` (pdf → 400 until Task 4).

- [ ] **Step 1: Write the failing test (pure, no DB)**

Create `AttendanceReportCsvTest.java`:

```java
package com.custoking.ims.schoolcoreservice.application.report;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AttendanceReportCsvTest {

    @Test
    void register_hasHeaderDayColumnsAndTotals() {
        Map<String, Object> report = Map.of(
                "sectionName", "Class 1-A", "monthLabel", "July 2024",
                "days", List.of(Map.of("date", "2024-07-01", "dayOfMonth", 1),
                                Map.of("date", "2024-07-02", "dayOfMonth", 2)),
                "students", List.of(Map.of("rollNo", "1", "admissionNo", "ADM1", "fullName", "Asha",
                        "cells", List.of(Map.of("status", "PRESENT"), Map.of("status", "LATE")),
                        "presentCount", 1, "lateCount", 1, "leaveCount", 0, "absentCount", 0, "presentPercent", 100.0)),
                "dayTotals", List.of(Map.of("presentCount", 1, "lateCount", 0, "leaveCount", 0, "absentCount", 0),
                                     Map.of("presentCount", 0, "lateCount", 1, "leaveCount", 0, "absentCount", 0)),
                "totals", Map.of("presentCount", 1, "lateCount", 1, "leaveCount", 0, "absentCount", 0, "presentPercent", 100.0));
        String csv = new String(AttendanceReportCsv.register(report), StandardCharsets.UTF_8);
        assertThat(csv).contains("Roll,Admission No,Name,1,2,Present,Late,Leave,Absent,Present%");
        assertThat(csv).contains("1,ADM1,Asha,P,L,1,1,0,0,100.0");   // P=PRESENT, L=LATE letters
    }

    @Test
    void summary_hasHeaderAndOverall() {
        Map<String, Object> report = Map.of(
                "from", "2024-07-01", "to", "2024-07-31",
                "sections", List.of(Map.of("className", "", "sectionName", "Class 1-A", "teacherName", "Ms Rao",
                        "presentCount", 5, "lateCount", 1, "leaveCount", 0, "absentCount", 1, "presentPercent", 85.7, "daysRecorded", 2)),
                "overall", Map.of("presentCount", 5, "lateCount", 1, "leaveCount", 0, "absentCount", 1, "presentPercent", 85.7));
        String csv = new String(AttendanceReportCsv.summary(report), StandardCharsets.UTF_8);
        assertThat(csv).contains("Section,Teacher,Present,Late,Leave,Absent,Present%,Days Recorded");
        assertThat(csv).contains("Class 1-A,Ms Rao,5,1,0,1,85.7,2");
        assertThat(csv).contains("Overall");
    }

    @Test
    void quotesFieldsWithCommas() {
        Map<String, Object> report = Map.of(
                "student", Map.of("fullName", "Rao, Asha", "admissionNo", "ADM1", "rollNo", "1", "sectionName", "Class 1-A"),
                "from", "2024-07-01", "to", "2024-07-31",
                "days", List.of(Map.of("date", "2024-07-01", "weekday", "Mon", "status", "PRESENT", "remarks", "late, excused")),
                "presentCount", 1, "lateCount", 0, "leaveCount", 0, "absentCount", 0, "presentPercent", 100.0, "daysRecorded", 1);
        String csv = new String(AttendanceReportCsv.student(report), StandardCharsets.UTF_8);
        assertThat(csv).contains("\"Rao, Asha\"");
        assertThat(csv).contains("\"late, excused\"");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\mvnw.cmd -f services\school-core-service\pom.xml test -Dtest=AttendanceReportCsvTest`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement `AttendanceReportCsv.java`**

```java
package com.custoking.ims.schoolcoreservice.application.report;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Static CSV formatters for the three attendance reports. Input is the same Map the JSON endpoints return. */
public final class AttendanceReportCsv {

    private AttendanceReportCsv() {}

    private static final Map<String, String> LETTER = Map.of("PRESENT", "P", "LATE", "L", "LEAVE", "E", "ABSENT", "A");

    @SuppressWarnings("unchecked")
    public static byte[] register(Map<String, Object> r) {
        List<Map<String, Object>> days = (List<Map<String, Object>>) r.get("days");
        List<Map<String, Object>> students = (List<Map<String, Object>>) r.get("students");
        List<Map<String, Object>> dayTotals = (List<Map<String, Object>>) r.get("dayTotals");
        Map<String, Object> totals = (Map<String, Object>) r.get("totals");
        StringBuilder sb = new StringBuilder();

        StringBuilder header = new StringBuilder("Roll,Admission No,Name");
        for (Map<String, Object> d : days) header.append(',').append(d.get("dayOfMonth"));
        header.append(",Present,Late,Leave,Absent,Present%");
        line(sb, header.toString());

        for (Map<String, Object> s : students) {
            List<Map<String, Object>> cells = (List<Map<String, Object>>) s.get("cells");
            StringBuilder row = new StringBuilder();
            field(row, s.get("rollNo")); field(row, s.get("admissionNo")); field(row, s.get("fullName"));
            for (Map<String, Object> cell : cells) row.append(',').append(LETTER.getOrDefault(String.valueOf(cell.get("status")), ""));
            row.append(',').append(s.get("presentCount")).append(',').append(s.get("lateCount"))
               .append(',').append(s.get("leaveCount")).append(',').append(s.get("absentCount"))
               .append(',').append(s.get("presentPercent"));
            line(sb, row.toString());
        }

        StringBuilder tot = new StringBuilder(",,Day totals");
        for (Map<String, Object> dt : dayTotals) {
            tot.append(',').append(num(dt.get("presentCount")) + num(dt.get("lateCount")) + num(dt.get("leaveCount")) + num(dt.get("absentCount")));
        }
        tot.append(",,,,");
        line(sb, tot.toString());

        StringBuilder grand = new StringBuilder(",,Section total");
        for (int i = 0; i < days.size(); i++) grand.append(',');
        grand.append(totals.get("presentCount")).append(',').append(totals.get("lateCount"))
             .append(',').append(totals.get("leaveCount")).append(',').append(totals.get("absentCount"))
             .append(',').append(totals.get("presentPercent"));
        line(sb, grand.toString());
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static byte[] student(Map<String, Object> r) {
        Map<String, Object> st = (Map<String, Object>) r.get("student");
        List<Map<String, Object>> days = (List<Map<String, Object>>) r.get("days");
        StringBuilder sb = new StringBuilder();
        line(sb, "Student," + esc(String.valueOf(st.get("fullName"))));
        line(sb, "Admission No," + esc(String.valueOf(st.get("admissionNo"))));
        line(sb, "Section," + esc(String.valueOf(st.get("sectionName"))));
        line(sb, "Range," + r.get("from") + " to " + r.get("to"));
        line(sb, "Present," + r.get("presentCount") + ",Late," + r.get("lateCount") + ",Leave," + r.get("leaveCount")
                + ",Absent," + r.get("absentCount") + ",Present%," + r.get("presentPercent"));
        line(sb, "");
        line(sb, "Date,Weekday,Status,Remarks");
        for (Map<String, Object> d : days) {
            StringBuilder row = new StringBuilder();
            field(row, d.get("date")); field(row, d.get("weekday")); field(row, d.get("status")); field(row, d.get("remarks"));
            line(sb, row.toString());
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static byte[] summary(Map<String, Object> r) {
        List<Map<String, Object>> sections = (List<Map<String, Object>>) r.get("sections");
        Map<String, Object> overall = (Map<String, Object>) r.get("overall");
        StringBuilder sb = new StringBuilder();
        line(sb, "Range," + r.get("from") + " to " + r.get("to"));
        line(sb, "");
        line(sb, "Section,Teacher,Present,Late,Leave,Absent,Present%,Days Recorded");
        for (Map<String, Object> s : sections) {
            StringBuilder row = new StringBuilder();
            field(row, s.get("sectionName")); field(row, s.get("teacherName"));
            row.append(',').append(s.get("presentCount")).append(',').append(s.get("lateCount"))
               .append(',').append(s.get("leaveCount")).append(',').append(s.get("absentCount"))
               .append(',').append(s.get("presentPercent")).append(',').append(s.get("daysRecorded"));
            line(sb, row.toString());
        }
        line(sb, "Overall,," + overall.get("presentCount") + ',' + overall.get("lateCount") + ','
                + overall.get("leaveCount") + ',' + overall.get("absentCount") + ',' + overall.get("presentPercent") + ',');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static void line(StringBuilder sb, String s) { sb.append(s).append("\r\n"); }
    private static void field(StringBuilder row, Object value) {
        if (row.length() > 0 && row.charAt(row.length() - 1) != ',') row.append(',');
        else if (row.length() > 0) { /* already has trailing comma */ }
        row.append(esc(value == null ? "" : String.valueOf(value)));
    }
    private static String esc(String v) {
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
```

> The `field(...)` helper appends a leading comma only between fields; the first field on a fresh row has `row.length()==0` so no comma is prepended. Verify the register/student rows begin without a stray comma when you run the test.

- [ ] **Step 4: Add the export endpoints + `respondReport` helper (CSV only for now)**

In `AttendanceReadController.java`: add imports at the top —

```java
import com.custoking.ims.schoolcoreservice.application.report.AttendanceReportCsv;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.util.function.Function;
```

Add the three export handlers after `reportSummary`:

```java
    @GetMapping("/report/register/export")
    public ResponseEntity<byte[]> exportRegister(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam String month, @RequestParam String classId, @RequestParam String sectionId,
            @RequestParam(defaultValue = "csv") String format) {
        requireToken(token, "attendance:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        Map<String, Object> report = execute(() -> attendance.registerReport(month, classId, sectionId, scope));
        return respondReport("register-" + month, format, report, AttendanceReportCsv::register);
    }

    @GetMapping("/report/student/export")
    public ResponseEntity<byte[]> exportStudent(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId, @RequestParam Long studentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "csv") String format) {
        requireToken(token, "attendance:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        Map<String, Object> report = execute(() -> attendance.studentHistory(studentId, from, to, scope));
        return respondReport("student-" + studentId + "-" + from + "_" + to, format, report, AttendanceReportCsv::student);
    }

    @GetMapping("/report/summary/export")
    public ResponseEntity<byte[]> exportSummary(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "csv") String format) {
        requireToken(token, "attendance:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        Map<String, Object> report = execute(() -> attendance.sectionSummary(from, to, scope));
        return respondReport("summary-" + from + "_" + to, format, report, AttendanceReportCsv::summary);
    }

    // CSV only until Task 4 wires PDF.
    private ResponseEntity<byte[]> respondReport(String baseName, String format, Map<String, Object> report,
                                                 Function<Map<String, Object>, byte[]> csv) {
        String fmt = format == null ? "csv" : format.toLowerCase();
        if (!"csv".equals(fmt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported format: " + format);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + baseName + ".csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv")
                .body(csv.apply(report));
    }
```

- [ ] **Step 5: Run the CSV test to verify it passes**

Run: `.\mvnw.cmd -f services\school-core-service\pom.xml test -Dtest=AttendanceReportCsvTest`
Expected: PASS (3 tests). Also compile-check the controller: `.\mvnw.cmd -f services\school-core-service\pom.xml -DskipTests compile` → BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/application/report/AttendanceReportCsv.java \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/AttendanceReadController.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/application/report/AttendanceReportCsvTest.java
git commit -m "feat(attendance): CSV export for register/student/summary reports"
```

---

### Task 4: OpenPDF dependency + PDF export

**Files:**
- Modify: `services/school-core-service/pom.xml`
- Create: `services/school-core-service/.../application/report/AttendanceReportPdf.java`
- Modify: `services/school-core-service/.../api/AttendanceReadController.java` (wire PDF into `respondReport`)
- Test: `services/school-core-service/.../application/report/AttendanceReportPdfTest.java`

**Interfaces:**
- Produces: `AttendanceReportPdf` (static) `byte[] register/student/summary(Map)`; `respondReport` gains a PDF branch.

- [ ] **Step 1: Add the OpenPDF dependency**

In `services/school-core-service/pom.xml`, inside `<dependencies>` (e.g. after the `spring-boot-starter-web` dependency), add:

```xml
        <dependency>
            <groupId>com.github.librepdf</groupId>
            <artifactId>openpdf</artifactId>
            <version>1.3.35</version>
        </dependency>
```

- [ ] **Step 2: Write the failing test**

Create `AttendanceReportPdfTest.java`:

```java
package com.custoking.ims.schoolcoreservice.application.report;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AttendanceReportPdfTest {

    private static void assertPdf(byte[] bytes) {
        assertThat(bytes).isNotNull();
        assertThat(bytes.length).isGreaterThan(200);
        assertThat(new String(bytes, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void register_generatesValidPdf() {
        Map<String, Object> report = Map.of(
                "sectionName", "Class 1-A", "monthLabel", "July 2024",
                "days", List.of(Map.of("date", "2024-07-01", "dayOfMonth", 1), Map.of("date", "2024-07-02", "dayOfMonth", 2)),
                "students", List.of(Map.of("rollNo", "1", "admissionNo", "ADM1", "fullName", "Asha",
                        "cells", List.of(Map.of("status", "PRESENT"), Map.of("status", "LATE")),
                        "presentCount", 1, "lateCount", 1, "leaveCount", 0, "absentCount", 0, "presentPercent", 100.0)),
                "dayTotals", List.of(Map.of("presentCount", 1, "lateCount", 0, "leaveCount", 0, "absentCount", 0),
                                     Map.of("presentCount", 0, "lateCount", 1, "leaveCount", 0, "absentCount", 0)),
                "totals", Map.of("presentCount", 1, "lateCount", 1, "leaveCount", 0, "absentCount", 0, "presentPercent", 100.0));
        assertPdf(AttendanceReportPdf.register(report));
    }

    @Test
    void student_generatesValidPdf() {
        Map<String, Object> report = Map.of(
                "student", Map.of("fullName", "Asha", "admissionNo", "ADM1", "rollNo", "1", "sectionName", "Class 1-A"),
                "from", "2024-07-01", "to", "2024-07-31",
                "days", List.of(Map.of("date", "2024-07-01", "weekday", "Mon", "status", "PRESENT", "remarks", "ok")),
                "presentCount", 1, "lateCount", 0, "leaveCount", 0, "absentCount", 0, "presentPercent", 100.0, "daysRecorded", 1);
        assertPdf(AttendanceReportPdf.student(report));
    }

    @Test
    void summary_generatesValidPdf() {
        Map<String, Object> report = Map.of(
                "from", "2024-07-01", "to", "2024-07-31",
                "sections", List.of(Map.of("sectionName", "Class 1-A", "teacherName", "Ms Rao",
                        "presentCount", 5, "lateCount", 1, "leaveCount", 0, "absentCount", 1, "presentPercent", 85.7, "daysRecorded", 2)),
                "overall", Map.of("presentCount", 5, "lateCount", 1, "leaveCount", 0, "absentCount", 1, "presentPercent", 85.7));
        assertPdf(AttendanceReportPdf.summary(report));
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `.\mvnw.cmd -f services\school-core-service\pom.xml test -Dtest=AttendanceReportPdfTest`
Expected: FAIL — class does not exist (and/or OpenPDF resolves after Step 1).

- [ ] **Step 4: Implement `AttendanceReportPdf.java`**

```java
package com.custoking.ims.schoolcoreservice.application.report;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/** Static OpenPDF table formatters for the three attendance reports. */
public final class AttendanceReportPdf {

    private AttendanceReportPdf() {}

    private static final Font TITLE = new Font(Font.HELVETICA, 13, Font.BOLD);
    private static final Font SUB = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.DARK_GRAY);
    private static final Font HEAD = new Font(Font.HELVETICA, 7, Font.BOLD, Color.WHITE);
    private static final Font BODY = new Font(Font.HELVETICA, 7, Font.NORMAL);
    private static final Color HEAD_BG = new Color(26, 79, 168);          // --b
    private static final Map<String, Color> LETTER_COLOR = Map.of(
            "P", new Color(26, 104, 64), "L", new Color(179, 92, 0), "E", new Color(26, 79, 168), "A", new Color(192, 49, 43));
    private static final Map<String, String> LETTER = Map.of("PRESENT", "P", "LATE", "L", "LEAVE", "E", "ABSENT", "A");

    @SuppressWarnings("unchecked")
    public static byte[] register(Map<String, Object> r) {
        List<Map<String, Object>> days = (List<Map<String, Object>>) r.get("days");
        List<Map<String, Object>> students = (List<Map<String, Object>>) r.get("students");
        Map<String, Object> totals = (Map<String, Object>) r.get("totals");
        return build(PageSize.A4.rotate(), String.valueOf(r.get("sectionName")) + " · " + r.get("monthLabel"), doc -> {
            int cols = 3 + days.size() + 5;
            PdfPTable t = new PdfPTable(cols);
            t.setWidthPercentage(100);
            headCell(t, "Roll"); headCell(t, "Adm"); headCell(t, "Name");
            for (Map<String, Object> d : days) headCell(t, String.valueOf(d.get("dayOfMonth")));
            for (String h : new String[] {"P", "L", "E", "A", "%"}) headCell(t, h);
            for (Map<String, Object> s : students) {
                bodyCell(t, s.get("rollNo")); bodyCell(t, s.get("admissionNo")); bodyCell(t, s.get("fullName"));
                for (Map<String, Object> cell : (List<Map<String, Object>>) s.get("cells")) {
                    String letter = LETTER.getOrDefault(String.valueOf(cell.get("status")), "");
                    letterCell(t, letter);
                }
                bodyCell(t, s.get("presentCount")); bodyCell(t, s.get("lateCount"));
                bodyCell(t, s.get("leaveCount")); bodyCell(t, s.get("absentCount")); bodyCell(t, s.get("presentPercent"));
            }
            // totals row
            bodyCell(t, ""); bodyCell(t, ""); bodyCell(t, "Total");
            for (int i = 0; i < days.size(); i++) bodyCell(t, "");
            bodyCell(t, totals.get("presentCount")); bodyCell(t, totals.get("lateCount"));
            bodyCell(t, totals.get("leaveCount")); bodyCell(t, totals.get("absentCount")); bodyCell(t, totals.get("presentPercent"));
            doc.add(t);
        });
    }

    @SuppressWarnings("unchecked")
    public static byte[] student(Map<String, Object> r) {
        Map<String, Object> st = (Map<String, Object>) r.get("student");
        List<Map<String, Object>> days = (List<Map<String, Object>>) r.get("days");
        return build(PageSize.A4, String.valueOf(st.get("fullName")) + " · " + st.get("sectionName"), doc -> {
            doc.add(new Paragraph(r.get("from") + " to " + r.get("to")
                    + "  ·  Present " + r.get("presentPercent") + "%"
                    + "  (P " + r.get("presentCount") + " / L " + r.get("lateCount")
                    + " / E " + r.get("leaveCount") + " / A " + r.get("absentCount") + ")", SUB));
            doc.add(new Paragraph(" ", SUB));
            PdfPTable t = new PdfPTable(new float[] {2, 2, 2, 6});
            t.setWidthPercentage(100);
            for (String h : new String[] {"Date", "Weekday", "Status", "Remarks"}) headCell(t, h);
            for (Map<String, Object> d : days) {
                bodyCell(t, d.get("date")); bodyCell(t, d.get("weekday")); bodyCell(t, d.get("status")); bodyCell(t, d.get("remarks"));
            }
            doc.add(t);
        });
    }

    @SuppressWarnings("unchecked")
    public static byte[] summary(Map<String, Object> r) {
        List<Map<String, Object>> sections = (List<Map<String, Object>>) r.get("sections");
        Map<String, Object> overall = (Map<String, Object>) r.get("overall");
        return build(PageSize.A4, "Attendance summary · " + r.get("from") + " to " + r.get("to"), doc -> {
            PdfPTable t = new PdfPTable(new float[] {5, 4, 2, 2, 2, 2, 3, 3});
            t.setWidthPercentage(100);
            for (String h : new String[] {"Section", "Teacher", "P", "L", "E", "A", "Present%", "Days"}) headCell(t, h);
            for (Map<String, Object> s : sections) {
                bodyCell(t, s.get("sectionName")); bodyCell(t, s.get("teacherName"));
                bodyCell(t, s.get("presentCount")); bodyCell(t, s.get("lateCount"));
                bodyCell(t, s.get("leaveCount")); bodyCell(t, s.get("absentCount"));
                bodyCell(t, s.get("presentPercent")); bodyCell(t, s.get("daysRecorded"));
            }
            bodyCell(t, "Overall"); bodyCell(t, "");
            bodyCell(t, overall.get("presentCount")); bodyCell(t, overall.get("lateCount"));
            bodyCell(t, overall.get("leaveCount")); bodyCell(t, overall.get("absentCount"));
            bodyCell(t, overall.get("presentPercent")); bodyCell(t, "");
            doc.add(t);
        });
    }

    private interface Body { void fill(Document doc) throws Exception; }

    private static byte[] build(com.lowagie.text.Rectangle size, String title, Body body) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document doc = new Document(size, 24, 24, 24, 24);
            PdfWriter.getInstance(doc, out);
            doc.open();
            doc.add(new Paragraph(title, TITLE));
            doc.add(new Paragraph(" ", SUB));
            body.fill(doc);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate PDF: " + e.getMessage(), e);
        }
    }

    private static void headCell(PdfPTable t, String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, HEAD));
        c.setBackgroundColor(HEAD_BG);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setPadding(2f);
        t.addCell(c);
    }

    private static void bodyCell(PdfPTable t, Object value) {
        PdfPCell c = new PdfPCell(new Phrase(value == null ? "" : String.valueOf(value), BODY));
        c.setPadding(2f);
        t.addCell(c);
    }

    private static void letterCell(PdfPTable t, String letter) {
        Color color = LETTER_COLOR.getOrDefault(letter, Color.BLACK);
        PdfPCell c = new PdfPCell(new Phrase(letter, new Font(Font.HELVETICA, 7, Font.BOLD, color)));
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setPadding(2f);
        t.addCell(c);
    }
}
```

- [ ] **Step 5: Wire PDF into `respondReport` and the three export endpoints**

In `AttendanceReadController.java`: add the import `import com.custoking.ims.schoolcoreservice.application.report.AttendanceReportPdf;`. Replace `respondReport` and update each export call to pass both formatters:

```java
    private ResponseEntity<byte[]> respondReport(String baseName, String format, Map<String, Object> report,
                                                 Function<Map<String, Object>, byte[]> csv,
                                                 Function<Map<String, Object>, byte[]> pdf) {
        String fmt = format == null ? "csv" : format.toLowerCase();
        byte[] body;
        String contentType;
        String ext;
        if ("csv".equals(fmt)) { body = csv.apply(report); contentType = "text/csv"; ext = "csv"; }
        else if ("pdf".equals(fmt)) { body = pdf.apply(report); contentType = MediaType.APPLICATION_PDF_VALUE; ext = "pdf"; }
        else throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported format: " + format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + baseName + "." + ext)
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .body(body);
    }
```

Update the three calls: `AttendanceReportCsv::register` → add `, AttendanceReportPdf::register`; likewise `student` and `summary`. E.g.:

```java
        return respondReport("register-" + month, format, report, AttendanceReportCsv::register, AttendanceReportPdf::register);
```
```java
        return respondReport("student-" + studentId + "-" + from + "_" + to, format, report,
                AttendanceReportCsv::student, AttendanceReportPdf::student);
```
```java
        return respondReport("summary-" + from + "_" + to, format, report, AttendanceReportCsv::summary, AttendanceReportPdf::summary);
```

- [ ] **Step 6: Run tests**

Run:
```bash
.\mvnw.cmd -f services\school-core-service\pom.xml test -Dtest=AttendanceReportPdfTest
.\mvnw.cmd -f services\school-core-service\pom.xml test -Dtest="Attendance*"
```
Expected: PDF test 3/3; full `Attendance*` sweep green (the controller compiles with the new PDF branch). If any earlier CSV test asserted `pdf → 400`, there is none in this plan (Task 3's test only exercised CSV), so no update needed.

- [ ] **Step 7: Commit**

```bash
git add services/school-core-service/pom.xml \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/application/report/AttendanceReportPdf.java \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/AttendanceReadController.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/application/report/AttendanceReportPdfTest.java
git commit -m "feat(attendance): OpenPDF table export (PDF) for the three reports"
```

---

### Task 5: Frontend foundation — report types, download helper, CSS, Mark|Reports host

**Files:**
- Modify: `frontend/src/types/attendance.ts`
- Create: `frontend/src/pages/workspace/panels/attendance/reports/download.ts`
- Modify: `frontend/src/styles/attendance.css` (append report classes)
- Create: `frontend/src/pages/workspace/panels/AttendanceModulePanel.tsx`
- Modify: `frontend/src/pages/UnifiedWorkspacePage.tsx:396`
- Test: `frontend/src/pages/workspace/panels/AttendanceModulePanel.test.tsx`

**Interfaces:**
- Produces: report TS types; `downloadReport(path, params, format, filename)`; `AttendanceModulePanel` (Mark|Reports host, same props as `AttendancePanel`); `ck-att-report-*` CSS.

- [ ] **Step 1: Add report types to `types/attendance.ts`**

Append:

```ts
export interface RegisterDay { date: string; dayOfMonth: number; weekday: string; nonWorkingDay: boolean; }
export interface RegisterCell { date: string; status: AttendanceStatus | null; }
export interface RegisterStudentRow {
  studentId: number; admissionNo: string; rollNo: string; fullName: string;
  cells: RegisterCell[];
  presentCount: number; lateCount: number; leaveCount: number; absentCount: number; presentPercent: number;
}
export interface RegisterDayTotal { date: string; presentCount: number; lateCount: number; leaveCount: number; absentCount: number; }
export interface AttendanceRegisterReport {
  month: string; monthLabel: string; classId: string; sectionId: string; sectionName: string; teacherName: string;
  days: RegisterDay[]; students: RegisterStudentRow[]; dayTotals: RegisterDayTotal[];
  totals: { presentCount: number; lateCount: number; leaveCount: number; absentCount: number; presentPercent: number };
}

export interface StudentHistoryDay { date: string; weekday: string; status: AttendanceStatus | null; remarks: string; nonWorkingDay: boolean; }
export interface AttendanceStudentHistory {
  student: { studentId: number; admissionNo: string; rollNo: string; fullName: string; sectionName: string };
  from: string; to: string; days: StudentHistoryDay[];
  presentCount: number; lateCount: number; leaveCount: number; absentCount: number; presentPercent: number; daysRecorded: number;
}

export interface SummarySection {
  classId: string; sectionId: string; sectionName: string; teacherName: string;
  presentCount: number; lateCount: number; leaveCount: number; absentCount: number; presentPercent: number; daysRecorded: number;
}
export interface AttendanceSummaryReport {
  from: string; to: string; sections: SummarySection[];
  overall: { presentCount: number; lateCount: number; leaveCount: number; absentCount: number; presentPercent: number };
}
```

- [ ] **Step 2: Create the download helper**

`frontend/src/pages/workspace/panels/attendance/reports/download.ts`:

```ts
import api from '../../../../../services/api';

/** Fetch a report as a blob and trigger a browser download. Mirrors the invoice/receipt PDF idiom. */
export async function downloadReport(
  path: string,
  params: Record<string, unknown>,
  format: 'csv' | 'pdf',
  filename: string,
): Promise<void> {
  const res = await api.get(path, { params: { ...params, format }, responseType: 'blob' });
  const type = format === 'pdf' ? 'application/pdf' : 'text/csv';
  const url = window.URL.createObjectURL(new Blob([res.data as BlobPart], { type }));
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  window.URL.revokeObjectURL(url);
}
```

- [ ] **Step 3: Append report CSS to `styles/attendance.css`**

```css
/* Attendance reports — Mark|Reports tabs, register grid, summary/history tables. */
.ck-att-tabs { display: flex; gap: 4px; margin-bottom: 16px; border-bottom: 1px solid var(--border); }
.ck-att-tab {
  padding: 8px 16px; font-size: 13px; font-weight: 600; border: none; background: none;
  color: var(--ink3); cursor: pointer; border-bottom: 2px solid transparent; margin-bottom: -1px;
}
.ck-att-tab--active { color: var(--b); border-bottom-color: var(--b); }

.ck-att-report-controls { display: flex; gap: 12px; flex-wrap: wrap; align-items: flex-end; margin-bottom: 16px; }
.ck-att-report-scroll { overflow-x: auto; border: 1px solid var(--border); border-radius: 8px; }
.ck-att-grid { border-collapse: collapse; font-size: 11px; font-variant-numeric: tabular-nums; }
.ck-att-grid th, .ck-att-grid td { border: 1px solid var(--border); padding: 4px 6px; text-align: center; white-space: nowrap; }
.ck-att-grid thead th { background: var(--b1); position: sticky; top: 0; }
.ck-att-grid .ck-att-grid-name { text-align: left; position: sticky; left: 0; background: var(--white); z-index: 1; }
.ck-att-grid .ck-att-grid-name-h { text-align: left; position: sticky; left: 0; background: var(--b1); z-index: 2; }
.ck-att-cell--present { color: var(--g); font-weight: 700; }
.ck-att-cell--late { color: var(--am); font-weight: 700; }
.ck-att-cell--leave { color: var(--b); font-weight: 700; }
.ck-att-cell--absent { color: var(--re); font-weight: 700; }
.ck-att-grid tfoot td, .ck-att-grid .ck-att-grid-tot { font-weight: 700; background: var(--bg); }

.ck-att-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.ck-att-table th, .ck-att-table td { border-bottom: 1px solid var(--border); padding: 8px 10px; text-align: left; }
.ck-att-table th { font-size: 11px; text-transform: uppercase; letter-spacing: 0.04em; color: var(--ink3); }
.ck-att-table td.num, .ck-att-table th.num { text-align: right; font-variant-numeric: tabular-nums; }
```

- [ ] **Step 4: Write the failing host test**

`frontend/src/pages/workspace/panels/AttendanceModulePanel.test.tsx`:

```tsx
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { afterEach, describe, it, expect, vi } from 'vitest';
import { AttendanceModulePanel } from './AttendanceModulePanel';

vi.mock('./AttendancePanel', () => ({ AttendancePanel: () => <div>MARK VIEW</div> }));
vi.mock('./AttendanceReportsPanel', () => ({ AttendanceReportsPanel: () => <div>REPORTS VIEW</div> }));

afterEach(cleanup);

describe('AttendanceModulePanel', () => {
  it('shows Mark by default and switches to Reports', () => {
    render(<AttendanceModulePanel onRefresh={vi.fn().mockResolvedValue(undefined)} />);
    expect(screen.getByText('MARK VIEW')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Reports' }));
    expect(screen.getByText('REPORTS VIEW')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Mark' }));
    expect(screen.getByText('MARK VIEW')).toBeTruthy();
  });
});
```

- [ ] **Step 5: Implement `AttendanceModulePanel.tsx`**

```tsx
import { useState } from 'react';
import { AttendancePanel } from './AttendancePanel';
import { AttendanceReportsPanel } from './AttendanceReportsPanel';

interface Props {
  onRefresh: () => Promise<void>;
  schoolScopedParams?: { schoolId: number };
}

export function AttendanceModulePanel({ onRefresh, schoolScopedParams }: Props) {
  const [tab, setTab] = useState<'mark' | 'reports'>('mark');
  return (
    <div>
      <div className="ck-att-tabs">
        <button type="button" className={`ck-att-tab${tab === 'mark' ? ' ck-att-tab--active' : ''}`} onClick={() => setTab('mark')}>
          Mark
        </button>
        <button type="button" className={`ck-att-tab${tab === 'reports' ? ' ck-att-tab--active' : ''}`} onClick={() => setTab('reports')}>
          Reports
        </button>
      </div>
      {tab === 'mark'
        ? <AttendancePanel onRefresh={onRefresh} schoolScopedParams={schoolScopedParams} />
        : <AttendanceReportsPanel schoolScopedParams={schoolScopedParams} />}
    </div>
  );
}
```

> `AttendanceReportsPanel` is created in Task 7. To keep this task's build green, add a **temporary stub** now and replace it in Task 7:
> `frontend/src/pages/workspace/panels/AttendanceReportsPanel.tsx`:
> ```tsx
> export function AttendanceReportsPanel(_props: { schoolScopedParams?: { schoolId: number } }) {
>   return <div className="ck-att-report-controls">Reports coming in Task 7.</div>;
> }
> ```

- [ ] **Step 6: Wire into the workspace page**

In `frontend/src/pages/UnifiedWorkspacePage.tsx`: change the import on line 19 from `AttendancePanel` to also import the host, and swap the render at line 396.

Replace `import { AttendancePanel } from './workspace/panels/AttendancePanel';` with:
```tsx
import { AttendanceModulePanel } from './workspace/panels/AttendanceModulePanel';
```
Replace line 396:
```tsx
          {panel === 'attendance' && <AttendanceModulePanel onRefresh={refresh} schoolScopedParams={schoolScopedParams} />}
```
(If `AttendancePanel` is not referenced elsewhere in the file, removing its import avoids an unused-import error — verify.)

- [ ] **Step 7: Run the test + build**

Run:
```bash
cd frontend
npm test -- AttendanceModulePanel
npm run build
```
Expected: host test passes; build succeeds (with the temporary `AttendanceReportsPanel` stub).

- [ ] **Step 8: Commit**

```bash
git add frontend/src/types/attendance.ts \
        frontend/src/pages/workspace/panels/attendance/reports/download.ts \
        frontend/src/styles/attendance.css \
        frontend/src/pages/workspace/panels/AttendanceModulePanel.tsx \
        frontend/src/pages/workspace/panels/AttendanceReportsPanel.tsx \
        frontend/src/pages/workspace/panels/AttendanceModulePanel.test.tsx \
        frontend/src/pages/UnifiedWorkspacePage.tsx
git commit -m "feat(attendance-ui): report types, download helper, CSS, Mark|Reports host"
```

---

### Task 6: Report view components (RegisterGrid, StudentHistory, SectionSummary)

**Files:**
- Create: `frontend/src/pages/workspace/panels/attendance/reports/RegisterGrid.tsx` (+ `.test.tsx`)
- Create: `frontend/src/pages/workspace/panels/attendance/reports/StudentHistory.tsx`
- Create: `frontend/src/pages/workspace/panels/attendance/reports/SectionSummary.tsx`

**Interfaces:**
- Consumes: report types from `../../../../../types/attendance`.
- Produces: `RegisterGrid` (props `{ report: AttendanceRegisterReport | null; loading: boolean }`), `StudentHistory` (props `{ report: AttendanceStudentHistory | null; loading: boolean }`), `SectionSummary` (props `{ report: AttendanceSummaryReport | null; loading: boolean }`).

- [ ] **Step 1: Implement `RegisterGrid.tsx`**

```tsx
import type { AttendanceRegisterReport, AttendanceStatus } from '../../../../../types/attendance';

const LETTER: Record<AttendanceStatus, { text: string; cls: string }> = {
  PRESENT: { text: 'P', cls: 'ck-att-cell--present' },
  LATE: { text: 'L', cls: 'ck-att-cell--late' },
  LEAVE: { text: 'E', cls: 'ck-att-cell--leave' },
  ABSENT: { text: 'A', cls: 'ck-att-cell--absent' },
};

export function RegisterGrid({ report, loading }: { report: AttendanceRegisterReport | null; loading: boolean }) {
  if (loading) return <div style={{ padding: 24, color: 'var(--ink3)' }}>Loading register…</div>;
  if (!report || report.students.length === 0) {
    return <div className="ck-alert ck-alert-am"><span>i</span><div>No attendance recorded for this section and month.</div></div>;
  }
  return (
    <div className="ck-att-report-scroll">
      <table className="ck-att-grid">
        <thead>
          <tr>
            <th className="ck-att-grid-name-h">Student</th>
            {report.days.map((d) => <th key={d.date} title={d.weekday}>{d.dayOfMonth}</th>)}
            <th>P</th><th>L</th><th>E</th><th>A</th><th>%</th>
          </tr>
        </thead>
        <tbody>
          {report.students.map((s) => (
            <tr key={s.studentId}>
              <td className="ck-att-grid-name">{s.rollNo ? `${s.rollNo}. ` : ''}{s.fullName}</td>
              {s.cells.map((c) => {
                const l = c.status ? LETTER[c.status] : null;
                return <td key={c.date} className={l ? l.cls : ''}>{l ? l.text : ''}</td>;
              })}
              <td>{s.presentCount}</td><td>{s.lateCount}</td><td>{s.leaveCount}</td><td>{s.absentCount}</td>
              <td>{s.presentPercent}%</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <td className="ck-att-grid-name ck-att-grid-tot">Daily present</td>
            {report.dayTotals.map((dt) => <td key={dt.date}>{dt.presentCount + dt.lateCount}</td>)}
            <td>{report.totals.presentCount}</td><td>{report.totals.lateCount}</td>
            <td>{report.totals.leaveCount}</td><td>{report.totals.absentCount}</td>
            <td>{report.totals.presentPercent}%</td>
          </tr>
        </tfoot>
      </table>
    </div>
  );
}
```

- [ ] **Step 2: Implement `StudentHistory.tsx`**

```tsx
import type { AttendanceStudentHistory } from '../../../../../types/attendance';

export function StudentHistory({ report, loading }: { report: AttendanceStudentHistory | null; loading: boolean }) {
  if (loading) return <div style={{ padding: 24, color: 'var(--ink3)' }}>Loading history…</div>;
  if (!report) return <div className="ck-alert ck-alert-am"><span>i</span><div>Pick a student to see their attendance.</div></div>;
  if (report.days.length === 0) {
    return <div className="ck-alert ck-alert-am"><span>i</span><div>No attendance recorded in this range.</div></div>;
  }
  return (
    <div>
      <div className="ck-att-summary" style={{ marginBottom: 16 }}>
        {[
          { label: 'Present%', value: `${report.presentPercent}%` },
          { label: 'Present', value: report.presentCount },
          { label: 'Late', value: report.lateCount },
          { label: 'Leave', value: report.leaveCount },
          { label: 'Absent', value: report.absentCount },
        ].map((c) => (
          <div key={c.label} className="ck-att-summary-cell">
            <div className="ck-att-summary-label">{c.label}</div>
            <div className="ck-att-summary-value">{c.value}</div>
          </div>
        ))}
      </div>
      <div className="ck-att-report-scroll">
        <table className="ck-att-table">
          <thead><tr><th>Date</th><th>Day</th><th>Status</th><th>Remarks</th></tr></thead>
          <tbody>
            {report.days.map((d) => (
              <tr key={d.date}><td>{d.date}</td><td>{d.weekday}</td><td>{d.status ?? ''}</td><td>{d.remarks}</td></tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
```

- [ ] **Step 3: Implement `SectionSummary.tsx`**

```tsx
import type { AttendanceSummaryReport } from '../../../../../types/attendance';

export function SectionSummary({ report, loading }: { report: AttendanceSummaryReport | null; loading: boolean }) {
  if (loading) return <div style={{ padding: 24, color: 'var(--ink3)' }}>Loading summary…</div>;
  if (!report || report.sections.length === 0) {
    return <div className="ck-alert ck-alert-am"><span>i</span><div>No attendance recorded in this range.</div></div>;
  }
  return (
    <div className="ck-att-report-scroll">
      <table className="ck-att-table">
        <thead>
          <tr>
            <th>Section</th><th>Teacher</th>
            <th className="num">P</th><th className="num">L</th><th className="num">E</th><th className="num">A</th>
            <th className="num">Present%</th><th className="num">Days</th>
          </tr>
        </thead>
        <tbody>
          {report.sections.map((s) => (
            <tr key={s.sectionId}>
              <td>{s.sectionName}</td><td>{s.teacherName}</td>
              <td className="num">{s.presentCount}</td><td className="num">{s.lateCount}</td>
              <td className="num">{s.leaveCount}</td><td className="num">{s.absentCount}</td>
              <td className="num">{s.presentPercent}%</td><td className="num">{s.daysRecorded}</td>
            </tr>
          ))}
          <tr>
            <td><strong>Overall</strong></td><td />
            <td className="num"><strong>{report.overall.presentCount}</strong></td>
            <td className="num"><strong>{report.overall.lateCount}</strong></td>
            <td className="num"><strong>{report.overall.leaveCount}</strong></td>
            <td className="num"><strong>{report.overall.absentCount}</strong></td>
            <td className="num"><strong>{report.overall.presentPercent}%</strong></td><td />
          </tr>
        </tbody>
      </table>
    </div>
  );
}
```

- [ ] **Step 4: Write the failing test for `RegisterGrid`**

`frontend/src/pages/workspace/panels/attendance/reports/RegisterGrid.test.tsx`:

```tsx
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, it, expect } from 'vitest';
import { RegisterGrid } from './RegisterGrid';
import type { AttendanceRegisterReport } from '../../../../../types/attendance';

afterEach(cleanup);

const report: AttendanceRegisterReport = {
  month: '2024-07', monthLabel: 'July 2024', classId: 'c1', sectionId: 's1',
  sectionName: 'Class 1-A', teacherName: 'Ms Rao',
  days: [
    { date: '2024-07-01', dayOfMonth: 1, weekday: 'Mon', nonWorkingDay: false },
    { date: '2024-07-02', dayOfMonth: 2, weekday: 'Tue', nonWorkingDay: false },
  ],
  students: [{
    studentId: 1, admissionNo: 'ADM1', rollNo: '1', fullName: 'Asha',
    cells: [{ date: '2024-07-01', status: 'PRESENT' }, { date: '2024-07-02', status: 'LATE' }],
    presentCount: 1, lateCount: 1, leaveCount: 0, absentCount: 0, presentPercent: 100,
  }],
  dayTotals: [
    { date: '2024-07-01', presentCount: 1, lateCount: 0, leaveCount: 0, absentCount: 0 },
    { date: '2024-07-02', presentCount: 0, lateCount: 1, leaveCount: 0, absentCount: 0 },
  ],
  totals: { presentCount: 1, lateCount: 1, leaveCount: 0, absentCount: 0, presentPercent: 100 },
};

describe('RegisterGrid', () => {
  it('renders color-coded status letters and totals inside a scroll container', () => {
    const { container } = render(<RegisterGrid report={report} loading={false} />);
    expect(container.querySelector('.ck-att-report-scroll')).toBeTruthy();
    expect(screen.getByText('Asha')).toBeTruthy();
    const present = container.querySelector('.ck-att-cell--present');
    const late = container.querySelector('.ck-att-cell--late');
    expect(present?.textContent).toBe('P');
    expect(late?.textContent).toBe('L');
  });

  it('shows an empty state when there are no students', () => {
    render(<RegisterGrid report={{ ...report, students: [] }} loading={false} />);
    expect(screen.getByText(/No attendance recorded/)).toBeTruthy();
  });
});
```

- [ ] **Step 5: Run + verify**

Run:
```bash
cd frontend
npm test -- RegisterGrid
```
Expected: PASS (2 tests). (StudentHistory/SectionSummary are exercised via the container test in Task 7.)

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/workspace/panels/attendance/reports/RegisterGrid.tsx \
        frontend/src/pages/workspace/panels/attendance/reports/RegisterGrid.test.tsx \
        frontend/src/pages/workspace/panels/attendance/reports/StudentHistory.tsx \
        frontend/src/pages/workspace/panels/attendance/reports/SectionSummary.tsx
git commit -m "feat(attendance-ui): RegisterGrid, StudentHistory, SectionSummary report views"
```

---

### Task 7: `AttendanceReportsPanel` container

**Files:**
- Replace: `frontend/src/pages/workspace/panels/AttendanceReportsPanel.tsx` (was the Task 5 stub)
- Test: `frontend/src/pages/workspace/panels/AttendanceReportsPanel.test.tsx`

**Interfaces:**
- Consumes: `RegisterGrid`/`StudentHistory`/`SectionSummary`, `downloadReport`, report types, `ModuleShell`/`Field` from `../ui`, `todayIso` from `../utils`, `api`.
- Produces: `AttendanceReportsPanel` (props `{ schoolScopedParams?: { schoolId: number } }`).

- [ ] **Step 1: Replace the stub with the container**

```tsx
import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field } from './ui';
import { todayIso } from './utils';
import { RegisterGrid } from './attendance/reports/RegisterGrid';
import { StudentHistory } from './attendance/reports/StudentHistory';
import { SectionSummary } from './attendance/reports/SectionSummary';
import { downloadReport } from './attendance/reports/download';
import type {
  AttendanceRegisterReport, AttendanceStudentHistory, AttendanceSummaryReport,
} from '../../../types/attendance';

interface Props { schoolScopedParams?: { schoolId: number }; }
type Tab = 'register' | 'student' | 'summary';
interface ClassOpt { id: string; name: string }
interface SectionOpt { id: string; name: string }
interface StudentOpt { id: number; fullName: string; admissionNo: string }

function monthIso(): string { return todayIso().slice(0, 7); }
function monthStart(m: string): string { return `${m}-01`; }
function errMessage(err: unknown, fallback: string): string {
  if (err instanceof Error && err.message) return err.message;
  return (err as { response?: { data?: { message?: string } } })?.response?.data?.message || fallback;
}

export function AttendanceReportsPanel({ schoolScopedParams }: Props) {
  const scoped = schoolScopedParams || {};
  const [tab, setTab] = useState<Tab>('register');
  const [classes, setClasses] = useState<ClassOpt[]>([]);
  const [sections, setSections] = useState<SectionOpt[]>([]);
  const [students, setStudents] = useState<StudentOpt[]>([]);
  const [classId, setClassId] = useState('');
  const [sectionId, setSectionId] = useState('');
  const [studentId, setStudentId] = useState('');
  const [month, setMonth] = useState(monthIso());
  const [from, setFrom] = useState(monthStart(monthIso()));
  const [to, setTo] = useState(todayIso());

  const [register, setRegister] = useState<AttendanceRegisterReport | null>(null);
  const [history, setHistory] = useState<AttendanceStudentHistory | null>(null);
  const [summary, setSummary] = useState<AttendanceSummaryReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [exporting, setExporting] = useState(false);

  useEffect(() => {
    void api.get<ClassOpt[]>('/classes', { params: scoped })
      .then((r) => setClasses(Array.isArray(r.data) ? r.data : []))
      .catch(() => setClasses([]));
  }, []);

  useEffect(() => {
    if (!classId) { setSections([]); return; }
    void api.get<SectionOpt[]>(`/classes/${encodeURIComponent(classId)}/sections`, { params: scoped })
      .then((r) => setSections(Array.isArray(r.data) ? r.data : []))
      .catch(() => setSections([]));
  }, [classId]);

  useEffect(() => {
    if (!classId || !sectionId) { setStudents([]); return; }
    void api.get<StudentOpt[]>(`/classes/${encodeURIComponent(classId)}/sections/${encodeURIComponent(sectionId)}/students`, { params: scoped })
      .then((r) => setStudents(Array.isArray(r.data) ? r.data : []))
      .catch(() => setStudents([]));
  }, [classId, sectionId]);

  const load = async () => {
    setError('');
    setLoading(true);
    try {
      if (tab === 'register') {
        if (!classId || !sectionId) { setRegister(null); return; }
        const r = await api.get<AttendanceRegisterReport>('/attendance/report/register', { params: { month, classId, sectionId, ...scoped } });
        setRegister(r.data);
      } else if (tab === 'student') {
        if (!studentId) { setHistory(null); return; }
        const r = await api.get<AttendanceStudentHistory>('/attendance/report/student', { params: { studentId, from, to, ...scoped } });
        setHistory(r.data);
      } else {
        const r = await api.get<AttendanceSummaryReport>('/attendance/report/summary', { params: { from, to, ...scoped } });
        setSummary(r.data);
      }
    } catch (err) {
      setError(errMessage(err, 'Failed to load report'));
    } finally {
      setLoading(false);
    }
  };

  const doExport = async (format: 'csv' | 'pdf') => {
    setError('');
    setExporting(true);
    try {
      if (tab === 'register') {
        await downloadReport('/attendance/report/register/export', { month, classId, sectionId, ...scoped }, format, `register-${month}.${format}`);
      } else if (tab === 'student') {
        await downloadReport('/attendance/report/student/export', { studentId, from, to, ...scoped }, format, `student-${studentId}-${from}_${to}.${format}`);
      } else {
        await downloadReport('/attendance/report/summary/export', { from, to, ...scoped }, format, `summary-${from}_${to}.${format}`);
      }
    } catch (err) {
      setError(errMessage(err, 'Export failed'));
    } finally {
      setExporting(false);
    }
  };

  const canExport = tab === 'register' ? !!register : tab === 'student' ? !!history : !!summary;

  return (
    <ModuleShell
      title="Attendance reports"
      subtitle="Register, per-student history, and section summary"
      actions={
        <span style={{ display: 'flex', gap: 8 }}>
          <button className="ck-btn ck-btn-ghost" disabled={!canExport || exporting} onClick={() => doExport('csv')}>Export CSV</button>
          <button className="ck-btn ck-btn-ghost" disabled={!canExport || exporting} onClick={() => doExport('pdf')}>Export PDF</button>
        </span>
      }
    >
      <div className="ck-att-tabs">
        {(['register', 'student', 'summary'] as Tab[]).map((t) => (
          <button key={t} type="button" className={`ck-att-tab${tab === t ? ' ck-att-tab--active' : ''}`}
            onClick={() => { setTab(t); setError(''); }}>
            {t === 'register' ? 'Register' : t === 'student' ? 'Student history' : 'Summary'}
          </button>
        ))}
      </div>

      {error && <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>!</span><div>{error}</div></div>}

      <div className="ck-att-report-controls">
        {(tab === 'register' || tab === 'student') && (
          <>
            <Field label="Class">
              <select value={classId} onChange={(e) => { setClassId(e.target.value); setSectionId(''); setStudentId(''); }}>
                <option value="">Select class</option>
                {classes.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </Field>
            <Field label="Section">
              <select value={sectionId} onChange={(e) => { setSectionId(e.target.value); setStudentId(''); }} disabled={!classId}>
                <option value="">Select section</option>
                {sections.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </Field>
          </>
        )}
        {tab === 'student' && (
          <Field label="Student">
            <select value={studentId} onChange={(e) => setStudentId(e.target.value)} disabled={!sectionId}>
              <option value="">Select student</option>
              {students.map((s) => <option key={s.id} value={s.id}>{s.fullName} ({s.admissionNo})</option>)}
            </select>
          </Field>
        )}
        {tab === 'register' && (
          <Field label="Month"><input type="month" value={month} onChange={(e) => setMonth(e.target.value)} /></Field>
        )}
        {(tab === 'student' || tab === 'summary') && (
          <>
            <Field label="From"><input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
            <Field label="To"><input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
          </>
        )}
        <button className="ck-btn ck-btn-b" onClick={load} disabled={loading}>{loading ? 'Loading…' : 'Run report'}</button>
      </div>

      {tab === 'register' && <RegisterGrid report={register} loading={loading} />}
      {tab === 'student' && <StudentHistory report={history} loading={loading} />}
      {tab === 'summary' && <SectionSummary report={summary} loading={loading} />}
    </ModuleShell>
  );
}
```

- [ ] **Step 2: Write the failing test**

`frontend/src/pages/workspace/panels/AttendanceReportsPanel.test.tsx`:

```tsx
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { AttendanceReportsPanel } from './AttendanceReportsPanel';
import api from '../../../services/api';

vi.mock('../../../services/api');

const summary = {
  from: '2024-07-01', to: '2024-07-31',
  sections: [{ classId: 'c1', sectionId: 's1', sectionName: 'Class 1-A', teacherName: 'Ms Rao',
    presentCount: 5, lateCount: 1, leaveCount: 0, absentCount: 1, presentPercent: 85.7, daysRecorded: 2 }],
  overall: { presentCount: 5, lateCount: 1, leaveCount: 0, absentCount: 1, presentPercent: 85.7 },
};

afterEach(cleanup);

describe('AttendanceReportsPanel', () => {
  beforeEach(() => {
    vi.mocked(api.get).mockReset();
    vi.mocked(api.get).mockImplementation((url: string) => {
      if (url === '/classes') return Promise.resolve({ data: [{ id: 'c1', name: 'Class 1' }] });
      if (url === '/attendance/report/summary') return Promise.resolve({ data: summary });
      return Promise.resolve({ data: [] });
    });
  });

  it('runs the summary report and renders ranked sections', async () => {
    render(<AttendanceReportsPanel />);
    fireEvent.click(screen.getByRole('button', { name: 'Summary' }));
    fireEvent.click(screen.getByRole('button', { name: 'Run report' }));
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/attendance/report/summary', expect.objectContaining({ params: expect.objectContaining({ from: '2024-07-01', to: '2024-07-31' }) })));
    await waitFor(() => expect(screen.getByText('Class 1-A')).toBeTruthy());
    expect(screen.getByText('85.7%')).toBeTruthy();
  });

  it('calls the export endpoint with format=csv', async () => {
    render(<AttendanceReportsPanel />);
    fireEvent.click(screen.getByRole('button', { name: 'Summary' }));
    fireEvent.click(screen.getByRole('button', { name: 'Run report' }));
    await waitFor(() => screen.getByText('Class 1-A'));
    fireEvent.click(screen.getByRole('button', { name: 'Export CSV' }));
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/attendance/report/summary/export',
      expect.objectContaining({ params: expect.objectContaining({ format: 'csv' }), responseType: 'blob' })));
  });
});
```

> Note: the export test asserts `downloadReport` reaches `api.get` with `responseType: 'blob'` and `format: 'csv'`. `createObjectURL`/anchor click run under jsdom; if `window.URL.createObjectURL` is undefined in the test env, add `window.URL.createObjectURL = vi.fn()` and `window.URL.revokeObjectURL = vi.fn()` in a `beforeEach` (mirror whatever `BulkImportPanel.test.tsx` / existing tests already do). Verify against the existing jsdom setup before adding.

- [ ] **Step 3: Run the test + full sweep**

Run:
```bash
cd frontend
npm test -- AttendanceReportsPanel
npm run build
npm test
```
Expected: container tests pass; build succeeds; whole Vitest suite green.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/workspace/panels/AttendanceReportsPanel.tsx \
        frontend/src/pages/workspace/panels/AttendanceReportsPanel.test.tsx
git commit -m "feat(attendance-ui): AttendanceReportsPanel container (pickers, tabs, export)"
```

---

## Self-Review Notes

- **Spec coverage:** register query+grid (T1, T6), student history (T2, T6), summary (T2, T6), CSV (T3), PDF/OpenPDF (T4), types+download+host tabs+CSS (T5), container with pickers+export (T7). All three reports + both export formats + the Mark|Reports host are covered.
- **Percent consistency:** every report percent routes through `attendancePercent(present, late, absent)` (Leave excluded) — reused from sub-project 1, no second formula. Summary `overall` and register `totals` use the same rule over summed buckets.
- **Additivity / no ripple:** CSV/PDF formatters are static (no controller-constructor change → existing `AttendanceReadController` tests untouched). No schema change. Types are appended. `simplePdf` (fees) is not touched.
- **Scoping:** every endpoint does `requireToken("attendance:read")` + `TenantScope.resolveSchoolId`; register/student re-check the section/student's real `school_id` → 403 (tested). Summary filters `ad.school_id`.
- **Interim state:** Task 3 rejects `format=pdf` with 400; Task 4 adds the PDF branch (its `respondReport` gains the `pdf` param and the three calls pass the PDF formatter). No test asserts the interim 400, so nothing to update.
- **Type consistency:** `AttendanceRegisterReport`/`AttendanceStudentHistory`/`AttendanceSummaryReport` field names match exactly what the backend Maps put (`presentPercent`, `lateCount`, `leaveCount`, `daysRecorded`, `cells[].status`, `days[].dayOfMonth`, etc.).
- **Known dependency:** OpenPDF `1.3.35` added to school-core `pom.xml` (Task 4). Uses `com.lowagie.text.*` (OpenPDF's package) and `java.awt.Color` — headless AWT is fine on Cloud Run (no display needed for PDF byte generation).
- **Out of scope (unchanged):** editing past marks, notifications (sub-project 3), charts, scheduled delivery.
```