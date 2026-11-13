-- Who took an order file out of use (item order workflows design D40: design-file uploads and removals join the order's
-- Activity history, and changes are logged with their actor). uploaded_by already names the uploader; superseded_by names
-- the user who removed the file, or whose upload replaced it, and is set together with superseded_at. Null for files
-- removed before this column existed, and for removals by a school-core revision older than this one while it still serves
-- during a release: that revision does not know the column, so its removals stay unattributed, as every removal was before.
ALTER TABLE catalog.catalog_order_assets ADD COLUMN superseded_by BIGINT;

COMMENT ON COLUMN catalog.catalog_order_assets.superseded_by IS
    'User who removed or replaced the file (set with superseded_at); null when unknown.';
