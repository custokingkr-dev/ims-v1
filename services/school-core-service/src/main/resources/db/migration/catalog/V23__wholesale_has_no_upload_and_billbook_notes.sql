-- Two product decisions taken on 2026-09-25, after the forms were seen in dev.
--
-- 1. A wholesale notebook run is printed from stock, so there is no artwork to attach. The sample
--    design upload belongs to the customised choice only. The rule already supports this: matching
--    it to CUSTOMIZATION = CUSTOMIZED hides the field for wholesale without any code change.
-- 2. Bill books collect a note after all. This reverses part of the earlier decision that the note
--    belongs to fliers alone; fliers keep theirs.

UPDATE catalog.product_form_rules
SET match_options = '{"CUSTOMIZATION":"CUSTOMIZED"}'::jsonb
WHERE category_code = 'NOTEBOOKS'
  AND rule_type = 'OFFER_ASSET'
  AND params ->> 'assetKind' = 'DESIGN'
  AND match_options = '{}'::jsonb;

UPDATE catalog.product_categories SET notes_enabled = true WHERE code = 'BILLBOOKS';

COMMENT ON COLUMN catalog.product_categories.notes_enabled IS
    'Whether the order form collects a free-text note. Fliers and bill books, as of 2026-09-25.';
