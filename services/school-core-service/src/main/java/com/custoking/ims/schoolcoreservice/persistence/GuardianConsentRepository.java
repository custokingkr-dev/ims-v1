package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Repository
public class GuardianConsentRepository {

    private static final Set<String> RELATIONSHIPS = Set.of(
            "FATHER", "MOTHER", "GUARDIAN", "GRANDPARENT", "SIBLING", "OTHER");
    private static final Set<String> PURPOSES = Set.of(
            "STUDENT_PHOTO", "ID_CARD_PRODUCTION", "SCHOOL_COMMUNICATIONS",
            "APAAR_REGISTRATION", "DATA_CORRECTION");
    private static final Set<String> CONSENT_STATUSES = Set.of(
            "PENDING", "GRANTED", "DENIED", "WITHDRAWN", "EXPIRED");
    private static final Set<String> EVIDENCE_SOURCES = Set.of(
            "SCHOOL_RECORD", "SIGNED_FORM", "GUARDIAN_PORTAL", "EMAIL", "SMS", "WHATSAPP", "OTHER");

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final StudentReviewInvalidationService reviewInvalidation;

    public GuardianConsentRepository(JdbcClient jdbc, OutboxWriter outbox) {
        this(jdbc, outbox, new StudentReviewInvalidationService(jdbc, outbox));
    }

