ALTER TABLE student.students
    DROP CONSTRAINT IF EXISTS students_school_id_fkey,
    DROP CONSTRAINT IF EXISTS students_class_id_fkey,
    DROP CONSTRAINT IF EXISTS students_section_id_fkey,
    DROP CONSTRAINT IF EXISTS students_academic_year_id_fkey;

ALTER TABLE student.student_review_campaigns
    DROP CONSTRAINT IF EXISTS student_review_campaigns_school_id_fkey,
    DROP CONSTRAINT IF EXISTS student_review_campaigns_academic_year_id_fkey;

ALTER TABLE student.student_review_items
    DROP CONSTRAINT IF EXISTS student_review_items_school_id_fkey;
