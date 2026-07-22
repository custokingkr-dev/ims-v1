ALTER TABLE student.import_rows
    ADD COLUMN IF NOT EXISTS school_id BIGINT;

UPDATE student.import_batches b
SET school_id = s.school_id
FROM (
    SELECT import_batch_id, MIN(school_id) AS school_id
    FROM student.students
    WHERE import_batch_id IS NOT NULL
    GROUP BY import_batch_id
) s
WHERE b.id = s.import_batch_id
  AND b.school_id IS NULL;

UPDATE student.import_rows r
SET school_id = b.school_id
FROM student.import_batches b
WHERE r.batch_id = b.id
  AND r.school_id IS NULL
  AND b.school_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_student_import_batches_school_created
    ON student.import_batches (school_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_student_import_batches_school_job
    ON student.import_batches (school_id, job_id);

CREATE INDEX IF NOT EXISTS idx_student_import_batches_school_file_token
    ON student.import_batches (school_id, file_token);

CREATE INDEX IF NOT EXISTS idx_student_import_rows_school_batch_status
    ON student.import_rows (school_id, batch_id, status);

ALTER TABLE student.import_batches ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.import_batches;
CREATE POLICY tenant_isolation ON student.import_batches
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE student.import_rows ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.import_rows;
CREATE POLICY tenant_isolation ON student.import_rows
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');
