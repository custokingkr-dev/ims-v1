package com.custoking.ims.schoolcoreservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.custoking.ims.schoolcoreservice.persistence.StudentPresentation.*;
import static com.custoking.ims.schoolcoreservice.persistence.StudentValues.*;
import static com.custoking.ims.schoolcoreservice.persistence.StudentRequestActor.actorId;


/** Owns student promotions persistence; application commands own transaction boundaries. */
@Repository
public class StudentPromotionPersistence {
    private final JdbcClient jdbc;
    private final StudentStructurePersistence structure;
    private final StudentEnrollmentPersistence enrollment;

    public StudentPromotionPersistence(JdbcClient jdbc, StudentStructurePersistence structure, StudentEnrollmentPersistence enrollment) {
        this.jdbc = jdbc;
        this.structure = structure;
        this.enrollment = enrollment;
    }


    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> createPromotionBatch(Map<String, Object> request) {
        Long schoolId = requireLong(request.get("schoolId"), "schoolId is required");
        structure.requireSchool(schoolId);
        String sourceAcademicYearId = str(request.get("sourceAcademicYearId"), structure.currentAcademicYearId(schoolId));
        String targetAcademicYearId = requireText(request.get("targetAcademicYearId"), "targetAcademicYearId is required");
        String sourceClassId = str(request.get("sourceClassId"), "").trim();
        String sourceSectionId = str(request.get("sourceSectionId"), "").trim();
        String batchId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO student.student_promotion_batches (
                    id, school_id, source_academic_year_id, target_academic_year_id,
                    source_class_id, source_section_id, status, notes, created_by, created_at
                ) VALUES (
                    :id, :schoolId, :sourceYear, :targetYear, :sourceClassId, :sourceSectionId,
                    'DRAFT', :notes, :createdBy, now()
                )
                """)
                .param("id", batchId)
                .param("schoolId", schoolId)
                .param("sourceYear", sourceAcademicYearId)
                .param("targetYear", targetAcademicYearId)
                .param("sourceClassId", blankToNull(sourceClassId))
                .param("sourceSectionId", blankToNull(sourceSectionId))
                .param("notes", str(request.get("notes"), ""))
                .param("createdBy", actorId())
                .update();

        StringBuilder sql = new StringBuilder("""
                SELECT id, class_id, section_id
                FROM student.students
                WHERE school_id = :schoolId
                  AND academic_year_id = :sourceYear
                  AND deleted_at IS NULL
                """);
        if (!sourceClassId.isBlank()) sql.append(" AND class_id = :sourceClassId");
        if (!sourceSectionId.isBlank()) sql.append(" AND section_id = :sourceSectionId");
        sql.append(" ORDER BY full_name, id");
        var spec = jdbc.sql(sql.toString()).param("schoolId", schoolId).param("sourceYear", sourceAcademicYearId);
        if (!sourceClassId.isBlank()) spec = spec.param("sourceClassId", sourceClassId);
        if (!sourceSectionId.isBlank()) spec = spec.param("sourceSectionId", sourceSectionId);
        List<Map<String, Object>> students = spec.query((rs, n) -> row(
                "id", rs.getLong("id"),
                "classId", rs.getString("class_id"),
                "sectionId", rs.getString("section_id"))).list();
        for (Map<String, Object> student : students) {
            String currentClassId = str(student.get("classId"), "");
            String currentSectionId = str(student.get("sectionId"), "");
            String targetClassId = str(request.get("targetClassId"), "").trim();
            if (targetClassId.isBlank()) {
                targetClassId = nextClassId(currentClassId).orElse("");
            }
            String targetSectionId = str(request.get("targetSectionId"), "").trim();
            if (targetSectionId.isBlank() && !targetClassId.isBlank()) {
                String resolvedTargetClassId = targetClassId;
                targetSectionId = matchingSectionForTargetClass(schoolId, resolvedTargetClassId, currentSectionId)
                        .or(() -> firstActiveSection(schoolId, resolvedTargetClassId))
                        .orElse("");
            }
            String action = (!targetClassId.isBlank() && !targetSectionId.isBlank()) ? "PROMOTE" : "HOLD";
            jdbc.sql("""
                    INSERT INTO student.student_promotion_batch_items (
                        id, batch_id, school_id, student_id, source_class_id, source_section_id,
                        target_class_id, target_section_id, action, status, created_at, updated_at
                    ) VALUES (
                        :id, :batchId, :schoolId, :studentId, :sourceClassId, :sourceSectionId,
                        :targetClassId, :targetSectionId, :action, 'PENDING', now(), now()
                    )
                    """)
                    .param("id", UUID.randomUUID().toString())
                    .param("batchId", batchId)
                    .param("schoolId", schoolId)
                    .param("studentId", longValue(student.get("id"), null))
                    .param("sourceClassId", currentClassId)
                    .param("sourceSectionId", currentSectionId)
                    .param("targetClassId", blankToNull(targetClassId))
                    .param("targetSectionId", blankToNull(targetSectionId))
                    .param("action", action)
                    .update();
        }
        return promotionBatchDetail(batchId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> updatePromotionBatchItem(String batchId, String itemId, Map<String, Object> request) {
        requireDraftPromotionBatch(batchId);
        jdbc.sql("""
                UPDATE student.student_promotion_batch_items
                SET target_class_id = COALESCE(:targetClassId, target_class_id),
                    target_section_id = COALESCE(:targetSectionId, target_section_id),
                    action = COALESCE(:action, action),
                    reason = COALESCE(:reason, reason),
                    updated_at = now()
                WHERE id = :itemId AND batch_id = :batchId
                """)
                .param("batchId", batchId)
                .param("itemId", itemId)
                .param("targetClassId", blankToNull(str(request.get("targetClassId"), "")))
                .param("targetSectionId", blankToNull(str(request.get("targetSectionId"), "")))
                .param("action", blankToNull(str(request.get("action"), "")))
                .param("reason", blankToNull(str(request.get("reason"), "")))
                .update();
        return promotionBatchDetail(batchId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> applyPromotionBatch(String batchId) {
        Map<String, Object> batch = requireDraftPromotionBatch(batchId);
        String targetYear = str(batch.get("targetAcademicYearId"), "");
        List<Map<String, Object>> items = promotionBatchItems(batchId);
        int promoted = 0;
        int skipped = 0;
        for (Map<String, Object> item : items) {
            String action = str(item.get("action"), "PROMOTE");
            String targetClassId = str(item.get("targetClassId"), "");
            String targetSectionId = str(item.get("targetSectionId"), "");
            Long studentId = longValue(item.get("studentId"), null);
            if (!"PROMOTE".equalsIgnoreCase(action) || targetClassId.isBlank() || targetSectionId.isBlank() || studentId == null) {
                jdbc.sql("UPDATE student.student_promotion_batch_items SET status = 'SKIPPED', updated_at = now() WHERE id = :id")
                        .param("id", item.get("id"))
                        .update();
                skipped++;
                continue;
            }
            jdbc.sql("""
                    UPDATE student.students
                    SET class_id = :classId,
                        section_id = :sectionId,
                        academic_year_id = :academicYearId,
                        updated_at = now(),
                        updated_by = :updatedBy,
                        version = version + 1
                    WHERE id = :studentId AND deleted_at IS NULL
                    """)
                    .param("studentId", studentId)
                    .param("classId", targetClassId)
                    .param("sectionId", targetSectionId)
                    .param("academicYearId", targetYear)
                    .param("updatedBy", actorId() == null ? null : String.valueOf(actorId()))
                    .update();
            enrollment.closeActiveEnrollment(studentId, "Promoted by batch " + batchId);
            enrollment.recordEnrollmentFromCurrentStudent(studentId, "Promoted by batch " + batchId, "PROMOTION", batchId);
            enrollment.emitStudentUpserted(studentId);
            jdbc.sql("UPDATE student.student_promotion_batch_items SET status = 'APPLIED', updated_at = now() WHERE id = :id")
                    .param("id", item.get("id"))
                    .update();
            promoted++;
        }
        jdbc.sql("""
                UPDATE student.student_promotion_batches
                SET status = 'APPLIED', applied_by = :appliedBy, applied_at = now()
                WHERE id = :batchId
                """)
                .param("batchId", batchId)
                .param("appliedBy", actorId())
                .update();
        Map<String, Object> detail = promotionBatchDetail(batchId);
        detail.put("promoted", promoted);
        detail.put("skipped", skipped);
        return detail;
    }

    public Map<String, Object> promotionBatchDetail(String batchId) {
        Map<String, Object> batch = jdbc.sql("""
                SELECT id, school_id, source_academic_year_id, target_academic_year_id,
                       source_class_id, source_section_id, status, notes, created_by, created_at,
                       applied_by, applied_at
                FROM student.student_promotion_batches
                WHERE id = :batchId
                """)
                .param("batchId", batchId)
                .query((rs, n) -> row(
                        "id", rs.getString("id"),
                        "schoolId", rs.getLong("school_id"),
                        "sourceAcademicYearId", rs.getString("source_academic_year_id"),
                        "targetAcademicYearId", rs.getString("target_academic_year_id"),
                        "sourceClassId", rs.getString("source_class_id"),
                        "sourceSectionId", rs.getString("source_section_id"),
                        "status", rs.getString("status"),
                        "notes", rs.getString("notes"),
                        "createdBy", rs.getObject("created_by"),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class),
                        "appliedBy", rs.getObject("applied_by"),
                        "appliedAt", rs.getObject("applied_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Promotion batch not found"));
        batch.put("items", promotionBatchItems(batchId));
        return batch;
    }

    private List<Map<String, Object>> promotionBatchItems(String batchId) {
        return jdbc.sql("""
                SELECT i.id, i.student_id, s.full_name, s.admission_no,
                       i.source_class_id, sc.name AS source_class_name,
                       i.source_section_id, ss.name AS source_section_name,
                       i.target_class_id, tc.name AS target_class_name,
                       i.target_section_id, ts.name AS target_section_name,
                       i.action, i.status, i.reason
                FROM student.student_promotion_batch_items i
                JOIN student.students s ON s.id = i.student_id
                LEFT JOIN tenant_school.school_classes sc ON sc.id = i.source_class_id
                LEFT JOIN tenant_school.school_sections ss ON ss.id = i.source_section_id
                LEFT JOIN tenant_school.school_classes tc ON tc.id = i.target_class_id
                LEFT JOIN tenant_school.school_sections ts ON ts.id = i.target_section_id
                WHERE i.batch_id = :batchId
                ORDER BY lower(s.full_name), s.id
                """)
                .param("batchId", batchId)
                .query((rs, n) -> row(
                        "id", rs.getString("id"),
                        "studentId", rs.getLong("student_id"),
                        "studentName", rs.getString("full_name"),
                        "admissionNumber", rs.getString("admission_no"),
                        "sourceClassId", rs.getString("source_class_id"),
                        "sourceClassName", rs.getString("source_class_name"),
                        "sourceSectionId", rs.getString("source_section_id"),
                        "sourceSectionName", rs.getString("source_section_name"),
                        "targetClassId", rs.getString("target_class_id"),
                        "targetClassName", rs.getString("target_class_name"),
                        "targetSectionId", rs.getString("target_section_id"),
                        "targetSectionName", rs.getString("target_section_name"),
                        "action", rs.getString("action"),
                        "status", rs.getString("status"),
                        "reason", rs.getString("reason")))
                .list();
    }

    private Map<String, Object> requireDraftPromotionBatch(String batchId) {
        Map<String, Object> batch = jdbc.sql("""
                SELECT id, school_id, target_academic_year_id, status
                FROM student.student_promotion_batches
                WHERE id = :batchId
                """)
                .param("batchId", batchId)
                .query((rs, n) -> row(
                        "id", rs.getString("id"),
                        "schoolId", rs.getLong("school_id"),
                        "targetAcademicYearId", rs.getString("target_academic_year_id"),
                        "status", rs.getString("status")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Promotion batch not found"));
        if (!"DRAFT".equalsIgnoreCase(str(batch.get("status"), ""))) {
            throw new IllegalArgumentException("Promotion batch is not editable");
        }
        return batch;
    }

    private Optional<String> nextClassId(String classId) {
        Integer sortOrder = jdbc.sql("SELECT sort_order FROM tenant_school.school_classes WHERE id = :classId")
                .param("classId", classId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        if (sortOrder == null) return Optional.empty();
        return jdbc.sql("""
                SELECT id
                FROM tenant_school.school_classes
                WHERE sort_order > :sortOrder
                ORDER BY sort_order, name
                LIMIT 1
                """)
                .param("sortOrder", sortOrder)
                .query(String.class)
                .optional();
    }

    private Optional<String> matchingSectionForTargetClass(Long schoolId, String targetClassId, String sourceSectionId) {
        String sourceName = jdbc.sql("SELECT name FROM tenant_school.school_sections WHERE id = :sourceSectionId")
                .param("sourceSectionId", sourceSectionId)
                .query(String.class)
                .optional()
                .orElse("");
        if (sourceName.isBlank()) return Optional.empty();
        return jdbc.sql("""
                SELECT id
                FROM tenant_school.school_sections
                WHERE school_id = :schoolId
                  AND school_class_id = :targetClassId
                  AND lower(name) = lower(:sourceName)
                  AND active = true
                ORDER BY id
                LIMIT 1
                """)
                .param("schoolId", schoolId)
                .param("targetClassId", targetClassId)
                .param("sourceName", sourceName)
                .query(String.class)
                .optional();
    }

    private Optional<String> firstActiveSection(Long schoolId, String classId) {
        return jdbc.sql("""
                SELECT id
                FROM tenant_school.school_sections
                WHERE school_id = :schoolId
                  AND school_class_id = :classId
                  AND active = true
                ORDER BY name, id
                LIMIT 1
                """)
                .param("schoolId", schoolId)
                .param("classId", classId)
                .query(String.class)
                .optional();
    }
}
