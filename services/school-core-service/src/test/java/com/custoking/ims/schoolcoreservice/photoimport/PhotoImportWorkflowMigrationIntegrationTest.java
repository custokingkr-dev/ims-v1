package com.custoking.ims.schoolcoreservice.photoimport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhotoImportWorkflowMigrationIntegrationTest {
    private static PostgreSQLContainer<?> postgres;
    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withUsername("owner")
                .withPassword("owner");
        postgres.start();
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), "owner", "owner");
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("CREATE SCHEMA student").update();
        jdbc.sql("""
                CREATE TABLE student.photo_import_batches (
                    id UUID PRIMARY KEY,
                    drive_folder_id VARCHAR(255) NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    version BIGINT NOT NULL DEFAULT 0,
                    CONSTRAINT uq_photo_import_drive_folder UNIQUE (drive_folder_id)
                )
                """).update();
        jdbc.sql("""
                CREATE TABLE student.photo_import_rows (
                    id UUID PRIMARY KEY,
                    source_checksum VARCHAR(255)
                )
                """).update();
        jdbc.sql("""
                CREATE TABLE student.photo_import_sources (
                    id UUID PRIMARY KEY,
                    checksum VARCHAR(255)
                )
                """).update();
        jdbc.sql("""
                INSERT INTO student.photo_import_batches (id, drive_folder_id, status)
                VALUES (:id, 'shared-intake-folder', 'COMPLETED')
                """)
                .param("id", UUID.randomUUID())
                .update();
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource(
                            "db/migration/student/V13__production_photo_import_workflow.sql"));
        }
        jdbc.sql("ALTER TABLE student.photo_import_batches ADD COLUMN school_id BIGINT NOT NULL DEFAULT 1").update();
        jdbc.sql("ALTER TABLE student.photo_import_batches ADD CONSTRAINT uq_test_batch_school UNIQUE (id, school_id)").update();
        jdbc.sql("ALTER TABLE student.photo_import_rows ADD COLUMN batch_id UUID, ADD COLUMN school_id BIGINT").update();
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource(
                            "db/migration/student/V20__photo_import_recovery_audit.sql"));
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource(
                            "db/migration/student/V21__photo_import_recovery_progress.sql"));
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource(
                            "db/migration/student/V27__photo_import_sha256_integrity.sql"));
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource(
                            "db/migration/student/V34__photo_import_drive_revision_evidence.sql"));
        }
    }

    @AfterAll
    static void tearDown() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void completedFolderCanBeReusedButOnlyOneNewBatchMayRemainActive() {
        UUID activeId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO student.photo_import_batches (id, drive_folder_id, status)
                VALUES (:id, 'shared-intake-folder', 'DRAFT')
                """)
                .param("id", activeId)
                .update();

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO student.photo_import_batches (id, drive_folder_id, status)
                        VALUES (:id, 'shared-intake-folder', 'REVIEW')
                        """)
                .param("id", UUID.randomUUID())
                .update())
                .hasMessageContaining("uq_photo_import_active_drive_folder");

        jdbc.sql("UPDATE student.photo_import_batches SET status = 'COMPLETED' WHERE id = :id")
                .param("id", activeId)
                .update();
        jdbc.sql("""
                INSERT INTO student.photo_import_batches (id, drive_folder_id, status)
                VALUES (:id, 'shared-intake-folder', 'DRAFT')
                """)
                .param("id", UUID.randomUUID())
                .update();

        Long batches = jdbc.sql("""
                SELECT count(*) FROM student.photo_import_batches
                WHERE drive_folder_id = 'shared-intake-folder'
                """).query(Long.class).single();
        assertThat(batches).isEqualTo(3);
    }

    @Test
    void cropCoordinatesDefaultToCenterAndAreDatabaseConstrained() {
        UUID rowId = UUID.randomUUID();
        jdbc.sql("INSERT INTO student.photo_import_rows (id) VALUES (:id)")
                .param("id", rowId)
                .update();

        var crop = jdbc.sql("""
                SELECT crop_x, crop_y, manually_reviewed
                FROM student.photo_import_rows WHERE id = :id
                """)
                .param("id", rowId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getBigDecimal("crop_x"),
                        rs.getBigDecimal("crop_y"),
                        rs.getBoolean("manually_reviewed")})
                .single();
        assertThat(crop[0].toString()).isEqualTo("0.5000");
        assertThat(crop[1].toString()).isEqualTo("0.5000");
        assertThat(crop[2]).isEqualTo(false);

        assertThatThrownBy(() -> jdbc.sql(
                        "UPDATE student.photo_import_rows SET crop_x = 1.01 WHERE id = :id")
                .param("id", rowId)
                .update())
                .hasMessageContaining("chk_photo_import_row_crop_x");
    }

    @Test
    void recoveryAuditIsVersionedPerAppliedRowAndConstrained() {
        UUID batchId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO student.photo_import_batches
                    (id, drive_folder_id, status, school_id)
                VALUES (:id, 'recovery-folder', 'COMPLETED', 1)
                """)
                .param("id", batchId)
                .update();
        jdbc.sql("""
                INSERT INTO student.photo_import_rows (id, batch_id, school_id)
                VALUES (:id, :batchId, 1)
                """)
                .param("id", rowId)
                .param("batchId", batchId)
                .update();
        jdbc.sql("""
                INSERT INTO student.photo_import_recoveries
                    (id, row_id, batch_id, school_id, student_id, recovery_version, status,
                     drive_file_id, prior_photo_key)
                VALUES (:id, :rowId, :batchId, 1, 101, 'fit-without-crop-v1', 'COMPLETED',
                        'drive-file-1', 'cropped-key')
                """)
                .param("id", UUID.randomUUID())
                .param("rowId", rowId)
                .param("batchId", batchId)
                .update();

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO student.photo_import_recoveries
                            (id, row_id, batch_id, school_id, student_id, recovery_version, status,
                             drive_file_id)
                        VALUES (:id, :rowId, :batchId, 1, 101, 'fit-without-crop-v1', 'COMPLETED',
                                'drive-file-1')
                        """)
                .param("id", UUID.randomUUID())
                .param("rowId", rowId)
                .param("batchId", batchId)
                .update())
                .hasMessageContaining("uq_photo_import_recovery_version");

        UUID protectedRowId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO student.photo_import_rows (id, batch_id, school_id)
                VALUES (:id, :batchId, 1)
                """)
                .param("id", protectedRowId)
                .param("batchId", batchId)
                .update();
        jdbc.sql("""
                INSERT INTO student.photo_import_recoveries
                    (id, row_id, batch_id, school_id, student_id, recovery_version, status,
                     drive_file_id, message)
                VALUES (:id, :rowId, :batchId, 1, 102, 'fit-without-crop-v1', 'PROTECTED',
                        'drive-file-2', 'Student photo changed after this import')
                """)
                .param("id", UUID.randomUUID())
                .param("rowId", protectedRowId)
                .param("batchId", batchId)
                .update();
        assertThat(jdbc.sql("""
                SELECT status FROM student.photo_import_recoveries WHERE row_id = :rowId
                """)
                .param("rowId", protectedRowId)
                .query(String.class)
                .single()).isEqualTo("PROTECTED");
    }

    @Test
    void sha256EvidenceIsNullableForHistoryButStrictlyConstrainedWhenPresent() {
        UUID sourceId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO student.photo_import_sources (id, checksum, sha256_checksum)
                VALUES (:id, 'legacy-md5', :sha256)
                """)
                .param("id", sourceId)
                .param("sha256", "a".repeat(64))
                .update();

        assertThat(jdbc.sql("""
                SELECT sha256_checksum FROM student.photo_import_sources WHERE id = :id
                """)
                .param("id", sourceId)
                .query(String.class)
                .single()).isEqualTo("a".repeat(64));

        jdbc.sql("""
                INSERT INTO student.photo_import_sources (id, checksum, sha256_checksum)
                VALUES (:id, 'historical-md5', NULL)
                """)
                .param("id", UUID.randomUUID())
                .update();

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO student.photo_import_sources (id, checksum, sha256_checksum)
                        VALUES (:id, 'legacy-md5', 'NOT-A-SHA256')
                        """)
                .param("id", UUID.randomUUID())
                .update())
                .hasMessageContaining("chk_photo_import_source_sha256");
    }

    @Test
    void driveRevisionEvidenceCanBeStoredWhenDriveOmitsSha256() {
        UUID sourceId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO student.photo_import_sources
                    (id, checksum, sha256_checksum, drive_head_revision_id, drive_version)
                VALUES (:id, 'legacy-md5', NULL, 'head-revision-1', '15')
                """)
                .param("id", sourceId)
                .update();

        assertThat(jdbc.sql("""
                SELECT drive_head_revision_id || ':' || drive_version
                FROM student.photo_import_sources WHERE id = :id
                """)
                .param("id", sourceId)
                .query(String.class)
                .single()).isEqualTo("head-revision-1:15");
    }
}
