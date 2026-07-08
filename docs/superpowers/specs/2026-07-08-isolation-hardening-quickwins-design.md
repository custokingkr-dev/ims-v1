# Isolation Audit Hardening — Quick Wins — Design

**Date:** 2026-07-08
**Status:** Approved for planning
**Services touched:** platform-service (2 fixes), identity-service (1), billing-service (1).

Four focused, low-risk fixes from the 2026-07-08 tenant-isolation audit (the remaining concrete gaps after the admin-endpoint BAC fixes in merge `0f3c99d`). Larger/riskier items — RLS backstops on `tenant_school`/reporting tables, the `academic_year_id` hardcoding, and the Operator multi-school feature — are explicitly **deferred** (each needs its own design).

## A — Attendance KPI cross-school leak (platform)
`ReportingReadRepository.commandCenterSummary(schoolId, platform=false)` computes the per-school `attendanceSections` count from `reporting.fact_attendance_daily` **without a `school_id` predicate** — so a non-superadmin school dashboard's "Attendance Today: N sections" tile shows the platform-wide count.

**Fix:** add `AND school_id = :schoolId` to that one count query (the `:schoolId` param is already bound in this method, as the sibling `feesPaid`/`overdueCount`/`openFF` queries show). No other change.

*(Out of scope: the hardcoded `academic_year_id = 'ay_2025_26'` on that query and elsewhere in this repo is a separate correctness bug — it needs a real current-academic-year source and affects several queries; leave it untouched here and note it as a follow-up.)*

## B — Notification status IDOR (platform)
`NotificationStatusController.getStatus(eventId)` (`GET /api/v1/notifications/{eventId}`) returns any event's delivery status/attempts/last-error by raw `eventId`, gated only by the internal `X-Notification-Service-Token` (injected by the gateway on every routed call). `notification_inbox_events` has **no `school_id` column**, so per-school scoping isn't possible, and **no in-repo consumer** calls this endpoint (no FE, no other service).

**Fix:** add `TenantScope.requireSuperAdmin();` after the existing `requireValidToken(...)` call (platform-service's `TenantScope`; role comes from `X-Authenticated-Role`, already populated by the platform `TenantContextFilter`). Superadmin-only closure.

## C — RBAC read info-disclosure (identity)
`RbacReadController` read endpoints are gated only by the internal service token. Two leak cross-school data: `GET /rbac/user-role-assignments` (all assignments across schools) and `GET /rbac/audit` (RBAC audit log, `school_id`/`zone_id` optional, unscoped).

**Fix:** add `TenantScope.requireSuperAdmin();` after `requireToken(...)` in `userRoleAssignments` and `audit`. Leave the platform-global reference reads (`/rbac/roles`, `/rbac/permissions`, `/rbac/role-permissions`) and the already-scoped per-user reads (`/rbac/users/{id}/roles`, `/rbac/users/{id}/permissions`) unchanged.

## D — Billing invoice-ID year bug (billing)
`BillingInvoiceRepository.allocateInvoiceId()` returns `"INV-2025-0" + next` — the year is hardcoded, so invoices minted in 2026+ still read `INV-2025-...`. (Not a tenancy bug; the counter is a correct global sequence.)

**Fix:** derive the year dynamically — `"INV-" + java.time.Year.now().getValue() + "-0" + next`. Keep the `-0<n>` suffix format unchanged. (Production code may call `Year.now()`; the no-`now()` rule is workflow-scripts-only.)

## Error handling
| Condition | HTTP | Message |
|---|---|---|
| non-superadmin hits `GET /api/v1/notifications/{eventId}` | 403 | "superadmin required" (existing `requireSuperAdmin()`) |
| non-superadmin hits `GET /rbac/user-role-assignments` or `/rbac/audit` | 403 | "superadmin required" |
| (A) and (D) | — | no auth change; correctness only |

## Testing
- **A:** a `ReportingReadRepository`/command-center test asserting the per-school `attendanceSections` count reflects only the caller's school (seed two schools' `fact_attendance_daily` rows for `CURRENT_DATE`; assert the school-scoped summary counts only its own). If the existing test suite already exercises `commandCenterSummary`, extend it; otherwise a focused repository/integration test.
- **B:** a `NotificationStatusController` test: non-superadmin (`X-Authenticated-Role: ADMIN`) → 403; superadmin → reaches the mocked inbox repository. Mirror the platform controller test style.
- **C:** an `RbacReadController` test: non-superadmin → 403 on `user-role-assignments` and `audit`; superadmin → reaches the mocked repository. Confirm the untouched reads still allow a non-superadmin (or their existing behavior).
- **D:** a `BillingInvoiceRepository` test asserting the minted invoice id starts with `"INV-" + currentYear + "-0"` (avoid hardcoding the year in the assertion — compute `Year.now()`), or update the existing invoice-id test if one asserts `INV-2025`.

## Files
**platform-service:** `persistence/ReportingReadRepository.java` (A), `api/NotificationStatusController.java` (B) + tests.
**identity-service:** `api/RbacReadController.java` (C) + test.
**billing-service:** `persistence/BillingInvoiceRepository.java` (D) + test.

## Deferred (documented follow-ups — not built here)
- RLS backstops on `tenant_school` core tables + reporting fact/dim/notification/identity tables (zone-scoped access + superadmin listing + event-projector writes make a naïve `school_id` policy unsafe).
- `academic_year_id` hardcoding across reporting (needs a current-year source).
- Operator multi-school assignment feature (needs multi-scope JWT/session plumbing).
