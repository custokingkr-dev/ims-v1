\set ON_ERROR_STOP on

BEGIN;
SET LOCAL statement_timeout = '0';
SET LOCAL lock_timeout = '10s';

DO $data01$
DECLARE
    phase_now text;
BEGIN
    IF current_setting('app.data01_maintenance_approved', true) IS DISTINCT FROM 'DATA-01' THEN
        RAISE EXCEPTION 'DATA-01 maintenance approval marker is absent';
    END IF;
    SELECT phase INTO phase_now
      FROM attendance.attendance_student_records_data01_control WHERE singleton FOR UPDATE;
    IF phase_now IS DISTINCT FROM 'VERIFIED' THEN
        RAISE EXCEPTION '40_cutover.sql requires VERIFIED phase, found %', phase_now;
    END IF;
END
$data01$;

LOCK TABLE attendance.attendance_student_records IN ACCESS EXCLUSIVE MODE;
LOCK TABLE attendance.attendance_student_records_data01 IN ACCESS EXCLUSIVE MODE;

DO $data01$
DECLARE
    source_rows bigint;
    target_rows bigint;
    source_checksum numeric;
    target_checksum numeric;
BEGIN
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
    IF source_rows <> target_rows OR source_checksum IS DISTINCT FROM target_checksum THEN
        RAISE EXCEPTION 'Cutover parity recheck failed';
    END IF;
END
$data01$;

ALTER TABLE attendance.attendance_student_records
    RENAME TO attendance_student_records_data01_unpartitioned;
ALTER TABLE attendance.attendance_student_records_data01
    RENAME TO attendance_student_records;

CREATE TRIGGER attendance_student_record_identity_insert
    BEFORE INSERT ON attendance.attendance_student_records
    FOR EACH ROW EXECUTE FUNCTION attendance.enforce_student_record_identity_insert();
CREATE TRIGGER attendance_student_record_identity_update
    BEFORE UPDATE OF id, attendance_daily_id, student_id, attendance_date, academic_year_id
    ON attendance.attendance_student_records
    FOR EACH ROW EXECUTE FUNCTION attendance.enforce_student_record_identity_update();
CREATE TRIGGER attendance_student_record_identity_delete
    AFTER DELETE ON attendance.attendance_student_records
    FOR EACH ROW EXECUTE FUNCTION attendance.enforce_student_record_identity_delete();

CREATE FUNCTION attendance.data01_note_post_cutover_write()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
    UPDATE attendance.attendance_student_records_data01_control
       SET post_cutover_write_statements = post_cutover_write_statements + 1
     WHERE singleton;
    RETURN NULL;
END
$function$;
REVOKE ALL ON FUNCTION attendance.data01_note_post_cutover_write() FROM PUBLIC;

CREATE TRIGGER data01_post_cutover_write_guard
    AFTER INSERT OR UPDATE OR DELETE ON attendance.attendance_student_records
    FOR EACH STATEMENT EXECUTE FUNCTION attendance.data01_note_post_cutover_write();

UPDATE attendance.attendance_student_records_data01_control
   SET phase = 'CUTOVER', cutover_at = clock_timestamp()
 WHERE singleton;

COMMIT;

SELECT 'IMS_DATA01_CUTOVER|' || json_build_object(
    'phase', phase,
    'activeTableKind', (SELECT relkind FROM pg_class
                         WHERE oid = 'attendance.attendance_student_records'::regclass),
    'rows', (SELECT count(*) FROM attendance.attendance_student_records),
    'postCutoverWriteStatements', post_cutover_write_statements,
    'rollbackPermitted', post_cutover_write_statements = 0
)::text
FROM attendance.attendance_student_records_data01_control
WHERE singleton;
