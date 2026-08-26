-- Tenant-scoped guardian parity review queue. This migration is deliberately
-- review-only: decisions are audited, but student/guardian values are never
-- changed by the runtime application. Data mutation remains an owner-operated,
-- hash-pinned workflow after the school has resolved identity ambiguity.

CREATE TABLE student.guardian_data_review_decisions (
    id VARCHAR(64) PRIMARY KEY,
    school_id BIGINT NOT NULL,
    case_id CHAR(64) NOT NULL,
    case_snapshot_sha256 CHAR(64) NOT NULL,
    decision VARCHAR(40) NOT NULL,
    notes VARCHAR(2000),
    idempotency_key VARCHAR(128) NOT NULL,
    decided_by BIGINT,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_guardian_review_decision_idempotency
        UNIQUE (school_id, idempotency_key),
    CONSTRAINT ck_guardian_review_case_hashes CHECK (
        case_id ~ '^[0-9a-f]{64}$'
        AND case_snapshot_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_guardian_review_decision CHECK (decision IN (
        'ACCEPT_NORMALIZED',
        'KEEP_LEGACY',
        'CLEAR_PLACEHOLDER',
        'CONFIRM_SHARED_IDENTITY',
        'RESOLVE_IN_STUDENT_EDITOR',
        'DEFER',
        'ESCALATE'
    ))
);

CREATE INDEX idx_guardian_review_decisions_case_latest
    ON student.guardian_data_review_decisions
        (case_id, decided_at DESC, id DESC);

CREATE INDEX idx_guardian_review_decisions_school_latest
    ON student.guardian_data_review_decisions
        (school_id, decided_at DESC);

ALTER TABLE student.guardian_data_review_decisions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON student.guardian_data_review_decisions
  USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
         OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

CREATE OR REPLACE VIEW student.guardian_data_review_queue_v1
WITH (security_invoker = true)
AS
WITH guardian_stats AS (
    SELECT
        guardian.id,
        count(DISTINCT link.student_id) AS linked_students,
        count(*) FILTER (
            WHERE NULLIF(regexp_replace(COALESCE(guardian.phone, ''), '[^0-9]', '', 'g'), '')
                  IS NOT NULL
        ) OVER (
            PARTITION BY guardian.school_id,
            NULLIF(regexp_replace(COALESCE(guardian.phone, ''), '[^0-9]', '', 'g'), '')
        ) AS phone_cluster_guardians
    FROM student.guardians guardian
    LEFT JOIN student.student_guardians link
      ON link.guardian_id = guardian.id
    GROUP BY guardian.id, guardian.school_id, guardian.phone
),
student_parents AS (
    SELECT
        student_row.id AS student_id,
        student_row.school_id,
        student_row.admission_no,
        student_row.full_name AS student_name,
        student_row.version AS student_version,
        student_row.updated_at AS student_updated_at,
        student_row.father_name,
        student_row.father_contact,
        student_row.mother_name,
        father.guardian_id AS father_guardian_id,
        father.link_id AS father_link_id,
        father.guardian_name,
        father.guardian_phone,
        father.guardian_version,
        father.link_version,
        father.contact_verified_at AS father_contact_verified_at,
        father.linked_students AS father_linked_students,
        father.phone_cluster_guardians AS father_phone_cluster_guardians,
        mother.guardian_id AS mother_guardian_id,
        mother.link_id AS mother_link_id,
        mother.guardian_name AS normalized_mother_name,
        mother.guardian_version AS mother_guardian_version,
        mother.link_version AS mother_link_version,
        mother.linked_students AS mother_linked_students
    FROM student.students student_row
    LEFT JOIN LATERAL (
        SELECT
            guardian.id AS guardian_id,
            link.id AS link_id,
            guardian.full_name AS guardian_name,
            guardian.phone AS guardian_phone,
            guardian.version AS guardian_version,
            link.version AS link_version,
            guardian.contact_verified_at,
            stats.linked_students,
            stats.phone_cluster_guardians
        FROM student.student_guardians link
        JOIN student.guardians guardian ON guardian.id = link.guardian_id
        LEFT JOIN guardian_stats stats ON stats.id = guardian.id
        WHERE link.student_id = student_row.id
          AND link.relationship = 'FATHER'
          AND guardian.status = 'ACTIVE'
        ORDER BY link.is_primary DESC, link.updated_at DESC, link.id
        LIMIT 1
    ) father ON TRUE
    LEFT JOIN LATERAL (
        SELECT
            guardian.id AS guardian_id,
            link.id AS link_id,
            guardian.full_name AS guardian_name,
            guardian.version AS guardian_version,
            link.version AS link_version,
            stats.linked_students
        FROM student.student_guardians link
        JOIN student.guardians guardian ON guardian.id = link.guardian_id
        LEFT JOIN guardian_stats stats ON stats.id = guardian.id
        WHERE link.student_id = student_row.id
          AND link.relationship = 'MOTHER'
          AND guardian.status = 'ACTIVE'
        ORDER BY link.is_primary DESC, link.updated_at DESC, link.id
        LIMIT 1
    ) mother ON TRUE
    WHERE student_row.deleted_at IS NULL
),
fields AS (
    SELECT
        parent.student_id,
        parent.school_id,
        parent.admission_no,
        parent.student_name,
        parent.student_version,
        parent.student_updated_at,
        field.relationship,
        field.field_name,
        field.legacy_value,
        field.normalized_value,
        field.guardian_id,
        field.link_id,
        field.guardian_version,
        field.link_version,
        field.contact_verified_at,
        field.linked_students,
        field.phone_cluster_guardians,
        CASE
            WHEN field.relationship = 'FATHER'
                 AND NULLIF(regexp_replace(COALESCE(parent.father_contact, ''), '[^0-9]', '', 'g'), '') IS NOT NULL
            THEN (SELECT count(*)
                  FROM student.guardians candidate
                  WHERE candidate.school_id = parent.school_id
                    AND NULLIF(regexp_replace(COALESCE(candidate.phone, ''), '[^0-9]', '', 'g'), '')
                        = NULLIF(regexp_replace(COALESCE(parent.father_contact, ''), '[^0-9]', '', 'g'), ''))
            ELSE 0
        END AS father_phone_candidates,
        CASE
            WHEN field.relationship = 'FATHER' AND field.field_name = 'contact' THEN
                (SELECT count(*)
                 FROM student.guardians candidate
                 WHERE candidate.school_id = parent.school_id
                   AND NULLIF(regexp_replace(COALESCE(candidate.phone, ''), '[^0-9]', '', 'g'), '')
                       IS NOT DISTINCT FROM
                       NULLIF(regexp_replace(COALESCE(field.legacy_value, ''), '[^0-9]', '', 'g'), ''))
            WHEN field.field_name = 'name' AND field.legacy_value IS NOT NULL THEN
                (SELECT count(*)
                 FROM student.guardians candidate
                 WHERE candidate.school_id = parent.school_id
                   AND lower(btrim(candidate.full_name)) = lower(btrim(field.legacy_value)))
            ELSE 0
        END AS identity_candidates
    FROM student_parents parent
    CROSS JOIN LATERAL (
        VALUES
          ('FATHER'::text, 'name'::text, parent.father_name, parent.guardian_name,
           parent.father_guardian_id, parent.father_link_id,
           parent.guardian_version, parent.link_version,
           parent.father_contact_verified_at, parent.father_linked_students,
           parent.father_phone_cluster_guardians),
          ('FATHER'::text, 'contact'::text, parent.father_contact, parent.guardian_phone,
           parent.father_guardian_id, parent.father_link_id,
           parent.guardian_version, parent.link_version,
           parent.father_contact_verified_at, parent.father_linked_students,
           parent.father_phone_cluster_guardians),
          ('MOTHER'::text, 'name'::text, parent.mother_name, parent.normalized_mother_name,
           parent.mother_guardian_id, parent.mother_link_id,
           parent.mother_guardian_version, parent.mother_link_version,
           NULL::timestamptz, parent.mother_linked_students, NULL::bigint)
    ) field(
        relationship, field_name, legacy_value, normalized_value,
        guardian_id, link_id, guardian_version, link_version,
        contact_verified_at, linked_students, phone_cluster_guardians
    )
    WHERE CASE
        WHEN field.field_name = 'contact' THEN
            NULLIF(regexp_replace(COALESCE(field.legacy_value, ''), '[^0-9]', '', 'g'), '')
                IS DISTINCT FROM
            NULLIF(regexp_replace(COALESCE(field.normalized_value, ''), '[^0-9]', '', 'g'), '')
        ELSE
            NULLIF(btrim(COALESCE(field.legacy_value, '')), '')
                IS DISTINCT FROM
            NULLIF(btrim(COALESCE(field.normalized_value, '')), '')
    END
),
classified AS (
    SELECT
        field.*,
        CASE
            WHEN field.guardian_id IS NOT NULL
                 AND (COALESCE(field.linked_students, 0) >= 10
                      OR COALESCE(field.phone_cluster_guardians, 0) >= 10)
                THEN 'PLACEHOLDER_CLUSTER'
            WHEN field.guardian_id IS NULL AND field.relationship = 'FATHER'
                 AND field.father_phone_candidates >= 10
                THEN 'PLACEHOLDER_CANDIDATE'
            WHEN field.guardian_id IS NULL
                 AND (field.identity_candidates > 0 OR field.father_phone_candidates > 0)
                THEN 'IDENTITY_CANDIDATE'
            WHEN field.guardian_id IS NULL AND field.legacy_value IS NOT NULL
                THEN 'MISSING_RELATIONSHIP'
            WHEN field.legacy_value IS NULL AND field.normalized_value IS NOT NULL
                THEN 'PROJECTION_MISSING'
            WHEN field.field_name = 'name'
                 AND lower(btrim(field.legacy_value)) = lower(btrim(field.normalized_value))
                THEN 'CASE_ONLY'
            ELSE 'LINKED_CONFLICT'
        END AS issue_bucket,
        encode(sha256(convert_to(
            field.school_id::text || ':' || field.student_id::text || ':'
            || field.relationship || ':' || field.field_name,
            'UTF8'
        )), 'hex') AS case_id,
        encode(sha256(convert_to(jsonb_build_object(
            'studentVersion', field.student_version,
            'studentUpdatedAt', to_char(field.student_updated_at AT TIME ZONE 'UTC',
                'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
            'relationship', field.relationship,
            'field', field.field_name,
            'legacyValue', field.legacy_value,
            'normalizedValue', field.normalized_value,
            'guardianId', field.guardian_id,
            'guardianVersion', field.guardian_version,
            'linkId', field.link_id,
            'linkVersion', field.link_version
        )::text, 'UTF8')), 'hex') AS case_snapshot_sha256
    FROM fields field
)
SELECT
    classified.case_id,
    classified.case_snapshot_sha256,
    classified.school_id,
    classified.student_id,
    classified.admission_no,
    classified.student_name,
    classified.relationship,
    classified.field_name,
    classified.legacy_value,
    classified.normalized_value,
    classified.guardian_id,
    classified.issue_bucket,
    COALESCE(classified.linked_students, 0) AS linked_students,
    COALESCE(classified.phone_cluster_guardians, 0) AS phone_cluster_guardians,
    classified.identity_candidates,
    classified.contact_verified_at,
    latest.decision,
    latest.notes AS decision_notes,
    latest.decided_by,
    latest.decided_at,
    CASE
        WHEN latest.id IS NULL THEN 'PENDING'
        WHEN latest.case_snapshot_sha256 <> classified.case_snapshot_sha256 THEN 'STALE'
        WHEN latest.decision = 'DEFER' THEN 'DEFERRED'
        WHEN latest.decision = 'ESCALATE' THEN 'ESCALATED'
        ELSE 'DECIDED'
    END AS review_status,
    CASE classified.issue_bucket
        WHEN 'CASE_ONLY' THEN 'ACCEPT_NORMALIZED'
        WHEN 'PROJECTION_MISSING' THEN 'ACCEPT_NORMALIZED'
        WHEN 'PLACEHOLDER_CLUSTER' THEN 'ESCALATE'
        WHEN 'PLACEHOLDER_CANDIDATE' THEN 'ESCALATE'
        WHEN 'IDENTITY_CANDIDATE' THEN 'ESCALATE'
        WHEN 'MISSING_RELATIONSHIP' THEN 'RESOLVE_IN_STUDENT_EDITOR'
        ELSE 'RESOLVE_IN_STUDENT_EDITOR'
    END AS recommended_decision
FROM classified
LEFT JOIN LATERAL (
    SELECT decision.*
    FROM student.guardian_data_review_decisions decision
    WHERE decision.case_id = classified.case_id
      AND decision.school_id = classified.school_id
    ORDER BY decision.decided_at DESC, decision.id DESC
    LIMIT 1
) latest ON TRUE;

REVOKE ALL ON TABLE student.guardian_data_review_decisions FROM PUBLIC;
REVOKE ALL ON TABLE student.guardian_data_review_queue_v1 FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        GRANT SELECT, INSERT ON student.guardian_data_review_decisions TO app_rt;
        GRANT SELECT ON student.guardian_data_review_queue_v1 TO app_rt;
    END IF;
END
$$;
