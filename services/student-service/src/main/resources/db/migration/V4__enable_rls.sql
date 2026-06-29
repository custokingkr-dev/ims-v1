-- Enable Row-Level Security on the cleanly-scoped (NOT NULL school_id) student tables.
-- ENABLE (not FORCE): the table owner (appuser) bypasses, so Flyway/seed keep working;
-- the unprivileged runtime role app_rt is subject. Policy reads the per-request GUCs.

ALTER TABLE student.students ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.students;
CREATE POLICY tenant_isolation ON student.students
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE student.student_review_campaigns ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.student_review_campaigns;
CREATE POLICY tenant_isolation ON student.student_review_campaigns
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE student.student_review_items ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.student_review_items;
CREATE POLICY tenant_isolation ON student.student_review_items
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');
