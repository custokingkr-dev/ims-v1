# Notification RLS — Design

**Date:** 2026-07-08
**Status:** Approved for planning
**Services touched:** platform-service (notification schema).

Add RLS to the notification tables that carry `school_id`. platform-service already has `TenantAwareDataSource` (GUCs per checkout) + the `ProjectorRls` helper (from reporting RLS), so infra exists.

## Findings (research)
| Table | school_id? | RLS | Write strategy |
|---|---|---|---|
| `notification_broadcasts` | yes (nullable) | **standard policy** | superadmin-only writes (always bypass); harmless/defense-in-depth |
| `notification_logs` | yes (nullable) | **standard policy** | **needs `ProjectorRls.allow(jdbc)` bypass** in the writer — see below |
|  ~~`notification_sender_profiles`~~ (EXCLUDED — read context-less by MSG91 delivery worker) | yes (nullable) | **standard policy** | request-scoped via `resolveSchoolId` (context always matches) — no bypass |
| `whatsapp_onboarding_sessions` | yes (NOT NULL) | **standard policy** | request-scoped — no bypass |
| `notification_delivery_logs` | no (broadcast_id only) | exclude | — |
| `notification_inbox_events`, `notification_delivery_attempts` | no | exclude | — |

**The write-context trap:** `notification_logs` is written by `NotificationLogCommandController` (`POST /api/v1/notifications/logs`) gated only by the internal `X-Notification-Service-Token` — **system-internal ingestion with NO matching tenant context** (explicitly tested by `NotificationTenantScopingTest.systemInternalLogIngestion_worksWithoutSuperadminContext`, which posts with no `X-Authenticated-*` headers). So the writer's `TenantContext` school_id does NOT reliably equal the row's `school_id` → a standard `WITH CHECK` would REJECT the insert and break notification logging. Fix: call `ProjectorRls.allow(jdbc)` as the first statement of the write method (`NotificationLogCommandRepository.createRequestLog`). Reads of notification_logs (from `ReportingReadRepository`) are request-scoped and self-filter by school_id, so the `USING` clause is fine.

## Migration
`notification/V8__enable_rls.sql` (next free — head is V7). Standard policy (verbatim from `reporting/V6`/`V22`) on the 4 tables:
```sql
ALTER TABLE notification.<t> ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON notification.<t>;
CREATE POLICY tenant_isolation ON notification.<t>
  USING      (school_id = nullif(current_setting('app.current_school_id', true),'')::bigint OR current_setting('app.bypass_rls', true)='on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true),'')::bigint OR current_setting('app.bypass_rls', true)='on');
```
Header comment noting `notification_logs` requires the `ProjectorRls` bypass in its writer.

## Testing
New `NotificationRlsIntegrationTest` (mirror `ReportingFactRlsIntegrationTest`): app_rt NOBYPASSRLS + TenantAwareDataSource. Per table: isolation reads (school-A only A, B only B, superadmin all, no-context none); `WITH CHECK` blocks cross-tenant insert (for the standard-policy tables); and — critical — `NotificationLogCommandRepository.createRequestLog` with `TenantContext.clear()` (no context) SUCCEEDS and lands the row (proves the bypass; would RED without it).

## Files
`notification/V8__enable_rls.sql` (new), `NotificationLogCommandRepository.java` (add `ProjectorRls.allow` to `createRequestLog`), `NotificationRlsIntegrationTest.java` (new).
