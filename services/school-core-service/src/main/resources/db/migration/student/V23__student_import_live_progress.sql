CREATE TABLE IF NOT EXISTS student.import_job_progress (
    job_id VARCHAR(255) PRIMARY KEY,
    batch_id VARCHAR(255) NOT NULL REFERENCES student.import_batches(id) ON DELETE CASCADE,
    school_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'READY',
    phase VARCHAR(40) NOT NULL DEFAULT 'READY',
    total_rows INTEGER NOT NULL DEFAULT 0,
    processed_rows INTEGER NOT NULL DEFAULT 0,
    inserted INTEGER NOT NULL DEFAULT 0,
    skipped INTEGER NOT NULL DEFAULT 0,
    percent_complete INTEGER NOT NULL DEFAULT 0,
    message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT chk_student_import_progress_status
        CHECK (status IN ('READY', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_student_import_progress_percent
        CHECK (percent_complete BETWEEN 0 AND 100),
    CONSTRAINT chk_student_import_progress_counts
        CHECK (total_rows >= 0 AND processed_rows >= 0 AND inserted >= 0 AND skipped >= 0)
);

CREATE INDEX IF NOT EXISTS idx_student_import_progress_school_updated
    ON student.import_job_progress (school_id, updated_at DESC);

ALTER TABLE student.import_job_progress ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.import_job_progress;
CREATE POLICY tenant_isolation ON student.import_job_progress
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');
