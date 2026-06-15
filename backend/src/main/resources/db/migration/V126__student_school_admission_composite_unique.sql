-- V126: Replace the global unique constraint on students.admission_no with a
-- school-scoped composite unique constraint.
--
-- Problem: uk_student_admission_no prevents the same admission number from
-- existing in *any* school. In multi-tenant operation every school manages its
-- own admission numbering, so "ADM-001" in School A and "ADM-001" in School B
-- must be allowed.
--
-- Solution: drop the global constraint, add a composite unique constraint on
-- (school_id, admission_no) so uniqueness is enforced per-school.
-- V109 already created idx_students_school_admission as a non-unique index;
-- this migration replaces it with a proper unique constraint.

-- Step 1 – drop the global unique constraint (created by Hibernate DDL / earlier bootstrap)
ALTER TABLE students DROP CONSTRAINT IF EXISTS uk_student_admission_no;

-- Step 2 – drop the non-unique composite index added by V109 (will be superseded)
DROP INDEX IF EXISTS idx_students_school_admission;

-- Step 3 – add the school-scoped unique constraint
--   • NULL admission_no values are excluded so legacy rows without a number
--     do not collide with each other.
ALTER TABLE students
    ADD CONSTRAINT uix_students_school_admission UNIQUE (school_id, admission_no);
