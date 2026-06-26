ALTER TABLE student.student_review_campaigns
    DROP CONSTRAINT IF EXISTS student_review_campaigns_initiated_by_fkey;

ALTER TABLE student.student_review_items
    DROP CONSTRAINT IF EXISTS student_review_items_assigned_to_user_id_fkey;
