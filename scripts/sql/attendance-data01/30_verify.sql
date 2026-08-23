\set ON_ERROR_STOP on

BEGIN;
SET LOCAL statement_timeout = '0';

DO $data01$
DECLARE
    phase_now text;
    source_rows bigint;
    target_rows bigint;
    registry_rows bigint;
    source_checksum numeric;
    target_checksum numeric;
BEGIN
    IF current_setting('app.data01_maintenance_approved', true) IS DISTINCT FROM 'DATA-01' THEN
        RAISE EXCEPTION 'DATA-01 maintenance approval marker is absent';
    END IF;
    SELECT phase INTO phase_now
      FROM attendance.attendance_student_records_data01_control WHERE singleton;
    IF phase_now IS DISTINCT FROM 'BUILT' THEN
        RAISE EXCEPTION '30_verify.sql requires BUILT phase, found %', phase_now;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_trigger
                    WHERE tgrelid = 'attendance.attendance_student_records'::regclass
                      AND tgname = 'data01_source_write_freeze'
                      AND NOT tgisinternal) THEN
        RAISE EXCEPTION 'Source write freeze is not active';
    END IF;

    SELECT count(*),
           sum(hashtextextended(concat_ws('|', id, attendance_daily_id, student_id,
               school_id, attendance_date, academic_year_id, class_id, section_id,
               status, coalesce(remarks, ''), coalesce(recorded_by::text, ''),
               coalesce(recorded_at::text, ''), coalesce(updated_by::text, ''),
               coalesce(updated_at::text, '')), 0)::numeric)
      INTO source_rows, source_checksum
      FROM attendance.attendance_student_records;

    SELECT count(*),
           sum(hashtextextended(concat_ws('|', id, attendance_daily_id, student_id,
               school_id, attendance_date, academic_year_id, class_id, section_id,
               status, coalesce(remarks, ''), coalesce(recorded_by::text, ''),
               coalesce(recorded_at::text, ''), coalesce(updated_by::text, ''),
               coalesce(updated_at::text, '')), 0)::numeric)
      INTO target_rows, target_checksum
      FROM attendance.attendance_student_records_data01;

    SELECT count(*) INTO registry_rows
      FROM attendance.attendance_student_record_identity;
    IF source_rows <> target_rows OR source_rows <> registry_rows
       OR source_checksum IS DISTINCT FROM target_checksum THEN
        RAISE EXCEPTION 'DATA-01 parity failed: source %, target %, registry %, checksum match %',
            source_rows, target_rows, registry_rows, source_checksum IS NOT DISTINCT FROM target_checksum;
    END IF;

    IF EXISTS (
        SELECT 1
          FROM attendance.attendance_student_records s
          FULL JOIN attendance.attendance_student_record_identity r USING (id)
         WHERE s.id IS NULL OR r.id IS NULL
            OR s.attendance_daily_id IS DISTINCT FROM r.attendance_daily_id
            OR s.student_id IS DISTINCT FROM r.student_id
            OR s.attendance_date IS DISTINCT FROM r.attendance_date
            OR s.academic_year_id IS DISTINCT FROM r.academic_year_id
    ) THEN
        RAISE EXCEPTION 'DATA-01 identity registry differs from the frozen source';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_constraint
                WHERE conrelid = 'attendance.attendance_student_records_data01'::regclass
                  AND NOT convalidated) THEN
        RAISE EXCEPTION 'DATA-01 target contains unvalidated constraints';
    END IF;
    IF NOT (SELECT relispartitioned AND relrowsecurity
              FROM (SELECT c.relkind = 'p' AS relispartitioned, c.relrowsecurity
                      FROM pg_class c
                     WHERE c.oid = 'attendance.attendance_student_records_data01'::regclass) q) THEN
        RAISE EXCEPTION 'DATA-01 target must be partitioned with RLS enabled';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies
                    WHERE schemaname = 'attendance'
                      AND tablename = 'attendance_student_records_data01'
                      AND policyname = 'tenant_isolation') THEN
        RAISE EXCEPTION 'DATA-01 target tenant_isolation policy is absent';
    END IF;
    IF to_regclass('attendance.attendance_student_records_default') IS NULL THEN
        RAISE EXCEPTION 'DATA-01 default partition is absent';
    END IF;

    UPDATE attendance.attendance_student_records_data01_control
       SET phase = 'VERIFIED'
     WHERE singleton;
END
$data01$;

COMMIT;

SELECT 'IMS_DATA01_VERIFY|' || json_build_object(
    'phase', phase,
    'sourceRows', (SELECT count(*) FROM attendance.attendance_student_records),
    'targetRows', (SELECT count(*) FROM attendance.attendance_student_records_data01),
    'registryRows', (SELECT count(*) FROM attendance.attendance_student_record_identity),
    'validatedConstraints', NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'attendance.attendance_student_records_data01'::regclass
           AND NOT convalidated),
    'rlsEnabled', (SELECT relrowsecurity FROM pg_class
                    WHERE oid = 'attendance.attendance_student_records_data01'::regclass),
    'defaultPartitionPresent',
        to_regclass('attendance.attendance_student_records_default') IS NOT NULL
)::text
FROM attendance.attendance_student_records_data01_control
WHERE singleton;
