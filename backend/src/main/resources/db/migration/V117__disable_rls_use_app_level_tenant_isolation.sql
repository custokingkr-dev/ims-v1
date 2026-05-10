-- V117 — Disable Row-Level Security; use application-level tenant isolation
-- ============================================================================
-- Decision: This application uses a SINGLE datasource user (ims_app or the
-- configured Spring datasource username) for all requests. DB-level RLS as
-- set up in V4 requires:
--   • A dedicated low-privilege ims_app Postgres role
--   • A JDBC interceptor that calls SET LOCAL app.current_school_id before
--     every school-scoped query
--   • Separate superadmin Postgres role to bypass RLS
--
-- None of these prerequisites are fully wired in production at this time.
-- Leaving half-configured RLS active with a single app user causes queries
-- to return EMPTY result sets (rows filtered by an unset session variable)
-- which is worse than having no RLS: data appears missing rather than leaking.
--
-- Chosen strategy: DISABLE RLS on all tables; enforce tenant isolation at
-- application level via TenantContext / TenantScopeService. This is the
-- correct production posture until a proper multi-role DB strategy is
-- implemented.
--
-- To re-enable RLS in the future:
--   1. Create and configure a low-privilege ims_app Postgres role.
--   2. Wire a Hibernate interceptor to call SET LOCAL app.current_school_id.
--   3. Create a separate superadmin Postgres connection pool.
--   4. Run integration tests proving cross-school isolation.
--   5. Drop and recreate the school_isolation policies with correct logic.
-- ============================================================================

-- Disable RLS and drop policies on all tables where V4 enabled them.

ALTER TABLE students DISABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS school_isolation ON students;

ALTER TABLE school_sections DISABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS school_isolation ON school_sections;

ALTER TABLE staff_members DISABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS school_isolation ON staff_members;

ALTER TABLE catalog_orders DISABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS school_isolation ON catalog_orders;

ALTER TABLE annual_plan_items DISABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS school_isolation ON annual_plan_items;

ALTER TABLE firefighting_requests DISABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS school_isolation ON firefighting_requests;

-- superadmin_invoices — cross-tenant table; RLS not needed with app-level isolation
ALTER TABLE superadmin_invoices DISABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS school_isolation ON superadmin_invoices;
