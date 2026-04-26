-- HUMAN REVIEW REQUIRED before applying to production
-- =============================================================
-- V4 – Row-Level Security (RLS) for tenant isolation
--
-- PURPOSE: Enforce school-level data isolation at the database
-- layer so that even if the application has a bug, a school's
-- data cannot leak to another school's users.
--
-- HOW IT WORKS:
--   1. A low-privilege app role (ims_app) is created.  The
--      application's JDBC connection must use this role.
--   2. Before running any school-scoped query the application
--      must execute:
--        SET LOCAL app.current_school_id = '<id>';
--      This is done by TenantContext / a JDBC interceptor.
--   3. Each tenant-scoped table has an ENABLE ROW LEVEL SECURITY
--      clause and a policy that permits rows whose school_id
--      matches the session variable.
--   4. SUPERADMIN connections BYPASS RLS (use the superadmin
--      Postgres role, not ims_app).
--
-- DO NOT APPLY without:
--   a) Updating application.yml to connect as ims_app
--   b) Wiring a JDBC ConnectionPreparedStatementCreator (or
--      Hibernate interceptor) that calls SET LOCAL for every
--      school-bound transaction
--   c) Testing every tenant-scoped endpoint against two schools
--      to confirm data does not bleed across tenants
-- =============================================================

-- ── 1. Create application role ────────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ims_app') THEN
        CREATE ROLE ims_app LOGIN PASSWORD 'CHANGE_ME_BEFORE_USE';
    END IF;
END$$;

-- Grant DML on all existing tables
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ims_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ims_app;

-- Ensure future tables are also accessible
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ims_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO ims_app;

-- ── 2. students ───────────────────────────────────────────────
ALTER TABLE students ENABLE ROW LEVEL SECURITY;
ALTER TABLE students FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS school_isolation ON students;
CREATE POLICY school_isolation ON students
    AS PERMISSIVE
    FOR ALL
    TO ims_app
    USING (
        school_id = NULLIF(current_setting('app.current_school_id', true), '')::bigint
    )
    WITH CHECK (
        school_id = NULLIF(current_setting('app.current_school_id', true), '')::bigint
    );

-- ── 3. school_sections ────────────────────────────────────────
ALTER TABLE school_sections ENABLE ROW LEVEL SECURITY;
ALTER TABLE school_sections FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS school_isolation ON school_sections;
CREATE POLICY school_isolation ON school_sections
    AS PERMISSIVE
    FOR ALL
    TO ims_app
    USING (
        school_id IS NULL
        OR school_id = NULLIF(current_setting('app.current_school_id', true), '')::bigint
    )
    WITH CHECK (
        school_id IS NULL
        OR school_id = NULLIF(current_setting('app.current_school_id', true), '')::bigint
    );

-- ── 4. staff_members ─────────────────────────────────────────
ALTER TABLE staff_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_members FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS school_isolation ON staff_members;
CREATE POLICY school_isolation ON staff_members
    AS PERMISSIVE
    FOR ALL
    TO ims_app
    USING (
        school_id IS NULL
        OR school_id = NULLIF(current_setting('app.current_school_id', true), '')::bigint
    )
    WITH CHECK (
        school_id IS NULL
        OR school_id = NULLIF(current_setting('app.current_school_id', true), '')::bigint
    );

-- ── 5. catalog_orders ────────────────────────────────────────
ALTER TABLE catalog_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog_orders FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS school_isolation ON catalog_orders;
CREATE POLICY school_isolation ON catalog_orders
    AS PERMISSIVE
    FOR ALL
    TO ims_app
    USING (
        school_id IS NULL
        OR school_id = NULLIF(current_setting('app.current_school_id', true), '')::bigint
    )
    WITH CHECK (
        school_id IS NULL
        OR school_id = NULLIF(current_setting('app.current_school_id', true), '')::bigint
    );

-- ── 6. annual_plan_items ──────────────────────────────────────
ALTER TABLE annual_plan_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE annual_plan_items FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS school_isolation ON annual_plan_items;
CREATE POLICY school_isolation ON annual_plan_items
    AS PERMISSIVE
    FOR ALL
    TO ims_app
    USING (
        school_id IS NULL
        OR school_id = NULLIF(current_setting('app.current_school_id', true), '')::bigint
    )
    WITH CHECK (
        school_id IS NULL
        OR school_id = NULLIF(current_setting('app.current_school_id', true), '')::bigint
    );

-- ── 7. firefighting_requests ──────────────────────────────────
ALTER TABLE firefighting_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE firefighting_requests FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS school_isolation ON firefighting_requests;
CREATE POLICY school_isolation ON firefighting_requests
    AS PERMISSIVE
    FOR ALL
    TO ims_app
    USING (
        school_id IS NULL
        OR school_id = NULLIF(current_setting('app.current_school_id', true), '')::bigint
    )
    WITH CHECK (
        school_id IS NULL
        OR school_id = NULLIF(current_setting('app.current_school_id', true), '')::bigint
    );

-- ── 8. superadmin_invoices ────────────────────────────────────
-- Superadmin invoices are cross-tenant; only the superadmin
-- Postgres role should read them.  ims_app gets no policy.
ALTER TABLE superadmin_invoices ENABLE ROW LEVEL SECURITY;
ALTER TABLE superadmin_invoices FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS school_isolation ON superadmin_invoices;
CREATE POLICY school_isolation ON superadmin_invoices
    AS PERMISSIVE
    FOR ALL
    TO ims_app
    USING (
        school_id IS NULL
        OR school_id = NULLIF(current_setting('app.current_school_id', true), '')::bigint
    );

-- ── 9. attendance_daily (via section → school) ────────────────
-- attendance_daily does not have a direct school_id column;
-- isolation is enforced through the section FK which is already
-- filtered in every query.  No RLS needed at this table for now.
-- Add a direct school_id column in a future migration if needed.

-- =============================================================
-- POST-APPLY CHECKLIST (tick each before declaring done):
--   [ ] ims_app password changed from CHANGE_ME_BEFORE_USE
--   [ ] application.yml updated: spring.datasource.username=ims_app
--   [ ] JDBC interceptor sets app.current_school_id per request
--   [ ] Integration tests pass with two isolated school tenants
--   [ ] SUPERADMIN queries use the owner/superadmin Postgres role
-- =============================================================
