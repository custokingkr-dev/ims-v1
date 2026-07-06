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
            st.execute("DELETE FROM tenant_school.school_class_subjects");
            st.execute("DELETE FROM tenant_school.school_class_bell_map");
            st.execute("DELETE FROM tenant_school.school_bell_periods");
            st.execute("DELETE FROM tenant_school.school_bell_schedules");
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
}
