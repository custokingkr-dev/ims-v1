package com.custoking.ims.schoolcoreservice.photoimport;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;

@Testcontainers(disabledWithoutDocker = true)
class PhotoImportRecoveryRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16")
            .withUsername("owner")
            .withPassword("owner");

    static HikariDataSource dataSource;
    static JdbcClient jdbc;
    static StudentPhotoStorage storage;
    static OutboxWriter outbox;
    static PhotoImportRepository repository;
    static UUID batchId;
    static UUID rowId;

    @BeforeAll
    static void setUp() throws Exception {
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

        batchId = UUID.randomUUID();
        rowId = UUID.randomUUID();
        try (Connection connection = java.sql.DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement sql = connection.createStatement()) {
            sql.execute("""
                    INSERT INTO tenant_school.schools
                        (id, name, short_code, active, created_at, configured_class_count, configured_section_count)
                    VALUES (201, 'Recovery School', 'REC', true, now(), 12, 1)
                    """);
            sql.execute("INSERT INTO tenant_school.academic_years (id, label, active) VALUES ('ay-recovery', '2026-27', true)");
            sql.execute("""
                    INSERT INTO tenant_school.school_sections
                        (id, name, active, school_class_id, school_id)
                    VALUES ('section-recovery', 'A', true, '9', 201)
                    """);
            sql.execute("""
                    INSERT INTO student.students
                        (id, admission_no, full_name, school_id, class_id, section_id,
                         academic_year_id, photo_url)
                    VALUES (301, 'REC-1', 'Recovery Student', 201, '9', 'section-recovery',
                            'ay-recovery', 'cropped-photo-key')
                    """);
            sql.execute("""
                    INSERT INTO student.photo_import_batches
                        (id, school_id, school_uid, academic_year_id, drive_folder_id,
                         drive_folder_name, status, snapshot_hash)
                    SELECT '%s', id, school_uid, 'ay-recovery', 'recovery-folder',
                           'Recovery originals', 'COMPLETED', 'snapshot-1'
                    FROM tenant_school.schools WHERE id = 201
                    """.formatted(batchId));
            sql.execute("""
                    INSERT INTO student.photo_import_sources
                        (id, batch_id, school_id, drive_file_id, file_name, mime_type,
                         byte_size, checksum, modified_time, source_type, image_no)
                    VALUES (gen_random_uuid(), '%s', 201, 'drive-photo-1', 'DSC5001.jpg',
                            'image/jpeg', 3, '5289df737df57326fcdd22597afb1fac',
                            '2026-07-31T00:00:00Z', 'IMAGE', '5001')
                    """.formatted(batchId));
            sql.execute("""
                    INSERT INTO student.photo_import_rows
                        (id, batch_id, school_id, excel_row, admission_no, image_no,
                         drive_file_id, drive_file_name, student_id, status, final_photo_key,
                         source_checksum, applied_at)
                    VALUES ('%s', '%s', 201, 2, 'REC-1', '5001', 'drive-photo-1',
                            'DSC5001.jpg', 301, 'APPLIED', 'cropped-photo-key',
                            '5289df737df57326fcdd22597afb1fac', now())
                    """.formatted(rowId, batchId));
        }

        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(PG.getJdbcUrl());
        dataSource.setUsername(PG.getUsername());
        dataSource.setPassword(PG.getPassword());
        dataSource.setMaximumPoolSize(2);
        jdbc = JdbcClient.create(dataSource);
        storage = mock(StudentPhotoStorage.class);
        outbox = mock(OutboxWriter.class);
        repository = new PhotoImportRepository(jdbc, storage, outbox);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @AfterAll
    static void closeDataSource() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void recoveryClaimCompletionAndRepeatAreAtomicAuditedAndIdempotent() {
        TenantContext.set(new TenantContext(1L, "superadmin@example.com", "SUPERADMIN", null, null,
                Set.of(), Set.of()));
        String version = "fit-without-crop-v1";
        byte[] normalized = new byte[]{9, 8, 7};
        when(storage.uploadNormalizedPortrait(
                anyString(), eq(301L), same(normalized)))
                .thenReturn("uncropped-photo-key");

        var claimed = repository.beginPhotoRecovery(batchId, 201L, rowId, version, 1L);
        assertThat(claimed.status()).isEqualTo("READY");

        var inProgress = repository.beginPhotoRecovery(batchId, 201L, rowId, version, 1L);
        assertThat(inProgress.status()).isEqualTo("IN_PROGRESS");

        var completed = repository.completePhotoRecovery(claimed, version, normalized);
        assertThat(completed.status()).isEqualTo("RECOVERED");
        assertThat(completed.photoKey()).isEqualTo("uncropped-photo-key");

        var repeated = repository.beginPhotoRecovery(batchId, 201L, rowId, version, 1L);
        assertThat(repeated.status()).isEqualTo("ALREADY_RECOVERED");
        assertThat(repeated.target().finalPhotoKey()).isEqualTo("uncropped-photo-key");

        assertThat(jdbc.sql("SELECT photo_url FROM student.students WHERE id = 301")
                .query(String.class).single()).isEqualTo("uncropped-photo-key");
        var audit = jdbc.sql("""
                SELECT status, prior_photo_key, recovered_photo_key, attempt_count
                FROM student.photo_import_recoveries
                WHERE row_id = :rowId AND recovery_version = :version
                """)
                .param("rowId", rowId)
                .param("version", version)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("status"), rs.getString("prior_photo_key"),
                        rs.getString("recovered_photo_key"), rs.getInt("attempt_count")})
                .single();
        assertThat(audit).containsExactly("COMPLETED", "cropped-photo-key", "uncropped-photo-key", 1);
        verify(outbox).append(
                org.mockito.ArgumentMatchers.eq("student.photo-recovered.v1"),
                org.mockito.ArgumentMatchers.eq("student.photo-recovered.v1:" + rowId + ":" + version),
                org.mockito.ArgumentMatchers.eq("student"),
                org.mockito.ArgumentMatchers.eq("301"),
                org.mockito.ArgumentMatchers.eq(201L),
                org.mockito.ArgumentMatchers.anyMap());
    }
}
