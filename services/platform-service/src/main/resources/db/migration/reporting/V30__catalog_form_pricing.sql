ALTER TABLE reporting.fact_catalog_order
    ADD COLUMN IF NOT EXISTS form_version integer NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS pricing_status varchar(30) NOT NULL DEFAULT 'NOT_APPLICABLE',
    ADD COLUMN IF NOT EXISTS source_version bigint;

ALTER TABLE reporting.fact_catalog_order
    ADD CONSTRAINT fact_catalog_order_pricing_status_check
    CHECK (pricing_status IN ('NOT_APPLICABLE', 'PENDING_PRICING', 'QUOTED'));
