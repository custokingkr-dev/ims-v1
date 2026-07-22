package com.custoking.ims.schoolcoreservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class SchoolUidMigrationTest {

    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Test
    void v20BackfillsExistingSchoolsAndDefaultsNewRows() {
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
                    .target("19")
                    .load()
                    .migrate();

            DataSource dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), "owner", "owner");
            JdbcClient jdbc = JdbcClient.create(dataSource);
            jdbc.sql("""
                            INSERT INTO tenant_school.schools
                                (name, short_code, city, state, active,
                                 configured_class_count, configured_section_count, created_at,
                                 academic_year_start_month, financial_year_start_month)
                            VALUES ('Legacy School', 'LEG', 'Hyd', 'TG', true, 12, 2, now(), 4, 4)
                            """)
                    .update();

            Flyway.configure()
                    .dataSource(pg.getJdbcUrl(), "owner", "owner")
                    .schemas("tenant_school")
                    .defaultSchema("tenant_school")
                    .locations("classpath:db/migration/tenant_school")
                    .load()
                    .migrate();

            String legacyUid = jdbc.sql("SELECT school_uid::text FROM tenant_school.schools WHERE short_code = 'LEG'")
                    .query(String.class)
                    .single();
            assertThat(legacyUid).matches(UUID_PATTERN);

            jdbc.sql("""
                            INSERT INTO tenant_school.schools
                                (name, short_code, city, state, active,
                                 configured_class_count, configured_section_count, created_at,
                                 academic_year_start_month, financial_year_start_month)
                            VALUES ('New School', 'NEW', 'Hyd', 'TG', true, 12, 2, now(), 4, 4)
                            """)
                    .update();
            String newUid = jdbc.sql("SELECT school_uid::text FROM tenant_school.schools WHERE short_code = 'NEW'")
                    .query(String.class)
                    .single();

            assertThat(newUid).matches(UUID_PATTERN);
            assertThat(newUid).isNotEqualTo(legacyUid);
            assertThat(jdbc.sql("SELECT count(DISTINCT school_uid) FROM tenant_school.schools")
                    .query(Long.class)
                    .single()).isEqualTo(2L);
        }
    }
}
