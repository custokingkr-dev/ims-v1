-- Whether a category's lines have a printed-page count. Only notebooks do: a flex sheet, a belt and
-- a bill book have no page concept, and the flier prototype is explicitly single-page.
--
-- Without this the order form has to guess, and the page field would appear on every new category.
-- Lines in non-paged categories still store page_count = 1, as recorded on that column in V13.

ALTER TABLE catalog.product_categories
    ADD COLUMN IF NOT EXISTS paged BOOLEAN NOT NULL DEFAULT false;

UPDATE catalog.product_categories SET paged = true WHERE code = 'NOTEBOOKS';
