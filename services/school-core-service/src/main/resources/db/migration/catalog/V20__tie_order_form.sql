-- Ties, from the prototype supplied 2026-09-25 (breadcrumb "Orders / Ties / New order").
--
-- Shaped like belts: a tie type chosen once, then a table of lengths each with its own count, and
-- rows left at zero skipped. Two details from the prototype's own source rather than its appearance:
--
--   var LENGTHS_STANDARD = ["10 inch","11 inch","12 inch","14 inch","16 inch"];
--   var LONG_TIE_ROW = "Long Tie · 48 inch";
--   /* Every tie type gets the 10-16 inch range, plus the Long Tie row (fixed 48 inch) - Long Tie is
--      a subcategory available under each of the 4 tie types, not a separate type. */
--
-- So Long Tie is a sixth length, not a fifth type, and no length depends on the type: every type
-- offers all six. Unlike belts, there are no dependency rows to seed. There is no 13 inch or
-- 15 inch; the list is exactly the five above plus Long Tie.

INSERT INTO catalog.product_categories (code, label, emoji, description, order_type, form_enabled, paged, notes_enabled, sort_order) VALUES
('TIES', 'Ties', U&'\+01F454', 'School ties by type and length', 'Recurring', true, false, false, 12)
ON CONFLICT (code) DO NOTHING;

INSERT INTO catalog.product_option_groups (category_code, code, label, level, scope, input_type, unit, render) VALUES
('TIES', 'TIE_TYPE', 'Tie type', 1, 'LINE', 'SELECT', '', 'SEGMENTED'),
('TIES', 'LENGTH', 'Length', 2, 'LINE', 'SELECT', '', 'MATRIX')
ON CONFLICT (category_code, code) DO NOTHING;

INSERT INTO catalog.product_options (group_id, code, label, sort_order)
SELECT g.id, v.code, v.label, v.sort_order
FROM catalog.product_option_groups g, (VALUES
    ('SATIN_WITH_LOGO', 'Satin Tie with logo', 1),
    ('SATIN', 'Satin Tie', 2),
    ('CLOTH', 'Cloth Tie', 3),
    ('READYMADE', 'Readymade ties', 4)
) v(code, label, sort_order) WHERE g.category_code = 'TIES' AND g.code = 'TIE_TYPE'
ON CONFLICT (group_id, code) DO NOTHING;

INSERT INTO catalog.product_options (group_id, code, label, spec_text, sort_order)
SELECT g.id, v.code, v.label, v.spec_text, v.sort_order
FROM catalog.product_option_groups g, (VALUES
    ('LEN_10', '10 inch', '10 inch', 1),
    ('LEN_11', '11 inch', '11 inch', 2),
    ('LEN_12', '12 inch', '12 inch', 3),
    ('LEN_14', '14 inch', '14 inch', 4),
    ('LEN_16', '16 inch', '16 inch', 5),
    ('LONG_TIE', 'Long Tie', '48 inch', 6)
) v(code, label, spec_text, sort_order) WHERE g.category_code = 'TIES' AND g.code = 'LENGTH'
ON CONFLICT (group_id, code) DO NOTHING;

-- The optional upload the prototype offers, on the same terms as the other image fields.
INSERT INTO catalog.product_form_rules (category_code, rule_type, target_field, match_options, params, priority, message)
SELECT 'TIES', 'OFFER_ASSET', NULL, '{}'::jsonb,
       '{"assetKind":"DESIGN","label":"Tie image","accept":"image/png,image/jpeg,image/webp","maxBytes":5242880}'::jsonb,
       50, 'Upload tie image (optional)'
WHERE NOT EXISTS (
    SELECT 1 FROM catalog.product_form_rules
    WHERE category_code = 'TIES' AND rule_type = 'OFFER_ASSET' AND params ->> 'assetKind' = 'DESIGN');
