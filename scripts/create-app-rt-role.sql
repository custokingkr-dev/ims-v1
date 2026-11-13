-- Idempotent creation of the unprivileged runtime role app_rt and its grants on the twelve service schemas.
-- psql vars:
--   app_rt_password : login password for app_rt        (required)
--   owner           : schema-owning / Flyway role       (required: appuser in prod, postgres in local)
--   set_superuser   : if defined, SET ROLE cloudsqlsuperuser for role creation (prod only)
-- Assumes target schemas already exist; grants apply only to schemas present in pg_namespace.
--
-- A re-run on a migrated database leaves what the migrations set alone:
--   * app_rt gets SELECT, INSERT, UPDATE and DELETE on every relation of the service schemas, except the table-level
--     privileges on the narrowing list below. Those are never granted, so a migration's narrowing survives, and column
--     grants a migration made (for example UPDATE on four columns) stay exactly as they are.
--   * If app_rt already holds a listed privilege at table level (a run of this script before the list existed widened
--     it), the owner's grant is taken back and the owner's column grants of that privilege are given back.
--   * student.students INSERT is a column rule: app_rt gets INSERT on every column but erasure_incarnation, as student
--     V39 grants it. Until V39 has added that column, the table keeps its table-level INSERT, which V39 converts.
--   * The grants and that repair run in one transaction, so a failure leaves app_rt as it was, never wider.
--   * The final check reads what app_rt actually holds (any grantor, PUBLIC included) and names what is wrong.
-- The Flyway history tables are on the list: the default privileges below hand them to app_rt when Flyway creates them,
-- and no runtime code reads them (migrations run as the owner), so a run takes them back.
--
-- The role step forces LOGIN. On dev, app_rt is retired (NOLOGIN, scripts/security/retire-dev-shared-runtime-role.ps1)
-- and kept only as the ACL template afterMigrate__dedicated_runtime_acl.sql copies; running this script there turns
-- login back on. See docs/MICROSERVICE-APP-RT-PROVISIONING-RUNBOOK.md.
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

-- app_rt narrowing list: begin. Keep this block identical in scripts/create-app-rt-role.sql and
-- scripts/audit-app-rt-privileges.sql: the Cloud Run job ships each file on its own, so they cannot share an include,
-- and scripts/tests/create_app_rt_role_migrated_test.py fails when the two copies differ.
-- One row per relation, with the table-level privileges app_rt must not hold there: a versioned migration took them
-- away once and never runs again, or (the Flyway history tables) nothing at runtime needs them. A relation that does
-- not exist yet is skipped (to_regclass is NULL), so the list is safe before the services migrate.
CREATE TEMP TABLE app_rt_narrowing (relation text NOT NULL, privilege text NOT NULL, reason text NOT NULL,
                                    PRIMARY KEY (relation, privilege));
