-- Notebook order tracker, step 2 (spec v4): up to six design drafts per order, and the intent to email superadmin when
-- the drafts rise above four. Drafts are their own asset kind so they are not limited to one current file the way the
-- school's sample design (DESIGN) and the pre-delivery photo are. A deleted draft is superseded, like any asset, and
-- only current drafts count toward the cap.
ALTER TABLE catalog.catalog_order_assets DROP CONSTRAINT IF EXISTS catalog_order_assets_asset_kind_check;
ALTER TABLE catalog.catalog_order_assets ADD CONSTRAINT catalog_order_assets_asset_kind_check
    CHECK (asset_kind IN ('DESIGN', 'PRE_DELIVERY_PHOTO', 'PRINT_REFERENCE', 'DESIGN_DRAFT'));

DROP INDEX IF EXISTS catalog.idx_catalog_order_current_assets;
CREATE UNIQUE INDEX idx_catalog_order_current_assets
    ON catalog.catalog_order_assets (school_id, order_id, asset_kind)
    WHERE superseded_at IS NULL AND asset_kind NOT IN ('PRINT_REFERENCE', 'DESIGN_DRAFT');

-- Counting current drafts is on the upload path.
CREATE INDEX idx_catalog_order_current_drafts
    ON catalog.catalog_order_assets (order_id)
    WHERE asset_kind = 'DESIGN_DRAFT' AND superseded_at IS NULL;

-- One row each time an order's current drafts rise above four. Nothing sends it yet: the table is the structure a sender
-- will read later. Recipients are resolved at send time as every active superadmin user, so no address is stored here.
CREATE TABLE catalog.order_design_email_intents (
    id          BIGSERIAL    PRIMARY KEY,
    order_id    VARCHAR(255) NOT NULL,
    school_id   BIGINT       NOT NULL,
    image_count SMALLINT     NOT NULL CHECK (image_count > 4),
    recipients  VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE_SUPERADMINS' CHECK (recipients IN ('ACTIVE_SUPERADMINS')),
    status      VARCHAR(12)  NOT NULL DEFAULT 'NOT_SENT' CHECK (status IN ('NOT_SENT', 'SENT', 'FAILED')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  BIGINT,
    FOREIGN KEY (order_id, school_id) REFERENCES catalog.catalog_orders (id, school_id) ON DELETE CASCADE
);
CREATE INDEX idx_order_design_email_intents_pending
    ON catalog.order_design_email_intents (created_at) WHERE status = 'NOT_SENT';
CREATE INDEX idx_order_design_email_intents_order
    ON catalog.order_design_email_intents (order_id, created_at);

ALTER TABLE catalog.order_design_email_intents ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.order_design_email_intents FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.order_design_email_intents
    USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on'
        OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]))
    WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on'
        OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.order_design_email_intents TO app_rt;
        GRANT USAGE, SELECT ON SEQUENCE catalog.order_design_email_intents_id_seq TO app_rt;
    END IF;
END $$;
