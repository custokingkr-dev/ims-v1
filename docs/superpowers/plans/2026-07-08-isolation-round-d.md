# Isolation Follow-ups Round D Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Two RLS builds — notification schema RLS (platform) and billing RLS + a new billing `TenantAwareDataSource` (billing). identity RLS is deferred (auth-outage risk; user-confirmed).

**Architecture:** Independent, different services → parallel. Both mirror the established RLS pattern; the two traps (notification_logs context-less ingestion; billing outbox_events @Scheduled relay) are handled per the specs.

**Tech Stack:** Spring Boot 4.0.7 / Java 25 (platform, billing), Postgres RLS.

## Global Constraints

- Specs: `docs/superpowers/specs/2026-07-08-notification-rls-design.md`, `…-billing-rls-design.md`.
- Standard policy shape verbatim from `reporting/V6__enable_rls.sql`. `app_rt` already has the grants for both schemas (no GRANT statements needed in the migrations).
- Backend TDD. Do not commit local tool settings.
- Build/test: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/<svc>/pom.xml -q -Dtest=<T> test`.

---

### Task 1: platform-service — notification schema RLS

**Files:** new `notification/V8__enable_rls.sql`, `NotificationLogCommandRepository.java` (bypass), new `NotificationRlsIntegrationTest.java`.

- [ ] **Step 1: Confirm version + read mirrors.** List `.../db/migration/notification/` (head V7 → use V8). READ `reporting/V22__enable_rls_facts_dims.sql` + `security/ProjectorRls.java` + `security/ReportingFactRlsIntegrationTest.java`. Confirm the 4 target tables have `school_id`: `notification_broadcasts`, `notification_logs`, `notification_sender_profiles`, `whatsapp_onboarding_sessions`.

- [ ] **Step 2: Write the failing RLS integration test.** `NotificationRlsIntegrationTest` (app_rt NOBYPASSRLS + TenantAwareDataSource + TransactionTemplate, mirror `ReportingFactRlsIntegrationTest`). Seed 2 schools' rows per table. Assert isolation reads (school-10 sees only 10, 20 only 20, superadmin all, no-context none); `WITH CHECK` blocks a cross-tenant insert on the standard-policy tables; and — critical — `NotificationLogCommandRepository.createRequestLog(...)` with `TenantContext.clear()` (no context) SUCCEEDS and lands the row. Run → RED.

- [ ] **Step 3: Migration + the logs bypass.** Create `notification/V8__enable_rls.sql`: `ENABLE ROW LEVEL SECURITY` + `tenant_isolation` policy (standard `school_id` shape, `DROP POLICY IF EXISTS` first) on the 4 tables. Add `ProjectorRls.allow(jdbc)` (the existing platform helper) as the FIRST statement of `NotificationLogCommandRepository.createRequestLog` (its writes are context-less system ingestion → would fail `WITH CHECK` without it). Confirm that method is `@Transactional`. Header comment noting the logs-bypass requirement.

- [ ] **Step 4: Run — GREEN + full platform suite.** `-q test`. Investigate any failure (a notification write missing the bypass, or a read test breaking).

- [ ] **Step 5: Commit** — `feat(platform): RLS on notification tables (broadcasts/logs/sender-profiles/whatsapp); logs writer bypass for context-less ingestion`

---

### Task 2: billing-service — TenantAwareDataSource + billing RLS

**Files:** new `security/TenantAwareDataSource.java` + `security/TenantDataSourceConfig.java` (copied from platform), new `billing/V4__enable_rls.sql`, new `BillingRlsIntegrationTest.java`.

- [ ] **Step 1: Copy the datasource wiring.** READ `platform-service/.../security/TenantAwareDataSource.java` + `TenantDataSourceConfig.java`; copy both into `billing-service/.../security/`, changing ONLY the package declaration (and confirm billing's `TenantContext` has the same `schoolId()`/`isSuperAdmin()` API — it mirrors platform). Confirm billing's Hikari `DataSource` bean gets wrapped and Flyway's datasource stays unwrapped (billing already splits app_rt runtime vs owner Flyway per docker-compose).

- [ ] **Step 2: Write the failing RLS integration test.** `BillingRlsIntegrationTest` (mirror `school-core FeeRlsIntegrationTest`): Testcontainers, Flyway-migrate as owner, `CREATE ROLE app_rt ... NOBYPASSRLS` + `billing` grants, seed 2 branches (10: 2 customers/invoices/payments; 20: 1). Wrap app_rt Hikari in `TenantAwareDataSource`. Assert: branch-10 ctx sees only 10's rows in `billing_customers`/`billing_invoices`/`billing_payments`, branch-20 only 20's, superadmin (role SUPERADMIN, schoolId null → bypass) all, no-context none, `WITH CHECK` blocks a cross-branch insert. Plus **outbox-still-works**: with `TenantContext.clear()`, a raw `SELECT`/`UPDATE` on `outbox_events` still sees/updates rows (proves no-RLS-on-outbox). Run → RED.

- [ ] **Step 3: Migration.** Create `billing/V4__enable_rls.sql` (head V3 → V4): branch_id-keyed policy (`branch_id = nullif(current_setting('app.current_school_id', true),'')::bigint OR current_setting('app.bypass_rls', true)='on'` on USING+WITH CHECK, `DROP POLICY IF EXISTS` first) on `billing_customers`, `billing_invoices`, `billing_payments`; bypass-only policy (`USING/WITH CHECK (current_setting('app.bypass_rls', true)='on')`) on `superadmin_invoices`, `superadmin_order_seq`. Do NOT enable RLS on `billing_invoice_items` or `outbox_events` — add comments explaining why (child-of-invoice; contextless @Scheduled relay would break).

- [ ] **Step 4: Run — GREEN + full billing suite.** `-q test`. CRITICAL: existing billing tests exercise superadmin flows (role SUPERADMIN → bypass) so they must still pass; and the `BillingSuperadminGateArchTest` (from Round C) is unaffected. Confirm the new datasource wrapper doesn't break any existing repository/service test (they run with a superadmin or owner context; if a test runs a repo write with NO context under the wrapped app_rt datasource + RLS, it will now fail closed — set a superadmin/bypass context in that test, don't weaken RLS).

- [ ] **Step 5: Commit** — `feat(billing): TenantAwareDataSource + branch-keyed RLS on customers/invoices/payments (bypass-only ledger; outbox excluded)`

---

## Self-Review

**Spec coverage:** notification (4 tables + logs bypass + test) → Task 1. billing (datasource + branch/bypass policies + outbox/items excluded + test) → Task 2. identity RLS deferred (documented, user-confirmed). **Placeholder scan:** "confirm version / read mirror / copy datasource" are read-first instructions at named files; the policy SQL + the two traps (logs bypass, outbox exclusion) are given in full. **Consistency:** standard policy shape identical to reporting/V6; ProjectorRls (existing) reused for notification_logs; billing datasource copied verbatim from platform; branch_id substituted for school_id in billing's branch-keyed policy with the GUC name unchanged.
