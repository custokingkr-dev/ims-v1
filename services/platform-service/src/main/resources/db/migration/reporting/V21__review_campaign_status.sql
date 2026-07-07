-- Adds campaign_status to the review-item fact so pendingReviewCount can exclude non-active
-- campaigns (restoring the filter dropped in SP7 phase 3). Existing rows default to 'ACTIVE',
-- correct because every pre-existing campaign is ACTIVE. Set by the
-- student-review-campaign.completed.v1 projection; the item upsert never touches this column
-- (ON CONFLICT DO UPDATE lists only school_id/campaign_id/status), so completion is order-safe.
ALTER TABLE fact_student_review_item
    ADD COLUMN IF NOT EXISTS campaign_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_fact_student_review_item_campaign
    ON fact_student_review_item (campaign_id);
