-- Preserve client order-sheet values that do not map to a single option or managed upload.
-- External links remain optional; uploaded files stay managed by the catalog asset workflow.

UPDATE catalog.product_option_groups
SET required = false
WHERE category_code = 'CERTIFICATES' AND code = 'CATEGORY';

INSERT INTO catalog.product_options (group_id, code, label, sort_order)
SELECT g.id, 'SLIPS_AND_PAGES', 'Slips and pages', 3
FROM catalog.product_option_groups g
WHERE g.category_code = 'BILLBOOKS' AND g.code = 'NUMBERING_BASED_ON'
ON CONFLICT (group_id, code) DO NOTHING;

INSERT INTO catalog.product_option_groups
    (category_code, code, label, level, scope, input_type, unit, render, required)
VALUES
    ('CERTIFICATES', 'DESIGN_FILE_LINK', 'Certificate file link (optional)', 90, 'ORDER', 'TEXT', '', 'FIELD', false),
    ('REPORT_CARDS', 'DESIGN_FILE_LINK', 'Report card file link (optional)', 90, 'ORDER', 'TEXT', '', 'FIELD', false),
    ('FLEX', 'DESIGN_FILE_LINK', 'Design file link (optional)', 90, 'ORDER', 'TEXT', '', 'FIELD', false),
    ('FLIERS', 'DESIGN_FILE_LINK', 'Design file link (optional)', 90, 'ORDER', 'TEXT', '', 'FIELD', false)
ON CONFLICT (category_code, code) DO NOTHING;
