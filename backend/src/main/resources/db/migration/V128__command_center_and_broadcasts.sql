-- V128: Command Center action cards, activity feed, and notification broadcasts.

CREATE TABLE IF NOT EXISTS command_center_actions (
    id UUID PRIMARY KEY,
    school_id BIGINT REFERENCES schools(id),
    module VARCHAR(50) NOT NULL,
    urgency VARCHAR(20) NOT NULL,
    confidence INT NOT NULL DEFAULT 80,
    title TEXT NOT NULL,
    reason TEXT,
    impact TEXT,
    current_state VARCHAR(100),
    target_state VARCHAR(100),
    cta_label VARCHAR(100),
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    source_type VARCHAR(50),
    source_id VARCHAR(100),
    accepted_by BIGINT REFERENCES app_users(id),
    accepted_at TIMESTAMPTZ,
    dismissed_by BIGINT REFERENCES app_users(id),
    dismissed_reason TEXT,
    dismissed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_cca_school_status_urgency ON command_center_actions(school_id, status, urgency);
CREATE INDEX IF NOT EXISTS idx_cca_status ON command_center_actions(status);
CREATE INDEX IF NOT EXISTS idx_cca_created ON command_center_actions(created_at DESC);

CREATE TABLE IF NOT EXISTS command_center_feed (
    id UUID PRIMARY KEY,
    school_id BIGINT REFERENCES schools(id),
    module VARCHAR(50) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    title TEXT NOT NULL,
    message TEXT,
    severity VARCHAR(20) DEFAULT 'info',
    entity_type VARCHAR(80),
    entity_id VARCHAR(100),
    actor_user_id BIGINT REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_cc_feed_school_created ON command_center_feed(school_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_cc_feed_created ON command_center_feed(created_at DESC);

CREATE TABLE IF NOT EXISTS notification_broadcasts (
    id UUID PRIMARY KEY,
    school_id BIGINT REFERENCES schools(id),
    module VARCHAR(50),
    title TEXT NOT NULL,
    message TEXT NOT NULL,
    audience_type VARCHAR(50) NOT NULL,
    channels TEXT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    scheduled_at TIMESTAMPTZ,
    approved_by BIGINT REFERENCES app_users(id),
    approved_at TIMESTAMPTZ,
    sent_by BIGINT REFERENCES app_users(id),
    sent_at TIMESTAMPTZ,
    created_by BIGINT REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_broadcast_school_status ON notification_broadcasts(school_id, status);
CREATE INDEX IF NOT EXISTS idx_broadcast_created ON notification_broadcasts(created_at DESC);

CREATE TABLE IF NOT EXISTS notification_delivery_logs (
    id UUID PRIMARY KEY,
    broadcast_id UUID REFERENCES notification_broadcasts(id),
    recipient_type VARCHAR(30),
    recipient_ref VARCHAR(100),
    channel VARCHAR(30),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    provider_message_id VARCHAR(150),
    failure_reason TEXT,
    delivered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_delivery_broadcast_status ON notification_delivery_logs(broadcast_id, status);
