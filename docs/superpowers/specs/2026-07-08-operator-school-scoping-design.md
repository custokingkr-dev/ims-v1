# Operator School-Scoping — Design

**Date:** 2026-07-08
**Status:** Approved for planning
**Services touched:** identity-service (assign + token claim), api-gateway (header), school-core-service (enforcement + catalog RLS), frontend (superadmin assignment UI).

Let a superadmin **assign a specific set of schools** to an `OPERATIONS` ("Operator") user, and **restrict** that operator's cross-school access to exactly that set. Today the `OPERATIONS` role is an unrestricted global cross-school reader; this bounds it.

## Current state (verified)
- `OPERATIONS` is an existing role (valid provisioning roles = `ADMIN`/`OPERATIONS`, `IdentityUserProvisioningRepository:186`; provisioned per school via `/schools/{schoolId}/operations-user`).
- Its cross-school privilege lives ONLY in school-core catalog:
  - `TenantScope.resolvePlatformReadScope(requested)` returns `requested` unchanged for superadmin **or operations** (passthrough, `null = all schools`) — used by `CatalogPublicCompatibilityController` catalog reads (`:50,:59`).
  - `CatalogReadRepository.allowCrossSchoolReadForOperations()` (`:562`) sets a transaction-local `app.bypass_rls='on'` for operations users on catalog reads (`:70,:103,:143,:430`).
  - `CatalogPublicCompatibilityController:139` lets superadmin **or operations** mark orders delivered (cross-school write).
- `catalog.catalog_orders` already has RLS (`catalog/V4__enable_rls.sql`): `school_id = current_setting('app.current_school_id') OR bypass`.
- Storage already supports assignment: `identity.user_role_assignments` holds `(user, role, school_id)` rows; `RbacCommandRepository.assignSchoolRole` creates them (one per school, superadmin-gated). The **assigned set** = distinct `school_id` from the user's active `OPERATIONS` assignment rows.

