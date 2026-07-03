CREATE TABLE IF NOT EXISTS reporting_event_inbox (
    event_id VARCHAR(120) PRIMARY KEY,
    event_key VARCHAR(255),
    event_type VARCHAR(160) NOT NULL,
    event_version VARCHAR(20),
    aggregate_type VARCHAR(120),
    aggregate_id VARCHAR(255),
    school_id BIGINT,
    actor_user_id BIGINT,
    occurred_at TIMESTAMPTZ,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    status VARCHAR(40) NOT NULL DEFAULT 'RECEIVED',
    envelope TEXT NOT NULL,
    payload TEXT NOT NULL,
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS idx_reporting_event_inbox_type_received
    ON reporting_event_inbox (event_type, received_at DESC);

CREATE INDEX IF NOT EXISTS idx_reporting_event_inbox_school_received
    ON reporting_event_inbox (school_id, received_at DESC);

CREATE INDEX IF NOT EXISTS idx_reporting_event_inbox_status_received
    ON reporting_event_inbox (status, received_at DESC);
