CREATE INDEX IF NOT EXISTS idx_student_students_active_school_section
    ON student.students (school_id, section_id)
    WHERE deleted_at IS NULL;
