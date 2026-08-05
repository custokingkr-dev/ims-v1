ALTER TABLE notification.notification_inbox_events
    ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS dead_lettered_at TIMESTAMPTZ;

UPDATE notification.notification_inbox_events
SET next_attempt_at = COALESCE(processed_at, received_at, now())
WHERE status = 'FAILED' AND next_attempt_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_notification_inbox_retry_due
    ON notification.notification_inbox_events (status, next_attempt_at, received_at)
    WHERE status = 'FAILED';
