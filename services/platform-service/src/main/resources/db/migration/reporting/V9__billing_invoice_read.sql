-- Read model projected from billing.invoice-upserted.v1 events (Phase 3 reporting-outbox spike).
-- Written by a contextless event consumer (the inbox projector), like command_center_feed and
-- reporting_event_inbox — school_id is nullable and this table intentionally stays WITHOUT RLS,
-- matching the sibling posture documented in V8__enable_rls.sql.
CREATE TABLE IF NOT EXISTS billing_invoice_read (
    id          TEXT PRIMARY KEY,
    school_id   BIGINT,
    status      TEXT,
    total       NUMERIC,
    occurred_at TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_billing_invoice_read_school_status ON billing_invoice_read (school_id, status);
