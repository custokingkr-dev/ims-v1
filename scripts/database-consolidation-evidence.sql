-- Read-only evidence bundle for the staged database-consolidation gates.
-- Run as the migration/owner identity with psql -v ON_ERROR_STOP=1.
-- This script intentionally performs no UPDATE, DELETE, ALTER, or DROP.

\pset pager off
\echo 'billing legacy-to-canonical summary'
SELECT * FROM billing.legacy_invoice_migration_summary;

\echo 'billing issues by reason'
SELECT issue, count(*) AS rows
FROM billing.legacy_invoice_migration_issues
GROUP BY issue
ORDER BY issue;

\echo 'catalog legacy mapping readiness'
SELECT *
FROM catalog.legacy_catalog_migration_readiness
ORDER BY source_table;

\echo 'guardian legacy-column parity'
SELECT
    count(*) AS student_rows,
    count(*) FILTER (WHERE father_name_matches AND father_contact_matches AND mother_name_matches)
        AS matching_rows,
    count(*) FILTER (WHERE NOT father_name_matches) AS father_name_mismatches,
    count(*) FILTER (WHERE NOT father_contact_matches) AS father_contact_mismatches,
    count(*) FILTER (WHERE NOT mother_name_matches) AS mother_name_mismatches
FROM student.guardian_legacy_parity;

\echo 'guardian mismatch anatomy'
SELECT
    count(*) FILTER (
        WHERE NOT father_name_matches
          AND NULLIF(btrim(COALESCE(legacy_father_name, '')), '') IS NOT NULL
          AND NULLIF(btrim(COALESCE(normalized_father_name, '')), '') IS NULL
    ) AS father_name_missing_normalized,
    count(*) FILTER (
        WHERE NOT father_name_matches
          AND NULLIF(btrim(COALESCE(legacy_father_name, '')), '') IS NULL
          AND NULLIF(btrim(COALESCE(normalized_father_name, '')), '') IS NOT NULL
    ) AS father_name_missing_legacy,
    count(*) FILTER (
        WHERE NOT father_name_matches
          AND NULLIF(btrim(COALESCE(legacy_father_name, '')), '') IS NOT NULL
          AND NULLIF(btrim(COALESCE(normalized_father_name, '')), '') IS NOT NULL
    ) AS father_name_value_different,
    count(*) FILTER (
        WHERE NOT father_contact_matches
          AND regexp_replace(COALESCE(legacy_father_contact, ''), '[^0-9]', '', 'g') <> ''
          AND regexp_replace(COALESCE(normalized_father_contact, ''), '[^0-9]', '', 'g') = ''
    ) AS father_contact_missing_normalized,
    count(*) FILTER (
        WHERE NOT father_contact_matches
          AND regexp_replace(COALESCE(legacy_father_contact, ''), '[^0-9]', '', 'g') = ''
          AND regexp_replace(COALESCE(normalized_father_contact, ''), '[^0-9]', '', 'g') <> ''
    ) AS father_contact_missing_legacy,
    count(*) FILTER (
        WHERE NOT father_contact_matches
          AND regexp_replace(COALESCE(legacy_father_contact, ''), '[^0-9]', '', 'g') <> ''
          AND regexp_replace(COALESCE(normalized_father_contact, ''), '[^0-9]', '', 'g') <> ''
    ) AS father_contact_value_different,
    count(*) FILTER (
        WHERE NOT mother_name_matches
          AND NULLIF(btrim(COALESCE(legacy_mother_name, '')), '') IS NOT NULL
          AND NULLIF(btrim(COALESCE(normalized_mother_name, '')), '') IS NULL
    ) AS mother_name_missing_normalized,
    count(*) FILTER (
        WHERE NOT mother_name_matches
          AND NULLIF(btrim(COALESCE(legacy_mother_name, '')), '') IS NULL
          AND NULLIF(btrim(COALESCE(normalized_mother_name, '')), '') IS NOT NULL
    ) AS mother_name_missing_legacy,
    count(*) FILTER (
        WHERE NOT mother_name_matches
          AND NULLIF(btrim(COALESCE(legacy_mother_name, '')), '') IS NOT NULL
          AND NULLIF(btrim(COALESCE(normalized_mother_name, '')), '') IS NOT NULL
    ) AS mother_name_value_different
FROM student.guardian_legacy_parity;

