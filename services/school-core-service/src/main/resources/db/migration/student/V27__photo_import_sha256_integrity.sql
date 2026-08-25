ALTER TABLE student.photo_import_sources
    ADD COLUMN IF NOT EXISTS sha256_checksum CHAR(64);

ALTER TABLE student.photo_import_rows
    ADD COLUMN IF NOT EXISTS source_sha256 CHAR(64);

ALTER TABLE student.photo_import_recoveries
    ADD COLUMN IF NOT EXISTS source_sha256 CHAR(64);

ALTER TABLE student.photo_import_sources
    DROP CONSTRAINT IF EXISTS chk_photo_import_source_sha256,
    ADD CONSTRAINT chk_photo_import_source_sha256 CHECK (
        sha256_checksum IS NULL OR sha256_checksum ~ '^[0-9a-f]{64}$'
    );

ALTER TABLE student.photo_import_rows
    DROP CONSTRAINT IF EXISTS chk_photo_import_row_source_sha256,
    ADD CONSTRAINT chk_photo_import_row_source_sha256 CHECK (
        source_sha256 IS NULL OR source_sha256 ~ '^[0-9a-f]{64}$'
    );

ALTER TABLE student.photo_import_recoveries
    DROP CONSTRAINT IF EXISTS chk_photo_import_recovery_source_sha256,
    ADD CONSTRAINT chk_photo_import_recovery_source_sha256 CHECK (
        source_sha256 IS NULL OR source_sha256 ~ '^[0-9a-f]{64}$'
    );

COMMENT ON COLUMN student.photo_import_sources.checksum IS
    'Legacy Google Drive MD5 metadata retained for diagnostics; never an integrity authorization gate.';
COMMENT ON COLUMN student.photo_import_sources.sha256_checksum IS
    'Google Drive SHA-256 content checksum used for source integrity verification.';
COMMENT ON COLUMN student.photo_import_rows.source_checksum IS
    'Legacy Google Drive MD5 metadata retained for diagnostics; never an integrity authorization gate.';
COMMENT ON COLUMN student.photo_import_rows.source_sha256 IS
    'Reviewed Google Drive SHA-256 checksum required for automated execution and recovery.';
COMMENT ON COLUMN student.photo_import_recoveries.source_checksum IS
    'Legacy Google Drive MD5 metadata retained for diagnostics; never an integrity authorization gate.';
COMMENT ON COLUMN student.photo_import_recoveries.source_sha256 IS
    'SHA-256 checksum copied from the reviewed import row for recovery audit evidence.';
