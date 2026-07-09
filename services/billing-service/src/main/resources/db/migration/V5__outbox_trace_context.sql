ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS trace_parent TEXT,
    ADD COLUMN IF NOT EXISTS trace_state TEXT;
