# Firefighting Per-User Permission Enforcement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce the caller's real permission code (`firefighting:approve`) on firefighting approval decisions inside operations-service, by carrying the user's permission codes in the JWT and propagating them through the gateway.

**Architecture:** identity puts the user's permission codes in the access token (`perms` claim, token `ver` 2→3); the gateway reads them and injects an `x-authenticated-permissions` header; operations' `TenantContext` carries them and a new `requirePermission("firefighting:approve")` guard gates approve-bursar/approve-principal/reject. Superadmin bypasses; a missing header (pre-ver-3 token during the ≤15-min token rollover) allows (falls back to today's behavior) so nobody is locked out mid-transition.

**Tech Stack:** Spring Boot 4.0.7 / Java 25 (identity, operations); Node.js http (gateway); React 18 + Vite + TS (frontend).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-08-firefighting-per-user-permission-design.md`.
- Canonical permission code: **`firefighting:approve`** (single code for both approval stages; true bursar≠principal SoD stays deferred).
- Access-token claim name: **`perms`** (JSON array of permission-code strings, sorted). Token `ver` becomes **3** for access tokens. Refresh tokens are unchanged (no perms).
- Gateway forwards codes as header **`x-authenticated-permissions`** (comma-separated; empty/absent when none). This header matches the existing `x-authenticated-` prefix that `isClientSpoofableHeader` already strips from inbound requests — a client cannot spoof it (verify, do not re-implement).
- operations guard `TenantScope.requirePermission(code)`: **superadmin bypasses**; permission set **non-empty and lacks the code → 403** "You do not have permission to approve firefighting requests"; permission set **empty (header absent) → allow** (transitional fallback; the internal service token + tenant scope still apply).
- Scope: guard only `approve-bursar`, `approve-principal`, `reject`. `approve-custoking` keeps `requireSuperAdmin()`. Create / read / submit / fulfill / vendor-paid unchanged.
- Backend gets tests (TDD). Gateway gets `node --test` tests. No FE tests (repo convention) — verify with `npm run build`.
- Do NOT commit `.claude/settings.local.json`.
- Backend build/test (Windows Bash tool): `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/<svc>/pom.xml -q -Dtest=<T> test`. Gateway: `cd services/api-gateway && node --test server.test.js`. Frontend: `cd frontend && npm run build`.

---

### Task 1: identity-service — permission codes in the access token

**Files:**
- Modify: `services/identity-service/src/main/java/com/custoking/ims/identityservice/security/JwtService.java`
- Modify: `services/identity-service/src/main/java/com/custoking/ims/identityservice/application/IdentityAuthService.java:132-146` (`issueSession`)
- Test: `services/identity-service/src/test/java/com/custoking/ims/identityservice/security/JwtServiceTest.java` (create or extend if it exists)

**Interfaces:**
- Consumes: `AuthenticatedUserSnapshot` (id/email/role/branchId/zoneId), `rbac.permissionCodes(userId): Collection<String>`.
- Produces: access token with claim `perms` (String[]) and `ver == 3`; consumed by the gateway (Task 2).

- [ ] **Step 1: Write the failing test**

Create/extend `JwtServiceTest`. Construct `JwtService` with a ≥32-char secret and assert the access token carries `perms` and `ver == 3`:
```java
@Test
void accessTokenCarriesPermissionsAndVer3() {
    JwtService jwt = new JwtService("0123456789012345678901234567890123456789", 900000, 604800000);
    AuthenticatedUserSnapshot user = new AuthenticatedUserSnapshot(
            7L, "Ann", "ann@x", "ADMIN", 10L, "Br", null, null);
    String token = jwt.generateAccessToken(user, java.util.List.of("firefighting:approve", "firefighting:read"));
    io.jsonwebtoken.Claims claims = jwt.claims(token);
    assertEquals(3, ((Number) claims.get("ver")).intValue());
    assertEquals(java.util.List.of("firefighting:approve", "firefighting:read"),
            claims.get("perms", java.util.List.class));
    assertEquals(7, ((Number) claims.get("uid")).intValue());
}
```
(Adjust the `AuthenticatedUserSnapshot` constructor arg order/count to the real record — read it first; the point is a snapshot for user 7 with role ADMIN.)

- [ ] **Step 2: Run — verify failure**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/identity-service/pom.xml -q -Dtest=JwtServiceTest test`
Expected: FAIL — `generateAccessToken(snapshot, List)` overload does not exist / `perms` null / `ver` is 2.
Requires `APP_JWT_SECRET`/`APP_AADHAR_SECRET` env only for full app boot — this unit test constructs `JwtService` directly, so no env needed.

- [ ] **Step 3: Add the `perms` claim + ver 3 to JwtService**

Add an overload that accepts the permission codes (keep the existing single-arg method delegating with an empty list so other callers/tests still compile):
```java
public String generateAccessToken(AuthenticatedUserSnapshot user) {
    return generateAccessToken(user, java.util.List.of());
}

public String generateAccessToken(AuthenticatedUserSnapshot user, java.util.List<String> permissions) {
    Map<String, Object> claims = new HashMap<>();
    claims.put("role", user.role());
    claims.put("uid", user.id());
    if (user.branchId() != null) {
        claims.put("sid", user.branchId());
    }
    if (user.zoneId() != null) {
        claims.put("zid", user.zoneId());
    }
    claims.put("perms", permissions == null ? java.util.List.of() : permissions);
    claims.put("ver", 3);
    return token(user.email(), claims, expirationMs);
}
```
(This bumps `ver` to 3 for ALL access tokens, including the empty-perms path — intended; the gateway defaults absent `perms` to `[]`.)

- [ ] **Step 4: Thread permissions through `issueSession`**

In `IdentityAuthService.issueSession` (line ~132), load the user's permission codes once and pass them into the token so BOTH login (line 60) and refresh (line 102) — which both call `issueSession` — mint ver-3 tokens with `perms`:
```java
private LoginResult issueSession(AppUserEntity user, String familyId) {
    AuthenticatedUserSnapshot snapshot = snapshot(user);
    java.util.List<String> permissions = new java.util.ArrayList<>(rbac.permissionCodes(user.getId()));
    permissions.sort(String::compareTo);
    String accessToken = jwtService.generateAccessToken(snapshot, permissions);
    String refreshToken = jwtService.generateRefreshToken(snapshot);
    // ... rest unchanged ...
}
```
(`responseFor` still computes its own sorted list for the response body — leave it; do not attempt to share the list across methods.)

- [ ] **Step 5: Run — GREEN + full identity suite**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/identity-service/pom.xml -q test`
Expected: BUILD SUCCESS (the new test passes; existing token/introspection tests still pass — `ver` moving 2→3 must not break any assertion; if an existing test asserts `ver == 2`, update it to 3 as part of this task).

- [ ] **Step 6: Commit**

```bash
git add services/identity-service/src/main/java/com/custoking/ims/identityservice/security/JwtService.java \
        services/identity-service/src/main/java/com/custoking/ims/identityservice/application/IdentityAuthService.java \
        services/identity-service/src/test/java/com/custoking/ims/identityservice/security/JwtServiceTest.java
git commit -m "feat(identity): carry user permission codes in the access token (perms claim, ver 3)"
```

---

### Task 2: api-gateway — forward permissions as a trusted header

**Files:**
- Modify: `services/api-gateway/server.js` (`principalFromClaims` ~line 309; header injection in `proxyToUrl` ~line 386-392)
- Test: `services/api-gateway/server.test.js`

**Interfaces:**
- Consumes: verified JWT claims with `perms` (Task 1) and `ver >= 3`.
- Produces: upstream request header `x-authenticated-permissions` (comma-separated codes); consumed by operations (Task 3).

- [ ] **Step 1: Write the failing test**

In `server.test.js`, add tests (match the file's existing `node:test` style — read a nearby `principalFromClaims`/`proxyToUrl` test first):
```js
test('principalFromClaims exposes permissions for ver>=3', () => {
  const p = server.principalFromClaims({ ver: 3, uid: 7, sub: 'a@x', role: 'ADMIN', perms: ['firefighting:approve'] });
  assert.deepEqual(p.permissions, ['firefighting:approve']);
});
test('principalFromClaims defaults permissions to [] for ver 2', () => {
  const p = server.principalFromClaims({ ver: 2, uid: 7, sub: 'a@x', role: 'ADMIN' });
  assert.deepEqual(p.permissions, []);
});
```
If `principalFromClaims` is not already exported from `server.js`, add it to the module exports (there is an existing exports block near the bottom — `verifyJwtLocally` is exported at ~line 624; add `principalFromClaims` alongside).

- [ ] **Step 2: Run — verify failure**

Run: `cd services/api-gateway && node --test server.test.js`
Expected: FAIL — `principalFromClaims` returns no `permissions` field (undefined, not `[]`/the array).

- [ ] **Step 3: Add `permissions` to the principal**

In `principalFromClaims` (server.js ~line 309), add the field (array; default empty; only populated when the claim is a present array):
```js
function principalFromClaims(claims) {
  if (!claims || typeof claims.ver !== 'number' || claims.ver < 2) return null;
  return {
    userId: claims.uid ?? null,
    email: claims.sub ?? null,
    role: claims.role ?? null,
    branchId: claims.sid ?? null,
    zoneId: claims.zid ?? null,
    permissions: Array.isArray(claims.perms) ? claims.perms : [],
  };
}
```

- [ ] **Step 4: Inject the header in `proxyToUrl`**

In `proxyToUrl` (server.js ~line 386-392), alongside the existing `x-authenticated-*` assignments, add:
```js
    headers['x-authenticated-permissions'] =
      Array.isArray(principal.permissions) ? principal.permissions.join(',') : '';
```
(No change to `isClientSpoofableHeader` — its `x-authenticated-` prefix already strips any inbound `x-authenticated-permissions`, so the gateway's value is authoritative.)

- [ ] **Step 5: Add a header-propagation assertion**

If `server.test.js` has an existing test that inspects the outbound headers of a proxied request with a principal (search for `x-authenticated-role` in the test file), extend it (or add a sibling) to assert `x-authenticated-permissions` equals the joined codes for a ver-3 principal and `''` for a ver-2 principal. If no such harness exists, assert at minimum via `principalFromClaims` (Step 1) plus a direct check that a client-supplied `x-authenticated-permissions` is classified spoofable: `assert.equal(server.isClientSpoofableHeader('x-authenticated-permissions'), true);` (export `isClientSpoofableHeader` if needed).

- [ ] **Step 6: Run — GREEN**

Run: `cd services/api-gateway && node --test server.test.js`
Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add services/api-gateway/server.js services/api-gateway/server.test.js
git commit -m "feat(gateway): forward user permissions as x-authenticated-permissions header"
```

---

### Task 3: operations-service — carry permissions + guard approvals

**Files:**
- Modify: `services/operations-service/src/main/java/com/custoking/ims/operationsservice/security/TenantContext.java`
- Modify: `services/operations-service/src/main/java/com/custoking/ims/operationsservice/security/TenantContextFilter.java`
- Modify: `services/operations-service/src/main/java/com/custoking/ims/operationsservice/security/TenantScope.java`
- Modify: `services/operations-service/src/main/java/com/custoking/ims/operationsservice/api/FirefightingReadController.java:196-239` (approveBursar/approvePrincipal/reject)
- Test: `services/operations-service/src/test/java/com/custoking/ims/operationsservice/security/TenantScopeTest.java` (extend)

**Interfaces:**
- Consumes: header `x-authenticated-permissions` (Task 2).
- Produces: `TenantContext.permissions(): Set<String>`, `TenantContext.hasPermission(String)`, `TenantScope.requirePermission(String)`.

- [ ] **Step 1: Write the failing guard test**

Extend `TenantScopeTest` (mirror its existing `requireSuperAdmin` tests; it drives `TenantContext.set(...)`):
```java
@Test
void requirePermission_allowsWhenCodePresent() {
    TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null, java.util.Set.of("firefighting:approve")));
    assertDoesNotThrow(() -> TenantScope.requirePermission("firefighting:approve"));
}
@Test
void requirePermission_403WhenPresentSetLacksCode() {
    TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null, java.util.Set.of("firefighting:read")));
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> TenantScope.requirePermission("firefighting:approve"));
    assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
}
@Test
void requirePermission_allowsSuperadminRegardless() {
    TenantContext.set(new TenantContext(1L, "s@x", "SUPERADMIN", null, null, java.util.Set.of()));
    assertDoesNotThrow(() -> TenantScope.requirePermission("firefighting:approve"));
}
@Test
void requirePermission_allowsWhenSetEmpty_transitional() {
    TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null, java.util.Set.of()));
    assertDoesNotThrow(() -> TenantScope.requirePermission("firefighting:approve"));
}
```

- [ ] **Step 2: Run — verify failure**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/operations-service/pom.xml -q -Dtest=TenantScopeTest test`
Expected: FAIL — 6-arg `TenantContext` constructor and `TenantScope.requirePermission` do not exist.

- [ ] **Step 3: Add permissions to `TenantContext`**

Add a `Set<String> permissions` field. Keep the existing 5-arg constructor (delegates to empty perms) so the ~30 existing `new TenantContext(...)` call sites/tests still compile; add a 6-arg constructor:
```java
import java.util.Set;

private final Set<String> permissions;

public TenantContext(Long userId, String email, String role, Long schoolId, Long zoneId) {
    this(userId, email, role, schoolId, zoneId, Set.of());
}

public TenantContext(Long userId, String email, String role, Long schoolId, Long zoneId, Set<String> permissions) {
    this.userId = userId;
    this.email = email;
    this.role = role;
    this.schoolId = schoolId;
    this.zoneId = zoneId;
    this.permissions = (permissions == null) ? Set.of() : Set.copyOf(permissions);
}

public Set<String> permissions() { return permissions; }
public boolean hasPermission(String code) { return permissions.contains(code); }
```
(The `get()` fallback uses the 5-arg constructor → empty perms. Leave it.)

- [ ] **Step 4: Parse the header in `TenantContextFilter`**

In `TenantContextFilter.doFilterInternal`, parse `x-authenticated-permissions` (CSV) into a Set and use the 6-arg constructor:
```java
TenantContext.set(new TenantContext(
        parseLong(request.getHeader("X-Authenticated-User-Id")),
        trimToNull(request.getHeader("X-Authenticated-Email")),
        trimToNull(request.getHeader("X-Authenticated-Role")),
        parseLong(request.getHeader("X-Authenticated-School-Id")),
        parseLong(request.getHeader("X-Authenticated-Zone-Id")),
        parsePermissions(request.getHeader("X-Authenticated-Permissions"))));
```
Add the helper:
```java
private static java.util.Set<String> parsePermissions(String header) {
    if (!StringUtils.hasText(header)) return java.util.Set.of();
    java.util.Set<String> out = new java.util.HashSet<>();
    for (String part : header.split(",")) {
        String code = part.trim();
        if (!code.isEmpty()) out.add(code);
    }
    return out;
}
```

- [ ] **Step 5: Add `requirePermission` to `TenantScope`**

```java
public static void requirePermission(String code) {
    TenantContext ctx = TenantContext.get();
    if (ctx.isSuperAdmin()) return;                       // superadmin bypass
    if (ctx.permissions().isEmpty()) return;              // transitional: pre-ver-3 token, no header
    if (!ctx.hasPermission(code)) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "You do not have permission to approve firefighting requests");
    }
}
```

- [ ] **Step 6: Guard the three approval endpoints**

In `FirefightingReadController`, add `TenantScope.requirePermission("firefighting:approve");` immediately after the existing `requireToken(...)` line in `approveBursar` (~:201), `approvePrincipal` (~:212), and `reject` (~:233). Do NOT touch `approveCustoking` (keeps `requireSuperAdmin()`), create, submit, quotations, fulfill, or vendor-paid. (Confirm `TenantScope` is imported in the controller; `TenantContext` already is.)

- [ ] **Step 7: Run — GREEN + full operations suite**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/operations-service/pom.xml -q test`
Expected: BUILD SUCCESS. Existing `FirefightingReadControllerTest` approval tests: if any drive an approve/reject endpoint as a non-superadmin with NO permissions header, they hit the transitional empty-set allow path → still pass. If any set a non-empty permission set lacking `firefighting:approve`, update them to include it. Verify no approval test now unexpectedly 403s.

