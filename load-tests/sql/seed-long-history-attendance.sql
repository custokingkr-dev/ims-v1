\set ON_ERROR_STOP on

BEGIN;
SET LOCAL app.bypass_rls = 'on';
SET LOCAL statement_timeout = '55min';
SELECT pg_advisory_xact_lock(hashtext('ims-scale-fixture'));

CREATE TEMP TABLE long_history_config ON COMMIT DROP AS
SELECT :base_school_id::bigint AS base_school_id;

DO $$
DECLARE
    configured_base_school_id bigint;
    synthetic_school_count bigint;
    large_school_student_count bigint;
BEGIN
    SELECT base_school_id INTO configured_base_school_id FROM long_history_config;
    SELECT count(*) INTO synthetic_school_count
    FROM tenant_school.schools
    WHERE id = configured_base_school_id
      AND short_code LIKE 'SCALE-%';
    IF synthetic_school_count <> 1 THEN
        RAISE EXCEPTION 'Reserved synthetic base school % is missing', configured_base_school_id;
    END IF;

    SELECT count(*) INTO large_school_student_count
    FROM student.students
    WHERE school_id = configured_base_school_id;
    IF large_school_student_count <> 10000 THEN
        RAISE EXCEPTION 'Long-history seed requires exactly 10,000 students at school %; found %',
            configured_base_school_id, large_school_student_count;
    END IF;
END $$;

-- This deletes only the reserved synthetic school's prior attendance. It does not touch real schools.
DELETE FROM reporting.fact_attendance_daily
WHERE school_id = :base_school_id::bigint;
DELETE FROM attendance.attendance_student_records
WHERE school_id = :base_school_id::bigint;
DELETE FROM attendance.attendance_daily
WHERE school_id = :base_school_id::bigint;

CREATE TEMP TABLE scale_section_counts ON COMMIT DROP AS
SELECT section_id,
       min(class_id) AS class_id,
       count(*)::integer AS total_enrolled,
       count(*) FILTER (WHERE student_id % 10 <> 0)::integer AS present_count,
       count(*) FILTER (WHERE student_id % 10 = 0)::integer AS absent_count
FROM (
    SELECT id AS student_id, section_id, class_id
    FROM student.students
    WHERE school_id = :base_school_id::bigint
      AND deleted_at IS NULL
) students
GROUP BY section_id;

INSERT INTO attendance.attendance_daily(
    id, attendance_date, total_enrolled, present_count, absent_count,
    late_count, leave_count, recorded_by, recorded_at, updated_by, updated_at,
    locked, school_class_id, section_id, academic_year_id, school_id)
SELECT 'scalehist-' || sections.section_id || '-' || to_char(days.attendance_date, 'YYYYMMDD'),
       days.attendance_date,
       sections.total_enrolled,
       sections.present_count,
       sections.absent_count,
       0,
       0,
       NULL,
       now(),
       NULL,
       now(),
       false,
       sections.class_id,
       sections.section_id,
       :'academic_year_id',
       :base_school_id::bigint
FROM scale_section_counts sections
CROSS JOIN LATERAL (
    SELECT current_date - day_offset AS attendance_date
    FROM generate_series(0, 729) AS day_offset
) days;

INSERT INTO attendance.attendance_student_records(
    id, attendance_daily_id, student_id, school_id, attendance_date,
    academic_year_id, class_id, section_id, status, remarks,
    recorded_by, recorded_at, updated_by, updated_at)
SELECT 'scalehist-' || students.id || '-' || to_char(days.attendance_date, 'YYYYMMDD'),
       'scalehist-' || students.section_id || '-' || to_char(days.attendance_date, 'YYYYMMDD'),
       students.id,
       students.school_id,
       days.attendance_date,
       :'academic_year_id',
       students.class_id,
       students.section_id,
       CASE WHEN students.id % 10 = 0 THEN 'ABSENT' ELSE 'PRESENT' END,
       'synthetic two-year certification history',
       NULL,
       now(),
       NULL,
       now()
FROM student.students students
CROSS JOIN LATERAL (
    SELECT current_date - day_offset AS attendance_date
    FROM generate_series(0, 729) AS day_offset
) days
WHERE students.school_id = :base_school_id::bigint
  AND students.deleted_at IS NULL;

INSERT INTO reporting.fact_attendance_daily(
    id, school_id, attendance_date, class_id, section_id, academic_year_id,
    present_count, absent_count, late_count, leave_count, total_enrolled, updated_at)
SELECT id, school_id, attendance_date, school_class_id, section_id, academic_year_id,
       present_count, absent_count, late_count, leave_count, total_enrolled, now()
FROM attendance.attendance_daily
WHERE school_id = :base_school_id::bigint;

ANALYZE attendance.attendance_daily;
ANALYZE attendance.attendance_student_records;
ANALYZE reporting.fact_attendance_daily;

SELECT 'IMS_LONG_HISTORY_RESULT|' || json_build_object(
    'schoolId', :base_school_id::bigint,
    'students', (SELECT count(*) FROM student.students WHERE school_id = :base_school_id::bigint),
    'attendanceDays', (SELECT count(DISTINCT attendance_date)
                       FROM attendance.attendance_student_records
                       WHERE school_id = :base_school_id::bigint),
    'attendanceRows', (SELECT count(*) FROM attendance.attendance_student_records
                       WHERE school_id = :base_school_id::bigint),
    'attendanceDailyRows', (SELECT count(*) FROM attendance.attendance_daily
                            WHERE school_id = :base_school_id::bigint),
    'reportingAttendanceRows', (SELECT count(*) FROM reporting.fact_attendance_daily
                                WHERE school_id = :base_school_id::bigint),
    'historySpanDays', (SELECT max(attendance_date) - min(attendance_date)
                        FROM attendance.attendance_student_records
                        WHERE school_id = :base_school_id::bigint),
    'databaseBytes', pg_database_size(current_database())
)::text;

COMMIT;
