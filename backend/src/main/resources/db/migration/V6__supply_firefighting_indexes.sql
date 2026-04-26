-- =============================================================
-- V6 – Supplemental indexes for Supply OS and Firefighting
-- V3 covers per-column indexes; this migration adds composite
-- and FK-support indexes for the query patterns introduced by
-- SupplyOrderService and FirefightingService.
-- =============================================================

-- ff_quotations: lookup by parent request (findByRequest_Code)
CREATE INDEX IF NOT EXISTS idx_ff_quotations_request
    ON ff_quotations (request_id);

-- catalog_orders: composite for school + status filter
CREATE INDEX IF NOT EXISTS idx_catalog_orders_school_status
    ON catalog_orders (school_id, status);

-- firefighting_requests: composite for school + status filter
CREATE INDEX IF NOT EXISTS idx_ff_requests_school_status
    ON firefighting_requests (school_id, status);

-- annual_plan_items: composite lookup
CREATE INDEX IF NOT EXISTS idx_annual_plan_school_year_cat
    ON annual_plan_items (school_id, academic_year_id, category);

-- audit_log: lookup by school or entity type
CREATE INDEX IF NOT EXISTS idx_audit_log_school
    ON audit_log (school_id);

CREATE INDEX IF NOT EXISTS idx_audit_log_entity
    ON audit_log (entity_type);
