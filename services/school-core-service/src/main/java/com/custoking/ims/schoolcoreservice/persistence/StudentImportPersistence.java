package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.custoking.ims.schoolcoreservice.persistence.StudentPresentation.*;
import static com.custoking.ims.schoolcoreservice.persistence.StudentValues.*;
import static com.custoking.ims.schoolcoreservice.persistence.StudentRequestActor.actorId;

import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository.ImportBatchRow;
import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository.ImportUsageDailyRow;
import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository.ImportRow;

/** Owns student imports persistence; application commands own transaction boundaries. */
@Repository
public class StudentImportPersistence {
    private final JdbcClient jdbc;
    private final StudentPhotoStorage photoStorage;
    private final StudentStructurePersistence structure;
    private final StudentEnrollmentPersistence enrollment;

    public StudentImportPersistence(JdbcClient jdbc, StudentPhotoStorage photoStorage, StudentStructurePersistence structure, StudentEnrollmentPersistence enrollment) {
        this.jdbc = jdbc;
        this.photoStorage = photoStorage;
        this.structure = structure;
        this.enrollment = enrollment;
        this.guardianSynchronizer = new LegacyGuardianSynchronizer(jdbc);
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StudentImportProgressStore importProgress;

    @Autowired(required = false)
    void setImportProgress(StudentImportProgressStore importProgress) {
        this.importProgress = importProgress;
    }
    private final LegacyGuardianSynchronizer guardianSynchronizer;
    private record ImportValidation(String status, String message, boolean valid, boolean error, boolean warning) {}
    private record ImportFileEvidence(String fileName, String sha256, Long size, String contentType, String objectPath) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> previewImport(Map<String, Object> request) {
        Long schoolId = longValue(request.get("schoolId"), null);
        if (schoolId == null) throw new IllegalArgumentException("School not found");
        structure.requireSchool(schoolId);
        String schoolStorageId = structure.schoolStorageId(schoolId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawRows = (List<Map<String, Object>>) request.getOrDefault("rows", List.of());
        if (rawRows.size() > 500) throw new IllegalArgumentException("Maximum 500 rows per import");
        for (Map<String, Object> importRow : rawRows) {
            if (importRow == null || importRow.size() > 64) throw new IllegalArgumentException("Import rows must contain at most 64 fields");
            for (var field : importRow.entrySet()) {
                if (field.getKey() == null || field.getKey().length() > 128)
                    throw new IllegalArgumentException("Import field names must be 128 characters or shorter");
                if (field.getValue() instanceof Map || field.getValue() instanceof java.util.Collection)
                    throw new IllegalArgumentException("Import fields must contain scalar values");
                if (field.getValue() != null && field.getValue().toString().length() > 4096)
                    throw new IllegalArgumentException("Import values must be 4096 characters or shorter");
            }
        }

        String batchId = UUID.randomUUID().toString();
        String jobId = UUID.randomUUID().toString();
        String fileToken = UUID.randomUUID().toString();
        ImportFileEvidence fileEvidence = importFileEvidence(schoolStorageId, batchId, request);
        Map<String, Object> structure = importStructureAnalysis(rawRows, schoolId);
        List<Map<String, Object>> normalizedRows = new ArrayList<>();
        Map<String, List<Integer>> admissionRows = new LinkedHashMap<>();
        for (int i = 0; i < rawRows.size(); i++) {
            Map<String, Object> normalized = normalizeImportRow(rawRows.get(i));
            normalizedRows.add(normalized);
            String admissionNo = str(normalized.get("admissionNo"), "").trim();
            if (!admissionNo.isBlank()) {
                String key = admissionNo.toLowerCase(Locale.ROOT);
                admissionRows.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(sourceRowNumber(rawRows.get(i), i + 2));
            }
        }
        int valid = 0;
        int errors = 0;
        int warnings = 0;
        List<Map<String, Object>> previewRows = new ArrayList<>();
        for (int i = 0; i < rawRows.size(); i++) {
            Map<String, Object> normalized = normalizedRows.get(i);
            int sourceRowNumber = sourceRowNumber(rawRows.get(i), i + 2);
            String admissionKey = str(normalized.get("admissionNo"), "").trim().toLowerCase(Locale.ROOT);
            List<Integer> duplicateAdmissionRows = admissionKey.isBlank() ? List.of() : admissionRows.getOrDefault(admissionKey, List.of());
            ImportValidation validation = validatePreviewRow(normalized, schoolId, duplicateAdmissionRows);
            if (validation.valid()) valid++;
            if (validation.error()) errors++;
            if (validation.warning()) warnings++;
            previewRows.add(row(
                    "rowNumber", sourceRowNumber,
                    "name", normalized.get("name"),
                    "className", normalized.get("className"),
                    "sectionName", normalized.get("sectionName"),
                    "admissionNo", normalized.get("admissionNo"),
                    "dateOfBirth", normalized.get("dateOfBirth"),
                    "phone", normalized.get("phone"),
                    "status", validation.status(),
                    "statusTone", validation.error() ? "sr" : validation.warning() ? "sam" : "sg",
                    "description", validation.message(),
                    "message", validation.message()));
        }
        jdbc.sql("""
                        INSERT INTO student.import_batches
                            (id, file_token, job_id, total_rows, valid_count, error_count, warning_count,
                             status, pct, inserted, skipped, created_at, school_id,
                             original_file_name, original_file_sha256, original_file_size,
                             original_file_content_type, original_file_object_path, uploaded_by)
                        VALUES
                            (:id, :fileToken, :jobId, :totalRows, :validCount, :errorCount, :warningCount,
                             'PREVIEWED', 0, 0, 0, :createdAt, :schoolId,
                             :originalFileName, :originalFileSha256, :originalFileSize,
                             :originalFileContentType, :originalFileObjectPath, :uploadedBy)
                        """)
                .param("id", batchId)
                .param("fileToken", fileToken)
                .param("jobId", jobId)
                .param("totalRows", rawRows.size())
                .param("validCount", valid)
                .param("errorCount", errors)
                .param("warningCount", warnings)
                .param("createdAt", OffsetDateTime.now())
                .param("schoolId", schoolId)
                .param("originalFileName", fileEvidence.fileName())
                .param("originalFileSha256", fileEvidence.sha256())
                .param("originalFileSize", fileEvidence.size())
                .param("originalFileContentType", fileEvidence.contentType())
                .param("originalFileObjectPath", fileEvidence.objectPath())
                .param("uploadedBy", actorId())
                .update();
        jdbc.sql("""
                        INSERT INTO student.import_job_progress (
                            job_id, batch_id, school_id, status, phase, total_rows,
                            processed_rows, inserted, skipped, percent_complete)
                        VALUES (:jobId, :batchId, :schoolId, 'READY', 'READY', :totalRows,
                                0, 0, 0, 0)
                        """)
                .param("jobId", jobId)
                .param("batchId", batchId)
                .param("schoolId", schoolId)
                .param("totalRows", rawRows.size())
                .update();
        for (int i = 0; i < rawRows.size(); i++) {
            Map<String, Object> normalized = normalizedRows.get(i);
            jdbc.sql("""
                            INSERT INTO student.import_rows
                                (id, row_no, name, class_name, section_name, admission_no, phone,
                                 status, message, raw_json, normalized_json, batch_id, school_id)
                            VALUES
                                (:id, :rowNo, :name, :className, :sectionName, :admissionNo, :phone,
                                 :status, :message, :rawJson, :normalizedJson, :batchId, :schoolId)
                            """)
                    .param("id", UUID.randomUUID().toString())
                    .param("rowNo", previewRows.get(i).get("rowNumber"))
                    .param("name", str(normalized.get("name"), ""))
                    .param("className", str(normalized.get("className"), ""))
                    .param("sectionName", str(normalized.get("sectionName"), ""))
                    .param("admissionNo", str(normalized.get("admissionNo"), ""))
                    .param("phone", str(normalized.get("phone"), ""))
                    .param("status", previewRows.get(i).get("status"))
                    .param("message", previewRows.get(i).get("message"))
                    .param("rawJson", toJson(rawRows.get(i)))
                    .param("normalizedJson", toJson(normalized))
                    .param("batchId", batchId)
                    .param("schoolId", schoolId)
                    .update();
        }
        return row("rows", previewRows, "fileToken", fileToken, "jobId", jobId,
                "validCount", valid, "errorCount", errors, "warningCount", warnings,
                "batchId", batchId,
                "originalFileStored", fileEvidence.objectPath() != null,
                "structure", structure);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> confirmImport(Map<String, Object> request) {
        String fileToken = requireText(request.get("fileToken"), "Preview token not found");
        Long schoolId = longValue(request.get("schoolId"), null);
        if (schoolId == null) throw new IllegalArgumentException("School not found");
        ImportBatchRow batch = jdbc.sql("""
                        SELECT id, file_token, job_id, total_rows, valid_count, error_count,
                               warning_count, status, pct, inserted, skipped, skipped_json,
                               created_at, completed_at
                        FROM student.import_batches
                        WHERE file_token = :fileToken
                          AND school_id = :schoolId
                        FOR UPDATE
                        """)
                .param("fileToken", fileToken)
                .param("schoolId", schoolId)
                .query(ImportBatchRow.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Preview token not found"));
        if ("DONE".equalsIgnoreCase(batch.status())) {
            return completedImportResult(batch, schoolId);
        }

        // Bound this synchronous, connection-holding operation across every service
        // replica: one confirmation per school and two confirmations fleet-wide.
        // Same-token retries still serialize on the batch row above and completed
        // retries return before consuming an admission slot.
        StudentImportAdmissionGuard.acquire(jdbc, schoolId);

        String batchId = batch.id();
        String jobId = batch.jobId() == null || batch.jobId().isBlank()
                ? UUID.randomUUID().toString()
                : batch.jobId();
        jdbc.sql("UPDATE student.import_batches SET job_id = :jobId, status = 'RUNNING', pct = 20 WHERE id = :batchId")
                .param("jobId", jobId)
                .param("batchId", batchId)
                .update();
        List<ImportRow> rows = importRows(schoolId, batchId, null, 1000);
        startImportProgress(jobId, batchId, schoolId, rows.size());
        registerImportProgressFailureOnRollback(jobId, schoolId);
        int inserted = 0;
        int skipped = 0;
        int processed = 0;
        List<Map<String, Object>> skippedRows = new ArrayList<>();
        List<Map<String, Object>> insertedStudents = new ArrayList<>();
        for (ImportRow row : rows) {
            if (!"Valid".equalsIgnoreCase(row.status()) && !"Warning".equalsIgnoreCase(row.status())) {
                skipped++;
                skippedRows.add(importSkippedRow(row, row.message()));
                processed++;
                updateImportProgress(jobId, schoolId, rows.size(), processed, inserted, skipped);
                continue;
            }
            try {
                Map<String, Object> normalized = objectMapper.readValue(row.normalizedJson(), new TypeReference<>() {});
                Long studentId = insertImportedStudent(normalized, schoolId, batchId);
                guardianSynchronizer.syncFromLegacy(studentId);
                jdbc.sql("UPDATE student.students SET imported_at = :importedAt, import_batch_id = :batchId WHERE id = :studentId")
                        .param("importedAt", OffsetDateTime.now())
                        .param("batchId", batchId)
                        .param("studentId", studentId)
                        .update();
                jdbc.sql("""
                        UPDATE student.import_rows
                        SET applied_student_id = :studentId, applied_at = now(), status = 'Imported'
                        WHERE id = :rowId
                        """)
                        .param("studentId", studentId)
                        .param("rowId", row.id())
                        .update();
                enrollment.recordEnrollmentFromCurrentStudent(studentId, "Imported from batch " + batchId, "IMPORT", batchId);
                enrollment.emitStudentUpserted(studentId);
                inserted++;
                insertedStudents.add(row("admissionNo", normalized.get("admissionNo"), "studentId", studentId));
            } catch (Exception ex) {
                skipped++;
                String reason = importFailureMessage(ex);
                jdbc.sql("""
                        UPDATE student.import_rows
                        SET status = 'Skipped', message = :message
                        WHERE id = :rowId
                        """)
                        .param("message", reason)
                        .param("rowId", row.id())
                        .update();
                skippedRows.add(importSkippedRow(row, reason));
            }
            processed++;
            updateImportProgress(jobId, schoolId, rows.size(), processed, inserted, skipped);
        }
        jdbc.sql("""
                        UPDATE student.import_batches
                        SET pct = 100, status = 'DONE', inserted = :inserted, skipped = :skipped,
                            completed_at = :completedAt, skipped_json = :skippedJson,
                            verified_student_count = :verifiedStudentCount
                        WHERE id = :batchId
                        """)
                .param("inserted", inserted)
                .param("skipped", skipped)
                .param("completedAt", OffsetDateTime.now())
                .param("skippedJson", toJson(skippedRows))
                .param("verifiedStudentCount", inserted)
                .param("batchId", batchId)
                .update();
        completeImportProgressAfterCommit(jobId, schoolId, rows.size(), inserted, skipped);
        return row("batchId", batchId,
                "jobId", jobId,
                "schoolId", schoolId,
                "totalRows", rows.size(),
                "inserted", inserted,
                "skipped", skipped,
                "done", true,
                "skippedRows", skippedRows,
                "insertedStudents", insertedStudents);
    }

    private Map<String, Object> completedImportResult(ImportBatchRow batch, Long schoolId) {
        List<Map<String, Object>> skippedRows = parseSkippedImportRows(batch.skippedJson());
        List<Map<String, Object>> insertedStudents = jdbc.sql("""
                        SELECT admission_no, applied_student_id
                        FROM student.import_rows
                        WHERE batch_id = :batchId
                          AND school_id = :schoolId
                          AND applied_student_id IS NOT NULL
                        ORDER BY row_no
                        """)
                .param("batchId", batch.id())
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> row(
                        "admissionNo", rs.getString("admission_no"),
                        "studentId", rs.getLong("applied_student_id")))
                .list();
        return row("batchId", batch.id(),
                "jobId", batch.jobId(),
                "schoolId", schoolId,
                "totalRows", batch.totalRows(),
                "inserted", batch.inserted(),
                "skipped", batch.skipped(),
                "done", true,
                "skippedRows", skippedRows,
                "insertedStudents", insertedStudents);
    }

    public Map<String, Object> importStatus(String jobId, Long schoolId) {
        if (importProgress != null) {
            Map<String, Object> live = importProgress.status(jobId, schoolId);
            if (live != null && !Boolean.TRUE.equals(live.get("done"))) {
                return live;
            }
        }
        ImportBatchRow batch = jdbc.sql("""
                        SELECT id, file_token, job_id, total_rows, valid_count, error_count,
                               warning_count, status, pct, inserted, skipped, skipped_json,
                               created_at, completed_at
                        FROM student.import_batches
                        WHERE job_id = :jobId
                          AND (:schoolId IS NULL OR school_id = :schoolId)
                        """)
                .param("jobId", jobId)
                // Fleet reads intentionally bind a null scope; PostgreSQL still needs its SQL type.
                .param("schoolId", schoolId, java.sql.Types.BIGINT)
                .query(ImportBatchRow.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Import job not found"));
        List<Map<String, Object>> skippedRows = parseSkippedImportRows(batch.skippedJson());
        return row("pct", batch.pct(), "done", "DONE".equalsIgnoreCase(batch.status()),
                "inserted", batch.inserted(), "skipped", batch.skipped(), "skippedRows", skippedRows);
    }

    private void startImportProgress(String jobId, String batchId, long schoolId, int totalRows) {
        if (importProgress == null) return;
        try {
            importProgress.start(jobId, batchId, schoolId, totalRows);
        } catch (RuntimeException ignored) {
            // Progress reporting must never block the import itself.
        }
    }

    private void updateImportProgress(String jobId, long schoolId, int totalRows, int processed,
                                      int inserted, int skipped) {
        if (importProgress == null) return;
        try {
            importProgress.update(jobId, schoolId, totalRows, processed, inserted, skipped);
        } catch (RuntimeException ignored) {
            // The import remains authoritative if a transient progress write fails.
        }
    }

    private void completeImportProgressAfterCommit(String jobId, long schoolId, int totalRows,
                                                   int inserted, int skipped) {
        Runnable completion = () -> completeImportProgress(jobId, schoolId, totalRows, inserted, skipped);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    completion.run();
                }
            });
        } else {
            completion.run();
        }
    }

