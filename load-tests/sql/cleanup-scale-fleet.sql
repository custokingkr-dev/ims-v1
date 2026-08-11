\set ON_ERROR_STOP on

BEGIN;
SET LOCAL app.bypass_rls = 'on';
SELECT pg_advisory_xact_lock(hashtext('ims-scale-fixture'));

-- The application relays are scheduled inside Cloud Run, so pausing Cloud
-- Scheduler alone does not serialize their writes. Acquire the same guarded
-- writer envelope used by the backlog cleanup before taking any counts.
SET LOCAL lock_timeout = '30s';
LOCK TABLE tenant_school.outbox_events IN EXCLUSIVE MODE;
LOCK TABLE reporting.reporting_event_inbox IN EXCLUSIVE MODE;

CREATE TEMP TABLE scale_config ON COMMIT DROP AS
SELECT :base_school_id::bigint AS base_school_id,
       :school_count::integer AS school_count,
       'scale-load-superadmin@custoking.local'::text AS load_user_email;

DO $$
DECLARE
    config scale_config%ROWTYPE;
    verified_school_count bigint;
BEGIN
    SELECT * INTO config FROM scale_config;
    IF config.school_count < 1 OR config.school_count > 10000 THEN
        RAISE EXCEPTION 'school_count must be between 1 and 10000, got %', config.school_count;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM tenant_school.schools s
        WHERE s.id >= config.base_school_id
          AND s.id < config.base_school_id + 10000
          AND s.short_code NOT LIKE 'SCALE-%'
    ) THEN
        RAISE EXCEPTION 'reserved scale school id range contains non-scale data';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM tenant_school.schools s
        WHERE s.id >= config.base_school_id
          AND s.id < config.base_school_id + 10000
          AND s.short_code LIKE 'SCALE-%'
          AND s.id >= config.base_school_id + config.school_count
    ) THEN
        RAISE EXCEPTION 'verified scale school exists outside the configured target fleet';
    END IF;

    SELECT count(*) INTO verified_school_count
    FROM tenant_school.schools s
    WHERE s.id >= config.base_school_id
      AND s.id < config.base_school_id + config.school_count
      AND s.short_code LIKE 'SCALE-%';

    -- An intact first pass has the configured fleet count; an idempotent pass
    -- has zero. A partial fleet is ambiguous and must be investigated rather
    -- than broadening or guessing which IDs are safe to remove.
    IF verified_school_count NOT IN (0, config.school_count) THEN
        RAISE EXCEPTION 'configured scale fleet is partial: expected % or 0 schools, found %',
            config.school_count, verified_school_count;
    END IF;
END $$;

-- Use the exact configured ID set, not only currently existing school rows.
-- This makes a second pass idempotent and lets it remove an orphan left by an
-- older, incomplete cleanup without widening into the rest of the 10k range.
CREATE TEMP TABLE scale_school_ids(id bigint PRIMARY KEY) ON COMMIT DROP;
INSERT INTO scale_school_ids(id)
SELECT c.base_school_id + offset_value
FROM scale_config c
CROSS JOIN generate_series(0, c.school_count - 1) AS offset_value;

CREATE TEMP TABLE scale_user_ids(id bigint PRIMARY KEY) ON COMMIT DROP;
INSERT INTO scale_user_ids(id)
SELECT u.id
FROM identity.app_users u, scale_config c
WHERE u.email = c.load_user_email;

-- Snapshot import-batch identity before deleting parents. The scope includes
-- nullable legacy batches inferred from a scale student as well as all modern
-- school-scoped batches. The snapshot remains usable for the after assertion.
CREATE TEMP TABLE scale_import_batch_ids(id text PRIMARY KEY) ON COMMIT DROP;
INSERT INTO scale_import_batch_ids(id)
SELECT b.id::text
FROM student.import_batches b
WHERE b.school_id IN (SELECT id FROM scale_school_ids)
   OR EXISTS (
       SELECT 1
       FROM student.students s
       WHERE s.import_batch_id = b.id
         AND s.school_id IN (SELECT id FROM scale_school_ids)
   );

