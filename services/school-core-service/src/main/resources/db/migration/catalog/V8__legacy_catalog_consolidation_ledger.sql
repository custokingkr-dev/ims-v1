-- Legacy supply_orders and annual_plan_entries predate tenant ownership, so they
-- cannot be merged safely until an operator explicitly maps each row to a school
-- (and, for annual plans, an academic year). This migration creates that durable,
-- auditable mapping boundary and canonical provenance; it never guesses a tenant.

ALTER TABLE catalog.catalog_orders
    ADD COLUMN IF NOT EXISTS legacy_source VARCHAR(64),
    ADD COLUMN IF NOT EXISTS legacy_source_id VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS uq_catalog_orders_legacy_source
    ON catalog.catalog_orders (legacy_source, legacy_source_id);

ALTER TABLE catalog.annual_plan_items
    ADD COLUMN IF NOT EXISTS legacy_source VARCHAR(64),
    ADD COLUMN IF NOT EXISTS legacy_source_id VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS uq_annual_plan_items_legacy_source
    ON catalog.annual_plan_items (legacy_source, legacy_source_id);

CREATE TABLE IF NOT EXISTS catalog.legacy_catalog_migration_map (
    source_table     VARCHAR(32)  NOT NULL,
    source_id        VARCHAR(255) NOT NULL,
    school_id        BIGINT,
    academic_year_id VARCHAR(255),
    mapped_by        VARCHAR(255),
    mapped_at        TIMESTAMPTZ,
    migrated_id      VARCHAR(255),
    migrated_at      TIMESTAMPTZ,
    notes            TEXT,
    PRIMARY KEY (source_table, source_id),
    CONSTRAINT ck_legacy_catalog_source
        CHECK (source_table IN ('supply_orders', 'annual_plan_entries')),
    CONSTRAINT ck_legacy_catalog_mapping_complete
        CHECK (migrated_at IS NULL OR (school_id IS NOT NULL AND migrated_id IS NOT NULL)),
    CONSTRAINT ck_legacy_plan_year_mapping
        CHECK (source_table <> 'annual_plan_entries' OR migrated_at IS NULL OR academic_year_id IS NOT NULL)
);

INSERT INTO catalog.legacy_catalog_migration_map (source_table, source_id)
SELECT 'supply_orders', code
FROM catalog.supply_orders
ON CONFLICT (source_table, source_id) DO NOTHING;

INSERT INTO catalog.legacy_catalog_migration_map (source_table, source_id)
SELECT 'annual_plan_entries', id::text
FROM catalog.annual_plan_entries
ON CONFLICT (source_table, source_id) DO NOTHING;

CREATE OR REPLACE VIEW catalog.legacy_catalog_migration_readiness
AS
SELECT
    mapping.source_table,
    count(*) AS source_rows,
    count(*) FILTER (WHERE mapping.school_id IS NOT NULL) AS school_mapped_rows,
    count(*) FILTER (
        WHERE mapping.school_id IS NOT NULL
          AND (mapping.source_table <> 'annual_plan_entries' OR mapping.academic_year_id IS NOT NULL)
    ) AS ready_rows,
    count(*) FILTER (WHERE mapping.migrated_at IS NOT NULL) AS migrated_rows,
    count(*) FILTER (WHERE mapping.school_id IS NULL) AS school_mapping_required_rows,
    count(*) FILTER (
        WHERE mapping.source_table = 'annual_plan_entries'
          AND mapping.school_id IS NOT NULL
          AND mapping.academic_year_id IS NULL
    ) AS academic_year_mapping_required_rows
FROM catalog.legacy_catalog_migration_map mapping
GROUP BY mapping.source_table;