INSERT INTO app_rt_narrowing (relation, privilege, reason)
SELECT r.relation, p.privilege, r.reason
FROM (VALUES
  ('catalog.legacy_catalog_migration_map',           'SELECT,INSERT,UPDATE,DELETE',   'catalog V8: owner-operated ledger, no RLS'),
  ('catalog.annual_plan_confirmations',              'UPDATE,DELETE',                 'catalog V24: append-only'),
  ('catalog.order_number_counters',                  'DELETE,TRUNCATE',               'catalog V37: a deleted counter repeats order numbers'),
  ('catalog.product_order_workflows',                'INSERT,UPDATE,DELETE,TRUNCATE', 'catalog V38: retired, read-only'),
  ('catalog.product_order_workflow_steps',           'INSERT,UPDATE,DELETE,TRUNCATE', 'catalog V38: retired, read-only'),
  ('student.guardian_legacy_parity',                 'SELECT,INSERT,UPDATE,DELETE',   'student V24: owner-only view'),
  ('student.guardian_safe_create_repair_runs',       'SELECT,INSERT,UPDATE,DELETE',   'student V26: owner-only repair evidence'),
  ('student.guardian_safe_create_repair_actions',    'SELECT,INSERT,UPDATE,DELETE',   'student V26: owner-only repair evidence'),
  ('student.erasure_journal_receipts',               'UPDATE,DELETE',                 'student V39: append-only receipts'),
  ('student.students',                               'INSERT',                        'student V39: INSERT on every column but erasure_incarnation'),
  ('tenant_school.photo_cleanup_outbox',             'DELETE',                        'tenant_school V30: a cleanup intent is never deleted'),
  ('firefighting.quotation_documents',               'UPDATE,DELETE',                 'firefighting V12: UPDATE on four columns only'),
  ('firefighting.creation_replays',                  'UPDATE,DELETE',                 'firefighting V13: append-only'),
  ('workflow.workflow_internal_notes',               'UPDATE,DELETE',                 'workflow V11: append-only'),
  ('workflow.workflow_follow_ups',                   'UPDATE,DELETE',                 'workflow V11: append-only'),
  ('workflow.workflow_call_logs',                    'UPDATE,DELETE,TRUNCATE',        'workflow V20: append-only'),
  ('notification.notification_broadcast_recipients', 'UPDATE,DELETE',                 'notification V11: UPDATE on nine columns only'),
  ('notification.broadcast_live_submissions',        'UPDATE,DELETE',                 'notification V12: UPDATE on five columns only'),
  ('notification.broadcast_live_reports',            'UPDATE,DELETE',                 'notification V12: append-only'),
  ('notification.generic_submissions',               'UPDATE,DELETE',                 'notification V15 and V16: UPDATE on five columns only'),
  ('notification.generic_delivery_results',          'INSERT,UPDATE,DELETE',          'notification V17: INSERT on six and UPDATE on two columns only'),
  ('notification.generic_delivery_reports',          'INSERT,UPDATE,DELETE',          'notification V17: INSERT on six columns only'),
  ('notification.generic_unknown_report_assertions', 'INSERT,UPDATE,DELETE',          'notification V18: INSERT on nine columns only'),
  ('reporting.trusted_command_audit',                'UPDATE,DELETE',                 'reporting V32: append-only audit'),
  ('identity.flyway_schema_history',                 'SELECT,INSERT,UPDATE,DELETE',   'Flyway history: owner only'),
  ('tenant_school.flyway_schema_history_tenant_school', 'SELECT,INSERT,UPDATE,DELETE', 'Flyway history: owner only'),
  ('student.flyway_schema_history',                  'SELECT,INSERT,UPDATE,DELETE',   'Flyway history: owner only'),
  ('attendance.flyway_schema_history',               'SELECT,INSERT,UPDATE,DELETE',   'Flyway history: owner only'),
  ('fee.flyway_schema_history',                      'SELECT,INSERT,UPDATE,DELETE',   'Flyway history: owner only'),
  ('catalog.flyway_schema_history',                  'SELECT,INSERT,UPDATE,DELETE',   'Flyway history: owner only'),
  ('workflow.flyway_schema_history',                 'SELECT,INSERT,UPDATE,DELETE',   'Flyway history: owner only'),
  ('firefighting.flyway_schema_history',             'SELECT,INSERT,UPDATE,DELETE',   'Flyway history: owner only'),
  ('reporting.flyway_schema_history',                'SELECT,INSERT,UPDATE,DELETE',   'Flyway history: owner only'),
  ('notification.flyway_schema_history',             'SELECT,INSERT,UPDATE,DELETE',   'Flyway history: owner only'),
  ('audit.flyway_schema_history',                    'SELECT,INSERT,UPDATE,DELETE',   'Flyway history: owner only'),
  ('billing.flyway_schema_history',                  'SELECT,INSERT,UPDATE,DELETE',   'Flyway history: owner only')
) AS r(relation, privileges, reason)
CROSS JOIN LATERAL unnest(string_to_array(r.privileges, ',')) AS p(privilege);
-- Column rules. Where a relation and privilege are named here, app_rt holds that privilege on every other column of the
-- relation, and never on the named columns or at table level (student V39: INSERT on every column but
-- erasure_incarnation).
CREATE TEMP TABLE app_rt_column_narrowing (relation text NOT NULL, column_name text NOT NULL, privilege text NOT NULL,
                                           reason text NOT NULL, PRIMARY KEY (relation, column_name, privilege));
INSERT INTO app_rt_column_narrowing (relation, column_name, privilege, reason) VALUES
  ('student.students', 'erasure_incarnation', 'INSERT', 'student V39: the database generates every incarnation');
-- The rows that apply to this database now: the relation exists and so does every column its column rules name. A
-- column rule's column is added by the migration that narrows the table, in the same transaction, so before it commits
-- the table keeps its table-level privilege. That matters for student V39: it turns app_rt's table-level INSERT into
-- column grants only if app_rt holds it when V39 runs.
CREATE TEMP VIEW app_rt_narrowing_active AS
SELECT a.relation, a.privilege, to_regclass(a.relation) AS rel
FROM app_rt_narrowing a
WHERE to_regclass(a.relation) IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM app_rt_column_narrowing c
                  WHERE c.relation = a.relation AND c.privilege = a.privilege
                    AND NOT EXISTS (SELECT 1 FROM pg_attribute att
                                    WHERE att.attrelid = to_regclass(c.relation) AND att.attname = c.column_name
                                      AND NOT att.attisdropped));
-- app_rt narrowing list: end