CREATE TEMP TABLE scale_cleanup_metadata ON COMMIT DROP AS
SELECT
    (SELECT count(*) FROM tenant_school.schools
        WHERE id IN (SELECT id FROM scale_school_ids)
          AND short_code LIKE 'SCALE-%') AS verified_schools_before,
    (SELECT count(*) FROM scale_import_batch_ids) AS import_batches_before,
    (SELECT COALESCE(jsonb_object_agg(status_key, rows ORDER BY status_key), '{}'::jsonb)
       FROM (
           SELECT COALESCE(b.status, '<null>') AS status_key, count(*) AS rows
           FROM student.import_batches b
           WHERE b.id::text IN (SELECT id FROM scale_import_batch_ids)
           GROUP BY COALESCE(b.status, '<null>')
       ) status_counts) AS import_batches_by_status_before;

-- Every DELETE below is constrained by one of three reserved namespaces:
-- exact scale school IDs, snapshotted scale import-batch IDs, or the dedicated
-- scale load user/class identifiers. Ordering is child before parent.
CREATE TEMP TABLE scale_cleanup_relations (
    delete_order integer PRIMARY KEY,
    schema_name text NOT NULL,
    relation_name text NOT NULL,
    scope_predicate text NOT NULL,
    UNIQUE (schema_name, relation_name)
) ON COMMIT DROP;

