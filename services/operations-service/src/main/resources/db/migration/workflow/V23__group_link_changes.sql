-- Who changed which WhatsApp group link, when, from where, and what it held before (design D34). Every save path writes
-- one row per real change: Workflow set-up (a school's groups, its item groups, the global internal approval group), an
-- order's School Group Chat card, the spreadsheet import and the vendor directory. The order's own history keeps
-- recording GROUP_LINK_SET as before; this log is the one place that covers every group, including those that belong to
-- no order. Both links are kept, so superadmin can see and put back an earlier group. The system only stores invite
-- links and opens them; it sends nothing.
--
-- A row about a school's group carries the school (tenant data under row-level security, like the links themselves);
-- the global internal approval group and a vendor's group belong to no school, so only superadmin, whose connection
-- bypasses row-level security, sees or writes those rows. Append-only: the runtime role adds and reads rows, never
-- changes or removes them.
CREATE TABLE workflow.workflow_group_link_changes (
    id                     BIGSERIAL     PRIMARY KEY,
    kind                   VARCHAR(30)   NOT NULL CONSTRAINT ck_wglc_kind CHECK (kind IN (
                               'SCHOOL_GROUP', 'INTERNAL_APPROVAL', 'ITEM', 'GLOBAL_INTERNAL_APPROVAL', 'VENDOR')),
    school_id              BIGINT,
    category_code          VARCHAR(60)   CONSTRAINT ck_wglc_category_code
                           CHECK (category_code IS NULL OR category_code ~ '^[A-Z][A-Z0-9_]{1,59}$'),
    -- No foreign key: the log outlives what it describes (vendors are deactivated, never deleted, by the application).
    vendor_id              BIGINT,
    before_url             VARCHAR(2048) CONSTRAINT ck_wglc_before_url
                           CHECK (before_url IS NULL OR before_url ~ '^https://chat\.whatsapp\.com/[A-Za-z0-9_-]{10,64}$'),
    after_url              VARCHAR(2048) CONSTRAINT ck_wglc_after_url
                           CHECK (after_url IS NULL OR after_url ~ '^https://chat\.whatsapp\.com/[A-Za-z0-9_-]{10,64}$'),
    source                 VARCHAR(20)   NOT NULL CONSTRAINT ck_wglc_source CHECK (source IN (
                               'SETUP', 'ORDER', 'IMPORT', 'VENDOR_DIRECTORY')),
    -- The order's number when the change was made on an order's School Group Chat card.
    order_id               VARCHAR(255)  CONSTRAINT ck_wglc_order_id CHECK (order_id IS NULL OR order_id ~ '^[A-Za-z0-9_-]{1,255}$'),
    changed_by             BIGINT        NOT NULL,
    changed_by_email       VARCHAR(320),
    changed_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    request_correlation_id VARCHAR(128),
    -- Saving the link already saved is not a change.
    CONSTRAINT ck_wglc_changed CHECK (before_url IS DISTINCT FROM after_url),
    -- Each kind names exactly the group it is about.
    CONSTRAINT ck_wglc_target CHECK (CASE kind
        WHEN 'SCHOOL_GROUP'             THEN school_id IS NOT NULL AND category_code IS NULL     AND vendor_id IS NULL
        WHEN 'INTERNAL_APPROVAL'        THEN school_id IS NOT NULL AND category_code IS NULL     AND vendor_id IS NULL
        WHEN 'ITEM'                     THEN school_id IS NOT NULL AND category_code IS NOT NULL AND vendor_id IS NULL
        WHEN 'GLOBAL_INTERNAL_APPROVAL' THEN school_id IS NULL     AND category_code IS NULL     AND vendor_id IS NULL
        WHEN 'VENDOR'                   THEN school_id IS NULL     AND category_code IS NULL     AND vendor_id IS NOT NULL
    END),
    -- Only an order's card names an order, and it always does; only an item group can be changed there.
    CONSTRAINT ck_wglc_order CHECK ((source = 'ORDER') = (order_id IS NOT NULL) AND (source <> 'ORDER' OR kind = 'ITEM'))
);
-- Newest first, for everything and for one school.
CREATE INDEX idx_wglc_school_id ON workflow.workflow_group_link_changes (school_id, id DESC) WHERE school_id IS NOT NULL;

ALTER TABLE workflow.workflow_group_link_changes ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow.workflow_group_link_changes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workflow.workflow_group_link_changes
    USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on'
        OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]))
    WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on'
        OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]));

-- Runtime grants: add and read only (the dedicated-role ACL is resynchronised by afterMigrate, so the revoke reaches it
-- too). Default privileges may have granted UPDATE and DELETE on creation; they are taken back explicitly, as V20 does.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        GRANT SELECT, INSERT ON workflow.workflow_group_link_changes TO app_rt;
        REVOKE UPDATE, DELETE, TRUNCATE ON workflow.workflow_group_link_changes FROM app_rt;
        GRANT USAGE, SELECT ON SEQUENCE workflow.workflow_group_link_changes_id_seq TO app_rt;
    END IF;
END $$;
