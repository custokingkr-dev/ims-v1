ALTER TABLE tenant_school.outbox_events
    ADD COLUMN IF NOT EXISTS last_error VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS dead_lettered_at TIMESTAMPTZ;

CREATE INDEX idx_ts_outbox_ready
    ON tenant_school.outbox_events (id)
    WHERE published_at IS NULL AND dead_lettered_at IS NULL;

CREATE INDEX idx_ts_outbox_pending_age
    ON tenant_school.outbox_events (occurred_at)
    WHERE published_at IS NULL AND dead_lettered_at IS NULL;

CREATE INDEX idx_ts_outbox_dead_lettered
    ON tenant_school.outbox_events (dead_lettered_at)
    WHERE dead_lettered_at IS NOT NULL;
