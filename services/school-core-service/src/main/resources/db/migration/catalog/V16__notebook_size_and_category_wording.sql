-- Corrects the notebook seed against the 2026-09-23 prototype, which was misread by V11.
--
-- The prototype's size list is unambiguous:
--     <option value="King"       data-dim="">King</option>
--     <option value="Jumbo King" data-dim="19 x 26 cm">Jumbo King - 19x26 cm</option>
-- so 19 x 26 cm belongs to JUMBO KING, which the original V9 seed already had right. V11 read the
-- dimension as King's, confirmed King at 190 x 260, and so both invented a specification for King
-- and duplicated Jumbo King's. An option with no agreed size must stay visible but unorderable,
-- which is what PENDING_SPEC with null dimensions expresses.
--
-- Scoped to the exact values V11 wrote, so a size someone has since confirmed deliberately, or
-- given different dimensions, is left alone.
UPDATE catalog.product_options o
SET spec_text = '',
    width_mm = NULL,
    height_mm = NULL,
    spec_status = 'PENDING_SPEC'
FROM catalog.product_option_groups g
WHERE o.group_id = g.id
  AND g.category_code = 'NOTEBOOKS'
  AND g.code = 'SIZE'
  AND o.code = 'KING'
  AND o.spec_status = 'CONFIRMED'
  AND o.width_mm = 190
  AND o.height_mm = 260;

-- The prototype labels the order-scope group "Category" and its two choices "Custom" and
-- "Wholesale". The codes stay as they are: they are referenced by every rule's match_options and by
-- existing orders, and renaming them would break both.
UPDATE catalog.product_option_groups
SET label = 'Category'
WHERE category_code = 'NOTEBOOKS' AND code = 'CUSTOMIZATION' AND label = 'Customization';

UPDATE catalog.product_options o
SET label = 'Custom'
FROM catalog.product_option_groups g
WHERE o.group_id = g.id
  AND g.category_code = 'NOTEBOOKS'
  AND g.code = 'CUSTOMIZATION'
  AND o.code = 'CUSTOMIZED'
  AND o.label = 'Customized';

UPDATE catalog.product_options o
SET label = 'Wholesale'
FROM catalog.product_option_groups g
WHERE o.group_id = g.id
  AND g.category_code = 'NOTEBOOKS'
  AND g.code = 'CUSTOMIZATION'
  AND o.code = 'NON_CUSTOMIZED'
  AND o.label = 'Non-customized';
