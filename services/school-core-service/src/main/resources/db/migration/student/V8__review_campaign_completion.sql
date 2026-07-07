-- Adds campaign-completion columns. status already exists (V1, default 'DRAFT'; the app writes
-- 'ACTIVE'); completion sets it to 'COMPLETED'. Forward-only, no backfill.
ALTER TABLE student.student_review_campaigns
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS completed_by BIGINT;
