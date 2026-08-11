package com.custoking.ims.schoolcoreservice.security;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StudentImportRlsIntegrationTest {
    static PostgreSQLContainer<?> postgres;
    static HikariDataSource pool;
    static DataSource appRuntime;
    static StudentReadRepository imports;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withUsername("owner")
                .withPassword("owner");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), "owner", "owner")
                .schemas("student")
                .defaultSchema("student")
                .locations("classpath:db/migration/student")
                .load()
                .migrate();

        try (Connection connection = java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), "owner", "owner");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
            statement.execute("GRANT USAGE ON SCHEMA student TO app_rt");
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA student TO app_rt");
            statement.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA student TO app_rt");
            statement.execute("""
                    INSERT INTO student.import_batches
                        (id, file_token, job_id, total_rows, valid_count, error_count,
                         warning_count, status, pct, inserted, skipped, school_id, created_at)
                    VALUES
                        ('batch-a', 'token-a', 'job-a', 1, 1, 0, 0, 'DONE', 100, 1, 0, 10, now()),
                        ('batch-b', 'token-b', 'job-b', 1, 1, 0, 0, 'DONE', 100, 1, 0, 20, now())
                    """);
            statement.execute("""
                    INSERT INTO student.import_rows
                        (id, row_no, name, class_name, section_name, admission_no,
                         phone, status, batch_id, school_id)
                    VALUES
                        ('row-a', 2, 'Synthetic A', '1', 'A', 'A-1', '9000000000', 'Imported', 'batch-a', 10),
                        ('row-b', 2, 'Synthetic B', '1', 'A', 'B-1', '9000000000', 'Imported', 'batch-b', 20)
                    """);
        }

        pool = new HikariDataSource();
        pool.setJdbcUrl(postgres.getJdbcUrl());
        pool.setUsername("app_rt");
        pool.setPassword("app_rt");
        pool.setMaximumPoolSize(1);
        appRuntime = new TenantAwareDataSource(pool);
        imports = new StudentReadRepository(
                org.springframework.jdbc.core.simple.JdbcClient.create(appRuntime),
                mock(StudentPhotoStorage.class), mock(OutboxWriter.class));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @AfterAll
    static void tearDown() {
        TenantContext.clear();
        if (pool != null) {
            pool.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void importEvidenceIsTenantScopedAcrossAReusedPhysicalConnection() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@invalid.local", "ADMIN", 10L, null));
        assertThat(count("student.import_batches")).isEqualTo(1);
        assertThat(count("student.import_rows")).isEqualTo(1);
        assertThat(single("SELECT job_id FROM student.import_batches")).isEqualTo("job-a");

        TenantContext.set(new TenantContext(2L, "b@invalid.local", "ADMIN", 20L, null));
        assertThat(count("student.import_batches")).isEqualTo(1);
        assertThat(count("student.import_rows")).isEqualTo(1);
        assertThat(single("SELECT job_id FROM student.import_batches")).isEqualTo("job-b");
    }

    @Test
    void noTenantContextFailsClosedForImportEvidence() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@invalid.local", "ADMIN", 10L, null));
        assertThat(count("student.import_batches")).isEqualTo(1);

        TenantContext.clear();
        assertThat(count("student.import_batches")).isZero();
        assertThat(count("student.import_rows")).isZero();
    }

    @Test
    void crossTenantImportEvidenceMutationIsANoOp() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@invalid.local", "ADMIN", 10L, null));
        try (Connection connection = appRuntime.getConnection(); Statement statement = connection.createStatement()) {
            assertThat(statement.executeUpdate(
                    "UPDATE student.import_batches SET status='FAILED' WHERE school_id=20")).isZero();
            assertThat(statement.executeUpdate(
                    "DELETE FROM student.import_rows WHERE school_id=20")).isZero();
        }

        TenantContext.set(new TenantContext(2L, "b@invalid.local", "ADMIN", 20L, null));
        assertThat(single("SELECT status FROM student.import_batches")).isEqualTo("DONE");
        assertThat(count("student.import_rows")).isEqualTo(1);
    }

    @Test
    void dailyImportUsageRemainsTenantScopedEvenWhenFleetScopeIsRequested() {
        TenantContext.set(new TenantContext(1L, "a@invalid.local", "ADMIN", 10L, null));
        assertThat(imports.importUsageDaily(null, 30, 100))
                .singleElement()
                .satisfies(row -> assertThat(row.schoolId()).isEqualTo(10L));

        TenantContext.set(new TenantContext(2L, "b@invalid.local", "ADMIN", 20L, null));
        assertThat(imports.importUsageDaily(null, 30, 100))
                .singleElement()
                .satisfies(row -> assertThat(row.schoolId()).isEqualTo(20L));

        TenantContext.clear();
        assertThat(imports.importUsageDaily(null, 30, 100)).isEmpty();
    }

    @Test
    void superadminCanListFleetImportBatchesWithoutAnUntypedNullParameter() {
        TenantContext.set(new TenantContext(99L, "super@invalid.local", "SUPERADMIN", null, null));

        assertThat(imports.importBatches(null, 500))
                .extracting(StudentReadRepository.ImportBatchRow::fileToken)
                .containsExactlyInAnyOrder("token-a", "token-b");
    }

    private long count(String table) throws Exception {
        try (Connection connection = appRuntime.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private String single(String sql) throws Exception {
        try (Connection connection = appRuntime.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
