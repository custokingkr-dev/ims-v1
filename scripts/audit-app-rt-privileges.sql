-- Regression audit for the app_rt runtime role. Exits non-zero on any violation.
-- psql var: run_live_probes  -- if defined, also run the live DDL/RLS probes (needs a superuser connection)
\set ON_ERROR_STOP on

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

-- 4) Where target schemas contain tables, app_rt has SELECT (proxy for DML grants).
DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT n.nspname, c.relname
    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE c.relkind='r'
      AND n.nspname IN ('identity','tenant_school','student','attendance','fee','catalog',
                        'workflow','firefighting','reporting','notification','audit','billing')
  LOOP
    IF NOT has_table_privilege('app_rt', format('%I.%I', r.nspname, r.relname), 'SELECT') THEN
      RAISE EXCEPTION 'app_rt lacks SELECT on %.%', r.nspname, r.relname;
    END IF;
  END LOOP;
END$$;

-- Live probes (require a superuser connection; set -v run_live_probes=1).
\if :{?run_live_probes}

-- 5) DDL is denied for app_rt (no CREATE privilege anywhere; PG15+ public is locked down).
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

-- 6) app_rt is SUBJECT TO RLS; the table owner bypasses.
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

\echo 'app_rt audit: role attribute + membership checks passed'
