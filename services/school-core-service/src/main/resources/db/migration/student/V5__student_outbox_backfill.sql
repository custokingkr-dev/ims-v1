-- Backfills tenant_school.outbox_events with student.upserted.v1 events for every existing
-- student row, per Reporting Decoupling SP5. This migration lives in the student schema's own
-- Flyway history (not tenant_school's) because SchoolCoreFlywayConfig migrates tenant_school
-- BEFORE student (tenantSchoolFlyway -> @DependsOn studentFlyway): by the time this migration
-- runs against the full app, tenant_school.outbox_events (created in tenant_school/V7) already
-- exists. Guarded with to_regclass so isolated tests that migrate only the student schema (no
-- tenant_school schema present) skip this backfill instead of failing on a missing relation.
DO $$
BEGIN
    IF to_regclass('tenant_school.outbox_events') IS NOT NULL THEN
        INSERT INTO tenant_school.outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
        SELECT 'StudentUpserted:'||id, 'student.upserted.v1', 'Student', id::text, school_id,
               jsonb_build_object('id', id, 'schoolId', school_id, 'admissionNo', admission_no,
                                  'fullName', full_name, 'rollNo', roll_no, 'classId', class_id,
                                  'sectionId', section_id, 'parentContact', father_contact,
                                  'phone', phone, 'active', (deleted_at IS NULL))
        FROM student.students;
    END IF;
END $$;
