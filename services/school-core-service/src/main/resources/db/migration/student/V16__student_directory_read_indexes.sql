-- The workspace directory pages one school's active students by case-insensitive name.
-- Keep the ordering columns together so PostgreSQL can stop after the requested page
-- instead of sorting every student in a 10,000-student school. Included section keys
-- also make the unfiltered directory aggregate eligible for an index-only scan.
CREATE INDEX IF NOT EXISTS idx_student_students_active_school_name
    ON student.students (school_id, lower(full_name), id)
    INCLUDE (class_id, section_id)
    WHERE deleted_at IS NULL;

-- Directory rows resolve the latest active profile/photo review for each returned
-- student. Existing indexes begin with campaign_id or (school_id, status), neither
-- of which matches the school_id + student_id lookup used by both lateral joins.
CREATE INDEX IF NOT EXISTS idx_student_review_items_school_student_latest
    ON student.student_review_items
        (school_id, student_id, updated_at DESC NULLS LAST, created_at DESC NULLS LAST, id DESC)
    INCLUDE (campaign_id, status);
