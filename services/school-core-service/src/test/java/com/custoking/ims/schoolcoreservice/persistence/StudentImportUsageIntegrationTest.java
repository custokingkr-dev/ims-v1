package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StudentImportUsageIntegrationTest {

    static PostgreSQLContainer<?> postgres;
    static JdbcClient jdbc;
    static StudentReadRepository repository;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withUsername("owner")
                .withPassword("owner");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("student")
                .defaultSchema("student")
                .locations("classpath:db/migration/student")
                .load()
                .migrate();

        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = JdbcClient.create(dataSource);
        repository = new StudentReadRepository(
                jdbc, mock(StudentPhotoStorage.class), mock(OutboxWriter.class));
        seedUsage();
    }

    @AfterAll
    static void tearDown() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void aggregatesDailyImportUsageForOneSchoolWithoutTokensOrStudentData() {
        List<StudentReadRepository.ImportUsageDailyRow> rows =
                repository.importUsageDaily(10L, 30, 100);

        assertThat(rows).hasSize(2);
        var today = rows.stream()
                .filter(row -> row.usageDate().equals(LocalDate.now(ZoneOffset.UTC)))
                .findFirst().orElseThrow();
        assertThat(today.schoolId()).isEqualTo(10L);
        assertThat(today.previewedBatches()).isEqualTo(2L);
        assertThat(today.completedBatches()).isEqualTo(1L);
        assertThat(today.unfinishedBatches()).isEqualTo(1L);
        assertThat(today.attemptedRows()).isEqualTo(750L);
        assertThat(today.insertedRows()).isEqualTo(490L);
        assertThat(today.skippedRows()).isEqualTo(10L);
        assertThat(today.sourceBytes()).isEqualTo(1_500L);
    }

    @Test
    void fleetViewIsBoundedToNinetyDaysAndContainsOnlyOpaqueOperationalDimensions() {
        List<StudentReadRepository.ImportUsageDailyRow> rows =
                repository.importUsageDaily(null, 1_000, 20_001);

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(StudentReadRepository.ImportUsageDailyRow::schoolId)
                .containsExactlyInAnyOrder(10L, 10L, 20L);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.usageDate()).isAfterOrEqualTo(LocalDate.now(ZoneOffset.UTC).minusDays(89));
            assertThat(row.toString()).doesNotContain("token-", "Synthetic Student", "9000000000");
        });
    }

    private static void seedUsage() {
        insertBatch("batch-10-done", "token-10-done", 10, "DONE", 500, 490, 10, 1_000, "now()");
        insertBatch("batch-10-preview", "token-10-preview", 10, "PREVIEWED", 250, 0, 0, 500, "now()");
        insertBatch("batch-10-yesterday", "token-10-yesterday", 10, "DONE", 100, 100, 0, 250,
                "now() - interval '1 day'");
        insertBatch("batch-20-done", "token-20-done", 20, "DONE", 300, 300, 0, 750, "now()");
        insertBatch("batch-old", "token-old", 10, "DONE", 50, 50, 0, 100,
                "now() - interval '120 days'");
    }

    private static void insertBatch(
            String id,
            String token,
            long schoolId,
            String status,
            int total,
            int inserted,
            int skipped,
            long sourceBytes,
            String createdAtExpression) {
        jdbc.sql("""
                        INSERT INTO student.import_batches
                            (id, file_token, total_rows, valid_count, error_count, warning_count,
                             status, pct, inserted, skipped, created_at, completed_at, school_id,
                             original_file_size)
                        VALUES
                            (:id, :token, :total, :total, 0, 0,
                             :status, :pct, :inserted, :skipped, %s,
                             CASE WHEN :status = 'DONE' THEN %s ELSE NULL END,
                             :schoolId, :sourceBytes)
                        """.formatted(createdAtExpression, createdAtExpression))
                .param("id", id)
                .param("token", token)
                .param("total", total)
                .param("status", status)
                .param("pct", "DONE".equals(status) ? 100 : 0)
                .param("inserted", inserted)
                .param("skipped", skipped)
                .param("schoolId", schoolId)
                .param("sourceBytes", sourceBytes)
                .update();
    }
}
