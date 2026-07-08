-- RLS backstop for reporting fact/dimension tables projected from cross-service outbox events.
--
-- These tables are written by contextless event consumers (projectors) that run with NO
-- TenantContext / app.current_school_id set. To keep WITH CHECK from rejecting those writes,
-- every projector upsert method touching a table enabled here MUST call
-- ProjectorRls.allow(jdbcClient) as the first statement of its @Transactional method — this sets
-- app.bypass_rls='on' transaction-locally (set_config(..., true)) so the bypass never leaks
-- outside that transaction. Any FUTURE fact/dim table added here needs the same treatment in its
-- projector repository.
--
-- dim_school, dim_academic_year, command_center_feed, reporting_event_inbox, and
-- billing_invoice_read intentionally remain WITHOUT RLS (global-by-design / nullable school_id
-- platform-wide projections) — do not add policies to those here.

ALTER TABLE reporting.fact_attendance_daily ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON reporting.fact_attendance_daily;
CREATE POLICY tenant_isolation ON reporting.fact_attendance_daily
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE reporting.fact_payment ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON reporting.fact_payment;
CREATE POLICY tenant_isolation ON reporting.fact_payment
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE reporting.fact_fee_assignment ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON reporting.fact_fee_assignment;
CREATE POLICY tenant_isolation ON reporting.fact_fee_assignment
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE reporting.fact_catalog_order ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON reporting.fact_catalog_order;
CREATE POLICY tenant_isolation ON reporting.fact_catalog_order
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE reporting.fact_firefighting_request ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON reporting.fact_firefighting_request;
CREATE POLICY tenant_isolation ON reporting.fact_firefighting_request
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE reporting.fact_student_review_item ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON reporting.fact_student_review_item;
CREATE POLICY tenant_isolation ON reporting.fact_student_review_item
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE reporting.dim_section ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON reporting.dim_section;
CREATE POLICY tenant_isolation ON reporting.dim_section
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE reporting.dim_student ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON reporting.dim_student;
CREATE POLICY tenant_isolation ON reporting.dim_student
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');
