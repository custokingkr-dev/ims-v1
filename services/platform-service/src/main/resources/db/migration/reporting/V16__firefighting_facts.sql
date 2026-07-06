-- Read model projected from firefighting-request.upserted.v1 events (SP6 reporting-outbox
-- decoupling): operations-service now owns firefighting.firefighting_requests, so reporting
-- no longer needs a live cross-schema read of that table for its KPI/vendor-dues/approvals
-- queries — this fact table is the projected substitute.
--
-- Written by a contextless event consumer (the inbox projector), like billing_invoice_read
-- and the dim_* tables — school_id is nullable and this table intentionally stays WITHOUT
-- RLS, matching the sibling posture documented in V8__enable_rls.sql.
CREATE TABLE IF NOT EXISTS fact_firefighting_request (
    code                  TEXT PRIMARY KEY,
    title                 TEXT,
    category              TEXT,
    urgency               TEXT,
    status                TEXT,
    estimated_budget      BIGINT,
    school_id             BIGINT,
    winner_vendor         TEXT,
    winner_amount         BIGINT,
    created_at            TIMESTAMPTZ,
    bursar_approved_at    TIMESTAMPTZ,
    principal_approved_at TIMESTAMPTZ,
    rejected_reason       TEXT,
    vendor_paid_at        TIMESTAMPTZ,
    vendor_paid_by        BIGINT,
    vendor_payment_notes  TEXT,
    occurred_at           TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_fact_ff_request_school_status ON fact_firefighting_request (school_id, status);
CREATE INDEX IF NOT EXISTS idx_fact_ff_request_vendor_unpaid ON fact_firefighting_request (school_id, status) WHERE vendor_paid_at IS NULL;
