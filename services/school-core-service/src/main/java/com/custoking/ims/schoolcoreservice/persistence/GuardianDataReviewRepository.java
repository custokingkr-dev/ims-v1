package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Repository
public class GuardianDataReviewRepository {

    private static final Set<String> BUCKETS = Set.of(
            "PLACEHOLDER_CLUSTER", "PLACEHOLDER_CANDIDATE", "MISSING_RELATIONSHIP",
            "IDENTITY_CANDIDATE", "PROJECTION_MISSING", "CASE_ONLY", "LINKED_CONFLICT");
    private static final Set<String> STATUSES = Set.of(
            "PENDING", "STALE", "DECIDED", "DEFERRED", "ESCALATED");
    private static final Set<String> DECISIONS = Set.of(
            "ACCEPT_NORMALIZED", "KEEP_LEGACY", "CLEAR_PLACEHOLDER",
            "CONFIRM_SHARED_IDENTITY", "RESOLVE_IN_STUDENT_EDITOR", "DEFER", "ESCALATE");

    private final JdbcClient jdbc;

    public GuardianDataReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> summary(Long schoolId) {
        String where = schoolId == null ? "" : " WHERE school_id = :schoolId";
        JdbcClient.StatementSpec totalsSpec = jdbc.sql("""
                SELECT count(*) AS total_cases,
                       count(DISTINCT student_id) AS distinct_students,
                       count(*) FILTER (WHERE review_status = 'DECIDED') AS decided,
                       count(*) FILTER (WHERE review_status = 'DEFERRED') AS deferred,
                       count(*) FILTER (WHERE review_status = 'ESCALATED') AS escalated,
                       count(*) FILTER (WHERE review_status IN ('PENDING', 'STALE')) AS remaining
                FROM student.guardian_data_review_queue_v1
                """ + where);
        if (schoolId != null) totalsSpec = totalsSpec.param("schoolId", schoolId);
        Map<String, Object> totals = totalsSpec.query((rs, n) -> row(
                "totalCases", rs.getLong("total_cases"),
                "distinctStudents", rs.getLong("distinct_students"),
                "decided", rs.getLong("decided"),
                "deferred", rs.getLong("deferred"),
                "escalated", rs.getLong("escalated"),
                "remaining", rs.getLong("remaining"))).single();

        JdbcClient.StatementSpec bucketsSpec = jdbc.sql("""
                SELECT issue_bucket, review_status, count(*) AS cases,
                       count(DISTINCT student_id) AS students
                FROM student.guardian_data_review_queue_v1
                """ + where + " GROUP BY issue_bucket, review_status ORDER BY issue_bucket, review_status");
        if (schoolId != null) bucketsSpec = bucketsSpec.param("schoolId", schoolId);
        List<Map<String, Object>> buckets = bucketsSpec.query((rs, n) -> row(
                "bucket", rs.getString("issue_bucket"),
                "status", rs.getString("review_status"),
                "cases", rs.getLong("cases"),
                "students", rs.getLong("students"))).list();

        long total = ((Number) totals.get("totalCases")).longValue();
        long reviewed = ((Number) totals.get("decided")).longValue()
                + ((Number) totals.get("deferred")).longValue()
                + ((Number) totals.get("escalated")).longValue();
        BigDecimal progress = total == 0 ? BigDecimal.valueOf(100)
                : BigDecimal.valueOf(reviewed * 100.0 / total).setScale(1, RoundingMode.HALF_UP);
        totals.put("reviewed", reviewed);
        totals.put("progressPercent", progress);
        totals.put("buckets", buckets);
        return totals;
    }

