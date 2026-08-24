CREATE TABLE IF NOT EXISTS student.photo_import_recoveries (
    id UUID PRIMARY KEY,
    row_id UUID NOT NULL REFERENCES student.photo_import_rows(id) ON DELETE CASCADE,
    batch_id UUID NOT NULL,
    school_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    recovery_version VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    requested_by BIGINT,
    drive_file_id VARCHAR(255) NOT NULL,
    source_checksum VARCHAR(255),
    prior_photo_key VARCHAR(1000),
    recovered_photo_key VARCHAR(1000),
    message TEXT,
    attempt_count INTEGER NOT NULL DEFAULT 1,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_photo_import_recovery_version UNIQUE (row_id, recovery_version),
    CONSTRAINT fk_photo_import_recovery_batch_school
        FOREIGN KEY (batch_id, school_id)
        REFERENCES student.photo_import_batches(id, school_id) ON DELETE CASCADE,
    CONSTRAINT chk_photo_import_recovery_status
        CHECK (status IN ('EXECUTING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_photo_import_recovery_attempt_count CHECK (attempt_count > 0)
);

CREATE INDEX IF NOT EXISTS idx_photo_import_recoveries_batch_status
    ON student.photo_import_recoveries (batch_id, school_id, status, updated_at DESC);

ALTER TABLE student.photo_import_recoveries ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.photo_import_recoveries;
CREATE POLICY tenant_isolation ON student.photo_import_recoveries
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');
