ALTER TABLE notification_inbox_events
    ADD COLUMN IF NOT EXISTS trace_parent TEXT,
    ADD COLUMN IF NOT EXISTS trace_state TEXT;