    private void registerImportProgressFailureOnRollback(String jobId, long schoolId) {
        if (importProgress == null || !TransactionSynchronizationManager.isActualTransactionActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) return;
                try {
                    importProgress.fail(jobId, schoolId, "Import stopped before completion; it can be retried safely");
                } catch (RuntimeException ignored) {
                    // The preview and canonical batch remain available even if failure reporting is unavailable.
                }
            }
        });
    }

    private void completeImportProgress(String jobId, long schoolId, int totalRows,
                                        int inserted, int skipped) {
        if (importProgress == null) return;
        try {
            importProgress.complete(jobId, schoolId, totalRows, inserted, skipped);
        } catch (RuntimeException ignored) {
            // Completion still persists on the canonical import batch row.
        }
    }

    private List<Map<String, Object>> parseSkippedImportRows(String skippedJson) {
        if (skippedJson == null || skippedJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(skippedJson, new TypeReference<>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public List<ImportBatchRow> importBatches(Long schoolId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, file_token, job_id, total_rows, valid_count, error_count,
                       warning_count, status, pct, inserted, skipped, skipped_json,
                       created_at, completed_at
                FROM student.import_batches
                """);
        if (schoolId != null) {
            sql.append("WHERE school_id = :schoolId\n");
        }
        sql.append("""
                ORDER BY created_at DESC NULLS LAST
                LIMIT :limit
                """);
        var spec = jdbc.sql(sql.toString());
        if (schoolId != null) {
            spec = spec.param("schoolId", schoolId);
        }
        return spec.param("limit", Math.max(1, Math.min(limit, 500)))
                .query(ImportBatchRow.class)
                .list();
    }

    public List<ImportUsageDailyRow> importUsageDaily(Long schoolId, int days, int limit) {
        int boundedDays = Math.max(1, Math.min(days, 90));
        int boundedLimit = Math.max(1, Math.min(limit, 20_000));
        OffsetDateTime cutoff = LocalDate.now(ZoneOffset.UTC)
                .minusDays(boundedDays - 1L)
                .atStartOfDay()
                .atOffset(ZoneOffset.UTC);
        StringBuilder sql = new StringBuilder("""
                        SELECT school_id,
                               (created_at AT TIME ZONE 'UTC')::date AS usage_date,
                               count(*) AS previewed_batches,
                               count(*) FILTER (WHERE status = 'DONE') AS completed_batches,
                               count(*) FILTER (WHERE status IS DISTINCT FROM 'DONE') AS unfinished_batches,
                               coalesce(sum(total_rows), 0) AS attempted_rows,
                               coalesce(sum(inserted) FILTER (WHERE status = 'DONE'), 0) AS inserted_rows,
                               coalesce(sum(skipped) FILTER (WHERE status = 'DONE'), 0) AS skipped_rows,
                               coalesce(sum(original_file_size), 0) AS source_bytes
                        FROM student.import_batches
                        WHERE created_at >= :cutoff
                        """);
        if (schoolId != null) {
            sql.append(" AND school_id = :schoolId\n");
        }
        sql.append("""
                        GROUP BY school_id, (created_at AT TIME ZONE 'UTC')::date
                        ORDER BY usage_date DESC, school_id
                        LIMIT :limit
                        """);
        var spec = jdbc.sql(sql.toString())
                .param("cutoff", cutoff)
                .param("limit", boundedLimit);
        if (schoolId != null) {
            spec = spec.param("schoolId", schoolId);
        }
        return spec.query(ImportUsageDailyRow.class).list();
    }

    public List<ImportRow> importRows(Long schoolId, String batchId, String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.id, r.row_no, r.name, r.class_name, r.section_name, r.admission_no, r.phone,
                       r.status, r.message, r.raw_json, r.normalized_json, r.batch_id
                FROM student.import_rows r
                JOIN student.import_batches b ON b.id = r.batch_id
                WHERE (:schoolId IS NULL OR COALESCE(r.school_id, b.school_id) = :schoolId)
                """);
        if (batchId != null && !batchId.isBlank()) sql.append(" AND r.batch_id = :batchId");
        if (status != null && !status.isBlank()) sql.append(" AND r.status = :status");
        sql.append(" ORDER BY r.row_no ASC LIMIT :limit");
        var spec = jdbc.sql(sql.toString())
                .param("schoolId", schoolId, java.sql.Types.BIGINT)
                .param("limit", Math.max(1, Math.min(limit, 1000)));
        if (batchId != null && !batchId.isBlank()) spec = spec.param("batchId", batchId);
        if (status != null && !status.isBlank()) spec = spec.param("status", status);
        return spec.query(ImportRow.class).list();
    }

    private ImportValidation validatePreviewRow(Map<String, Object> normalized, Long schoolId, List<Integer> duplicateAdmissionRows) {
        if (str(normalized.get("name"), "").isBlank()
                || str(normalized.get("className"), "").isBlank()
                || str(normalized.get("sectionName"), "").isBlank()
                || str(normalized.get("admissionNo"), "").isBlank()
                || str(normalized.get("phone"), "").isBlank()) {
            return new ImportValidation("Missing field", "Required field is blank", false, true, false);
        }
        if (duplicateAdmissionRows != null && duplicateAdmissionRows.size() > 1) {
            return new ImportValidation("Duplicate in file",
                    "Admission number appears multiple times in this file: rows "
                            + duplicateAdmissionRows.stream().map(String::valueOf).collect(Collectors.joining(", ")),
                    false, true, false);
        }
        try {
            StudentImportDateParser.parseDateOfBirth(str(normalized.get("dateOfBirth"), ""));
            StudentImportDateParser.parseOptional(str(normalized.get("admissionDate"), ""));
        } catch (IllegalArgumentException ex) {
            return new ImportValidation("Invalid date", ex.getMessage(), false, true, false);
        }
        String className = str(normalized.get("className"), "");
        Optional<Map<String, Object>> schoolClass = structure.classByName(className);
        if (schoolClass.isEmpty()) {
            return new ImportValidation("Class not found", "Class value not found in the system", false, true, false);
        }
        Optional<Map<String, Object>> activeClass = structure.activeClassForSchool(schoolId, schoolClass.get());
        if (activeClass.isEmpty()) {
            return new ImportValidation("Setup update needed",
                    "Class is not active for this school's configured setup",
                    false, true, false);
        }
        String admissionNo = str(normalized.get("admissionNo"), "");
        Long duplicate = jdbc.sql("SELECT COUNT(*) FROM student.students WHERE lower(admission_no) = lower(:admissionNo) AND school_id = :schoolId")
                .param("admissionNo", admissionNo)
                .param("schoolId", schoolId)
                .query(Long.class)
                .single();
        if (duplicate != null && duplicate > 0) {
            return new ImportValidation("Duplicate", "Admission number already exists", false, true, false);
        }
        Optional<Map<String, Object>> section = structure.activeSectionByNameForClassSchool(
                schoolId,
                String.valueOf(activeClass.get().get("id")),
                str(normalized.get("sectionName"), ""));
        if (section.isEmpty()) {
            return new ImportValidation("Setup update needed",
                    "Class section is not active for this school's configured setup",
                    false, true, false);
        }
        if (!validInternationalPhone(str(normalized.get("phone"), ""))) {
            return new ImportValidation("Warning", "Phone is unusual format", true, false, true);
        }
        return new ImportValidation("Valid", "", true, false, false);
    }

    private Long insertImportedStudent(Map<String, Object> normalized, Long schoolId, String batchId) {
        Map<String, Object> schoolClass = structure.classByName(str(normalized.get("className"), ""))
                .orElseThrow(() -> new IllegalArgumentException("Class not found"));
        Map<String, Object> activeClass = structure.activeClassForSchool(schoolId, schoolClass)
                .orElseThrow(() -> new IllegalArgumentException("Class is not active for this school's configured setup"));
        Map<String, Object> section = structure.activeSectionByNameForClassSchool(
                schoolId,
                String.valueOf(activeClass.get("id")),
                str(normalized.get("sectionName"), "A"))
                .orElseThrow(() -> new IllegalArgumentException("Class section is not active for this school's configured setup"));
        String academicYearId = structure.currentAcademicYearId(schoolId);
        String admissionNo = str(normalized.get("admissionNo"), "");
        Long duplicate = jdbc.sql("SELECT COUNT(*) FROM student.students WHERE lower(admission_no) = lower(:admissionNo) AND school_id = :schoolId")
                .param("admissionNo", admissionNo)
                .param("schoolId", schoolId)
                .query(Long.class)
                .single();
        if (duplicate != null && duplicate > 0) {
            throw new IllegalArgumentException("Duplicate admission number");
        }
        OffsetDateTime now = OffsetDateTime.now();
        return jdbc.sql("""
                        INSERT INTO student.students(admission_no, roll_no, board_reg_no, full_name, dob, admission_date, gender,
                                             father_name, father_contact, phone, address,
                                             house_number, street, locality, city, state, pin_code,
                                             fee_status, attendance_percent, imported_at, import_batch_id,
                                             created_at, updated_at, school_id, class_id, section_id, academic_year_id, version)
                        VALUES (:admissionNo, :rollNo, :boardRegNo, :fullName, :dob, :admissionDate, :gender,
                                :fatherName, :fatherContact, :phone, :address,
                                :houseNumber, :street, :locality, :city, :state, :pinCode,
                                'Pending', 0, :importedAt, :batchId,
                                :createdAt, :updatedAt, :schoolId, :classId, :sectionId, :academicYearId, 0)
                        RETURNING id
                        """)
                .param("admissionNo", admissionNo)
                .param("rollNo", String.valueOf(structure.countBySection(String.valueOf(section.get("id"))) + 1))
                .param("boardRegNo", str(normalized.get("boardRegistrationNo"), ""))
                .param("fullName", str(normalized.get("name"), ""))
                .param("dob", StudentImportDateParser.parseDateOfBirth(str(normalized.get("dateOfBirth"), "")))
                .param("admissionDate", StudentImportDateParser.parseOptional(str(normalized.get("admissionDate"), "")))
                .param("gender", str(normalized.get("gender"), "Unspecified"))
                .param("fatherName", str(normalized.get("fatherName"), ""))
                .param("fatherContact", str(normalized.get("phone"), ""))
                .param("phone", str(normalized.get("phone"), ""))
                .param("address", preferredAddress(normalized.get("address"),
                        str(normalized.get("houseNumber"), ""),
                        str(normalized.get("street"), ""),
                        str(normalized.get("locality"), ""),
                        str(normalized.get("city"), ""),
                        str(normalized.get("state"), ""),
                        str(normalized.get("pinCode"), "")))
                .param("houseNumber", str(normalized.get("houseNumber"), ""))
                .param("street", str(normalized.get("street"), ""))
                .param("locality", str(normalized.get("locality"), ""))
                .param("city", str(normalized.get("city"), ""))
                .param("state", str(normalized.get("state"), ""))
                .param("pinCode", str(normalized.get("pinCode"), ""))
                .param("importedAt", now)
                .param("batchId", batchId)
                .param("createdAt", now)
                .param("updatedAt", now)
                .param("schoolId", schoolId)
                .param("classId", activeClass.get("id"))
                .param("sectionId", section.get("id"))
                .param("academicYearId", academicYearId)
                .query(Long.class)
                .single();
    }

    private int sourceRowNumber(Map<String, Object> rawRow, int fallback) {
        Long rowNumber = longValue(rawRow.get("__rowNumber"), null);
        if (rowNumber == null || rowNumber <= 0 || rowNumber > Integer.MAX_VALUE) {
            return fallback;
        }
        return rowNumber.intValue();
    }

    private Map<String, Object> importSkippedRow(ImportRow row, String reason) {
        return row(
                "rowNumber", row.rowNo(),
                "name", row.name(),
                "admissionNo", row.admissionNo(),
                "className", row.className(),
                "sectionName", row.sectionName(),
                "phone", row.phone(),
                "status", row.status(),
                "message", row.message(),
                "reason", reason == null || reason.isBlank() ? "Import row failed" : reason);
    }

    private String importFailureMessage(Exception ex) {
        String message = rootCauseMessage(ex);
        if (message == null || message.isBlank()) {
            return "Import row failed";
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("uix_student_students_school_admission")
                || (lower.contains("duplicate key") && lower.contains("admission"))) {
            return "Duplicate admission number";
        }
        return message;
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable cursor = throwable;
        Throwable previous = null;
        while (cursor != null && cursor != previous) {
            previous = cursor;
            cursor = cursor.getCause();
        }
        String message = previous == null ? null : previous.getMessage();
        if ((message == null || message.isBlank()) && throwable != null) {
            message = throwable.getMessage();
        }
        return message;
    }

    private Map<String, Object> normalizeImportRow(Map<String, Object> rawRow) {
        return row("name", firstPresentIgnoreCase(rawRow, "Name", "name"),
                "className", firstPresentIgnoreCase(rawRow, "Class", "class", "className"),
                "sectionName", firstPresentIgnoreCase(rawRow, "Section", "section", "sectionName"),
                "admissionNo", firstPresentIgnoreCase(rawRow, "AdmissionNo", "admissionNo", "Admission No"),
                "dateOfBirth", firstPresentIgnoreCase(rawRow, "DateOfBirth", "dateOfBirth"),
                "admissionDate", firstPresentIgnoreCase(rawRow, "AdmissionDate", "Admission Date", "admissionDate"),
                "gender", firstPresentIgnoreCase(rawRow, "Gender", "gender"),
                "fatherName", firstPresentIgnoreCase(rawRow, "FatherName", "fatherName"),
                "phone", firstPresentIgnoreCase(rawRow, "Phone", "phone"),
                "houseNumber", firstPresentIgnoreCase(rawRow, "HouseNumber", "House Number", "houseNumber"),
                "street", firstPresentIgnoreCase(rawRow, "Street", "street"),
                "locality", firstPresentIgnoreCase(rawRow, "Locality", "locality"),
                "city", firstPresentIgnoreCase(rawRow, "City", "city"),
                "state", firstPresentIgnoreCase(rawRow, "State", "state"),
                "pinCode", firstPresentIgnoreCase(rawRow, "PostalCode", "Postal Code", "PinCode", "PIN Code", "Pincode", "pinCode"),
                "address", firstPresentIgnoreCase(rawRow, "Address", "address"),
                "boardRegistrationNo", firstPresentIgnoreCase(rawRow, "BoardRegistrationNo", "boardRegistrationNo"));
    }

    private String firstPresentIgnoreCase(Map<String, Object> rawRow, String... keys) {
        for (String key : keys) {
            for (Map.Entry<String, Object> entry : rawRow.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key) && entry.getValue() != null) {
                    return String.valueOf(entry.getValue()).trim();
                }
            }
        }
        return "";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private ImportFileEvidence importFileEvidence(String schoolStorageId, String batchId, Map<String, Object> request) {
        Object bytesValue = request.get("originalFileBytes");
        byte[] bytes = bytesValue instanceof byte[] data ? data : null;
        if (bytes == null || bytes.length == 0) {
            return new ImportFileEvidence(
                    str(request.get("originalFileName"), null),
                    null,
                    longValue(request.get("originalFileSize"), null),
                    str(request.get("originalFileContentType"), null),
                    null);
        }
        String fileName = str(request.get("originalFileName"), "students-import");
        String contentType = str(request.get("originalFileContentType"), "application/octet-stream");
        String objectPath = photoStorage.uploadImportFile(schoolStorageId, batchId, bytes, contentType, fileName);
        return new ImportFileEvidence(fileName, StudentPhotoStorage.sha256Hex(bytes), (long) bytes.length, contentType, objectPath);
    }

    private Map<String, Object> importStructureAnalysis(List<Map<String, Object>> rawRows, Long schoolId) {
        Map<String, Object> school = jdbc.sql("""
                SELECT COALESCE(configured_class_count, :defaultClassCount) AS class_count,
                       COALESCE(configured_section_count, 2) AS section_count
                FROM tenant_school.schools
                WHERE id = :schoolId
                """)
                .param("schoolId", schoolId)
                .param("defaultClassCount", SchoolStructureReadRepository.DEFAULT_CONFIGURED_CLASS_COUNT)
                .query((rs, n) -> row(
                        "classCount", rs.getInt("class_count"),
                        "sectionCount", rs.getInt("section_count")))
                .optional()
                .orElse(row("classCount", SchoolStructureReadRepository.DEFAULT_CONFIGURED_CLASS_COUNT, "sectionCount", 2));
        int currentClassCount = ((Number) school.get("classCount")).intValue();
        int currentSectionCount = ((Number) school.get("sectionCount")).intValue();
        int requiredClassCount = currentClassCount;
        int requiredSectionCount = currentSectionCount;
        List<String> missingClasses = new ArrayList<>();
        List<String> missingSections = new ArrayList<>();
        List<String> unsupportedClasses = new ArrayList<>();

        for (Map<String, Object> rawRow : rawRows) {
            Map<String, Object> normalized = normalizeImportRow(rawRow);
            String className = str(normalized.get("className"), "").trim();
            Optional<Map<String, Object>> schoolClass = structure.classByName(className);
            if (schoolClass.isPresent()) {
                int sortOrder = ((Number) schoolClass.get().getOrDefault("sortOrder", 0)).intValue();
                if (sortOrder > requiredClassCount) requiredClassCount = sortOrder;
                if (sortOrder > currentClassCount && !missingClasses.contains(className)) {
                    missingClasses.add(className);
                }
                if (sortOrder > SchoolStructureReadRepository.MAX_CONFIGURED_CLASS_COUNT
                        && !unsupportedClasses.contains(className)) {
                    unsupportedClasses.add(className);
                }
            }
            String sectionName = str(normalized.get("sectionName"), "").trim();
            int sectionIndex = sectionIndex(sectionName);
            if (sectionIndex > requiredSectionCount) requiredSectionCount = sectionIndex;
            if (sectionIndex > currentSectionCount && !sectionName.isBlank() && !missingSections.contains(sectionName)) {
                missingSections.add(sectionName);
            }
        }

        return row(
                "currentClassCount", currentClassCount,
                "currentSectionCount", currentSectionCount,
                "requiredClassCount", requiredClassCount,
                "requiredSectionCount", requiredSectionCount,
                "requiresStructureUpdate", requiredClassCount > currentClassCount || requiredSectionCount > currentSectionCount,
                "missingClasses", missingClasses,
                "missingSections", missingSections,
                "unsupportedClasses", unsupportedClasses);
    }
}
