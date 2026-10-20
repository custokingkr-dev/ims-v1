package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.custoking.ims.schoolcoreservice.persistence.StudentPresentation.*;
import static com.custoking.ims.schoolcoreservice.persistence.StudentValues.*;
import static com.custoking.ims.schoolcoreservice.persistence.StudentRequestActor.actorId;


/** Enrollment mutation and student-upsert outbox writes in the command transaction. */
@Repository
public class StudentEnrollmentPersistence {
    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final StudentStructurePersistence structure;

    public StudentEnrollmentPersistence(JdbcClient jdbc, OutboxWriter outbox, StudentStructurePersistence structure) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.structure = structure;
    }


    /** Emits {@code student.upserted.v1} for the reporting dim_student projection (SP5). */
    void emitStudentUpserted(Long id) {
        Map<String, Object> row = jdbc.sql("""
                SELECT id, school_id, admission_no, full_name, roll_no, class_id, section_id,
                       father_contact, phone, deleted_at, attendance_percent, father_name, aggregate_version
                FROM student.students
                WHERE id = :id
                """)
                .param("id", id)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("schoolId", rs.getLong("school_id"));
                    m.put("admissionNo", rs.getString("admission_no"));
                    m.put("fullName", rs.getString("full_name"));
                    m.put("rollNo", rs.getString("roll_no"));
                    m.put("classId", rs.getString("class_id"));
                    m.put("sectionId", rs.getString("section_id"));
                    m.put("parentContact", rs.getString("father_contact"));
                    m.put("phone", rs.getString("phone"));
                    m.put("active", rs.getObject("deleted_at") == null);
                    m.put("attendancePercent", rs.getObject("attendance_percent", Double.class));
                    m.put("fatherName", rs.getString("father_name"));
                    m.put("aggregateVersion", rs.getLong("aggregate_version"));
                    return m;
                })
                .single();
        Long schoolId = ((Number) row.get("schoolId")).longValue();
        outbox.append("student.upserted.v1", "StudentUpserted:" + id, "Student", String.valueOf(id), schoolId, row);
    }

    void recordEnrollment(Long studentId, Long schoolId, String academicYearId, String classId,
                                  String sectionId, String rollNo, String status, String reason,
                                  String sourceType, String sourceId) {
        jdbc.sql("""
                INSERT INTO student.student_enrollments (
                    id, student_id, school_id, academic_year_id, class_id, section_id, roll_no,
                    status, effective_from, reason, source_type, source_id, created_by, created_at, updated_at
                ) VALUES (
                    :id, :studentId, :schoolId, :academicYearId, :classId, :sectionId, :rollNo,
                    :status, CURRENT_DATE, :reason, :sourceType, :sourceId, :createdBy, now(), now()
                )
                """)
                .param("id", UUID.randomUUID().toString())
                .param("studentId", studentId)
                .param("schoolId", schoolId)
                .param("academicYearId", academicYearId)
                .param("classId", classId)
                .param("sectionId", sectionId)
                .param("rollNo", rollNo)
                .param("status", status)
                .param("reason", reason)
                .param("sourceType", sourceType)
                .param("sourceId", sourceId)
                .param("createdBy", actorId())
                .update();
    }

    void recordEnrollmentFromCurrentStudent(Long studentId, String reason, String sourceType, String sourceId) {
        Map<String, Object> current = jdbc.sql("""
                SELECT school_id, academic_year_id, class_id, section_id, roll_no
                FROM student.students
                WHERE id = :studentId
                """)
                .param("studentId", studentId)
                .query((rs, n) -> row(
                        "schoolId", rs.getLong("school_id"),
                        "academicYearId", rs.getString("academic_year_id"),
                        "classId", rs.getString("class_id"),
                        "sectionId", rs.getString("section_id"),
                        "rollNo", rs.getString("roll_no")))
                .single();
        recordEnrollment(studentId, longValue(current.get("schoolId"), null),
                str(current.get("academicYearId"), structure.currentAcademicYearId(longValue(current.get("schoolId"), null))),
                str(current.get("classId"), ""), str(current.get("sectionId"), ""),
                str(current.get("rollNo"), ""), "ACTIVE", reason, sourceType, sourceId);
    }

    void closeActiveEnrollment(Long studentId, String reason) {
        jdbc.sql("""
                UPDATE student.student_enrollments
                SET status = CASE WHEN status = 'ACTIVE' THEN 'ENDED' ELSE status END,
                    effective_to = COALESCE(effective_to, CURRENT_DATE),
                    reason = COALESCE(:reason, reason),
                    updated_at = now()
                WHERE student_id = :studentId
                  AND effective_to IS NULL
                """)
                .param("studentId", studentId)
                .param("reason", reason)
                .update();
    }

    void refreshActiveEnrollmentRollNo(Long studentId, String rollNo) {
        jdbc.sql("""
                UPDATE student.student_enrollments
                SET roll_no = :rollNo, updated_at = now()
                WHERE student_id = :studentId
                  AND status = 'ACTIVE'
                  AND effective_to IS NULL
                """)
                .param("studentId", studentId)
                .param("rollNo", rollNo)
                .update();
    }
}
