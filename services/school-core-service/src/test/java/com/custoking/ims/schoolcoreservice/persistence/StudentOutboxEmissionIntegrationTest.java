package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that {@code createStudent} / {@code updateStudent} emit {@code student.upserted.v1}
 * into {@code tenant_school.outbox_events} within the same transaction as the domain write,
 * per Reporting Decoupling SP5. Mirrors {@code ReferenceEventEmissionIntegrationTest}'s
 * (SP1) bootstrap/assertion shape exactly, one level down (student, not school/section).
 */
class StudentOutboxEmissionIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbc;
    static SchoolStructureReadRepository schoolRepo;
    static StudentReadRepository studentRepo;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        for (String schema : new String[] {"tenant_school", "student"}) {
            Flyway.configure()
                    .dataSource(PG.getJdbcUrl(), "owner", "owner")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration/" + schema)
                    .load()
                    .migrate();
        }
        dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), "owner", "owner");
        jdbc = JdbcClient.create(dataSource);
        OutboxWriter outbox = new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school");
        schoolRepo = new SchoolStructureReadRepository(jdbc, outbox);
        studentRepo = new StudentReadRepository(jdbc,
                org.mockito.Mockito.mock(com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage.class),
                outbox);
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    @BeforeEach
    void resetData() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM student.students");
            st.execute("DELETE FROM tenant_school.outbox_events");
            st.execute("DELETE FROM tenant_school.school_sections");
            st.execute("DELETE FROM tenant_school.school_classes");
            st.execute("DELETE FROM tenant_school.academic_years");
            st.execute("DELETE FROM tenant_school.schools");
            for (int i = 1; i <= 12; i++) {
                st.execute("INSERT INTO tenant_school.school_classes (id, name, sort_order) VALUES " +
                        "('c" + i + "', '" + i + "', " + i + ")");
            }
            st.execute("INSERT INTO tenant_school.academic_years (id, label, active) VALUES ('ay1', '2025-26', true)");
        }
    }

    static long seedSchool(int classCount, int sectionCount) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO tenant_school.schools " +
                    "(name, short_code, city, state, active, configured_class_count, configured_section_count, created_at) " +
                    "VALUES ('Demo', 'DEMO', 'Hyd', 'TG', true, " + classCount + ", " + sectionCount + ", now()) ");
        }
        Long id = jdbc.sql("SELECT id FROM tenant_school.schools WHERE short_code = 'DEMO'")
                .query(Long.class).single();
        schoolRepo.updateStructure(id, classCount, sectionCount);
        return id;
    }

    @Test
    void createStudentEmitsStudentUpsertedInSameTransaction() throws Exception {
        long schoolId = seedSchool(3, 2);

        Map<String, Object> created = studentRepo.createStudent(Map.of(
                "schoolId", schoolId,
                "fullName", "Jane Doe",
                "admissionNumber", "ADM-1",
                "gradeLevel", "1",
                "sectionName", "A",
                "phone", "9876500000"));
        Long id = ((Number) created.get("id")).longValue();

        var rows = jdbc.sql("""
                        SELECT event_type, payload FROM tenant_school.outbox_events
                        WHERE aggregate_type = 'Student' AND aggregate_id = :id
                        """)
                .param("id", id.toString())
                .query((rs, n) -> rs.getString("event_type"))
                .list();
        assertThat(rows).contains("student.upserted.v1");
    }

    @Test
    void updateStudentEmitsStudentUpsertedInSameTransaction() throws Exception {
        long schoolId = seedSchool(3, 2);
        Map<String, Object> created = studentRepo.createStudent(Map.of(
                "schoolId", schoolId,
                "fullName", "Jane Doe",
                "admissionNumber", "ADM-2",
                "gradeLevel", "1",
                "sectionName", "A",
                "phone", "9876500000"));
        Long id = ((Number) created.get("id")).longValue();
        jdbc.sql("DELETE FROM tenant_school.outbox_events WHERE aggregate_type = 'Student' AND aggregate_id = :id")
                .param("id", id.toString())
                .update();

        studentRepo.updateStudent(id, Map.of(
                "schoolId", schoolId,
                "fullName", "Jane Updated",
                "admissionNumber", "ADM-2",
                "classId", created.get("classId"),
                "sectionId", created.get("sectionId"),
                "phone", "9876500001"));

        var rows = jdbc.sql("""
                        SELECT event_type, payload FROM tenant_school.outbox_events
                        WHERE aggregate_type = 'Student' AND aggregate_id = :id
                        """)
                .param("id", id.toString())
                .query((rs, n) -> Map.of("eventType", rs.getString("event_type"), "payload", rs.getString("payload")))
                .list();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("eventType")).isEqualTo("student.upserted.v1");
        assertThat(rows.get(0).get("payload")).contains("Jane Updated");
    }
}
