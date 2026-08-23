\set ON_ERROR_STOP on

-- Destructive finalization is separately authorized after the observation/backup checkpoint.
-- Required marker: app.data01_finalize_approved=DROP-LEGACY-DATA-01
BEGIN;
SET LOCAL lock_timeout = '10s';

DO $data01$
DECLARE phase_now text;
BEGIN
    IF current_setting('app.data01_maintenance_approved', true) IS DISTINCT FROM 'DATA-01'
       OR current_setting('app.data01_finalize_approved', true) IS DISTINCT FROM 'DROP-LEGACY-DATA-01' THEN
        RAISE EXCEPTION 'Both DATA-01 maintenance and destructive-finalize markers are required';
    END IF;
    SELECT phase INTO phase_now
      FROM attendance.attendance_student_records_data01_control WHERE singleton FOR UPDATE;
    IF phase_now IS DISTINCT FROM 'CUTOVER' THEN
        RAISE EXCEPTION '50_finalize.sql requires CUTOVER phase, found %', phase_now;
    END IF;
END
$data01$;

DROP TRIGGER data01_post_cutover_write_guard ON attendance.attendance_student_records;
DROP FUNCTION attendance.data01_note_post_cutover_write();
DROP TABLE attendance.attendance_student_records_data01_unpartitioned;
DROP FUNCTION attendance.data01_reject_source_write();

-- Restore the Flyway V1/V7 constraint and index names after the legacy objects release them.
ALTER TABLE attendance.attendance_student_records
    RENAME CONSTRAINT attendance_student_records_data01_pk TO attendance_student_records_pkey;
ALTER TABLE attendance.attendance_student_records
    RENAME CONSTRAINT attendance_student_records_data01_daily_fk TO fk_attendance_student_records_daily;
ALTER TABLE attendance.attendance_student_records
    RENAME CONSTRAINT attendance_student_records_data01_daily_student_uk TO uk_attendance_student_daily_student;
ALTER TABLE attendance.attendance_student_records
    RENAME CONSTRAINT attendance_student_records_data01_student_date_year_uk TO uk_attendance_student_date_year;
ALTER TABLE attendance.attendance_student_records
    RENAME CONSTRAINT attendance_student_records_data01_status_check TO attendance_student_records_status_check;

ALTER INDEX attendance.idx_asr_data01_school_date RENAME TO idx_attendance_student_records_school_date;
ALTER INDEX attendance.idx_asr_data01_section_date RENAME TO idx_attendance_student_records_section_date;
ALTER INDEX attendance.idx_asr_data01_daily RENAME TO idx_attendance_student_records_daily;
ALTER INDEX attendance.idx_asr_data01_student_date RENAME TO idx_attendance_student_records_student_date;
ALTER INDEX attendance.idx_asr_data01_academic_year RENAME TO idx_attendance_student_records_academic_year;

UPDATE attendance.attendance_student_records_data01_control
   SET phase = 'FINALIZED', finalized_at = clock_timestamp()
 WHERE singleton;

COMMIT;

SELECT 'IMS_DATA01_FINALIZE|' || json_build_object(
    'phase', phase,
    'rows', (SELECT count(*) FROM attendance.attendance_student_records),
    'legacyRemoved', to_regclass(
        'attendance.attendance_student_records_data01_unpartitioned') IS NULL,
    'rollbackPermitted', false
)::text
FROM attendance.attendance_student_records_data01_control
WHERE singleton;