- [ ] **Step 8: Commit**

```bash
git add services/operations-service/src/main/java/com/custoking/ims/operationsservice/security/TenantContext.java \
        services/operations-service/src/main/java/com/custoking/ims/operationsservice/security/TenantContextFilter.java \
        services/operations-service/src/main/java/com/custoking/ims/operationsservice/security/TenantScope.java \
        services/operations-service/src/main/java/com/custoking/ims/operationsservice/api/FirefightingReadController.java \
        services/operations-service/src/test/java/com/custoking/ims/operationsservice/security/TenantScopeTest.java
git commit -m "feat(operations): enforce firefighting:approve on approval decisions (per-user permission from JWT header)"
```

---

### Task 4: frontend — align the Approvals gate to firefighting:approve

**Files:**
- Modify: `frontend/src/pages/workspace/panels/FirefightingApprovalsPanel.tsx:60`

**Interfaces:**
- Consumes: `usePermissions().can(code)` (permission codes from `/auth/me`).

- [ ] **Step 1: Switch the gate code**

In `FirefightingApprovalsPanel.tsx`, change the permission the panel gates on from the ad-hoc `firefighting:write` to the catalog code `firefighting:approve` (the value of `FIREFIGHTING_APPROVE`), so the FE gate matches the new backend guard. Rename the variable for clarity and update its four usages (lines ~228, 234, 237, 240):
```tsx
// line 60
const canApprove = can('firefighting:approve');
```
Then replace each `canWrite` at lines ~228/234/237/240 with `canApprove`. No other logic changes; the custoking button stays `canApprove && isSuperAdmin && req.status === 'APPROVED'`.

