package com.custoking.ims.schoolcoreservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.custoking.ims.schoolcoreservice.persistence.StudentPresentation.*;
import static com.custoking.ims.schoolcoreservice.persistence.StudentValues.*;
import static com.custoking.ims.schoolcoreservice.persistence.StudentRequestActor.actorId;


/** Shared school/class/calendar persistence under the caller's transaction and tenant context. */
@Repository
public class StudentStructurePersistence {
    private final JdbcClient jdbc;

    public StudentStructurePersistence(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }


    Optional<Map<String, Object>> classByName(String className) {
        String requestedName = str(className, "").trim();
        String canonicalName = canonicalClassLookupName(requestedName);
        int requestedSortOrder = classSortOrder(requestedName);
        String numericName = requestedSortOrder > 0 ? String.valueOf(requestedSortOrder) : "";
        return jdbc.sql("""
                SELECT id, name, sort_order
                FROM tenant_school.school_classes
                WHERE lower(name) = lower(:name)
                   OR lower(id) = lower(:name)
                   OR lower(id) = lower(:canonicalName)
                   OR lower(name) = lower(:canonicalName)
                   OR (:sortOrder > 0 AND lower(name) = lower(:numericName))
                   OR (:sortOrder > 0 AND lower(id) = lower(:numericName))
                ORDER BY
                    CASE
                        WHEN lower(name) = lower(:name) THEN 0
                        WHEN lower(id) = lower(:name) THEN 1
                        WHEN lower(id) = lower(:canonicalName) THEN 2
                        WHEN lower(name) = lower(:canonicalName) THEN 3
                        WHEN :sortOrder > 0 AND lower(name) = lower(:numericName) THEN 4
                        WHEN :sortOrder > 0 AND lower(id) = lower(:numericName) THEN 5
                        ELSE 6
                    END,
                    sort_order ASC,
                    id ASC
                LIMIT 1
                """)
                .param("name", requestedName)
                .param("canonicalName", canonicalName)
                .param("numericName", numericName)
                .param("sortOrder", requestedSortOrder)
                .query((rs, rowNum) -> row("id", rs.getString("id"), "name", rs.getString("name"), "sortOrder", rs.getInt("sort_order")))
                .optional();
    }

    Optional<Map<String, Object>> classById(String id) {
        return jdbc.sql("SELECT id, name, sort_order FROM tenant_school.school_classes WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> row("id", rs.getString("id"), "name", rs.getString("name"), "sortOrder", rs.getInt("sort_order")))
                .optional();
    }

    Optional<Map<String, Object>> sectionByIdForClassSchool(Long schoolId, String classId, String sectionId) {
        return jdbc.sql("""
                SELECT id, name FROM tenant_school.school_sections
                WHERE id = :sectionId AND school_class_id = :classId AND school_id = :schoolId
                """)
                .param("sectionId", sectionId)
                .param("classId", classId)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> row("id", rs.getString("id"), "name", rs.getString("name")))
                .optional();
    }

    Optional<Map<String, Object>> activeClassForSchool(Long schoolId, Map<String, Object> schoolClass) {
        String classId = String.valueOf(schoolClass.get("id"));
        return jdbc.sql("""
                SELECT sc.id, sc.name, sc.sort_order
                FROM tenant_school.school_classes sc
                WHERE sc.id = :classId
                  AND EXISTS (
                      SELECT 1
                      FROM tenant_school.school_sections ss
                      WHERE ss.school_id = :schoolId
                        AND ss.school_class_id = sc.id
                        AND ss.active = true
                  )
                LIMIT 1
                """)
                .param("schoolId", schoolId)
                .param("classId", classId)
                .query((rs, rowNum) -> row("id", rs.getString("id"), "name", rs.getString("name"), "sortOrder", rs.getInt("sort_order")))
                .optional();
    }

    Optional<Map<String, Object>> activeSectionByNameForClassSchool(Long schoolId, String classId, String requestedSectionName) {
        String sectionName = str(requestedSectionName, "").trim();
        if (sectionName.isBlank()) return Optional.empty();
        return jdbc.sql("""
                SELECT id, name
                FROM tenant_school.school_sections
                WHERE school_id = :schoolId
                  AND school_class_id = :classId
                  AND lower(name) = lower(:name)
                  AND active = true
                ORDER BY id ASC
                LIMIT 1
                """)
                .param("schoolId", schoolId)
                .param("classId", classId)
                .param("name", sectionName)
                .query((rs, rowNum) -> row("id", rs.getString("id"), "name", rs.getString("name")))
                .optional();
    }

    Map<String, Object> getOrCreateSection(Long schoolId, String classId, String requestedSectionName) {
        String sectionName = str(requestedSectionName, "A").trim();
        if (sectionName.isBlank()) sectionName = "A";
        String normalized = sectionName.toUpperCase(Locale.ROOT);
        Optional<Map<String, Object>> existing = jdbc.sql("""
                SELECT id, name FROM tenant_school.school_sections
                WHERE school_id = :schoolId AND school_class_id = :classId AND lower(name) = lower(:name)
                ORDER BY active DESC, id ASC
                LIMIT 1
                """)
                .param("schoolId", schoolId)
                .param("classId", classId)
                .param("name", normalized)
                .query((rs, rowNum) -> row("id", rs.getString("id"), "name", rs.getString("name")))
                .optional();
        if (existing.isPresent()) return existing.get();
        String id = schoolId + "-" + classId + "-" + normalized;
        jdbc.sql("""
                INSERT INTO tenant_school.school_sections(id, name, teacher_name, active, school_class_id, school_id)
                VALUES (:id, :name, '', true, :classId, :schoolId)
                """)
                .param("id", id)
                .param("name", normalized)
                .param("classId", classId)
                .param("schoolId", schoolId)
                .update();
        return row("id", id, "name", normalized);
    }

    String currentAcademicYearId() {
        return AcademicCalendar.activeOrCurrentAcademicYearId(jdbc);
    }

    String currentAcademicYearId(Long schoolId) {
        return schoolId == null ? currentAcademicYearId() : AcademicCalendar.currentAcademicYearId(jdbc, schoolId);
    }

    void requireSchool(Long schoolId) {
        Long count = jdbc.sql("SELECT COUNT(*) FROM tenant_school.schools WHERE id = :schoolId")
                .param("schoolId", schoolId)
                .query(Long.class)
                .single();
        if (count == null || count == 0) {
            throw new IllegalArgumentException("School not found");
        }
    }

    String schoolStorageId(Long schoolId) {
        return jdbc.sql("SELECT school_uid::text FROM tenant_school.schools WHERE id = :schoolId")
                .param("schoolId", schoolId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("School not found"));
    }

    long countBySection(String sectionId) {
        return jdbc.sql("SELECT COUNT(*) FROM student.students WHERE section_id = :sectionId AND deleted_at IS NULL")
                .param("sectionId", sectionId)
                .query(Long.class)
                .single();
    }

    public long count(Long schoolId) {
        if (schoolId == null) {
            return jdbc.sql("SELECT count(*) FROM student.students WHERE deleted_at IS NULL")
                    .query(Long.class)
                    .single();
        }
        return jdbc.sql("SELECT count(*) FROM student.students WHERE deleted_at IS NULL AND school_id = :schoolId")
                .param("schoolId", schoolId)
                .query(Long.class)
                .single();
    }
}
