package com.custoking.ims.schoolcoreservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    SyncResult syncFromLegacy(Long studentId) {
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

        Set<Long> affectedStudents = new LinkedHashSet<>();
        Set<Long> projectionChanges = new LinkedHashSet<>();
        for (SyncResult result : List.of(
                syncRelationship(studentId, parents.schoolId(), "FATHER",
                        parents.fatherName(), parents.fatherContact()),
                syncRelationship(studentId, parents.schoolId(), "MOTHER",
                        parents.motherName(), null))) {
            affectedStudents.addAll(result.affectedStudentIds());
            projectionChanges.addAll(result.projectionChangedStudentIds());
        }
        return new SyncResult(Set.copyOf(affectedStudents), Set.copyOf(projectionChanges));
    }

    private SyncResult syncRelationship(Long studentId, Long schoolId, String relationship,
                                        String legacyName, String legacyPhone) {
        String name = trimmedOrNull(legacyName);
        String phone = trimmedOrNull(legacyPhone);
        boolean hasLegacyValue = name != null || ("FATHER".equals(relationship) && phone != null);
        if (!hasLegacyValue) {
            unlinkRelationship(studentId, schoolId, relationship);
            return SyncResult.none();
        }

        Optional<GuardianLink> existing = jdbc.sql("""
                        SELECT guardian.id AS guardian_id
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
                .query((rs, rowNum) -> new GuardianLink(rs.getString("guardian_id")))
                .optional();

        if (existing.isPresent()) {
            return updateExisting(existing.get(), relationship, name, phone);
        }
        insertNew(studentId, schoolId, relationship, name, phone);
        return SyncResult.none();
    }

    private SyncResult updateExisting(GuardianLink link, String relationship, String name, String phone) {
        OffsetDateTime now = OffsetDateTime.now();
        int updated;
        if ("FATHER".equals(relationship)) {
            updated = jdbc.sql("""
                            UPDATE student.guardians
                            SET full_name = :fullName, phone = :phone, status = 'ACTIVE',
                                contact_verified_at = CASE
                                    WHEN phone IS DISTINCT FROM :phone THEN NULL
                                    ELSE contact_verified_at
                                END,
                                updated_at = :now, version = version + 1
                            WHERE id = :guardianId
                              AND (full_name IS DISTINCT FROM :fullName
                                   OR phone IS DISTINCT FROM :phone
                                   OR status IS DISTINCT FROM 'ACTIVE')
                            """)
                    .param("fullName", name == null ? "" : name)
                    .param("phone", phone)
                    .param("now", now)
                    .param("guardianId", link.guardianId())
                    .update();
        } else {
            // The legacy student shape has no mother-contact field. Preserve normalized contact
            // data rather than silently erasing information that this API cannot represent.
            updated = jdbc.sql("""
                            UPDATE student.guardians
                            SET full_name = :fullName, status = 'ACTIVE',
                                updated_at = :now, version = version + 1
                            WHERE id = :guardianId
                              AND (full_name IS DISTINCT FROM :fullName
                                   OR status IS DISTINCT FROM 'ACTIVE')
                            """)
                    .param("fullName", name)
                    .param("now", now)
                    .param("guardianId", link.guardianId())
                    .update();
        }
        // The identity is shared, while relationship and permission fields belong to each link.
        // Preserve every link row and the guardian id referenced by append-only consent events.
        if (updated == 0) return SyncResult.none();
        Set<Long> affectedStudents = linkedStudentIds(link.guardianId());
        Set<Long> projectionChanges = refreshLegacyProjectionForLinkedStudents(link.guardianId());
        return new SyncResult(affectedStudents, projectionChanges);
    }

    private Set<Long> linkedStudentIds(String guardianId) {
        return Set.copyOf(jdbc.sql("""
                        SELECT DISTINCT link.student_id
                        FROM student.student_guardians link
                        JOIN student.students student_row ON student_row.id = link.student_id
                        WHERE link.guardian_id = :guardianId AND student_row.deleted_at IS NULL
                        """)
                .param("guardianId", guardianId)
                .query(Long.class)
                .list());
    }

    /**
     * Rebuilds compatibility columns for every student connected to a changed shared identity.
     * The three correlated selections intentionally match V24's ordering rules exactly.
     */
    private Set<Long> refreshLegacyProjectionForLinkedStudents(String guardianId) {
        List<Long> changed = jdbc.sql("""
                        WITH affected_students AS (
                            SELECT DISTINCT student_id
                            FROM student.student_guardians
                            WHERE guardian_id = :guardianId
                        ), parent_values AS (
                            SELECT student_row.id,
                                   (SELECT guardian.full_name
                                    FROM student.student_guardians link
                                    JOIN student.guardians guardian ON guardian.id = link.guardian_id
                                    WHERE link.student_id = student_row.id
                                      AND link.relationship = 'FATHER'
                                      AND guardian.status = 'ACTIVE'
                                    ORDER BY link.is_primary DESC, link.updated_at DESC, link.id
                                    LIMIT 1) AS father_name,
                                   (SELECT guardian.phone
                                    FROM student.student_guardians link
                                    JOIN student.guardians guardian ON guardian.id = link.guardian_id
                                    WHERE link.student_id = student_row.id
                                      AND link.relationship = 'FATHER'
                                      AND guardian.status = 'ACTIVE'
                                    ORDER BY link.is_primary DESC, link.updated_at DESC, link.id
                                    LIMIT 1) AS father_contact,
                                   (SELECT guardian.full_name
                                    FROM student.student_guardians link
                                    JOIN student.guardians guardian ON guardian.id = link.guardian_id
                                    WHERE link.student_id = student_row.id
                                      AND link.relationship = 'MOTHER'
                                      AND guardian.status = 'ACTIVE'
                                    ORDER BY link.is_primary DESC, link.updated_at DESC, link.id
                                    LIMIT 1) AS mother_name
                            FROM student.students student_row
                            JOIN affected_students affected ON affected.student_id = student_row.id
                            WHERE student_row.deleted_at IS NULL
                        )
                        UPDATE student.students student_row
                        SET father_name = parent.father_name,
                            father_contact = parent.father_contact,
                            mother_name = parent.mother_name,
                            updated_at = now(),
                            version = version + 1
                        FROM parent_values parent
                        WHERE student_row.id = parent.id
                          AND (NULLIF(btrim(COALESCE(student_row.father_name, '')), '')
                                   IS DISTINCT FROM NULLIF(btrim(COALESCE(parent.father_name, '')), '')
                               OR regexp_replace(COALESCE(student_row.father_contact, ''), '[^0-9]', '', 'g')
                                   IS DISTINCT FROM regexp_replace(COALESCE(parent.father_contact, ''), '[^0-9]', '', 'g')
                               OR NULLIF(btrim(COALESCE(student_row.mother_name, '')), '')
                                   IS DISTINCT FROM NULLIF(btrim(COALESCE(parent.mother_name, '')), ''))
                        RETURNING student_row.id
                        """)
                .param("guardianId", guardianId)
                .query(Long.class)
                .list();
        return Set.copyOf(changed);
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
        List<String> unlinkedGuardianIds = jdbc.sql("""
                        DELETE FROM student.student_guardians link
                        WHERE student_id = :studentId
                          AND school_id = :schoolId
                          AND relationship = :relationship
                        RETURNING link.guardian_id
                        """)
                .param("studentId", studentId)
                .param("schoolId", schoolId)
                .param("relationship", relationship)
                .query(String.class)
                .list();
        if (unlinkedGuardianIds.isEmpty()) return;
        jdbc.sql("""
                        UPDATE student.guardians guardian
                        SET status = 'INACTIVE', updated_at = now(), version = version + 1
                        WHERE guardian.id IN (:guardianIds)
                          AND NOT EXISTS (
                              SELECT 1 FROM student.student_guardians link
                              WHERE link.guardian_id = guardian.id
                          )
                        """)
                .param("guardianIds", unlinkedGuardianIds)
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

    private record GuardianLink(String guardianId) {
    }

    record SyncResult(Set<Long> affectedStudentIds, Set<Long> projectionChangedStudentIds) {
        private static SyncResult none() {
            return new SyncResult(Set.of(), Set.of());
        }
    }
}
