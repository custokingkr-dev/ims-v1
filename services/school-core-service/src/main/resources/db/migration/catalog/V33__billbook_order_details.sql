-- Additional Bill Books fields present in the client order sheet, without guessing at color values.
-- Expected completion date already maps to the shared requiredByDate field on every order form.

INSERT INTO catalog.product_option_groups (category_code, code, label, level, scope, input_type, unit, render)
VALUES
    ('BILLBOOKS', 'PAGE_COPIES', 'Page copies needed', 6, 'LINE', 'INTEGER', '', 'FIELD'),
    ('BILLBOOKS', 'COLOR_FIRST_PAGE', 'Color of first page', 7, 'LINE', 'TEXT', '', 'FIELD'),
    ('BILLBOOKS', 'COLOR_FIRST_COPY_PAGE', 'Color of first copy page', 8, 'LINE', 'TEXT', '', 'FIELD'),
    ('BILLBOOKS', 'COLOR_SECOND_COPY_PAGE', 'Color of second copy page', 9, 'LINE', 'TEXT', '', 'FIELD'),
    ('BILLBOOKS', 'PRINT_COLOR_FIRST_PAGE', 'Color on first page', 10, 'LINE', 'TEXT', '', 'FIELD'),
    ('BILLBOOKS', 'PRINT_COLOR_FIRST_COPY_PAGE', 'Color on first copy page', 11, 'LINE', 'TEXT', '', 'FIELD'),
    ('BILLBOOKS', 'PRINT_COLOR_SECOND_COPY_PAGE', 'Color on second copy page', 12, 'LINE', 'TEXT', '', 'FIELD'),
    ('BILLBOOKS', 'NUMBERING_REQUIRED', 'Numbering required', 13, 'LINE', 'SELECT', '', 'SEGMENTED'),
    ('BILLBOOKS', 'NUMBERING_START', 'Numbering start (optional)', 14, 'LINE', 'INTEGER', '', 'FIELD'),
    ('BILLBOOKS', 'NUMBERING_BASED_ON', 'Numbering based on (optional)', 15, 'LINE', 'SELECT', '', 'SELECT'),
    ('BILLBOOKS', 'DESIGN_FILE_LINK', 'Design file link (optional)', 90, 'ORDER', 'TEXT', '', 'FIELD')
ON CONFLICT (category_code, code) DO NOTHING;

UPDATE catalog.product_option_groups
SET required = false
WHERE category_code = 'BILLBOOKS' AND code IN ('NUMBERING_START', 'NUMBERING_BASED_ON', 'DESIGN_FILE_LINK');

INSERT INTO catalog.product_options (group_id, code, label, sort_order)
SELECT g.id, v.code, v.label, v.sort_order
FROM catalog.product_option_groups g
CROSS JOIN (VALUES ('NO', 'No', 1), ('YES', 'Yes', 2)) v(code, label, sort_order)
WHERE g.category_code = 'BILLBOOKS' AND g.code = 'NUMBERING_REQUIRED'
ON CONFLICT (group_id, code) DO NOTHING;

INSERT INTO catalog.product_options (group_id, code, label, sort_order)
SELECT g.id, v.code, v.label, v.sort_order
FROM catalog.product_option_groups g
CROSS JOIN (VALUES ('SLIPS', 'Slips', 1), ('PAGES', 'Pages', 2)) v(code, label, sort_order)
WHERE g.category_code = 'BILLBOOKS' AND g.code = 'NUMBERING_BASED_ON'
ON CONFLICT (group_id, code) DO NOTHING;
