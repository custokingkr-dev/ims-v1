ALTER TABLE catalog.catalog_order_assets
    DROP CONSTRAINT IF EXISTS catalog_order_assets_size_bytes_check;
ALTER TABLE catalog.catalog_order_assets
    ADD CONSTRAINT catalog_order_assets_size_bytes_check
    CHECK (size_bytes > 0 AND size_bytes <= 26214400);
