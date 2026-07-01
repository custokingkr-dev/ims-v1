DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='app_rt') THEN
    CREATE ROLE app_rt LOGIN NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS PASSWORD 'app_rt';
  END IF;
END$$;

GRANT app_rt TO postgres;

-- Global default privileges: future tables/sequences created by postgres (local Flyway owner)
-- auto-grant DML to app_rt. Per-schema USAGE is granted post-migration by ensure-app-rt-local.ps1.
ALTER DEFAULT PRIVILEGES FOR ROLE postgres GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_rt;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres GRANT USAGE, SELECT ON SEQUENCES TO app_rt;
