-- Decommission the ims_app database role: collapse to a single DB identity (appuser).
--
-- Target model: appuser is the ONLY application/migration database user. It owns
-- every service schema and object and is used for both Flyway migrations and
-- runtime (SPRING_DATASOURCE_USERNAME/FLYWAY_USERNAME = appuser, password
-- db-password). ims_app is removed entirely.
--
-- !! ORDERING: run this ONLY AFTER cloudbuild has been deployed with
--    _APP_DB_USER=appuser and _FLYWAY_DB_USER=appuser, i.e. once NO running
--    service connects as ims_app. Running it earlier breaks live services.
--
-- Run as appuser (PG15 CREATEROLE lets it administer/drop the non-superuser
-- ims_app role). Idempotent: re-running after ims_app is gone is a no-op error on
-- the final DROP only.

-- 1. Move any objects still owned by ims_app (the 5 schemas it created and their
--    contents) to appuser so nothing is lost.
REASSIGN OWNED BY ims_app TO appuser;

-- 2. Drop the temporary role membership that let appuser manage ims_app's objects.
REVOKE ims_app FROM appuser;

-- 3. Remove every privilege/default-privilege still granted to ims_app
--    (ims_app now owns nothing).
DROP OWNED BY ims_app;

-- 4. Remove the role.
DROP ROLE ims_app;
