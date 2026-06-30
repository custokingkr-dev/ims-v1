-- fee_assignments and payment_records have no school_id; derive it from the student's school.
-- CROSS-SCHEMA backfill: reads student.students (one-time; appuser owns all schemas).
-- The UPDATE is guarded so migration is safe to run in envs where student.students
-- may not yet exist (e.g. test containers); in production the table always exists.
ALTER TABLE fee.fee_assignments ADD COLUMN IF NOT EXISTS school_id BIGINT;

DO $$
BEGIN
    IF to_regclass('student.students') IS NOT NULL THEN
        UPDATE fee.fee_assignments fa
           SET school_id = s.school_id
          FROM student.students s
         WHERE s.id = fa.student_id AND fa.school_id IS NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_fee_assignments_school_year_student
    ON fee.fee_assignments (school_id, academic_year_id, student_id);

ALTER TABLE fee.payment_records ADD COLUMN IF NOT EXISTS school_id BIGINT;

DO $$
BEGIN
    IF to_regclass('student.students') IS NOT NULL THEN
        UPDATE fee.payment_records pr
           SET school_id = s.school_id
          FROM student.students s
         WHERE s.id = pr.student_id AND pr.school_id IS NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_payment_records_school_paid
    ON fee.payment_records (school_id, paid_at DESC);
