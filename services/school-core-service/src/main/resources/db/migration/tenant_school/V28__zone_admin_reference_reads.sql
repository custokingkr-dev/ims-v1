-- Zone administrators may read their assigned zone and its active school mappings.
-- All write policies and access to admin assignments remain unchanged.
CREATE POLICY zone_admin_read ON tenant_school.zones FOR SELECT
    USING (id = nullif(current_setting('app.current_zone_id', true), '')::bigint);

CREATE POLICY zone_admin_read ON tenant_school.zone_school_mappings FOR SELECT
    USING (active = true AND zone_id = nullif(current_setting('app.current_zone_id', true), '')::bigint);

CREATE POLICY zone_admin_read ON tenant_school.schools FOR SELECT
    USING (EXISTS (
        SELECT 1 FROM tenant_school.zone_school_mappings m
        WHERE m.school_id = schools.id AND m.active = true
          AND m.zone_id = nullif(current_setting('app.current_zone_id', true), '')::bigint
    ));