-- Everything that grants or takes back runs in one transaction: in prod app_rt is the live role, and a failure part way
-- must not leave it holding more than before.
BEGIN;

-- Grants on existing target schemas (intersection with pg_namespace keeps this re-runnable
-- before all services have migrated, and harmless if a schema is absent).
SELECT format('GRANT USAGE ON SCHEMA %I TO app_rt', n.nspname)
FROM (VALUES ('identity'),('tenant_school'),('student'),('attendance'),('fee'),
             ('catalog'),('workflow'),('firefighting'),('reporting'),
             ('notification'),('audit'),('billing')) AS s(name)
JOIN pg_namespace n ON n.nspname = s.name
\gexec

-- DML on every relation, one GRANT per relation, leaving out the privileges the list bars there. The relation kinds are
-- the ones GRANT ... ON ALL TABLES IN SCHEMA covers (tables, partitioned tables, views, materialized views, foreign
-- tables). A relation whose four privileges are all listed gets no GRANT at all.
SELECT format('GRANT %s ON TABLE %I.%I TO app_rt', string_agg(p.privilege, ', ' ORDER BY p.ord), n.nspname, c.relname)
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN (VALUES ('identity'),('tenant_school'),('student'),('attendance'),('fee'),
             ('catalog'),('workflow'),('firefighting'),('reporting'),
             ('notification'),('audit'),('billing')) AS s(name) ON s.name = n.nspname
CROSS JOIN (VALUES (1, 'SELECT'), (2, 'INSERT'), (3, 'UPDATE'), (4, 'DELETE')) AS p(ord, privilege)
WHERE c.relkind IN ('r', 'p', 'v', 'm', 'f')
  AND NOT EXISTS (SELECT 1 FROM app_rt_narrowing_active a WHERE a.rel = c.oid AND a.privilege = p.privilege)
GROUP BY n.nspname, c.relname
ORDER BY n.nspname, c.relname
\gexec

-- No migration narrows a sequence below USAGE and SELECT (fee V10 re-grants exactly these), so the blanket grant is safe.
SELECT format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO app_rt', n.nspname)
FROM (VALUES ('identity'),('tenant_school'),('student'),('attendance'),('fee'),
             ('catalog'),('workflow'),('firefighting'),('reporting'),
             ('notification'),('audit'),('billing')) AS s(name)
JOIN pg_namespace n ON n.nspname = s.name
\gexec

-- Default privileges so FUTURE owner-created objects auto-grant DML to app_rt. A migration that creates a table app_rt
-- must not write takes the privileges away itself, and that relation then belongs on the narrowing list above.
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

-- Repair: take back a listed privilege app_rt holds at table level (an earlier run of this script granted it, or the
-- default privileges did on a Flyway history table). A table-level REVOKE also revokes that privilege on every column,
-- so the owner's column grants of it are read first and given back unchanged. Then the column rules.
DO $narrow$
DECLARE
  n record;
  plain_columns text;
  grantable_columns text;