\echo 'V24-effective shared father identity conflict summary'
WITH ranked_father_links AS (
    SELECT
        student_row.id AS student_id,
        link.guardian_id,
        student_row.father_name,
        student_row.father_contact,
        row_number() OVER (
            PARTITION BY student_row.id
            ORDER BY link.is_primary DESC, link.updated_at DESC, link.id
        ) AS effective_rank
    FROM student.students student_row
    JOIN student.student_guardians link ON link.student_id = student_row.id
    JOIN student.guardians guardian ON guardian.id = link.guardian_id
    WHERE student_row.deleted_at IS NULL
      AND link.relationship = 'FATHER'
      AND guardian.status = 'ACTIVE'
),
father_identity AS (
    SELECT
        guardian_id,
        count(DISTINCT student_id) AS linked_students,
        count(DISTINCT jsonb_build_array(NULLIF(btrim(COALESCE(father_name, '')), '')))
            AS distinct_legacy_names,
        count(DISTINCT jsonb_build_array(NULLIF(
            regexp_replace(COALESCE(father_contact, ''), '[^0-9]', '', 'g'), '')))
            AS distinct_legacy_contacts
    FROM ranked_father_links
    WHERE effective_rank = 1
    GROUP BY guardian_id
)
SELECT
    count(*) FILTER (WHERE linked_students > 1) AS shared_father_guardians,
    count(*) FILTER (
        WHERE linked_students > 1
          AND (distinct_legacy_names > 1 OR distinct_legacy_contacts > 1)
    ) AS shared_guardians_with_conflicting_legacy_values,
    COALESCE(max(linked_students), 0) AS maximum_students_per_father_guardian
FROM father_identity;