    public Map<String, Object> cases(Long schoolId, String bucket, String status,
                                     String search, int page, int size) {
        String normalizedBucket = optionalAllowed(bucket, BUCKETS, "bucket");
        String normalizedStatus = optionalAllowed(status, STATUSES, "status");
        String normalizedSearch = text(search);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (schoolId != null) where.append(" AND school_id = :schoolId");
        if (normalizedBucket != null) where.append(" AND issue_bucket = :bucket");
        if (normalizedStatus != null) where.append(" AND review_status = :status");
        if (normalizedSearch != null) {
            where.append(" AND (admission_no ILIKE :search OR student_name ILIKE :search)");
        }

        JdbcClient.StatementSpec countSpec = bind(jdbc.sql(
                "SELECT count(*) FROM student.guardian_data_review_queue_v1" + where),
                schoolId, normalizedBucket, normalizedStatus, normalizedSearch);
        long total = countSpec.query(Long.class).single();

        JdbcClient.StatementSpec rowsSpec = bind(jdbc.sql("""
                SELECT case_id, case_snapshot_sha256, school_id, student_id, admission_no,
                       student_name, relationship, field_name, legacy_value, normalized_value,
                       guardian_id, issue_bucket, linked_students, phone_cluster_guardians,
                       identity_candidates, contact_verified_at, decision, decision_notes,
                       decided_by, decided_at, review_status, recommended_decision
                FROM student.guardian_data_review_queue_v1
                """ + where + "\n" + """
                ORDER BY CASE issue_bucket
                    WHEN 'PLACEHOLDER_CLUSTER' THEN 1
                    WHEN 'PLACEHOLDER_CANDIDATE' THEN 2
                    WHEN 'IDENTITY_CANDIDATE' THEN 3
                    WHEN 'LINKED_CONFLICT' THEN 4
                    WHEN 'MISSING_RELATIONSHIP' THEN 5
                    WHEN 'PROJECTION_MISSING' THEN 6
                    ELSE 7 END,
                    student_name, student_id, relationship, field_name
                LIMIT :size OFFSET :offset
                """), schoolId, normalizedBucket, normalizedStatus, normalizedSearch)
                .param("size", safeSize).param("offset", safePage * safeSize);
        List<Map<String, Object>> content = rowsSpec.query((rs, n) -> caseRow(rs)).list();

        return row("content", content, "page", safePage, "size", safeSize,
                "totalElements", total,
                "totalPages", total == 0 ? 0 : (total + safeSize - 1) / safeSize,
                "last", (long) (safePage + 1) * safeSize >= total);
    }

    @Transactional
    public Map<String, Object> decide(Long schoolId, String caseId, Map<String, Object> request,
                                      String idempotencyKey) {
        String safeCaseId = hash(caseId, "caseId");
        String expectedSnapshot = hash(request.get("caseSnapshotSha256"), "caseSnapshotSha256");
        String decision = allowed(request.get("decision"), DECISIONS, "decision");
        String notes = text(request.get("notes"));
        if (notes != null && notes.length() > 2000) {
            throw new IllegalArgumentException("Decision notes must be 2000 characters or fewer");
        }
        String safeIdempotencyKey = text(idempotencyKey);
        if (safeIdempotencyKey == null || safeIdempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key is required and must be 128 characters or fewer");
        }

        Map<String, Object> current = jdbc.sql("""
                SELECT school_id, case_snapshot_sha256
                FROM student.guardian_data_review_queue_v1
                WHERE case_id = :caseId AND school_id = :schoolId
                """).param("caseId", safeCaseId).param("schoolId", schoolId)
                .query((rs, n) -> row("schoolId", rs.getLong("school_id"),
                        "snapshot", rs.getString("case_snapshot_sha256")))
                .optional().orElseThrow(() -> new IllegalArgumentException(
                        "Review case no longer exists; refresh the queue"));
        if (!expectedSnapshot.equals(current.get("snapshot"))) {
            throw new IllegalArgumentException("Review case changed since it was loaded; refresh and review again");
        }

        String decisionId = UUID.randomUUID().toString();
        int inserted = jdbc.sql("""
                INSERT INTO student.guardian_data_review_decisions
                    (id, school_id, case_id, case_snapshot_sha256, decision, notes,
                     idempotency_key, decided_by)
                VALUES
                    (:id, :schoolId, :caseId, :snapshot, :decision, :notes,
                     :idempotencyKey, :actorId)
                ON CONFLICT (school_id, idempotency_key) DO NOTHING
                """).param("id", decisionId).param("schoolId", schoolId)
                .param("caseId", safeCaseId).param("snapshot", expectedSnapshot)
                .param("decision", decision).param("notes", notes)
                .param("idempotencyKey", safeIdempotencyKey)
                .param("actorId", TenantContext.get().userId()).update();

        if (inserted == 0) {
            Map<String, Object> existing = jdbc.sql("""
                    SELECT case_id, case_snapshot_sha256, decision, notes
                    FROM student.guardian_data_review_decisions
                    WHERE school_id = :schoolId AND idempotency_key = :idempotencyKey
                    """).param("schoolId", schoolId).param("idempotencyKey", safeIdempotencyKey)
                    .query((rs, n) -> row("caseId", rs.getString("case_id"),
                            "snapshot", rs.getString("case_snapshot_sha256"),
                            "decision", rs.getString("decision"), "notes", rs.getString("notes")))
                    .single();
            if (!safeCaseId.equals(existing.get("caseId"))
                    || !expectedSnapshot.equals(existing.get("snapshot"))
                    || !decision.equals(existing.get("decision"))
                    || !java.util.Objects.equals(notes, existing.get("notes"))) {
                throw new IllegalArgumentException("Idempotency-Key was already used for a different decision");
            }
        }
        return caseById(schoolId, safeCaseId);
    }

