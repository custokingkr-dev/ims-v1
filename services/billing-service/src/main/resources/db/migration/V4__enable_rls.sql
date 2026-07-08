-- Branch-keyed RLS on the school-billing tables (branch_id NOT NULL on all three).
-- The GUC stays named app.current_school_id (shared convention across services) even though
-- billing's tenant column is branch_id.

ALTER TABLE billing_customers ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON billing_customers;
CREATE POLICY tenant_isolation ON billing_customers
  USING      (branch_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (branch_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE billing_invoices ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON billing_invoices;
CREATE POLICY tenant_isolation ON billing_invoices
  USING      (branch_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (branch_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE billing_payments ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON billing_payments;
CREATE POLICY tenant_isolation ON billing_payments
  USING      (branch_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (branch_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

-- Superadmin-only ledger tables: no per-branch column to key on (school_id here is a display
-- attribute, not a partition key — legacy superadmin_invoices predates branch-scoped billing).
-- Bypass-only policy so only bypass_rls='on' (superadmin) sessions can read/write; a
-- non-superadmin app_rt session with any tenant context sees/writes nothing.
ALTER TABLE superadmin_invoices ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON superadmin_invoices;
CREATE POLICY tenant_isolation ON superadmin_invoices
  USING      (current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE superadmin_order_seq ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON superadmin_order_seq;
CREATE POLICY tenant_isolation ON superadmin_order_seq
  USING      (current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (current_setting('app.bypass_rls', true) = 'on');

-- Deliberately EXCLUDED from RLS:
--
-- billing_invoice_items: no tenant column of its own (child of billing_invoices via
-- invoice_id FK only); it is reachable exclusively through an already-RLS'd invoice row, so a
-- policy here would be redundant and would need its own subquery-based USING clause with no
-- extra isolation benefit.
--
-- outbox_events: OutboxRelay (see outbox/OutboxRelay.java) polls/updates this table on a
-- @Scheduled background thread that runs with NO TenantContext set (bypass_rls stays 'off',
-- current_school_id stays unset). Any RLS policy here — branch-keyed or bypass-only — would
-- make USING/WITH CHECK evaluate false for every row on that thread, and the relay would
-- silently stop publishing outbox events with no error surfaced. This mirrors
-- operations-service's firefighting.outbox_events (V6__outbox_events.sql), which is likewise
-- deliberately left without RLS for the same contextless-scheduler reason.
