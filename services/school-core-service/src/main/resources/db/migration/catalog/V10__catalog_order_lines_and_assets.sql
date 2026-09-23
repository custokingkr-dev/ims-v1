ALTER TABLE catalog.catalog_orders
    ADD COLUMN form_version INT NOT NULL DEFAULT 1,
    ADD COLUMN order_selections JSONB,
    ADD COLUMN form_snapshot JSONB,
    ADD COLUMN pricing_status VARCHAR(30) NOT NULL DEFAULT 'NOT_APPLICABLE',
    ADD COLUMN quoted_at TIMESTAMPTZ,
    ADD COLUMN quoted_by BIGINT,
    ADD COLUMN quantity_rule_results JSONB,
    ADD COLUMN approved_design_asset_id BIGINT,
    ADD CONSTRAINT uq_catalog_order_school UNIQUE (id, school_id),
    ADD CONSTRAINT ck_catalog_pricing_status CHECK (pricing_status IN ('NOT_APPLICABLE','PENDING_PRICING','QUOTED'));

CREATE TABLE catalog.catalog_order_lines (
    id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(255) NOT NULL,
    school_id BIGINT NOT NULL,
    line_no INT NOT NULL CHECK (line_no > 0),
    option_selections JSONB NOT NULL,
    requested_book_count INT NOT NULL CHECK (requested_book_count > 0),
    book_count INT NOT NULL CHECK (book_count > 0),
    requested_page_count INT NOT NULL CHECK (requested_page_count > 0),
    page_count INT NOT NULL CHECK (page_count > 0),
    applied_rules JSONB NOT NULL DEFAULT '[]',
    unit_price_paise BIGINT CHECK (unit_price_paise >= 0),
    line_total_paise BIGINT CHECK (line_total_paise >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    FOREIGN KEY (order_id, school_id) REFERENCES catalog.catalog_orders(id, school_id) ON DELETE CASCADE,
    UNIQUE (school_id, order_id, line_no)
);

CREATE TABLE catalog.catalog_order_assets (
    id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(255) NOT NULL,
    school_id BIGINT NOT NULL,
    asset_kind VARCHAR(30) NOT NULL CHECK (asset_kind IN ('DESIGN','PRE_DELIVERY_PHOTO')),
    storage_key VARCHAR(500) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 5242880),
    checksum_sha256 CHAR(64) NOT NULL,
    original_filename VARCHAR(200) NOT NULL,
    uploaded_by BIGINT,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    superseded_at TIMESTAMPTZ,
    FOREIGN KEY (order_id, school_id) REFERENCES catalog.catalog_orders(id, school_id) ON DELETE CASCADE,
    UNIQUE (id, order_id, school_id)
);
CREATE UNIQUE INDEX idx_catalog_order_current_assets ON catalog.catalog_order_assets(school_id, order_id, asset_kind)
    WHERE superseded_at IS NULL;
ALTER TABLE catalog.catalog_orders ADD CONSTRAINT fk_catalog_approved_design
    FOREIGN KEY (approved_design_asset_id, id, school_id)
    REFERENCES catalog.catalog_order_assets(id, order_id, school_id) DEFERRABLE INITIALLY DEFERRED;

DO $$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['catalog_order_lines','catalog_order_assets'] LOOP
        EXECUTE format('ALTER TABLE catalog.%I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE catalog.%I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format($policy$
            CREATE POLICY tenant_isolation ON catalog.%I
            USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
                OR current_setting('app.bypass_rls', true) = 'on'
                OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]))
            WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
                OR current_setting('app.bypass_rls', true) = 'on'
                OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]))
        $policy$, table_name);
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.catalog_order_lines, catalog.catalog_order_assets TO app_rt;
        GRANT USAGE, SELECT ON SEQUENCE catalog.catalog_order_lines_id_seq, catalog.catalog_order_assets_id_seq TO app_rt;
    END IF;
END $$;
