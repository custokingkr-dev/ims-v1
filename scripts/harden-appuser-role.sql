-- Reduce appuser (the single app + Flyway DB role) toward least privilege.
--
-- appuser owns all 12 service schemas, so it needs no role- or database-creation
-- powers for normal application or Flyway-migration work. Migrations are plain
-- DDL on owned schemas (no CREATE EXTENSION / event triggers / etc.).
--
-- Run as appuser (while it still has CREATEROLE) or as postgres.

-- NOTE: run NOINHERIT (below) as `postgres` BEFORE stripping CREATEROLE if doing
-- this on a fresh setup -- once appuser is NOCREATEROLE it can no longer ALTER
-- itself, so NOINHERIT then requires the `postgres` admin.
ALTER ROLE appuser NOCREATEROLE NOCREATEDB;

-- Neutralize the inherited cloudsqlsuperuser privileges. appuser remains a member
-- of cloudsqlsuperuser (a Cloud SQL default that CANNOT be revoked via SQL -- no
-- role holds ADMIN OPTION on it, not even postgres), but NOINHERIT stops appuser
-- from automatically wielding those privileges. It keeps full access to its own
-- data via direct schema/object ownership. Run as `postgres`:
ALTER ROLE appuser NOINHERIT;
