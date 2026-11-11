CREATE TABLE workflow.workflow_school_whatsapp_group_links (
    school_id   BIGINT       NOT NULL,
    target_key  VARCHAR(64)  NOT NULL CHECK (target_key IN (
        'SCHOOL_GROUP', 'INTERNAL_APPROVAL',
        'board_shree', 'board_national', 'board_prime',
        'lam_glosspro', 'lam_crystal', 'lam_brightfinish',
        'binder_perfect', 'binder_classic', 'binder_metro',
        'offset_metro', 'offset_precision', 'offset_cityline'
    )),
    invite_url  VARCHAR(2048) NOT NULL CHECK (
        invite_url ~ '^https://chat\.whatsapp\.com/[A-Za-z0-9_-]{10,64}$'
    ),
    updated_by  BIGINT       NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (school_id, target_key)
);

ALTER TABLE workflow.workflow_school_whatsapp_group_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow.workflow_school_whatsapp_group_links FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workflow.workflow_school_whatsapp_group_links
    USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on'
        OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]))
    WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on'
        OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON workflow.workflow_school_whatsapp_group_links TO app_rt;
    END IF;
END $$;
