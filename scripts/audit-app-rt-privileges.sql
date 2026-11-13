-- Regression audit for the app_rt runtime role. Exits non-zero on any violation.
-- psql var: run_live_probes  -- if defined, also run the live DDL/RLS probes (needs a superuser connection)
-- Read-only apart from session temp objects (and the live probes' throwaway table in public).
\set ON_ERROR_STOP on

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

-- 1) Role exists with the correct unprivileged attributes.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='app_rt') THEN
    RAISE EXCEPTION 'app_rt role does not exist';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='app_rt'
             AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolinherit OR NOT rolcanlogin)) THEN
    RAISE EXCEPTION 'app_rt attributes wrong (need LOGIN, NOINHERIT, NOSUPERUSER, NOBYPASSRLS, NOCREATEROLE, NOCREATEDB)';
  END IF;
END$$;

-- 2) app_rt must NOT be a member of cloudsqlsuperuser.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM pg_auth_members m
    JOIN pg_roles grp ON grp.oid=m.roleid
    JOIN pg_roles mem ON mem.oid=m.member
    WHERE mem.rolname='app_rt' AND grp.rolname='cloudsqlsuperuser'
  ) THEN
    RAISE EXCEPTION 'app_rt must not be a member of cloudsqlsuperuser';
  END IF;
END$$;

-- 3) For each target schema that exists, app_rt has USAGE.
DO $$
DECLARE s text;
BEGIN
  FOR s IN
    SELECT n.nspname FROM (VALUES ('identity'),('tenant_school'),('student'),('attendance'),
      ('fee'),('catalog'),('workflow'),('firefighting'),('reporting'),
      ('notification'),('audit'),('billing')) v(name)
    JOIN pg_namespace n ON n.nspname=v.name
  LOOP
    IF NOT has_schema_privilege('app_rt', s, 'USAGE') THEN
      RAISE EXCEPTION 'app_rt lacks USAGE on schema %', s;
    END IF;
  END LOOP;
END$$;

-- 4) Where target schemas contain tables, app_rt has SELECT (proxy for DML grants), except on the relations the
--    narrowing list bars it from reading (owner-only ledgers and evidence, the Flyway history tables).
DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT n.nspname, c.relname
    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE c.relkind='r'
      AND n.nspname IN ('identity','tenant_school','student','attendance','fee','catalog',
                        'workflow','firefighting','reporting','notification','audit','billing')
      AND NOT EXISTS (SELECT 1 FROM app_rt_narrowing_active a WHERE a.rel = c.oid AND a.privilege = 'SELECT')
  LOOP
    IF NOT has_table_privilege('app_rt', format('%I.%I', r.nspname, r.relname), 'SELECT') THEN
      RAISE EXCEPTION 'app_rt lacks SELECT on %.%', r.nspname, r.relname;
    END IF;
  END LOOP;
END$$;

-- 5) app_rt holds none of the privileges the narrowing list bars: what it can actually do, from any grantor and
--    through PUBLIC, on the rows that apply now. Every violation is named with who granted it.
DO $$
DECLARE
  held text;
BEGIN
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

-- Live probes (require a superuser connection; set -v run_live_probes=1).
\if :{?run_live_probes}

-- 6) DDL is denied for app_rt (no CREATE privilege anywhere; PG15+ public is locked down).
DO $$
BEGIN
  SET LOCAL ROLE app_rt;
  BEGIN
    EXECUTE 'CREATE TABLE public._app_rt_ddl_probe (x int)';
    RESET ROLE;
    EXECUTE 'DROP TABLE IF EXISTS public._app_rt_ddl_probe';
    RAISE EXCEPTION 'app_rt was able to CREATE TABLE (DDL not denied)';
  EXCEPTION WHEN insufficient_privilege THEN
    RESET ROLE;
    RAISE NOTICE 'OK: DDL denied for app_rt';
  END;
END$$;

-- 7) app_rt is SUBJECT TO RLS; the table owner bypasses.
DO $$
DECLARE app_rt_count int; owner_count int;
BEGIN
  DROP TABLE IF EXISTS public._app_rt_rls_probe;
  CREATE TABLE public._app_rt_rls_probe (tenant text NOT NULL, val int NOT NULL);
  INSERT INTO public._app_rt_rls_probe VALUES ('A',1),('A',2),('B',3);
  GRANT SELECT ON public._app_rt_rls_probe TO app_rt;
  ALTER TABLE public._app_rt_rls_probe ENABLE ROW LEVEL SECURITY;
  CREATE POLICY _app_rt_rls_probe_pol ON public._app_rt_rls_probe USING (tenant = 'A');

  SET LOCAL ROLE app_rt;
  SELECT count(*) INTO app_rt_count FROM public._app_rt_rls_probe;
  RESET ROLE;
  SELECT count(*) INTO owner_count FROM public._app_rt_rls_probe;

  DROP TABLE public._app_rt_rls_probe;

  IF app_rt_count <> 2 THEN
    RAISE EXCEPTION 'RLS not enforced for app_rt: saw % rows, expected 2', app_rt_count;
  END IF;
  IF owner_count <> 3 THEN
    RAISE EXCEPTION 'owner unexpectedly constrained by RLS: saw % rows, expected 3', owner_count;
  END IF;
  RAISE NOTICE 'OK: app_rt subject to RLS (2 rows), owner bypasses (3 rows)';
END$$;

\endif

\echo 'app_rt audit: all checks passed'
