-- Reduce appuser (the single app + Flyway DB role) toward least privilege.
--
-- appuser owns all 12 service schemas, so it needs no role- or database-creation
-- powers for normal application or Flyway-migration work. Migrations are plain
-- DDL on owned schemas (no CREATE EXTENSION / event triggers / etc.).
--
-- Run as appuser (while it still has CREATEROLE) or as postgres.

ALTER ROLE appuser NOCREATEROLE NOCREATEDB;

-- Known residual privilege (NOT addressed here):
--   appuser is a member of cloudsqlsuperuser (a Cloud SQL default for users
--   created via `gcloud sql users create`) and inherits its privileges.
--
--   * `REVOKE cloudsqlsuperuser FROM appuser;` fails for every available role --
--     no role holds ADMIN OPTION on cloudsqlsuperuser, so it cannot be revoked
--     via SQL. It requires Cloud SQL-level / true-superuser intervention.
--   * `ALTER ROLE appuser NOINHERIT;` would stop appuser from auto-wielding
--     cloudsqlsuperuser privileges (it keeps full access via direct ownership),
--     but requires CREATEROLE -- run it as `postgres` BEFORE stripping CREATEROLE
--     if full neutralization is desired:
--       ALTER ROLE appuser NOINHERIT;
