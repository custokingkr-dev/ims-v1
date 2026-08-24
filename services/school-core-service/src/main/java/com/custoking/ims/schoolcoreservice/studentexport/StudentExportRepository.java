package com.custoking.ims.schoolcoreservice.studentexport;

import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.studentexport.StudentExportService.ExportProgress;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class StudentExportRepository {

    private final JdbcClient jdbc;

    public StudentExportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns active schools visible to the authenticated Operations user. The database read is
     * deliberately performed with RLS bypass inside this transaction and then filtered against
     * the trusted operator-school IDs from the verified JWT. This mirrors the existing photo
     * import context and prevents an Operations user from enumerating unassigned schools.
     */
    @Transactional(readOnly = true)
    public List<SchoolOption> allowedSchools() {
        bypassRls();
        TenantContext context = TenantContext.get();
        Set<Long> allowed = context.isSuperAdmin() ? Set.of() : context.operatorSchools();
        List<SchoolOption> schools = jdbc.sql("""
                SELECT school.id,
                       school.name,
                       school.short_code,
                       COUNT(student.id) AS student_count,
                       COUNT(student.id) FILTER (
                           WHERE NULLIF(trim(student.photo_url), '') IS NOT NULL
                       ) AS photo_count
                FROM tenant_school.schools school
                LEFT JOIN student.students student
                  ON student.school_id = school.id
                 AND student.deleted_at IS NULL
                WHERE school.active = true
                GROUP BY school.id, school.name, school.short_code
                ORDER BY lower(school.name), school.id
                """)
                .query((rs, rowNum) -> new SchoolOption(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("short_code"),
                        rs.getLong("student_count"),
                        rs.getLong("photo_count")))
                .list();
        if (context.isSuperAdmin()) {
            return schools;
        }
        return schools.stream().filter(school -> allowed.contains(school.id())).toList();
    }

    @Transactional(readOnly = true)
    public ExportData load(long schoolId) {
        selectSchoolScope(schoolId);
        School school = jdbc.sql("""
                SELECT id, name, short_code
                FROM tenant_school.schools
                WHERE id = :schoolId AND active = true
                """)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> new School(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("short_code")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Active school not found"));

        List<Student> students = jdbc.sql("""
                SELECT student.id,
                       student.admission_no,
                       student.full_name,
                       class.name AS class_name,
                       section.name AS section_name,
                       student.roll_no,
                       student.board_reg_no,
                       student.dob,
                       student.admission_date,
                       student.gender,
                       student.father_name,
                       student.father_contact,
                       student.mother_name,
                       student.phone,
                       student.house_number,
                       student.street,
                       student.locality,
                       student.city,
                       student.state,
                       student.pin_code,
                       student.address,
                       year.label AS academic_year,
                       student.fee_status,
                       student.attendance_percent,
                       student.photo_url
                FROM student.students student
                LEFT JOIN tenant_school.school_classes class ON class.id = student.class_id
                LEFT JOIN tenant_school.school_sections section ON section.id = student.section_id
                LEFT JOIN tenant_school.academic_years year ON year.id = student.academic_year_id
                WHERE student.school_id = :schoolId
                  AND student.deleted_at IS NULL
                ORDER BY class.sort_order NULLS LAST,
                         lower(class.name) NULLS LAST,
                         lower(section.name) NULLS LAST,
                         lower(student.roll_no) NULLS LAST,
                         lower(student.admission_no),
                         student.id
                """)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> new Student(
                        rs.getLong("id"),
                        rs.getString("admission_no"),
                        rs.getString("full_name"),
                        rs.getString("class_name"),
                        rs.getString("section_name"),
                        rs.getString("roll_no"),
                        rs.getString("board_reg_no"),
                        rs.getObject("dob", LocalDate.class),
                        rs.getObject("admission_date", LocalDate.class),
                        rs.getString("gender"),
                        rs.getString("father_name"),
                        rs.getString("father_contact"),
                        rs.getString("mother_name"),
                        rs.getString("phone"),
                        rs.getString("house_number"),
                        rs.getString("street"),
                        rs.getString("locality"),
                        rs.getString("city"),
                        rs.getString("state"),
                        rs.getString("pin_code"),
                        rs.getString("address"),
                        rs.getString("academic_year"),
                        rs.getString("fee_status"),
                        rs.getObject("attendance_percent", Double.class),
                        rs.getString("photo_url")))
                .list();
        return new ExportData(school, students);
    }

    @Transactional
    public UUID startAudit(long schoolId, Long requestedBy, int studentCount) {
        selectSchoolScope(schoolId);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO student.student_export_audit (
                    id, school_id, requested_by, status, student_count, started_at
                ) VALUES (
                    :id, :schoolId, :requestedBy, 'STARTED', :studentCount, now()
                )
                """)
                .param("id", id)
                .param("schoolId", schoolId)
                .param("requestedBy", requestedBy)
                .param("studentCount", studentCount)
                .update();
        return id;
    }

    @Transactional
    public void finishAudit(UUID auditId, long schoolId, String status,
                            int exportedPhotos, int missingPhotos, String failureReason) {
        selectSchoolScope(schoolId);
        jdbc.sql("""
                UPDATE student.student_export_audit
                SET status = :status,
                    exported_photo_count = CASE WHEN :status = 'COMPLETED' THEN :exportedPhotos ELSE exported_photo_count END,
                    missing_photo_count = CASE WHEN :status = 'COMPLETED' THEN :missingPhotos ELSE missing_photo_count END,
                    processed_student_count = CASE WHEN :status = 'COMPLETED' THEN student_count ELSE processed_student_count END,
                    progress_percent = CASE WHEN :status = 'COMPLETED' THEN 100 ELSE progress_percent END,
                    progress_phase = CASE WHEN :status = 'COMPLETED' THEN 'COMPLETED' ELSE 'FAILED' END,
                    failure_reason = :failureReason,
                    completed_at = now()
                WHERE id = :auditId AND school_id = :schoolId
                """)
                .param("status", status)
                .param("exportedPhotos", exportedPhotos)
                .param("missingPhotos", missingPhotos)
                .param("failureReason", truncate(failureReason, 500))
                .param("auditId", auditId)
                .param("schoolId", schoolId)
                .update();
    }

    @Transactional
    public void updateProgress(UUID auditId, long schoolId, int processedStudents,
                               int exportedPhotos, int missingPhotos, int percent, String phase) {
        selectSchoolScope(schoolId);
        jdbc.sql("""
                UPDATE student.student_export_audit
                SET processed_student_count = LEAST(student_count, GREATEST(processed_student_count, :processedStudents)),
                    exported_photo_count = GREATEST(exported_photo_count, :exportedPhotos),
                    missing_photo_count = GREATEST(missing_photo_count, :missingPhotos),
                    progress_percent = LEAST(99, GREATEST(progress_percent, :percent)),
                    progress_phase = :phase
                WHERE id = :auditId
                  AND school_id = :schoolId
                  AND status = 'STARTED'
                """)
                .param("processedStudents", processedStudents)
                .param("exportedPhotos", exportedPhotos)
                .param("missingPhotos", missingPhotos)
                .param("percent", percent)
                .param("phase", truncate(phase, 32))
                .param("auditId", auditId)
                .param("schoolId", schoolId)
                .update();
    }

    @Transactional(readOnly = true)
    public ExportProgress progress(UUID auditId, long schoolId) {
        selectSchoolScope(schoolId);
        return jdbc.sql("""
                SELECT id, status, progress_percent, progress_phase,
                       processed_student_count, student_count,
                       exported_photo_count, missing_photo_count, failure_reason
                FROM student.student_export_audit
                WHERE id = :auditId AND school_id = :schoolId
                """)
                .param("auditId", auditId)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> new ExportProgress(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getInt("progress_percent"),
                        rs.getString("progress_phase"),
                        rs.getInt("processed_student_count"),
                        rs.getInt("student_count"),
                        rs.getInt("exported_photo_count"),
                        rs.getInt("missing_photo_count"),
                        rs.getString("failure_reason")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student export not found"));
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
        if (value == null || value.isBlank()) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record SchoolOption(long id, String name, String shortCode,
                               long studentCount, long photoCount) {}

    public record School(long id, String name, String shortCode) {}

    public record ExportData(School school, List<Student> students) {
        public ExportData {
            students = List.copyOf(new ArrayList<>(students));
        }
    }

    public record Student(
            long id,
            String admissionNumber,
            String fullName,
            String className,
            String sectionName,
            String rollNumber,
            String boardRegistrationNumber,
            LocalDate dateOfBirth,
            LocalDate admissionDate,
            String gender,
            String fatherName,
            String fatherContact,
            String motherName,
            String phone,
            String houseNumber,
            String street,
            String locality,
            String city,
            String state,
            String pinCode,
            String address,
            String academicYear,
            String feeStatus,
            Double attendancePercent,
            String storedPhoto) {}
}
