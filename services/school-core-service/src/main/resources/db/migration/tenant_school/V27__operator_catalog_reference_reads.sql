-- Catalog operators must resolve their assigned schools and module entitlements.
-- These policies only widen SELECT; school and entitlement writes retain their tenant policies.
CREATE POLICY operator_catalog_read ON tenant_school.schools FOR SELECT
    USING (id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]));

CREATE POLICY operator_catalog_read ON tenant_school.school_module_entitlements FOR SELECT
    USING (school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]));
