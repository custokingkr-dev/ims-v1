-- Notebook order tracker, created after commit (review fix, spec §3.3). Placing a notebook order queues its tracker in
-- the placing transaction and calls no peer while the order row is locked. After commit the row is attempted once; the
-- relay trigger (Cloud Scheduler, every minute) drains what is left with a doubling backoff capped at an hour, and a
-- created tracker deletes its row. One row per order: tracker creation is idempotent on the order id.
CREATE TABLE catalog.notebook_tracker_creation_queue (
    order_id        VARCHAR(255) PRIMARY KEY,
    school_id       BIGINT       NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    FOREIGN KEY (order_id, school_id) REFERENCES catalog.catalog_orders (id, school_id) ON DELETE CASCADE
);
CREATE INDEX idx_notebook_tracker_creation_queue_due ON catalog.notebook_tracker_creation_queue (next_attempt_at);

ALTER TABLE catalog.notebook_tracker_creation_queue ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.notebook_tracker_creation_queue FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.notebook_tracker_creation_queue
    USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on'
        OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]))
    WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on'
        OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.notebook_tracker_creation_queue TO app_rt;
    END IF;
END $$;
