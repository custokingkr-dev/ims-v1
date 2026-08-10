\set ON_ERROR_STOP on

BEGIN READ ONLY;
SET LOCAL app.bypass_rls = 'on';
SET LOCAL statement_timeout = '60s';

CREATE TEMP TABLE plan_config AS
SELECT :base_school_id::bigint AS base_school_id;

DO $$
DECLARE
    student_count bigint;
    configured_base_school_id bigint;
BEGIN
    SELECT base_school_id INTO configured_base_school_id FROM plan_config;
    SELECT count(*) INTO student_count
    FROM student.students
    WHERE school_id >= configured_base_school_id
      AND school_id < configured_base_school_id + 10000;
    IF student_count < 300000 THEN
        RAISE EXCEPTION 'Query-plan certification requires the 300,000-student synthetic fixture; found %', student_count;
    END IF;
END $$;

CREATE TEMP TABLE captured_plans(label text PRIMARY KEY, plan jsonb NOT NULL);

DO $$
DECLARE
    plan_json json;
    configured_base_school_id bigint;
BEGIN
    SELECT base_school_id INTO configured_base_school_id FROM plan_config;
    EXECUTE format($query$
        EXPLAIN (ANALYZE, BUFFERS, WAL, FORMAT JSON)
        SELECT id, admission_no, full_name, class_id, section_id, academic_year_id
        FROM student.students
        WHERE school_id = %s AND deleted_at IS NULL
        ORDER BY id
        LIMIT 50
    $query$, configured_base_school_id) INTO plan_json;
    INSERT INTO captured_plans VALUES ('student-directory-school-page', plan_json::jsonb);

    EXECUTE format($query$
        EXPLAIN (ANALYZE, BUFFERS, WAL, FORMAT JSON)
        SELECT id, admission_no, full_name
        FROM student.students
        WHERE school_id = %s AND deleted_at IS NULL
          AND (lower(full_name) LIKE '%%synthetic student 12%%'
               OR lower(admission_no) LIKE '%%synthetic student 12%%')
        ORDER BY id
        LIMIT 50
    $query$, configured_base_school_id) INTO plan_json;
    INSERT INTO captured_plans VALUES ('student-directory-school-search', plan_json::jsonb);

    EXECUTE format($query$
        EXPLAIN (ANALYZE, BUFFERS, WAL, FORMAT JSON)
        SELECT student_id, attendance_date, status
        FROM attendance.attendance_student_records
        WHERE school_id = %s
          AND attendance_date BETWEEN current_date - interval '730 days' AND current_date
        ORDER BY attendance_date DESC
        LIMIT 500
    $query$, configured_base_school_id) INTO plan_json;
    INSERT INTO captured_plans VALUES ('attendance-school-two-year-history', plan_json::jsonb);

    EXECUTE format($query$
        EXPLAIN (ANALYZE, BUFFERS, WAL, FORMAT JSON)
        SELECT attendance_date,
               sum(total_enrolled) AS enrolled,
               sum(present_count) AS present,
               sum(absent_count) AS absent
        FROM reporting.fact_attendance_daily
        WHERE school_id = %s
          AND attendance_date BETWEEN current_date - interval '730 days' AND current_date
        GROUP BY attendance_date
        ORDER BY attendance_date DESC
        LIMIT 730
    $query$, configured_base_school_id) INTO plan_json;
    INSERT INTO captured_plans VALUES ('reporting-attendance-two-year-history', plan_json::jsonb);
END $$;

SELECT 'IMS_QUERY_PLAN_FIXTURE|' || json_build_object(
    'students', (SELECT count(*) FROM student.students
                 WHERE school_id >= :base_school_id::bigint AND school_id < :base_school_id::bigint + 10000),
    'largestSchoolStudents', (SELECT count(*) FROM student.students WHERE school_id = :base_school_id::bigint),
    'attendanceRows', (SELECT count(*) FROM attendance.attendance_student_records
                       WHERE school_id >= :base_school_id::bigint AND school_id < :base_school_id::bigint + 10000),
    'reportingAttendanceRows', (SELECT count(*) FROM reporting.fact_attendance_daily
                                WHERE school_id >= :base_school_id::bigint AND school_id < :base_school_id::bigint + 10000),
    'historyCertified', (SELECT count(*) >= 7300000
                         AND (max(attendance_date) - min(attendance_date)) >= 700
                         FROM attendance.attendance_student_records
                         WHERE school_id = :base_school_id::bigint)
)::text;

SELECT 'IMS_QUERY_PLAN|' || json_build_object('label', label, 'plan', plan)::text
FROM captured_plans
ORDER BY label;

ROLLBACK;