## Decisions (settled)
- Operator = the existing **OPERATIONS** role (not a new role).
- Operator works across **all assigned schools together** (scoped multi-school, matching today's behavior — narrowed from "all" to "the set").
- Bound **both** the cross-school reads AND the existing cross-school writes (mark-delivered) to the set.

## Part 1 — Assignment (identity + FE)
- Superadmin assigns schools to an operator by creating `OPERATIONS` school-scoped rows. Add a bulk endpoint `POST /api/v1/rbac/users/{userId}/operator-schools` `{ schoolIds: [...] }` (superadmin-gated) that, in one transaction, syncs the user's active `OPERATIONS` school assignments to the given set (add missing, revoke removed) via the existing `assignScopedRole`/revoke machinery. Add `GET /api/v1/rbac/users/{userId}/operator-schools` → the current assigned `{schoolId, schoolName}` set (identity resolves names via its existing `TenantSchoolClient`).
- **FE:** on `SchoolManagementPage` (or an operators admin panel), a superadmin-only multi-select "Assign schools to this operator" bound to those endpoints. (The per-school "Add/Reset operations user" flow stays.)

## Part 2 — Propagate the assigned set (identity → gateway → school-core)
- **identity token:** for an `OPERATIONS` user, add an `ops_schools` claim (JSON array of assigned school ids) to the access token, resolved in `issueSession` from the user's active `OPERATIONS` assignment rows. Empty/absent for non-operations users. (Keep `ver=3`; this is additive.)
- **gateway:** `principalFromClaims` reads `ops_schools` (default `[]`); `proxyToUrl` injects `x-authenticated-operator-schools` (CSV) — part of the trusted `x-authenticated-` set (already spoof-stripped).
- **school-core:** `TenantContext` gains `Set<Long> operatorSchools` (parsed from the header by `TenantContextFilter`, never null → empty set).

## Part 3 — Enforce the set (school-core catalog)
- `TenantScope.resolvePlatformReadScope(requested)`: for an operations caller —
  - `requested != null`: allow only if `requested ∈ operatorSchools`; else **403** ("school not in operator scope"). Return `requested`.
  - `requested == null` ("all"): the operator's reads must be bounded to the set (see RLS below); return `null` (the RLS policy narrows to the set, so downstream stays unchanged).
  - (superadmin still returns `requested` unchanged — true all-schools.)
- `CatalogReadRepository.allowCrossSchoolReadForOperations()`: instead of full `app.bypass_rls='on'`, set a transaction-local `app.operator_schools = '<csv>'` GUC from `TenantContext.operatorSchools` (and NOT bypass). 
- **Catalog RLS policy** (new migration `catalog/V…__operator_scope.sql`): extend `catalog_orders` (and any other operations-read catalog table — verify: `catalog_orders`, and check `annual_plan_items`) `USING`/`WITH CHECK` to also allow rows whose `school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true),''), ',')::bigint[])`. So an operator's reads are RLS-scoped to their set automatically — no per-query `IN` filter, and a forgotten filter fails closed (RLS enforces).
- **Mark-delivered write** (`CatalogPublicCompatibilityController:139` / the repo path): an operations caller may mark an order delivered only if that order's `school_id ∈ operatorSchools` (check in the guard, or rely on the extended `WITH CHECK` blocking the update). Add an explicit app-level check for a clear 403.
- **Empty operator set** (an OPERATIONS user with no assigned schools yet): fail closed — they see/act on **no** schools (the RLS `operator_schools` clause matches nothing; the specific-school request 403s). This is correct (an unassigned operator has no scope) and safe.

## Part 4 — Permission-union note (separate, not required here)
`RbacLookupRepository.permissionCodes()` unions permission codes across all a user's assignments ignoring school. For a pure OPERATIONS-at-N-schools user the role (hence codes) is the same at each school, so the union is harmless here. It remains a latent bug for **mixed-role** multi-school users — tracked as a separate follow-up ([[tenant-isolation-audit-followups]]); NOT fixed in this feature.

## Error handling
| Condition | HTTP | Message |
|---|---|---|
| operator requests a specific school not in their set | 403 | "school not in operator scope" |
| operator requests "all" | (allow) | reads/writes bounded to the assigned set by RLS |
| operator with empty assigned set | (allow, empty) | sees/acts on no schools |
| superadmin | (allow) | true all-schools, unchanged |
| assign endpoints by non-superadmin | 403 | requireSuperAdmin (existing) |

## Testing
- **identity:** token for an OPERATIONS user carries `ops_schools` = their assigned set; assign/sync endpoints add+revoke correctly and are superadmin-gated.
- **gateway:** `principalFromClaims` exposes `ops_schools`; header injected; spoof-stripped.
- **school-core:** `resolvePlatformReadScope` 403s an operator for an out-of-set school, allows in-set; a `CatalogRlsIntegrationTest`-style test with the `app.operator_schools` GUC set to {A,B} shows the operator sees A+B orders but not C, and cannot mark C's order delivered; superadmin still sees all; a bypass-less non-operator still sees only its own school.
- **FE:** `npm run build`; superadmin assign UI wired.

## Files
**identity:** `JwtService` (`ops_schools` claim), `IdentityAuthService.issueSession` (resolve set), `RbacReadController`/`RbacCommandRepository` (operator-schools sync + read endpoints) + tests.
**gateway:** `server.js` (`principalFromClaims` + header) + `server.test.js`.
**school-core:** `security/{TenantContext,TenantContextFilter,TenantScope}.java`, `persistence/CatalogReadRepository.java` (GUC), `api/compat/CatalogPublicCompatibilityController.java` (mark-delivered guard), new `catalog/V…__operator_scope.sql`, tests.
**frontend:** `SchoolManagementPage.tsx` (or operators admin) assign-schools UI + api calls.
