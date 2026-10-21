-- Tracker status outbox: dead letter (review follow-up; the shape of school-core's catalog V30). A status that has failed
-- TrackerStatusOutbox.MAX_ATTEMPTS times (about two days at the hourly backoff cap) is given up on: dead_lettered_at is
-- set, drains skip it, and an ERROR log (event=tracker_status_dead_lettered) and the outbox dead-letter count say so. A
-- newer status for the order (any later step change) revives the row with fresh retries. Rows still being retried are
-- what the drain claims.
ALTER TABLE workflow.workflow_tracker_status_outbox ADD COLUMN dead_lettered_at TIMESTAMPTZ NULL;

-- When the order's status first waited for delivery. A newer status replaces the row's status but keeps this, and a
-- failed attempt never moves it (updated_at does both), so the outbox health report's oldest pending age is how long the
-- order's shown status has been out of date. Rows queued before this migration count from now.
ALTER TABLE workflow.workflow_tracker_status_outbox ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();

DROP INDEX workflow.idx_wtso_due;
CREATE INDEX idx_wtso_pending ON workflow.workflow_tracker_status_outbox (next_attempt_at) WHERE dead_lettered_at IS NULL;
