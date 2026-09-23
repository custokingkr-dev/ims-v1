-- Flex and flier order forms, from the 2026-09-23 prototypes. Both need the typed per-line values
-- added in V13: flex measures each line in feet, flier carries a free-text size and a GSM.

INSERT INTO catalog.product_categories (code, label, emoji, description, order_type, form_enabled, sort_order) VALUES
('FLEX', 'Flex', U&'\+01F5BC', 'Flex signage by type and size in feet', 'One-time', true, 10),
('FLIERS', 'Fliers', U&'\+01F4C4', 'Single-page fliers by size, paper weight and quantity', 'One-time', true, 11)
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------- flex
-- Length and breadth are entered per line, so they are DECIMAL groups rather than option lists.
-- Quotation works from the stored dimensions; the prototype computes no area itself.
INSERT INTO catalog.product_option_groups (category_code, code, label, level, scope, input_type, unit) VALUES
('FLEX', 'FLEX_TYPE', 'Type of flex', 1, 'LINE', 'SELECT', ''),
('FLEX', 'LENGTH_FT', 'Length', 2, 'LINE', 'DECIMAL', 'ft'),
('FLEX', 'BREADTH_FT', 'Breadth', 3, 'LINE', 'DECIMAL', 'ft')
ON CONFLICT (category_code, code) DO NOTHING;

INSERT INTO catalog.product_options (group_id, code, label, sort_order)
SELECT g.id, v.code, v.label, v.sort_order
FROM catalog.product_option_groups g CROSS JOIN (VALUES
('STAR', 'Star flex', 1),
('NORMAL', 'Normal flex', 2),
('BACKLIT', 'Backlit flex', 3),
('STAR_BACKLIT', 'Star backlit flex', 4),
('BLACK_BACKGROUND', 'Flex with black background', 5)
) v(code, label, sort_order) WHERE g.category_code = 'FLEX' AND g.code = 'FLEX_TYPE'
ON CONFLICT (group_id, code) DO NOTHING;

-- ---------------------------------------------------------------- fliers
-- Size is free text in the prototype ("A4", "8.5 x 11"), and GSM is typed per row. Both are
-- recorded as typed groups. The free-text size means reporting sees whatever was typed; the
-- expansion document flags a fixed list as the cheaper long-term shape.
INSERT INTO catalog.product_option_groups (category_code, code, label, level, scope, input_type, unit) VALUES
('FLIERS', 'SIZE', 'Size', 1, 'LINE', 'TEXT', ''),
('FLIERS', 'GSM', 'GSM', 2, 'LINE', 'INTEGER', 'gsm')
ON CONFLICT (category_code, code) DO NOTHING;

-- The prototype requires at least 3000 per GSM entry and rejects less rather than raising it, so
-- this is MIN_VALUE, not the FLOOR_VALUE used for customised notebooks.
INSERT INTO catalog.product_form_rules (category_code, rule_type, target_field, match_options, params, priority, message)
SELECT 'FLIERS', 'MIN_VALUE', 'BOOK_COUNT', '{}', '{"value":3000}', 10,
       'Each paper weight needs a count of at least 3000'
WHERE NOT EXISTS (
    SELECT 1 FROM catalog.product_form_rules
    WHERE category_code = 'FLIERS' AND rule_type = 'MIN_VALUE' AND target_field = 'BOOK_COUNT');
