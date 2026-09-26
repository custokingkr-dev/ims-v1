-- An immutable, reviewed snapshot. Confirmation does not place orders or send notifications.
CREATE TABLE catalog.annual_plan_confirmations (
    id UUID PRIMARY KEY,
    school_id BIGINT NOT NULL,
    academic_year_id VARCHAR(255) NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    fingerprint VARCHAR(64) NOT NULL,
    item_count INTEGER NOT NULL CHECK (item_count > 0),
    snapshot_json JSONB NOT NULL,
    confirmed_by BIGINT NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (school_id, academic_year_id, fingerprint),
    UNIQUE (school_id, academic_year_id, revision)
);
ALTER TABLE catalog.annual_plan_confirmations ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.annual_plan_confirmations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.annual_plan_confirmations
    USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on');
-- A selective GRANT alone would leave inherited owner-default UPDATE/DELETE
-- privileges intact. Runtime confirmations are append-only, including under bypass.
REVOKE ALL ON catalog.annual_plan_confirmations FROM PUBLIC;
DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
    REVOKE ALL ON catalog.annual_plan_confirmations FROM app_rt;
    GRANT SELECT, INSERT ON catalog.annual_plan_confirmations TO app_rt;
END IF; END $$;
