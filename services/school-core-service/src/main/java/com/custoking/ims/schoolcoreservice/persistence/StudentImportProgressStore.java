package com.custoking.ims.schoolcoreservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists import progress independently from the long-running confirmation transaction so a
 * second request can observe real row-by-row progress while the import is still executing.
 */
@Repository
public class StudentImportProgressStore {

    private final JdbcClient jdbc;

    public StudentImportProgressStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void start(String jobId, String batchId, long schoolId, int totalRows) {
        selectSchoolScope(schoolId);
        jdbc.sql("""
                INSERT INTO student.import_job_progress (
                    job_id, batch_id, school_id, status, phase, total_rows,
                    processed_rows, inserted, skipped, percent_complete, updated_at)
                VALUES (:jobId, :batchId, :schoolId, 'RUNNING', 'IMPORTING', :totalRows,
                        0, 0, 0, 0, now())
                ON CONFLICT (job_id) DO UPDATE
                SET status = 'RUNNING', phase = 'IMPORTING', total_rows = EXCLUDED.total_rows,
                    processed_rows = 0, inserted = 0, skipped = 0, percent_complete = 0,
                    message = NULL, completed_at = NULL, updated_at = now()
                """)
                .param("jobId", jobId)
                .param("batchId", batchId)
                .param("schoolId", schoolId)
                .param("totalRows", totalRows)
                .update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void update(String jobId, long schoolId, int totalRows, int processedRows,
                       int inserted, int skipped) {
        selectSchoolScope(schoolId);
        int percent = totalRows <= 0 ? 100 : Math.min(99,
                (int) Math.floor(processedRows * 100.0 / totalRows));
        jdbc.sql("""
                UPDATE student.import_job_progress
                SET processed_rows = :processedRows, inserted = :inserted, skipped = :skipped,
                    percent_complete = :percent, phase = 'IMPORTING', updated_at = now()
                WHERE job_id = :jobId AND school_id = :schoolId
                """)
                .param("processedRows", processedRows)
                .param("inserted", inserted)
                .param("skipped", skipped)
                .param("percent", percent)
                .param("jobId", jobId)
                .param("schoolId", schoolId)
                .update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String jobId, long schoolId, int totalRows, int inserted, int skipped) {
        selectSchoolScope(schoolId);
        jdbc.sql("""
                UPDATE student.import_job_progress
                SET status = 'COMPLETED', phase = 'COMPLETED', processed_rows = :totalRows,
                    inserted = :inserted, skipped = :skipped, percent_complete = 100,
                    message = NULL, completed_at = now(), updated_at = now()
                WHERE job_id = :jobId AND school_id = :schoolId
                """)
                .param("totalRows", totalRows)
                .param("inserted", inserted)
                .param("skipped", skipped)
                .param("jobId", jobId)
                .param("schoolId", schoolId)
                .update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String jobId, long schoolId, String message) {
        selectSchoolScope(schoolId);
        jdbc.sql("""
                UPDATE student.import_job_progress
                SET status = 'FAILED', phase = 'FAILED', message = :message,
                    completed_at = now(), updated_at = now()
                WHERE job_id = :jobId AND school_id = :schoolId
                """)
                .param("message", truncate(message, 500))
                .param("jobId", jobId)
                .param("schoolId", schoolId)
                .update();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(String jobId, Long schoolId) {
        var spec = jdbc.sql("""
                SELECT job_id, batch_id, school_id, status, phase, total_rows, processed_rows,
                       inserted, skipped, percent_complete, message, updated_at
                FROM student.import_job_progress
                WHERE job_id = :jobId
                  AND (:schoolId IS NULL OR school_id = :schoolId)
                """)
                .param("jobId", jobId)
                .param("schoolId", schoolId);
        return spec.query((rs, rowNum) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            String status = rs.getString("status");
            result.put("jobId", rs.getString("job_id"));
            result.put("batchId", rs.getString("batch_id"));
            result.put("status", status);
            result.put("phase", rs.getString("phase"));
            result.put("totalRows", rs.getInt("total_rows"));
            result.put("processedRows", rs.getInt("processed_rows"));
            result.put("inserted", rs.getInt("inserted"));
            result.put("skipped", rs.getInt("skipped"));
            result.put("pct", rs.getInt("percent_complete"));
            result.put("done", "COMPLETED".equals(status));
            result.put("failed", "FAILED".equals(status));
            String message = rs.getString("message");
            if (message != null && !message.isBlank()) result.put("message", message);
            OffsetDateTime updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
            if (updatedAt != null) result.put("updatedAt", updatedAt.toString());
            return result;
        }).optional().orElse(null);
    }

    private void selectSchoolScope(long schoolId) {
        jdbc.sql("SELECT set_config('app.bypass_rls', 'off', true)").query(String.class).single();
        jdbc.sql("SELECT set_config('app.current_school_id', :schoolId, true)")
                .param("schoolId", String.valueOf(schoolId))
                .query(String.class)
                .single();
    }

    private static String truncate(String value, int limit) {
        String safe = value == null || value.isBlank() ? "Import failed" : value.trim();
        return safe.length() <= limit ? safe : safe.substring(0, limit);
    }
}
