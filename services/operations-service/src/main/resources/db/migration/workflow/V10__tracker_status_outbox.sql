-- Tracker status delivery to the catalogue (review fix, spec §3.3). A tracker-managed order in school-core shows the
-- tracker's current step as its status. A step change writes one row per order here, in the transaction that changed
-- the step, carrying the new status and the instance version after the change. After commit the row is pushed once;
-- the relay trigger (Cloud Scheduler, every minute) retries what is left with a doubling backoff capped at an hour.
--   * A row is replaced only by a newer tracker_version, so the outbox always holds the order's latest status.
--   * A delivered row is deleted only while it still holds the version that was sent, so a newer status queued while
--     the push was in flight stays for its own delivery.
--   * school-core ignores a version no newer than the one it has applied, so late or repeated pushes are harmless.
CREATE TABLE workflow.workflow_tracker_status_outbox (
    order_id        VARCHAR(255) PRIMARY KEY,
    school_id       BIGINT       NOT NULL,
    status          VARCHAR(41)  NOT NULL,
    tracker_version BIGINT       NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      VARCHAR(500),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_wtso_due ON workflow.workflow_tracker_status_outbox (next_attempt_at);

-- Row-level security: the shape of the V8 tracker tables, including the operator disjunct. A step change writes the
-- row under the acting user's scope; the relay claims through app.bypass_rls and then scopes to the row's school.
ALTER TABLE workflow.workflow_tracker_status_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow.workflow_tracker_status_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workflow.workflow_tracker_status_outbox
    USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on'
        OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]))
    WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on'
        OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]));

-- Runtime grants (the dedicated-role ACL is resynchronised by afterMigrate; this covers app_rt where it exists).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON workflow.workflow_tracker_status_outbox TO app_rt;
    END IF;
END $$;
