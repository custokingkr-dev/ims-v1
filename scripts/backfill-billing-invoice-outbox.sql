-- =============================================================================
-- backfill-billing-invoice-outbox.sql
--
-- One-off PROD backfill for the reporting-outbox spike (Phase 3, Task 3.1).
--
-- Purpose: seed the reporting read model (`reporting.billing_invoice_read`,
-- projected by `platform-service`'s ReportingEventInboxProcessor) for invoices
-- that already existed BEFORE the outbox writer went live. It does this by
-- emitting one `billing.invoice-upserted.v1` outbox row per existing invoice
-- in `billing.superadmin_invoices` -- NOT by copying rows cross-schema. The
-- existing pipeline (OutboxRelay -> Pub/Sub -> reporting event inbox ->
-- ReportingEventInboxProcessor.projectBillingInvoice) then does the real
-- projection, exactly as it does for new/updated invoices going forward.
--
-- Idempotent: guarded by `NOT EXISTS` on `event_key = 'InvoiceUpserted:' || id`,
-- which is the same event_key BillingInvoiceService.appendInvoiceOutbox uses
-- for create/update. Safe to run multiple times; safe to run after the
-- pipeline has already picked up some rows (those already have a matching
-- event_key and are skipped).
--
-- Payload shape and JSON keys MUST match exactly what
-- BillingInvoiceService.appendInvoiceOutbox (services/billing-service/src/main/java/
-- com/custoking/ims/billingservice/application/BillingInvoiceService.java) builds,
-- because ReportingEventInboxProcessor.projectBillingInvoice (services/platform-service/
-- src/main/java/com/custoking/ims/platformservice/application/ReportingEventInboxProcessor.java)
-- reads these exact keys: id, orderRef, school, schoolId, description, qty, rate,
-- amount, gstAmount, total, status, issuedAt, dueAt, notes, createdAt. A key
-- mismatch would silently project nulls (see findings doc hardening item on
-- malformed-payload handling).
--
-- Run in the billing schema (this service's own DB/schema), e.g. via the
-- Cloud Run one-off psql job / `gcloud sql connect` against the billing
-- Cloud SQL instance:
--
--   psql "$BILLING_DB_URL" -f scripts/backfill-billing-invoice-outbox.sql
--
-- After running, the OutboxRelay (fixed-delay ${billing.outbox.relay.fixed-delay-ms:10000})
-- picks up the newly inserted rows on its next tick and publishes them; the
-- reporting event-projection scheduler
-- (${reporting.event-projection.fixed-delay-ms:10000}) then projects them into
-- `reporting.billing_invoice_read`. No manual intervention needed after this
-- script runs beyond letting both schedulers tick.
-- =============================================================================

INSERT INTO outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
SELECT
    'InvoiceUpserted:' || i.id,
    'billing.invoice-upserted.v1',
    'SuperadminInvoice',
    i.id::text,
    i.school_id,
    jsonb_build_object(
        'id',          i.id,
        'orderRef',    i.order_ref,
        'school',      i.school,
        'schoolId',    i.school_id,
        'description', i.description,
        'qty',         i.qty,
        'rate',        i.rate,
        'amount',      i.amount,
        'gstAmount',   i.gst_amount,
        'total',       i.total,
        'status',      i.status,
        'issuedAt',    i.issued_at,
        'dueAt',       i.due_at,
        'notes',       i.notes,
        'createdAt',   i.created_at
    )
FROM superadmin_invoices i
WHERE NOT EXISTS (
    SELECT 1 FROM outbox_events o WHERE o.event_key = 'InvoiceUpserted:' || i.id
);
