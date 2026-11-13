-- Order numbers (item order workflows design v1, D8, D10, D11, D13): <ItemCode>-<SchoolOrderCode>-<YY-MM-DD>-<NNNN>,
-- for example NB-DPS_GUR-26-11-13-0042. The CK id stays the key everywhere; the number is a separate, immutable field that
-- people see. Runs after tenant_school (the per-schema Flyway beans are chained), so schools.order_code exists.

-- 1. Per category: the item code in order numbers, the noun of the introduction message, and the workflow template that a
--    placement starts. Null code: no order number. Null template: no workflow (the order keeps approve, quote, deliver).
ALTER TABLE catalog.product_categories
    ADD COLUMN order_code VARCHAR(4) CONSTRAINT ck_product_categories_order_code CHECK (order_code ~ '^[A-Z]{2,4}$'),
    ADD COLUMN item_noun VARCHAR(40),
    ADD COLUMN workflow_definition VARCHAR(100) CONSTRAINT ck_product_categories_workflow_definition
        CHECK (workflow_definition IN ('NOTEBOOK_ORDER_TRACKER', 'FLIER_ORDER_WORKFLOW', 'FLEX_ORDER_WORKFLOW',
                                       'BILL_BOOK_ORDER_WORKFLOW', 'GENERAL_ITEM_ORDER_WORKFLOW')),
    ADD CONSTRAINT ck_product_categories_workflow_needs_code CHECK (workflow_definition IS NULL OR order_code IS NOT NULL);

CREATE UNIQUE INDEX uk_product_categories_order_code ON catalog.product_categories (order_code) WHERE order_code IS NOT NULL;

UPDATE catalog.product_categories AS category
SET order_code = seed.order_code, item_noun = seed.item_noun, workflow_definition = seed.workflow_definition
FROM (VALUES
    ('NOTEBOOKS',    'NB',  'notebook',     'NOTEBOOK_ORDER_TRACKER'),
    ('FLIERS',       'FE',  'flier',        'FLIER_ORDER_WORKFLOW'),
    ('FLEX',         'FX',  'flex',         'FLEX_ORDER_WORKFLOW'),
    ('BILLBOOKS',    'BBK', 'bill book',    'BILL_BOOK_ORDER_WORKFLOW'),
    ('CERTIFICATES', 'CF',  'certificate',  'GENERAL_ITEM_ORDER_WORKFLOW'),
    ('FLUTE_BOARDS', 'FB',  'flute board',  'GENERAL_ITEM_ORDER_WORKFLOW'),
    ('REPORT_CARDS', 'RC',  'report card',  'GENERAL_ITEM_ORDER_WORKFLOW'),
    ('BROCHURES',    'BCH', 'brochure',     'GENERAL_ITEM_ORDER_WORKFLOW'),
    ('BELTS',        'BLT', 'belt',         'GENERAL_ITEM_ORDER_WORKFLOW'),
    ('STANDEES',     'STD', 'standee',      'GENERAL_ITEM_ORDER_WORKFLOW'),
    ('TIES',         'TIE', 'tie',          NULL),
    ('TROPHIES',     'TRP', 'trophy',       NULL)
) AS seed(code, order_code, item_noun, workflow_definition)
WHERE category.code = seed.code;

-- 2. The number on the order: null for drafts and for categories without a code, minted once at placement, never changed.
ALTER TABLE catalog.catalog_orders ADD COLUMN order_number VARCHAR(40);
CREATE UNIQUE INDEX uk_catalog_orders_order_number ON catalog.catalog_orders (order_number) WHERE order_number IS NOT NULL;

CREATE FUNCTION catalog.refuse_order_number_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'The order number of % is immutable', OLD.id USING ERRCODE = 'check_violation';
END $$;

CREATE TRIGGER trg_catalog_orders_order_number_immutable
    BEFORE UPDATE ON catalog.catalog_orders
    FOR EACH ROW
    WHEN (OLD.order_number IS NOT NULL AND NEW.order_number IS DISTINCT FROM OLD.order_number)
    EXECUTE FUNCTION catalog.refuse_order_number_change();

