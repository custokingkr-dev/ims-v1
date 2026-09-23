-- Bill book and belt order forms, from the 2026-09-23 prototypes.
--
-- Both fit the existing model with no schema change: every value a line carries is a selection from
-- a fixed list, plus a count. Neither has a minimum or a rounding rule, so no product_form_rules
-- rows are seeded; the prototypes state the count is a plain entry.
--
-- Attachments are optional in both prototypes, so there is deliberately no REQUIRE_ASSET rule. The
-- existing asset upload path already accepts optional images.
--
-- form_enabled is true so the category routes to the structured form. Nothing is reachable until
-- CATALOG_PRODUCT_FORM_ENABLED is also set for the environment.

INSERT INTO catalog.product_categories (code, label, emoji, description, order_type, form_enabled, sort_order) VALUES
('BILLBOOKS', 'Bill Books', U&'\+01F9FE', 'Receipt and bill books by size, pages and slip layout', 'Recurring', true, 8),
('BELTS', 'Belts', U&'\+01F45A', 'School belts by material, buckle and length', 'Recurring', true, 9)
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------- bill books
INSERT INTO catalog.product_option_groups (category_code, code, label, level, scope) VALUES
('BILLBOOKS', 'SIZE', 'Size', 1, 'LINE'),
('BILLBOOKS', 'PAGES', 'Pages', 2, 'LINE'),
('BILLBOOKS', 'PERFORATION', 'Perforation', 3, 'LINE'),
('BILLBOOKS', 'SEQUENCE', 'Sequence', 4, 'LINE'),
('BILLBOOKS', 'SLIPS_PER_PAGE', 'Slips per page', 5, 'LINE')
ON CONFLICT (category_code, code) DO NOTHING;

INSERT INTO catalog.product_options (group_id, code, label, sort_order)
SELECT g.id, v.code, v.label, v.sort_order
FROM catalog.product_option_groups g CROSS JOIN (VALUES
('A3', 'A3', 1), ('A4', 'A4', 2), ('A5', 'A5', 3)
) v(code, label, sort_order) WHERE g.category_code = 'BILLBOOKS' AND g.code = 'SIZE'
ON CONFLICT (group_id, code) DO NOTHING;

INSERT INTO catalog.product_options (group_id, code, label, sort_order)
SELECT g.id, v.code, v.label, v.sort_order
FROM catalog.product_option_groups g CROSS JOIN (VALUES
('P50', '50', 1), ('P100', '100', 2), ('P200', '200', 3)
) v(code, label, sort_order) WHERE g.category_code = 'BILLBOOKS' AND g.code = 'PAGES'
ON CONFLICT (group_id, code) DO NOTHING;

INSERT INTO catalog.product_options (group_id, code, label, sort_order)
SELECT g.id, v.code, v.label, v.sort_order
FROM catalog.product_option_groups g CROSS JOIN (VALUES
('YES', 'Yes', 1), ('NO', 'No', 2)
) v(code, label, sort_order) WHERE g.category_code = 'BILLBOOKS' AND g.code = 'PERFORATION'
ON CONFLICT (group_id, code) DO NOTHING;

INSERT INTO catalog.product_options (group_id, code, label, sort_order)
SELECT g.id, v.code, v.label, v.sort_order
FROM catalog.product_option_groups g CROSS JOIN (VALUES
('WHITE_UP', 'White up', 1), ('COLOR_UP', 'Color up', 2)
) v(code, label, sort_order) WHERE g.category_code = 'BILLBOOKS' AND g.code = 'SEQUENCE'
ON CONFLICT (group_id, code) DO NOTHING;

INSERT INTO catalog.product_options (group_id, code, label, sort_order)
SELECT g.id, v.code, v.label, v.sort_order
FROM catalog.product_option_groups g CROSS JOIN (VALUES
('S1', '1', 1), ('S2', '2', 2), ('S4', '4', 3), ('S8', '8', 4)
) v(code, label, sort_order) WHERE g.category_code = 'BILLBOOKS' AND g.code = 'SLIPS_PER_PAGE'
ON CONFLICT (group_id, code) DO NOTHING;

-- ---------------------------------------------------------------- belts
-- Belt type and buckle are chosen before the length matrix appears, but every length is valid for
-- every combination, so no option dependencies are seeded; the sequencing is presentational.
INSERT INTO catalog.product_option_groups (category_code, code, label, level, scope) VALUES
('BELTS', 'BELT_TYPE', 'Belt type', 1, 'LINE'),
('BELTS', 'BUCKLE_TYPE', 'Buckle type', 2, 'LINE'),
('BELTS', 'LENGTH', 'Length', 3, 'LINE')
ON CONFLICT (category_code, code) DO NOTHING;

INSERT INTO catalog.product_options (group_id, code, label, sort_order)
SELECT g.id, v.code, v.label, v.sort_order
FROM catalog.product_option_groups g CROSS JOIN (VALUES
('CLOTH', 'Cloth Belt', 1), ('SATIN', 'Satin Belt', 2)
) v(code, label, sort_order) WHERE g.category_code = 'BELTS' AND g.code = 'BELT_TYPE'
ON CONFLICT (group_id, code) DO NOTHING;

INSERT INTO catalog.product_options (group_id, code, label, sort_order)
SELECT g.id, v.code, v.label, v.sort_order
FROM catalog.product_option_groups g CROSS JOIN (VALUES
('BRONZE', 'Bronze', 1), ('PLASTIC', 'Plastic', 2)
) v(code, label, sort_order) WHERE g.category_code = 'BELTS' AND g.code = 'BUCKLE_TYPE'
ON CONFLICT (group_id, code) DO NOTHING;

INSERT INTO catalog.product_options (group_id, code, label, spec_text, sort_order)
SELECT g.id, v.code, v.label, v.spec_text, v.sort_order
FROM catalog.product_option_groups g CROSS JOIN (VALUES
('L70', '70 cm', '70 cm', 1), ('L75', '75 cm', '75 cm', 2), ('L80', '80 cm', '80 cm', 3),
('L85', '85 cm', '85 cm', 4), ('L90', '90 cm', '90 cm', 5), ('L95', '95 cm', '95 cm', 6)
) v(code, label, spec_text, sort_order) WHERE g.category_code = 'BELTS' AND g.code = 'LENGTH'
ON CONFLICT (group_id, code) DO NOTHING;