- [ ] **Step 2: Build**

Run: `cd frontend && npm run build`
Expected: clean (no TS errors; no remaining `canWrite` reference).

- [ ] **Step 3: Commit**

```bash
git add frontend/src/pages/workspace/panels/FirefightingApprovalsPanel.tsx
git commit -m "fix(fe): gate firefighting approvals on firefighting:approve (matches backend)"
```

---

## Self-Review

**Spec coverage:** Part 1 (identity perms claim + ver 3, login + refresh) → Task 1. Part 2 (gateway principal.permissions + header + spoof-strip) → Task 2. Part 3 (TenantContext permissions, filter parse, requirePermission superadmin/empty/absent semantics, guard the three endpoints) → Task 3. Part 4 (FE gate on firefighting:approve) → Task 4. Error table (403 present-but-lacking / allow empty / superadmin / custoking superadmin) → Task 3 Steps 1/5/6. Deferred (bursar≠principal SoD) explicitly NOT built.

**Placeholder scan:** no TBD/TODO. "read the real record first" for `AuthenticatedUserSnapshot` arg order is a read-first instruction (the test's intent — user 7 / role ADMIN / with perms — is fully specified), not a gap. "extend the existing outbound-header test if present" names the concrete fallback assertions when it is not.

**Type/behavior consistency:** claim `perms` (List<String>) set in Task 1, read as `claims.perms` array in Task 2, joined to CSV header in Task 2, split back to `Set<String>` in Task 3 — consistent. `ver == 3` set in Task 1, gated `ver >= 2` (permissions default `[]` for ver 2) in Task 2 — consistent (ver-2 tokens carry no perms → empty header → operations transitional-allow). Code string `firefighting:approve` identical across Task 3 guard, Task 3 controller, and Task 4 FE gate. `TenantContext` 6-arg constructor signature identical between Task 3 Step 3 (definition), Step 4 (filter call), and Step 1 (test). `requirePermission(String)` signature identical between Step 5 (definition) and Steps 1/6 (callers).
