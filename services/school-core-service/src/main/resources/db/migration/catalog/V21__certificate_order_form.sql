-- Certificates, from the prototype supplied 2026-09-25 (breadcrumb "Orders / Certificates / New order").
--
-- No matrix: the form builds one line at a time from a category, size, GSM and count, the way bill
-- books and fliers do. From the prototype's source:
--
--   var MIN_COUNT = 20;
--   'Minimum order quantity is ' + MIN_COUNT + ' per size/GSM line.'
--   if (!state.cat || !state.size || !state.gsm || count < MIN_COUNT) return;
--
-- The minimum rejects rather than raising, so it is MIN_VALUE like the flier's 3000, not the
-- FLOOR_VALUE used for customised notebooks. `findGroup(state.cat)` keeps one group per category
-- inside a single order, so the category is a line-scope choice rather than an order-wide one -
-- a school can order Generic and Individual customisation certificates on one order.

INSERT INTO catalog.product_categories (code, label, emoji, description, order_type, form_enabled, paged, notes_enabled, sort_order) VALUES
('CERTIFICATES', 'Certificates', U&'\+01F4DC', 'Single-sheet certificates by size and paper weight', 'One-time', true, false, false, 13)
ON CONFLICT (code) DO NOTHING;

INSERT INTO catalog.product_option_groups (category_code, code, label, level, scope, input_type, unit, render) VALUES
('CERTIFICATES', 'CATEGORY', 'Category', 1, 'LINE', 'SELECT', '', 'SEGMENTED'),
('CERTIFICATES', 'SIZE', 'Size', 2, 'LINE', 'SELECT', '', 'SEGMENTED'),
('CERTIFICATES', 'GSM', 'GSM', 3, 'LINE', 'SELECT', '', 'SEGMENTED')
ON CONFLICT (category_code, code) DO NOTHING;

INSERT INTO catalog.product_options (group_id, code, label, sort_order)
SELECT g.id, v.code, v.label, v.sort_order
FROM catalog.product_option_groups g, (VALUES
    ('GENERIC', 'Generic', 1),
    ('INDIVIDUAL', 'Individual customisation', 2)
) v(code, label, sort_order) WHERE g.category_code = 'CERTIFICATES' AND g.code = 'CATEGORY'
ON CONFLICT (group_id, code) DO NOTHING;

INSERT INTO catalog.product_options (group_id, code, label, sort_order)
SELECT g.id, v.code, v.label, v.sort_order
FROM catalog.product_option_groups g, (VALUES
    ('A3', 'A3', 1), ('A4', 'A4', 2), ('A5', 'A5', 3)
) v(code, label, sort_order) WHERE g.category_code = 'CERTIFICATES' AND g.code = 'SIZE'
ON CONFLICT (group_id, code) DO NOTHING;

INSERT INTO catalog.product_options (group_id, code, label, sort_order)
SELECT g.id, v.code, v.label, v.sort_order
FROM catalog.product_option_groups g, (VALUES
    ('GSM_250', '250', 1), ('GSM_300', '300', 2)
) v(code, label, sort_order) WHERE g.category_code = 'CERTIFICATES' AND g.code = 'GSM'
ON CONFLICT (group_id, code) DO NOTHING;

INSERT INTO catalog.product_form_rules (category_code, rule_type, target_field, match_options, params, priority, message)
SELECT 'CERTIFICATES', 'MIN_VALUE', 'BOOK_COUNT', '{}'::jsonb, '{"value":20}'::jsonb, 10,
       'Minimum order quantity is 20 per size and paper weight'
WHERE NOT EXISTS (
    SELECT 1 FROM catalog.product_form_rules
    WHERE category_code = 'CERTIFICATES' AND rule_type = 'MIN_VALUE' AND target_field = 'BOOK_COUNT');

-- The certificate image, and the print references the prototype allows several of.
INSERT INTO catalog.product_form_rules (category_code, rule_type, target_field, match_options, params, priority, message)
SELECT 'CERTIFICATES', 'OFFER_ASSET', NULL, '{}'::jsonb, v.params::jsonb, v.priority, v.message
FROM (VALUES
    ('{"assetKind":"DESIGN","label":"Certificate image","accept":"image/png,image/jpeg,image/webp","maxBytes":5242880}', 50, 'Upload certificate image (optional)'),
    ('{"assetKind":"PRINT_REFERENCE","label":"Share all the images","accept":"image/png,image/jpeg,image/webp","maxBytes":5242880,"multiple":"true"}', 60, 'Share all the images (optional, multiple allowed)')
) AS v(params, priority, message)
WHERE NOT EXISTS (
    SELECT 1 FROM catalog.product_form_rules r
    WHERE r.category_code = 'CERTIFICATES' AND r.rule_type = 'OFFER_ASSET'
      AND r.params ->> 'assetKind' = (v.params::jsonb) ->> 'assetKind');
