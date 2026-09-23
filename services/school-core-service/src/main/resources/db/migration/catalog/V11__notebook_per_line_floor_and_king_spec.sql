-- Notebook order form revised against the 2026-09-23 prototype.
--
-- 1. The customised quantity rule changes from "exactly 1000 books across all sizes and ruling
--    lines combined" to a floor of 1000 on each customised ruling line. The prototype raises a low
--    count to the floor rather than rejecting the order, so this is a clamp like the page rounding
--    rule, not a validation. Recorded in docs/product/catalog-order-forms-expansion.md, which also
--    notes that this supersedes decision D-2 of 2026-09-15.
-- 2. King gains confirmed dimensions (19 x 26 cm), so it stops being visible-but-unorderable.

ALTER TABLE catalog.product_form_rules
    DROP CONSTRAINT IF EXISTS product_form_rules_rule_type_check;

ALTER TABLE catalog.product_form_rules
    ADD CONSTRAINT product_form_rules_rule_type_check
    CHECK (rule_type IN ('REQUIRE_QUANTITY_TOTAL', 'ROUND_TO_MULTIPLE', 'FLOOR_VALUE',
                         'MIN_VALUE', 'MAX_VALUE', 'REQUIRE_ASSET'));

-- Replace the order-wide exact total with a per-line floor. Both are keyed on the same match, so
-- the delete is scoped to the rule type rather than to the whole category.
DELETE FROM catalog.product_form_rules
    WHERE category_code = 'NOTEBOOKS' AND rule_type = 'REQUIRE_QUANTITY_TOTAL';

INSERT INTO catalog.product_form_rules (category_code, rule_type, target_field, match_options, params, priority, message)
SELECT 'NOTEBOOKS', 'FLOOR_VALUE', 'BOOK_COUNT', '{"CUSTOMIZATION":"CUSTOMIZED"}', '{"value":1000}', 10,
       'Customised ruling lines are raised to the minimum of 1000 books'
WHERE NOT EXISTS (
    SELECT 1 FROM catalog.product_form_rules
    WHERE category_code = 'NOTEBOOKS' AND rule_type = 'FLOOR_VALUE' AND target_field = 'BOOK_COUNT');

UPDATE catalog.product_options o
SET spec_text = '19 cm x 26 cm',
    width_mm = 190,
    height_mm = 260,
    spec_status = 'CONFIRMED'
FROM catalog.product_option_groups g
WHERE o.group_id = g.id
  AND g.category_code = 'NOTEBOOKS'
  AND g.code = 'SIZE'
  AND o.code = 'KING'
  AND o.spec_status = 'PENDING_SPEC';
