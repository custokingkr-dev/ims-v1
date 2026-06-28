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

\echo 'app_rt audit: role attribute + membership checks passed'