-- 3. The running number: per item code per calendar year of the placement date, taken with a single-row
--    INSERT ... ON CONFLICT DO UPDATE ... RETURNING (never MAX()+1, the lesson of V5). It holds no tenant data, so it has no
--    row-level security policy.
CREATE TABLE catalog.order_number_counters (
    item_code  VARCHAR(4) NOT NULL CHECK (item_code ~ '^[A-Z]{2,4}$'),
    year       SMALLINT   NOT NULL CHECK (year BETWEEN 2000 AND 2999),
    last_value INTEGER    NOT NULL CHECK (last_value >= 0),
    PRIMARY KEY (item_code, year)
);

-- Exactly SELECT, INSERT and UPDATE: a counter row is never deleted, which would restart its numbers. The default privileges
-- that scripts/create-app-rt-role.sql sets would also hand app_rt DELETE on this new table, so that is taken back here.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        GRANT SELECT, INSERT, UPDATE ON catalog.order_number_counters TO app_rt;
        REVOKE DELETE, TRUNCATE ON catalog.order_number_counters FROM app_rt;
    END IF;
END $$;

-- 4. Backfill (D13): every placed order of a coded category, in placement order per item code and calendar year, dated in
--    the school's time zone; then the counters continue from there; then reporting is told the numbers through the
--    ordinary catalog-order.upserted.v1 event (the same payload the application emits, at the order's current version).
--    Guarded with to_regclass, as tenant_school V11 is: SchoolCoreFlywayConfig always runs tenant_school first, but a
--    database holding the catalog schema alone (a catalog-only test) has no schools to number orders by.
DO $$
BEGIN
    IF to_regclass('tenant_school.schools') IS NULL OR to_regclass('tenant_school.outbox_events') IS NULL THEN
        RETURN;
    END IF;
    PERFORM set_config('app.bypass_rls', 'on', true);

    CREATE TEMPORARY TABLE numbered_orders ON COMMIT DROP AS
    WITH placed AS (
        SELECT co.id, pc.order_code AS item_code, s.order_code AS school_code,
               COALESCE(co.placed_at, co.created_at) AS placed,
               (COALESCE(co.placed_at, co.created_at) AT TIME ZONE s.time_zone)::date AS local_day
        FROM catalog.catalog_orders co
        JOIN catalog.product_categories pc ON pc.code = co.category AND pc.order_code IS NOT NULL
        JOIN tenant_school.schools s ON s.id = co.school_id
        WHERE co.status <> 'DRAFT' AND co.order_number IS NULL
          AND COALESCE(co.placed_at, co.created_at) IS NOT NULL
    )
    SELECT id, item_code, school_code, local_day, EXTRACT(YEAR FROM local_day)::smallint AS year,
           row_number() OVER (PARTITION BY item_code, EXTRACT(YEAR FROM local_day) ORDER BY placed, id) AS counter
    FROM placed;

    UPDATE catalog.catalog_orders co
    SET order_number = n.item_code || '-' || n.school_code || '-' || to_char(n.local_day, 'YY-MM-DD') || '-'
                       || CASE WHEN n.counter < 10000 THEN lpad(n.counter::text, 4, '0') ELSE n.counter::text END
    FROM numbered_orders n
    WHERE co.id = n.id;

    INSERT INTO catalog.order_number_counters (item_code, year, last_value)
    SELECT item_code, year, max(counter) FROM numbered_orders GROUP BY item_code, year
    ON CONFLICT (item_code, year) DO UPDATE
        SET last_value = GREATEST(catalog.order_number_counters.last_value, EXCLUDED.last_value);

    INSERT INTO tenant_school.outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
    SELECT 'CatalogOrderUpserted:' || co.id, 'catalog-order.upserted.v1', 'CatalogOrder', co.id, co.school_id,
           jsonb_build_object(
               'id', co.id,
               'schoolId', co.school_id,
               'category', co.category,
               'status', co.status,
               'totalAmount', co.total_amount,
               'superadminApprovalStatus', co.superadmin_approval_status,
               'vendorPaidAt', co.vendor_paid_at,
               'createdAt', co.created_at,
               'requiredByDate', co.required_by_date,
               'designStatus', co.design_status,
               'notes', co.notes,
               'formVersion', co.form_version,
               'pricingStatus', co.pricing_status,
               'version', co.version,
               'orderNumber', co.order_number,
               'itemCode', n.item_code)
    FROM catalog.catalog_orders co JOIN numbered_orders n ON n.id = co.id;
END $$;
