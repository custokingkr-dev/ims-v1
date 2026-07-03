ALTER TABLE attendance.attendance_daily
    DROP CONSTRAINT IF EXISTS fk_attendance_class,
    DROP CONSTRAINT IF EXISTS fk_attendance_section,
    DROP CONSTRAINT IF EXISTS fk_attendance_year;

ALTER TABLE attendance.attendance_student_records
    DROP CONSTRAINT IF EXISTS fk_attendance_student_records_school,
    DROP CONSTRAINT IF EXISTS fk_attendance_student_records_academic_year,
    DROP CONSTRAINT IF EXISTS fk_attendance_student_records_class,
    DROP CONSTRAINT IF EXISTS fk_attendance_student_records_section,
    DROP CONSTRAINT IF EXISTS fk_attendance_student_records_student;
