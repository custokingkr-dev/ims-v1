ALTER TABLE billing.outbox_events
    ADD COLUMN IF NOT EXISTS last_error VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS dead_lettered_at TIMESTAMPTZ;

CREATE INDEX idx_outbox_ready
    ON billing.outbox_events (created_at, id)
    WHERE published_at IS NULL AND dead_lettered_at IS NULL;

CREATE INDEX idx_outbox_pending_age
    ON billing.outbox_events (occurred_at)
    WHERE published_at IS NULL AND dead_lettered_at IS NULL;

CREATE INDEX idx_outbox_dead_lettered
    ON billing.outbox_events (dead_lettered_at)
    WHERE dead_lettered_at IS NOT NULL;
