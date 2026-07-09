package com.custoking.ims.schoolcoreservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class TenantSchoolClassCatalogMigrationTest {

    @Test
    void migrationSeedsClassCatalogAndBackfillsConfiguredSections() {
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
                    .target("15")
                    .load()
                    .migrate();

            JdbcClient jdbc = JdbcClient.create(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(
                            pg.getJdbcUrl(), "owner", "owner"));
            jdbc.sql("""
                            INSERT INTO tenant_school.schools
                                (name, short_code, city, state, active,
                                 configured_class_count, configured_section_count, created_at)
                            VALUES ('Migration Demo', 'MIG', 'Hyd', 'TG', true, 3, 2, now())
                            """)
                    .update();

            Flyway.configure()
                    .dataSource(pg.getJdbcUrl(), "owner", "owner")
                    .schemas("tenant_school")
                    .defaultSchema("tenant_school")
                    .locations("classpath:db/migration/tenant_school")
                    .load()
                    .migrate();

            assertThat(jdbc.sql("SELECT count(*) FROM tenant_school.school_classes")
                    .query(Long.class)
                    .single()).isEqualTo(12L);
            assertThat(jdbc.sql("""
                            SELECT count(*)
                            FROM tenant_school.school_sections
                            WHERE school_id = 1 AND active = true
                            """)
                    .query(Long.class)
                    .single()).isEqualTo(6L);
        }
    }
}
