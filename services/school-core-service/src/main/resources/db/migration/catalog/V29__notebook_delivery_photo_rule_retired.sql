-- Notebook order tracker (spec v4): a notebook order placed now follows the tracker, which records delivery itself, so
-- the school is no longer asked for a pre-delivery photo when it drafts a customised notebook order.
--
-- Deactivated, not deleted. An order drafted before this keeps the rule in its own form_snapshot, and the delivery check
-- of an order placed before the tracker reads that snapshot (CatalogOrderFormService.requireStage), never the live rule:
-- such an order still needs its photo before delivery (owner decision D1). Superadmin can still see the rule, inactive,
-- in the product catalogue.
UPDATE catalog.product_form_rules
SET active = false, updated_at = now()
WHERE category_code = 'NOTEBOOKS'
  AND rule_type = 'REQUIRE_ASSET'
  AND params ->> 'assetKind' = 'PRE_DELIVERY_PHOTO'
  AND params ->> 'stage' = 'BEFORE_DELIVERY'
  AND active;
