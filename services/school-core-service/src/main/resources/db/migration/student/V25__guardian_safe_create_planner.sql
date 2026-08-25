-- Versioned, read-only planning contract for repairing legacy guardian values when an
-- active student has no guardian links at all.  The row-level function contains PII and
-- is deliberately owner-only.  The aggregate function is also owner-only so the evidence
-- and future repair workflows must continue to use the dedicated migration operator.

-- Some schema-isolation checks migrate the student schema before tenant_school. Flyway
-- runs this migration transactionally; defer cross-schema body resolution here while the
-- PostgreSQL planner integration test validates the function after both schemas migrate.
SET LOCAL check_function_bodies = off;

CREATE OR REPLACE FUNCTION student.guardian_safe_create_contract_v1()
RETURNS TABLE (
    contract_version TEXT,
    contract_digest TEXT,
    contract_manifest JSONB
)
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    WITH contract AS (
        SELECT jsonb_build_object(
                'version', 'guardian-safe-create-v1',
                'scope', 'active-student-whole-record-zero-links',
                'father_identity', 'normalized-phone-when-present-otherwise-normalized-name',
                'mother_identity', 'normalized-name',
                'deterministic_guardian_id', 'guardian-repair-v1-md5(student-id:relationship)',
                'deterministic_link_id', 'guardian-link-repair-v1-md5(student-id:relationship)',
                'timestamp_fingerprint', 'UTC-microsecond-text',
                'execution_action', 'create-only-no-merge-no-reactivation-no-update',
                'guardian_status', 'ACTIVE',
                'contact_verified_at', NULL,
                'link_authority_flags', jsonb_build_object(
                    'receives_notifications', false,
                    'can_view_academic', false,
                    'can_manage_fees', false,
                    'pickup_authorized', false
                ),
                'primary_rule', 'father-when-present-otherwise-mother',
                'consent_effect', 'no-insert-no-update-no-delete',
                'profile_review_effect',
                    'invalidate-profile-fields-preserve-verified-photo-and-emit-review-outbox',
                'outbox_contract', jsonb_build_array(
                    'student.guardian.upserted.v1',
                    'student-review-item.upserted.v1'
                ),
                'transaction_isolation', 'SERIALIZABLE',
                'transaction_advisory_lock', 'student.guardian-repair-v1',
                'transaction_table_lock_mode', 'SHARE MODE NOWAIT',
                'transaction_table_lock_order', jsonb_build_array(
                    'tenant_school.schools',
                    'student.students',
                    'student.guardians',
                    'student.student_guardians',
                    'student.student_consent_events',
                    'student.student_review_campaigns',
                    'student.student_review_items'
                ),
                'ledger_contract', 'guardian-repair-ledger-v1',
                'idempotency_contract',
                    'deterministic-run-id-from-contract-and-plan-completed-replay-returns-stored-result',
                'execution_approval_gate',
                    'compiled-exact-contract-plan-counts-requires-later-reviewed-repin',
                'execution_provenance', jsonb_build_array(
                    'approval-reference',
                    'deployed-source-revision',
                    'runner-payload-sha256',
                    'operator-job-name',
                    'database-user'
                ),
                'gates', jsonb_build_array(
                    'school-exists-and-active',
                    'legacy-values-representable',
                    'no-guardian-bound-consent-or-consent-graph-anomaly',
                    'no-v14-or-target-id-collision',
                    'no-same-school-identity-candidate',
                    'no-repeated-legacy-identity-cluster'
                ),
                'eligibility_precedence', jsonb_build_array(
                    'REVIEW_UNLINKED_SCHOOL_MISSING_OR_INACTIVE',
                    'REVIEW_UNLINKED_INVALID_OR_UNAPPROVED_LEGACY_VALUE',
                    'REVIEW_UNLINKED_GUARDIAN_CONSENT',
                    'REVIEW_UNLINKED_DETERMINISTIC_ID_EXISTS',
                    'REVIEW_UNLINKED_IDENTITY_CANDIDATE',
                    'REVIEW_UNLINKED_SHARED_LEGACY_CLUSTER',
                    'SAFE_CREATE_UNLINKED_STUDENT'
                )
            ) AS manifest
    )
    SELECT
        'guardian-safe-create-v1'::text,
        encode(sha256(convert_to(contract.manifest::text, 'UTF8')), 'hex'),
        contract.manifest
    FROM contract
