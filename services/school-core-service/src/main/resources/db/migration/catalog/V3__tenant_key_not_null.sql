-- Tenant-key hardening: catalog_orders and annual_plan_items already carry school_id
-- (set by the app) and tenant-leading indexes. Enforce NOT NULL. Fails loudly if any
-- legacy NULL remains (no FK source to backfill — these rows ARE the tenant row).
ALTER TABLE catalog.catalog_orders ALTER COLUMN school_id SET NOT NULL;
ALTER TABLE catalog.annual_plan_items ALTER COLUMN school_id SET NOT NULL;
