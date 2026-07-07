-- Real delivery tracking: a fulfiller (superadmin/operations) marks an APPROVED order DELIVERED.
-- status is free-text VARCHAR, so 'DELIVERED' is a new value with no enum change or backfill.
ALTER TABLE catalog.catalog_orders
    ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS delivered_by BIGINT;
