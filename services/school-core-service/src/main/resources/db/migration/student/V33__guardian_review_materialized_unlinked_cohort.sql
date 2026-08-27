-- Derive repeated-contact thresholds from one materialized pass over the
-- governed review source. V32 produced the correct cohort semantics but its
-- correlated active-link exclusion could exceed the runtime API timeout on
-- production data. This view remains label-only and preserves all case IDs,
-- snapshots, decisions, and source values.

ALTER VIEW student.guardian_data_review_queue_v1
    RENAME TO guardian_data_review_queue_v32;

CREATE VIEW student.guardian_data_review_queue_v1
WITH (security_invoker = true)
AS
WITH source AS MATERIALIZED (
    SELECT *
    FROM student.guardian_data_review_queue_v29
),
complete_unlinked_legacy_fathers AS (
    SELECT
        queue.student_id,
        queue.school_id,
        max(NULLIF(
            regexp_replace(COALESCE(queue.legacy_value, ''), '[^0-9]', '', 'g'),
            ''
        )) FILTER (WHERE queue.field_name = 'contact') AS normalized_father_contact
    FROM source queue
    WHERE queue.guardian_id IS NULL
      AND queue.relationship = 'FATHER'
    GROUP BY queue.student_id, queue.school_id
    HAVING count(*) FILTER (
               WHERE queue.field_name = 'name'
                 AND NULLIF(btrim(COALESCE(queue.legacy_value, '')), '') IS NOT NULL
           ) > 0
       AND count(*) FILTER (
               WHERE queue.field_name = 'contact'
                 AND NULLIF(
                       regexp_replace(COALESCE(queue.legacy_value, ''), '[^0-9]', '', 'g'),
                       ''
                     ) IS NOT NULL
           ) > 0
),
clustered_unlinked_legacy_fathers AS (
    SELECT
        father.student_id,
        father.school_id,
        count(*) OVER (
            PARTITION BY father.school_id, father.normalized_father_contact
        ) AS legacy_phone_cluster_students
    FROM complete_unlinked_legacy_fathers father
),
corrected AS (
    SELECT
        queue.*,
        CASE
            WHEN queue.field_name = 'contact'
                 AND NULLIF(
                       regexp_replace(COALESCE(queue.legacy_value, ''), '[^0-9]', '', 'g'),
                       ''
                     ) IS NULL
                 AND NULLIF(
                       regexp_replace(COALESCE(queue.normalized_value, ''), '[^0-9]', '', 'g'),
                       ''
                     ) IS NOT NULL
                THEN 'PROJECTION_MISSING'
            WHEN queue.field_name = 'name'
                 AND NULLIF(btrim(COALESCE(queue.legacy_value, '')), '') IS NULL
                 AND NULLIF(btrim(COALESCE(queue.normalized_value, '')), '') IS NOT NULL
                THEN 'PROJECTION_MISSING'
            WHEN queue.field_name = 'name'
                 AND NULLIF(btrim(COALESCE(queue.legacy_value, '')), '') IS NOT NULL
                 AND NULLIF(btrim(COALESCE(queue.normalized_value, '')), '') IS NOT NULL
                 AND lower(btrim(queue.legacy_value)) = lower(btrim(queue.normalized_value))
                THEN 'CASE_ONLY'
            WHEN queue.guardian_id IS NULL
                 AND queue.relationship = 'FATHER'
                 AND COALESCE(father.legacy_phone_cluster_students, 0) >= 100
                THEN 'PLACEHOLDER_CLUSTER'
            WHEN queue.guardian_id IS NULL
                 AND queue.relationship = 'FATHER'
                 AND COALESCE(father.legacy_phone_cluster_students, 0) >= 10
                THEN 'PLACEHOLDER_CANDIDATE'
            WHEN queue.issue_bucket = 'PLACEHOLDER_CANDIDATE'
                THEN 'PLACEHOLDER_CANDIDATE'
            WHEN queue.issue_bucket = 'IDENTITY_CANDIDATE'
                THEN 'IDENTITY_CANDIDATE'
            WHEN queue.issue_bucket = 'MISSING_RELATIONSHIP'
                THEN 'MISSING_RELATIONSHIP'
            ELSE 'LINKED_CONFLICT'
        END AS corrected_issue_bucket
    FROM source queue
    LEFT JOIN clustered_unlinked_legacy_fathers father
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

REVOKE ALL ON TABLE student.guardian_data_review_queue_v32 FROM PUBLIC;
REVOKE ALL ON TABLE student.guardian_data_review_queue_v1 FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        GRANT SELECT ON student.guardian_data_review_queue_v32 TO app_rt;
        GRANT SELECT ON student.guardian_data_review_queue_v1 TO app_rt;
    END IF;
END
$$;
