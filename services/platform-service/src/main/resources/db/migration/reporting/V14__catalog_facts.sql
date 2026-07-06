-- Reporting fact table projected from school-core (catalog schema) outbox events
-- (catalog-order.upserted.v1), per Reporting Decoupling SP4. Mirrors
-- reporting.billing_invoice_read (V9) / reporting.dim_* (V11) posture exactly: this is a
-- contextless event-consumer projection, so it intentionally stays WITHOUT RLS, matching the
-- sibling posture documented in V8__enable_rls.sql.
CREATE TABLE IF NOT EXISTS fact_catalog_order (
    id                          VARCHAR(255) PRIMARY KEY,
    school_id                   BIGINT,
    category                    VARCHAR(255),
    status                      VARCHAR(255),
    total_amount                BIGINT,
    superadmin_approval_status  VARCHAR(255),
    vendor_paid_at              TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ,
    required_by_date            DATE,
    design_status               VARCHAR(255),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_fact_catalog_order_school_id ON fact_catalog_order (school_id);
CREATE INDEX IF NOT EXISTS idx_fact_catalog_order_status ON fact_catalog_order (status);
