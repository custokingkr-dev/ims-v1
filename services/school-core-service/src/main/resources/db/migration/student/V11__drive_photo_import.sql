CREATE TABLE IF NOT EXISTS student.photo_import_batches (
    id UUID PRIMARY KEY,
    school_id BIGINT NOT NULL,
    school_uid UUID NOT NULL,
    academic_year_id VARCHAR(255) NOT NULL,
    drive_folder_id VARCHAR(255) NOT NULL,
    drive_folder_name VARCHAR(255),
    workbook_file_id VARCHAR(255),
    workbook_file_name VARCHAR(255),
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    snapshot_hash VARCHAR(64),
    total_rows INTEGER NOT NULL DEFAULT 0,
    ready_count INTEGER NOT NULL DEFAULT 0,
    held_count INTEGER NOT NULL DEFAULT 0,
    error_count INTEGER NOT NULL DEFAULT 0,
    applied_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    created_by BIGINT,
    executed_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    scanned_at TIMESTAMPTZ,
    frozen_at TIMESTAMPTZ,
    executed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_photo_import_drive_folder UNIQUE (drive_folder_id),
    CONSTRAINT uq_photo_import_batch_school UNIQUE (id, school_id),
    CONSTRAINT chk_photo_import_batch_status CHECK (status IN
        ('DRAFT', 'REVIEW', 'FROZEN', 'EXECUTING', 'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'))
);

CREATE TABLE IF NOT EXISTS student.photo_import_sources (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL,
    school_id BIGINT NOT NULL,
    drive_file_id VARCHAR(255) NOT NULL,
    file_name VARCHAR(500) NOT NULL,
    mime_type VARCHAR(255),
    byte_size BIGINT,
    checksum VARCHAR(255),
    modified_time VARCHAR(64),
    source_type VARCHAR(16) NOT NULL,
    image_no VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_photo_import_source_file UNIQUE (batch_id, drive_file_id),
    CONSTRAINT fk_photo_import_source_batch_school
        FOREIGN KEY (batch_id, school_id)
        REFERENCES student.photo_import_batches(id, school_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS student.photo_import_column_mappings (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL,
    school_id BIGINT NOT NULL,
    source_header VARCHAR(255) NOT NULL,
    canonical_field VARCHAR(64) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT uq_photo_import_mapping_header UNIQUE (batch_id, source_header),
    CONSTRAINT fk_photo_import_mapping_batch_school
        FOREIGN KEY (batch_id, school_id)
        REFERENCES student.photo_import_batches(id, school_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS student.photo_import_rows (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL,
    school_id BIGINT NOT NULL,
    excel_row INTEGER NOT NULL,
    admission_no VARCHAR(255),
    workbook_name VARCHAR(255),
    class_name VARCHAR(255),
    section_name VARCHAR(255),
    image_no VARCHAR(64),
    drive_file_id VARCHAR(255),
    drive_file_name VARCHAR(500),
    student_id BIGINT REFERENCES student.students(id),
    status VARCHAR(24) NOT NULL,
    message TEXT,
    prior_photo_key VARCHAR(500),
    final_photo_key VARCHAR(500),
    source_checksum VARCHAR(255),
    applied_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_photo_import_excel_row UNIQUE (batch_id, excel_row),
    CONSTRAINT fk_photo_import_row_batch_school
        FOREIGN KEY (batch_id, school_id)
        REFERENCES student.photo_import_batches(id, school_id) ON DELETE CASCADE,
    CONSTRAINT chk_photo_import_row_status CHECK (status IN
        ('READY', 'HELD', 'ERROR', 'EXCLUDED', 'APPLIED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_photo_import_batches_school_created
    ON student.photo_import_batches (school_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_photo_import_rows_batch_status
    ON student.photo_import_rows (batch_id, status, excel_row);
CREATE INDEX IF NOT EXISTS idx_photo_import_sources_batch_image
    ON student.photo_import_sources (batch_id, image_no);

ALTER TABLE student.photo_import_batches ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.photo_import_batches;
CREATE POLICY tenant_isolation ON student.photo_import_batches
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE student.photo_import_sources ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.photo_import_sources;
CREATE POLICY tenant_isolation ON student.photo_import_sources
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE student.photo_import_column_mappings ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.photo_import_column_mappings;
CREATE POLICY tenant_isolation ON student.photo_import_column_mappings
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE student.photo_import_rows ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.photo_import_rows;
CREATE POLICY tenant_isolation ON student.photo_import_rows
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');
