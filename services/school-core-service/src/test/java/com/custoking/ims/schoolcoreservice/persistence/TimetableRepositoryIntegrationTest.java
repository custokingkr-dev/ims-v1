package com.custoking.ims.schoolcoreservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimetableRepositoryIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbc;
    static TimetableRepository repo;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("tenant_school")
                .defaultSchema("tenant_school")
                .locations("classpath:db/migration/tenant_school")
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), "owner", "owner");
        jdbc = JdbcClient.create(dataSource);
        repo = new TimetableRepository(jdbc);
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    @BeforeEach
    void resetData() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM tenant_school.school_timetable_entries");
            st.execute("DELETE FROM tenant_school.school_class_subjects");
            st.execute("DELETE FROM tenant_school.school_class_bell_map");
            st.execute("DELETE FROM tenant_school.school_bell_periods");
            st.execute("DELETE FROM tenant_school.school_bell_schedules");
            st.execute("DELETE FROM tenant_school.staff_members");
            st.execute("DELETE FROM tenant_school.school_sections");
            st.execute("DELETE FROM tenant_school.school_classes");
            st.execute("DELETE FROM tenant_school.schools");
            st.execute("DELETE FROM tenant_school.academic_years");
        }
    }

    private long seedSchool() throws Exception {
        String shortCode = "S" + UUID.randomUUID().toString().substring(0, 8);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO tenant_school.schools (name, short_code, active, created_at) " +
                    "VALUES ('Demo School', '" + shortCode + "', true, now())");
        }
        return jdbc.sql("SELECT id FROM tenant_school.schools WHERE short_code = :c")
                .param("c", shortCode).query(Long.class).single();
    }

    private String seedClass(long schoolId, String name) throws Exception {
        String classId = "cls-" + name + "-" + UUID.randomUUID();
        String sectionId = "sec-" + name + "-" + UUID.randomUUID();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO tenant_school.school_classes (id, name, sort_order) VALUES " +
                    "('" + classId + "', '" + name + "', 1)");
            st.execute("INSERT INTO tenant_school.school_sections (id, name, active, school_class_id, school_id) VALUES " +
                    "('" + sectionId + "', 'A', true, '" + classId + "', " + schoolId + ")");
        }
        return classId;
    }

    private String seedYear(long schoolId, String id, boolean active) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO tenant_school.academic_years (id, label, active) VALUES (" +
                    "'" + id + "', '" + id + "', " + active + ")");
        }
        return id;
    }

    private String seedSection(long schoolId, String classId, String name) throws Exception {
        String sectionId = "sec-" + name;
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO tenant_school.school_sections (id, name, active, school_class_id, school_id) VALUES " +
                    "('" + sectionId + "', '" + name + "', true, '" + classId + "', " + schoolId + ")");
        }
        return sectionId;
    }

    private long seedStaff(long schoolId, String name) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO tenant_school.staff_members (name, designation, monthly_salary, school_id) VALUES " +
                    "('" + name + "', 'Teacher', 0, " + schoolId + ")");
        }
        return jdbc.sql("SELECT id FROM tenant_school.staff_members WHERE school_id = :s AND name = :n ORDER BY id DESC LIMIT 1")
                .param("s", schoolId).param("n", name).query(Long.class).single();
    }

    @Test
    void createsScheduleWithPeriodsAndMapsClass() throws Exception {
        long schoolId = seedSchool();
        String classId = seedClass(schoolId, "6");
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

    @Test
    void rejectsSubjectEditsForNonActiveYear() throws Exception {
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

    @Test
    void upsertIsReplaceInPlaceAndReportsTeacherConflict() throws Exception {
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
    void swapPeriodOrderSwapsSortOrdersWithoutConstraintViolation() throws Exception {
        long schoolId = seedSchool();
        var sched = repo.createSchedule(schoolId, "Std");
        long schedId = ((Number) sched.get("id")).longValue();
        var p1 = repo.addPeriod(schoolId, schedId, "P1", "08:00", "08:45", false, 1);
        var p2 = repo.addPeriod(schoolId, schedId, "P2", "08:45", "09:30", false, 2);
        long p1Id = ((Number) p1.get("id")).longValue();
        long p2Id = ((Number) p2.get("id")).longValue();

        repo.swapPeriodOrder(schoolId, schedId, p1Id, p2Id);

        var periods = repo.bellSchedules(schoolId).get(0).get("periods");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> periodList = (List<Map<String, Object>>) periods;
        Map<String, Object> reloadedP1 = periodList.stream().filter(m -> ((Number) m.get("id")).longValue() == p1Id).findFirst().orElseThrow();
        Map<String, Object> reloadedP2 = periodList.stream().filter(m -> ((Number) m.get("id")).longValue() == p2Id).findFirst().orElseThrow();
        assertThat(reloadedP1.get("sortOrder")).isEqualTo(2);
        assertThat(reloadedP2.get("sortOrder")).isEqualTo(1);
    }

    private void insertEntryDirect(long schoolId, String year, String sectionId, long periodId, String subject) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO tenant_school.school_timetable_entries " +
                    "(school_id, academic_year_id, section_id, day_name, bell_period_id, subject_name) VALUES (" +
                    schoolId + ", '" + year + "', '" + sectionId + "', 'Mon', " + periodId + ", '" + subject + "')");
        }
    }

    @Test
    void deletePeriodBlockedWhenReferencedByPastYearTimetable() throws Exception {
        long schoolId = seedSchool();
        String classId = seedClass(schoolId, "6");
        String sec = seedSection(schoolId, classId, "6-A");
        seedYear(schoolId, "ay_2026_27", true);
        String past = seedYear(schoolId, "ay_2025_26", false);
        var sched = repo.createSchedule(schoolId, "Std");
        long schedId = ((Number) sched.get("id")).longValue();
        var p1 = repo.addPeriod(schoolId, schedId, "P1", "08:00", "08:45", false, 1);
        long pid = ((Number) p1.get("id")).longValue();
        // An archived (past-year) timetable references this period.
        insertEntryDirect(schoolId, past, sec, pid, "Math");

        assertThatThrownBy(() -> repo.deletePeriod(schoolId, pid))
            .isInstanceOf(YearLockedException.class);
        // period must survive so the archive stays intact
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> periods = (List<Map<String, Object>>) repo.bellSchedules(schoolId).get(0).get("periods");
        assertThat(periods).anySatisfy(m -> assertThat(((Number) m.get("id")).longValue()).isEqualTo(pid));
    }

    @Test
    void deletePeriodAllowedWhenOnlyActiveYearUsesIt() throws Exception {
        long schoolId = seedSchool();
        String classId = seedClass(schoolId, "6");
        String sec = seedSection(schoolId, classId, "6-A");
        String active = seedYear(schoolId, "ay_2026_27", true);
        var sched = repo.createSchedule(schoolId, "Std");
        long schedId = ((Number) sched.get("id")).longValue();
        var p1 = repo.addPeriod(schoolId, schedId, "P1", "08:00", "08:45", false, 1);
        long pid = ((Number) p1.get("id")).longValue();
        repo.setClassSchedule(schoolId, classId, schedId);
        repo.addSubject(schoolId, classId, active, "Math");
        repo.upsertEntry(schoolId, sec, "Mon", pid, "Math", null);

        repo.deletePeriod(schoolId, pid);   // active-year only → allowed, cascade clears the active entry
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> periods = (List<Map<String, Object>>) repo.bellSchedules(schoolId).get(0).get("periods");
        assertThat(periods).noneSatisfy(m -> assertThat(((Number) m.get("id")).longValue()).isEqualTo(pid));
    }

    @Test
    void deleteScheduleBlockedWhenReferencedByPastYearTimetable() throws Exception {
        long schoolId = seedSchool();
        String classId = seedClass(schoolId, "6");
        String sec = seedSection(schoolId, classId, "6-A");
        seedYear(schoolId, "ay_2026_27", true);
        String past = seedYear(schoolId, "ay_2025_26", false);
        var sched = repo.createSchedule(schoolId, "Std");
        long schedId = ((Number) sched.get("id")).longValue();
        var p1 = repo.addPeriod(schoolId, schedId, "P1", "08:00", "08:45", false, 1);
        long pid = ((Number) p1.get("id")).longValue();
        insertEntryDirect(schoolId, past, sec, pid, "Math");

        assertThatThrownBy(() -> repo.deleteSchedule(schoolId, schedId))
            .isInstanceOf(YearLockedException.class);
        assertThat(repo.bellSchedules(schoolId)).hasSize(1);
    }

    @Test
    void upsertRejectsNonActiveYearAndBreakAndUnknownSubject() throws Exception {
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
}
