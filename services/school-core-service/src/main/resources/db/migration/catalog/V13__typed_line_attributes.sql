-- Per-line typed values, for categories whose lines carry a measurement or free text rather than
-- only selections from a fixed list. Flex needs length and breadth in feet; flier needs a free-text
-- size and a GSM. See docs/product/catalog-order-forms-expansion.md.
--
-- An option group now declares how it captures its value. SELECT keeps today's behaviour and stays
-- the default, so every existing group is unaffected.

ALTER TABLE catalog.product_option_groups
    ADD COLUMN IF NOT EXISTS input_type VARCHAR(10) NOT NULL DEFAULT 'SELECT',
    ADD COLUMN IF NOT EXISTS unit VARCHAR(16) NOT NULL DEFAULT '';

ALTER TABLE catalog.product_option_groups
    DROP CONSTRAINT IF EXISTS product_option_groups_input_type_check;

ALTER TABLE catalog.product_option_groups
    ADD CONSTRAINT product_option_groups_input_type_check
    CHECK (input_type IN ('SELECT', 'TEXT', 'INTEGER', 'DECIMAL'));

-- Values captured by non-SELECT groups, keyed by group code. Empty for every existing line.
ALTER TABLE catalog.catalog_order_lines
    ADD COLUMN IF NOT EXISTS attributes JSONB NOT NULL DEFAULT '{}';

-- page_count is meaningless outside notebooks: a flex sheet and a flier have no page count, and the
-- flier prototype is explicitly single-page. Rather than rename a column that reporting, the rule
-- engine's targetField values and the frontend all depend on, non-paged categories store 1. The
-- naming debt is recorded in the expansion document rather than paid down here, where it would
-- touch the reporting projection mid-feature.
COMMENT ON COLUMN catalog.catalog_order_lines.page_count IS
    'Printed pages for paged categories such as notebooks; 1 for categories with no page concept.';
COMMENT ON COLUMN catalog.catalog_order_lines.book_count IS
    'Units ordered on this line, whatever the category calls them (books, sheets, fliers, belts).';
COMMENT ON COLUMN catalog.catalog_order_lines.attributes IS
    'Typed per-line values keyed by option group code, for groups whose input_type is not SELECT.';
