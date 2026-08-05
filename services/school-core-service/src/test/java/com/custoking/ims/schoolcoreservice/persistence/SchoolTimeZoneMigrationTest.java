package com.custoking.ims.schoolcoreservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class SchoolTimeZoneMigrationTest {

    @Test
    void v24BackfillsExistingSchoolsAndDefaultsNewSchools() {
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
                    .target("23")
                    .load()
                    .migrate();

            JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), "owner", "owner"));
            jdbc.sql("""
                    INSERT INTO tenant_school.schools (name, short_code, active, created_at)
                    VALUES ('Existing School', 'EXIST', true, now())
                    """).update();

            Flyway.configure()
                    .dataSource(pg.getJdbcUrl(), "owner", "owner")
                    .schemas("tenant_school")
                    .defaultSchema("tenant_school")
                    .locations("classpath:db/migration/tenant_school")
                    .load()
                    .migrate();

            jdbc.sql("""
                    INSERT INTO tenant_school.schools (name, short_code, active, created_at)
                    VALUES ('New School', 'NEW-TZ', true, now())
                    """).update();

            assertThat(jdbc.sql("SELECT time_zone FROM tenant_school.schools ORDER BY id")
                    .query(String.class)
                    .list()).containsExactly("Asia/Kolkata", "Asia/Kolkata");
        }
    }
}
