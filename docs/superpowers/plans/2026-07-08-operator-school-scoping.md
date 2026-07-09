# Operator School-Scoping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`).

**Goal:** Let a superadmin assign a specific set of schools to an `OPERATIONS` user, and restrict that operator's cross-school catalog access (reads + mark-delivered) to exactly that set — instead of today's unrestricted all-schools access.

**Architecture:** identity mints an `ops_schools` claim (the operator's assigned school ids) → gateway forwards `x-authenticated-operator-schools` → school-core `TenantContext` carries the set; the catalog RLS policy gains an `= ANY(operator set)` clause and `allowCrossSchoolReadForOperations()` sets an `app.operator_schools` GUC (instead of full bypass), so RLS bounds the operator to their set. Superadmin behavior is unchanged (true all-schools). A superadmin UI assigns the set (reusing the existing `OPERATIONS` school-scoped `user_role_assignments`).

**Tech Stack:** Spring Boot 4.0.7 / Java 25 (identity, school-core), Node http (gateway), React 18/Vite/TS (frontend), Postgres RLS.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-08-operator-school-scoping-design.md`.
- Operator = existing `OPERATIONS` role. Assigned set = distinct `school_id` from the user's active `OPERATIONS` `user_role_assignments` rows.
- Claim name `ops_schools` (JSON array of school-id numbers); header `x-authenticated-operator-schools` (CSV; part of the already-spoof-stripped `x-authenticated-` set). Keep token `ver=3` (additive claim).
- Enforcement is school-core catalog only (that's the sole place `isOperations()`/`resolvePlatformReadScope` grants cross-school access today). Superadmin unchanged. An operator with an empty set sees/acts on NO schools (fail closed).
- Do NOT fix the `permissionCodes()` union (separate follow-up; harmless for a same-role-everywhere operator).
- Backend/gateway TDD; FE verified via `npm run build`. Do not commit local tool settings.
- Build/test: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/<svc>/pom.xml -q -Dtest=<T> test`; gateway `cd services/api-gateway && node --test server.test.js`; FE `cd frontend && npm run build`.

---

### Task 1: identity-service — ops_schools claim + operator-schools assignment endpoints

**Files:**
- Modify: `.../identityservice/security/JwtService.java` (add `ops_schools` claim)
- Modify: `.../identityservice/application/IdentityAuthService.java` (`issueSession` — resolve the operator set for OPERATIONS users)
- Modify: `.../identityservice/api/RbacReadController.java` + `.../persistence/RbacCommandRepository.java` / `RbacReadRepository.java` (sync + read endpoints)
- Test: JwtService test + an operator-schools controller test.

**Interfaces:**
- Produces: access token with `ops_schools` (List<Long>); endpoints `POST /api/v1/rbac/users/{userId}/operator-schools {schoolIds:[...]}` and `GET /api/v1/rbac/users/{userId}/operator-schools`.

- [ ] **Step 1: Write failing tests**
  - JwtService: `generateAccessToken(snapshot, perms, opsSchools)` (new overload) puts `ops_schools` = the list and keeps `ver=3`; the existing 2-arg overload delegates with `List.of()`.
  - Controller: `POST /operator-schools` as non-superadmin → 403; as superadmin → repository sync invoked; `GET /operator-schools` as superadmin → returns the set. Mirror `RbacAuthorizationTest` (MockMvc + `X-Identity-Service-Token` + `X-Authenticated-Role`).

- [ ] **Step 2: Run — verify failure** (`-Dtest='JwtServiceTest,*OperatorSchools*,Rbac*'`). Expected: RED (overload + endpoints absent).

- [ ] **Step 3: Add the `ops_schools` claim**
  In `JwtService`, add `generateAccessToken(AuthenticatedUserSnapshot user, List<String> permissions, List<Long> opsSchools)` — same as the existing 3-claim overload plus `claims.put("ops_schools", opsSchools == null ? List.of() : opsSchools);`. Keep the 2-arg overload delegating with an empty ops list. In `IdentityAuthService.issueSession`, after resolving permissions, resolve `List<Long> opsSchools = "OPERATIONS".equalsIgnoreCase(user.getRole()) ? rbac.operatorSchoolIds(user.getId()) : List.of();` and pass it. Add `RbacReadRepository.operatorSchoolIds(userId)` = `SELECT DISTINCT school_id FROM identity.user_role_assignments WHERE user_id=:id AND active AND school_id IS NOT NULL AND upper(role_name/role)= 'OPERATIONS'` — READ the existing `user_role_assignments` schema + how role is stored (role_id join vs role string) in `RbacReadRepository`/`RbacCommandRepository` first and match it.

- [ ] **Step 4: Add the operator-schools endpoints**
  `POST /api/v1/rbac/users/{userId}/operator-schools` (superadmin-gated: `requireToken` + `requireSuperAdmin`): body `{schoolIds:[...]}`; in one transaction, sync the user's active OPERATIONS school assignments to the given set — add missing via the existing `assignScopedRole(userId, "OPERATIONS", schoolId, ...)`, revoke the rows for removed schools (reuse the existing revoke path). `GET /api/v1/rbac/users/{userId}/operator-schools` (superadmin-gated): return `[{schoolId, schoolName}]` — resolve names via identity's existing `TenantSchoolClient` (READ it; it already fetches school data). If name resolution is awkward, return `{schoolId}` only and note it (FE can hydrate) — but prefer names via TenantSchoolClient.

- [ ] **Step 5: Run — GREEN + full identity suite** (`-q test`). Fix any existing JwtService/token test that asserts the exact claim set to include `ops_schools` (empty for non-operators).

- [ ] **Step 6: Commit** — `feat(identity): ops_schools access-token claim + superadmin operator-schools assignment endpoints`

---

### Task 2: api-gateway — propagate operator schools

**Files:** Modify `services/api-gateway/server.js` (`principalFromClaims`, `proxyToUrl`); Test `server.test.js`.

- [ ] **Step 1: Failing test** — `principalFromClaims({ver:3, ops_schools:[2,3]})` → `principal.operatorSchools` deep-equals `[2,3]`; absent → `[]`. And the outbound header assertion / spoof-strip check for `x-authenticated-operator-schools`.
- [ ] **Step 2: Run** (`node --test server.test.js`) — RED.
- [ ] **Step 3: Implement** — in `principalFromClaims` add `operatorSchools: Array.isArray(claims.ops_schools) ? claims.ops_schools : []`; in `proxyToUrl` add `headers['x-authenticated-operator-schools'] = Array.isArray(principal.operatorSchools) ? principal.operatorSchools.join(',') : '';` (inside the `if (principal)` block). `isClientSpoofableHeader` already covers the `x-authenticated-` prefix — do not change it.
- [ ] **Step 4: Run — GREEN.**
- [ ] **Step 5: Commit** — `feat(gateway): forward operator schools as x-authenticated-operator-schools header`

---

### Task 3: school-core-service — enforce the operator set (TenantContext + catalog RLS)

**Files:**
- Modify: `.../schoolcoreservice/security/{TenantContext,TenantContextFilter,TenantScope}.java`
- Modify: `.../schoolcoreservice/persistence/CatalogReadRepository.java` (`allowCrossSchoolReadForOperations`)
- Modify: `.../schoolcoreservice/api/compat/CatalogPublicCompatibilityController.java` (mark-delivered guard) — and the repo mark-delivered path
- Create: `services/school-core-service/src/main/resources/db/migration/catalog/V7__operator_scope.sql`
- Test: extend `TenantScope`/catalog RLS integration tests.

**Interfaces:**
- Consumes: `x-authenticated-operator-schools` (Task 2).
- Produces: `TenantContext.operatorSchools(): Set<Long>`; catalog reads/writes RLS-bounded to that set for operations callers.

- [ ] **Step 1: Failing tests**
  - `TenantScopeTest`: an operations context (role OPERATIONS, operatorSchools={10,20}) → `resolvePlatformReadScope(10)` returns 10; `resolvePlatformReadScope(30)` → **403**; `resolvePlatformReadScope(null)` returns null (bounded by RLS). Superadmin unchanged.
  - A `CatalogRlsIntegrationTest`-style test (mirror the existing one): with `app.operator_schools = '10,20'` set (no bypass) under an OPERATIONS `app_rt` context, a `SELECT` from `catalog.catalog_orders` returns rows for schools 10 and 20 but NOT 30; and an UPDATE (mark delivered) of a school-30 order is blocked. Seed 3 schools' orders.

- [ ] **Step 2: Run — RED** (`-Dtest='TenantScopeTest,*CatalogRls*'`).

- [ ] **Step 3: TenantContext + filter carry the set**
  Add `Set<Long> operatorSchools` to `TenantContext` (keep existing constructors working — add an overload or a wither, defensive `Set.copyOf`, never null → empty). In `TenantContextFilter`, parse `X-Authenticated-Operator-Schools` (CSV → `Set<Long>`, drop blanks) and populate it. Add `TenantContext.operatorSchools()` accessor.

- [ ] **Step 4: Enforce in TenantScope + the GUC**
  In `TenantScope.resolvePlatformReadScope(requested)`, for an operations caller (`ctx.isOperations()`): if `requested != null && !ctx.operatorSchools().contains(requested)` → `throw ResponseStatusException(FORBIDDEN, "school not in operator scope")`; otherwise return `requested` (unchanged for superadmin). In `CatalogReadRepository.allowCrossSchoolReadForOperations()`, replace the `set_config('app.bypass_rls','on', true)` with setting the operator set: `SELECT set_config('app.operator_schools', :csv, true)` where `:csv` = `String.join(",", operatorSchools)` (empty string when the set is empty → RLS matches nothing → fail closed). Do NOT set `bypass_rls` for operations anymore.

- [ ] **Step 5: Extend the catalog RLS policy (migration V7)**
  Create `catalog/V7__operator_scope.sql` recreating the `catalog_orders` AND `annual_plan_items` `tenant_isolation` policies (DROP + CREATE) to add a third disjunct on BOTH `USING` and `WITH CHECK`:
  ```sql
  DROP POLICY IF EXISTS tenant_isolation ON catalog.catalog_orders;
  CREATE POLICY tenant_isolation ON catalog.catalog_orders
    USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
                OR current_setting('app.bypass_rls', true) = 'on'
                OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]))
    WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
                OR current_setting('app.bypass_rls', true) = 'on'
                OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]));
  ```
  (Same for `annual_plan_items`.) The `nullif(...,'')` guard makes an unset/empty `app.operator_schools` yield `ANY(NULL)` → false, so non-operator sessions and empty-set operators are unaffected/fail-closed.

- [ ] **Step 6: Bound the mark-delivered write**
  In the mark-order-delivered path (`CatalogPublicCompatibilityController` ~:139 guard + the repo update), for an operations caller add an explicit check that the target order's `school_id ∈ operatorSchools` → else `403 "school not in operator scope"` (belt-and-suspenders with the RLS `WITH CHECK`). Superadmin unchanged.

- [ ] **Step 7: Run — GREEN + full school-core suite** (`-q test`). Existing operations catalog tests that relied on the old full-bypass (seeing ALL schools) must be updated to set an `operatorSchools` set (or superadmin) — the operator is now scoped; update those tests to the new semantics (do not restore full bypass).

- [ ] **Step 8: Commit** — `feat(school-core): bound OPERATIONS catalog access to the assigned operator school set (RLS operator_schools + guards)`

---

### Task 4: frontend — superadmin assign-schools-to-operator UI

**Files:** Modify `frontend/src/pages/SchoolManagementPage.tsx` (or a small operators admin panel) + api calls.

- [ ] **Step 1: Add the assign UI** — for a superadmin, a multi-select of schools bound to an operator user, calling `GET/POST /api/v1/rbac/users/{userId}/operator-schools`. Surface it alongside the existing "Add / Reset operations user" flow (which creates the OPERATIONS user); after creating an operations user, allow assigning their schools. Reuse existing form/modal styles; superadmin-gated in the UI (these endpoints are superadmin-only server-side).
- [ ] **Step 2: Build** — `cd frontend && npm run build` clean.
- [ ] **Step 3: Commit** — `feat(fe): superadmin assigns schools to an operator (operator-schools UI)`

---

## Self-Review

**Spec coverage:** assignment (endpoints + FE) → Tasks 1/4; propagation (claim → header → context) → Tasks 1/2/3; enforcement (resolvePlatformReadScope 403 + operator_schools GUC + catalog RLS clause + mark-delivered guard + empty-set fail-closed) → Task 3. Permission-union deferral noted. **Placeholder scan:** "READ the assignment schema/TenantSchoolClient first" are read-first instructions at named files; the RLS policy SQL + GUC + claim/header names are given in full. **Consistency:** `ops_schools` (claim) → `x-authenticated-operator-schools` (header) → `operatorSchools` (Set<Long>) → `app.operator_schools` (GUC csv) consistent across tasks; `OPERATIONS` role + "school not in operator scope" 403 message consistent; catalog policy disjunct identical between USING/WITH CHECK and both tables.
