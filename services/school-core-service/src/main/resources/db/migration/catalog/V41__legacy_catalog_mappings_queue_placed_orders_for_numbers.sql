-- Item order workflows design v1, D13 ("Contract release: retiring the rollout bridges"): every placed order of a category
-- with an order code ends with a number. The legacy catalog consolidation broke that. Catalog V8's
-- catalog.apply_legacy_catalog_mappings(), which an operator runs by hand (docs/operations/database-consolidation-runbook.md
-- §2), inserts each mapped catalog.supply_orders row into catalog_orders in its legacy status and category, and never
-- numbers it. A legacy 'Notebooks' order of status 'Placed' becomes a placed NOTEBOOKS order (code NB) with no number, for
-- good: V37 numbered only the orders placed before it, and V38's repair trigger sees only an update out of DRAFT, never
-- this insert.
--
-- So the function now queues each order it inserts that is placed (not DRAFT) and of a coded category for repair, exactly
-- as V38 step 2b queues one. The next drain of the creation queue mints its number, dated by its placement (the legacy
-- order date), tells reporting, and drops the row: a legacy order (form_version 1) runs on no workflow. Nothing else
-- changes: the same inserts, the same ledger updates, the same result row. CREATE OR REPLACE keeps the owner-only
-- privileges V8 set (no grant to PUBLIC or app_rt).
--
-- The repair column of catalog.order_workflow_creation_queue and NotebookTrackerCreationQueue's repair code must therefore
-- stay while this function exists, whatever happens to V38's placement trigger.
--
-- Orders this function migrated earlier are not re-queued here: V37 numbered every placed order of a coded category when it
-- ran; in dev, read-only on 2026-11-13, no placed order of a coded category lacked a number; prod runs V37 to this in one
-- migration job. A CREATE OR REPLACE FUNCTION takes no table lock.
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
    WITH inserted AS (
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
        ON CONFLICT (legacy_source, legacy_source_id) DO NOTHING
        RETURNING id, school_id, category, status
    ),
    queued AS (
        -- A placed order of a coded category is numbered by the creation queue's repair (V38 step 2b's rule).
        INSERT INTO catalog.order_workflow_creation_queue (order_id, school_id, repair)
        SELECT inserted.id, inserted.school_id, true
        FROM inserted
        JOIN catalog.product_categories pc ON pc.code = inserted.category AND pc.order_code IS NOT NULL
        WHERE inserted.status <> 'DRAFT'
        ON CONFLICT (order_id) DO UPDATE SET repair = true
        RETURNING order_id
    )
    SELECT count(*) INTO v_supply FROM inserted;

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

COMMENT ON FUNCTION catalog.apply_legacy_catalog_mappings() IS
    'Owner-operated (docs/operations/database-consolidation-runbook.md §2). Since catalog V41 it queues each placed order of a '
    'category with an order code that it inserts for repair (order_workflow_creation_queue.repair), so the creation queue '
    'numbers it (design D13). Keep the repair column and the repair code while this function exists.';