    private Map<String, Object> caseById(Long schoolId, String caseId) {
        return jdbc.sql("""
                SELECT case_id, case_snapshot_sha256, school_id, student_id, admission_no,
                       student_name, relationship, field_name, legacy_value, normalized_value,
                       guardian_id, issue_bucket, linked_students, phone_cluster_guardians,
                       identity_candidates, contact_verified_at, decision, decision_notes,
                       decided_by, decided_at, review_status, recommended_decision
                FROM student.guardian_data_review_queue_v1
                WHERE case_id = :caseId AND school_id = :schoolId
                """).param("caseId", caseId).param("schoolId", schoolId)
                .query((rs, n) -> caseRow(rs)).single();
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Long schoolId,
                                           String bucket, String status, String search) {
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (bucket != null) spec = spec.param("bucket", bucket);
        if (status != null) spec = spec.param("status", status);
        if (search != null) spec = spec.param("search", "%" + search + "%");
        return spec;
    }

    private static Map<String, Object> caseRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return row(
                "caseId", rs.getString("case_id"),
                "caseSnapshotSha256", rs.getString("case_snapshot_sha256"),
                "schoolId", rs.getLong("school_id"),
                "studentId", rs.getLong("student_id"),
                "admissionNo", rs.getString("admission_no"),
                "studentName", rs.getString("student_name"),
                "relationship", rs.getString("relationship"),
                "fieldName", rs.getString("field_name"),
                "legacyValue", rs.getString("legacy_value"),
                "normalizedValue", rs.getString("normalized_value"),
                "guardianId", rs.getString("guardian_id"),
                "issueBucket", rs.getString("issue_bucket"),
                "linkedStudents", rs.getLong("linked_students"),
                "phoneClusterGuardians", rs.getLong("phone_cluster_guardians"),
                "identityCandidates", rs.getLong("identity_candidates"),
                "contactVerifiedAt", rs.getObject("contact_verified_at", OffsetDateTime.class),
                "decision", rs.getString("decision"),
                "decisionNotes", rs.getString("decision_notes"),
                "decidedBy", rs.getObject("decided_by", Long.class),
                "decidedAt", rs.getObject("decided_at", OffsetDateTime.class),
                "reviewStatus", rs.getString("review_status"),
                "recommendedDecision", rs.getString("recommended_decision"));
    }

    private static String optionalAllowed(String value, Set<String> allowed, String name) {
        String normalized = text(value);
        return normalized == null ? null : allowed(normalized, allowed, name);
    }

    private static String allowed(Object value, Set<String> allowed, String name) {
        String normalized = text(value);
        if (normalized == null) throw new IllegalArgumentException(name + " is required");
        normalized = normalized.toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!allowed.contains(normalized)) throw new IllegalArgumentException("Unsupported " + name + ": " + normalized);
        return normalized;
    }

    private static String hash(Object value, String name) {
        String hash = text(value);
        if (hash == null || !hash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest");
        }
        return hash;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private static Map<String, Object> row(Object... values) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            row.put(String.valueOf(values[index]), values[index + 1]);
        }
        return row;
    }
}
