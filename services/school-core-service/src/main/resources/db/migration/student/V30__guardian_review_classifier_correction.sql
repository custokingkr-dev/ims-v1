-- Correct the review-only classifier without changing any student, guardian,
-- relationship, consent, or decision value. V29 remains available as the
-- immutable source view so existing case IDs and snapshot digests stay stable.

ALTER VIEW student.guardian_data_review_queue_v1
    RENAME TO guardian_data_review_queue_v29;

CREATE VIEW student.guardian_data_review_queue_v1
WITH (security_invoker = true)
AS
WITH complete_legacy_fathers AS (
    SELECT
        candidate.id AS student_id,
        candidate.school_id,
        count(*) OVER (
            PARTITION BY candidate.school_id, candidate.normalized_father_contact
        ) AS legacy_phone_cluster_students
    FROM (
        SELECT
            student_row.id,
            student_row.school_id,
            NULLIF(
                regexp_replace(COALESCE(student_row.father_contact, ''), '[^0-9]', '', 'g'),
                ''
            ) AS normalized_father_contact
        FROM student.students student_row
        WHERE student_row.deleted_at IS NULL
          AND NULLIF(btrim(COALESCE(student_row.father_name, '')), '') IS NOT NULL
          AND NULLIF(
                regexp_replace(COALESCE(student_row.father_contact, ''), '[^0-9]', '', 'g'),
                ''
              ) IS NOT NULL
    ) candidate
),
corrected AS (
    SELECT
        queue.*,
        CASE
            -- A populated canonical value with no legacy projection is a projection
            -- gap even when that guardian is shared by many students.
            WHEN queue.legacy_value IS NULL AND queue.normalized_value IS NOT NULL
                THEN 'PROJECTION_MISSING'
            -- Repeated legacy phone clusters exist on student rows, not necessarily
            -- as guardian rows. Only complete father bundles participate; contact-only
            -- incomplete records remain explicit missing-relationship cases.
            WHEN queue.guardian_id IS NULL
                 AND queue.relationship = 'FATHER'
                 AND COALESCE(father.legacy_phone_cluster_students, 0) >= 10
                THEN 'PLACEHOLDER_CLUSTER'
            -- Whitespace folding makes the casing-only bucket deterministic for names
            -- containing duplicate spaces or non-line-breaking whitespace.
            WHEN queue.issue_bucket = 'LINKED_CONFLICT'
                 AND queue.field_name = 'name'
                 AND queue.legacy_value IS NOT NULL
                 AND queue.normalized_value IS NOT NULL
                 AND regexp_replace(
                       lower(btrim(queue.legacy_value)), '[[:space:]]+', ' ', 'g'
                     ) = regexp_replace(
                       lower(btrim(queue.normalized_value)), '[[:space:]]+', ' ', 'g'
                     )
                THEN 'CASE_ONLY'
            ELSE queue.issue_bucket
        END AS corrected_issue_bucket
    FROM student.guardian_data_review_queue_v29 queue
    LEFT JOIN complete_legacy_fathers father
      ON father.student_id = queue.student_id
     AND father.school_id = queue.school_id
)
SELECT
    corrected.case_id,
    corrected.case_snapshot_sha256,
    corrected.school_id,
    corrected.student_id,
    corrected.admission_no,
    corrected.student_name,
    corrected.relationship,
    corrected.field_name,
    corrected.legacy_value,
    corrected.normalized_value,
    corrected.guardian_id,
    corrected.corrected_issue_bucket AS issue_bucket,
    corrected.linked_students,
    corrected.phone_cluster_guardians,
    corrected.identity_candidates,
    corrected.contact_verified_at,
    corrected.decision,
    corrected.decision_notes,
    corrected.decided_by,
    corrected.decided_at,
    corrected.review_status,
    CASE corrected.corrected_issue_bucket
        WHEN 'CASE_ONLY' THEN 'ACCEPT_NORMALIZED'
        WHEN 'PROJECTION_MISSING' THEN 'ACCEPT_NORMALIZED'
        WHEN 'PLACEHOLDER_CLUSTER' THEN 'ESCALATE'
        WHEN 'PLACEHOLDER_CANDIDATE' THEN 'ESCALATE'
        WHEN 'IDENTITY_CANDIDATE' THEN 'ESCALATE'
        WHEN 'MISSING_RELATIONSHIP' THEN 'RESOLVE_IN_STUDENT_EDITOR'
        ELSE 'RESOLVE_IN_STUDENT_EDITOR'
    END AS recommended_decision
FROM corrected;

REVOKE ALL ON TABLE student.guardian_data_review_queue_v29 FROM PUBLIC;
REVOKE ALL ON TABLE student.guardian_data_review_queue_v1 FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        GRANT SELECT ON student.guardian_data_review_queue_v29 TO app_rt;
        GRANT SELECT ON student.guardian_data_review_queue_v1 TO app_rt;
    END IF;
END
$$;
