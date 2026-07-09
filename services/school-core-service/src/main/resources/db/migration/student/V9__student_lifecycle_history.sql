ALTER TABLE student.students
    ADD COLUMN IF NOT EXISTS deleted_reason TEXT;

ALTER TABLE student.import_batches
    ADD COLUMN IF NOT EXISTS school_id BIGINT,
    ADD COLUMN IF NOT EXISTS original_file_name VARCHAR(512),
    ADD COLUMN IF NOT EXISTS original_file_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS original_file_size BIGINT,
    ADD COLUMN IF NOT EXISTS original_file_content_type VARCHAR(255),
    ADD COLUMN IF NOT EXISTS original_file_object_path VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS uploaded_by BIGINT,
    ADD COLUMN IF NOT EXISTS verified_student_count INTEGER;

ALTER TABLE student.import_rows
    ADD COLUMN IF NOT EXISTS applied_student_id BIGINT,
    ADD COLUMN IF NOT EXISTS applied_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS student.student_enrollments (
    id VARCHAR(255) PRIMARY KEY,
    student_id BIGINT NOT NULL,
    school_id BIGINT NOT NULL,
    academic_year_id VARCHAR(255) NOT NULL,
    class_id VARCHAR(255) NOT NULL,
    section_id VARCHAR(255) NOT NULL,
    roll_no VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    effective_from DATE,
    effective_to DATE,
    reason TEXT,
    source_type VARCHAR(64),
    source_id VARCHAR(255),
    created_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_student_enrollments_student
    ON student.student_enrollments (student_id, academic_year_id, created_at);

CREATE INDEX IF NOT EXISTS idx_student_enrollments_school_year
    ON student.student_enrollments (school_id, academic_year_id, class_id, section_id);

CREATE INDEX IF NOT EXISTS idx_student_enrollments_active
    ON student.student_enrollments (student_id)
    WHERE status = 'ACTIVE' AND effective_to IS NULL;

CREATE TABLE IF NOT EXISTS student.student_promotion_batches (
    id VARCHAR(255) PRIMARY KEY,
    school_id BIGINT NOT NULL,
    source_academic_year_id VARCHAR(255) NOT NULL,
    target_academic_year_id VARCHAR(255) NOT NULL,
    source_class_id VARCHAR(255),
    source_section_id VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    notes TEXT,
    created_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    applied_by BIGINT,
    applied_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_student_promotion_batches_school
    ON student.student_promotion_batches (school_id, status, created_at);

CREATE TABLE IF NOT EXISTS student.student_promotion_batch_items (
    id VARCHAR(255) PRIMARY KEY,
    batch_id VARCHAR(255) NOT NULL REFERENCES student.student_promotion_batches(id) ON DELETE CASCADE,
    school_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    source_class_id VARCHAR(255) NOT NULL,
    source_section_id VARCHAR(255) NOT NULL,
    target_class_id VARCHAR(255),
    target_section_id VARCHAR(255),
    action VARCHAR(32) NOT NULL DEFAULT 'PROMOTE',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_student_promotion_items_batch
    ON student.student_promotion_batch_items (batch_id, status);

CREATE INDEX IF NOT EXISTS idx_student_promotion_items_student
    ON student.student_promotion_batch_items (student_id);

INSERT INTO student.student_enrollments (
    id, student_id, school_id, academic_year_id, class_id, section_id, roll_no,
    status, effective_from, reason, source_type, source_id, created_at, updated_at
)
SELECT
    'backfill-' || s.id,
    s.id,
    s.school_id,
    s.academic_year_id,
    s.class_id,
    s.section_id,
    s.roll_no,
    CASE WHEN s.deleted_at IS NULL THEN 'ACTIVE' ELSE 'DELETED' END,
    COALESCE(s.created_at::date, CURRENT_DATE),
    'Backfilled from current student placement',
    'BACKFILL',
    CAST(s.id AS VARCHAR),
    COALESCE(s.created_at, now()),
    COALESCE(s.updated_at, now())
FROM student.students s
WHERE NOT EXISTS (
    SELECT 1
    FROM student.student_enrollments e
    WHERE e.student_id = s.id
      AND e.source_type = 'BACKFILL'
);

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

ALTER TABLE student.student_enrollments ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.student_enrollments;
CREATE POLICY tenant_isolation ON student.student_enrollments
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE student.student_promotion_batches ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.student_promotion_batches;
CREATE POLICY tenant_isolation ON student.student_promotion_batches
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE student.student_promotion_batch_items ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.student_promotion_batch_items;
CREATE POLICY tenant_isolation ON student.student_promotion_batch_items
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');
