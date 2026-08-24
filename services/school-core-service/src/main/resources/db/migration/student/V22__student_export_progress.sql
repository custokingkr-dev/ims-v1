ALTER TABLE student.student_export_audit
    ADD COLUMN IF NOT EXISTS progress_percent INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS progress_phase VARCHAR(32) NOT NULL DEFAULT 'PREPARING',
    ADD COLUMN IF NOT EXISTS processed_student_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE student.student_export_audit
    DROP CONSTRAINT IF EXISTS ck_student_export_audit_progress_percent;
ALTER TABLE student.student_export_audit
    ADD CONSTRAINT ck_student_export_audit_progress_percent
        CHECK (progress_percent BETWEEN 0 AND 100);

ALTER TABLE student.student_export_audit
    DROP CONSTRAINT IF EXISTS ck_student_export_audit_processed_students;
ALTER TABLE student.student_export_audit
    ADD CONSTRAINT ck_student_export_audit_processed_students
        CHECK (processed_student_count >= 0 AND processed_student_count <= student_count);
