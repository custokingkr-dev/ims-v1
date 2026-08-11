\set ON_ERROR_STOP on

-- DESTRUCTIVE TEST FIXTURE. Run only in a disposable PostgreSQL 16 database.
DO $$
BEGIN
    IF current_database() NOT LIKE 'cleanup_test%' THEN
        RAISE EXCEPTION 'refusing destructive fixture outside a cleanup_test* database';
    END IF;
    IF current_setting('server_version_num')::integer / 10000 <> 16 THEN
        RAISE EXCEPTION 'cleanup fixture requires PostgreSQL 16';
    END IF;
END $$;

DROP SCHEMA IF EXISTS reporting CASCADE;
DROP SCHEMA IF EXISTS attendance CASCADE;
DROP SCHEMA IF EXISTS billing CASCADE;
DROP SCHEMA IF EXISTS firefighting CASCADE;
DROP SCHEMA IF EXISTS student CASCADE;
DROP SCHEMA IF EXISTS tenant_school CASCADE;
DROP SCHEMA IF EXISTS identity CASCADE;
DROP SCHEMA IF EXISTS cleanup_test CASCADE;
CREATE SCHEMA reporting;
CREATE SCHEMA attendance;
CREATE SCHEMA billing;
CREATE SCHEMA firefighting;
CREATE SCHEMA student;
CREATE SCHEMA tenant_school;
CREATE SCHEMA identity;
CREATE SCHEMA cleanup_test;

CREATE TABLE billing.outbox_events (
    id text PRIMARY KEY,
    school_id bigint NOT NULL
);
CREATE TABLE firefighting.outbox_events (
    id text PRIMARY KEY,
    school_id bigint NOT NULL
);

INSERT INTO billing.outbox_events(id, school_id) VALUES
    ('scale-billing-outbox', 900000000), ('outside-billing-outbox', 800000000);
INSERT INTO firefighting.outbox_events(id, school_id) VALUES
    ('scale-firefighting-outbox', 900000000), ('outside-firefighting-outbox', 800000000);

CREATE TABLE tenant_school.schools (
    id bigint PRIMARY KEY,
    short_code text NOT NULL
);
CREATE TABLE tenant_school.school_classes (
    id text PRIMARY KEY
);
CREATE TABLE tenant_school.school_sections (
    id text PRIMARY KEY,
    school_id bigint REFERENCES tenant_school.schools(id),
    school_class_id text NOT NULL REFERENCES tenant_school.school_classes(id)
);

DO $$
DECLARE
    relation_name text;
BEGIN
    FOREACH relation_name IN ARRAY ARRAY[
        'outbox_events', 'school_timetable_entries', 'school_teacher_availability',
        'school_timetable_publications', 'school_class_subjects', 'school_class_bell_map',
        'school_bell_periods', 'school_bell_schedules', 'school_rooms', 'staff_members',
        'zone_school_mappings', 'school_module_entitlements'
    ] LOOP
        EXECUTE format(
            'CREATE TABLE tenant_school.%I (id text PRIMARY KEY, school_id bigint REFERENCES tenant_school.schools(id))',
            relation_name
        );
    END LOOP;
END $$;

CREATE TABLE student.import_batches (
    id text PRIMARY KEY,
    school_id bigint,
    status text
);
CREATE TABLE student.students (
    id bigint PRIMARY KEY,
    school_id bigint NOT NULL,
    import_batch_id text
);
CREATE TABLE student.import_rows (
    id text PRIMARY KEY,
    batch_id text NOT NULL REFERENCES student.import_batches(id),
    school_id bigint
);
CREATE TABLE student.guardians (
    id text PRIMARY KEY,
    school_id bigint NOT NULL
);
CREATE TABLE student.student_guardians (
    id text PRIMARY KEY,
    school_id bigint NOT NULL,
    student_id bigint REFERENCES student.students(id),
    guardian_id text REFERENCES student.guardians(id)
);
CREATE TABLE student.student_consent_events (
    id text PRIMARY KEY,
    school_id bigint NOT NULL,
    student_id bigint REFERENCES student.students(id),
    guardian_id text REFERENCES student.guardians(id)
);
CREATE TABLE student.student_review_campaigns (
    id text PRIMARY KEY,
    school_id bigint NOT NULL
);
CREATE TABLE student.student_review_items (
    id text PRIMARY KEY,
    school_id bigint NOT NULL,
    campaign_id text REFERENCES student.student_review_campaigns(id),
    student_id bigint REFERENCES student.students(id)
);
CREATE TABLE student.student_promotion_batches (
    id text PRIMARY KEY,
    school_id bigint NOT NULL
);
CREATE TABLE student.student_promotion_batch_items (
    id text PRIMARY KEY,
    school_id bigint NOT NULL,
    batch_id text REFERENCES student.student_promotion_batches(id),
    student_id bigint
);
CREATE TABLE student.student_enrollments (
    id text PRIMARY KEY,
    school_id bigint NOT NULL,
    student_id bigint
);
CREATE TABLE student.photo_import_batches (
    id text PRIMARY KEY,
    school_id bigint NOT NULL
);
CREATE TABLE student.photo_import_rows (
    id text PRIMARY KEY,
    school_id bigint NOT NULL,
    batch_id text REFERENCES student.photo_import_batches(id),
    student_id bigint REFERENCES student.students(id)
);
CREATE TABLE student.photo_import_column_mappings (
    id text PRIMARY KEY,
    school_id bigint NOT NULL,
    batch_id text REFERENCES student.photo_import_batches(id)
);
CREATE TABLE student.photo_import_sources (
    id text PRIMARY KEY,
    school_id bigint NOT NULL,
    batch_id text REFERENCES student.photo_import_batches(id)
);
CREATE TABLE student.photo_import_drive_folders (
    id text PRIMARY KEY,
    school_id bigint NOT NULL
);

