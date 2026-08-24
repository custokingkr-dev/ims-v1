\set ON_ERROR_STOP on

-- This rollback is intentionally fail-closed. It is valid only before the first successful write
-- against the partitioned table. After writes resume, use the approved recovery procedure instead.
BEGIN;
SET LOCAL statement_timeout = '0';
SET LOCAL lock_timeout = '10s';

DO $data01$
DECLARE
    state attendance.attendance_student_records_data01_control%ROWTYPE;
BEGIN
    IF current_setting('app.data01_maintenance_approved', true) IS DISTINCT FROM 'DATA-01' THEN
        RAISE EXCEPTION 'DATA-01 maintenance approval marker is absent';
    END IF;
    SELECT * INTO state
      FROM attendance.attendance_student_records_data01_control
     WHERE singleton FOR UPDATE;
    IF state.phase IS DISTINCT FROM 'CUTOVER' THEN
        RAISE EXCEPTION 'Rollback requires CUTOVER phase, found %', state.phase;
    END IF;
    IF state.post_cutover_write_statements <> 0 THEN
        RAISE EXCEPTION 'Rollback refused: % post-cutover write trigger event(s) committed',
            state.post_cutover_write_statements;
    END IF;
    IF to_regclass('attendance.attendance_student_records_data01_partitioned_failed') IS NOT NULL THEN
        RAISE EXCEPTION 'Rollback target name already exists';
    END IF;
END
$data01$;

LOCK TABLE attendance.attendance_student_records IN ACCESS EXCLUSIVE MODE;
LOCK TABLE attendance.attendance_student_records_data01_unpartitioned IN ACCESS EXCLUSIVE MODE;

DO $data01$
DECLARE
    active_rows bigint;
    legacy_rows bigint;
    active_checksum numeric;
    legacy_checksum numeric;
    writes bigint;
BEGIN
    SELECT post_cutover_write_statements INTO writes
      FROM attendance.attendance_student_records_data01_control WHERE singleton;
    IF writes <> 0 THEN
        RAISE EXCEPTION 'Rollback refused after lock: % post-cutover writes committed', writes;
    END IF;
    SELECT count(*),
           sum(hashtextextended(concat_ws('|', id, attendance_daily_id, student_id,
               school_id, attendance_date, academic_year_id, class_id, section_id,
               status, coalesce(remarks, ''), coalesce(recorded_by::text, ''),
               coalesce(recorded_at::text, ''), coalesce(updated_by::text, ''),
               coalesce(updated_at::text, '')), 0)::numeric)
      INTO active_rows, active_checksum
      FROM attendance.attendance_student_records;
    SELECT count(*),
           sum(hashtextextended(concat_ws('|', id, attendance_daily_id, student_id,
               school_id, attendance_date, academic_year_id, class_id, section_id,
               status, coalesce(remarks, ''), coalesce(recorded_by::text, ''),
               coalesce(recorded_at::text, ''), coalesce(updated_by::text, ''),
               coalesce(updated_at::text, '')), 0)::numeric)
      INTO legacy_rows, legacy_checksum
      FROM attendance.attendance_student_records_data01_unpartitioned;
    IF active_rows <> legacy_rows OR active_checksum IS DISTINCT FROM legacy_checksum THEN
        RAISE EXCEPTION 'Rollback parity check failed';
    END IF;
END
$data01$;

DROP TRIGGER attendance_student_record_identity_insert ON attendance.attendance_student_records;
DROP TRIGGER attendance_student_record_identity_update ON attendance.attendance_student_records;
DROP TRIGGER attendance_student_record_identity_delete ON attendance.attendance_student_records;
DROP TRIGGER data01_post_cutover_write_guard ON attendance.attendance_student_records;

ALTER TABLE attendance.attendance_student_records
    RENAME TO attendance_student_records_data01_partitioned_failed;
ALTER TABLE attendance.attendance_student_records_data01_unpartitioned
    RENAME TO attendance_student_records;

DROP TRIGGER data01_source_write_freeze ON attendance.attendance_student_records;
DROP FUNCTION attendance.data01_reject_source_write();
DROP FUNCTION attendance.data01_note_post_cutover_write();

UPDATE attendance.attendance_student_records_data01_control
   SET phase = 'ROLLED_BACK'
 WHERE singleton;

COMMIT;

SELECT 'IMS_DATA01_ROLLBACK|' || json_build_object(
    'phase', phase,
    'activeTableKind', (SELECT relkind FROM pg_class
                         WHERE oid = 'attendance.attendance_student_records'::regclass),
    'rows', (SELECT count(*) FROM attendance.attendance_student_records)
)::text
FROM attendance.attendance_student_records_data01_control
WHERE singleton;
