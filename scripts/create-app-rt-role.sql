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

-- Final assertions (run as owner; pg catalogs are world-readable).
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='app_rt'
             AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolinherit)) THEN
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
END$$;

\echo 'create-app-rt-role: role ready'
