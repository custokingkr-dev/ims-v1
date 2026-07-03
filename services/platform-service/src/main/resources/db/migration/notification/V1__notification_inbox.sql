CREATE TABLE IF NOT EXISTS notification_inbox_events (
    event_id VARCHAR(120) PRIMARY KEY,
    event_type VARCHAR(160) NOT NULL,
    event_key VARCHAR(220),
    aggregate_type VARCHAR(120),
    aggregate_id VARCHAR(120),
    payload JSONB NOT NULL,
    status VARCHAR(40) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS idx_notification_inbox_status_received
    ON notification_inbox_events (status, received_at);
