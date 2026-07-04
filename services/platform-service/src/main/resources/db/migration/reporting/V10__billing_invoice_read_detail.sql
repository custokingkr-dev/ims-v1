-- Widens reporting.billing_invoice_read into a FULL invoice projection (Phase 3 reporting-outbox
-- spike, Task 4b) so the invoices() LIST endpoint can be swapped off the cross-schema read of the
-- billing service's invoice table onto this same-schema read model, matching the 10 invoice METRIC
-- queries that already read it (Task 4).
--
-- Column types mirror services/billing-service/src/main/resources/db/migration/V1__billing_schema.sql
-- (table: superadmin_invoices) exactly for money/qty fields (BIGINT rate/amount/gst_amount, INTEGER
-- qty) so there is no precision loss; VARCHAR(255) source columns become TEXT here, consistent with
-- the existing id/status/school_id columns added in V9.
ALTER TABLE billing_invoice_read
    ADD COLUMN IF NOT EXISTS order_ref   TEXT,
    ADD COLUMN IF NOT EXISTS school      TEXT,
    ADD COLUMN IF NOT EXISTS description TEXT,
    ADD COLUMN IF NOT EXISTS qty         INTEGER,
    ADD COLUMN IF NOT EXISTS rate        BIGINT,
    ADD COLUMN IF NOT EXISTS amount      BIGINT,
    ADD COLUMN IF NOT EXISTS gst_amount  BIGINT,
    ADD COLUMN IF NOT EXISTS issued_at   TEXT,
    ADD COLUMN IF NOT EXISTS due_at      TEXT,
    ADD COLUMN IF NOT EXISTS notes       TEXT,
    ADD COLUMN IF NOT EXISTS created_at  TIMESTAMPTZ;
