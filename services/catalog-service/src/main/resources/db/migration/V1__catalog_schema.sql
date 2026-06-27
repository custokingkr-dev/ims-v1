CREATE TABLE IF NOT EXISTS catalog_items (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255),
    subtitle VARCHAR(255),
    icon VARCHAR(255),
    order_type VARCHAR(255),
    sample_amount BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS supply_orders (
    code VARCHAR(255) PRIMARY KEY,
    title VARCHAR(255),
    category VARCHAR(255),
    items VARCHAR(255),
    amount BIGINT NOT NULL,
    status VARCHAR(255),
    order_date DATE,
    action_label VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS annual_plan_entries (
    id BIGSERIAL PRIMARY KEY,
    term_name VARCHAR(255),
    category VARCHAR(255),
    status VARCHAR(255),
    quantity VARCHAR(255),
    amount BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS catalog_orders (
    id VARCHAR(255) PRIMARY KEY,
    category VARCHAR(255) NOT NULL,
    order_data TEXT,
    subtotal BIGINT NOT NULL,
    gst BIGINT NOT NULL,
    total_amount BIGINT NOT NULL,
    status VARCHAR(255),
    class_group VARCHAR(255),
    logo_on_uniform VARCHAR(255),
    notebook_cover_logo VARCHAR(255),
    notebook_delivery_mode VARCHAR(255),
    notebook_spine_name VARCHAR(255),
    stationery_pack_type VARCHAR(255),
    event_name VARCHAR(255),
    event_date DATE,
    design_status VARCHAR(255),
    superadmin_approval_status VARCHAR(255),
    required_by_date DATE,
    estimated_delivery VARCHAR(255),
    placed_by BIGINT,
    placed_at TIMESTAMPTZ,
    notes VARCHAR(255),
    created_at TIMESTAMPTZ,
    school_id BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    vendor_paid_at TIMESTAMPTZ,
    vendor_paid_by BIGINT,
    vendor_payment_notes TEXT
);

CREATE TABLE IF NOT EXISTS annual_plan_items (
    id VARCHAR(255) PRIMARY KEY,
    term_name VARCHAR(255),
    category VARCHAR(255),
    description VARCHAR(255),
    quantity VARCHAR(255),
    estimated_amount BIGINT NOT NULL,
    status VARCHAR(255),
    linked_order_id VARCHAR(255),
    created_at TIMESTAMPTZ,
    school_id BIGINT,
    academic_year_id VARCHAR(255) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_catalog_orders_school ON catalog_orders(school_id);
CREATE INDEX IF NOT EXISTS idx_catalog_orders_status ON catalog_orders(status);
CREATE INDEX IF NOT EXISTS idx_catalog_orders_school_status ON catalog_orders(school_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_catalog_orders_school_created ON catalog_orders(school_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_catalog_orders_vendor_unpaid ON catalog_orders(school_id, status)
    WHERE vendor_paid_at IS NULL AND status IN ('APPROVED', 'FULFILLED');
CREATE INDEX IF NOT EXISTS idx_annual_plan_items_school_id ON annual_plan_items(school_id);
CREATE INDEX IF NOT EXISTS idx_annual_plan_school_year ON annual_plan_items(school_id, academic_year_id);
CREATE INDEX IF NOT EXISTS idx_annual_plan_school_year_cat ON annual_plan_items(school_id, academic_year_id, category);

DO $$
BEGIN
    IF to_regclass('public.catalog_items') IS NOT NULL THEN
    INSERT INTO catalog_items (id, title, subtitle, icon, order_type, sample_amount)
    SELECT id, title, subtitle, icon, order_type, sample_amount
    FROM public.catalog_items
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.supply_orders') IS NOT NULL THEN
    INSERT INTO supply_orders (code, title, category, items, amount, status, order_date, action_label)
    SELECT code, title, category, items, amount, status, order_date, action_label
    FROM public.supply_orders
    ON CONFLICT (code) DO NOTHING;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.annual_plan_entries') IS NOT NULL THEN
    INSERT INTO annual_plan_entries (id, term_name, category, status, quantity, amount)
    SELECT id, term_name, category, status, quantity, amount
    FROM public.annual_plan_entries
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.catalog_orders') IS NOT NULL THEN
    INSERT INTO catalog_orders
        (id, category, order_data, subtotal, gst, total_amount, status,
         class_group, logo_on_uniform, notebook_cover_logo, notebook_delivery_mode,
         notebook_spine_name, stationery_pack_type, event_name, event_date,
         design_status, superadmin_approval_status, required_by_date,
         estimated_delivery, placed_by, placed_at, notes, created_at, school_id,
         version, created_by, updated_by, vendor_paid_at, vendor_paid_by,
         vendor_payment_notes)
    SELECT id, category, order_data, subtotal, gst, total_amount, status,
           class_group, logo_on_uniform, notebook_cover_logo, notebook_delivery_mode,
           notebook_spine_name, stationery_pack_type, event_name, event_date,
           design_status, superadmin_approval_status, required_by_date,
           estimated_delivery, placed_by, placed_at, notes, created_at, school_id,
           version, created_by, updated_by, vendor_paid_at, vendor_paid_by,
           vendor_payment_notes
    FROM public.catalog_orders
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.annual_plan_items') IS NOT NULL THEN
    INSERT INTO annual_plan_items
        (id, term_name, category, description, quantity, estimated_amount,
         status, linked_order_id, created_at, school_id, academic_year_id)
    SELECT id, term_name, category, description, quantity, estimated_amount,
           status, linked_order_id, created_at, school_id, academic_year_id
    FROM public.annual_plan_items
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

SELECT setval(pg_get_serial_sequence('catalog_items', 'id'),
              COALESCE((SELECT max(id) FROM catalog_items), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('annual_plan_entries', 'id'),
              COALESCE((SELECT max(id) FROM annual_plan_entries), 0) + 1, false);