BEGIN
  FOR n IN
    SELECT a.privilege, c.oid AS rel, c.relowner
    FROM app_rt_narrowing_active a JOIN pg_class c ON c.oid = a.rel
    WHERE EXISTS (SELECT 1 FROM aclexplode(c.relacl) x
                  WHERE x.grantee = 'app_rt'::regrole AND x.privilege_type = a.privilege)
    ORDER BY a.relation, a.privilege
  LOOP
    SELECT string_agg(quote_ident(att.attname), ', ' ORDER BY att.attnum) FILTER (WHERE NOT x.is_grantable),
           string_agg(quote_ident(att.attname), ', ' ORDER BY att.attnum) FILTER (WHERE x.is_grantable)
      INTO plain_columns, grantable_columns
    FROM pg_attribute att CROSS JOIN LATERAL aclexplode(att.attacl) x
    WHERE att.attrelid = n.rel AND att.attnum > 0 AND NOT att.attisdropped
      AND x.grantee = 'app_rt'::regrole AND x.privilege_type = n.privilege AND x.grantor = n.relowner;
    EXECUTE format('REVOKE %s ON TABLE %s FROM app_rt', n.privilege, n.rel::regclass);
    IF plain_columns IS NOT NULL THEN
      EXECUTE format('GRANT %s (%s) ON TABLE %s TO app_rt', n.privilege, plain_columns, n.rel::regclass);
    END IF;
    IF grantable_columns IS NOT NULL THEN
      EXECUTE format('GRANT %s (%s) ON TABLE %s TO app_rt WITH GRANT OPTION', n.privilege, grantable_columns, n.rel::regclass);
    END IF;
    RAISE NOTICE 'create-app-rt-role: took table-level % on % back from app_rt', n.privilege, n.rel::regclass;
  END LOOP;
  -- Column rules: the privilege on every other column. This is what the narrowing migration grants when app_rt held
  -- the table-level privilege as it ran; a build that ran this script before its schemas existed gave app_rt nothing
  -- then, and gets the same columns here. A column a later migration adds is included, so the invariant test
  -- (create_app_rt_role_migrated_test.py) fails until that migration grants it too or a rule here excludes it.
  FOR n IN
    SELECT a.privilege, a.rel, string_agg(quote_ident(att.attname), ', ' ORDER BY att.attnum) AS allowed
    FROM app_rt_narrowing_active a
    JOIN pg_attribute att ON att.attrelid = a.rel AND att.attnum > 0 AND NOT att.attisdropped
    WHERE EXISTS (SELECT 1 FROM app_rt_column_narrowing c WHERE c.relation = a.relation AND c.privilege = a.privilege)
      AND NOT EXISTS (SELECT 1 FROM app_rt_column_narrowing c
                      WHERE c.relation = a.relation AND c.privilege = a.privilege AND c.column_name = att.attname)
    GROUP BY a.privilege, a.rel
  LOOP
    EXECUTE format('GRANT %s (%s) ON TABLE %s TO app_rt', n.privilege, n.allowed, n.rel::regclass);
  END LOOP;
  FOR n IN
    SELECT c.privilege, c.column_name, att.attrelid AS rel
    FROM app_rt_column_narrowing c
    JOIN pg_attribute att ON att.attrelid = to_regclass(c.relation) AND att.attname = c.column_name AND NOT att.attisdropped
    WHERE EXISTS (SELECT 1 FROM aclexplode(att.attacl) x
                  WHERE x.grantee = 'app_rt'::regrole AND x.privilege_type = c.privilege)
  LOOP
    EXECUTE format('REVOKE %s (%I) ON TABLE %s FROM app_rt', n.privilege, n.column_name, n.rel::regclass);
    RAISE NOTICE 'create-app-rt-role: took % on %.% back from app_rt', n.privilege, n.rel::regclass, n.column_name;
  END LOOP;
END $narrow$;

COMMIT;

-- Final assertions (run as owner; pg catalogs are world-readable). They run after COMMIT on purpose: if a privilege
-- another role granted survives (the owner's REVOKE cannot reach it), the owner's narrowing above is still kept.
DO $$
DECLARE
  held text;
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
  -- What app_rt can actually do, from any grantor and through PUBLIC, on the rows that apply now.
  SELECT string_agg(format('%s %s (%s)', a.relation, a.privilege,
           coalesce((SELECT string_agg(format('granted to %s by %s',
                                              CASE WHEN x.grantee = 0 THEN 'PUBLIC' ELSE pg_get_userbyid(x.grantee) END,
                                              pg_get_userbyid(x.grantor)), ', ' ORDER BY x.grantee, x.grantor)
                     FROM pg_class c CROSS JOIN LATERAL aclexplode(c.relacl) x
                     WHERE c.oid = a.rel AND x.privilege_type = a.privilege
                       AND x.grantee IN (0::oid, 'app_rt'::regrole::oid)),
                    'held through a role')), '; ' ORDER BY a.relation, a.privilege)
    INTO held
  FROM app_rt_narrowing_active a
  WHERE has_table_privilege('app_rt', a.rel, a.privilege);
  IF held IS NOT NULL THEN
    RAISE EXCEPTION 'app_rt holds table privileges the narrowing list bars: %', held;
  END IF;
  SELECT string_agg(format('%s.%s %s (%s)', c.relation, c.column_name, c.privilege,
           coalesce((SELECT string_agg(format('column grant to %s by %s',
                                              CASE WHEN x.grantee = 0 THEN 'PUBLIC' ELSE pg_get_userbyid(x.grantee) END,
                                              pg_get_userbyid(x.grantor)), ', ' ORDER BY x.grantee, x.grantor)
                     FROM aclexplode(att.attacl) x
                     WHERE x.privilege_type = c.privilege AND x.grantee IN (0::oid, 'app_rt'::regrole::oid)),
                    'held at table level')), '; ' ORDER BY c.relation, c.column_name)
    INTO held
  FROM app_rt_column_narrowing c
  JOIN pg_attribute att ON att.attrelid = to_regclass(c.relation) AND att.attname = c.column_name AND NOT att.attisdropped
  WHERE has_column_privilege('app_rt', att.attrelid, att.attnum, c.privilege);
  IF held IS NOT NULL THEN
    RAISE EXCEPTION 'app_rt holds column privileges the narrowing list bars: %', held;
  END IF;
END$$;

\echo 'create-app-rt-role: role ready'
