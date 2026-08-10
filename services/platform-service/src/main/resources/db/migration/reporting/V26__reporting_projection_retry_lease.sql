ALTER TABLE reporting_event_inbox
    ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ;

UPDATE reporting_event_inbox
SET next_attempt_at = COALESCE(next_attempt_at, received_at, now())
WHERE status = 'FAILED';

CREATE INDEX IF NOT EXISTS idx_reporting_event_inbox_projection_ready
    ON reporting_event_inbox (status, next_attempt_at, received_at)
    WHERE status IN ('RECEIVED', 'FAILED', 'PROCESSING');

COMMENT ON COLUMN reporting_event_inbox.attempt_count IS
    'Number of failed projection attempts; five failures move the event to DEAD_LETTER.';
COMMENT ON COLUMN reporting_event_inbox.claimed_at IS
    'Projection lease timestamp; PROCESSING rows are reclaimable after five minutes.';
