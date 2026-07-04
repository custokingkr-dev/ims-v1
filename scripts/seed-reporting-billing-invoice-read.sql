-- One-time cutover seed for Phase 3 Task 3.1 (billing -> reporting event-driven).
-- Pre-creates reporting.billing_invoice_read (exact V9+V10 DDL, idempotent) so the read model
-- exists+populated BEFORE platform's read-swap deploys (avoids a blank-reporting window). Platform's
-- Flyway then idempotently re-applies + records V9/V10. The direct copy is a migration-style one-off,
-- NOT a runtime cross-schema read; the live outbox->Pub/Sub->projection pipeline keeps it fresh after.
\pset pager off
CREATE TABLE IF NOT EXISTS reporting.billing_invoice_read (
    id TEXT PRIMARY KEY, school_id BIGINT, status TEXT, total NUMERIC,
    occurred_at TIMESTAMPTZ, updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_billing_invoice_read_school_status ON reporting.billing_invoice_read (school_id, status);
ALTER TABLE reporting.billing_invoice_read
    ADD COLUMN IF NOT EXISTS order_ref TEXT, ADD COLUMN IF NOT EXISTS school TEXT,
    ADD COLUMN IF NOT EXISTS description TEXT, ADD COLUMN IF NOT EXISTS qty INTEGER,
    ADD COLUMN IF NOT EXISTS rate BIGINT, ADD COLUMN IF NOT EXISTS amount BIGINT,
    ADD COLUMN IF NOT EXISTS gst_amount BIGINT, ADD COLUMN IF NOT EXISTS issued_at TEXT,
    ADD COLUMN IF NOT EXISTS due_at TEXT, ADD COLUMN IF NOT EXISTS notes TEXT,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ;
\echo '== source invoices =='
SELECT count(*) AS billing_invoices FROM billing.superadmin_invoices;
INSERT INTO reporting.billing_invoice_read
  (id, school_id, status, total, order_ref, school, description, qty, rate, amount, gst_amount,
   issued_at, due_at, notes, created_at, occurred_at, updated_at)
SELECT id, school_id, status, total, order_ref, school, description, qty, rate, amount, gst_amount,
   issued_at, due_at, notes, created_at, created_at, now()
FROM billing.superadmin_invoices
ON CONFLICT (id) DO UPDATE SET
  school_id=EXCLUDED.school_id, status=EXCLUDED.status, total=EXCLUDED.total, order_ref=EXCLUDED.order_ref,
  school=EXCLUDED.school, description=EXCLUDED.description, qty=EXCLUDED.qty, rate=EXCLUDED.rate,
  amount=EXCLUDED.amount, gst_amount=EXCLUDED.gst_amount, issued_at=EXCLUDED.issued_at,
  due_at=EXCLUDED.due_at, notes=EXCLUDED.notes, created_at=EXCLUDED.created_at,
  occurred_at=EXCLUDED.occurred_at, updated_at=now();
\echo '== seeded read model rows =='
SELECT count(*) AS read_model_rows FROM reporting.billing_invoice_read;
