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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
    static UUID protectedBatchId;
    static UUID protectedRowId;

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
        protectedBatchId = UUID.randomUUID();
        protectedRowId = UUID.randomUUID();
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
                    INSERT INTO student.students
                        (id, admission_no, full_name, school_id, class_id, section_id,
                         academic_year_id, photo_url)
                    VALUES (302, 'REC-2', 'Protected Recovery Student', 201, '9', 'section-recovery',
                            'ay-recovery', 'newer-manual-photo-key')
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
                    INSERT INTO student.photo_import_batches
                        (id, school_id, school_uid, academic_year_id, drive_folder_id,
                         drive_folder_name, status, snapshot_hash)
                    SELECT '%s', id, school_uid, 'ay-recovery', 'protected-recovery-folder',
                           'Protected recovery originals', 'COMPLETED', 'snapshot-2'
                    FROM tenant_school.schools WHERE id = 201
                    """.formatted(protectedBatchId));
            sql.execute("""
                    INSERT INTO student.photo_import_sources
                        (id, batch_id, school_id, drive_file_id, file_name, mime_type,
                         byte_size, checksum, sha256_checksum, modified_time, source_type, image_no)
                    VALUES (gen_random_uuid(), '%s', 201, 'drive-photo-1', 'DSC5001.jpg',
                            'image/jpeg', 3, '5289df737df57326fcdd22597afb1fac',
                            'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                            '2026-07-31T00:00:00Z', 'IMAGE', '5001')
                    """.formatted(batchId));
            sql.execute("""
                    INSERT INTO student.photo_import_rows
                        (id, batch_id, school_id, excel_row, admission_no, image_no,
                         drive_file_id, drive_file_name, student_id, status, final_photo_key,
                         source_checksum, source_sha256, applied_at)
                    VALUES ('%s', '%s', 201, 2, 'REC-1', '5001', 'drive-photo-1',
                            'DSC5001.jpg', 301, 'APPLIED', 'cropped-photo-key',
                            '5289df737df57326fcdd22597afb1fac',
                            'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', now())
                    """.formatted(rowId, batchId));
            sql.execute("""
                    INSERT INTO student.photo_import_rows
                        (id, batch_id, school_id, excel_row, admission_no, image_no,
                         drive_file_id, drive_file_name, student_id, status, final_photo_key,
                         source_checksum, applied_at)
                    VALUES ('%s', '%s', 201, 2, 'REC-2', '5002', 'drive-photo-2',
                            'DSC5002.jpg', 302, 'APPLIED', 'import-photo-key',
                            'checksum-2', now())
                    """.formatted(protectedRowId, protectedBatchId));
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
        String campaignId = UUID.randomUUID().toString();
        String reviewItemId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO student.student_review_campaigns
                    (id, school_id, academic_year_id, review_type, title, status)
                VALUES (:campaignId, 201, 'ay-recovery', 'PHOTO_VERIFICATION',
                        'Recovery photo verification', 'ACTIVE')
                """).param("campaignId", campaignId).update();
        jdbc.sql("""
                INSERT INTO student.student_review_items
                    (id, campaign_id, student_id, school_id, status, verified_photo,
                     verified_full_name, current_full_name, suggested_full_name,
                     correction_requested, correction_notes, completed_at)
                VALUES (:itemId, :campaignId, 301, 201, 'COMPLETED', true,
                        true, 'Recovery Student', 'Candidate Name', true,
                        'Old correction', now())
                """)
                .param("itemId", reviewItemId)
                .param("campaignId", campaignId)
                .update();
        byte[] normalized = new byte[]{9, 8, 7};
        when(storage.uploadNormalizedPortrait(
                anyString(), eq(301L), same(normalized)))
                .thenReturn("uncropped-photo-key");

        var claimed = repository.beginPhotoRecovery(batchId, 201L, rowId, version, 1L);
        assertThat(claimed.status()).isEqualTo("READY");
        assertThat(claimed.target().sourceSha256())
                .isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        var inProgress = repository.beginPhotoRecovery(batchId, 201L, rowId, version, 1L);
        assertThat(inProgress.status()).isEqualTo("IN_PROGRESS");

        var completed = repository.completePhotoRecovery(claimed, version, normalized);
        assertThat(completed.status()).isEqualTo("RECOVERED");
        assertThat(completed.photoKey()).isEqualTo("uncropped-photo-key");

        var progress = repository.photoRecoveryProgress(batchId, 201L, version);
        assertThat(progress.totalCount()).isEqualTo(1);
        assertThat(progress.processedCount()).isEqualTo(1);
        assertThat(progress.recoveredCount()).isEqualTo(1);
        assertThat(progress.protectedCount()).isZero();
        assertThat(progress.pendingCount()).isZero();
        assertThat(progress.percentComplete()).isEqualTo(100);
        assertThat(progress.resumable()).isFalse();

        var repeated = repository.beginPhotoRecovery(batchId, 201L, rowId, version, 1L);
        assertThat(repeated.status()).isEqualTo("ALREADY_RECOVERED");
        assertThat(repeated.target().finalPhotoKey()).isEqualTo("uncropped-photo-key");

        assertThat(jdbc.sql("SELECT photo_url FROM student.students WHERE id = 301")
                .query(String.class).single()).isEqualTo("uncropped-photo-key");
        var audit = jdbc.sql("""
                SELECT status, prior_photo_key, recovered_photo_key, source_sha256, attempt_count
                FROM student.photo_import_recoveries
                WHERE row_id = :rowId AND recovery_version = :version
                """)
                .param("rowId", rowId)
                .param("version", version)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("status"), rs.getString("prior_photo_key"),
                        rs.getString("recovered_photo_key"), rs.getString("source_sha256"),
                        rs.getInt("attempt_count")})
                .single();
        assertThat(audit).containsExactly(
                "COMPLETED", "cropped-photo-key", "uncropped-photo-key",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 1);
        assertThat(jdbc.sql("""
                        SELECT status || '|' || verified_photo || '|' || verified_full_name || '|' ||
                               correction_requested || '|' || (correction_notes IS NULL) || '|' ||
                               (suggested_full_name IS NULL) || '|' || (completed_at IS NULL)
                        FROM student.student_review_items
                        WHERE id = :itemId
                        """)
                .param("itemId", reviewItemId)
                .query(String.class)
                .single()).isEqualTo("PENDING|false|true|false|true|true|true");
        verify(outbox).appendAll(argThat(events -> events.size() == 1
                && "student-review-item.upserted.v1".equals(events.getFirst().eventType())
                && reviewItemId.equals(events.getFirst().aggregateId())
                && "PENDING".equals(events.getFirst().payload().get("status"))));
        verify(outbox).append(
                org.mockito.ArgumentMatchers.eq("student.photo-recovered.v1"),
                org.mockito.ArgumentMatchers.eq("student.photo-recovered.v1:" + rowId + ":" + version),
                org.mockito.ArgumentMatchers.eq("student"),
                org.mockito.ArgumentMatchers.eq("301"),
                org.mockito.ArgumentMatchers.eq(201L),
                org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void newerStudentPhotoIsDurablyClassifiedAsProtectedWithoutRepeatedAttempts() {
        TenantContext.set(new TenantContext(1L, "superadmin@example.com", "SUPERADMIN", null, null,
                Set.of(), Set.of()));
        String version = "fit-without-crop-v1";

        var protectedResult = repository.beginPhotoRecovery(
                protectedBatchId, 201L, protectedRowId, version, 1L);
        assertThat(protectedResult.status()).isEqualTo("PROTECTED");
        assertThat(protectedResult.message()).contains("did not overwrite");

        var repeated = repository.beginPhotoRecovery(
                protectedBatchId, 201L, protectedRowId, version, 1L);
        assertThat(repeated.status()).isEqualTo("PROTECTED");

        var audit = jdbc.sql("""
                SELECT status, attempt_count, completed_at IS NOT NULL AS completed
                FROM student.photo_import_recoveries
                WHERE row_id = :rowId AND recovery_version = :version
                """)
                .param("rowId", protectedRowId)
                .param("version", version)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("status"), rs.getInt("attempt_count"), rs.getBoolean("completed")})
                .single();
        assertThat(audit).containsExactly("PROTECTED", 1, true);

        var progress = repository.photoRecoveryProgress(protectedBatchId, 201L, version);
        assertThat(progress.protectedCount()).isEqualTo(1);
        assertThat(progress.failedCount()).isZero();
        assertThat(progress.percentComplete()).isEqualTo(100);
        assertThat(jdbc.sql("SELECT photo_url FROM student.students WHERE id = 302")
                .query(String.class).single()).isEqualTo("newer-manual-photo-key");
    }

    @Test
    void applyPhotoUsesUnifiedPhotoReviewInvalidationAndOutboxEvent() {
        TenantContext.set(new TenantContext(1L, "superadmin@example.com", "SUPERADMIN", null, null,
                Set.of(), Set.of()));
        UUID applyBatchId = UUID.randomUUID();
        UUID applyRowId = UUID.randomUUID();
        String campaignId = UUID.randomUUID().toString();
        String reviewItemId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO student.students
                    (id, admission_no, full_name, school_id, class_id, section_id,
                     academic_year_id, photo_url)
                VALUES (303, 'REC-3', 'Imported Photo Student', 201, '9', 'section-recovery',
                        'ay-recovery', 'batch-old-photo')
                """).update();
        jdbc.sql("""
                INSERT INTO student.photo_import_batches
                    (id, school_id, school_uid, academic_year_id, drive_folder_id,
                     drive_folder_name, status, snapshot_hash)
                SELECT :batchId, id, school_uid, 'ay-recovery', 'apply-folder',
                       'Apply originals', 'EXECUTING', 'apply-snapshot'
                FROM tenant_school.schools WHERE id = 201
                """).param("batchId", applyBatchId).update();
        jdbc.sql("""
                INSERT INTO student.photo_import_rows
                    (id, batch_id, school_id, excel_row, admission_no, image_no,
                     drive_file_id, drive_file_name, student_id, status, prior_photo_key,
                     source_checksum)
                VALUES (:rowId, :batchId, 201, 2, 'REC-3', '5003', 'drive-photo-3',
                        'batch-photo.jpg', 303, 'READY', 'batch-old-photo', 'checksum-3')
                """)
                .param("rowId", applyRowId)
                .param("batchId", applyBatchId)
                .update();
        jdbc.sql("""
                INSERT INTO student.student_review_campaigns
                    (id, school_id, academic_year_id, review_type, title, status)
                VALUES (:campaignId, 201, 'ay-recovery', 'PHOTO_VERIFICATION',
                        'Photo verification', 'ACTIVE')
                """).param("campaignId", campaignId).update();
        jdbc.sql("""
                INSERT INTO student.student_review_items
                    (id, campaign_id, student_id, school_id, status, verified_photo,
                     verified_full_name, current_full_name, suggested_full_name,
                     correction_requested, correction_notes, completed_at)
                VALUES (:itemId, :campaignId, 303, 201, 'COMPLETED', true,
                        true, 'Imported Photo Student', 'Candidate Name', true,
                        'Old correction', now())
                """)
                .param("itemId", reviewItemId)
                .param("campaignId", campaignId)
                .update();

        byte[] source = new byte[]{1, 2, 3};
        byte[] normalized = new byte[]{4, 5, 6};
        when(storage.uploadTemporaryPhotoImportFile(
                anyString(), anyString(), same(source), eq("image/jpeg"), eq("batch-photo.jpg")))
                .thenReturn("source-object-key");
        when(storage.uploadNormalizedPortrait(anyString(), eq(303L), same(normalized)))
                .thenReturn("normalized-photo-key");

        PhotoImportRepository.Batch batch = repository.batch(applyBatchId, 201L);
        PhotoImportRepository.ImportRow row = repository.rows(applyBatchId, 201L).getFirst();
        assertThat(repository.applyPhoto(batch, row, source, "image/jpeg", normalized))
                .isEqualTo("normalized-photo-key");

        Map<String, Object> review = jdbc.sql("""
                        SELECT status, verified_photo, verified_full_name, current_full_name,
                               correction_requested, correction_notes, suggested_full_name,
                               completed_at
                        FROM student.student_review_items
                        WHERE id = :itemId
                        """)
                .param("itemId", reviewItemId)
                .query((rs, rowNum) -> {
                    Map<String, Object> values = new java.util.LinkedHashMap<>();
                    values.put("status", rs.getString("status"));
                    values.put("verifiedPhoto", rs.getBoolean("verified_photo"));
                    values.put("verifiedFullName", rs.getBoolean("verified_full_name"));
                    values.put("currentFullName", rs.getString("current_full_name"));
                    values.put("correctionRequested", rs.getBoolean("correction_requested"));
                    values.put("correctionNotes", rs.getString("correction_notes"));
                    values.put("suggestedFullName", rs.getString("suggested_full_name"));
                    values.put("completed", rs.getObject("completed_at") != null);
                    return values;
                })
                .single();
        assertThat(review)
                .containsEntry("status", "PENDING")
                .containsEntry("verifiedPhoto", false)
                .containsEntry("verifiedFullName", true)
                .containsEntry("currentFullName", "Imported Photo Student")
                .containsEntry("correctionRequested", false)
                .containsEntry("correctionNotes", null)
                .containsEntry("suggestedFullName", null)
                .containsEntry("completed", false);
        verify(outbox).appendAll(argThat(events -> events.size() == 1
                && "student-review-item.upserted.v1".equals(events.getFirst().eventType())
                && reviewItemId.equals(events.getFirst().aggregateId())
                && "PENDING".equals(events.getFirst().payload().get("status"))));
    }
}
