-- Idempotent creation of the unprivileged runtime role app_rt.
-- psql vars:
--   app_rt_password : login password for app_rt        (required)
--   owner           : schema-owning / Flyway role       (required: appuser in prod, postgres in local)
--   set_superuser   : if defined, SET ROLE cloudsqlsuperuser for role creation (prod only)
-- Assumes target schemas already exist; grants apply only to schemas present in pg_namespace.
\set ON_ERROR_STOP on

-- Role creation/repair needs CREATEROLE; in prod the connecting appuser elevates via cloudsqlsuperuser.
\if :{?set_superuser}
SET ROLE cloudsqlsuperuser;
\endif

SELECT 'CREATE ROLE app_rt LOGIN NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='app_rt')
\gexec

ALTER ROLE app_rt LOGIN NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS PASSWORD :'app_rt_password';

-- Let the owner/admin SET ROLE app_rt (needed for the audit's live probes). Harmless: app_rt is unprivileged.
GRANT app_rt TO :"owner";

\if :{?set_superuser}
RESET ROLE;
\endif

-- Grants on existing target schemas (intersection with pg_namespace keeps this re-runnable
-- before all services have migrated, and harmless if a schema is absent).
SELECT format('GRANT USAGE ON SCHEMA %I TO app_rt', n.nspname)
FROM (VALUES ('identity'),('tenant_school'),('student'),('attendance'),('fee'),
             ('catalog'),('workflow'),('firefighting'),('reporting'),
             ('notification'),('audit'),('billing')) AS s(name)
JOIN pg_namespace n ON n.nspname = s.name
\gexec

SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO app_rt', n.nspname)
FROM (VALUES ('identity'),('tenant_school'),('student'),('attendance'),('fee'),
             ('catalog'),('workflow'),('firefighting'),('reporting'),
             ('notification'),('audit'),('billing')) AS s(name)
JOIN pg_namespace n ON n.nspname = s.name
\gexec

SELECT format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO app_rt', n.nspname)
FROM (VALUES ('identity'),('tenant_school'),('student'),('attendance'),('fee'),
             ('catalog'),('workflow'),('firefighting'),('reporting'),
             ('notification'),('audit'),('billing')) AS s(name)
JOIN pg_namespace n ON n.nspname = s.name
\gexec

-- Default privileges so FUTURE owner-created objects auto-grant DML to app_rt.
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_rt', :'owner', n.nspname)
FROM (VALUES ('identity'),('tenant_school'),('student'),('attendance'),('fee'),
             ('catalog'),('workflow'),('firefighting'),('reporting'),
             ('notification'),('audit'),('billing')) AS s(name)
JOIN pg_namespace n ON n.nspname = s.name
\gexec

SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT USAGE, SELECT ON SEQUENCES TO app_rt', :'owner', n.nspname)
FROM (VALUES ('identity'),('tenant_school'),('student'),('attendance'),('fee'),
             ('catalog'),('workflow'),('firefighting'),('reporting'),
             ('notification'),('audit'),('billing')) AS s(name)
JOIN pg_namespace n ON n.nspname = s.name
\gexec

-- The order workflows' logs are append-only (operations workflow V11 revokes UPDATE and DELETE on the notes and
-- follow-ups, V20 on the call logs). The blanket table grant above hands both back on every re-run (environment
-- rebuilds, ensure-app-rt-local.ps1), so take them away again. Selecting from pg_tables makes this a no-op until the
-- operations service has created the tables.
SELECT format('REVOKE UPDATE, DELETE ON %I.%I FROM app_rt', schemaname, tablename)
FROM pg_tables
WHERE schemaname = 'workflow' AND tablename IN ('workflow_internal_notes', 'workflow_follow_ups', 'workflow_call_logs')
\gexec

-- School-core's catalog migrations narrow two more tables once, and the blanket grant above widens them again:
--   * order_number_counters is SELECT, INSERT and UPDATE only (catalog V37). Deleting a counter row restarts that item's
--     running number for the year, so later order numbers repeat (D11).
--   * product_order_workflows and product_order_workflow_steps are read-only (catalog V38 retired that workflow).
-- Each school-core migration copies app_rt's privileges onto ims_school_core_rt (afterMigrate__dedicated_runtime_acl.sql),
-- so anything left here also reaches the dedicated runtime role. Selecting from pg_tables keeps both no-ops until
-- school-core has created the tables.
SELECT format('REVOKE DELETE, TRUNCATE ON %I.%I FROM app_rt', schemaname, tablename)
FROM pg_tables
WHERE schemaname = 'catalog' AND tablename = 'order_number_counters'
\gexec

SELECT format('REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON %I.%I FROM app_rt', schemaname, tablename)
FROM pg_tables
WHERE schemaname = 'catalog' AND tablename IN ('product_order_workflows', 'product_order_workflow_steps')
\gexec

-- Final assertions (run as owner; pg catalogs are world-readable).
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='app_rt'
             AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolinherit OR NOT rolcanlogin)) THEN
    RAISE EXCEPTION 'app_rt has forbidden attributes after creation';
  END IF;
  IF EXISTS (
    SELECT 1 FROM pg_auth_members m
    JOIN pg_roles grp ON grp.oid=m.roleid
    JOIN pg_roles mem ON mem.oid=m.member
    WHERE mem.rolname='app_rt' AND grp.rolname='cloudsqlsuperuser'
  ) THEN
    RAISE EXCEPTION 'app_rt must not be a member of cloudsqlsuperuser';
  END IF;
  -- The narrowed tables above. A privilege another grantor gave app_rt survives the owner's REVOKE, so check what app_rt
  -- actually holds. to_regclass is NULL for a table not created yet, and has_table_privilege is then NULL too.
  IF EXISTS (
    SELECT 1
    FROM (VALUES ('catalog.order_number_counters', 'DELETE'),
                 ('catalog.product_order_workflows', 'INSERT'),
                 ('catalog.product_order_workflows', 'UPDATE'),
                 ('catalog.product_order_workflows', 'DELETE'),
                 ('catalog.product_order_workflow_steps', 'INSERT'),
                 ('catalog.product_order_workflow_steps', 'UPDATE'),
                 ('catalog.product_order_workflow_steps', 'DELETE'),
                 ('workflow.workflow_internal_notes', 'UPDATE'),
                 ('workflow.workflow_internal_notes', 'DELETE'),
                 ('workflow.workflow_follow_ups', 'UPDATE'),
                 ('workflow.workflow_follow_ups', 'DELETE'),
                 ('workflow.workflow_call_logs', 'UPDATE'),
                 ('workflow.workflow_call_logs', 'DELETE')) AS narrowed(relation, privilege)
    WHERE has_table_privilege('app_rt', to_regclass(narrowed.relation), narrowed.privilege)
  ) THEN
    RAISE EXCEPTION 'app_rt still holds a privilege the migrations revoked (catalog.order_number_counters, the retired product_order_workflow tables or the workflow logs)';
  END IF;
END$$;

\echo 'create-app-rt-role: role ready'
