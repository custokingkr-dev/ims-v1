CREATE TABLE IF NOT EXISTS student.photo_import_drive_folders (
    school_id BIGINT NOT NULL,
    school_uid UUID NOT NULL,
    academic_year_id VARCHAR(255) NOT NULL,
    root_folder_id VARCHAR(255) NOT NULL,
    school_folder_id VARCHAR(255),
    academic_year_folder_id VARCHAR(255),
    intake_folder_id VARCHAR(255),
    intake_folder_name VARCHAR(255),
    intake_folder_url VARCHAR(1000),
    status VARCHAR(24) NOT NULL DEFAULT 'PROVISIONING',
    last_error TEXT,
    provisioned_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (school_id, academic_year_id),
    CONSTRAINT uq_photo_import_managed_intake UNIQUE (intake_folder_id),
    CONSTRAINT chk_photo_import_drive_folder_status CHECK (
        status IN ('PROVISIONING', 'READY', 'FAILED')
    )
);

CREATE INDEX IF NOT EXISTS idx_photo_import_drive_folders_status
    ON student.photo_import_drive_folders (status, updated_at);

ALTER TABLE student.photo_import_drive_folders ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.photo_import_drive_folders;
CREATE POLICY tenant_isolation ON student.photo_import_drive_folders
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');
