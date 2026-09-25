-- Two product decisions taken on 2026-09-25.
--
-- 1. A customised notebook no longer has to carry artwork to be placed. The upload stays, as the
--    optional "Sample design" every category now offers, so nothing is lost except the block at
--    placement. The pre-delivery photo is untouched: it belongs to a later stage, not to ordering.
-- 2. The free-text note belongs on fliers only. It was previously shown on every category, and the
--    "Share the content" group added for bill books and fliers duplicated it.

DELETE FROM catalog.product_form_rules
WHERE category_code = 'NOTEBOOKS'
  AND rule_type = 'REQUIRE_ASSET'
  AND params ->> 'assetKind' = 'DESIGN'
  AND params ->> 'stage' = 'ON_PLACE';

-- Which categories collect a note. Sits beside form_enabled and paged, so the frontend reads it
-- from the definition rather than naming a category in code.
ALTER TABLE catalog.product_categories
    ADD COLUMN IF NOT EXISTS notes_enabled BOOLEAN NOT NULL DEFAULT false;

UPDATE catalog.product_categories SET notes_enabled = true WHERE code = 'FLIERS';

COMMENT ON COLUMN catalog.product_categories.notes_enabled IS
    'Whether the order form collects a free-text note. Fliers only, decided 2026-09-25.';

-- The note now covers what this group was for, and two text boxes on one form is one too many.
DELETE FROM catalog.product_option_groups
WHERE code = 'PRINT_CONTENT' AND category_code IN ('BILLBOOKS', 'FLIERS');
