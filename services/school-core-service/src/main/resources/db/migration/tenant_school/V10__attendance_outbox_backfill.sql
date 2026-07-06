-- Backfill attendance-daily.upserted.v1 events for existing attendance.attendance_daily rows so
-- the reporting projector (Reporting Decoupling SP3) has a full history to replay, matching the
-- V8__reference_outbox_backfill.sql pattern for the reference-dimension events (SP1).
-- CROSS-SCHEMA read: attendance.attendance_daily is owned by this same service (school-core-service
-- hosts both tenant_school and attendance schemas), so this one-time backfill read is in-process,
-- not a cross-service boundary violation.
DO $$
BEGIN
    IF to_regclass('attendance.attendance_daily') IS NOT NULL THEN
        INSERT INTO tenant_school.outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
        SELECT 'AttendanceDailyUpserted:'||ad.id, 'attendance-daily.upserted.v1', 'AttendanceDaily', ad.id, ad.school_id,
               jsonb_build_object(
                   'id', ad.id,
                   'schoolId', ad.school_id,
                   'date', ad.attendance_date,
                   'classId', ad.school_class_id,
                   'sectionId', ad.section_id,
                   'academicYearId', ad.academic_year_id,
                   'presentCount', ad.present_count,
                   'absentCount', ad.absent_count,
                   'lateCount', ad.late_count,
                   'leaveCount', ad.leave_count,
                   'totalEnrolled', ad.total_enrolled)
        FROM attendance.attendance_daily ad;
    END IF;
END $$;