CREATE TABLE attendance.attendance_daily (
    id text PRIMARY KEY,
    school_id bigint NOT NULL
);
CREATE TABLE attendance.attendance_student_records (
    id text PRIMARY KEY,
    school_id bigint NOT NULL,
    attendance_daily_id text REFERENCES attendance.attendance_daily(id),
    student_id bigint
);
CREATE TABLE attendance.absentee_notifications (
    id text PRIMARY KEY,
    school_id bigint NOT NULL,
    student_id bigint
);

CREATE TABLE reporting.academic_events (
    id text PRIMARY KEY,
    school_id bigint NOT NULL
);
CREATE TABLE reporting.event_student_contributions (
    id text PRIMARY KEY,
    school_id bigint NOT NULL,
    event_id text REFERENCES reporting.academic_events(id),
    student_id bigint
);
CREATE TABLE reporting.dim_school (
    id bigint PRIMARY KEY
);

DO $$
DECLARE
    relation_name text;
BEGIN
    FOREACH relation_name IN ARRAY ARRAY[
        'reporting_event_inbox', 'fact_student_review_item', 'fact_payment',
        'fact_fee_assignment', 'fact_attendance_daily', 'fact_catalog_order',
        'fact_firefighting_request', 'billing_invoice_read', 'command_center_feed',
        'command_center_actions', 'dim_student', 'dim_section'
    ] LOOP
        EXECUTE format(
            'CREATE TABLE reporting.%I (id text PRIMARY KEY, school_id bigint)',
            relation_name
        );
    END LOOP;
END $$;

CREATE TABLE identity.app_users (
    id bigint PRIMARY KEY,
    email text NOT NULL
);
CREATE TABLE identity.auth_sessions (
    id text PRIMARY KEY,
    user_id bigint REFERENCES identity.app_users(id)
);
CREATE TABLE identity.user_role_assignments (
    id text PRIMARY KEY,
    user_id bigint REFERENCES identity.app_users(id),
    school_id bigint
);
CREATE TABLE identity.rbac_audit_log (
    id text PRIMARY KEY,
    school_id bigint,
    actor_user_id bigint,
    target_user_id bigint
);

INSERT INTO tenant_school.schools(id, short_code) VALUES
    (900000000, 'SCALE-000'),
    (900000001, 'SCALE-001'),
    (800000000, 'OUTSIDE');
INSERT INTO tenant_school.school_classes(id) VALUES ('scale-c-001'), ('outside-class');
INSERT INTO tenant_school.school_sections(id, school_id, school_class_id) VALUES
    ('scale-section', 900000000, 'scale-c-001'),
    ('outside-section', 800000000, 'outside-class');

DO $$
DECLARE
    relation_name text;
BEGIN
    FOREACH relation_name IN ARRAY ARRAY[
        'outbox_events', 'school_timetable_entries', 'school_teacher_availability',
        'school_timetable_publications', 'school_class_subjects', 'school_class_bell_map',
        'school_bell_periods', 'school_bell_schedules', 'school_rooms', 'staff_members',
        'zone_school_mappings', 'school_module_entitlements'
    ] LOOP
        EXECUTE format(
            'INSERT INTO tenant_school.%I(id, school_id) VALUES ($1, $2), ($3, $4)',
            relation_name
        ) USING 'scale-' || relation_name, 900000000::bigint,
                'outside-' || relation_name, 800000000::bigint;
    END LOOP;
END $$;

