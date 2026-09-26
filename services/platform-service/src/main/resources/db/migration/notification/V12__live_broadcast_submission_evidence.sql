-- Previously approved dry-run manifests cannot acquire live meaning by changing configuration.
ALTER TABLE notification_broadcasts ADD COLUMN approval_mode VARCHAR(20) NOT NULL DEFAULT 'DRY_RUN';
ALTER TABLE notification_broadcasts ADD COLUMN approval_fingerprint VARCHAR(64);

-- No addresses, provider credentials, or message payloads are persisted in this ledger.
-- A committed row is a permanent fence against any second application submission.
CREATE TABLE broadcast_live_submissions (
    event_id VARCHAR(200) PRIMARY KEY REFERENCES notification_broadcast_recipients(event_id),
    school_id BIGINT NOT NULL,
    broadcast_id UUID NOT NULL,
    channel VARCHAR(20) NOT NULL CHECK (channel = 'EMAIL'),
    correlation_id VARCHAR(51) NOT NULL UNIQUE,
    destination_sha256 VARCHAR(64) NOT NULL,
    sender_sha256 VARCHAR(64) NOT NULL,
    request_sha256 VARCHAR(64) NOT NULL,
    provider_message_id VARCHAR(200),
    submission_status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTING'
        CHECK (submission_status IN ('SUBMITTING','ACCEPTED','REJECTED','UNKNOWN')),
    delivery_status VARCHAR(30),
    reason VARCHAR(100),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (school_id, event_id)
);
CREATE UNIQUE INDEX idx_live_provider_message ON broadcast_live_submissions(channel, provider_message_id)
    WHERE provider_message_id IS NOT NULL;
CREATE TABLE broadcast_live_reports (
    report_sha256 VARCHAR(64) PRIMARY KEY,
    event_id VARCHAR(200) NOT NULL REFERENCES broadcast_live_submissions(event_id),
    school_id BIGINT NOT NULL,
    provider_message_id VARCHAR(200) NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN ('ACCEPTED','DELIVERED','DELIVERY_FAILED')),
    provider_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_live_reports_event ON broadcast_live_reports(event_id);
ALTER TABLE broadcast_live_submissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE broadcast_live_reports ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON broadcast_live_submissions
 USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on')
 WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on');
CREATE POLICY tenant_isolation ON broadcast_live_reports
 USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on')
 WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint OR current_setting('app.bypass_rls', true) = 'on');
REVOKE ALL ON broadcast_live_submissions, broadcast_live_reports FROM PUBLIC;
DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
    REVOKE ALL ON broadcast_live_submissions, broadcast_live_reports FROM app_rt;
    GRANT SELECT, INSERT ON broadcast_live_submissions, broadcast_live_reports TO app_rt;
    GRANT UPDATE (provider_message_id, submission_status, delivery_status, reason, updated_at) ON broadcast_live_submissions TO app_rt;
END IF; END $$;
