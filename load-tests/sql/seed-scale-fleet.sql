\set ON_ERROR_STOP on

BEGIN;
SET LOCAL app.bypass_rls = 'on';
SELECT pg_advisory_xact_lock(hashtext('ims-scale-fixture'));

CREATE TEMP TABLE scale_config AS
SELECT :base_school_id::bigint AS base_school_id,
       :school_count::integer AS school_count,
       :total_students::integer AS total_students,
       :large_school_students::integer AS large_school_students,
       :'academic_year_id'::text AS academic_year_id,
       'scale-load-superadmin@custoking.local'::text AS load_user_email;

DO $$
DECLARE
    c record;
BEGIN
    SELECT * INTO c FROM scale_config;
    IF c.school_count < 1 OR c.school_count > 500 THEN
        RAISE EXCEPTION 'school_count must be between 1 and 500';
    END IF;
    IF c.total_students < 1 OR c.total_students > 1000000 THEN
        RAISE EXCEPTION 'total_students must be between 1 and 1000000';
    END IF;
    IF c.large_school_students < 0 OR c.large_school_students > c.total_students THEN
        RAISE EXCEPTION 'large_school_students must be between 0 and total_students';
    END IF;
    IF c.school_count = 1 AND c.large_school_students <> c.total_students THEN
        RAISE EXCEPTION 'single-school fixture requires large_school_students = total_students';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM tenant_school.schools s
        WHERE s.id >= c.base_school_id
          AND s.id < c.base_school_id + 10000
          AND s.short_code NOT LIKE 'SCALE-%'
    ) THEN
        RAISE EXCEPTION 'reserved scale school id range contains non-scale data';
    END IF;
END $$;

CREATE TEMP TABLE previous_scale_schools AS
SELECT s.id
FROM tenant_school.schools s, scale_config c
WHERE s.id >= c.base_school_id
  AND s.id < c.base_school_id + 10000
  AND s.short_code LIKE 'SCALE-%';

DELETE FROM reporting.fact_attendance_daily
WHERE school_id IN (SELECT id FROM previous_scale_schools);
DELETE FROM attendance.attendance_student_records
WHERE school_id IN (SELECT id FROM previous_scale_schools);
DELETE FROM attendance.attendance_daily
WHERE school_id IN (SELECT id FROM previous_scale_schools);
DELETE FROM student.student_promotion_batch_items
WHERE school_id IN (SELECT id FROM previous_scale_schools);
DELETE FROM student.student_promotion_batches
WHERE school_id IN (SELECT id FROM previous_scale_schools);
DELETE FROM student.student_review_items
WHERE school_id IN (SELECT id FROM previous_scale_schools);
DELETE FROM student.student_review_campaigns
WHERE school_id IN (SELECT id FROM previous_scale_schools);
DELETE FROM student.student_enrollments
WHERE school_id IN (SELECT id FROM previous_scale_schools);
DELETE FROM student.students
WHERE school_id IN (SELECT id FROM previous_scale_schools);
DELETE FROM tenant_school.outbox_events
WHERE school_id IN (SELECT id FROM previous_scale_schools);
DELETE FROM tenant_school.school_module_entitlements
WHERE school_id IN (SELECT id FROM previous_scale_schools);
DELETE FROM tenant_school.school_sections
WHERE school_id IN (SELECT id FROM previous_scale_schools);
DELETE FROM tenant_school.schools
WHERE id IN (SELECT id FROM previous_scale_schools);

CREATE TEMP TABLE previous_scale_users AS
SELECT u.id
FROM identity.app_users u, scale_config c
WHERE u.email = c.load_user_email;

DELETE FROM identity.auth_sessions
WHERE user_id IN (SELECT id FROM previous_scale_users);
DELETE FROM identity.user_role_assignments
WHERE user_id IN (SELECT id FROM previous_scale_users);
DELETE FROM identity.app_users
WHERE id IN (SELECT id FROM previous_scale_users);

INSERT INTO tenant_school.academic_years(id, label, active)
SELECT academic_year_id, academic_year_id, true
FROM scale_config
ON CONFLICT (id) DO UPDATE SET label = EXCLUDED.label;

INSERT INTO tenant_school.school_classes(id, name, sort_order)
SELECT 'scale-c-' || lpad(class_no::text, 2, '0'),
       'Scale Class ' || class_no,
       1000 + class_no
FROM generate_series(1, 12) AS class_no
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    sort_order = EXCLUDED.sort_order;

CREATE TEMP TABLE scale_targets (
    school_index integer PRIMARY KEY,
    school_id bigint NOT NULL,
    student_count integer NOT NULL
);

INSERT INTO scale_targets(school_index, school_id, student_count)
SELECT school_index,
       c.base_school_id + school_index,
       CASE
           WHEN school_index = 0 THEN c.large_school_students
           ELSE ((c.total_students - c.large_school_students) / (c.school_count - 1))
                + CASE WHEN school_index <= ((c.total_students - c.large_school_students) % (c.school_count - 1))
                       THEN 1 ELSE 0 END
       END
