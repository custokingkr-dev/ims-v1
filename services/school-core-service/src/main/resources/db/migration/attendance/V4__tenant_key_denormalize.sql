-- attendance_daily has no school_id; derive it from its section's owning school.
-- CROSS-SCHEMA backfill: reads tenant_school.school_sections (one-time; appuser owns all schemas).
-- The UPDATE is guarded so migration is safe to run in envs where tenant_school.school_sections
-- may not yet exist (e.g. test containers); in production the table always exists.
ALTER TABLE attendance.attendance_daily ADD COLUMN IF NOT EXISTS school_id BIGINT;

DO $$
BEGIN
    IF to_regclass('tenant_school.school_sections') IS NOT NULL THEN
        UPDATE attendance.attendance_daily ad
           SET school_id = ss.school_id
          FROM tenant_school.school_sections ss
         WHERE ss.id = ad.section_id AND ad.school_id IS NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_attendance_daily_school_date
    ON attendance.attendance_daily (school_id, attendance_date, academic_year_id);
