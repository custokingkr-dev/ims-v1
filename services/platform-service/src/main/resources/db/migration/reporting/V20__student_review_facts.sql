-- Fact table projected from school-core (student) outbox events
-- (student-review-item.upserted.v1), per Reporting Decoupling SP7 (student-review).
-- Mirrors reporting.fact_fee_assignment (V12) / fact_attendance_daily (V13) posture exactly:
-- this is a contextless event-consumer projection, so it intentionally stays WITHOUT RLS,
-- matching the sibling posture documented in V8__enable_rls.sql. Rows are upserted
-- idempotently by id so replaying the same or a later event never duplicates state.
CREATE TABLE IF NOT EXISTS fact_student_review_item (
    id          VARCHAR(255) PRIMARY KEY,
    school_id   BIGINT,
    campaign_id VARCHAR(255),
    status      VARCHAR(32),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_fact_student_review_item_school_status
    ON fact_student_review_item (school_id, status);
