ALTER TABLE catalog.catalog_orders
    DROP CONSTRAINT IF EXISTS catalog_orders_school_id_fkey;

ALTER TABLE catalog.annual_plan_items
    DROP CONSTRAINT IF EXISTS annual_plan_items_school_id_fkey,
    DROP CONSTRAINT IF EXISTS annual_plan_items_academic_year_id_fkey;
