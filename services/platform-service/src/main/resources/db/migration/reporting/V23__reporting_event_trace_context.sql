ALTER TABLE reporting_event_inbox
    ADD COLUMN IF NOT EXISTS trace_parent TEXT,
    ADD COLUMN IF NOT EXISTS trace_state TEXT;
