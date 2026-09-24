-- Every prototype offers an upload, and all of them are optional. The model could only express a
-- required asset (REQUIRE_ASSET), so four of the five categories had no upload at all and the two
-- "print reference" blocks did not exist. This migration adds what the prototypes actually show.
--
--   OFFER_ASSET     an optional upload. Same shape as REQUIRE_ASSET, never blocks placement.
--   PRINT_REFERENCE a new kind: several images plus a note, on bill books and fliers.
--   maxBytes        per-rule size cap, because the flex PDF is 10 MB and the images are 5 MB.

ALTER TABLE catalog.product_form_rules
    DROP CONSTRAINT IF EXISTS product_form_rules_rule_type_check;
ALTER TABLE catalog.product_form_rules
    ADD CONSTRAINT product_form_rules_rule_type_check
    CHECK (rule_type IN ('REQUIRE_QUANTITY_TOTAL', 'ROUND_TO_MULTIPLE', 'FLOOR_VALUE',
                         'MIN_VALUE', 'MAX_VALUE', 'REQUIRE_ASSET', 'OFFER_ASSET'));

ALTER TABLE catalog.catalog_order_assets
    DROP CONSTRAINT IF EXISTS catalog_order_assets_asset_kind_check;
ALTER TABLE catalog.catalog_order_assets
    ADD CONSTRAINT catalog_order_assets_asset_kind_check
    CHECK (asset_kind IN ('DESIGN', 'PRE_DELIVERY_PHOTO', 'PRINT_REFERENCE'));

-- The flex prototype accepts a 10 MB PDF; the table refused anything over 5 MB.
ALTER TABLE catalog.catalog_order_assets
    DROP CONSTRAINT IF EXISTS catalog_order_assets_size_bytes_check;
ALTER TABLE catalog.catalog_order_assets
    ADD CONSTRAINT catalog_order_assets_size_bytes_check
    CHECK (size_bytes > 0 AND size_bytes <= 10485760);

-- One current asset per kind is right for a design or a delivery photo, and wrong for print
-- references, which the prototypes allow several of.
DROP INDEX IF EXISTS catalog.idx_catalog_order_current_assets;
CREATE UNIQUE INDEX idx_catalog_order_current_assets
    ON catalog.catalog_order_assets(school_id, order_id, asset_kind)
    WHERE superseded_at IS NULL AND asset_kind <> 'PRINT_REFERENCE';

-- Order-scope typed values, the counterpart of catalog_order_lines.attributes.
ALTER TABLE catalog.catalog_orders
    ADD COLUMN IF NOT EXISTS attributes JSONB NOT NULL DEFAULT '{}'::jsonb;

-- The optional upload each prototype shows, with its own accepted types and cap.
INSERT INTO catalog.product_form_rules (category_code, rule_type, target_field, match_options, params, priority, message)
SELECT v.category_code, 'OFFER_ASSET', NULL, '{}'::jsonb, v.params::jsonb, v.priority, v.message
FROM (VALUES
    ('NOTEBOOKS',  '{"assetKind":"DESIGN","label":"Sample design","accept":"image/png,image/jpeg,image/webp,application/pdf","maxBytes":5242880}', 50, 'Sample design (optional)'),
    ('BILLBOOKS',  '{"assetKind":"DESIGN","label":"Bill book image","accept":"image/png,image/jpeg,image/webp","maxBytes":5242880}', 50, 'Upload bill book image (optional)'),
    ('BELTS',      '{"assetKind":"DESIGN","label":"Belt image","accept":"image/png,image/jpeg,image/webp","maxBytes":5242880}', 50, 'Upload belt image (optional)'),
    ('FLEX',       '{"assetKind":"DESIGN","label":"Flex PDF","accept":"application/pdf","maxBytes":10485760}', 50, 'Upload flex PDF (optional)'),
    ('FLIERS',     '{"assetKind":"DESIGN","label":"Flier image","accept":"image/png,image/jpeg,image/webp","maxBytes":5242880}', 50, 'Upload flier image (optional)'),
    ('BILLBOOKS',  '{"assetKind":"PRINT_REFERENCE","label":"Share all the images","accept":"image/png,image/jpeg,image/webp","maxBytes":5242880,"multiple":"true"}', 60, 'Share all the images (optional, multiple allowed)'),
    ('FLIERS',     '{"assetKind":"PRINT_REFERENCE","label":"Share all the images","accept":"image/png,image/jpeg,image/webp","maxBytes":5242880,"multiple":"true"}', 60, 'Share all the images (optional, multiple allowed)')
) AS v(category_code, params, priority, message)
WHERE NOT EXISTS (
    SELECT 1 FROM catalog.product_form_rules r
    WHERE r.category_code = v.category_code AND r.rule_type = 'OFFER_ASSET'
      AND r.params ->> 'assetKind' = (v.params::jsonb) ->> 'assetKind');

-- "Share the content (optional)" beside those images: an order-scope typed value, so it needs no
-- new storage beyond catalog_orders.attributes above.
INSERT INTO catalog.product_option_groups (category_code, code, label, level, selection_type, input_type, unit, render, required, scope, active)
SELECT v.category_code, 'PRINT_CONTENT', 'Share the content', 90, 'SINGLE', 'TEXT', '', 'FIELD', false, 'ORDER', true
FROM (VALUES ('BILLBOOKS'), ('FLIERS')) AS v(category_code)
WHERE NOT EXISTS (
    SELECT 1 FROM catalog.product_option_groups g
    WHERE g.category_code = v.category_code AND g.code = 'PRINT_CONTENT');
