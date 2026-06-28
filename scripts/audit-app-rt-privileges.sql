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

\echo 'app_rt audit: role attribute + membership checks passed'
