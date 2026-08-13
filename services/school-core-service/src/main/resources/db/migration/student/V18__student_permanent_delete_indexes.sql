-- Keep single-student hard deletion index-backed at large school/import volumes.
CREATE INDEX IF NOT EXISTS idx_student_import_rows_applied_student
    ON student.import_rows (applied_student_id)
    WHERE applied_student_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_photo_import_rows_student
    ON student.photo_import_rows (student_id)
    WHERE student_id IS NOT NULL;
