-- Structured forms for the additional client printing products.
-- Optional text fields preserve the client's varying units and legacy column values without
-- interpreting them as fixed manufacturing specifications.

INSERT INTO catalog.product_categories
    (code, label, emoji, description, order_type, form_enabled, paged, notes_enabled, sort_order)
VALUES
    ('BROCHURES', 'Brochures', U&'\+01F4D6', 'Brochures by finished size, folding and paper specifications', 'One-time', true, false, false, 16),
    ('FLUTE_BOARDS', 'Flute Boards', U&'\+01F4CB', 'Flute boards by finished dimensions', 'One-time', true, false, false, 17),
    ('STANDEES', 'Standees', U&'\+01F5BC', 'Display standees by type and dimensions', 'One-time', true, false, false, 18)
ON CONFLICT (code) DO NOTHING;

INSERT INTO catalog.product_option_groups
    (category_code, code, label, level, scope, input_type, unit, render, required)
VALUES
    ('BROCHURES', 'HEIGHT_AFTER_FOLDING', 'Height after folding', 1, 'LINE', 'TEXT', '', 'FIELD', true),
    ('BROCHURES', 'WIDTH_AFTER_FOLDING', 'Width after folding', 2, 'LINE', 'TEXT', '', 'FIELD', true),
    ('BROCHURES', 'BROCHURE_TYPE', 'Brochure type', 3, 'LINE', 'TEXT', '', 'FIELD', false),
    ('BROCHURES', 'HEIGHT_BEFORE_FOLDING', 'Height before folding', 4, 'LINE', 'TEXT', '', 'FIELD', false),
    ('BROCHURES', 'WIDTH_BEFORE_FOLDING', 'Width before folding', 5, 'LINE', 'TEXT', '', 'FIELD', false),
    ('BROCHURES', 'INNER_PAPER_GSM', 'Inner paper GSM', 6, 'LINE', 'TEXT', '', 'FIELD', false),
    ('BROCHURES', 'INNER_PAGES', 'Pages inside', 7, 'LINE', 'TEXT', '', 'FIELD', false),
    ('BROCHURES', 'FOLD_COUNT', 'Number of foldings', 8, 'LINE', 'TEXT', '', 'FIELD', false),
    ('BROCHURES', 'OUTER_PAPER_TYPE', 'Outer paper type', 9, 'LINE', 'TEXT', '', 'FIELD', false),
    ('BROCHURES', 'OUTER_PAPER_THICKNESS', 'Outer paper thickness', 10, 'LINE', 'TEXT', '', 'FIELD', false),
    ('BROCHURES', 'DESIGN_FILE_LINK', 'Design file link (optional)', 90, 'ORDER', 'TEXT', '', 'FIELD', false),

    ('FLUTE_BOARDS', 'HEIGHT_FT', 'Height', 1, 'LINE', 'DECIMAL', 'ft', 'FIELD', true),
    ('FLUTE_BOARDS', 'WIDTH_FT', 'Width', 2, 'LINE', 'DECIMAL', 'ft', 'FIELD', true),
    ('FLUTE_BOARDS', 'DESIGN_FILE_LINK', 'Design file link (optional)', 90, 'ORDER', 'TEXT', '', 'FIELD', false),

    ('STANDEES', 'STANDEE_TYPE', 'Standee type', 1, 'LINE', 'TEXT', '', 'FIELD', false),
    ('STANDEES', 'HEIGHT_FT', 'Height', 2, 'LINE', 'DECIMAL', 'ft', 'FIELD', true),
    ('STANDEES', 'WIDTH_FT', 'Width', 3, 'LINE', 'DECIMAL', 'ft', 'FIELD', true),
    ('STANDEES', 'FLEX_TYPE', 'Flex type', 4, 'LINE', 'TEXT', '', 'FIELD', false),
    ('STANDEES', 'DESIGN_FILE_LINK', 'Design file link (optional)', 90, 'ORDER', 'TEXT', '', 'FIELD', false)
ON CONFLICT (category_code, code) DO NOTHING;

UPDATE catalog.product_option_groups
SET label = 'Card (GSM)'
WHERE category_code = 'CERTIFICATES' AND code = 'GSM';