CREATE OR REPLACE FUNCTION catalog.apply_legacy_catalog_mappings()
RETURNS TABLE (
    migrated_supply_rows BIGINT,
    migrated_annual_plan_rows BIGINT,
    remaining_unmapped_rows BIGINT
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_supply BIGINT;
    v_plans BIGINT;
    v_remaining BIGINT;
BEGIN
    INSERT INTO catalog.catalog_orders
        (id, school_id, category, order_data, subtotal, gst, total_amount, status,
         placed_at, created_at, notes, version, legacy_source, legacy_source_id)
    SELECT
        'LEGACY-SUPPLY-' || md5(source.code),
        mapping.school_id,
        upper(COALESCE(NULLIF(btrim(source.category), ''), 'STATIONERY')),
        jsonb_build_object(
            'legacyTitle', source.title,
            'legacyItems', source.items,
            'legacyActionLabel', source.action_label
        )::text,
        source.amount,
        0,
        source.amount,
        upper(COALESCE(NULLIF(btrim(source.status), ''), 'DRAFT')),
        source.order_date::timestamptz,
        COALESCE(source.order_date::timestamptz, now()),
        'Migrated from legacy supply_orders',
        0,
        'supply_orders',
        source.code
    FROM catalog.supply_orders source
    JOIN catalog.legacy_catalog_migration_map mapping
      ON mapping.source_table = 'supply_orders'
     AND mapping.source_id = source.code
    WHERE mapping.school_id IS NOT NULL
      AND mapping.migrated_at IS NULL
    ON CONFLICT (legacy_source, legacy_source_id) DO NOTHING;
    GET DIAGNOSTICS v_supply = ROW_COUNT;

    INSERT INTO catalog.annual_plan_items
        (id, school_id, academic_year_id, term_name, category, description,
         quantity, estimated_amount, status, created_at, legacy_source, legacy_source_id)
    SELECT
        'LEGACY-PLAN-' || md5(source.id::text),
        mapping.school_id,
        mapping.academic_year_id,
        source.term_name,
        COALESCE(NULLIF(btrim(source.category), ''), 'STATIONERY'),
        COALESCE(NULLIF(btrim(source.category), ''), 'Legacy annual plan item'),
        source.quantity,
        source.amount,
        upper(COALESCE(NULLIF(btrim(source.status), ''), 'PLANNED')),
        now(),
        'annual_plan_entries',
        source.id::text
    FROM catalog.annual_plan_entries source
    JOIN catalog.legacy_catalog_migration_map mapping
      ON mapping.source_table = 'annual_plan_entries'
     AND mapping.source_id = source.id::text
    WHERE mapping.school_id IS NOT NULL
      AND mapping.academic_year_id IS NOT NULL
      AND mapping.migrated_at IS NULL
    ON CONFLICT (legacy_source, legacy_source_id) DO NOTHING;
    GET DIAGNOSTICS v_plans = ROW_COUNT;

    UPDATE catalog.legacy_catalog_migration_map mapping
    SET migrated_id = canonical.id,
        migrated_at = COALESCE(mapping.migrated_at, now())
    FROM catalog.catalog_orders canonical
    WHERE mapping.source_table = 'supply_orders'
      AND canonical.legacy_source = mapping.source_table
      AND canonical.legacy_source_id = mapping.source_id;

    UPDATE catalog.legacy_catalog_migration_map mapping
    SET migrated_id = canonical.id,
        migrated_at = COALESCE(mapping.migrated_at, now())
    FROM catalog.annual_plan_items canonical
    WHERE mapping.source_table = 'annual_plan_entries'
      AND canonical.legacy_source = mapping.source_table
      AND canonical.legacy_source_id = mapping.source_id;

    SELECT count(*) INTO v_remaining
    FROM catalog.legacy_catalog_migration_map
    WHERE school_id IS NULL
       OR (source_table = 'annual_plan_entries' AND academic_year_id IS NULL);

    RETURN QUERY SELECT v_supply, v_plans, v_remaining;
END
$$;
-- The ledger and migration function are owner-operated controls, not application APIs.
REVOKE ALL ON TABLE catalog.legacy_catalog_migration_map FROM PUBLIC;
REVOKE ALL ON FUNCTION catalog.apply_legacy_catalog_mappings() FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        REVOKE ALL ON TABLE catalog.legacy_catalog_migration_map FROM app_rt;
        REVOKE ALL ON FUNCTION catalog.apply_legacy_catalog_mappings() FROM app_rt;
    END IF;
END
$$;
