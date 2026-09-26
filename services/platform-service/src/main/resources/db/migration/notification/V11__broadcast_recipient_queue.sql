ALTER TABLE notification_broadcasts ADD COLUMN IF NOT EXISTS communication_category VARCHAR(50) NOT NULL DEFAULT 'UNCLASSIFIED';
ALTER TABLE notification_broadcasts ADD COLUMN IF NOT EXISTS dispatch_mode VARCHAR(20);
ALTER TABLE notification_broadcasts ADD COLUMN IF NOT EXISTS queued_at TIMESTAMPTZ;
ALTER TABLE notification_broadcasts ADD COLUMN IF NOT EXISTS queued_by BIGINT;

-- The approved manifest and durable delivery queue are the same rows. Addresses are fetched only
-- from school-core at dispatch time, never stored here or accepted from a browser.
CREATE TABLE notification_broadcast_recipients (
    id UUID PRIMARY KEY,
    broadcast_id UUID NOT NULL REFERENCES notification_broadcasts(id),
    school_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    channel VARCHAR(20) NOT NULL,
    event_id VARCHAR(200) NOT NULL UNIQUE,
    guardian_id VARCHAR(100),
    destination_sha256 VARCHAR(64),
    approval_evidence TEXT,
    status VARCHAR(30) NOT NULL,
    reason TEXT,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    provider VARCHAR(50),
    provider_message_id VARCHAR(200),
    dry_run BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (broadcast_id, student_id, channel)
);
CREATE INDEX idx_broadcast_recipient_pending ON notification_broadcast_recipients(status, next_attempt_at);
ALTER TABLE notification_broadcast_recipients ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification_broadcast_recipients
 USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on')
 WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on');

-- Queue progress is mutable; the approved recipient/event identity is not.
-- Reset broad default privileges before granting the exact runtime operations.
REVOKE ALL ON notification_broadcast_recipients FROM PUBLIC;
DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
    REVOKE ALL ON notification_broadcast_recipients FROM app_rt;
    GRANT SELECT, INSERT ON notification_broadcast_recipients TO app_rt;
    GRANT UPDATE (status, reason, attempts, next_attempt_at, last_attempt_at,
        provider, provider_message_id, dry_run, updated_at) ON notification_broadcast_recipients TO app_rt;
END IF; END $$;
