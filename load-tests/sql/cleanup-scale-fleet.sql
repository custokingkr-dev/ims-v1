\set ON_ERROR_STOP on

BEGIN;
SET LOCAL app.bypass_rls = 'on';
SELECT pg_advisory_xact_lock(hashtext('ims-scale-fixture'));

CREATE TEMP TABLE scale_config AS
SELECT :base_school_id::bigint AS base_school_id,
       'scale-load-superadmin@custoking.local'::text AS load_user_email;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM tenant_school.schools s, scale_config c
        WHERE s.id >= c.base_school_id
          AND s.id < c.base_school_id + 10000
          AND short_code NOT LIKE 'SCALE-%'
    ) THEN
        RAISE EXCEPTION 'reserved scale school id range contains non-scale data';
    END IF;
END $$;

CREATE TEMP TABLE scale_school_ids AS
SELECT id
FROM tenant_school.schools s, scale_config c
WHERE s.id >= c.base_school_id
  AND s.id < c.base_school_id + 10000
  AND short_code LIKE 'SCALE-%';

CREATE TEMP TABLE scale_user_ids AS
SELECT u.id
FROM identity.app_users u, scale_config c
WHERE u.email = c.load_user_email;

DELETE FROM identity.auth_sessions WHERE user_id IN (SELECT id FROM scale_user_ids);
DELETE FROM identity.user_role_assignments WHERE user_id IN (SELECT id FROM scale_user_ids);
DELETE FROM identity.app_users WHERE id IN (SELECT id FROM scale_user_ids);

DELETE FROM reporting.reporting_event_inbox WHERE school_id IN (SELECT id FROM scale_school_ids);
DELETE FROM reporting.fact_attendance_daily WHERE school_id IN (SELECT id FROM scale_school_ids);
DELETE FROM attendance.attendance_student_records WHERE school_id IN (SELECT id FROM scale_school_ids);
DELETE FROM attendance.attendance_daily WHERE school_id IN (SELECT id FROM scale_school_ids);
DELETE FROM student.student_promotion_batch_items WHERE school_id IN (SELECT id FROM scale_school_ids);
DELETE FROM student.student_promotion_batches WHERE school_id IN (SELECT id FROM scale_school_ids);
DELETE FROM student.student_review_items WHERE school_id IN (SELECT id FROM scale_school_ids);
DELETE FROM student.student_review_campaigns WHERE school_id IN (SELECT id FROM scale_school_ids);
DELETE FROM student.student_enrollments WHERE school_id IN (SELECT id FROM scale_school_ids);
DELETE FROM student.students WHERE school_id IN (SELECT id FROM scale_school_ids);
DELETE FROM tenant_school.outbox_events WHERE school_id IN (SELECT id FROM scale_school_ids);
DELETE FROM tenant_school.school_module_entitlements WHERE school_id IN (SELECT id FROM scale_school_ids);
DELETE FROM tenant_school.school_sections WHERE school_id IN (SELECT id FROM scale_school_ids);
DELETE FROM tenant_school.schools WHERE id IN (SELECT id FROM scale_school_ids);
DELETE FROM tenant_school.school_classes c
WHERE c.id LIKE 'scale-c-%'
  AND NOT EXISTS (SELECT 1 FROM tenant_school.school_sections s WHERE s.school_class_id = c.id);

SELECT 'IMS_SCALE_CLEANUP|cleaned';
COMMIT;
