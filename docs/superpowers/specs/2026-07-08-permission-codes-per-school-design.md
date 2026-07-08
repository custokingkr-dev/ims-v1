# Per-School Permission Resolution at Login — Design

**Date:** 2026-07-08
**Status:** Approved for planning
**Services touched:** identity-service.

Fix the `permissionCodes()` union-all leak: at login/refresh the JWT + response bake the union of a user's permission codes across ALL their `user_role_assignments` (any school/zone), applied against their single active school. Resolve per the active school instead.

## Findings (research)
- Bug: `RbacLookupRepository.permissionCodes(userId)` and `roleNames(userId)` — no `school_id`/`zone_id` filter. Called at `IdentityAuthService.issueSession:134` (JWT) and `responseFor:156`/`:155` (response body).
- Correct query already exists: `RbacReadRepository.effectivePermissions(userId, schoolId, zoneId)` — includes platform-global (`school_id IS NULL AND zone_id IS NULL`) OR school-match OR zone-match. Used correctly by `RbacReadController.userPermissions`.
- Resolve against `user.getBranchId()` / `user.getZoneId()` (the home school/zone the gateway already trusts downstream).
- Blast radius: single-school users, superadmins, platform-role users, zone-admins → **no change**; only genuinely cross-school-leaked codes get dropped (intended tightening; also tightens FE `can()` and any JWT-permission backend gate — the goal). Tightens **on refresh**, not just next login.
- `roleNames` → `AuthResponse.roles` is display-only (no server authz consumer, no FE consumer). Leave unscoped.

## The operator regression (critical — must handle)
`syncOperatorSchools` can move an operator to a school set that EXCLUDES their stale `app_users.branch_id` (never updated). Then `effectivePermissions(userId, branchId, zoneId)` matches nothing → the operator's permission array is EMPTY → they fail the permission-code gate for every school. Real regression.

**Mitigation (b):** an operator's permission CODES come from the OPERATIONS role (same at every school) and their SCHOOL scope is enforced separately via `ops_schools`/`TenantContext.operatorSchools()`. So when the user has any active OPERATIONS assignment (`operatorSchoolIds(userId)` non-empty), include the OPERATIONS role's permission codes UNCONDITIONALLY (in addition to `effectivePermissions(branchId, zoneId)`). Self-contained in the permission-resolution path; no change to provisioning/sync.

## Change
- In `IdentityAuthService`, resolve login permissions as: `effectivePermissions(user.getId(), user.getBranchId(), user.getZoneId())`, UNION (if `operatorSchoolIds(user.getId())` non-empty) the OPERATIONS role's permission codes. Apply at BOTH `issueSession` (JWT) and `responseFor` (response body) so they match. Sort as today.
- Add a repository method for "permission codes of the OPERATIONS role" (or reuse an existing role→codes query) for the operator union.
- Swap the injected repo as needed (`RbacReadRepository.effectivePermissions` + a role-codes query); consolidate the duplicate `operatorSchoolIds` (identical on both `RbacLookupRepository` and `RbacReadRepository`) onto one canonical repo.
- Delete the now-dead `RbacLookupRepository.permissionCodes` (+ its test/mocks). Keep `roleNames` (still used for `AuthResponse.roles`, unscoped) — or move it to the canonical repo unchanged.

## Testing (mirror the firefighting fail-closed / IdentityTenantScoping style)
- User with role A (code CODE_A) at school 1 and role B (CODE_B) at school 2, `branch_id=1`: login → permissions contain CODE_A, NOT CODE_B. Assert `effectivePermissions(userId, 1, zone)` used (not `permissionCodes`).
- Platform-global assignment (school/zone null) + school-1 role, branch_id=1 → both codes present.
- Zone-admin (assignment zone_id=Z, school_id null; app_users.zone_id=Z) → codes present.
- **Operator regression:** operator provisioned at school 1 (branch_id=1), then `syncOperatorSchools`→[2,3] (school-1 OPERATIONS row revoked). Login → permissions STILL include the OPERATIONS codes (mitigation b), and `ops_schools` = {2,3} (not 1).
- Update `IdentityAuthServiceRotationTest` mocks (currently mock `permissionCodes`/`roleNames`).

## Files
`IdentityAuthService.java` (2 call sites), `RbacReadRepository.java`/`RbacLookupRepository.java` (role-codes query + consolidation + delete dead union), tests.

## Out of scope
`roleNames` scoping (display-only); any provisioning/sync change to keep branch_id in the operator set (mitigation c) — not needed with (b).
