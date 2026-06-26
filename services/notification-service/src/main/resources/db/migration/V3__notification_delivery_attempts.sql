CREATE TABLE IF NOT EXISTS notification_delivery_attempts (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(120) NOT NULL,
    event_type VARCHAR(160),
    channel VARCHAR(40),
    provider VARCHAR(80) NOT NULL,
    status VARCHAR(40) NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    error TEXT,
    CONSTRAINT fk_notification_delivery_attempt_event
        FOREIGN KEY (event_id)
        REFERENCES notification_inbox_events(event_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_notification_delivery_attempt_event
    ON notification_delivery_attempts (event_id, attempted_at DESC);

CREATE INDEX IF NOT EXISTS idx_notification_delivery_attempt_status
    ON notification_delivery_attempts (status, attempted_at DESC);
