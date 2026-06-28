-- Flyway DDL permissions for the migration user (ims_app).
--
-- Context: the Flyway/runtime user is `ims_app`. Schema ownership is mixed:
--   * appuser owns: audit, catalog, fee, identity, notification, student, tenant_school
--   * ims_app  owns: attendance, billing, firefighting, reporting, workflow
-- ims_app can already run DDL in the schemas it owns. For the appuser-owned
-- schemas it has only USAGE + DML (see grant-microservice-schema-permissions.sql),
-- so a new DDL migration there fails -- e.g. tenant_school V4__school_timetable.sql:
--     ERROR: permission denied for schema tenant_school
--
-- This grants ims_app the privileges its migrations need on the appuser-owned
-- schemas only: CREATE on the schema (new tables/indexes/sequences) and REFERENCES
-- on existing + future appuser-owned tables (foreign keys to them).
--
-- MUST be applied as `appuser` (the owner of these schemas). Idempotent.

GRANT CREATE ON SCHEMA
  audit,
  catalog,
  fee,
  identity,
  notification,
  student,
  tenant_school
TO ims_app;

-- REFERENCES on existing appuser-owned tables (needed to create FKs to them).
GRANT REFERENCES ON ALL TABLES IN SCHEMA
  audit,
  catalog,
  fee,
  identity,
  notification,
  student,
  tenant_school
TO ims_app;

-- REFERENCES on future appuser-created tables in those schemas.
ALTER DEFAULT PRIVILEGES FOR ROLE appuser IN SCHEMA audit GRANT REFERENCES ON TABLES TO ims_app;
ALTER DEFAULT PRIVILEGES FOR ROLE appuser IN SCHEMA catalog GRANT REFERENCES ON TABLES TO ims_app;
ALTER DEFAULT PRIVILEGES FOR ROLE appuser IN SCHEMA fee GRANT REFERENCES ON TABLES TO ims_app;
ALTER DEFAULT PRIVILEGES FOR ROLE appuser IN SCHEMA identity GRANT REFERENCES ON TABLES TO ims_app;
ALTER DEFAULT PRIVILEGES FOR ROLE appuser IN SCHEMA notification GRANT REFERENCES ON TABLES TO ims_app;
ALTER DEFAULT PRIVILEGES FOR ROLE appuser IN SCHEMA student GRANT REFERENCES ON TABLES TO ims_app;
ALTER DEFAULT PRIVILEGES FOR ROLE appuser IN SCHEMA tenant_school GRANT REFERENCES ON TABLES TO ims_app;