-- Model the cleanupRequired import-admission artifact: one completed 500-row
-- batch plus one rejected/previewed 500-row batch for the same scale school.
INSERT INTO student.import_batches(id, school_id, status) VALUES
    ('scale-done', 900000000, 'DONE'),
    ('scale-previewed', 900000000, 'PREVIEWED'),
    ('outside-done', 800000000, 'DONE');
INSERT INTO student.import_rows(id, batch_id, school_id)
SELECT 'scale-done-row-' || n, 'scale-done', 900000000
FROM generate_series(1, 500) n;
INSERT INTO student.import_rows(id, batch_id, school_id)
SELECT 'scale-preview-row-' || n, 'scale-previewed', 900000000
FROM generate_series(1, 500) n;
INSERT INTO student.import_rows(id, batch_id, school_id)
SELECT 'outside-row-' || n, 'outside-done', 800000000
FROM generate_series(1, 7) n;
INSERT INTO student.students(id, school_id, import_batch_id)
SELECT n, 900000000, 'scale-done' FROM generate_series(1, 500) n;
INSERT INTO student.students(id, school_id, import_batch_id) VALUES
    (9001, 800000000, 'outside-done');

INSERT INTO student.guardians(id, school_id) VALUES
    ('scale-guardian', 900000000), ('outside-guardian', 800000000);
INSERT INTO student.student_guardians(id, school_id, student_id, guardian_id) VALUES
    ('scale-link', 900000000, 1, 'scale-guardian'),
    ('outside-link', 800000000, 9001, 'outside-guardian');
INSERT INTO student.student_consent_events(id, school_id, student_id, guardian_id) VALUES
    ('scale-consent', 900000000, 1, 'scale-guardian'),
    ('outside-consent', 800000000, 9001, 'outside-guardian');
INSERT INTO student.student_review_campaigns(id, school_id) VALUES
    ('scale-campaign', 900000000), ('outside-campaign', 800000000);
INSERT INTO student.student_review_items(id, school_id, campaign_id, student_id) VALUES
    ('scale-review', 900000000, 'scale-campaign', 1),
    ('outside-review', 800000000, 'outside-campaign', 9001);
INSERT INTO student.student_promotion_batches(id, school_id) VALUES
    ('scale-promotion', 900000000), ('outside-promotion', 800000000);
INSERT INTO student.student_promotion_batch_items(id, school_id, batch_id, student_id) VALUES
    ('scale-promotion-item', 900000000, 'scale-promotion', 1),
    ('outside-promotion-item', 800000000, 'outside-promotion', 9001);
INSERT INTO student.student_enrollments(id, school_id, student_id) VALUES
    ('scale-enrollment', 900000000, 1),
    ('outside-enrollment', 800000000, 9001);
INSERT INTO student.photo_import_batches(id, school_id) VALUES
    ('scale-photo', 900000000), ('outside-photo', 800000000);
INSERT INTO student.photo_import_rows(id, school_id, batch_id, student_id) VALUES
    ('scale-photo-row', 900000000, 'scale-photo', 1),
    ('outside-photo-row', 800000000, 'outside-photo', 9001);
INSERT INTO student.photo_import_column_mappings(id, school_id, batch_id) VALUES
    ('scale-photo-map', 900000000, 'scale-photo'),
    ('outside-photo-map', 800000000, 'outside-photo');
INSERT INTO student.photo_import_sources(id, school_id, batch_id) VALUES
    ('scale-photo-source', 900000000, 'scale-photo'),
    ('outside-photo-source', 800000000, 'outside-photo');
INSERT INTO student.photo_import_drive_folders(id, school_id) VALUES
    ('scale-drive', 900000000), ('outside-drive', 800000000);

INSERT INTO attendance.attendance_daily(id, school_id) VALUES
    ('scale-daily', 900000000), ('outside-daily', 800000000);
INSERT INTO attendance.attendance_student_records(id, school_id, attendance_daily_id, student_id) VALUES
    ('scale-record', 900000000, 'scale-daily', 1),
    ('outside-record', 800000000, 'outside-daily', 9001);
INSERT INTO attendance.absentee_notifications(id, school_id, student_id) VALUES
    ('scale-absence', 900000000, 1),
    ('outside-absence', 800000000, 9001);

INSERT INTO reporting.academic_events(id, school_id) VALUES
    ('scale-event', 900000000), ('outside-event', 800000000);
INSERT INTO reporting.event_student_contributions(id, school_id, event_id, student_id) VALUES
    ('scale-contribution', 900000000, 'scale-event', 1),
    ('outside-contribution', 800000000, 'outside-event', 9001);
INSERT INTO reporting.dim_school(id) VALUES (900000000), (900000001), (800000000);

DO $$
DECLARE
    relation_name text;