\echo 'guardian repair planning buckets'
WITH active_students AS (
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
all_active_student_link_hazards AS (
    SELECT
        link.guardian_id,
        bool_or(
            link.school_id IS DISTINCT FROM student_row.school_id
            OR guardian.school_id IS DISTINCT FROM student_row.school_id
        ) AS tenant_or_link_anomaly
    FROM active_students student_row
    JOIN student.student_guardians link ON link.student_id = student_row.id
    JOIN student.guardians guardian ON guardian.id = link.guardian_id
    GROUP BY link.guardian_id
),
active_student_link_counts AS (
    SELECT
        student_row.id AS student_id,
        count(link.id) AS total_student_links
    FROM active_students student_row
    LEFT JOIN student.student_guardians link ON link.student_id = student_row.id
    GROUP BY student_row.id
),
student_relationship_intents AS (
    SELECT
        student_row.id AS student_id,
        student_row.school_id,
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
student_intent_validation AS (
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
    FROM student_relationship_intents intent
    JOIN (
        SELECT
            student_id,
            relationship,
            count(*) OVER (PARTITION BY identity_key) AS cluster_size
        FROM student_relationship_intents
    ) cluster
      ON cluster.student_id = intent.student_id
     AND cluster.relationship = intent.relationship
    GROUP BY intent.student_id
),
student_consent_state AS (
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
student_identity_candidate_state AS (
    SELECT
        intent.student_id,
        count(guardian.id) AS same_school_identity_candidates
    FROM student_relationship_intents intent
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
student_expected_id_state AS (
    SELECT
        intent.student_id,
        bool_or(v14_guardian.id IS NOT NULL OR v14_link.id IS NOT NULL)
            AS expected_v14_id_exists,
        bool_or(target_guardian.id IS NOT NULL OR target_link.id IS NOT NULL)
            AS target_id_exists
    FROM student_relationship_intents intent
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
unlinked_student_eligibility AS (
    SELECT
        student_row.id AS student_id,
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
    JOIN active_student_link_counts link_count ON link_count.student_id = student_row.id
    JOIN student_intent_validation validation ON validation.student_id = student_row.id
    JOIN student_consent_state consent ON consent.student_id = student_row.id
    JOIN student_identity_candidate_state candidate ON candidate.student_id = student_row.id
    JOIN student_expected_id_state expected_id ON expected_id.student_id = student_row.id
    WHERE link_count.total_student_links = 0
),
ranked_links AS (
    SELECT
        student_row.id AS student_id,
        student_row.school_id AS student_school_id,
        link.relationship,
        link.guardian_id,
        link.school_id AS link_school_id,
        guardian.school_id AS guardian_school_id,
        guardian.full_name,
        guardian.phone,
        row_number() OVER (
            PARTITION BY student_row.id, link.relationship
            ORDER BY link.is_primary DESC, link.updated_at DESC, link.id
        ) AS effective_rank,
        count(*) OVER (
            PARTITION BY student_row.id, link.relationship
        ) AS active_relationship_links
    FROM active_students student_row
    JOIN student.student_guardians link ON link.student_id = student_row.id
    JOIN student.guardians guardian ON guardian.id = link.guardian_id
    WHERE link.relationship IN ('FATHER', 'MOTHER')
      AND guardian.status = 'ACTIVE'
),
effective_links AS (
    SELECT * FROM ranked_links WHERE effective_rank = 1
),
projected_uses AS (
    SELECT
        effective.student_id,
        effective.student_school_id,
        effective.relationship,
        effective.guardian_id,
        effective.link_school_id,
        effective.guardian_school_id,
        effective.active_relationship_links,
        'name'::text AS field_name,
        CASE effective.relationship
            WHEN 'FATHER' THEN NULLIF(btrim(COALESCE(student_row.father_name, '')), '')
            ELSE NULLIF(btrim(COALESCE(student_row.mother_name, '')), '')
        END AS legacy_value
    FROM effective_links effective
    JOIN active_students student_row ON student_row.id = effective.student_id

    UNION ALL

    SELECT
        effective.student_id,
        effective.student_school_id,
        effective.relationship,
        effective.guardian_id,
        effective.link_school_id,
        effective.guardian_school_id,
        effective.active_relationship_links,
        'contact'::text,
        NULLIF(regexp_replace(COALESCE(student_row.father_contact, ''), '[^0-9]', '', 'g'), '')
    FROM effective_links effective
    JOIN active_students student_row ON student_row.id = effective.student_id
    WHERE effective.relationship = 'FATHER'
),
guardian_hazards AS (
    SELECT
        projected.guardian_id,
        bool_or(projected.active_relationship_links <> 1)
            OR COALESCE(bool_or(link_hazard.tenant_or_link_anomaly), false)
            AS tenant_or_link_anomaly,
        count(DISTINCT jsonb_build_array(projected.legacy_value))
            FILTER (WHERE field_name = 'name') > 1 AS name_divergent,
        count(DISTINCT jsonb_build_array(projected.legacy_value))
            FILTER (WHERE field_name = 'contact') > 1 AS contact_divergent
    FROM projected_uses projected
    LEFT JOIN all_active_student_link_hazards link_hazard
      ON link_hazard.guardian_id = projected.guardian_id
    GROUP BY projected.guardian_id
),
student_fields AS (
    SELECT
        student_row.id AS student_id,
        student_row.school_id,
        field.relationship,
        field.field_name,
        field.legacy_value
    FROM active_students student_row
    CROSS JOIN LATERAL (
        VALUES
            ('FATHER'::text, 'name'::text,
             NULLIF(btrim(COALESCE(student_row.father_name, '')), '')),
            ('FATHER'::text, 'contact'::text,
             NULLIF(regexp_replace(COALESCE(student_row.father_contact, ''), '[^0-9]', '', 'g'), '')),
            ('MOTHER'::text, 'name'::text,
             NULLIF(btrim(COALESCE(student_row.mother_name, '')), ''))
    ) AS field(relationship, field_name, legacy_value)
),
field_state AS (
    SELECT
        field.student_id,
        field.school_id,
        field.relationship,
        field.field_name,
        field.legacy_value,
        CASE field.field_name
            WHEN 'contact' THEN NULLIF(regexp_replace(COALESCE(effective.phone, ''), '[^0-9]', '', 'g'), '')
            ELSE NULLIF(btrim(COALESCE(effective.full_name, '')), '')
        END AS normalized_value,
        effective.guardian_id,
        link_count.total_student_links,
        student_row.version AS student_version,
        student_row.updated_at AS student_updated_at,
        student_row.father_name AS legacy_father_name_raw,
        student_row.father_contact AS legacy_father_contact_raw,
        student_row.mother_name AS legacy_mother_name_raw,
        student_row.school_exists,
        student_row.school_is_active,
        intent.expected_v14_guardian_id,
        intent.expected_v14_link_id,
        intent.target_guardian_id,
        intent.target_link_id,
        eligibility.eligibility_bucket AS unlinked_eligibility_bucket,
        eligibility.intended_relationships,
        eligibility.guardian_bound_consents,
        eligibility.same_school_identity_candidates,
        eligibility.maximum_identity_cluster_size,
        COALESCE(hazard.tenant_or_link_anomaly, false) AS tenant_or_link_anomaly,
        CASE field.field_name
            WHEN 'contact' THEN COALESCE(hazard.contact_divergent, false)
            ELSE COALESCE(hazard.name_divergent, false)
        END AS global_identity_divergent
    FROM student_fields field
    LEFT JOIN effective_links effective
      ON effective.student_id = field.student_id
     AND effective.relationship = field.relationship
    JOIN active_student_link_counts link_count ON link_count.student_id = field.student_id
    JOIN active_students student_row ON student_row.id = field.student_id
    LEFT JOIN student_relationship_intents intent
      ON intent.student_id = field.student_id
     AND intent.relationship = field.relationship
    LEFT JOIN unlinked_student_eligibility eligibility ON eligibility.student_id = field.student_id
    LEFT JOIN guardian_hazards hazard ON hazard.guardian_id = effective.guardian_id
),
classified AS (
    SELECT
        *,
        CASE
            WHEN legacy_value IS NOT DISTINCT FROM normalized_value THEN NULL
            WHEN guardian_id IS NULL
             AND total_student_links = 0
             AND legacy_value IS NOT NULL THEN unlinked_eligibility_bucket
            WHEN guardian_id IS NULL THEN 'REVIEW_INACTIVE_OR_MISSING_EFFECTIVE_LINK'
            WHEN tenant_or_link_anomaly THEN 'REVIEW_TENANT_OR_LINK_ANOMALY'
            WHEN global_identity_divergent THEN 'REVIEW_SHARED_DIVERGENCE'
            WHEN legacy_value IS NOT NULL AND normalized_value IS NULL THEN 'SAFE_LEGACY_ONLY'
            WHEN legacy_value IS NOT NULL AND normalized_value IS NOT NULL
                THEN 'REVIEW_BOTH_PRESENT_DIFFERENT'
            WHEN legacy_value IS NULL AND normalized_value IS NOT NULL THEN 'REVIEW_NORMALIZED_ONLY'
            ELSE 'REVIEW_INACTIVE_OR_MISSING_EFFECTIVE_LINK'
        END AS repair_bucket
    FROM field_state
),
mismatched_plan_state AS (
    SELECT *
    FROM classified
    WHERE repair_bucket IS NOT NULL
),
fingerprint_records AS (
    SELECT
        *,
        jsonb_build_object(
            'student_id', student_id,
            'school_id', school_id,
            'student_version', student_version,
            'student_updated_at', student_updated_at,
            'school_exists', school_exists,
            'school_is_active', school_is_active,
            'legacy_father_name_raw', legacy_father_name_raw,
            'legacy_father_contact_raw', legacy_father_contact_raw,
            'legacy_mother_name_raw', legacy_mother_name_raw,
            'relationship', relationship,
            'field_name', field_name,
            'guardian_id', guardian_id,
            'legacy_value', legacy_value,
            'normalized_value', normalized_value,
            'total_student_links', total_student_links,
            'expected_v14_guardian_id', expected_v14_guardian_id,
            'expected_v14_link_id', expected_v14_link_id,
            'target_guardian_id', target_guardian_id,
            'target_link_id', target_link_id,
            'unlinked_eligibility_bucket', unlinked_eligibility_bucket,
            'intended_relationships', intended_relationships,
            'guardian_bound_consents', guardian_bound_consents,
            'same_school_identity_candidates', same_school_identity_candidates,
            'maximum_identity_cluster_size', maximum_identity_cluster_size,
            'repair_bucket', repair_bucket
        ) AS fingerprint_record
    FROM mismatched_plan_state
),
bucket_summary AS (
    SELECT
        repair_bucket,
        relationship,
        field_name,
        count(*) AS field_actions,
        count(DISTINCT student_id) AS distinct_students,
        count(DISTINCT guardian_id) FILTER (WHERE guardian_id IS NOT NULL) AS guardian_actions
    FROM fingerprint_records
    GROUP BY repair_bucket, relationship, field_name
),
plan_fingerprint AS (
    SELECT
        count(*) AS planned_field_actions,
        count(*) FILTER (WHERE repair_bucket = 'SAFE_LEGACY_ONLY') AS safe_legacy_only_actions,
        count(*) FILTER (WHERE repair_bucket = 'SAFE_CREATE_UNLINKED_STUDENT')
            AS safe_create_unlinked_actions,
        count(DISTINCT (student_id, relationship))
            FILTER (WHERE repair_bucket = 'SAFE_CREATE_UNLINKED_STUDENT')
            AS safe_create_relationship_actions,
        count(DISTINCT student_id)
            FILTER (WHERE repair_bucket = 'SAFE_CREATE_UNLINKED_STUDENT')
            AS safe_create_students,
        encode(sha256(convert_to(COALESCE(
            jsonb_agg(
                fingerprint_record
                ORDER BY student_id, relationship, field_name
            )::text,
            '[]'
        ), 'UTF8')), 'hex') AS plan_sha256
        ,encode(sha256(convert_to(COALESCE(
            jsonb_agg(
                fingerprint_record
                ORDER BY student_id, relationship, field_name
            ) FILTER (WHERE repair_bucket = 'SAFE_CREATE_UNLINKED_STUDENT')::text,
            '[]'
        ), 'UTF8')), 'hex') AS safe_create_plan_sha256
    FROM fingerprint_records
)
SELECT
    summary.repair_bucket,
    summary.relationship,
    summary.field_name,
    COALESCE(summary.field_actions, 0) AS field_actions,
    COALESCE(summary.distinct_students, 0) AS distinct_students,
    COALESCE(summary.guardian_actions, 0) AS guardian_actions,
    fingerprint.planned_field_actions,
    fingerprint.safe_legacy_only_actions,
    fingerprint.safe_create_unlinked_actions,
    fingerprint.safe_create_relationship_actions,
    fingerprint.safe_create_students,
    fingerprint.plan_sha256,
    fingerprint.safe_create_plan_sha256
FROM plan_fingerprint fingerprint
LEFT JOIN bucket_summary summary ON true
ORDER BY summary.repair_bucket, summary.relationship, summary.field_name;

\echo 'reporting student projection parity'
SELECT * FROM reporting.student_projection_reconciliation_summary;
SELECT issue, count(*) AS rows
FROM reporting.student_projection_reconciliation
WHERE issue IS NOT NULL
GROUP BY issue
ORDER BY issue;

\echo 'processed outbox retention candidates (30-day observation only)'
SELECT 'billing' AS service, count(*) AS candidate_rows
FROM billing.outbox_events WHERE published_at < now() - interval '30 days'
UNION ALL
SELECT 'operations', count(*) FROM firefighting.outbox_events WHERE published_at < now() - interval '30 days'
UNION ALL
SELECT 'school-core', count(*) FROM tenant_school.outbox_events WHERE published_at < now() - interval '30 days'
ORDER BY service;

\echo 'largest user tables and indexes'
SELECT
    schemaname,
    relname,
    pg_size_pretty(pg_total_relation_size(relid)) AS total_size,
    pg_size_pretty(pg_relation_size(relid)) AS table_size,
    pg_size_pretty(pg_indexes_size(relid)) AS index_size,
    n_live_tup,
    n_dead_tup
FROM pg_stat_user_tables
ORDER BY pg_total_relation_size(relid) DESC
LIMIT 50;

\echo 'unused-index observations (not a drop list)'
SELECT
    schemaname,
    relname,
    indexrelname,
    idx_scan,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size
FROM pg_stat_user_indexes
WHERE idx_scan = 0
ORDER BY pg_relation_size(indexrelid) DESC
LIMIT 100;

\echo 'duplicate index definitions (review constraints before any action)'
SELECT
    namespace.nspname AS schemaname,
    table_class.relname AS tablename,
    indexes.indisunique,
    indexes.indkey::text AS key_columns,
    COALESCE(pg_get_expr(indexes.indexprs, indexes.indrelid), '') AS expressions,
    COALESCE(pg_get_expr(indexes.indpred, indexes.indrelid), '') AS predicate,
    array_agg(index_class.relname ORDER BY index_class.relname) AS index_names
FROM pg_index indexes
JOIN pg_class table_class ON table_class.oid = indexes.indrelid
JOIN pg_class index_class ON index_class.oid = indexes.indexrelid
JOIN pg_namespace namespace ON namespace.oid = table_class.relnamespace
WHERE namespace.nspname NOT IN ('pg_catalog', 'information_schema')
GROUP BY namespace.nspname, table_class.relname, indexes.indisunique,
         indexes.indkey::text, indexes.indclass::text, indexes.indcollation::text,
         pg_get_expr(indexes.indexprs, indexes.indrelid),
         pg_get_expr(indexes.indpred, indexes.indrelid)
HAVING count(*) > 1
ORDER BY schemaname, tablename, key_columns;

\echo 'pg_stat_statements availability'
SELECT to_regclass('public.pg_stat_statements') IS NOT NULL AS available;
