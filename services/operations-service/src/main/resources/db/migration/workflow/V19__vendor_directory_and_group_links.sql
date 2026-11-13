-- Item order workflows (design v1 §3.1, D31-D34). The vendor directory replaces the four hard-coded categories and twelve
-- placeholder vendors (VendorDirectory.java, the V15/V16 key CHECK, the frontend and contract copies).
--   * workflow_vendor_categories: the SOP's 18 categories. Codes are stable; superadmin may rename or deactivate one.
--   * workflow_vendors: Custoking's suppliers, global like workflow_designers (a vendor can serve every school). Each may
--     carry its own WhatsApp group invite. The prototype's twelve vendors are seeded as sample rows without phones,
--     keeping their old ids in legacy_key so existing per-order selections stay valid.
--   * workflow_vendor_category_members: a vendor belongs to one or more categories.
--   * workflow_settings: global settings; today only the internal approval group's invite (D34).
--   * workflow_school_item_group_links: a school's group for one item, which overrides its school-wide group (D34).
-- The system only stores invite links and opens them; it sends nothing.
-- Owner backfills on forced-RLS tables need the deliberate bypass (V7).
SET LOCAL app.bypass_rls = 'on';

-- 1. Categories (global: no tenant key and no RLS) -------------------------------------------------------------------
CREATE TABLE workflow.workflow_vendor_categories (
    code        VARCHAR(40)  PRIMARY KEY CONSTRAINT ck_wvc_code CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    name        VARCHAR(100) NOT NULL CONSTRAINT ck_wvc_name CHECK (btrim(name) <> ''),
    sort_order  SMALLINT     NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT true,
    updated_by  BIGINT,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO workflow.workflow_vendor_categories (code, name, sort_order) VALUES
    ('BOARD_SUPPLIERS',      'Board Suppliers',                  1),
    ('LAMINATION_VENDORS',   'Lamination Vendors',               2),
    ('BINDERS',              'Binders',                          3),
    ('OFFSET_PRINTERS',      'Offset Printers',                  4),
    ('DIGITAL_PRINTERS',     'Digital Printers',                 5),
    ('FLEX_PRINTERS',        'Flex Printers',                    6),
    ('PHOTO_PAPER_PRINTERS', 'Photo Paper Printers',             7),
    ('BILL_BOOK_MAKERS',     'Bill Book Makers',                 8),
    ('BATCH_MAKERS',         'Batch Makers',                     9),
    ('FLUTE_BOARD_MAKERS',   'Flute Board Makers',              10),
    ('TROPHIES_MEDALS',      'Trophies & Medals',               11),
    ('FILE_MAKERS',          'File Makers',                     12),
    ('SASHES',               'Sashes',                          13),
    ('DIARIES',              'Diaries',                         14),
    ('ID_CARDS',             'ID Cards',                        15),
    ('FLAPS',                'Flaps',                           16),
    ('GIFTING',              'Gifting Item Vendors & Printers', 17),
    ('NON_WOVEN_BAGS',       'Non-Woven Bag Vendors',           18);

-- 2. Vendors (global) ------------------------------------------------------------------------------------------------
CREATE TABLE workflow.workflow_vendors (
    id                 BIGSERIAL     PRIMARY KEY,
    name               VARCHAR(200)  NOT NULL CONSTRAINT ck_wv_name CHECK (btrim(name) <> ''),
    contact_person     VARCHAR(200),
    phone              VARCHAR(32)   CONSTRAINT ck_wv_phone CHECK (phone IS NULL OR btrim(phone) <> ''),
    alt_phone          VARCHAR(32)   CONSTRAINT ck_wv_alt_phone CHECK (alt_phone IS NULL OR btrim(alt_phone) <> ''),
    email              VARCHAR(254),
    city               VARCHAR(100),
    whatsapp_group_url VARCHAR(2048) CONSTRAINT ck_wv_whatsapp_group_url
                       CHECK (whatsapp_group_url ~ '^https://chat\.whatsapp\.com/[A-Za-z0-9_-]{10,64}$'),
    notes              VARCHAR(500),
    sample             BOOLEAN       NOT NULL DEFAULT false,
    legacy_key         VARCHAR(64)   CONSTRAINT uk_wv_legacy_key UNIQUE,
    active             BOOLEAN       NOT NULL DEFAULT true,
    created_by         BIGINT,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by         BIGINT,
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- A real vendor is reached by phone; only the prototype's sample vendors have none (D31).
    CONSTRAINT ck_wv_phone_required CHECK (sample OR phone IS NOT NULL)
);

CREATE TABLE workflow.workflow_vendor_category_members (
    vendor_id     BIGINT      NOT NULL REFERENCES workflow.workflow_vendors (id) ON DELETE CASCADE,
    category_code VARCHAR(40) NOT NULL REFERENCES workflow.workflow_vendor_categories (code),
    PRIMARY KEY (vendor_id, category_code)
);
-- "The vendors of a category", for the stage cards and the directory screen.
CREATE INDEX idx_wvcm_category ON workflow.workflow_vendor_category_members (category_code, vendor_id);

-- The prototype's twelve vendors, in the order VendorDirectory listed them.
INSERT INTO workflow.workflow_vendors (name, sample, legacy_key, notes)
SELECT v.name, true, v.legacy_key, 'Sample vendor from the client prototype. Replace or deactivate it.'
FROM (VALUES
    ( 1, 'board_shree',      'Shree Board Traders'),
    ( 2, 'board_national',   'National Boards Co.'),
    ( 3, 'board_prime',      'Prime Board Suppliers'),
    ( 4, 'lam_glosspro',     'GlossPro Laminations'),
    ( 5, 'lam_crystal',      'Crystal Lamination Works'),
    ( 6, 'lam_brightfinish', 'BrightFinish Laminators'),
    ( 7, 'binder_perfect',   'Perfect Bindery'),
    ( 8, 'binder_classic',   'Classic Notebook Binding'),
    ( 9, 'binder_metro',     'Metro Binding House'),
    (10, 'offset_metro',     'Metro Offset Printers'),
    (11, 'offset_precision', 'Precision Print House'),
    (12, 'offset_cityline',  'Cityline Offset Works')
) AS v(position, legacy_key, name)
ORDER BY v.position;

INSERT INTO workflow.workflow_vendor_category_members (vendor_id, category_code)
SELECT v.id, CASE split_part(v.legacy_key, '_', 1)
                 WHEN 'board' THEN 'BOARD_SUPPLIERS'
                 WHEN 'lam' THEN 'LAMINATION_VENDORS'
                 WHEN 'binder' THEN 'BINDERS'
                 WHEN 'offset' THEN 'OFFSET_PRINTERS'
             END
FROM workflow.workflow_vendors v
WHERE v.sample AND v.legacy_key IS NOT NULL;

-- 3. Per-order selections: {"board": ["board_shree"]} becomes {"BOARD_SUPPLIERS": [<vendor id>]} ----------------------
-- The old service accepted only VendorDirectory's categories and ids, so every entry maps. An entry that does not is not
-- dropped: the migration stops and names it, because silently losing an operator's selection is worse than a failed
-- deploy (V6: never delete history to make a migration pass).
DO $$
DECLARE unmapped TEXT;
BEGIN
    SELECT string_agg(format('%s %s=%s', t.order_id, sel.key, sel.value::text), '; ' ORDER BY t.order_id, sel.key)
    INTO unmapped
    FROM workflow.workflow_notebook_trackers t
    CROSS JOIN LATERAL jsonb_each(t.vendor_selections) AS sel(key, value)
    WHERE sel.key NOT IN ('board', 'lamination', 'binder', 'offset')
       OR jsonb_typeof(sel.value) <> 'array'
       OR EXISTS (
            SELECT 1
            FROM jsonb_array_elements(sel.value) AS item(value)
            WHERE jsonb_typeof(item.value) <> 'string'
               OR NOT EXISTS (
                    SELECT 1
                    FROM workflow.workflow_vendors v
                    JOIN workflow.workflow_vendor_category_members m ON m.vendor_id = v.id
                    WHERE v.legacy_key = item.value #>> '{}'
                      AND m.category_code = CASE sel.key
                                                WHEN 'board' THEN 'BOARD_SUPPLIERS'
                                                WHEN 'lamination' THEN 'LAMINATION_VENDORS'
                                                WHEN 'binder' THEN 'BINDERS'
                                                WHEN 'offset' THEN 'OFFSET_PRINTERS'
                                            END));
    IF unmapped IS NOT NULL THEN
        RAISE EXCEPTION 'V19: vendor selections that match no directory vendor: %', unmapped;
    END IF;
END $$;

WITH category_map(legacy, code) AS (
    VALUES ('board', 'BOARD_SUPPLIERS'), ('lamination', 'LAMINATION_VENDORS'), ('binder', 'BINDERS'), ('offset', 'OFFSET_PRINTERS')
), picked AS (
    SELECT DISTINCT t.instance_id, cm.code, v.id
    FROM workflow.workflow_notebook_trackers t
    CROSS JOIN LATERAL jsonb_each(t.vendor_selections) AS sel(key, value)
    JOIN category_map cm ON cm.legacy = sel.key
    CROSS JOIN LATERAL jsonb_array_elements_text(sel.value) AS item(legacy_id)
    JOIN workflow.workflow_vendors v ON v.legacy_key = item.legacy_id
), per_category AS (
    SELECT instance_id, code, jsonb_agg(id ORDER BY id) AS ids
    FROM picked
    GROUP BY instance_id, code
), per_tracker AS (
    SELECT instance_id, jsonb_object_agg(code, ids) AS selections
    FROM per_category
    GROUP BY instance_id
)
-- A tracker whose lists were all empty ends with {}; the tracker's version moves so a stale browser cannot save over it.
UPDATE workflow.workflow_notebook_trackers t
SET vendor_selections = COALESCE((SELECT p.selections FROM per_tracker p WHERE p.instance_id = t.instance_id), '{}'::jsonb),
    version = t.version + 1
WHERE t.vendor_selections <> '{}'::jsonb;

-- 4. Vendor-key group links: V15 kept them per school. A vendor gets the invite only where every school that saved one for
--    it saved the same one; otherwise which school's link is the vendor's is unknowable and superadmin sets it. The
--    source rows stay where they are and are no longer read.
UPDATE workflow.workflow_vendors v
SET whatsapp_group_url = links.invite_url
FROM (
    SELECT target_key, min(invite_url) AS invite_url
    FROM workflow.workflow_school_whatsapp_group_links
    GROUP BY target_key
    HAVING count(DISTINCT invite_url) = 1
) links
WHERE v.legacy_key = links.target_key;

-- 5. Global settings -------------------------------------------------------------------------------------------------
CREATE TABLE workflow.workflow_settings (
    key        VARCHAR(64)   PRIMARY KEY CONSTRAINT ck_ws_key CHECK (key IN ('INTERNAL_APPROVAL_GROUP_URL')),
    value      VARCHAR(2048) NOT NULL,
    updated_by BIGINT        NOT NULL,
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_ws_internal_approval_group_url CHECK (
        key <> 'INTERNAL_APPROVAL_GROUP_URL' OR value ~ '^https://chat\.whatsapp\.com/[A-Za-z0-9_-]{10,64}$')
);

-- 6. A school's group for one item (tenant data) ----------------------------------------------------------------------
CREATE TABLE workflow.workflow_school_item_group_links (
    school_id     BIGINT        NOT NULL,
    category_code VARCHAR(60)   NOT NULL CONSTRAINT ck_wsigl_category_code CHECK (category_code ~ '^[A-Z][A-Z0-9_]{1,59}$'),
    invite_url    VARCHAR(2048) NOT NULL CONSTRAINT ck_wsigl_invite_url
                  CHECK (invite_url ~ '^https://chat\.whatsapp\.com/[A-Za-z0-9_-]{10,64}$'),
    updated_by    BIGINT        NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (school_id, category_code)
);

ALTER TABLE workflow.workflow_school_item_group_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow.workflow_school_item_group_links FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workflow.workflow_school_item_group_links
    USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on'
        OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]))
    WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on'
        OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]));

-- 7. Runtime grants (the dedicated-role ACL is resynchronised by afterMigrate; this covers app_rt where it exists). The
--    directory tables get the same grants as workflow_designers: the application deactivates rather than deletes.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON
            workflow.workflow_vendor_categories, workflow.workflow_vendors, workflow.workflow_vendor_category_members,
            workflow.workflow_settings, workflow.workflow_school_item_group_links TO app_rt;
        GRANT USAGE, SELECT ON SEQUENCE workflow.workflow_vendors_id_seq TO app_rt;
    END IF;
END $$;
