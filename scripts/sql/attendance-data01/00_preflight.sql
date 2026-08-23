\set ON_ERROR_STOP on

-- DATA-01 is an offline, operator-controlled migration. This preflight is read-only.
-- Mutating phases additionally require:
--   PGOPTIONS="-c app.data01_maintenance_approved=DATA-01"

DO $data01$
DECLARE
    source_owner name;
    invalid_constraint_count integer;
BEGIN
    IF current_setting('server_version_num')::integer < 160000 THEN
        RAISE EXCEPTION 'DATA-01 requires PostgreSQL 16 or newer';
    END IF;

    SELECT pg_get_userbyid(c.relowner)
      INTO source_owner
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'attendance'
       AND c.relname = 'attendance_student_records'
       AND c.relkind = 'r';
    IF source_owner IS NULL THEN
        RAISE EXCEPTION 'attendance.attendance_student_records must exist and be unpartitioned';
    END IF;
    IF source_owner <> current_user THEN
        RAISE EXCEPTION 'DATA-01 must run as table owner %, current user is %', source_owner, current_user;
    END IF;

    IF to_regclass('attendance.attendance_student_records_data01') IS NOT NULL
       OR to_regclass('attendance.attendance_student_records_data01_unpartitioned') IS NOT NULL
       OR to_regclass('attendance.attendance_student_record_identity') IS NOT NULL THEN
        RAISE EXCEPTION 'DATA-01 staging/legacy objects already exist; inspect control state before proceeding';
    END IF;

    IF EXISTS (SELECT 1 FROM attendance.attendance_student_records WHERE attendance_date IS NULL) THEN
        RAISE EXCEPTION 'Partition key attendance_date contains NULL values';
    END IF;

    SELECT count(*) INTO invalid_constraint_count
      FROM pg_constraint
     WHERE conrelid IN ('attendance.attendance_daily'::regclass,
                        'attendance.attendance_student_records'::regclass)
       AND NOT convalidated;
    IF invalid_constraint_count <> 0 THEN
        RAISE EXCEPTION 'Source attendance constraints include % unvalidated constraint(s)', invalid_constraint_count;
    END IF;

    IF NOT (SELECT relrowsecurity FROM pg_class
             WHERE oid = 'attendance.attendance_student_records'::regclass) THEN
        RAISE EXCEPTION 'Source attendance_student_records must have RLS enabled';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE schemaname = 'attendance'
           AND tablename = 'attendance_student_records'
           AND policyname = 'tenant_isolation'
    ) THEN
        RAISE EXCEPTION 'Source tenant_isolation policy is missing';
    END IF;

    IF (SELECT count(*) FROM pg_constraint
         WHERE conrelid = 'attendance.attendance_student_records'::regclass
           AND conname IN ('attendance_student_records_pkey',
                           'fk_attendance_student_records_daily',
                           'uk_attendance_student_daily_student',
                           'uk_attendance_student_date_year',
                           'attendance_student_records_status_check')) <> 5 THEN
        RAISE EXCEPTION 'Source constraint contract differs from attendance Flyway V1-V9';
    END IF;
END
$data01$;

SELECT 'IMS_DATA01_PREFLIGHT|' || json_build_object(
    'database', current_database(),
    'serverVersion', current_setting('server_version'),
    'sourceRows', count(*),
    'minimumAttendanceDate', min(attendance_date),
    'maximumAttendanceDate', max(attendance_date),
    'sourceHeapBytes', pg_relation_size('attendance.attendance_student_records'),
    'sourceIndexBytes', pg_indexes_size('attendance.attendance_student_records'),
    'sourceTotalBytes', pg_total_relation_size('attendance.attendance_student_records'),
    'databaseBytes', pg_database_size(current_database()),
    'minimumEstimatedAdditionalHeadroomBytesExcludingWal',
        ceil(pg_total_relation_size('attendance.attendance_student_records') * 2.25),
    'rlsEnabled', (SELECT relrowsecurity FROM pg_class
                    WHERE oid = 'attendance.attendance_student_records'::regclass),
    'maintenanceApprovalPresent',
        current_setting('app.data01_maintenance_approved', true) = 'DATA-01',
    'deploymentMode', 'offline-maintenance-window'
)::text
FROM attendance.attendance_student_records;
