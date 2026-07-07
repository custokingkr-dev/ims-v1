-- Adds notes to reporting.fact_catalog_order so the catalog-approvals read can later be
-- decoupled from school-core's catalog.catalog_orders table (order notes are part of what the
-- superadmin-approvals view surfaces today).
ALTER TABLE reporting.fact_catalog_order ADD COLUMN notes TEXT;
