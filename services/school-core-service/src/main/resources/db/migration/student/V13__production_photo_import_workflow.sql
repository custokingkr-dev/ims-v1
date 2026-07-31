ALTER TABLE student.photo_import_batches
    DROP CONSTRAINT IF EXISTS uq_photo_import_drive_folder;

CREATE UNIQUE INDEX IF NOT EXISTS uq_photo_import_active_drive_folder
    ON student.photo_import_batches (drive_folder_id)
    WHERE status IN ('DRAFT', 'REVIEW', 'FROZEN', 'EXECUTING', 'PARTIAL', 'FAILED');

CREATE INDEX IF NOT EXISTS idx_photo_import_batches_folder_status
    ON student.photo_import_batches (drive_folder_id, status, created_at DESC);

ALTER TABLE student.photo_import_batches
    ADD COLUMN IF NOT EXISTS workbook_object_key VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS photographer_access_expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS photographer_access_revoked_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cancelled_by BIGINT;

UPDATE student.photo_import_batches
SET photographer_access_expires_at = created_at + interval '14 days'
WHERE photographer_access_expires_at IS NULL;

ALTER TABLE student.photo_import_batches
    ALTER COLUMN photographer_access_expires_at SET DEFAULT (now() + interval '14 days');

ALTER TABLE student.photo_import_rows
    ADD COLUMN IF NOT EXISTS crop_x NUMERIC(5,4) NOT NULL DEFAULT 0.5,
    ADD COLUMN IF NOT EXISTS crop_y NUMERIC(5,4) NOT NULL DEFAULT 0.5,
    ADD COLUMN IF NOT EXISTS manually_reviewed BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS source_object_key VARCHAR(1000);

ALTER TABLE student.photo_import_rows
    DROP CONSTRAINT IF EXISTS chk_photo_import_row_crop_x,
    DROP CONSTRAINT IF EXISTS chk_photo_import_row_crop_y;

ALTER TABLE student.photo_import_rows
    ADD CONSTRAINT chk_photo_import_row_crop_x CHECK (crop_x BETWEEN 0 AND 1),
    ADD CONSTRAINT chk_photo_import_row_crop_y CHECK (crop_y BETWEEN 0 AND 1);
