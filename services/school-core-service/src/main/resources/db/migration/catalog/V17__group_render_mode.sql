-- How each option group is presented, taken from the 2026-09-23 prototypes.
--
-- The prototypes are not five variations on one list of rows. They share a shell and differ in two
-- ways this column captures:
--   SEGMENTED  a short choice shown as a row of buttons     (Custom/Wholesale, Cloth/Satin, A3/A4/A5)
--   SELECT     a longer choice shown as a dropdown          (notebook Size)
--   MATRIX     the axis of a table: every option becomes a row with its own quantity, and rows left
--              at zero are skipped                          (notebook Ruling, belt Length)
--   FIELD      a typed value entered per line               (flex length/breadth, flier size/GSM)
--
-- Without this the frontend has to infer the shape from option counts and scope, which is exactly
-- the guessing that produced a generic form none of the prototypes actually show.
ALTER TABLE catalog.product_option_groups
    ADD COLUMN IF NOT EXISTS render VARCHAR(12) NOT NULL DEFAULT 'SELECT';

ALTER TABLE catalog.product_option_groups
    DROP CONSTRAINT IF EXISTS product_option_groups_render_check;

ALTER TABLE catalog.product_option_groups
    ADD CONSTRAINT product_option_groups_render_check
    CHECK (render IN ('SEGMENTED', 'SELECT', 'MATRIX', 'FIELD'));

-- A typed group is always a field, whatever else is said below.
UPDATE catalog.product_option_groups SET render = 'FIELD' WHERE input_type <> 'SELECT';

UPDATE catalog.product_option_groups SET render = 'SEGMENTED'
WHERE (category_code, code) IN (
    ('NOTEBOOKS', 'CUSTOMIZATION'),
    ('BELTS', 'BELT_TYPE'),
    ('BELTS', 'BUCKLE_TYPE'),
    ('BILLBOOKS', 'SIZE'),
    ('BILLBOOKS', 'PAGES'),
    ('BILLBOOKS', 'PERFORATION'),
    ('BILLBOOKS', 'SEQUENCE'),
    ('BILLBOOKS', 'SLIPS_PER_PAGE'),
    ('FLEX', 'FLEX_TYPE')
);

UPDATE catalog.product_option_groups SET render = 'MATRIX'
WHERE (category_code, code) IN (
    ('NOTEBOOKS', 'RULING'),
    ('BELTS', 'LENGTH')
);

COMMENT ON COLUMN catalog.product_option_groups.render IS
    'Presentation taken from the 2026-09-23 order-form prototypes: SEGMENTED buttons, SELECT dropdown, MATRIX table axis, or FIELD typed entry.';
