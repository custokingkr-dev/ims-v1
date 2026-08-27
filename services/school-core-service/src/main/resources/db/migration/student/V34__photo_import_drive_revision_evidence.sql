ALTER TABLE student.photo_import_sources
    ADD COLUMN IF NOT EXISTS drive_head_revision_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS drive_version VARCHAR(64);

COMMENT ON COLUMN student.photo_import_sources.drive_head_revision_id IS
    'Google Drive binary head revision captured at scan time and compared before execution/recovery.';
COMMENT ON COLUMN student.photo_import_sources.drive_version IS
    'Google Drive monotonically increasing file version captured at scan time.';

COMMENT ON COLUMN student.photo_import_sources.sha256_checksum IS
    'SHA-256 content checksum supplied by Drive or computed from downloaded source bytes.';
COMMENT ON COLUMN student.photo_import_rows.source_sha256 IS
    'Certified SHA-256 content checksum; computed during execution when Drive omits SHA-256 metadata.';
