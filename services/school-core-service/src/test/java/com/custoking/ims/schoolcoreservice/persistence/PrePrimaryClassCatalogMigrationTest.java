package com.custoking.ims.schoolcoreservice.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrePrimaryClassCatalogMigrationTest {

    @Test
    void v19_preservesExistingNumericCoverageAndAddsPrePrimaryClasses() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
                .withUsername("owner")
                .withPassword("owner")) {
            pg.start();

            Flyway.configure()
                    .dataSource(pg.getJdbcUrl(), "owner", "owner")
                    .schemas("tenant_school")
                    .defaultSchema("tenant_school")
                    .locations("classpath:db/migration/tenant_school")
                    .target(MigrationVersion.fromVersion("18"))
                    .load()
                    .migrate();

            DataSource dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), "owner", "owner");
            JdbcClient jdbc = JdbcClient.create(dataSource);
            long schoolId;
            try (Connection c = dataSource.getConnection();
                 Statement st = c.createStatement()) {
                st.execute("""
                        INSERT INTO tenant_school.schools
                            (name, short_code, city, state, active, configured_class_count,
                             configured_section_count, created_at, academic_year_start_month,
                             financial_year_start_month)
                        VALUES
                            ('Demo', 'DEMO', 'Hyd', 'TG', true, 5, 2, now(), 4, 4)
                        """);
                schoolId = jdbc.sql("SELECT id FROM tenant_school.schools WHERE short_code = 'DEMO'")
                        .query(Long.class)
                        .single();
                for (int classNo = 1; classNo <= 5; classNo++) {
                    for (String section : List.of("A", "B")) {
                        st.execute("INSERT INTO tenant_school.school_sections " +
                                "(id, school_id, school_class_id, name, teacher_name, active) VALUES " +
                                "('" + schoolId + "-" + classNo + "-" + section + "', " + schoolId +
                                ", '" + classNo + "', '" + section + "', '', true)");
                    }
                }
            }

            Flyway.configure()
                    .dataSource(pg.getJdbcUrl(), "owner", "owner")
                    .schemas("tenant_school")
                    .defaultSchema("tenant_school")
                    .locations("classpath:db/migration/tenant_school")
                    .load()
                    .migrate();

            Integer configuredCount = jdbc.sql("""
                            SELECT configured_class_count
                            FROM tenant_school.schools
                            WHERE id = :schoolId
                            """)
                    .param("schoolId", schoolId)
                    .query(Integer.class)
                    .single();
            assertThat(configuredCount).isEqualTo(8);

            List<String> activeClasses = jdbc.sql("""
                            SELECT sc.name
                            FROM tenant_school.school_sections ss
                            JOIN tenant_school.school_classes sc ON sc.id = ss.school_class_id
                            WHERE ss.school_id = :schoolId
                              AND ss.active = true
                            GROUP BY sc.name
                            ORDER BY min(sc.sort_order)
                            """)
                    .param("schoolId", schoolId)
                    .query(String.class)
                    .list();
            assertThat(activeClasses).containsExactly(
                    "Nursery / Pre-Nursery / Playgroup",
                    "LKG (Lower Kindergarten)",
                    "UKG (Upper Kindergarten)",
                    "1", "2", "3", "4", "5");

            Long prePrimaryEvents = jdbc.sql("""
                            SELECT count(*)
                            FROM tenant_school.outbox_events
                            WHERE event_type = 'school-section.upserted.v1'
                              AND payload->>'classId' IN ('pre-primary', 'lkg', 'ukg')
                            """)
                    .query(Long.class)
                    .single();
            assertThat(prePrimaryEvents).isEqualTo(6);
        }
    }

    @Test
    void v21_normalizesNumericClassOrderAfterSeedDrift() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
                .withUsername("owner")
                .withPassword("owner")) {
            pg.start();

            Flyway.configure()
                    .dataSource(pg.getJdbcUrl(), "owner", "owner")
                    .schemas("tenant_school")
                    .defaultSchema("tenant_school")
                    .locations("classpath:db/migration/tenant_school")
                    .target(MigrationVersion.fromVersion("20"))
                    .load()
                    .migrate();

            DataSource dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), "owner", "owner");
            JdbcClient jdbc = JdbcClient.create(dataSource);
            jdbc.sql("""
                            UPDATE tenant_school.school_classes
                            SET sort_order = 1
                            WHERE id = '1'
                            """)
                    .update();

            Flyway.configure()
                    .dataSource(pg.getJdbcUrl(), "owner", "owner")
                    .schemas("tenant_school")
                    .defaultSchema("tenant_school")
                    .locations("classpath:db/migration/tenant_school")
                    .load()
                    .migrate();

            List<String> classIds = jdbc.sql("""
                            SELECT id
                            FROM tenant_school.school_classes
                            ORDER BY sort_order, name
                            LIMIT 5
                            """)
                    .query(String.class)
                    .list();

            assertThat(classIds).containsExactly("pre-primary", "lkg", "ukg", "1", "2");
        }
    }
}