$$;

CREATE OR REPLACE FUNCTION student.guardian_safe_create_plan_v1()
RETURNS TABLE (
    contract_version TEXT,
    contract_digest TEXT,
    student_id BIGINT,
    school_id BIGINT,
    student_version BIGINT,
    student_updated_at TIMESTAMPTZ,
    school_exists BOOLEAN,
    school_is_active BOOLEAN,
    legacy_father_name_raw TEXT,
    legacy_father_contact_raw TEXT,
    legacy_mother_name_raw TEXT,
    relationship TEXT,
    field_name TEXT,
    legacy_value TEXT,
    intent_shape TEXT,
    expected_v14_guardian_id TEXT,
    expected_v14_link_id TEXT,
    target_guardian_id TEXT,
    target_link_id TEXT,
    total_student_links BIGINT,
    eligibility_bucket TEXT,
    intended_relationships BIGINT,
    guardian_bound_consents BIGINT,
    same_school_identity_candidates BIGINT,
    maximum_identity_cluster_size BIGINT,
    fingerprint_record JSONB
)
LANGUAGE sql
STABLE
PARALLEL SAFE
SECURITY INVOKER
AS $$
WITH contract AS (
    SELECT * FROM student.guardian_safe_create_contract_v1()
),
active_students AS (
    SELECT
        student_row.id,
        student_row.school_id,
        student_row.father_name,
        student_row.father_contact,
        student_row.mother_name,
        student_row.version,
        student_row.updated_at,
        school.id IS NOT NULL AS school_exists,
        COALESCE(school.active, false) AS school_is_active
    FROM student.students student_row
    LEFT JOIN tenant_school.schools school ON school.id = student_row.school_id
    WHERE student_row.deleted_at IS NULL
),
student_link_counts AS (
    SELECT
        student_row.id AS student_id,
        count(link.id) AS total_student_links
    FROM active_students student_row
    LEFT JOIN student.student_guardians link ON link.student_id = student_row.id
    GROUP BY student_row.id
),
relationship_intents AS (
    SELECT
        student_row.id AS student_id,
        student_row.school_id,
        student_row.version AS student_version,
        student_row.updated_at AS student_updated_at,
        student_row.school_exists,
        student_row.school_is_active,
        student_row.father_name AS legacy_father_name_raw,
        student_row.father_contact AS legacy_father_contact_raw,
        student_row.mother_name AS legacy_mother_name_raw,
        intent.relationship,
        intent.legacy_name,
        intent.raw_contact,
        intent.normalized_contact,
        CASE
            WHEN intent.legacy_name IS NOT NULL AND intent.raw_contact IS NOT NULL
                THEN 'NAME_AND_CONTACT'
            WHEN intent.legacy_name IS NOT NULL THEN 'NAME_ONLY'
            ELSE 'CONTACT_ONLY'
        END AS intent_shape,
        CASE intent.relationship
            WHEN 'FATHER' THEN 'legacy-' || md5(
                student_row.school_id::text || ':father:'
                || lower(COALESCE(intent.legacy_name, '')) || ':'
                || CASE WHEN intent.raw_contact IS NULL
                    THEN student_row.id::text ELSE intent.normalized_contact END)
            ELSE 'legacy-' || md5(
                student_row.school_id::text || ':mother:'
                || lower(intent.legacy_name) || ':' || student_row.id::text)
        END AS expected_v14_guardian_id,
        'legacy-link-' || md5(
            student_row.id::text || ':' || lower(intent.relationship))
            AS expected_v14_link_id,
        'guardian-repair-v1-' || md5(
            student_row.id::text || ':' || lower(intent.relationship))
            AS target_guardian_id,
        'guardian-link-repair-v1-' || md5(
            student_row.id::text || ':' || lower(intent.relationship))
            AS target_link_id,
        CASE
            WHEN intent.relationship = 'FATHER' AND intent.raw_contact IS NOT NULL
                THEN jsonb_build_array(
                    student_row.school_id, intent.relationship,
                    'PHONE', intent.normalized_contact)
            ELSE jsonb_build_array(
                student_row.school_id, intent.relationship,
                'NAME', lower(intent.legacy_name))
        END AS identity_key
    FROM active_students student_row
    CROSS JOIN LATERAL (
        VALUES
            (
                'FATHER'::text,
                NULLIF(btrim(COALESCE(student_row.father_name, '')), ''),
                NULLIF(btrim(COALESCE(student_row.father_contact, '')), ''),
                NULLIF(regexp_replace(COALESCE(student_row.father_contact, ''), '[^0-9]', '', 'g'), '')
            ),
            (
                'MOTHER'::text,
                NULLIF(btrim(COALESCE(student_row.mother_name, '')), ''),
                NULL::text,
                NULL::text
            )
    ) AS intent(relationship, legacy_name, raw_contact, normalized_contact)
    WHERE intent.legacy_name IS NOT NULL OR intent.raw_contact IS NOT NULL
),
intent_validation AS (
    SELECT
        intent.student_id,
        bool_or(
            length(intent.legacy_name) > 255
            OR (
                intent.relationship = 'FATHER'
                AND (
                    intent.legacy_name IS NULL
                    OR length(intent.raw_contact) > 32
                    OR (
                        intent.raw_contact IS NOT NULL
                        AND (
                            intent.normalized_contact IS NULL
                            OR length(intent.normalized_contact) NOT BETWEEN 10 AND 15
                        )
                    )
                )
            )
        ) AS invalid_or_unapproved_value,
        max(cluster.cluster_size) AS maximum_identity_cluster_size,
        count(*) AS intended_relationships
    FROM relationship_intents intent
    JOIN (
        SELECT
            student_id,
            relationship,
            count(*) OVER (PARTITION BY identity_key) AS cluster_size
        FROM relationship_intents
    ) cluster
      ON cluster.student_id = intent.student_id
     AND cluster.relationship = intent.relationship
    GROUP BY intent.student_id
),
consent_state AS (
    SELECT
        student_row.id AS student_id,
        count(consent.id) FILTER (WHERE consent.guardian_id IS NOT NULL)
            AS guardian_bound_consents,
        bool_or(
            consent.id IS NOT NULL
            AND (
                consent.school_id IS DISTINCT FROM student_row.school_id
                OR (
                    consent.guardian_id IS NOT NULL
                    AND guardian.id IS NULL
                )
                OR (
                    consent.guardian_id IS NOT NULL
                    AND guardian.school_id IS DISTINCT FROM student_row.school_id
                )
            )
        ) AS consent_graph_anomaly
    FROM active_students student_row
    LEFT JOIN student.student_consent_events consent ON consent.student_id = student_row.id
    LEFT JOIN student.guardians guardian ON guardian.id = consent.guardian_id
    GROUP BY student_row.id
),
identity_candidate_state AS (
    SELECT
        intent.student_id,
        count(guardian.id) AS same_school_identity_candidates
    FROM relationship_intents intent
    LEFT JOIN student.guardians guardian
      ON guardian.school_id = intent.school_id
     AND CASE
        WHEN intent.relationship = 'MOTHER' THEN
            lower(btrim(guardian.full_name)) = lower(intent.legacy_name)
        WHEN intent.raw_contact IS NOT NULL THEN
            NULLIF(regexp_replace(COALESCE(guardian.phone, ''), '[^0-9]', '', 'g'), '')
                IS NOT DISTINCT FROM intent.normalized_contact
        ELSE
            lower(btrim(guardian.full_name)) = lower(intent.legacy_name)
     END
    GROUP BY intent.student_id
),
expected_id_state AS (
    SELECT
        intent.student_id,
        bool_or(v14_guardian.id IS NOT NULL OR v14_link.id IS NOT NULL)
            AS expected_v14_id_exists,
        bool_or(target_guardian.id IS NOT NULL OR target_link.id IS NOT NULL)
            AS target_id_exists
    FROM relationship_intents intent
    LEFT JOIN student.guardians v14_guardian
      ON v14_guardian.id = intent.expected_v14_guardian_id
    LEFT JOIN student.student_guardians v14_link
      ON v14_link.id = intent.expected_v14_link_id
    LEFT JOIN student.guardians target_guardian
      ON target_guardian.id = intent.target_guardian_id
    LEFT JOIN student.student_guardians target_link
      ON target_link.id = intent.target_link_id
    GROUP BY intent.student_id
),
student_eligibility AS (
    SELECT
        student_row.id AS student_id,
        link_count.total_student_links,
        validation.intended_relationships,
        consent.guardian_bound_consents,
        candidate.same_school_identity_candidates,
        validation.maximum_identity_cluster_size,
        CASE
            WHEN NOT student_row.school_exists OR NOT student_row.school_is_active
                THEN 'REVIEW_UNLINKED_SCHOOL_MISSING_OR_INACTIVE'
            WHEN validation.invalid_or_unapproved_value
                THEN 'REVIEW_UNLINKED_INVALID_OR_UNAPPROVED_LEGACY_VALUE'
            WHEN consent.consent_graph_anomaly OR consent.guardian_bound_consents > 0
                THEN 'REVIEW_UNLINKED_GUARDIAN_CONSENT'
            WHEN expected_id.expected_v14_id_exists OR expected_id.target_id_exists
                THEN 'REVIEW_UNLINKED_DETERMINISTIC_ID_EXISTS'
            WHEN candidate.same_school_identity_candidates > 0
                THEN 'REVIEW_UNLINKED_IDENTITY_CANDIDATE'
            WHEN validation.maximum_identity_cluster_size > 1
                THEN 'REVIEW_UNLINKED_SHARED_LEGACY_CLUSTER'
            ELSE 'SAFE_CREATE_UNLINKED_STUDENT'
        END AS eligibility_bucket
    FROM active_students student_row
    JOIN student_link_counts link_count ON link_count.student_id = student_row.id
    JOIN intent_validation validation ON validation.student_id = student_row.id
    JOIN consent_state consent ON consent.student_id = student_row.id
    JOIN identity_candidate_state candidate ON candidate.student_id = student_row.id
    JOIN expected_id_state expected_id ON expected_id.student_id = student_row.id
    WHERE link_count.total_student_links = 0
),
field_plan AS (
    SELECT
        contract.contract_version,
        contract.contract_digest,
        intent.student_id,
        intent.school_id,
        intent.student_version,
        intent.student_updated_at,
        intent.school_exists,
        intent.school_is_active,
        intent.legacy_father_name_raw,
        intent.legacy_father_contact_raw,
        intent.legacy_mother_name_raw,
        intent.relationship,
        field.field_name,
        field.legacy_value,
        intent.intent_shape,
        intent.expected_v14_guardian_id,
        intent.expected_v14_link_id,
        intent.target_guardian_id,
        intent.target_link_id,
        eligibility.total_student_links,
        eligibility.eligibility_bucket,
        eligibility.intended_relationships,
        eligibility.guardian_bound_consents,
        eligibility.same_school_identity_candidates,
        eligibility.maximum_identity_cluster_size
    FROM relationship_intents intent
    JOIN student_eligibility eligibility ON eligibility.student_id = intent.student_id
    CROSS JOIN contract
    CROSS JOIN LATERAL (
        VALUES
            ('name'::text, intent.legacy_name),
            ('contact'::text, CASE WHEN intent.relationship = 'FATHER'
                THEN intent.normalized_contact ELSE NULL::text END)
    ) AS field(field_name, legacy_value)
    WHERE field.legacy_value IS NOT NULL
)
SELECT
    plan.*,
    jsonb_build_object(
        'contract_version', plan.contract_version,
        'contract_digest', plan.contract_digest,
        'student_id', plan.student_id,
        'school_id', plan.school_id,
        'student_version', plan.student_version,
        'student_updated_at', to_char(
            plan.student_updated_at AT TIME ZONE 'UTC',
            'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'
        ),
        'school_exists', plan.school_exists,
        'school_is_active', plan.school_is_active,
        'legacy_father_name_raw', plan.legacy_father_name_raw,
        'legacy_father_contact_raw', plan.legacy_father_contact_raw,
        'legacy_mother_name_raw', plan.legacy_mother_name_raw,
        'relationship', plan.relationship,
        'field_name', plan.field_name,
        'guardian_id', NULL,
        'legacy_value', plan.legacy_value,
        'normalized_value', NULL,
        'total_student_links', plan.total_student_links,
        'expected_v14_guardian_id', plan.expected_v14_guardian_id,
        'expected_v14_link_id', plan.expected_v14_link_id,
        'target_guardian_id', plan.target_guardian_id,
        'target_link_id', plan.target_link_id,
        'unlinked_eligibility_bucket', plan.eligibility_bucket,
        'intended_relationships', plan.intended_relationships,
        'guardian_bound_consents', plan.guardian_bound_consents,
        'same_school_identity_candidates', plan.same_school_identity_candidates,
        'maximum_identity_cluster_size', plan.maximum_identity_cluster_size,
        'repair_bucket', plan.eligibility_bucket
    ) AS fingerprint_record
