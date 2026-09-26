-- Durable command receipts survive quotation deletion. Reusing a key can never
-- recreate a removed quotation or produce another request/outbox event.
CREATE TABLE firefighting.creation_replays (
    school_id BIGINT NOT NULL,
    operation VARCHAR(300) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (school_id, operation, idempotency_key)
);
ALTER TABLE firefighting.creation_replays ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON firefighting.creation_replays
    USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on');
-- Broad owner default grants must not let runtime rewrite or erase a saved key.
REVOKE ALL ON firefighting.creation_replays FROM PUBLIC;
DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='app_rt') THEN
    REVOKE ALL ON firefighting.creation_replays FROM app_rt;
    GRANT SELECT, INSERT ON firefighting.creation_replays TO app_rt;
END IF; END $$;
