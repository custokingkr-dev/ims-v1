package com.custoking.ims.schoolcoreservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps the normalized guardian model in step while the student compatibility APIs still accept
 * the legacy father/mother columns. Normalized guardian writes continue to synchronize in the
 * opposite direction through {@link GuardianConsentRepository}.
 */
final class LegacyGuardianSynchronizer {

    private final JdbcClient jdbc;

    LegacyGuardianSynchronizer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void syncFromLegacy(Long studentId) {
        LegacyParents parents = jdbc.sql("""
                        SELECT school_id, father_name, father_contact, mother_name
                        FROM student.students
                        WHERE id = :studentId AND deleted_at IS NULL
                        """)
                .param("studentId", studentId)
                .query((rs, rowNum) -> new LegacyParents(
                        rs.getLong("school_id"),
                        rs.getString("father_name"),
                        rs.getString("father_contact"),
                        rs.getString("mother_name")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));

        syncRelationship(studentId, parents.schoolId(), "FATHER",
                parents.fatherName(), parents.fatherContact());
        syncRelationship(studentId, parents.schoolId(), "MOTHER",
                parents.motherName(), null);
    }

    private void syncRelationship(Long studentId, Long schoolId, String relationship,
                                  String legacyName, String legacyPhone) {
        String name = trimmedOrNull(legacyName);
        String phone = trimmedOrNull(legacyPhone);
        boolean hasLegacyValue = name != null || ("FATHER".equals(relationship) && phone != null);
        if (!hasLegacyValue) {
            unlinkRelationship(studentId, schoolId, relationship);
            return;
        }

        Optional<GuardianLink> existing = jdbc.sql("""
                        SELECT link.id AS link_id, guardian.id AS guardian_id
                        FROM student.student_guardians link
                        JOIN student.guardians guardian ON guardian.id = link.guardian_id
                        WHERE link.student_id = :studentId
                          AND link.school_id = :schoolId
                          AND link.relationship = :relationship
                        ORDER BY (guardian.status = 'ACTIVE') DESC,
                                 link.is_primary DESC, link.updated_at DESC, link.id
                        LIMIT 1
                        FOR UPDATE OF link, guardian
                        """)
                .param("studentId", studentId)
                .param("schoolId", schoolId)
                .param("relationship", relationship)
                .query((rs, rowNum) -> new GuardianLink(
                        rs.getString("link_id"), rs.getString("guardian_id")))
                .optional();

        if (existing.isPresent()) {
            updateExisting(existing.get(), relationship, name, phone);
            return;
        }
        insertNew(studentId, schoolId, relationship, name, phone);
    }

    private void updateExisting(GuardianLink link, String relationship, String name, String phone) {
        OffsetDateTime now = OffsetDateTime.now();
        if ("FATHER".equals(relationship)) {
            jdbc.sql("""
                            UPDATE student.guardians
                            SET full_name = :fullName, phone = :phone, status = 'ACTIVE',
                                updated_at = :now, version = version + 1
                            WHERE id = :guardianId
                            """)
                    .param("fullName", name == null ? "" : name)
                    .param("phone", phone)
                    .param("now", now)
                    .param("guardianId", link.guardianId())
                    .update();
        } else {
            // The legacy student shape has no mother-contact field. Preserve normalized contact
            // data rather than silently erasing information that this API cannot represent.
            jdbc.sql("""
                            UPDATE student.guardians
                            SET full_name = :fullName, status = 'ACTIVE',
                                updated_at = :now, version = version + 1
                            WHERE id = :guardianId
                            """)
                    .param("fullName", name)
                    .param("now", now)
                    .param("guardianId", link.guardianId())
                    .update();
        }
        // Preserve primary/permission flags and the guardian id referenced by consent events.
        jdbc.sql("""
                        UPDATE student.student_guardians
                        SET updated_at = :now, version = version + 1
                        WHERE id = :linkId
                        """)
                .param("now", now)
                .param("linkId", link.linkId())
                .update();
    }

    private void insertNew(Long studentId, Long schoolId, String relationship,
                           String name, String phone) {
        String guardianId = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.sql("""
                        INSERT INTO student.guardians
                            (id, school_id, full_name, phone, status, created_at, updated_at, version)
                        VALUES
                            (:id, :schoolId, :fullName, :phone, 'ACTIVE', :now, :now, 0)
                        """)
                .param("id", guardianId)
                .param("schoolId", schoolId)
                .param("fullName", name == null ? "" : name)
                .param("phone", "FATHER".equals(relationship) ? phone : null)
                .param("now", now)
                .update();

        boolean primary = jdbc.sql("""
                        SELECT NOT EXISTS (
                            SELECT 1 FROM student.student_guardians
                            WHERE student_id = :studentId AND is_primary
                        )
                        """)
                .param("studentId", studentId)
                .query(Boolean.class)
                .single();
        jdbc.sql("""
                        INSERT INTO student.student_guardians
                            (id, school_id, student_id, guardian_id, relationship, is_primary,
                             receives_notifications, can_view_academic, can_manage_fees,
                             pickup_authorized, created_at, updated_at, version)
                        VALUES
                            (:id, :schoolId, :studentId, :guardianId, :relationship, :primary,
                             true, true, false, false, :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("schoolId", schoolId)
                .param("studentId", studentId)
                .param("guardianId", guardianId)
                .param("relationship", relationship)
                .param("primary", primary)
                .param("now", now)
                .update();
    }

    private void unlinkRelationship(Long studentId, Long schoolId, String relationship) {
        jdbc.sql("""
                        DELETE FROM student.student_guardians
                        WHERE student_id = :studentId
                          AND school_id = :schoolId
                          AND relationship = :relationship
                        """)
                .param("studentId", studentId)
                .param("schoolId", schoolId)
                .param("relationship", relationship)
                .update();
    }

    private static String trimmedOrNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record LegacyParents(Long schoolId, String fatherName, String fatherContact,
                                 String motherName) {
    }

    private record GuardianLink(String linkId, String guardianId) {
    }
}