    @Autowired
    public GuardianConsentRepository(JdbcClient jdbc, OutboxWriter outbox,
                                     StudentReviewInvalidationService reviewInvalidation) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.reviewInvalidation = reviewInvalidation;
    }

    public Map<String, Object> overview(Long studentId) {
        Long schoolId = schoolId(studentId);
        List<Map<String, Object>> guardians = jdbc.sql("""
                SELECT g.id, g.full_name, g.phone, g.email, g.preferred_language,
                       g.contact_verified_at, g.status, g.version,
                       sg.relationship, sg.is_primary, sg.receives_notifications, sg.version AS link_version,
                       sg.can_view_academic, sg.can_manage_fees, sg.pickup_authorized
                FROM student.student_guardians sg
                JOIN student.guardians g ON g.id = sg.guardian_id
                WHERE sg.student_id = :studentId AND sg.school_id = :schoolId
                ORDER BY sg.is_primary DESC, g.full_name, g.id
                """)
                .param("studentId", studentId)
                .param("schoolId", schoolId)
                .query((rs, n) -> row(
                        "id", rs.getString("id"),
                        "fullName", rs.getString("full_name"),
                        "phone", rs.getString("phone"),
                        "email", rs.getString("email"),
                        "preferredLanguage", rs.getString("preferred_language"),
                        "contactVerifiedAt", rs.getObject("contact_verified_at", OffsetDateTime.class),
                        "status", rs.getString("status"),
                        "version", rs.getLong("version"),
                        "linkVersion", rs.getLong("link_version"),
                        "relationship", rs.getString("relationship"),
                        "primary", rs.getBoolean("is_primary"),
                        "receivesNotifications", rs.getBoolean("receives_notifications"),
                        "canViewAcademic", rs.getBoolean("can_view_academic"),
                        "canManageFees", rs.getBoolean("can_manage_fees"),
                        "pickupAuthorized", rs.getBoolean("pickup_authorized")))
                .list();

        List<Map<String, Object>> effectiveConsents = jdbc.sql("""
                SELECT DISTINCT ON (c.purpose)
                       c.id, c.guardian_id, g.full_name AS guardian_name, c.purpose,
                       CASE WHEN c.status = 'GRANTED' AND c.expires_at <= now()
                            THEN 'EXPIRED' ELSE c.status END AS status,
                       c.lawful_basis, c.notice_version, c.evidence_source, c.evidence_reference,
                       c.notes, c.effective_at, c.expires_at, c.recorded_by, c.recorded_at
                FROM student.student_consent_events c
                LEFT JOIN student.guardians g ON g.id = c.guardian_id
                WHERE c.student_id = :studentId AND c.school_id = :schoolId
                ORDER BY c.purpose, c.effective_at DESC, c.recorded_at DESC, c.id DESC
                """)
                .param("studentId", studentId)
                .param("schoolId", schoolId)
                .query((rs, n) -> consentRow(rs))
                .list();

        List<Map<String, Object>> consentHistory = jdbc.sql("""
                SELECT c.id, c.guardian_id, g.full_name AS guardian_name, c.purpose, c.status,
                       c.lawful_basis, c.notice_version, c.evidence_source, c.evidence_reference,
                       c.notes, c.effective_at, c.expires_at, c.recorded_by, c.recorded_at
                FROM student.student_consent_events c
                LEFT JOIN student.guardians g ON g.id = c.guardian_id
                WHERE c.student_id = :studentId AND c.school_id = :schoolId
                ORDER BY c.effective_at DESC, c.recorded_at DESC, c.id DESC
                LIMIT 100
                """)
                .param("studentId", studentId)
                .param("schoolId", schoolId)
                .query((rs, n) -> consentRow(rs))
                .list();

        return row("studentId", studentId, "schoolId", schoolId, "guardians", guardians,
                "consents", effectiveConsents, "consentHistory", consentHistory,
                "supportedPurposes", PURPOSES.stream().sorted().toList());
    }

    @Transactional
    public Map<String, Object> addGuardian(Long studentId, Map<String, Object> request) {
        Long schoolId = schoolId(studentId);
        String requestedGuardianId = text(request.get("guardianId"));
        String guardianId = requestedGuardianId == null ? UUID.randomUUID().toString() : requestedGuardianId;
        String relationship = allowed(request.get("relationship"), RELATIONSHIPS, "relationship");
        OffsetDateTime now = OffsetDateTime.now();
        Long actorId = actorId();

        if (requestedGuardianId == null) {
            String fullName = required(request.get("fullName"), "Guardian name is required");
            String phone = phone(request.get("phone"));
            String email = email(request.get("email"));
            jdbc.sql("""
                    INSERT INTO student.guardians
                        (id, school_id, full_name, phone, email, preferred_language, contact_verified_at,
                         status, created_by, updated_by, created_at, updated_at)
                    VALUES
                        (:id, :schoolId, :fullName, :phone, :email, :preferredLanguage, :contactVerifiedAt,
                         'ACTIVE', :actorId, :actorId, :now, :now)
                    """)
                    .param("id", guardianId).param("schoolId", schoolId).param("fullName", fullName)
                    .param("phone", phone).param("email", email)
                    .param("preferredLanguage", text(request.get("preferredLanguage")))
                    .param("contactVerifiedAt", bool(request.get("contactVerified"), false) ? now : null)
                    .param("actorId", actorId).param("now", now).update();
        } else {
            requireGuardianInSchool(guardianId, schoolId);
        }

        boolean primary = bool(request.get("primary"), false);
        if (primary) clearPrimary(studentId, guardianId);
        try {
            jdbc.sql("""
                    INSERT INTO student.student_guardians
                        (id, school_id, student_id, guardian_id, relationship, is_primary,
                         receives_notifications, can_view_academic, can_manage_fees, pickup_authorized,
                         created_by, updated_by, created_at, updated_at)
                    VALUES
                        (:id, :schoolId, :studentId, :guardianId, :relationship, :primary,
                         :receivesNotifications, :canViewAcademic, :canManageFees, :pickupAuthorized,
                         :actorId, :actorId, :now, :now)
                    """)
                    .param("id", UUID.randomUUID().toString()).param("schoolId", schoolId)
                    .param("studentId", studentId).param("guardianId", guardianId)
                    .param("relationship", relationship).param("primary", primary)
                    .param("receivesNotifications", bool(request.get("receivesNotifications"), true))
                    .param("canViewAcademic", bool(request.get("canViewAcademic"), true))
                    .param("canManageFees", bool(request.get("canManageFees"), false))
                    .param("pickupAuthorized", bool(request.get("pickupAuthorized"), false))
                    .param("actorId", actorId).param("now", now).update();
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("This guardian is already linked to the student", ex);
        }
        emitStudentProjectionUpdates(syncLegacyParents(studentId));
        reviewInvalidation.invalidateProfile(studentId);
        emit("student.guardian.upserted.v1", guardianId, schoolId, studentId,
                row("guardianId", guardianId, "studentId", studentId, "relationship", relationship));
        return overview(studentId);
    }

    @Transactional
    public Map<String, Object> updateGuardian(Long studentId, String guardianId, Map<String, Object> request) {
        Long schoolId = schoolId(studentId);
        requireLinkedGuardian(studentId, guardianId, schoolId);
        long linkVersion = longValue(request.get("linkVersion"), -1L);
        requireLinkVersion(studentId, guardianId, schoolId, linkVersion);
        List<Long> affectedStudentIds = linkedStudentIds(guardianId, schoolId);
        String fullName = required(request.get("fullName"), "Guardian name is required");
        String relationship = allowed(request.get("relationship"), RELATIONSHIPS, "relationship");
        boolean primary = bool(request.get("primary"), false);
        if (primary) clearPrimary(studentId, guardianId);
        OffsetDateTime now = OffsetDateTime.now();
        int updated = jdbc.sql("""
                UPDATE student.guardians
                SET full_name = :fullName, phone = :phone, email = :email,
                    preferred_language = :preferredLanguage,
                    contact_verified_at = CASE
                        WHEN phone IS DISTINCT FROM :phone OR email IS DISTINCT FROM :email THEN NULL
                        WHEN :contactVerified THEN COALESCE(contact_verified_at, :now)
                        ELSE NULL
                    END,
                    status = :status, updated_by = :actorId, updated_at = :now, version = version + 1
                WHERE id = :guardianId AND school_id = :schoolId AND version = :version
                """)
                .param("fullName", fullName).param("phone", phone(request.get("phone")))
                .param("email", email(request.get("email")))
                .param("preferredLanguage", text(request.get("preferredLanguage")))
                .param("contactVerified", bool(request.get("contactVerified"), false))
                .param("status", allowedDefault(request.get("status"), Set.of("ACTIVE", "INACTIVE"), "status", "ACTIVE"))
                .param("actorId", actorId()).param("now", now).param("guardianId", guardianId)
                .param("schoolId", schoolId).param("version", longValue(request.get("version"), -1L)).update();
        if (updated == 0) throw new IllegalArgumentException("Guardian changed since it was loaded; refresh and try again");

        int linkUpdated = jdbc.sql("""
                UPDATE student.student_guardians
                SET relationship = :relationship, is_primary = :primary,
                    receives_notifications = :receivesNotifications,
                    can_view_academic = :canViewAcademic, can_manage_fees = :canManageFees,
                    pickup_authorized = :pickupAuthorized, updated_by = :actorId,
                    updated_at = :now, version = version + 1
                WHERE student_id = :studentId AND guardian_id = :guardianId AND school_id = :schoolId
                  AND version = :linkVersion
                """)
                .param("relationship", relationship).param("primary", primary)
                .param("receivesNotifications", bool(request.get("receivesNotifications"), true))
                .param("canViewAcademic", bool(request.get("canViewAcademic"), true))
                .param("canManageFees", bool(request.get("canManageFees"), false))
                .param("pickupAuthorized", bool(request.get("pickupAuthorized"), false))
                .param("actorId", actorId()).param("now", now).param("studentId", studentId)
                .param("guardianId", guardianId).param("schoolId", schoolId)
                .param("linkVersion", linkVersion).update();
        if (linkUpdated == 0) {
            throw new IllegalArgumentException("Guardian permissions changed since they were loaded; refresh and try again");
        }
        emitStudentProjectionUpdates(syncLegacyParentsForGuardian(guardianId, schoolId));
        affectedStudentIds.forEach(reviewInvalidation::invalidateProfile);
        emit("student.guardian.upserted.v1", guardianId, schoolId, studentId,
                row("guardianId", guardianId, "studentId", studentId, "relationship", relationship));
        return overview(studentId);
    }

    @Transactional
    public Map<String, Object> unlinkGuardian(Long studentId, String guardianId) {
        Long schoolId = schoolId(studentId);
        requireLinkedGuardian(studentId, guardianId, schoolId);
        jdbc.sql("DELETE FROM student.student_guardians WHERE student_id = :studentId AND guardian_id = :guardianId")
                .param("studentId", studentId).param("guardianId", guardianId).update();
        jdbc.sql("""
                UPDATE student.guardians SET status = 'INACTIVE', updated_at = now(), updated_by = :actorId,
                    version = version + 1
                WHERE id = :guardianId
                  AND NOT EXISTS (SELECT 1 FROM student.student_guardians WHERE guardian_id = :guardianId)
                """)
                .param("guardianId", guardianId).param("actorId", actorId()).update();
        emitStudentProjectionUpdates(syncLegacyParents(studentId));
        reviewInvalidation.invalidateProfile(studentId);
        emit("student.guardian.unlinked.v1", guardianId, schoolId, studentId,
                row("guardianId", guardianId, "studentId", studentId));
        return overview(studentId);
    }

    @Transactional
    public Map<String, Object> recordConsent(Long studentId, Map<String, Object> request, String idempotencyHeader) {
        Long schoolId = schoolId(studentId);
        String idempotencyKey = firstNonBlank(idempotencyHeader, text(request.get("idempotencyKey")));
        if (idempotencyKey != null && idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Idempotency key must not exceed 128 characters");
        }
        if (idempotencyKey != null) {
            Map<String, Object> existing = findConsentByIdempotency(schoolId, idempotencyKey);
            if (existing != null) {
                if (!studentId.equals(existing.get("studentId"))) {
                    throw new IllegalArgumentException("Idempotency key was already used for another student");
                }
                return overview(studentId);
            }
        }
        String guardianId = text(request.get("guardianId"));
        if (guardianId != null) requireLinkedGuardian(studentId, guardianId, schoolId);
        String purpose = allowed(request.get("purpose"), PURPOSES, "purpose");
        String status = allowed(request.get("status"), CONSENT_STATUSES, "status");
        String noticeVersion = required(request.get("noticeVersion"), "Notice version is required");
        String evidenceSource = allowedDefault(request.get("evidenceSource"), EVIDENCE_SOURCES,
                "evidence source", "SCHOOL_RECORD");
        String consentId = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.sql("""
                INSERT INTO student.student_consent_events
                    (id, school_id, student_id, guardian_id, purpose, status, lawful_basis,
                     notice_version, evidence_source, evidence_reference, notes, effective_at,
                     expires_at, recorded_by, recorded_at, idempotency_key)
                VALUES
                    (:id, :schoolId, :studentId, :guardianId, :purpose, :status, :lawfulBasis,
                     :noticeVersion, :evidenceSource, :evidenceReference, :notes, :effectiveAt,
                     :expiresAt, :recordedBy, :recordedAt, :idempotencyKey)
                """)
                .param("id", consentId).param("schoolId", schoolId).param("studentId", studentId)
                .param("guardianId", guardianId).param("purpose", purpose).param("status", status)
                .param("lawfulBasis", allowedDefault(request.get("lawfulBasis"), Set.of("CONSENT", "LEGAL_OBLIGATION"), "lawful basis", "CONSENT"))
                .param("noticeVersion", noticeVersion).param("evidenceSource", evidenceSource)
                .param("evidenceReference", text(request.get("evidenceReference")))
                .param("notes", text(request.get("notes")))
                .param("effectiveAt", offsetDateTime(request.get("effectiveAt"), now))
                .param("expiresAt", offsetDateTime(request.get("expiresAt"), null))
                .param("recordedBy", actorId()).param("recordedAt", now)
                .param("idempotencyKey", idempotencyKey).update();
        emit("student.consent.recorded.v1", consentId, schoolId, studentId,
                row("consentId", consentId, "studentId", studentId, "guardianId", guardianId,
                        "purpose", purpose, "status", status, "noticeVersion", noticeVersion));
        return overview(studentId);
    }

    private Map<String, Object> findConsentByIdempotency(Long schoolId, String idempotencyKey) {
        return jdbc.sql("SELECT id, student_id FROM student.student_consent_events WHERE school_id = :schoolId AND idempotency_key = :key")
                .param("schoolId", schoolId).param("key", idempotencyKey)
                .query((rs, n) -> row("id", rs.getString("id"), "studentId", rs.getLong("student_id")))
                .optional().orElse(null);
    }

    private Map<String, Object> consentRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return row("id", rs.getString("id"), "guardianId", rs.getString("guardian_id"),
                "guardianName", rs.getString("guardian_name"), "purpose", rs.getString("purpose"),
                "status", rs.getString("status"), "lawfulBasis", rs.getString("lawful_basis"),
                "noticeVersion", rs.getString("notice_version"), "evidenceSource", rs.getString("evidence_source"),
                "evidenceReference", rs.getString("evidence_reference"), "notes", rs.getString("notes"),
                "effectiveAt", rs.getObject("effective_at", OffsetDateTime.class),
                "expiresAt", rs.getObject("expires_at", OffsetDateTime.class),
                "recordedBy", rs.getObject("recorded_by", Long.class),
                "recordedAt", rs.getObject("recorded_at", OffsetDateTime.class));
    }

    private Long schoolId(Long studentId) {
        return jdbc.sql("SELECT school_id FROM student.students WHERE id = :id AND deleted_at IS NULL")
                .param("id", studentId).query(Long.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
    }

    private void requireGuardianInSchool(String guardianId, Long schoolId) {
        long count = jdbc.sql("SELECT count(*) FROM student.guardians WHERE id = :id AND school_id = :schoolId AND status = 'ACTIVE'")
                .param("id", guardianId).param("schoolId", schoolId).query(Long.class).single();
        if (count == 0) throw new IllegalArgumentException("Guardian not found");
    }

    private void requireLinkedGuardian(Long studentId, String guardianId, Long schoolId) {
        long count = jdbc.sql("""
                SELECT count(*) FROM student.student_guardians
                WHERE student_id = :studentId AND guardian_id = :guardianId AND school_id = :schoolId
                """).param("studentId", studentId).param("guardianId", guardianId)
                .param("schoolId", schoolId).query(Long.class).single();
        if (count == 0) throw new IllegalArgumentException("Guardian is not linked to this student");
    }

    private void requireLinkVersion(Long studentId, String guardianId, Long schoolId, long linkVersion) {
        long count = jdbc.sql("""
                        SELECT count(*) FROM student.student_guardians
                        WHERE student_id = :studentId AND guardian_id = :guardianId
                          AND school_id = :schoolId AND version = :linkVersion
                        """)
                .param("studentId", studentId).param("guardianId", guardianId)
                .param("schoolId", schoolId).param("linkVersion", linkVersion)
                .query(Long.class).single();
        if (count == 0) {
            throw new IllegalArgumentException(
                    "Guardian permissions changed since they were loaded; refresh and try again");
        }
    }

    private void clearPrimary(Long studentId, String selectedGuardianId) {
        jdbc.sql("""
                        UPDATE student.student_guardians
                        SET is_primary = false, updated_at = now(), version = version + 1
                        WHERE student_id = :studentId AND is_primary AND guardian_id <> :selectedGuardianId
                        """)
                .param("studentId", studentId)
                .param("selectedGuardianId", selectedGuardianId)
                .update();
    }

    private List<Long> linkedStudentIds(String guardianId, Long schoolId) {
        return jdbc.sql("""
                        SELECT DISTINCT student_id
                        FROM student.student_guardians
                        WHERE guardian_id = :guardianId AND school_id = :schoolId
                        ORDER BY student_id
                        """)
                .param("guardianId", guardianId)
                .param("schoolId", schoolId)
                .query(Long.class)
                .list();
    }

    private List<Long> syncLegacyParentsForGuardian(String guardianId, Long schoolId) {
        return syncLegacyParents("""
                SELECT DISTINCT student_id
                FROM student.student_guardians
                WHERE guardian_id = :guardianId AND school_id = :schoolId
                """, spec -> spec.param("guardianId", guardianId).param("schoolId", schoolId));
    }

    private List<Long> syncLegacyParents(Long studentId) {
        return syncLegacyParents("SELECT :studentId::bigint AS student_id",
                spec -> spec.param("studentId", studentId));
    }

    private List<Long> syncLegacyParents(
            String affectedStudentsSql,
            java.util.function.UnaryOperator<JdbcClient.StatementSpec> bind) {
        String sql = """
                WITH affected_students AS (
                    %s
                ),
                parent_values AS (
                    SELECT s.id,
                           (SELECT g.full_name FROM student.student_guardians sg
                            JOIN student.guardians g ON g.id = sg.guardian_id
                            WHERE sg.student_id = s.id AND sg.relationship = 'FATHER' AND g.status = 'ACTIVE'
                            ORDER BY sg.is_primary DESC, sg.updated_at DESC, sg.id LIMIT 1) AS father_name,
                           (SELECT g.phone FROM student.student_guardians sg
                            JOIN student.guardians g ON g.id = sg.guardian_id
                            WHERE sg.student_id = s.id AND sg.relationship = 'FATHER' AND g.status = 'ACTIVE'
                            ORDER BY sg.is_primary DESC, sg.updated_at DESC, sg.id LIMIT 1) AS father_phone,
                           (SELECT g.full_name FROM student.student_guardians sg
                            JOIN student.guardians g ON g.id = sg.guardian_id
                            WHERE sg.student_id = s.id AND sg.relationship = 'MOTHER' AND g.status = 'ACTIVE'
                            ORDER BY sg.is_primary DESC, sg.updated_at DESC, sg.id LIMIT 1) AS mother_name
                    FROM student.students s
                    JOIN affected_students affected ON affected.student_id = s.id
                )
                UPDATE student.students s
                SET father_name = p.father_name, father_contact = p.father_phone,
                    mother_name = p.mother_name, updated_at = now()
                FROM parent_values p
                WHERE s.id = p.id
                  AND (s.father_name, s.father_contact, s.mother_name)
                      IS DISTINCT FROM (p.father_name, p.father_phone, p.mother_name)
                RETURNING s.id
                """.formatted(affectedStudentsSql);
        return bind.apply(jdbc.sql(sql)).query(Long.class).list();
    }

    private void emitStudentProjectionUpdates(List<Long> studentIds) {
        for (Long studentId : studentIds) {
            Map<String, Object> student = jdbc.sql("""
                            SELECT id, school_id, admission_no, full_name, roll_no, class_id, section_id,
                                   father_contact, phone, deleted_at, attendance_percent, father_name
                            FROM student.students
                            WHERE id = :studentId
                            """)
                    .param("studentId", studentId)
                    .query((rs, n) -> row(
                            "id", rs.getLong("id"),
                            "schoolId", rs.getLong("school_id"),
                            "admissionNo", rs.getString("admission_no"),
                            "fullName", rs.getString("full_name"),
                            "rollNo", rs.getString("roll_no"),
                            "classId", rs.getString("class_id"),
                            "sectionId", rs.getString("section_id"),
                            "parentContact", rs.getString("father_contact"),
                            "phone", rs.getString("phone"),
                            "active", rs.getObject("deleted_at") == null,
                            "attendancePercent", rs.getObject("attendance_percent", Double.class),
                            "fatherName", rs.getString("father_name")))
                    .single();
            Long schoolId = ((Number) student.get("schoolId")).longValue();
            outbox.append("student.upserted.v1", "StudentUpserted:" + studentId,
                    "Student", String.valueOf(studentId), schoolId, student);
        }
    }

    private void emit(String eventType, String aggregateId, Long schoolId, Long studentId, Map<String, Object> payload) {
        outbox.append(eventType, UUID.randomUUID().toString(), "student", String.valueOf(studentId), schoolId, payload);
    }

    private static String allowed(Object value, Set<String> allowed, String name) {
        String normalized = required(value, name + " is required").toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!allowed.contains(normalized)) throw new IllegalArgumentException("Unsupported " + name + ": " + normalized);
        return normalized;
    }

    private static String allowedDefault(Object value, Set<String> allowed, String name, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : allowed(value, allowed, name);
    }

    private static String required(Object value, String message) {
        String text = text(value);
        if (text == null) throw new IllegalArgumentException(message);
        return text;
    }

    private static String phone(Object value) {
        String raw = text(value);
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() < 10 || digits.length() > 15) throw new IllegalArgumentException("Guardian phone must contain 10 to 15 digits");
        return raw;
    }

    private static String email(Object value) {
        String email = text(value);
        if (email != null && (!email.contains("@") || email.startsWith("@") || email.endsWith("@"))) {
            throw new IllegalArgumentException("Guardian email is invalid");
        }
        return email;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private static boolean bool(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static Long longValue(Object value, Long fallback) {
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Version is invalid", ex); }
    }

    private static OffsetDateTime offsetDateTime(Object value, OffsetDateTime fallback) {
        String text = text(value);
        if (text == null) return fallback;
        try { return OffsetDateTime.parse(text); }
        catch (Exception ex) { throw new IllegalArgumentException("Timestamp must use ISO-8601 with an offset", ex); }
    }

    private static Long actorId() {
        return TenantContext.get().userId();
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first.trim();
    }

    private static Map<String, Object> row(Object... values) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) row.put(String.valueOf(values[i]), values[i + 1]);
        return row;
    }
}
