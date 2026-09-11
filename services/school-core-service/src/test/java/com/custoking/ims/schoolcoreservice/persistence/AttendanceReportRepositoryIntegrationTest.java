package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

/**
 * Characterisation of the three attendance report readers against a real schema:
 * {@code registerReport} (student x day pivot), {@code studentHistory} (one student over a
 * range) and {@code sectionSummary} (per-section rollup). Marks are written through
 * {@code saveSectionRegister} — the same path the UI uses — so the reports are read back
 * exactly as production would see them.
 *
 * <p>Runs as the table owner, which is RLS-exempt. Nothing here claims to prove tenant
 * isolation; the school-scope assertions exercise the application-level checks only.</p>
 */
class AttendanceReportRepositoryIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource ds;
    static JdbcClient jdbc;
    static AttendanceReadRepository repo;

    /** April 2026: 30 days, starts on a Wednesday, first Sunday is the 5th. */
    static final String MONTH = "2026-04";
    static final LocalDate FIRST = LocalDate.parse("2026-04-01");
    static final LocalDate LAST = LocalDate.parse("2026-04-30");
    static final LocalDate DAY_BEFORE = LocalDate.parse("2026-03-31");
    static final LocalDate DAY_AFTER = LocalDate.parse("2026-05-01");

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
        jdbc = JdbcClient.create(ds);
        StudentPhotoStorage photo = Mockito.mock(StudentPhotoStorage.class);
        Mockito.when(photo.toDisplayUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        OutboxWriter outbox = new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school");
        repo = new AttendanceReadRepository(jdbc, photo, outbox, "attendance");
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    /**
     * School 1: class c1 with sections s1 (students 1-4, Ms Rao) and s1b (student 5, Mr Iyer),
     * class c2 with section s2 (student 6). School 2: class c1 section o1 (student 7).
     */
    @BeforeEach
    void seed() throws Exception {
        AcademicCalendar.AcademicYear academicYear =
                AcademicCalendar.currentAcademicYear(AcademicCalendar.DEFAULT_ACADEMIC_YEAR_START_MONTH);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM attendance.absentee_notifications");
            st.execute("DELETE FROM attendance.attendance_student_records");
            st.execute("DELETE FROM attendance.attendance_daily");
            st.execute("DELETE FROM student.students");
            st.execute("DELETE FROM tenant_school.outbox_events");
            st.execute("DELETE FROM tenant_school.school_sections");
            st.execute("DELETE FROM tenant_school.school_classes");
            st.execute("DELETE FROM tenant_school.schools");
            st.execute("DELETE FROM tenant_school.academic_years");

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO tenant_school.academic_years(id, label, active) VALUES (?, ?, true)")) {
                ps.setString(1, academicYear.id());
                ps.setString(2, academicYear.label());
                ps.executeUpdate();
            }
            st.execute("INSERT INTO tenant_school.schools(id, name, short_code, active, created_at) VALUES " +
                    "(1,'Test School','TST',true, now()), (2,'Other School','OTH',true, now())");
            st.execute("INSERT INTO tenant_school.school_classes(id, name, sort_order) VALUES " +
                    "('c1','Class 1',1), ('c2','Class 2',2)");
            st.execute("INSERT INTO tenant_school.school_sections(id, name, teacher_name, active, school_class_id, school_id) VALUES " +
                    "('s1','A','Ms Rao',true,'c1',1), " +
                    "('s1b','B','Mr Iyer',true,'c1',1), " +
                    "('s2','A','Ms Das',true,'c2',1), " +
                    "('o1','A','Mr Other',true,'c1',2)");
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO student.students
                        (id, admission_no, roll_no, full_name, school_id, class_id, section_id, academic_year_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                Object[][] rows = {
                        {1L, "ADM1", "1", "Asha", 1L, "c1", "s1"},
                        {2L, "ADM2", "2", "Bala", 1L, "c1", "s1"},
                        {3L, "ADM3", "3", "Chitra", 1L, "c1", "s1"},
                        {4L, "ADM4", "10", "Dev", 1L, "c1", "s1"},      // roll 10 sorts numerically after 3
                        {5L, "ADM5", "1", "Esha", 1L, "c1", "s1b"},
                        {6L, "ADM6", "1", "Farid", 1L, "c2", "s2"},
                        {7L, "ADM7", "1", "Gita", 2L, "c1", "o1"},
                };
                for (Object[] r : rows) {
                    ps.setLong(1, (Long) r[0]);
                    ps.setString(2, (String) r[1]);
                    ps.setString(3, (String) r[2]);
                    ps.setString(4, (String) r[3]);
                    ps.setLong(5, (Long) r[4]);
                    ps.setString(6, (String) r[5]);
                    ps.setString(7, (String) r[6]);
                    ps.setString(8, academicYear.id());
                    ps.executeUpdate();
                }
            }
        }
    }

    // ── registerReport ──────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void registerReport_pivotsStudentsByDayAcrossTheWholeMonth_andIgnoresMarksOutsideIt() {
        // Marks on the month's edges — plus the day before and the day after, which must be invisible.
        mark(DAY_BEFORE, "c1", "s1", 1, "ABSENT", 2, "ABSENT", 3, "ABSENT", 4, "ABSENT");
        mark(FIRST, "c1", "s1", 1, "PRESENT", 2, "LATE", 3, "LEAVE");           // Dev (4) unmarked
        mark(LAST, "c1", "s1", 1, "ABSENT", 2, "PRESENT", 3, "ABSENT");
        mark(DAY_AFTER, "c1", "s1", 1, "ABSENT", 2, "ABSENT", 3, "ABSENT", 4, "ABSENT");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM attendance.attendance_student_records").query(Long.class).single())
                .as("sanity: the out-of-month marks really are on disk").isEqualTo(14L);

        Map<String, Object> report = repo.registerReport(MONTH, "c1", "s1", 1L);

        assertThat(report).containsEntry("month", MONTH)
                .containsEntry("monthLabel", "April 2026")
                .containsEntry("sectionName", "Class 1-A")
                .containsEntry("teacherName", "Ms Rao");

        List<Map<String, Object>> days = (List<Map<String, Object>>) report.get("days");
        assertThat(days).hasSize(30);
        assertThat(days.getFirst()).containsEntry("date", "2026-04-01").containsEntry("dayOfMonth", 1)
                .containsEntry("weekday", "Wed").containsEntry("nonWorkingDay", false);
        assertThat(days.getLast()).containsEntry("date", "2026-04-30").containsEntry("dayOfMonth", 30);
        assertThat(days.get(4)).containsEntry("date", "2026-04-05").containsEntry("weekday", "Sun")
                .containsEntry("nonWorkingDay", true);
        assertThat(days).extracting(d -> d.get("date")).doesNotContain("2026-03-31", "2026-05-01");

        List<Map<String, Object>> students = (List<Map<String, Object>>) report.get("students");
        assertThat(students).extracting(s -> s.get("fullName")).containsExactly("Asha", "Bala", "Chitra", "Dev");

        Map<String, Object> asha = students.get(0);
        List<Map<String, Object>> ashaCells = (List<Map<String, Object>>) asha.get("cells");
        assertThat(ashaCells).hasSize(30);
        assertThat(ashaCells.getFirst()).containsEntry("date", "2026-04-01").containsEntry("status", "PRESENT");
        assertThat(ashaCells.getLast()).containsEntry("date", "2026-04-30").containsEntry("status", "ABSENT");
        for (int i = 1; i < 29; i++) {
            assertThat(ashaCells.get(i).get("status")).as("Asha day %d", i + 1).isNull();
        }
        assertThat(asha).containsEntry("presentCount", 1).containsEntry("lateCount", 0)
                .containsEntry("leaveCount", 0).containsEntry("absentCount", 1)
                .containsEntry("presentPercent", 50.0);

        // Bala: LATE + PRESENT -> 100 (late is attended). Chitra: LEAVE + ABSENT -> 0/1 = 0.0.
        assertThat(students.get(1)).containsEntry("lateCount", 1).containsEntry("presentCount", 1)
                .containsEntry("presentPercent", 100.0);
        assertThat(students.get(2)).containsEntry("leaveCount", 1).containsEntry("absentCount", 1)
                .containsEntry("presentPercent", 0.0);

        // Dev has no marks at all: still a full row of 30 empty cells, all-zero counts, 0.0 percent.
        Map<String, Object> dev = students.get(3);
        List<Map<String, Object>> devCells = (List<Map<String, Object>>) dev.get("cells");
        assertThat(devCells).hasSize(30);
        assertThat(devCells).allSatisfy(cell -> assertThat(cell.get("status")).isNull());
        assertThat(dev).containsEntry("presentCount", 0).containsEntry("lateCount", 0)
                .containsEntry("leaveCount", 0).containsEntry("absentCount", 0)
                .containsEntry("presentPercent", 0.0);

        List<Map<String, Object>> dayTotals = (List<Map<String, Object>>) report.get("dayTotals");
        assertThat(dayTotals).hasSize(30);
        assertThat(dayTotals.getFirst()).containsEntry("date", "2026-04-01")
                .containsEntry("presentCount", 1).containsEntry("lateCount", 1)
                .containsEntry("leaveCount", 1).containsEntry("absentCount", 0);
        assertThat(dayTotals.get(1)).containsEntry("presentCount", 0).containsEntry("lateCount", 0)
                .containsEntry("leaveCount", 0).containsEntry("absentCount", 0);
        assertThat(dayTotals.getLast()).containsEntry("date", "2026-04-30")
                .containsEntry("presentCount", 1).containsEntry("lateCount", 0)
                .containsEntry("leaveCount", 0).containsEntry("absentCount", 2);

        // Totals: P=2 (Asha 1st, Bala 30th) L=1 (Bala) E=1 (Chitra) A=2 (Asha 30th, Chitra 30th)
        // -> (2+1)/(2+1+2) = 60.0. The eight out-of-month absences do not count.
        Map<String, Object> totals = (Map<String, Object>) report.get("totals");
        assertThat(totals).containsEntry("presentCount", 2).containsEntry("lateCount", 1)
                .containsEntry("leaveCount", 1).containsEntry("absentCount", 2)
                .containsEntry("presentPercent", 60.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void registerReport_onASectionWithNoMarksAtAll_isAnEmptyGridNotAnError() {
        Map<String, Object> report = repo.registerReport(MONTH, "c1", "s1b", 1L);
        List<Map<String, Object>> students = (List<Map<String, Object>>) report.get("students");
        assertThat(students).hasSize(1);
        assertThat((List<Map<String, Object>>) students.getFirst().get("cells")).hasSize(30);
        assertThat((Map<String, Object>) report.get("totals"))
                .containsEntry("presentCount", 0).containsEntry("absentCount", 0)
                .containsEntry("presentPercent", 0.0);
    }

    @Test
    void registerReport_rejectsSectionOutsideClass_crossSchool_unknownSection_andBadMonth() {
        assertThatThrownBy(() -> repo.registerReport(MONTH, "c2", "s1", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Section does not belong to class");
        assertThatThrownBy(() -> repo.registerReport(MONTH, "c1", "s1", 2L))
                .isInstanceOf(SecurityException.class)
                .hasMessage("You do not have access to this section");
        assertThatThrownBy(() -> repo.registerReport(MONTH, "c1", "missing", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Section not found");
        assertThatThrownBy(() -> repo.registerReport("2026-4", "c1", "s1", 1L))
                .isInstanceOf(DateTimeParseException.class);
        assertThatThrownBy(() -> repo.registerReport("April 2026", "c1", "s1", 1L))
                .isInstanceOf(DateTimeParseException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void registerReport_superadminWithNullScope_readsTheSectionsOwnSchool() {
        mark(FIRST, "c1", "o1", 7, "PRESENT");
        Map<String, Object> report = repo.registerReport(MONTH, "c1", "o1", null);
        List<Map<String, Object>> students = (List<Map<String, Object>>) report.get("students");
        assertThat(students).extracting(s -> s.get("fullName")).containsExactly("Gita");
        assertThat(students.getFirst()).containsEntry("presentCount", 1);
    }

    // ── studentHistory ──────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void studentHistory_leaveNeverMovesTheDenominator() {
        LocalDate d = LocalDate.parse("2026-04-06");
        mark(d, "c1", "s1", 1, "PRESENT");
        mark(d.plusDays(1), "c1", "s1", 1, "LATE");
        mark(d.plusDays(2), "c1", "s1", 1, "ABSENT");

        Map<String, Object> before = repo.studentHistory(1L, d, d.plusDays(10), 1L);
        assertThat(before).containsEntry("presentCount", 1).containsEntry("lateCount", 1)
                .containsEntry("leaveCount", 0).containsEntry("absentCount", 1)
                .containsEntry("daysRecorded", 3)
                .containsEntry("presentPercent", 66.7);   // (1+1)/(1+1+1)

        // Two LEAVE days: leaveCount and daysRecorded move, the percent does not.
        mark(d.plusDays(3), "c1", "s1", 1, "LEAVE");
        mark(d.plusDays(4), "c1", "s1", 1, "LEAVE");
        Map<String, Object> withLeave = repo.studentHistory(1L, d, d.plusDays(10), 1L);
        assertThat(withLeave).containsEntry("leaveCount", 2).containsEntry("daysRecorded", 5)
                .containsEntry("presentPercent", 66.7);

        // One more ABSENT does move it: (1+1)/(1+1+2) = 50.0.
        mark(d.plusDays(7), "c1", "s1", 1, "ABSENT");
        Map<String, Object> withAbsent = repo.studentHistory(1L, d, d.plusDays(10), 1L);
        assertThat(withAbsent).containsEntry("absentCount", 2).containsEntry("presentPercent", 50.0);

        // A leave-only range is 0.0, not NaN and not 100.
        Map<String, Object> leaveOnly = repo.studentHistory(1L, d.plusDays(3), d.plusDays(4), 1L);
        assertThat(leaveOnly).containsEntry("leaveCount", 2).containsEntry("presentPercent", 0.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void studentHistory_listsDaysInDateOrderWithWeekdayRemarksAndSundayFlag() {
        LocalDate mon = LocalDate.parse("2026-04-06");
        LocalDate sun = LocalDate.parse("2026-04-12");
        // Write the later day first so the ORDER BY is what sorts them.
        markWithRemarks(sun, "c1", "s1", 1, "LEAVE", "family function");
        mark(mon, "c1", "s1", 1, "PRESENT");

        Map<String, Object> history = repo.studentHistory(1L, mon, sun, 1L);
        Map<String, Object> student = (Map<String, Object>) history.get("student");
        assertThat(student).containsEntry("studentId", 1L).containsEntry("admissionNo", "ADM1")
                .containsEntry("rollNo", "1").containsEntry("fullName", "Asha")
                .containsEntry("sectionName", "Class 1-A");
        assertThat(history).containsEntry("from", "2026-04-06").containsEntry("to", "2026-04-12");

        List<Map<String, Object>> days = (List<Map<String, Object>>) history.get("days");
        assertThat(days).hasSize(2);
        assertThat(days.get(0)).containsEntry("date", "2026-04-06").containsEntry("weekday", "Mon")
                .containsEntry("status", "PRESENT").containsEntry("remarks", "").containsEntry("nonWorkingDay", false);
        assertThat(days.get(1)).containsEntry("date", "2026-04-12").containsEntry("weekday", "Sun")
                .containsEntry("status", "LEAVE").containsEntry("remarks", "family function")
                .containsEntry("nonWorkingDay", true);
    }

    @Test
    void studentHistory_rejectsInvertedRange_unknownStudent_andCrossSchool() {
        LocalDate d = LocalDate.parse("2026-04-06");
        assertThatThrownBy(() -> repo.studentHistory(1L, d.plusDays(1), d, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("from must be on or before to");
        assertThatThrownBy(() -> repo.studentHistory(9999L, d, d, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Student not found");
        assertThatThrownBy(() -> repo.studentHistory(1L, d, d, 2L))
                .isInstanceOf(SecurityException.class)
                .hasMessage("You do not have access to this student");
        // Superadmin (null scope) can read any school's student.
        assertThat(repo.studentHistory(7L, d, d, null)).containsEntry("daysRecorded", 0);
    }

    // ── sectionSummary ──────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void sectionSummary_rollsUpPerSection_leaveExcluded_sortedByPercent_andScopedToSchool() {
        LocalDate d1 = LocalDate.parse("2026-04-06");
        LocalDate d2 = LocalDate.parse("2026-04-07");
        mark(d1, "c1", "s1", 1, "PRESENT", 2, "LATE", 3, "LEAVE", 4, "ABSENT");
        mark(d2, "c1", "s1", 1, "PRESENT", 2, "PRESENT", 3, "PRESENT", 4, "ABSENT");
        mark(d1, "c1", "s1b", 5, "PRESENT");
        mark(d1, "c2", "s2", 6, "ABSENT");
        mark(d1, "c1", "o1", 7, "PRESENT");                       // other school
        mark(LocalDate.parse("2026-04-20"), "c1", "s1b", 5, "ABSENT"); // outside the range

        Map<String, Object> summary = repo.sectionSummary(d1, d2, 1L);
        assertThat(summary).containsEntry("from", "2026-04-06").containsEntry("to", "2026-04-07");

        List<Map<String, Object>> sections = (List<Map<String, Object>>) summary.get("sections");
        assertThat(sections).extracting(s -> s.get("sectionId")).containsExactly("s1b", "s1", "s2");

        Map<String, Object> s1 = sections.get(1);
        assertThat(s1).containsEntry("classId", "c1").containsEntry("sectionName", "Class 1-A")
                .containsEntry("teacherName", "Ms Rao")
                .containsEntry("presentCount", 4).containsEntry("lateCount", 1)
                .containsEntry("leaveCount", 1).containsEntry("absentCount", 2)
                .containsEntry("daysRecorded", 2)
                .containsEntry("presentPercent", 71.4);           // (4+1)/(4+1+2)
        assertThat(sections.get(0)).containsEntry("sectionId", "s1b").containsEntry("presentPercent", 100.0)
                .containsEntry("daysRecorded", 1);
        assertThat(sections.get(2)).containsEntry("sectionId", "s2").containsEntry("presentPercent", 0.0);

        Map<String, Object> overall = (Map<String, Object>) summary.get("overall");
        assertThat(overall).containsEntry("presentCount", 5).containsEntry("lateCount", 1)
                .containsEntry("leaveCount", 1).containsEntry("absentCount", 3)
                .containsEntry("presentPercent", 66.7);           // (5+1)/(5+1+3)

        // Superadmin with no scope sees the other school's section as well.
        List<Map<String, Object>> all = (List<Map<String, Object>>) repo.sectionSummary(d1, d2, null).get("sections");
        assertThat(all).extracting(s -> s.get("sectionId")).contains("o1");
        assertThat(all).hasSize(4);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sectionSummary_emptyRangeIsZeroesNotAnError_andInvertedRangeIsRejected() {
        LocalDate d = LocalDate.parse("2026-04-06");
        mark(d, "c1", "s1", 1, "PRESENT");

        Map<String, Object> empty = repo.sectionSummary(d.plusDays(10), d.plusDays(20), 1L);
        assertThat((List<?>) empty.get("sections")).isEmpty();
        assertThat((Map<String, Object>) empty.get("overall"))
                .containsEntry("presentCount", 0).containsEntry("presentPercent", 0.0);

        assertThatThrownBy(() -> repo.sectionSummary(d.plusDays(1), d, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("from must be on or before to");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sectionSummary_tiesOnPercentBreakOnSectionName() {
        LocalDate d = LocalDate.parse("2026-04-06");
        mark(d, "c1", "s1", 1, "PRESENT");
        mark(d, "c1", "s1b", 5, "PRESENT");
        mark(d, "c2", "s2", 6, "PRESENT");
        List<Map<String, Object>> sections = (List<Map<String, Object>>) repo.sectionSummary(d, d, 1L).get("sections");
        assertThat(sections).extracting(s -> s.get("sectionName"))
                .containsExactly("Class 1-A", "Class 1-B", "Class 2-A");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** {@code mark(date, classId, sectionId, studentId, status, studentId, status, ...)} */
    private static void mark(LocalDate date, String classId, String sectionId, Object... pairs) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            records.add(Map.of("studentId", pairs[i], "status", pairs[i + 1], "remarks", ""));
        }
        repo.saveSectionRegister(Map.of(
                "date", date.toString(), "classId", classId, "sectionId", sectionId,
                "records", records));
    }

    private static void markWithRemarks(LocalDate date, String classId, String sectionId,
                                        long studentId, String status, String remarks) {
        repo.saveSectionRegister(Map.of(
                "date", date.toString(), "classId", classId, "sectionId", sectionId,
                "records", List.of(Map.of("studentId", studentId, "status", status, "remarks", remarks))));
    }
}
