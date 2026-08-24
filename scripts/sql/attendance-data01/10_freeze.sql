\set ON_ERROR_STOP on

BEGIN;
SET LOCAL lock_timeout = '10s';

DO $data01$
BEGIN
    IF current_setting('app.data01_maintenance_approved', true) IS DISTINCT FROM 'DATA-01' THEN
        RAISE EXCEPTION 'Set app.data01_maintenance_approved=DATA-01 only after the service write drain is approved';
    END IF;
    IF to_regclass('attendance.attendance_student_records_data01_control') IS NOT NULL THEN
        RAISE EXCEPTION '10_freeze.sql is single-shot; DATA-01 control state already exists';
    END IF;
END
$data01$;

CREATE TABLE attendance.attendance_student_records_data01_control (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    phase text NOT NULL,
    source_rows_at_freeze bigint NOT NULL,
    source_checksum_at_freeze numeric,
    -- Conservative write evidence: INSERT .. ON CONFLICT DO UPDATE can fire both event arms.
    post_cutover_write_statements bigint NOT NULL DEFAULT 0,
    frozen_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    cutover_at timestamptz,
    finalized_at timestamptz
);

CREATE FUNCTION attendance.data01_reject_source_write()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $function$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '55000',
        MESSAGE = 'attendance writes are frozen for the DATA-01 maintenance window';
END
$function$;

LOCK TABLE attendance.attendance_student_records IN SHARE ROW EXCLUSIVE MODE;

CREATE TRIGGER data01_source_write_freeze
    BEFORE INSERT OR UPDATE OR DELETE ON attendance.attendance_student_records
    FOR EACH STATEMENT EXECUTE FUNCTION attendance.data01_reject_source_write();

INSERT INTO attendance.attendance_student_records_data01_control(
    singleton, phase, source_rows_at_freeze, source_checksum_at_freeze
)
SELECT true,
       'FROZEN',
       count(*),
       sum(hashtextextended(concat_ws('|', id, attendance_daily_id, student_id,
           school_id, attendance_date, academic_year_id, class_id, section_id,
           status, coalesce(remarks, ''), coalesce(recorded_by::text, ''),
           coalesce(recorded_at::text, ''), coalesce(updated_by::text, ''),
           coalesce(updated_at::text, '')), 0)::numeric)
FROM attendance.attendance_student_records;

COMMIT;

SELECT 'IMS_DATA01_FREEZE|' || row_to_json(c)::text
FROM attendance.attendance_student_records_data01_control c;
