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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceEventEmissionIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbc;
    static SchoolStructureReadRepository repo;

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
        repo = new SchoolStructureReadRepository(jdbc, new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school"));
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

    static long countOutbox() {
        return jdbc.sql("SELECT count(*) FROM tenant_school.outbox_events").query(Long.class).single();
    }

    @Test
    void createSchoolEmitsSchoolUpsertedInSameTransaction() {
        Map<String, Object> req = Map.of("name", "Test School", "shortCode", "TS", "active", true);
        var created = repo.createSchool(req);
        Long id = ((Number) created.get("id")).longValue();

        var rows = jdbc.sql("SELECT event_type, event_key, payload FROM tenant_school.outbox_events WHERE aggregate_type='School' AND aggregate_id=:id")
                .param("id", id.toString())
                .query((rs, n) -> rs.getString("event_type"))
                .list();
        assertThat(rows).contains("school.upserted.v1");
    }

    @Test
    void failedSchoolCreateEmitsNoEvent() {
        long before = countOutbox();
        assertThatThrownBy(() -> repo.createSchool(Map.of()))   // missing required name -> throws
                .isInstanceOf(RuntimeException.class);
        assertThat(countOutbox()).isEqualTo(before);             // rolled back with the transaction
    }
}
