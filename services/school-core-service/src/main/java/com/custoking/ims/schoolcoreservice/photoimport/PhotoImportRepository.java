package com.custoking.ims.schoolcoreservice.photoimport;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.persistence.AcademicCalendarAccess;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class PhotoImportRepository {
    private final JdbcClient jdbc;
    private final StudentPhotoStorage photoStorage;
    private final OutboxWriter outbox;

    public PhotoImportRepository(JdbcClient jdbc, StudentPhotoStorage photoStorage, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.photoStorage = photoStorage;
        this.outbox = outbox;
    }

    @Transactional
    public List<SchoolContext> allowedSchools() {
        bypassRls();
        TenantContext context = TenantContext.get();
        Set<Long> allowed = context.isSuperAdmin() ? Set.of() : context.operatorSchools();
        List<SchoolBase> schools = jdbc.sql("""
                SELECT id, school_uid::text AS school_uid, name, short_code
                FROM tenant_school.schools
                WHERE active = true
                ORDER BY name
                """)
                .query((rs, rowNum) -> new SchoolBase(
                        rs.getLong("id"),
                        rs.getString("school_uid"),
                        rs.getString("name"),
                        rs.getString("short_code")))
                .list();
        List<SchoolContext> result = new ArrayList<>();
        for (SchoolBase school : schools) {
            if (!context.isSuperAdmin() && !allowed.contains(school.id())) {
                continue;
            }
            var year = AcademicCalendarAccess.currentAcademicYear(jdbc, school.id());
            result.add(new SchoolContext(school.id(), school.schoolUid(), school.name(),
                    school.shortCode(), year.id(), year.label()));
        }
        return result;
    }

    @Transactional
    public Batch createBatch(long schoolId, String academicYearId, String folderId,
                             String folderName, Long createdBy) {
        selectSchoolScope(schoolId);
        SchoolBase school = jdbc.sql("""
                SELECT id, school_uid::text AS school_uid, name, short_code
                FROM tenant_school.schools
                WHERE id = :schoolId AND active = true
                """)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> new SchoolBase(
                        rs.getLong("id"), rs.getString("school_uid"),
                        rs.getString("name"), rs.getString("short_code")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Active school not found"));
        var currentYear = AcademicCalendarAccess.currentAcademicYear(jdbc, schoolId);
        if (!currentYear.id().equals(academicYearId)) {
            throw new IllegalArgumentException("Photo import is restricted to the school's current academic year "
                    + currentYear.label());
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO student.photo_import_batches
                    (id, school_id, school_uid, academic_year_id, drive_folder_id,
                     drive_folder_name, status, created_by)
                VALUES
                    (:id, :schoolId, :schoolUid::uuid, :academicYearId, :folderId,
                     :folderName, 'DRAFT', :createdBy)
                """)
                .param("id", id)
                .param("schoolId", schoolId)
                .param("schoolUid", school.schoolUid())
                .param("academicYearId", academicYearId)
                .param("folderId", folderId)
                .param("folderName", folderName)
                .param("createdBy", createdBy)
                .update();
        for (String header : List.of("AdmissionNo", "Name", "Class", "Section", "ImageNo")) {
            jdbc.sql("""
                    INSERT INTO student.photo_import_column_mappings
                        (id, batch_id, school_id, source_header, canonical_field, required)
                    VALUES (:id, :batchId, :schoolId, :header, :field, :required)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("batchId", id)
                    .param("schoolId", schoolId)
                    .param("header", header)
                    .param("field", header.substring(0, 1).toLowerCase() + header.substring(1))
                    .param("required", !"ImageNo".equals(header))
                    .update();
        }
        return batch(id, schoolId);
    }

    @Transactional(readOnly = true)
    public Long batchSchoolId(UUID id) {
        bypassRls();
        return jdbc.sql("SELECT school_id FROM student.photo_import_batches WHERE id = :id")
                .param("id", id)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Photo import batch not found"));
    }

    @Transactional(readOnly = true)
    public Batch batch(UUID id, long schoolId) {
        selectSchoolScope(schoolId);
        return jdbc.sql(batchSelect() + " WHERE b.id = :id AND b.school_id = :schoolId")
                .param("id", id)
                .param("schoolId", schoolId)
                .query(this::mapBatch)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Photo import batch not found"));
    }

    @Transactional(readOnly = true)
    public List<Batch> listBatches(long schoolId) {
        selectSchoolScope(schoolId);
        return jdbc.sql(batchSelect() + """
                 WHERE b.school_id = :schoolId
                 ORDER BY b.created_at DESC
                 LIMIT 50
                """)
                .param("schoolId", schoolId)
                .query(this::mapBatch)
                .list();
    }

    @Transactional(readOnly = true)
    public boolean studentsModuleEnabled(long schoolId) {
        selectSchoolScope(schoolId);
        Long count = jdbc.sql("""
                SELECT count(*)
                FROM tenant_school.school_module_entitlements
                WHERE school_id = :schoolId
                  AND module_code IN ('STUDENTS', 'ERP')
                  AND enabled = true
                  AND (start_date IS NULL OR start_date <= current_date)
                  AND (end_date IS NULL OR end_date >= current_date)
                """)
                .param("schoolId", schoolId)
                .query(Long.class)
                .single();
        return count != null && count > 0;
    }

    @Transactional
    public Batch replaceScan(UUID batchId, long schoolId, String workbookFileId,
                             String workbookFileName, String snapshotHash,
                             List<SourceInput> sources, List<RowInput> rows) {
        selectSchoolScope(schoolId);
        Batch current = batchInTransaction(batchId, schoolId);
        if (!Set.of("DRAFT", "REVIEW").contains(current.status())) {
            throw new IllegalArgumentException("Only draft or review batches can be scanned");
        }
        jdbc.sql("DELETE FROM student.photo_import_rows WHERE batch_id = :batchId")
                .param("batchId", batchId).update();
        jdbc.sql("DELETE FROM student.photo_import_sources WHERE batch_id = :batchId")
                .param("batchId", batchId).update();
        for (SourceInput source : sources) {
            jdbc.sql("""
                    INSERT INTO student.photo_import_sources
                        (id, batch_id, school_id, drive_file_id, file_name, mime_type,
                         byte_size, checksum, modified_time, source_type, image_no)
                    VALUES
                        (:id, :batchId, :schoolId, :driveFileId, :fileName, :mimeType,
                         :byteSize, :checksum, :modifiedTime, :sourceType, :imageNo)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("batchId", batchId)
                    .param("schoolId", schoolId)
                    .param("driveFileId", source.driveFileId())
                    .param("fileName", source.fileName())
                    .param("mimeType", source.mimeType())
                    .param("byteSize", source.byteSize())
                    .param("checksum", source.checksum())
                    .param("modifiedTime", source.modifiedTime())
                    .param("sourceType", source.sourceType())
                    .param("imageNo", source.imageNo())
                    .update();
        }
        int ready = 0;
        int held = 0;
        int errors = 0;
        for (RowInput row : rows) {
            if ("READY".equals(row.status())) ready++;
            if ("HELD".equals(row.status())) held++;
            if ("ERROR".equals(row.status())) errors++;
            jdbc.sql("""
                    INSERT INTO student.photo_import_rows
                        (id, batch_id, school_id, excel_row, admission_no, workbook_name,
                         class_name, section_name, image_no, drive_file_id, drive_file_name,
                         student_id, status, message, prior_photo_key, source_checksum)
                    VALUES
                        (:id, :batchId, :schoolId, :excelRow, :admissionNo, :workbookName,
                         :className, :sectionName, :imageNo, :driveFileId, :driveFileName,
                         :studentId, :status, :message, :priorPhotoKey, :sourceChecksum)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("batchId", batchId)
                    .param("schoolId", schoolId)
                    .param("excelRow", row.excelRow())
                    .param("admissionNo", row.admissionNo())
                    .param("workbookName", row.workbookName())
                    .param("className", row.className())
                    .param("sectionName", row.sectionName())
                    .param("imageNo", row.imageNo())
                    .param("driveFileId", row.driveFileId())
                    .param("driveFileName", row.driveFileName())
                    .param("studentId", row.studentId())
                    .param("status", row.status())
                    .param("message", row.message())
                    .param("priorPhotoKey", row.priorPhotoKey())
                    .param("sourceChecksum", row.sourceChecksum())
                    .update();
        }
        jdbc.sql("""
                UPDATE student.photo_import_batches
                SET workbook_file_id = :workbookFileId,
                    workbook_file_name = :workbookFileName,
                    snapshot_hash = :snapshotHash,
                    status = 'REVIEW',
                    total_rows = :totalRows,
                    ready_count = :ready,
                    held_count = :held,
                    error_count = :errors,
                    applied_count = 0,
                    failed_count = 0,
                    scanned_at = now(),
                    updated_at = now(),
                    version = version + 1
                WHERE id = :batchId AND school_id = :schoolId
                """)
                .param("workbookFileId", workbookFileId)
                .param("workbookFileName", workbookFileName)
                .param("snapshotHash", snapshotHash)
                .param("totalRows", rows.size())
                .param("ready", ready)
                .param("held", held)
                .param("errors", errors)
                .param("batchId", batchId)
                .param("schoolId", schoolId)
                .update();
        return batchInTransaction(batchId, schoolId);
    }

    @Transactional(readOnly = true)
    public List<ImportRow> rows(UUID batchId, long schoolId) {
        selectSchoolScope(schoolId);
        return jdbc.sql("""
                SELECT id, batch_id, school_id, excel_row, admission_no, workbook_name,
                       class_name, section_name, image_no, drive_file_id, drive_file_name,
                       student_id, status, message, prior_photo_key, final_photo_key,
                       source_checksum, applied_at
                FROM student.photo_import_rows
                WHERE batch_id = :batchId AND school_id = :schoolId
                ORDER BY excel_row
                """)
                .param("batchId", batchId)
                .param("schoolId", schoolId)
                .query(this::mapRow)
                .list();
    }

    @Transactional(readOnly = true)
    public Optional<StudentMatch> studentByAdmission(long schoolId, String academicYearId, String admissionNo) {
        selectSchoolScope(schoolId);
        return jdbc.sql("""
                SELECT st.id, st.admission_no, st.full_name, st.photo_url,
                       sc.name AS class_name, sc.sort_order AS class_sort_order,
                       ss.name AS section_name
                FROM student.students st
                LEFT JOIN tenant_school.school_classes sc ON sc.id = st.class_id
                LEFT JOIN tenant_school.school_sections ss ON ss.id = st.section_id
                WHERE st.school_id = :schoolId
                  AND st.academic_year_id = :academicYearId
                  AND lower(trim(st.admission_no)) = lower(trim(:admissionNo))
                  AND st.deleted_at IS NULL
                """)
                .param("schoolId", schoolId)
                .param("academicYearId", academicYearId)
                .param("admissionNo", admissionNo)
                .query((rs, rowNum) -> new StudentMatch(
                        rs.getLong("id"),
                        rs.getString("admission_no"),
                        rs.getString("full_name"),
                        rs.getString("class_name"),
                        (Integer) rs.getObject("class_sort_order"),
                        rs.getString("section_name"),
                        rs.getString("photo_url")))
                .optional();
    }

    @Transactional
    public Batch freeze(UUID id, long schoolId, String snapshotHash) {
        selectSchoolScope(schoolId);
        int updated = jdbc.sql("""
                UPDATE student.photo_import_batches
                SET status = 'FROZEN', frozen_at = now(), updated_at = now(), version = version + 1
                WHERE id = :id AND school_id = :schoolId AND status = 'REVIEW'
                  AND snapshot_hash = :snapshotHash AND ready_count > 0 AND error_count = 0
                """)
                .param("id", id)
                .param("schoolId", schoolId)
                .param("snapshotHash", snapshotHash)
                .update();
        if (updated != 1) {
            throw new IllegalArgumentException(
                    "Batch cannot be frozen: resolve errors, rescan changed files, or ensure at least one row is ready");
        }
        return batchInTransaction(id, schoolId);
    }

    @Transactional
    public Batch startExecution(UUID id, long schoolId, Long userId) {
        selectSchoolScope(schoolId);
        Batch current = batchInTransaction(id, schoolId);
        if (!Set.of("FROZEN", "EXECUTING", "PARTIAL", "FAILED").contains(current.status())) {
            throw new IllegalArgumentException("Only a frozen, executing or retryable batch can be executed");
        }
        if (Set.of("PARTIAL", "FAILED").contains(current.status())) {
            jdbc.sql("""
                UPDATE student.photo_import_rows
                SET status = 'READY', message = 'Retry queued', updated_at = now()
                WHERE batch_id = :id AND school_id = :schoolId AND status = 'FAILED'
                """)
                    .param("id", id)
                    .param("schoolId", schoolId)
                    .update();
        }
        jdbc.sql("""
                UPDATE student.photo_import_batches
                SET status = 'EXECUTING',
                    executed_by = COALESCE(executed_by, :userId),
                    ready_count = (
                        SELECT count(*) FROM student.photo_import_rows
                        WHERE batch_id = :id AND school_id = :schoolId AND status = 'READY'
                    ),
                    failed_count = 0,
                    updated_at = now(),
                    version = version + 1
                WHERE id = :id AND school_id = :schoolId
                  AND status IN ('FROZEN', 'EXECUTING', 'PARTIAL', 'FAILED')
                """)
                .param("id", id)
                .param("schoolId", schoolId)
                .param("userId", userId)
                .update();
        return batchInTransaction(id, schoolId);
    }

    @Transactional
    public String applyPhoto(Batch batch, ImportRow row, byte[] data, String contentType) {
        selectSchoolScope(batch.schoolId());
        ImportRow currentRow = rowInTransaction(row.id(), batch.schoolId());
        if ("APPLIED".equals(currentRow.status())) {
            return currentRow.finalPhotoKey();
        }
        if (!"READY".equals(currentRow.status())) {
            throw new IllegalArgumentException("The import row is not ready");
        }
        StudentState student = jdbc.sql("""
                SELECT photo_url, academic_year_id
                FROM student.students
                WHERE id = :studentId AND school_id = :schoolId AND deleted_at IS NULL
                FOR UPDATE
                """)
                .param("studentId", currentRow.studentId())
                .param("schoolId", batch.schoolId())
                .query((rs, rowNum) -> new StudentState(
                        rs.getString("photo_url"), rs.getString("academic_year_id")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Student is no longer active"));
        if (!batch.academicYearId().equals(student.academicYearId())) {
            throw new IllegalArgumentException("Student academic year changed after review");
        }
        if (!Objects.equals(currentRow.priorPhotoKey(), student.photoKey())) {
            throw new IllegalArgumentException("Student photo changed after review; rescan before overwriting");
        }
        String key = photoStorage.upload(batch.schoolUid(), currentRow.studentId(), data, contentType);
        jdbc.sql("""
                UPDATE student.students
                SET photo_url = :key, updated_at = now(), updated_by = :updatedBy, version = version + 1
                WHERE id = :studentId AND school_id = :schoolId
                """)
                .param("key", key)
                .param("updatedBy", String.valueOf(TenantContext.get().userId()))
                .param("studentId", currentRow.studentId())
                .param("schoolId", batch.schoolId())
                .update();
        jdbc.sql("""
                UPDATE student.photo_import_rows
                SET status = 'APPLIED', final_photo_key = :key, applied_at = now(),
                    message = 'Portrait imported', updated_at = now()
                WHERE id = :rowId AND school_id = :schoolId
                """)
                .param("key", key)
                .param("rowId", currentRow.id())
                .param("schoolId", batch.schoolId())
                .update();
        outbox.append(
                "student.photo-imported.v1",
                "student.photo-imported.v1:" + batch.id() + ":" + currentRow.id(),
                "student",
                String.valueOf(currentRow.studentId()),
                batch.schoolId(),
                Map.of(
                        "studentId", currentRow.studentId(),
                        "schoolId", batch.schoolId(),
                        "academicYearId", batch.academicYearId(),
                        "photoKey", key,
                        "photoImportBatchId", batch.id().toString()));
        return key;
    }

    @Transactional
    public void markRowFailed(UUID rowId, long schoolId, String message) {
        selectSchoolScope(schoolId);
        jdbc.sql("""
                UPDATE student.photo_import_rows
                SET status = 'FAILED', message = :message, updated_at = now()
                WHERE id = :rowId AND school_id = :schoolId AND status = 'READY'
                """)
                .param("rowId", rowId)
                .param("schoolId", schoolId)
                .param("message", truncate(message, 800))
                .update();
    }

    @Transactional
    public Batch finishExecution(UUID id, long schoolId) {
        selectSchoolScope(schoolId);
        Counts counts = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'APPLIED') AS applied,
                       count(*) FILTER (WHERE status = 'FAILED') AS failed,
                       count(*) FILTER (WHERE status = 'READY') AS ready
                FROM student.photo_import_rows
                WHERE batch_id = :id AND school_id = :schoolId
                """)
                .param("id", id)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> new Counts(
                        rs.getInt("applied"), rs.getInt("failed"), rs.getInt("ready")))
                .single();
        String status = counts.ready() > 0
                ? "EXECUTING"
                : counts.failed() == 0 ? "COMPLETED" : (counts.applied() > 0 ? "PARTIAL" : "FAILED");
        int updated = jdbc.sql("""
                UPDATE student.photo_import_batches
                SET status = :status, ready_count = :ready, applied_count = :applied,
                    failed_count = :failed,
                    executed_at = CASE WHEN :status = 'EXECUTING' THEN executed_at ELSE now() END,
                    updated_at = now(), version = version + 1
                WHERE id = :id AND school_id = :schoolId AND status = 'EXECUTING'
                """)
                .param("status", status)
                .param("ready", counts.ready())
                .param("applied", counts.applied())
                .param("failed", counts.failed())
                .param("id", id)
                .param("schoolId", schoolId)
                .update();
        if (updated == 1 && !"EXECUTING".equals(status)) {
            outbox.append(
                    "student.photo-import.completed.v1",
                    "student.photo-import.completed.v1:" + id,
                    "student-photo-import",
                    id.toString(),
                    schoolId,
                    Map.of(
                            "photoImportBatchId", id.toString(),
                            "schoolId", schoolId,
                            "status", status,
                            "appliedCount", counts.applied(),
                            "failedCount", counts.failed()));
        }
        return batchInTransaction(id, schoolId);
    }

    @Transactional(readOnly = true)
    public GoogleDrivePhotoImportClient.DriveFile sourceFile(UUID batchId, long schoolId, String driveFileId) {
        selectSchoolScope(schoolId);
        return jdbc.sql("""
                SELECT drive_file_id, file_name, mime_type, byte_size, checksum, modified_time
                FROM student.photo_import_sources
                WHERE batch_id = :batchId AND school_id = :schoolId AND drive_file_id = :driveFileId
                """)
                .param("batchId", batchId)
                .param("schoolId", schoolId)
                .param("driveFileId", driveFileId)
                .query((rs, rowNum) -> new GoogleDrivePhotoImportClient.DriveFile(
                        rs.getString("drive_file_id"),
                        rs.getString("file_name"),
                        rs.getString("mime_type"),
                        (Long) rs.getObject("byte_size"),
                        rs.getString("checksum"),
                        rs.getString("modified_time")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Drive source file not found"));
    }

    @Transactional
    public String currentAcademicYearId(long schoolId) {
        selectSchoolScope(schoolId);
        return AcademicCalendarAccess.currentAcademicYear(jdbc, schoolId).id();
    }

    private Batch batchInTransaction(UUID id, long schoolId) {
        return jdbc.sql(batchSelect() + " WHERE b.id = :id AND b.school_id = :schoolId")
                .param("id", id)
                .param("schoolId", schoolId)
                .query(this::mapBatch)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Photo import batch not found"));
    }

    private ImportRow rowInTransaction(UUID id, long schoolId) {
        return jdbc.sql("""
                SELECT id, batch_id, school_id, excel_row, admission_no, workbook_name,
                       class_name, section_name, image_no, drive_file_id, drive_file_name,
                       student_id, status, message, prior_photo_key, final_photo_key,
                       source_checksum, applied_at
                FROM student.photo_import_rows
                WHERE id = :id AND school_id = :schoolId
                """)
                .param("id", id)
                .param("schoolId", schoolId)
                .query(this::mapRow)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Photo import row not found"));
    }

    private String batchSelect() {
        return """
                SELECT b.id, b.school_id, b.school_uid::text AS school_uid, b.academic_year_id,
                       b.drive_folder_id, b.drive_folder_name, b.workbook_file_id,
                       b.workbook_file_name, b.status, b.snapshot_hash, b.total_rows,
                       b.ready_count, b.held_count, b.error_count, b.applied_count,
                       b.failed_count, b.created_by, b.executed_by, b.created_at,
                       b.scanned_at, b.frozen_at, b.executed_at, b.updated_at, b.version,
                       s.name AS school_name, ay.label AS academic_year_label
                FROM student.photo_import_batches b
                JOIN tenant_school.schools s ON s.id = b.school_id
                LEFT JOIN tenant_school.academic_years ay ON ay.id = b.academic_year_id
                """;
    }

    private Batch mapBatch(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Batch(
                rs.getObject("id", UUID.class),
                rs.getLong("school_id"),
                rs.getString("school_uid"),
                rs.getString("school_name"),
                rs.getString("academic_year_id"),
                rs.getString("academic_year_label"),
                rs.getString("drive_folder_id"),
                rs.getString("drive_folder_name"),
                rs.getString("workbook_file_id"),
                rs.getString("workbook_file_name"),
                rs.getString("status"),
                rs.getString("snapshot_hash"),
                rs.getInt("total_rows"),
                rs.getInt("ready_count"),
                rs.getInt("held_count"),
                rs.getInt("error_count"),
                rs.getInt("applied_count"),
                rs.getInt("failed_count"),
                (Long) rs.getObject("created_by"),
                (Long) rs.getObject("executed_by"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("scanned_at", OffsetDateTime.class),
                rs.getObject("frozen_at", OffsetDateTime.class),
                rs.getObject("executed_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getLong("version"));
    }

    private ImportRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ImportRow(
                rs.getObject("id", UUID.class),
                rs.getObject("batch_id", UUID.class),
                rs.getLong("school_id"),
                rs.getInt("excel_row"),
                rs.getString("admission_no"),
                rs.getString("workbook_name"),
                rs.getString("class_name"),
                rs.getString("section_name"),
                rs.getString("image_no"),
                rs.getString("drive_file_id"),
                rs.getString("drive_file_name"),
                (Long) rs.getObject("student_id"),
                rs.getString("status"),
                rs.getString("message"),
                rs.getString("prior_photo_key"),
                rs.getString("final_photo_key"),
                rs.getString("source_checksum"),
                rs.getObject("applied_at", OffsetDateTime.class));
    }

    private void selectSchoolScope(long schoolId) {
        jdbc.sql("SELECT set_config('app.bypass_rls', 'off', true)").query(String.class).single();
        jdbc.sql("SELECT set_config('app.current_school_id', :schoolId, true)")
                .param("schoolId", String.valueOf(schoolId))
                .query(String.class)
                .single();
    }

    private void bypassRls() {
        jdbc.sql("SELECT set_config('app.bypass_rls', 'on', true)").query(String.class).single();
    }

    private static String truncate(String value, int max) {
        String normalized = value == null || value.isBlank() ? "Photo import failed" : value;
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private record SchoolBase(long id, String schoolUid, String name, String shortCode) {
    }

    private record StudentState(String photoKey, String academicYearId) {
    }

    private record Counts(int applied, int failed, int ready) {
    }

    public record SchoolContext(
            long id,
            String schoolUid,
            String name,
            String shortCode,
            String academicYearId,
            String academicYearLabel) {
    }

    public record Batch(
            UUID id,
            long schoolId,
            String schoolUid,
            String schoolName,
            String academicYearId,
            String academicYearLabel,
            String driveFolderId,
            String driveFolderName,
            String workbookFileId,
            String workbookFileName,
            String status,
            String snapshotHash,
            int totalRows,
            int readyCount,
            int heldCount,
            int errorCount,
            int appliedCount,
            int failedCount,
            Long createdBy,
            Long executedBy,
            OffsetDateTime createdAt,
            OffsetDateTime scannedAt,
            OffsetDateTime frozenAt,
            OffsetDateTime executedAt,
            OffsetDateTime updatedAt,
            long version) {
    }

    public record SourceInput(
            String driveFileId,
            String fileName,
            String mimeType,
            Long byteSize,
            String checksum,
            String modifiedTime,
            String sourceType,
            String imageNo) {
    }

    public record RowInput(
            int excelRow,
            String admissionNo,
            String workbookName,
            String className,
            String sectionName,
            String imageNo,
            String driveFileId,
            String driveFileName,
            Long studentId,
            String status,
            String message,
            String priorPhotoKey,
            String sourceChecksum) {
    }

    public record ImportRow(
            UUID id,
            UUID batchId,
            long schoolId,
            int excelRow,
            String admissionNo,
            String workbookName,
            String className,
            String sectionName,
            String imageNo,
            String driveFileId,
            String driveFileName,
            Long studentId,
            String status,
            String message,
            String priorPhotoKey,
            String finalPhotoKey,
            String sourceChecksum,
            OffsetDateTime appliedAt) {
    }

    public record StudentMatch(
            long id,
            String admissionNo,
            String fullName,
            String className,
            Integer classSortOrder,
            String sectionName,
            String photoKey) {
    }
}
