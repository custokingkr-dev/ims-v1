# Admin-Endpoint Caller Authorization — Design

**Date:** 2026-07-08
**Status:** Approved for planning
**Services touched:** identity-service, platform-service.

Closes verified cross-school broken-access-control holes found in the tenant-isolation audit (2026-07-08). A set of **mutating admin endpoints** authenticate only the internal service token (which the api-gateway injects on *every* routed call) and never check the **caller** — so any authenticated user could self-grant SUPERADMIN, reset any user's password, or approve any school's spending. Fix: gate each with `TenantScope.requireSuperAdmin()`, mirroring the existing `IdentityPublicCompatibilityController` precedent (which already does `requireToken` + `requireSuperAdmin()`).

## Decision (settled during brainstorming)
- **Superadmin-only** for all affected admin surfaces (role management, user provisioning/mutation, cross-school approvals). Role management and approvals are inherently platform-wide; user management is centralized. School-admin self-service (scoped delegation) is explicitly **deferred** — it would need permission-code propagation into identity/platform `TenantContext`.
- Role-based check via `requireSuperAdmin()` (reads `role` from `X-Authenticated-Role`, already populated in `TenantContext`). This is the established platform-admin bypass used across the codebase; no new plumbing.

## Root cause
Each affected endpoint calls only `requireToken(token, "<scope>")`, which validates the gateway-injected internal service token (`X-Identity-Service-Token` / `X-Reporting-Service-Token`). That token is present on every gateway-routed request, so it authenticates the *route*, not the *user*. None of these endpoints check `TenantContext` (the caller's role/school). The gateway is a pass-through proxy — it verifies the JWT (authenticated) but does not enforce per-route authorization. Net effect: any logged-in user reaching these routes is authorized.

## Fix
Add `TenantScope.requireSuperAdmin();` immediately after the existing `requireToken(...)` line in each endpoint below. `requireSuperAdmin()` already exists in both services' `TenantScope` and throws `403 "superadmin required"` when `TenantContext.get().isSuperAdmin()` is false.

### identity-service
- `RbacReadController` (`api/RbacReadController.java`): `createRole` (POST /rbac/roles), `updateRole` (PUT /rbac/roles/{roleId}), `assignPlatformRole` (POST /rbac/users/{userId}/roles/platform), `assignSchoolRole` (POST .../roles/school), `assignZoneRole` (POST .../roles/zone), `revokeAssignment` (DELETE /rbac/users/{userId}/roles/{assignmentId}).
- `IdentityProvisioningController` (`api/IdentityProvisioningController.java`): `provisionSchoolUser` (POST /api/v1/users/provisioning/schools/{schoolId}/users/{role}), `provisionZoneAdmin` (POST /api/v1/users/provisioning/zones/{zoneId}/admin).
- `UserDirectoryController` (`api/UserDirectoryController.java`): `resetPassword` (POST /api/v1/users/{id}/password-reset), `disableUser` (POST /{id}/disable), `enableUser` (POST /{id}/enable). (Leave `GET /users` and `GET /{id}` unchanged — already tenant-scoped via `resolveSchoolId`.)

### platform-service
- `ReportingApprovalsCompatibilityController` (`api/compat/ReportingApprovalsCompatibilityController.java`): `approvals` (GET /api/v1/approvals), `decide` (POST /api/v1/approvals/{id}/{action}).

## Out of scope (documented follow-ups)
- **RBAC read endpoints** (`GET /rbac/roles|permissions|user-role-assignments|audit`): info disclosure of the role/permission catalog + assignment/audit rows. Lower severity (not privilege escalation); some back a superadmin console. Note as a follow-up (either superadmin-gate or tenant-scope the audit/assignments reads).
- **Defense-in-depth RLS** on the currently-unprotected tenant tables (`tenant_school` core tables, reporting fact/dim tables) and the two leaky read paths the audit found (`commandCenterSummary` attendance-sections count missing `school_id`; `NotificationStatusController` IDOR) — separate hardening pass.
- **School-admin scoped delegation** (non-superadmin managing their own school's users/roles) — needs permission-code propagation into identity/platform `TenantContext` (the `x-authenticated-permissions` header already exists from the firefighting work).
- **Multi-school Operator** feature — depends on this fix landing first (it extends the RBAC-assignment surface).

## Error handling
| Condition | HTTP | Message |
|---|---|---|
| non-superadmin hits any gated admin endpoint | 403 | "superadmin required" (existing `requireSuperAdmin()` message) |
| missing/invalid internal service token | 401 | existing `requireToken` message (unchanged) |
| superadmin | (allow) | proceeds |

## Testing
- **identity-service:** for `RbacReadController`, `IdentityProvisioningController`, `UserDirectoryController` — a test per controller (or per endpoint group) asserting a non-superadmin caller (`X-Authenticated-Role: ADMIN` + valid service token) → **403**, and a superadmin caller (`X-Authenticated-Role: SUPERADMIN`) → reaches the repository (mock verifies invocation) or returns success. Use MockMvc through the real `TenantContextFilter` (mirror existing controller tests that set `X-Authenticated-*` headers), or direct controller calls with `TenantContext.set(...)` where that's the file's existing style.
- **platform-service:** `ReportingApprovalsCompatibilityController` — non-superadmin → 403 on both `approvals` and `decide`; superadmin → repository invoked.
- Existing tests that drive these endpoints as a non-superadmin (if any) must be updated to a superadmin context (they were testing body/response mapping, not auth) — do not weaken the guard.

## Files
**identity-service:** `api/RbacReadController.java`, `api/IdentityProvisioningController.java`, `api/UserDirectoryController.java` + their tests.
**platform-service:** `api/compat/ReportingApprovalsCompatibilityController.java` + its test.
