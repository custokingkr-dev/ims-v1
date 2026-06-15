-- V127: Composite index on catalog_orders(school_id, created_at DESC)
-- Supports the paginated listCatalogOrders query:
--   WHERE school_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?
-- Without this, Postgres performs a full table scan + sort on every page request.

CREATE INDEX IF NOT EXISTS idx_catalog_orders_school_created
    ON catalog_orders (school_id, created_at DESC);

-- Also index firefighting_requests for the same pagination pattern
CREATE INDEX IF NOT EXISTS idx_ff_requests_school_created
    ON firefighting_requests (school_id, created_at DESC);
