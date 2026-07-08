-- Bound OPERATIONS catalog access to a superadmin-assigned operator school set.
-- Adds a third disjunct to the existing tenant_isolation policies: rows whose school_id is
-- in the comma-separated app.operator_schools GUC are visible/writable, in addition to the
-- caller's own current_school_id and the full session bypass_rls escape hatch.
--
-- nullif(current_setting('app.operator_schools', true), '') makes an unset/empty GUC evaluate
-- to NULL, so string_to_array(...)::bigint[] is NULL and `school_id = ANY(NULL)` is false —
-- non-operator sessions and operators with an empty assigned set are unaffected/fail-closed.

DROP POLICY IF EXISTS tenant_isolation ON catalog.catalog_orders;
CREATE POLICY tenant_isolation ON catalog.catalog_orders
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on'
              OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]))
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on'
              OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]));

DROP POLICY IF EXISTS tenant_isolation ON catalog.annual_plan_items;
CREATE POLICY tenant_isolation ON catalog.annual_plan_items
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on'
              OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]))
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on'
              OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]));
