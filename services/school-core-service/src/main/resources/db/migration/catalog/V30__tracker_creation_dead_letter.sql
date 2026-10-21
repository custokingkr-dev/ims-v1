-- Notebook tracker creation queue: dead letter. A row that has failed NotebookTrackerCreationQueue.MAX_ATTEMPTS times (about
-- two days at the hourly backoff cap) is given up on: dead_lettered_at is set, drains skip it, and an ERROR log
-- (event=tracker_creation_dead_lettered) and the outbox dead-letter count say so. The order stays tracker-pending;
-- "start the tracker" (ensure) revives the row with fresh retries. Rows still being retried are what the drain claims.
ALTER TABLE catalog.notebook_tracker_creation_queue ADD COLUMN dead_lettered_at TIMESTAMPTZ NULL;

DROP INDEX catalog.idx_notebook_tracker_creation_queue_due;
CREATE INDEX idx_notebook_tracker_creation_queue_pending ON catalog.notebook_tracker_creation_queue (next_attempt_at)
    WHERE dead_lettered_at IS NULL;
