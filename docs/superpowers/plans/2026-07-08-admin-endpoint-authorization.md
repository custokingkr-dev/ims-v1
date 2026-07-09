# Admin-Endpoint Caller Authorization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gate every mutating admin endpoint on `TenantScope.requireSuperAdmin()` so a non-superadmin caller can no longer self-grant SUPERADMIN, reset arbitrary users' passwords, provision users at any school, or approve any school's spending.

**Architecture:** Pure authorization hardening. Each affected endpoint currently calls only `requireToken(...)` (validates the gateway-injected internal service token, present on every routed call). Add `TenantScope.requireSuperAdmin();` right after it — role comes from `X-Authenticated-Role` already populated in `TenantContext`. Mirrors the existing `IdentityPublicCompatibilityController` precedent (`requireToken` + `requireSuperAdmin()`). No new plumbing, no schema, no gateway change.

**Tech Stack:** Spring Boot 4.0.7 / Java 25 (identity-service, platform-service).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-08-admin-endpoint-authorization-design.md`.
- Use the EXISTING `TenantScope.requireSuperAdmin()` in each service (throws `403 "superadmin required"` when `TenantContext.get().isSuperAdmin()` is false). Do not add new plumbing or permission-code propagation.
- Superadmin-only for ALL listed endpoints. Do NOT gate the `GET` read endpoints (`GET /users`, `GET /users/{id}` are already tenant-scoped; `/rbac` reads are an out-of-scope follow-up — leave them exactly as-is).
- Add `TenantScope.requireSuperAdmin();` immediately AFTER the existing `requireToken(...)` line in each endpoint (so an invalid service token still 401s first, then a non-superadmin caller 403s).
- Backend TDD. Mirror existing controller tests for the auth assertions: `services/identity-service/.../api/compat/IdentityPublicCompatibilityControllerTest.java` shows the non-superadmin→403 / superadmin→ok pattern for identity; `services/platform-service/.../api/compat/ReportingApprovalsCompatibilityControllerTest.java` is the platform test to extend.
- Do not commit local tool settings.
- Build/test (Windows Bash tool): `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/<svc>/pom.xml -q -Dtest=<T> test`. identity-service also needs `APP_JWT_SECRET`/`APP_AADHAR_SECRET` only for full app boot — controller unit/MockMvc tests do not.

---

### Task 1: identity-service — gate RBAC, provisioning, and user-mutation endpoints

**Files:**
- Modify: `services/identity-service/src/main/java/com/custoking/ims/identityservice/api/RbacReadController.java` (createRole, updateRole, assignPlatformRole, assignSchoolRole, assignZoneRole, revokeAssignment)
- Modify: `services/identity-service/src/main/java/com/custoking/ims/identityservice/api/IdentityProvisioningController.java` (provisionSchoolUser, provisionZoneAdmin)
- Modify: `services/identity-service/src/main/java/com/custoking/ims/identityservice/api/UserDirectoryController.java` (resetPassword, disableUser, enableUser)
- Test: add/extend controller tests (see Step 1) — likely new `RbacAuthorizationTest.java`, `IdentityProvisioningAuthorizationTest.java`, `UserDirectoryAuthorizationTest.java` or extend the existing `RbacValidationTest`/`UserDirectoryValidationTest`.

**Interfaces:**
- Consumes: `TenantScope.requireSuperAdmin()` (existing), `TenantContext` role from `X-Authenticated-Role` (existing filter).

- [ ] **Step 1: Write the failing authorization tests**

Read `services/identity-service/.../api/compat/IdentityPublicCompatibilityControllerTest.java` first to copy the exact MockMvc + `X-Authenticated-Role` + service-token setup (that controller already does `requireToken` + `requireSuperAdmin()`, so its test is the template). Add tests asserting, for a representative endpoint in EACH of the three controllers, that a **non-superadmin** caller (valid `X-Identity-Service-Token` + `X-Authenticated-Role: ADMIN` + `X-Authenticated-School-Id: 10`) gets **403**, and a **superadmin** caller (`X-Authenticated-Role: SUPERADMIN`) is allowed (reaches the mocked repository / returns success). Minimum coverage:
```
// RbacReadController
assignPlatformRole:  ADMIN -> 403 ;  SUPERADMIN -> repo.assignPlatformRole invoked
createRole:          ADMIN -> 403 ;  SUPERADMIN -> repo.createRole invoked
revokeAssignment:    ADMIN -> 403 ;  SUPERADMIN -> allowed
// IdentityProvisioningController
provisionSchoolUser: ADMIN -> 403 ;  SUPERADMIN -> repo.provisionSchoolUser invoked
provisionZoneAdmin:  ADMIN -> 403 ;  SUPERADMIN -> allowed
// UserDirectoryController
resetPassword:       ADMIN -> 403 ;  SUPERADMIN -> repo.resetPassword invoked
disableUser:         ADMIN -> 403 ;  SUPERADMIN -> allowed
enableUser:          ADMIN -> 403 ;  SUPERADMIN -> allowed
```
Use the same MockMvc `standaloneSetup(new <Controller>(mockRepo, SERVICE_TOKEN)).addFilters(new TenantContextFilter())` style the existing identity tests use, mocking the repository so the superadmin path doesn't need a DB. (If a controller test in this service instead sets `TenantContext.set(...)` and calls the controller directly, follow that file's style — the key assertion is non-superadmin→`ResponseStatusException`/403, superadmin→through.)

- [ ] **Step 2: Run — verify failure**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/identity-service/pom.xml -q -Dtest='*Authorization*,RbacValidationTest,UserDirectoryValidationTest' test`
Expected: the new non-superadmin→403 assertions FAIL (endpoints currently allow any caller → return 2xx, not 403).

