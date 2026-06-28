-- Durable Flyway migration model: appuser is the single migration role.
--
-- Schema ownership is split: appuser owns audit/catalog/fee/identity/notification/
-- student/tenant_school; ims_app owns attendance/billing/firefighting/reporting/
-- workflow. Granting appuser membership in ims_app lets appuser run DDL (CREATE,
-- ALTER, DROP) on the ims_app-owned schemas via inheritance, in addition to the
-- schemas it already owns -- so appuser can migrate ALL 12 service schemas while
-- the runtime user ims_app keeps only USAGE + DML (least privilege preserved;
-- ims_app is NOT made a member of appuser).
--
-- With this in place, cloudbuild.yaml runs Flyway as appuser (_FLYWAY_DB_USER=
-- appuser, FLYWAY_PASSWORD=db-password) and the app as ims_app, so future DDL
-- migrations no longer need per-schema CREATE grants
-- (supersedes scripts/grant-flyway-ddl-schema-permissions.sql).
--
-- Run as appuser. On PostgreSQL 15 a CREATEROLE role may grant membership in any
-- non-superuser role, so appuser can execute this itself. Idempotent.

GRANT ims_app TO appuser;