INSERT INTO scale_cleanup_relations(delete_order, schema_name, relation_name, scope_predicate) VALUES
    (10,  'reporting', 'reporting_event_inbox',       't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (20,  'reporting', 'event_student_contributions', 't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (30,  'reporting', 'academic_events',              't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (40,  'reporting', 'fact_student_review_item',     't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (50,  'reporting', 'fact_payment',                 't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (60,  'reporting', 'fact_fee_assignment',          't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (70,  'reporting', 'fact_attendance_daily',        't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (80,  'reporting', 'fact_catalog_order',           't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (90,  'reporting', 'fact_firefighting_request',    't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (100, 'reporting', 'billing_invoice_read',         't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (110, 'reporting', 'command_center_feed',          't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (120, 'reporting', 'command_center_actions',       't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (130, 'reporting', 'dim_student',                  't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (140, 'reporting', 'dim_section',                  't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (150, 'reporting', 'dim_school',                   't.id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (200, 'attendance', 'absentee_notifications',      't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (210, 'attendance', 'attendance_student_records',  't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (220, 'attendance', 'attendance_daily',            't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (300, 'student', 'student_consent_events',          't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (310, 'student', 'student_guardians',               't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (320, 'student', 'student_review_items',            't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (330, 'student', 'student_promotion_batch_items',   't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (340, 'student', 'photo_import_rows',               't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (350, 'student', 'photo_import_column_mappings',    't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (360, 'student', 'photo_import_sources',            't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (370, 'student', 'import_rows',                     't.school_id IN (SELECT id FROM pg_temp.scale_school_ids) OR t.batch_id::text IN (SELECT id FROM pg_temp.scale_import_batch_ids)'),
    (380, 'student', 'student_enrollments',             't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (390, 'student', 'guardians',                       't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (400, 'student', 'student_review_campaigns',        't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (410, 'student', 'student_promotion_batches',       't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (420, 'student', 'photo_import_batches',            't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (430, 'student', 'photo_import_drive_folders',      't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (440, 'student', 'students',                        't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (450, 'student', 'import_batches',                  't.id::text IN (SELECT id FROM pg_temp.scale_import_batch_ids) OR t.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (500, 'tenant_school', 'outbox_events',             't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (510, 'tenant_school', 'school_timetable_entries',  't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (520, 'tenant_school', 'school_teacher_availability','t.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (530, 'tenant_school', 'school_timetable_publications','t.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (540, 'tenant_school', 'school_class_subjects',     't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (550, 'tenant_school', 'school_class_bell_map',     't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (560, 'tenant_school', 'school_bell_periods',       't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (570, 'tenant_school', 'school_bell_schedules',     't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (580, 'tenant_school', 'school_rooms',              't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (590, 'tenant_school', 'staff_members',             't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (600, 'tenant_school', 'zone_school_mappings',      't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (610, 'tenant_school', 'school_module_entitlements','t.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (620, 'tenant_school', 'school_sections',           't.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (630, 'tenant_school', 'schools',                   't.id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (680, 'tenant_school', 'school_classes',            't.id LIKE ''scale-c-%'''),
    (700, 'identity', 'auth_sessions',                  't.user_id IN (SELECT id FROM pg_temp.scale_user_ids)'),
    (710, 'identity', 'user_role_assignments',          't.user_id IN (SELECT id FROM pg_temp.scale_user_ids) OR t.school_id IN (SELECT id FROM pg_temp.scale_school_ids)'),
    (720, 'identity', 'rbac_audit_log',                 't.school_id IN (SELECT id FROM pg_temp.scale_school_ids) OR t.actor_user_id IN (SELECT id FROM pg_temp.scale_user_ids) OR t.target_user_id IN (SELECT id FROM pg_temp.scale_user_ids)'),
    (730, 'identity', 'app_users',                      't.id IN (SELECT id FROM pg_temp.scale_user_ids)');

DO $$
DECLARE
    relation_record record;
BEGIN
    FOR relation_record IN SELECT * FROM scale_cleanup_relations LOOP
        IF to_regclass(format('%I.%I', relation_record.schema_name, relation_record.relation_name)) IS NULL THEN
            RAISE EXCEPTION 'cleanup schema drift: required relation %.% is absent',
                relation_record.schema_name, relation_record.relation_name;
        END IF;
    END LOOP;
END $$;

CREATE TEMP TABLE scale_cleanup_counts (
    phase text NOT NULL,
    relation_key text NOT NULL,
    scope_rows bigint NOT NULL,
    outside_scope_rows bigint,
    PRIMARY KEY (phase, relation_key)
) ON COMMIT DROP;

DO $$
DECLARE
    relation_record record;
    total_rows bigint;
    scoped_rows bigint;
BEGIN
    FOR relation_record IN SELECT * FROM scale_cleanup_relations ORDER BY delete_order LOOP
        EXECUTE format(
            'SELECT count(*), count(*) FILTER (WHERE %s) FROM %I.%I t',
            relation_record.scope_predicate,
            relation_record.schema_name,
            relation_record.relation_name
        ) INTO total_rows, scoped_rows;

        INSERT INTO scale_cleanup_counts(phase, relation_key, scope_rows, outside_scope_rows)
        VALUES ('before', relation_record.schema_name || '.' || relation_record.relation_name,
                scoped_rows, total_rows - scoped_rows);
    END LOOP;
END $$;

DO $$
DECLARE
    relation_record record;
    deleted_rows bigint;
BEGIN
    FOR relation_record IN SELECT * FROM scale_cleanup_relations ORDER BY delete_order LOOP
        -- A scale class remains protected if any outside-scope section still
        -- references it. The after assertion then fails and rolls back rather
        -- than deleting a shared class or silently leaving partial residue.
        IF relation_record.schema_name = 'tenant_school'
           AND relation_record.relation_name = 'school_classes' THEN
            EXECUTE format(
                'DELETE FROM %I.%I t WHERE (%s) AND NOT EXISTS (' ||
                'SELECT 1 FROM tenant_school.school_sections s WHERE s.school_class_id = t.id)',
                relation_record.schema_name,
                relation_record.relation_name,
                relation_record.scope_predicate
            );
        ELSE
            EXECUTE format(
                'DELETE FROM %I.%I t WHERE %s',
                relation_record.schema_name,
                relation_record.relation_name,
                relation_record.scope_predicate
            );
        END IF;
        GET DIAGNOSTICS deleted_rows = ROW_COUNT;

        INSERT INTO scale_cleanup_counts(phase, relation_key, scope_rows, outside_scope_rows)
        VALUES ('deleted', relation_record.schema_name || '.' || relation_record.relation_name,
                deleted_rows, NULL);
    END LOOP;
END $$;

DO $$
DECLARE
    relation_record record;
    total_rows bigint;
    scoped_rows bigint;
BEGIN
    FOR relation_record IN SELECT * FROM scale_cleanup_relations ORDER BY delete_order LOOP
        EXECUTE format(
            'SELECT count(*), count(*) FILTER (WHERE %s) FROM %I.%I t',
            relation_record.scope_predicate,
            relation_record.schema_name,
            relation_record.relation_name
        ) INTO total_rows, scoped_rows;

        INSERT INTO scale_cleanup_counts(phase, relation_key, scope_rows, outside_scope_rows)
        VALUES ('after', relation_record.schema_name || '.' || relation_record.relation_name,
                scoped_rows, total_rows - scoped_rows);
    END LOOP;
END $$;

CREATE TEMP TABLE scale_cleanup_unhandled_residue (
    relation_key text PRIMARY KEY,
    rows bigint NOT NULL
) ON COMMIT DROP;

-- Fail closed if a current or future base table with school_id retains any
-- target row. This turns schema growth into a reviewed cleanup change instead
-- of allowing a partial delete to commit unnoticed.
DO $$
DECLARE
    column_record record;
    residue_rows bigint;
BEGIN
    FOR column_record IN
        SELECT c.table_schema, c.table_name
        FROM information_schema.columns c
        JOIN information_schema.tables t
          ON t.table_schema = c.table_schema
         AND t.table_name = c.table_name
         AND t.table_type = 'BASE TABLE'
        WHERE c.column_name = 'school_id'
          AND c.table_schema NOT IN ('pg_catalog', 'information_schema')
          AND c.table_schema NOT LIKE 'pg_temp_%'
        ORDER BY c.table_schema, c.table_name
    LOOP
        EXECUTE format(
            'SELECT count(*) FROM %I.%I WHERE school_id IN (SELECT id FROM pg_temp.scale_school_ids)',
            column_record.table_schema,
            column_record.table_name
        ) INTO residue_rows;

        IF residue_rows > 0 THEN
            INSERT INTO scale_cleanup_unhandled_residue(relation_key, rows)
            VALUES (column_record.table_schema || '.' || column_record.table_name, residue_rows);
        END IF;
    END LOOP;
END $$;

DO $$
DECLARE
    mismatch jsonb;
    residue jsonb;
BEGIN
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
               'relation', before_counts.relation_key,
               'before', before_counts.scope_rows,
               'deleted', deleted_counts.scope_rows,
               'after', after_counts.scope_rows,
               'outsideBefore', before_counts.outside_scope_rows,
               'outsideAfter', after_counts.outside_scope_rows
           ) ORDER BY before_counts.relation_key), '[]'::jsonb)
    INTO mismatch
    FROM scale_cleanup_counts before_counts
    JOIN scale_cleanup_counts deleted_counts
      ON deleted_counts.phase = 'deleted'
     AND deleted_counts.relation_key = before_counts.relation_key
    JOIN scale_cleanup_counts after_counts
      ON after_counts.phase = 'after'
     AND after_counts.relation_key = before_counts.relation_key
    WHERE before_counts.phase = 'before'
      AND (before_counts.scope_rows <> deleted_counts.scope_rows
           OR after_counts.scope_rows <> 0
           OR before_counts.outside_scope_rows <> after_counts.outside_scope_rows);

    IF jsonb_array_length(mismatch) > 0 THEN
        RAISE EXCEPTION 'scale cleanup count/residue assertion failed: %', mismatch;
    END IF;

    SELECT COALESCE(jsonb_object_agg(relation_key, rows ORDER BY relation_key), '{}'::jsonb)
    INTO residue
    FROM scale_cleanup_unhandled_residue;
    IF residue <> '{}'::jsonb THEN
        RAISE EXCEPTION 'unhandled scale school residue remains; transaction rolled back: %', residue;
    END IF;
END $$;

SELECT 'IMS_SCALE_CLEANUP|' || jsonb_build_object(
    'baseSchoolId', config.base_school_id,
    'configuredSchoolCount', config.school_count,
    'verifiedSchoolsBefore', metadata.verified_schools_before,
    'importBatchesBefore', metadata.import_batches_before,
    'importBatchesByStatusBefore', metadata.import_batches_by_status_before,
    'before', (
        SELECT jsonb_object_agg(relation_key, jsonb_build_object(
            'scope', scope_rows,
            'outsideScope', outside_scope_rows
        ) ORDER BY relation_key)
        FROM scale_cleanup_counts WHERE phase = 'before'
    ),
    'deleted', (
        SELECT jsonb_object_agg(relation_key, scope_rows ORDER BY relation_key)
        FROM scale_cleanup_counts WHERE phase = 'deleted'
    ),
    'after', (
        SELECT jsonb_object_agg(relation_key, jsonb_build_object(
            'scope', scope_rows,
            'outsideScope', outside_scope_rows
        ) ORDER BY relation_key)
        FROM scale_cleanup_counts WHERE phase = 'after'
    ),
    'unhandledSchoolScopedResidue', '{}'::jsonb,
    'idempotentWhenRepeated', true
)::text
FROM scale_config config, scale_cleanup_metadata metadata;

COMMIT;
