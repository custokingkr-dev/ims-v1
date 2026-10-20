CREATE TABLE reporting.trusted_command_audit (
    id uuid PRIMARY KEY,
    action varchar(100) NOT NULL,
    entity_id varchar(255) NOT NULL,
    actor_user_id bigint,
    school_id bigint,
    request_id varchar(64) NOT NULL,
    authority varchar(16) NOT NULL CHECK(authority='SERVER'),
    outcome varchar(16) NOT NULL CHECK(outcome='SUCCESS'),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON reporting.trusted_command_audit FROM PUBLIC;
ALTER TABLE reporting.trusted_command_audit ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON reporting.trusted_command_audit
USING (school_id=nullif(current_setting('app.current_school_id',true),'')::bigint OR current_setting('app.bypass_rls',true)='on')
WITH CHECK (school_id=nullif(current_setting('app.current_school_id',true),'')::bigint OR current_setting('app.bypass_rls',true)='on');
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='app_rt') THEN
    REVOKE ALL ON reporting.trusted_command_audit FROM app_rt;
    GRANT SELECT,INSERT ON reporting.trusted_command_audit TO app_rt;
END IF; END $$;
