-- V133: Add vendor payment tracking columns to catalog_orders and firefighting_requests.
-- When a school's catalog order or firefighting request is APPROVED, the school owes
-- payment to the vendor. These columns track when that payment was recorded.

ALTER TABLE catalog_orders
    ADD COLUMN IF NOT EXISTS vendor_paid_at        TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS vendor_paid_by        BIGINT REFERENCES app_users(id),
    ADD COLUMN IF NOT EXISTS vendor_payment_notes  TEXT;

ALTER TABLE firefighting_requests
    ADD COLUMN IF NOT EXISTS vendor_paid_at        TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS vendor_paid_by        BIGINT REFERENCES app_users(id),
    ADD COLUMN IF NOT EXISTS vendor_payment_notes  TEXT;

CREATE INDEX IF NOT EXISTS idx_catalog_orders_vendor_unpaid
    ON catalog_orders(school_id, status)
    WHERE vendor_paid_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_ff_requests_vendor_unpaid
    ON firefighting_requests(school_id, status)
    WHERE vendor_paid_at IS NULL;
