package com.custoking.ims.schoolcoreservice.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class StudentImportProgressStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16")
            .withUsername("owner")
            .withPassword("owner");

    static HikariDataSource dataSource;
    static JdbcClient jdbc;
    static StudentImportProgressStore progress;

    @BeforeAll
    static void setUp() {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .schemas("tenant_school").defaultSchema("tenant_school")
                .locations("classpath:db/migration/tenant_school")
                .load().migrate();
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .schemas("student").defaultSchema("student")
                .locations("classpath:db/migration/student")
                .load().migrate();

        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(PG.getJdbcUrl());
        dataSource.setUsername(PG.getUsername());
        dataSource.setPassword(PG.getPassword());
        dataSource.setMaximumPoolSize(2);
        jdbc = JdbcClient.create(dataSource);
        progress = new StudentImportProgressStore(jdbc);

        jdbc.sql("""
                INSERT INTO student.import_batches
                    (id, file_token, job_id, total_rows, valid_count, error_count, warning_count,
                     status, pct, inserted, skipped, created_at, school_id)
                VALUES ('batch-live', 'file-live', 'job-live', 5, 5, 0, 0,
                        'PREVIEWED', 0, 0, 0, now(), 101)
                """).update();
    }

    @AfterAll
    static void closeDataSource() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void persistsExactRowProgressAndCompletion() {
        progress.start("job-live", "batch-live", 101L, 5);
        progress.update("job-live", 101L, 5, 2, 1, 1);

        assertThat(progress.status("job-live", 101L))
                .containsEntry("status", "RUNNING")
                .containsEntry("processedRows", 2)
                .containsEntry("totalRows", 5)
                .containsEntry("inserted", 1)
                .containsEntry("skipped", 1)
                .containsEntry("pct", 40)
                .containsEntry("done", false);

        progress.complete("job-live", 101L, 5, 4, 1);

        assertThat(progress.status("job-live", 101L))
                .containsEntry("status", "COMPLETED")
                .containsEntry("processedRows", 5)
                .containsEntry("pct", 100)
                .containsEntry("done", true);
    }
}