BEGIN
    FOREACH relation_name IN ARRAY ARRAY[
        'reporting_event_inbox', 'fact_student_review_item', 'fact_payment',
        'fact_fee_assignment', 'fact_attendance_daily', 'fact_catalog_order',
        'fact_firefighting_request', 'billing_invoice_read', 'command_center_feed',
        'command_center_actions', 'dim_student', 'dim_section'
    ] LOOP
        EXECUTE format(
            'INSERT INTO reporting.%I(id, school_id) VALUES ($1, $2), ($3, $4)',
            relation_name
        ) USING 'scale-' || relation_name, 900000000::bigint,
                'outside-' || relation_name, 800000000::bigint;
    END LOOP;
END $$;

-- dim_student represents the 500 imported students, not just one sentinel.
DELETE FROM reporting.dim_student WHERE id = 'scale-dim_student';
INSERT INTO reporting.dim_student(id, school_id)
SELECT 'scale-student-' || n, 900000000 FROM generate_series(1, 500) n;

INSERT INTO identity.app_users(id, email) VALUES
    (1, 'scale-load-superadmin@custoking.local'),
    (2, 'outside@example.test');
INSERT INTO identity.auth_sessions(id, user_id) VALUES
    ('scale-session', 1), ('outside-session', 2);
INSERT INTO identity.user_role_assignments(id, user_id, school_id) VALUES
    ('scale-role', 1, 900000000), ('outside-role', 2, 800000000);
INSERT INTO identity.rbac_audit_log(id, school_id, actor_user_id, target_user_id) VALUES
    ('scale-audit', 900000000, 1, 1),
    ('outside-audit', 800000000, 2, 2);

\set base_school_id 900000000
\set school_count 2
\ir cleanup-scale-fleet.sql

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM tenant_school.schools WHERE id >= 900000000 AND id < 900000002)
       OR EXISTS (SELECT 1 FROM reporting.dim_school WHERE id >= 900000000 AND id < 900000002)
       OR EXISTS (SELECT 1 FROM student.import_batches WHERE id LIKE 'scale-%')
       OR EXISTS (SELECT 1 FROM student.import_rows WHERE id LIKE 'scale-%')
       OR EXISTS (SELECT 1 FROM billing.outbox_events WHERE school_id = 900000000)
       OR EXISTS (SELECT 1 FROM firefighting.outbox_events WHERE school_id = 900000000)
       OR EXISTS (SELECT 1 FROM tenant_school.school_classes WHERE id LIKE 'scale-c-%') THEN
        RAISE EXCEPTION 'first cleanup pass left target residue';
    END IF;

    IF (SELECT count(*) FROM student.import_rows WHERE batch_id = 'outside-done') <> 7
       OR (SELECT count(*) FROM student.students WHERE school_id = 800000000) <> 1
       OR (SELECT count(*) FROM reporting.dim_student WHERE school_id = 800000000) <> 1
       OR (SELECT count(*) FROM billing.outbox_events WHERE school_id = 800000000) <> 1
       OR (SELECT count(*) FROM firefighting.outbox_events WHERE school_id = 800000000) <> 1
       OR (SELECT count(*) FROM tenant_school.schools WHERE id = 800000000) <> 1
       OR (SELECT count(*) FROM identity.app_users WHERE id = 2) <> 1 THEN
        RAISE EXCEPTION 'first cleanup pass changed outside-scope sentinels';
    END IF;
END $$;

-- The same exact invocation must succeed and report zero deletes.
\ir cleanup-scale-fleet.sql

DO $$
BEGIN
    IF (SELECT count(*) FROM student.import_rows WHERE batch_id = 'outside-done') <> 7
       OR (SELECT count(*) FROM student.students WHERE school_id = 800000000) <> 1
       OR (SELECT count(*) FROM reporting.dim_student WHERE school_id = 800000000) <> 1
       OR (SELECT count(*) FROM billing.outbox_events WHERE school_id = 800000000) <> 1
       OR (SELECT count(*) FROM firefighting.outbox_events WHERE school_id = 800000000) <> 1
       OR (SELECT count(*) FROM tenant_school.schools WHERE id = 800000000) <> 1
       OR (SELECT count(*) FROM identity.app_users WHERE id = 2) <> 1 THEN
        RAISE EXCEPTION 'idempotent cleanup pass changed outside-scope sentinels';
    END IF;
END $$;

SELECT 'IMS_SCALE_CLEANUP_TEST|' || json_build_object(
    'postgresMajor', current_setting('server_version_num')::integer / 10000,
    'firstPassTargetRemoved', true,
    'outsideScopePreserved', true,
    'secondPassIdempotent', true,
    'modeledImportRowsRemoved', 1000,
    'modeledImportStatuses', json_build_array('DONE', 'PREVIEWED')
)::text;
