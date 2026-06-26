CREATE TABLE audit_events (
    id BIGSERIAL PRIMARY KEY,
    action VARCHAR(255) NOT NULL,
    user_id BIGINT,
    school_id BIGINT,
    entity_type VARCHAR(255),
    entity_id VARCHAR(255),
    ip_address VARCHAR(64),
    user_agent VARCHAR(512),
    request_id VARCHAR(64),
    actor_email VARCHAR(255),
    old_value TEXT,
    new_value TEXT,
    outcome VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
    event_timestamp TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_events_user_id ON audit_events (user_id);
CREATE INDEX idx_audit_events_school_ts ON audit_events (school_id, event_timestamp DESC);
CREATE INDEX idx_audit_events_action ON audit_events (action);
CREATE INDEX idx_audit_events_request_id ON audit_events (request_id);
