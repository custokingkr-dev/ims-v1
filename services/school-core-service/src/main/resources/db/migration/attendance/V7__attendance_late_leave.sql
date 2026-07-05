-- Allow the two new per-student statuses (was IN ('PRESENT','ABSENT')).
-- The inline CHECK from V1 has Postgres's default name; drop-if-exists then re-add is robust.
ALTER TABLE attendance.attendance_student_records
    DROP CONSTRAINT IF EXISTS attendance_student_records_status_check;
ALTER TABLE attendance.attendance_student_records
    ADD CONSTRAINT attendance_student_records_status_check
    CHECK (status IN ('PRESENT', 'ABSENT', 'LATE', 'LEAVE'));

-- Aggregate late/leave alongside present/absent on the day rollup.
ALTER TABLE attendance.attendance_daily
    ADD COLUMN IF NOT EXISTS late_count  INTEGER NOT NULL DEFAULT 0;
ALTER TABLE attendance.attendance_daily
    ADD COLUMN IF NOT EXISTS leave_count INTEGER NOT NULL DEFAULT 0;
