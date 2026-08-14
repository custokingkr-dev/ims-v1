CREATE TABLE IF NOT EXISTS student.student_export_audit (
    id UUID PRIMARY KEY,
    school_id BIGINT NOT NULL,
    requested_by BIGINT,
    status VARCHAR(16) NOT NULL,
    student_count INTEGER NOT NULL DEFAULT 0,
    exported_photo_count INTEGER NOT NULL DEFAULT 0,
    missing_photo_count INTEGER NOT NULL DEFAULT 0,
    failure_reason VARCHAR(500),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_student_export_audit_status CHECK (status IN ('STARTED', 'COMPLETED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_student_export_audit_school_started
    ON student.student_export_audit (school_id, started_at DESC);

ALTER TABLE student.student_export_audit ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.student_export_audit;
CREATE POLICY tenant_isolation ON student.student_export_audit
  USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
         OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
    GRANT SELECT, INSERT, UPDATE ON student.student_export_audit TO app_rt;
  END IF;
END $$;