FROM field_plan plan
$$;

CREATE OR REPLACE FUNCTION student.guardian_safe_create_summary_v1()
RETURNS TABLE (
    contract_version TEXT,
    contract_digest TEXT,
    eligibility_bucket TEXT,
    relationship TEXT,
    field_name TEXT,
    field_actions BIGINT,
    distinct_students BIGINT,
    unlinked_field_actions BIGINT,
    unlinked_relationship_actions BIGINT,
    unlinked_students BIGINT,
    safe_create_field_actions BIGINT,
    safe_create_relationship_actions BIGINT,
    safe_create_students BIGINT,
    unlinked_plan_sha256 TEXT,
    safe_create_plan_sha256 TEXT
)
LANGUAGE sql
STABLE
PARALLEL SAFE
SECURITY INVOKER
AS $$
WITH plan AS (
    SELECT * FROM student.guardian_safe_create_plan_v1()
),
bucket_summary AS (
    SELECT
        eligibility_bucket,
        relationship,
        field_name,
        count(*) AS field_actions,
        count(DISTINCT student_id) AS distinct_students
    FROM plan
    GROUP BY eligibility_bucket, relationship, field_name
),
fingerprint AS (
    SELECT
        count(*) AS unlinked_field_actions,
        count(DISTINCT (student_id, relationship)) AS unlinked_relationship_actions,
        count(DISTINCT student_id) AS unlinked_students,
        count(*) FILTER (WHERE eligibility_bucket = 'SAFE_CREATE_UNLINKED_STUDENT')
            AS safe_create_field_actions,
        count(DISTINCT (student_id, relationship))
            FILTER (WHERE eligibility_bucket = 'SAFE_CREATE_UNLINKED_STUDENT')
            AS safe_create_relationship_actions,
        count(DISTINCT student_id)
            FILTER (WHERE eligibility_bucket = 'SAFE_CREATE_UNLINKED_STUDENT')
            AS safe_create_students,
        encode(sha256(convert_to(COALESCE(
            jsonb_agg(fingerprint_record ORDER BY student_id, relationship, field_name)::text,
            '[]'
        ), 'UTF8')), 'hex') AS unlinked_plan_sha256,
        encode(sha256(convert_to(COALESCE(
            jsonb_agg(fingerprint_record ORDER BY student_id, relationship, field_name)
                FILTER (WHERE eligibility_bucket = 'SAFE_CREATE_UNLINKED_STUDENT')::text,
            '[]'
        ), 'UTF8')), 'hex') AS safe_create_plan_sha256
    FROM plan
),
contract AS (
    SELECT * FROM student.guardian_safe_create_contract_v1()
)
SELECT
    contract.contract_version,
    contract.contract_digest,
    bucket.eligibility_bucket,
    bucket.relationship,
    bucket.field_name,
    COALESCE(bucket.field_actions, 0),
    COALESCE(bucket.distinct_students, 0),
    fingerprint.unlinked_field_actions,
    fingerprint.unlinked_relationship_actions,
    fingerprint.unlinked_students,
    fingerprint.safe_create_field_actions,
    fingerprint.safe_create_relationship_actions,
    fingerprint.safe_create_students,
    fingerprint.unlinked_plan_sha256,
    fingerprint.safe_create_plan_sha256
FROM contract
CROSS JOIN fingerprint
LEFT JOIN bucket_summary bucket ON true
ORDER BY bucket.eligibility_bucket, bucket.relationship, bucket.field_name
$$;

REVOKE ALL ON FUNCTION student.guardian_safe_create_contract_v1() FROM PUBLIC;
REVOKE ALL ON FUNCTION student.guardian_safe_create_plan_v1() FROM PUBLIC;
REVOKE ALL ON FUNCTION student.guardian_safe_create_summary_v1() FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        REVOKE ALL ON FUNCTION student.guardian_safe_create_contract_v1() FROM app_rt;
        REVOKE ALL ON FUNCTION student.guardian_safe_create_plan_v1() FROM app_rt;
        REVOKE ALL ON FUNCTION student.guardian_safe_create_summary_v1() FROM app_rt;
    END IF;
END
$$;
