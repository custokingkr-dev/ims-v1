-- Report cards, from the prototype supplied 2026-09-25 (breadcrumb "Orders / Report Cards / New order").
--
-- Ordering works like certificates: a line built from an after-folding size, inner pages and whether
-- folding is required, with a minimum of 50 per line that rejects rather than raising.
--
-- This form is also the first to show a school a price. The prototype computes it - nothing is typed -
-- from rates it attributes to a source sheet:
--
--   calcD7(size, qty)          A4 -> 16*qty          A5 -> 8*qty
--   calcD8(size, pages, qty)   c = ceil(pages/4)     A4 -> 14*qty*c   A5 -> 7*qty*c
--   calcD9(folding, qty)       YES -> 250            else 80 / 150 / 200 by quantity band
--   calcE9(pages, qty, d9)     pages != 0 -> (qty < 150 ? 150*6 : qty*6)   else d9
--   costPerCard = (d7 + d8 + e9) / qty               -- D9 is shown for reference and excluded
--
-- The rates live here rather than in the frontend so they can be corrected without a release, and
-- the estimate stays advisory: the superadmin quote remains the price of record.

INSERT INTO catalog.product_categories (code, label, emoji, description, order_type, form_enabled, paged, notes_enabled, sort_order) VALUES
('REPORT_CARDS', 'Report Cards', U&'\+01F4CB', 'Report cards by folded size, inner pages and folding', 'Recurring', true, false, false, 14)
ON CONFLICT (code) DO NOTHING;

INSERT INTO catalog.product_option_groups (category_code, code, label, level, scope, input_type, unit, render) VALUES
('REPORT_CARDS', 'SIZE', 'After folding size', 1, 'LINE', 'SELECT', '', 'SEGMENTED'),
('REPORT_CARDS', 'INNER_PAGES', 'Inner pages (multiple of 4)', 2, 'LINE', 'SELECT', '', 'SEGMENTED'),
('REPORT_CARDS', 'FOLDING', 'Folding required', 3, 'LINE', 'SELECT', '', 'SEGMENTED')
ON CONFLICT (category_code, code) DO NOTHING;

INSERT INTO catalog.product_options (group_id, code, label, sort_order)
SELECT g.id, v.code, v.label, v.sort_order
FROM catalog.product_option_groups g, (VALUES ('A4', 'A4', 1), ('A5', 'A5', 2)) v(code, label, sort_order)
WHERE g.category_code = 'REPORT_CARDS' AND g.code = 'SIZE'
ON CONFLICT (group_id, code) DO NOTHING;

INSERT INTO catalog.product_options (group_id, code, label, sort_order)
SELECT g.id, v.code, v.label, v.sort_order
FROM catalog.product_option_groups g, (VALUES
    ('P0', '0', 1), ('P4', '4', 2), ('P8', '8', 3), ('P12', '12', 4),
    ('P16', '16', 5), ('P20', '20', 6), ('P24', '24', 7)
) v(code, label, sort_order) WHERE g.category_code = 'REPORT_CARDS' AND g.code = 'INNER_PAGES'
ON CONFLICT (group_id, code) DO NOTHING;

INSERT INTO catalog.product_options (group_id, code, label, sort_order)
SELECT g.id, v.code, v.label, v.sort_order
FROM catalog.product_option_groups g, (VALUES ('YES', 'Yes', 1), ('NO', 'No', 2)) v(code, label, sort_order)
WHERE g.category_code = 'REPORT_CARDS' AND g.code = 'FOLDING'
ON CONFLICT (group_id, code) DO NOTHING;

INSERT INTO catalog.product_form_rules (category_code, rule_type, target_field, match_options, params, priority, message)
SELECT 'REPORT_CARDS', 'MIN_VALUE', 'BOOK_COUNT', '{}'::jsonb, '{"value":50}'::jsonb, 10,
       'Minimum order is 50 report cards per line'
WHERE NOT EXISTS (
    SELECT 1 FROM catalog.product_form_rules
    WHERE category_code = 'REPORT_CARDS' AND rule_type = 'MIN_VALUE' AND target_field = 'BOOK_COUNT');

INSERT INTO catalog.product_form_rules (category_code, rule_type, target_field, match_options, params, priority, message)
SELECT 'REPORT_CARDS', 'OFFER_ASSET', NULL, '{}'::jsonb,
       '{"assetKind":"DESIGN","label":"Report card image","accept":"image/png,image/jpeg,image/webp","maxBytes":5242880}'::jsonb,
       50, 'Upload report card image (optional)'
WHERE NOT EXISTS (
    SELECT 1 FROM catalog.product_form_rules
    WHERE category_code = 'REPORT_CARDS' AND rule_type = 'OFFER_ASSET' AND params ->> 'assetKind' = 'DESIGN');

ALTER TABLE catalog.product_form_rules
    DROP CONSTRAINT IF EXISTS product_form_rules_rule_type_check;
ALTER TABLE catalog.product_form_rules
    ADD CONSTRAINT product_form_rules_rule_type_check
    CHECK (rule_type IN ('REQUIRE_QUANTITY_TOTAL', 'ROUND_TO_MULTIPLE', 'FLOOR_VALUE',
                         'MIN_VALUE', 'MAX_VALUE', 'REQUIRE_ASSET', 'OFFER_ASSET', 'COST_ESTIMATE'));

-- The rates behind the estimate, kept as data so they can be corrected without a release.
INSERT INTO catalog.product_form_rules (category_code, rule_type, target_field, match_options, params, priority, message)
SELECT 'REPORT_CARDS', 'COST_ESTIMATE', NULL, '{}'::jsonb,
       '{"model":"SHEET_V1","a4Base":16,"a5Base":8,"a4PerSignature":14,"a5PerSignature":7,
         "foldingFee":250,"band1Max":100,"band1":80,"band2Max":200,"band2":150,"band3":200,
         "printRate":6,"printMinUnits":150}'::jsonb,
       90, 'Estimate only. The Custoking quote is the price of record.'
WHERE NOT EXISTS (
    SELECT 1 FROM catalog.product_form_rules
    WHERE category_code = 'REPORT_CARDS' AND rule_type = 'COST_ESTIMATE');