FROM scale_config c
CROSS JOIN LATERAL generate_series(0, c.school_count - 1) AS school_index
WHERE c.school_count > 1
UNION ALL
SELECT 0, c.base_school_id, c.total_students
FROM scale_config c
WHERE c.school_count = 1;

INSERT INTO tenant_school.schools(
    id, name, short_code, city, state, contact_email, active,
    configured_class_count, configured_section_count, created_at)
SELECT t.school_id,
       'Scale Test School ' || lpad((t.school_index + 1)::text, 3, '0'),
       'SCALE-' || lpad((t.school_index + 1)::text, 3, '0'),
       'Synthetic City',
       'Synthetic State',
       'scale+' || t.school_index || '@invalid.local',
       true,
       12,
       ceil(t.student_count / 40.0)::integer,
       now()
FROM scale_targets t;

INSERT INTO tenant_school.school_module_entitlements(
    school_id, module_code, enabled, plan, notes, created_at, updated_at)
SELECT t.school_id, module_code, true, 'SCALE_TEST',
       'Synthetic capacity fixture - safe to delete', now(), now()
FROM scale_targets t
CROSS JOIN (VALUES ('STUDENTS'), ('ATTENDANCE'), ('REPORTS')) AS modules(module_code);

INSERT INTO tenant_school.school_sections(
    id, name, teacher_name, active, school_class_id, school_id)
SELECT 'scale-' || t.school_id || '-s-' || lpad(section_no::text, 4, '0'),
       'Scale ' || lpad(section_no::text, 4, '0'),
       'Synthetic Teacher',
       true,
       'scale-c-' || lpad((((section_no - 1) % 12) + 1)::text, 2, '0'),
       t.school_id
FROM scale_targets t
CROSS JOIN LATERAL generate_series(1, ceil(t.student_count / 40.0)::integer) AS section_no;

INSERT INTO student.students(
    admission_no, roll_no, full_name, dob, gender, father_name, father_contact,
    city, state, fee_status, attendance_percent, created_at, updated_at,
    school_id, class_id, section_id, academic_year_id, version)
SELECT 'SCALE-' || t.school_index || '-' || lpad(student_no::text, 7, '0'),
       (((student_no - 1) % 40) + 1)::text,
       'Synthetic Student ' || t.school_index || '-' || student_no,
       date '2010-01-01' + ((student_no - 1) % 2500),
       CASE WHEN student_no % 2 = 0 THEN 'FEMALE' ELSE 'MALE' END,
       'Synthetic Parent ' || student_no,
       '9000000000',
       'Synthetic City',
       'Synthetic State',
       CASE WHEN student_no % 10 = 0 THEN 'DUE' ELSE 'PAID' END,
       75 + (student_no % 26),
       now(),
       now(),
       t.school_id,
       'scale-c-' || lpad((((ceil(student_no / 40.0)::integer - 1) % 12) + 1)::text, 2, '0'),
       'scale-' || t.school_id || '-s-' || lpad((ceil(student_no / 40.0)::integer)::text, 4, '0'),
       c.academic_year_id,
       0
FROM scale_targets t
CROSS JOIN scale_config c
CROSS JOIN LATERAL generate_series(1, t.student_count) AS student_no;

DO $$
DECLARE
    load_user_id bigint;
    super_role_id bigint;
    load_user_email text;
BEGIN
    SELECT c.load_user_email INTO load_user_email FROM scale_config c;
    SELECT r.id INTO super_role_id
    FROM identity.roles r
    WHERE upper(r.name) = 'SUPERADMIN'
    LIMIT 1;

    IF super_role_id IS NULL THEN
        RAISE EXCEPTION 'SUPERADMIN role is missing';
    END IF;

    INSERT INTO identity.app_users(
        full_name, email, password_hash, role, branch_id, branch_name,
        created_at, deleted_at, deleted_by)
    VALUES (
        'Synthetic Scale Load Superadmin', load_user_email,
        '$2a$10$J7RjqxrkPBk31.tolxpMkO0LHevKKGCNi6AsSPAsGeHtnyvHfmXlG',
        'SUPERADMIN', NULL, NULL, now(), NULL, NULL)
    RETURNING id INTO load_user_id;

    INSERT INTO identity.user_role_assignments(
        user_id, role_id, school_id, zone_id, assigned_by, assigned_at,
        active, valid_from)
    VALUES (
        load_user_id, super_role_id, NULL, NULL, load_user_id, now(),
        true, now());
END $$;

ANALYZE tenant_school.schools;
ANALYZE tenant_school.school_sections;
ANALYZE student.students;

SELECT 'IMS_SCALE_RESULT|' || json_build_object(
    'schools', (SELECT count(*) FROM scale_targets),
    'students', (SELECT sum(student_count) FROM scale_targets),
    'largestSchoolStudents', (SELECT max(student_count) FROM scale_targets),
    'sections', (SELECT sum(ceil(student_count / 40.0)::integer) FROM scale_targets),
    'baseSchoolId', (SELECT base_school_id FROM scale_config),
    'academicYearId', (SELECT academic_year_id FROM scale_config),
    'loadUserEmail', (SELECT load_user_email FROM scale_config)
)::text;

COMMIT;
