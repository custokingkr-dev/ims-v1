\set ON_ERROR_STOP on

-- The only writes are to this transaction-local temporary plan table. The
-- transaction is always rolled back, so application schemas remain unchanged.
BEGIN;
SET LOCAL app.bypass_rls = 'on';
SET LOCAL statement_timeout = '120s';

CREATE TEMP TABLE ims_mixed_plans (
    flow text PRIMARY KEY,
    plan jsonb NOT NULL
);

CREATE TEMP TABLE ims_mixed_config AS
SELECT :'base_school_id'::bigint AS school_id,
       :'academic_year_id'::text AS academic_year_id;

DO $$
DECLARE
    plan_row record;
    v_school_id bigint;
    v_academic_year_id text;
BEGIN
    SELECT school_id, academic_year_id
    INTO v_school_id, v_academic_year_id
    FROM ims_mixed_config;

    FOR plan_row IN EXECUTE format($plan$
        EXPLAIN (ANALYZE, BUFFERS, WAL, FORMAT JSON)
        SELECT COUNT(*) AS total,
               COUNT(DISTINCT s.section_id) AS sections
        FROM student.students s
        WHERE s.deleted_at IS NULL
          AND s.school_id = %s
    $plan$, v_school_id) LOOP
        INSERT INTO ims_mixed_plans VALUES ('student-list-stats', plan_row."QUERY PLAN"::jsonb);
    END LOOP;

    FOR plan_row IN EXECUTE format($plan$
        EXPLAIN (ANALYZE, BUFFERS, WAL, FORMAT JSON)
        SELECT s.id, s.full_name, s.admission_no, s.roll_no, s.board_reg_no, s.dob, s.gender,
               s.father_name, s.father_contact, s.mother_name, s.phone, s.address,
               s.house_number, s.street, s.locality, s.city, s.state, s.pin_code,
               s.photo_url, s.fee_status, s.attendance_percent, s.school_id,
               s.deleted_at, s.deleted_reason,
               sc.id AS class_id, sc.name AS class_name, sc.sort_order,
               ss.id AS section_id, ss.name AS section_name,
               ay.label AS academic_year_label,
               profile_review.status AS profile_verification_status,
               photo_review.status AS photo_verification_status
        FROM student.students s
        JOIN tenant_school.school_classes sc ON sc.id = s.class_id
        JOIN tenant_school.school_sections ss ON ss.id = s.section_id
        JOIN tenant_school.academic_years ay ON ay.id = s.academic_year_id
        LEFT JOIN LATERAL (
            SELECT i.status
            FROM student.student_review_items i
            JOIN student.student_review_campaigns c ON c.id = i.campaign_id
            WHERE i.student_id = s.id AND i.school_id = s.school_id
              AND c.review_type = 'PROFILE_VERIFICATION' AND c.status = 'ACTIVE'
            ORDER BY i.updated_at DESC NULLS LAST, i.created_at DESC NULLS LAST, i.id DESC
            LIMIT 1
        ) profile_review ON TRUE
        LEFT JOIN LATERAL (
            SELECT i.status
            FROM student.student_review_items i
            JOIN student.student_review_campaigns c ON c.id = i.campaign_id
            WHERE i.student_id = s.id AND i.school_id = s.school_id
              AND c.review_type = 'PHOTO_VERIFICATION' AND c.status = 'ACTIVE'
            ORDER BY i.updated_at DESC NULLS LAST, i.created_at DESC NULLS LAST, i.id DESC
            LIMIT 1
        ) photo_review ON TRUE
        WHERE s.deleted_at IS NULL
          AND s.school_id = %s
        ORDER BY lower(s.full_name), s.id
        LIMIT 50 OFFSET 0
    $plan$, v_school_id) LOOP
        INSERT INTO ims_mixed_plans VALUES ('student-list-page', plan_row."QUERY PLAN"::jsonb);
    END LOOP;

    FOR plan_row IN EXECUTE format($plan$
        EXPLAIN (ANALYZE, BUFFERS, WAL, FORMAT JSON)
        SELECT ss.id, ss.name, ss.teacher_name, ss.school_class_id, sc.name AS class_name,
               ad.total_enrolled, ad.present_count, ad.absent_count,
               ad.late_count, ad.leave_count, ad.recorded_at, ad.updated_at, ad.locked,
               COALESCE(enrolled.total_students, 0) AS total_students
        FROM tenant_school.school_sections ss
        JOIN tenant_school.school_classes sc ON sc.id = ss.school_class_id
        LEFT JOIN attendance.attendance_daily ad
               ON ad.section_id = ss.id
              AND ad.attendance_date = CURRENT_DATE
              AND ad.academic_year_id = %L
        LEFT JOIN (
            SELECT section_id, count(*) AS total_students
            FROM student.students
            WHERE school_id = %s AND deleted_at IS NULL
            GROUP BY section_id
        ) enrolled ON enrolled.section_id = ss.id
        WHERE ss.school_id = %s
        ORDER BY sc.sort_order, ss.name
    $plan$, v_academic_year_id, v_school_id, v_school_id) LOOP
        INSERT INTO ims_mixed_plans VALUES ('attendance-daily-summary', plan_row."QUERY PLAN"::jsonb);
    END LOOP;

    FOR plan_row IN EXECUTE format($plan$
        EXPLAIN (ANALYZE, BUFFERS, WAL, FORMAT JSON)
        SELECT ss.id AS section_id, ss.school_class_id AS class_id, ss.name AS section_name,
               ss.teacher_name, sc.name AS class_name,
               COALESCE(SUM(ad.present_count), 0) AS p,
               COALESCE(SUM(ad.late_count), 0) AS l,
               COALESCE(SUM(ad.leave_count), 0) AS e,
               COALESCE(SUM(ad.absent_count), 0) AS a,
               COUNT(ad.id) AS days_recorded
        FROM attendance.attendance_daily ad
        JOIN tenant_school.school_sections ss ON ss.id = ad.section_id
        JOIN tenant_school.school_classes sc ON sc.id = ss.school_class_id
        WHERE ad.academic_year_id = %L
          AND ad.attendance_date BETWEEN CURRENT_DATE AND CURRENT_DATE
          AND ad.school_id = %s
        GROUP BY ss.id, ss.school_class_id, ss.name, ss.teacher_name, sc.name
    $plan$, v_academic_year_id, v_school_id) LOOP
        INSERT INTO ims_mixed_plans VALUES ('attendance-report-summary', plan_row."QUERY PLAN"::jsonb);
    END LOOP;
END $$;

SELECT 'IMS_MIXED_QUERY_PLAN|' || json_build_object(
    'capturedAt', now(),
    'schoolId', config.school_id,
    'academicYearId', config.academic_year_id,
    'flows', (SELECT jsonb_object_agg(flow, plan ORDER BY flow) FROM ims_mixed_plans),
    'schoolRows', (SELECT count(*) FROM student.students
        WHERE school_id = config.school_id AND deleted_at IS NULL),
    'studentIndexes', (
        SELECT json_agg(json_build_object('name', indexname, 'definition', indexdef) ORDER BY indexname)
        FROM pg_indexes
        WHERE schemaname = 'student' AND tablename IN ('students', 'student_review_items')
    )
)::text
FROM ims_mixed_config config;

ROLLBACK;
