# Billing RLS — Design

**Date:** 2026-07-08
**Status:** Approved for planning
**Services touched:** billing-service (new `TenantAwareDataSource` + billing schema RLS).

Add per-branch RLS to billing. billing-service has `TenantContext`/`TenantScope` but **no** `TenantAwareDataSource` — so this adds one (mirroring platform's) + the policies. Every billing endpoint is superadmin-gated today, so RLS is a fail-closed backstop/forcing-function (enforces nothing live yet), not a live-leak fix.

## Findings (research)
| Table | Tenant col | RLS |
|---|---|---|
| `billing_customers`, `billing_invoices`, `billing_payments` | `branch_id NOT NULL` | **branch_id-keyed policy** |
| `billing_invoice_items` | none (via `invoice_id` FK) | **no RLS** (child; reachable only via an RLS'd invoice — document as deliberate) |
| `superadmin_invoices`, `superadmin_order_seq` | school_id nullable / none | **bypass-only policy** (platform-global ledger) |
| `outbox_events` | school_id nullable | **NO RLS** (critical — see below) |

**Critical: `outbox_events` must NOT get RLS.** `OutboxRelay` runs on a `@Scheduled` thread with NO `TenantContext` (bypass off, current_school_id ''). Any policy (even bypass-only) → `USING` false for every row → the relay silently stops publishing → event-delivery outage with only a growing backlog. This mirrors `operations firefighting/V6__outbox_events.sql`, which deliberately leaves the outbox without RLS (grants DML only). Leave `billing.outbox_events` unprotected.

## Datasource wiring (new)
Copy `platform-service/.../security/TenantAwareDataSource.java` (DelegatingDataSource; per-checkout `set_config('app.current_school_id', ?, false), set_config('app.bypass_rls', ?, false)` from `TenantContext`) + `TenantDataSourceConfig.java` (BeanPostProcessor wrapping the Hikari DataSource bean) into `billing-service/.../security/`, changing only the package. Flyway's datasource is separate/unwrapped (owner) → migrations RLS-exempt (billing already splits `SPRING_DATASOURCE_USERNAME=app_rt` runtime vs `FLYWAY_USERNAME` owner per docker-compose). `app_rt` already has `billing` schema grants (`scripts/create-app-rt-role.sql`) — no grant change.

## Migration `billing/V4__enable_rls.sql` (next free — head is V3)
- **Branch-keyed** (`billing_customers`, `billing_invoices`, `billing_payments`) — mirror `firefighting/V5` shape, `branch_id` in place of `school_id` (GUC stays `app.current_school_id`):
```sql
USING      (branch_id = nullif(current_setting('app.current_school_id', true),'')::bigint OR current_setting('app.bypass_rls', true)='on')
WITH CHECK (branch_id = nullif(current_setting('app.current_school_id', true),'')::bigint OR current_setting('app.bypass_rls', true)='on')
```
- **Bypass-only** (`superadmin_invoices`, `superadmin_order_seq`) — `USING/WITH CHECK (current_setting('app.bypass_rls', true)='on')` (tenant_school V14 Group C shape).
- **Exclude** `billing_invoice_items` (comment: child of an RLS'd invoice) and `outbox_events` (comment: contextless @Scheduled relay writes it — RLS would break publishing).

## Testing
New `BillingRlsIntegrationTest` (mirror `FeeRlsIntegrationTest`): Testcontainers, Flyway-migrate as owner, `CREATE ROLE app_rt ... NOBYPASSRLS` + grants, seed 2 branches. Assert: branch-A ctx sees only branch-10 customers/invoices/payments, branch-B only 20, superadmin (schoolId=null, role SUPERADMIN → bypass) all, no-context none, `WITH CHECK` blocks a cross-branch insert. Plus an **outbox-still-works** case: with `TenantContext.clear()` (relay-style), a `SELECT`/`UPDATE` on `outbox_events` still sees/updates rows (proves the no-RLS-on-outbox decision).

## Risks
- Outbox/RLS (handled by excluding outbox_events).
- The datasource `BeanPostProcessor` wraps any DataSource bean — only one Hikari pool today (low risk; comment it, as platform does).
- Superadmin flows unchanged (role SUPERADMIN → bypass on → WITH CHECK passes).
- Verify `app_rt` billing grants are applied in Cloud SQL/staging before prod (operational step per the create-app-rt script).

## Files
`billing-service/.../security/TenantAwareDataSource.java` + `TenantDataSourceConfig.java` (new, copied from platform), `billing/V4__enable_rls.sql` (new), `BillingRlsIntegrationTest.java` (new).

## Deferred (documented)
identity RLS — auth-outage risk (login reads user tables pre-context); near-zero enforcement gain; kept deferred (user-confirmed).