- [ ] **Step 3: Gate the RbacReadController mutations**

In `RbacReadController.java`, add `TenantScope.requireSuperAdmin();` immediately after the `requireToken(token, "identity:write");` line in EACH of: `createRole`, `updateRole`, `assignPlatformRole`, `assignSchoolRole`, `assignZoneRole`, `revokeAssignment`. Add the import `import com.custoking.ims.identityservice.security.TenantScope;` if not present. Do NOT touch the `@GetMapping` read methods.

- [ ] **Step 4: Gate the IdentityProvisioningController endpoints**

In `IdentityProvisioningController.java`, add `TenantScope.requireSuperAdmin();` immediately after `requireToken(token, "identity:write");` in `provisionSchoolUser` and `provisionZoneAdmin`. Add the `TenantScope` import if not present.

- [ ] **Step 5: Gate the UserDirectoryController mutations**

In `UserDirectoryController.java`, add `TenantScope.requireSuperAdmin();` immediately after `requireToken(token, "identity:write");` in `resetPassword`, `disableUser`, and `enableUser`. (`TenantScope` is already imported.) Do NOT touch `GET /users` or `GET /{id}`.

- [ ] **Step 6: Run — GREEN + full identity suite**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/identity-service/pom.xml -q test`
Expected: BUILD SUCCESS. If any EXISTING test drove one of these endpoints as a non-superadmin to assert body/response mapping, it will now 403 — update that test to set `X-Authenticated-Role: SUPERADMIN` (it was testing mapping, not auth; do not weaken the guard). Verify no read-endpoint test regressed.

- [ ] **Step 7: Commit**

```bash
git add services/identity-service/src/main/java/com/custoking/ims/identityservice/api/RbacReadController.java \
        services/identity-service/src/main/java/com/custoking/ims/identityservice/api/IdentityProvisioningController.java \
        services/identity-service/src/main/java/com/custoking/ims/identityservice/api/UserDirectoryController.java \
        services/identity-service/src/test/java/com/custoking/ims/identityservice/api/
git commit -m "fix(identity): require superadmin on RBAC, provisioning, and user-mutation endpoints (close cross-school BAC)"
```

---

### Task 2: platform-service — gate the approvals endpoints

**Files:**
- Modify: `services/platform-service/src/main/java/com/custoking/ims/platformservice/api/compat/ReportingApprovalsCompatibilityController.java` (approvals, decide)
- Test: `services/platform-service/src/test/java/com/custoking/ims/platformservice/api/compat/ReportingApprovalsCompatibilityControllerTest.java` (extend)

**Interfaces:**
- Consumes: `TenantScope.requireSuperAdmin()` (existing in platform-service), `TenantContext` role from `X-Authenticated-Role`.

- [ ] **Step 1: Write the failing authorization test**

Read the existing `ReportingApprovalsCompatibilityControllerTest.java` first for its setup style. Add assertions: a non-superadmin caller (valid `X-Reporting-Service-Token` + `X-Authenticated-Role: ADMIN`) → **403** on both `GET /api/v1/approvals` and `POST /api/v1/approvals/{id}/{action}`; a superadmin caller (`X-Authenticated-Role: SUPERADMIN`) → reaches the mocked `ReportingApprovalRepository` (`approvals(...)` / `decide(...)` invoked). Mock the repository so the superadmin path needs no DB.

- [ ] **Step 2: Run — verify failure**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/platform-service/pom.xml -q -Dtest=ReportingApprovalsCompatibilityControllerTest test`
Expected: the non-superadmin→403 assertions FAIL (endpoints currently allow any caller).

- [ ] **Step 3: Gate the endpoints**

In `ReportingApprovalsCompatibilityController.java`, add `TenantScope.requireSuperAdmin();` immediately after the `requireToken(token, "reporting:read");` line in BOTH `approvals` and `decide`. Add `import com.custoking.ims.platformservice.security.TenantScope;` (verify the exact package for platform-service's `TenantScope`).

- [ ] **Step 4: Run — GREEN + full platform suite**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/platform-service/pom.xml -q test`
Expected: BUILD SUCCESS. Update any existing approvals test that drove these as a non-superadmin (asserting mapping) to a SUPERADMIN role.

- [ ] **Step 5: Commit**

```bash
git add services/platform-service/src/main/java/com/custoking/ims/platformservice/api/compat/ReportingApprovalsCompatibilityController.java \
        services/platform-service/src/test/java/com/custoking/ims/platformservice/api/compat/ReportingApprovalsCompatibilityControllerTest.java
git commit -m "fix(platform): require superadmin on /api/v1/approvals list + decide (close cross-school approvals)"
```

---

## Self-Review

**Spec coverage:** identity RBAC mutations (6) → Task 1 Step 3. identity provisioning (2) → Task 1 Step 4. identity user-mutations (3) → Task 1 Step 5. platform approvals (2) → Task 2 Step 3. All 13 endpoints from the spec covered. Reads left untouched (spec's out-of-scope). Tests (non-superadmin→403, superadmin→allowed) → Task 1 Step 1 / Task 2 Step 1.

**Placeholder scan:** no TBD/TODO. "read the existing test first" points at named template files (`IdentityPublicCompatibilityControllerTest`, `ReportingApprovalsCompatibilityControllerTest`) that already demonstrate the exact 403/allow assertion pattern — not a gap. "verify the exact package for platform-service's TenantScope" is a read-first import instruction.

**Consistency:** `TenantScope.requireSuperAdmin()` used identically in both tasks; placed after `requireToken` in every case; role source (`X-Authenticated-Role` → `TenantContext.isSuperAdmin()`) consistent; the 403 assertion for non-superadmin and repo-invocation assertion for superadmin are consistent across all endpoints.
