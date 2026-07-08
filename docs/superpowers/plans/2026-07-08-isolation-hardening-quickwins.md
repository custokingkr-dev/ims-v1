# Isolation Audit Hardening — Quick Wins Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close four concrete gaps from the tenant-isolation audit — a cross-school attendance KPI leak, a notification-status IDOR, two cross-school RBAC read endpoints, and a hardcoded invoice-ID year.

**Architecture:** Small, independent fixes. A/D are correctness (add a `school_id` predicate; derive the year). B/C are authorization (`requireSuperAdmin()` on endpoints that authenticated only the gateway-injected service token). No schema, no new plumbing.

**Tech Stack:** Spring Boot 4.0.7 / Java 25 (platform-service, identity-service, billing-service).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-08-isolation-hardening-quickwins-design.md`.
- B/C use the EXISTING per-service `TenantScope.requireSuperAdmin()` (throws 403 "superadmin required" when `TenantContext.get().isSuperAdmin()` is false; role from `X-Authenticated-Role`, already populated by each service's `TenantContextFilter`). No new plumbing.
- Do NOT touch the hardcoded `academic_year_id = 'ay_2025_26'` (separate deferred follow-up). Do NOT gate the RBAC reference reads (`/rbac/roles`, `/rbac/permissions`, `/rbac/role-permissions`) or the per-user reads.
- Backend TDD. Do NOT commit `.claude/settings.local.json`.
- Build/test (Windows Bash tool): `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/<svc>/pom.xml -q -Dtest=<T> test`.

---

### Task 1: platform-service — attendance KPI school scope + notification-status superadmin gate

**Files:**
- Modify: `services/platform-service/src/main/java/com/custoking/ims/platformservice/persistence/ReportingReadRepository.java` (the `attendanceSections` count in `commandCenterSummary`)
- Modify: `services/platform-service/src/main/java/com/custoking/ims/platformservice/api/NotificationStatusController.java` (`getStatus`)
- Test: extend/add `ReportingReadRepository` (or command-center) test + `NotificationStatusController` test.

- [ ] **Step 1: Write the failing tests**

(1) A test proving the per-school attendance count is school-scoped: seed `reporting.fact_attendance_daily` rows for `CURRENT_DATE` under **two** schools, call the non-platform `commandCenterSummary(schoolIdA, false)`, and assert the `attendance_today` KPI value counts only school A's sections (not both). Mirror how existing `ReportingReadRepository`/`ReportingFactReadIntegrationTest`-style tests seed facts and read the summary (read one first for the seeding/DB harness). If a command-center summary test already exists, extend it with a second school.
(2) A `NotificationStatusController` test: a non-superadmin caller (valid `X-Notification-Service-Token` + `X-Authenticated-Role: ADMIN`) → **403**; a superadmin caller (`X-Authenticated-Role: SUPERADMIN`) → reaches the mocked inbox repository (returns the status). Mirror the existing platform controller test style (MockMvc through `TenantContextFilter`, or direct call with `TenantContext.set(...)` if that's the file's style).

- [ ] **Step 2: Run — verify failure**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/platform-service/pom.xml -q -Dtest='*NotificationStatus*,*CommandCenter*,*ReportingReadRepository*' test`
Expected: the attendance-scope test FAILS (count includes both schools) and the notification non-superadmin→403 test FAILS (currently returns 200).

- [ ] **Step 3: Add the `school_id` predicate (A)**

In `ReportingReadRepository.commandCenterSummary`, the `attendanceSections` count query currently reads:
```sql
SELECT count(*)
FROM reporting.fact_attendance_daily
WHERE attendance_date = CURRENT_DATE
  AND academic_year_id = 'ay_2025_26'
```
Add the school predicate (leave the `academic_year_id` line exactly as-is) and pass `schoolId`:
```sql
SELECT count(*)
FROM reporting.fact_attendance_daily
WHERE attendance_date = CURRENT_DATE
  AND academic_year_id = 'ay_2025_26'
  AND school_id = :schoolId
```
Use the same `count(""" ... """, schoolId)` call form the sibling queries in this method use (they pass `schoolId` as the bound param).

- [ ] **Step 4: Gate the notification-status endpoint (B)**

In `NotificationStatusController.getStatus`, add `TenantScope.requireSuperAdmin();` immediately after the existing `requireValidToken(token, "notification:status:read");` line. Add `import com.custoking.ims.platformservice.security.TenantScope;` (verify the exact package by reading `services/platform-service/.../security/TenantScope.java`).

- [ ] **Step 5: Run — GREEN + full platform suite**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/platform-service/pom.xml -q test`
Expected: BUILD SUCCESS. If any existing test drove `getStatus` as a non-superadmin to assert the response body, update it to `X-Authenticated-Role: SUPERADMIN` (it tested mapping, not auth).

- [ ] **Step 6: Commit**

```bash
git add services/platform-service/src/main/java/com/custoking/ims/platformservice/persistence/ReportingReadRepository.java \
        services/platform-service/src/main/java/com/custoking/ims/platformservice/api/NotificationStatusController.java \
        services/platform-service/src/test/java/com/custoking/ims/platformservice/
git commit -m "fix(platform): scope attendance KPI by school; superadmin-gate notification status endpoint"
```

---

### Task 2: identity-service — gate the two cross-school RBAC read endpoints

**Files:**
- Modify: `services/identity-service/src/main/java/com/custoking/ims/identityservice/api/RbacReadController.java` (`userRoleAssignments` at `GET /rbac/user-role-assignments` ~line 68; `audit` at `GET /rbac/audit` ~line 100)
- Test: add/extend an `RbacReadController` authorization test.

- [ ] **Step 1: Write the failing test**

Add assertions: a non-superadmin caller (valid `X-Identity-Service-Token` + `X-Authenticated-Role: ADMIN`) → **403** on both `GET /api/v1/rbac/user-role-assignments` and `GET /api/v1/rbac/audit`; a superadmin (`X-Authenticated-Role: SUPERADMIN`) → reaches the mocked repository. Mirror the existing `RbacAuthorizationTest`/`RbacValidationTest` MockMvc + header setup (read one first). Also assert an untouched read (e.g. `GET /api/v1/rbac/roles`) is NOT gated — a non-superadmin still reaches it — to prove the guard was applied narrowly.

- [ ] **Step 2: Run — verify failure**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/identity-service/pom.xml -q -Dtest='Rbac*' test`
Expected: the two non-superadmin→403 assertions FAIL (endpoints currently return 200 for any caller).

- [ ] **Step 3: Gate the two reads**

In `RbacReadController.java`, add `TenantScope.requireSuperAdmin();` immediately after the `requireToken(token, "identity:read");` line in `userRoleAssignments` and `audit` ONLY. Do NOT touch `roles`, `permissions`, `rolePermissions`, `usersRoles`, or `usersPermissions`. (`TenantScope` is imported from Task-1-era work / verify the import exists — `import com.custoking.ims.identityservice.security.TenantScope;`.)

- [ ] **Step 4: Run — GREEN + full identity suite**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/identity-service/pom.xml -q test`
Expected: BUILD SUCCESS. Update any existing test that drove these two reads as a non-superadmin.

- [ ] **Step 5: Commit**

```bash
git add services/identity-service/src/main/java/com/custoking/ims/identityservice/api/RbacReadController.java \
        services/identity-service/src/test/java/com/custoking/ims/identityservice/api/
git commit -m "fix(identity): superadmin-gate cross-school RBAC reads (user-role-assignments, audit)"
```

---

### Task 3: billing-service — derive invoice-ID year dynamically

**Files:**
- Modify: `services/billing-service/src/main/java/com/custoking/ims/billingservice/persistence/BillingInvoiceRepository.java` (`allocateInvoiceId`)
- Test: add/extend a `BillingInvoiceRepository` test.

- [ ] **Step 1: Write the failing test**

Add/extend a test asserting the minted invoice id starts with the CURRENT year, computed (not hardcoded): e.g. `assertTrue(id.startsWith("INV-" + java.time.Year.now().getValue() + "-0"));`. If an existing test asserts a literal `"INV-2025..."`, this new/updated assertion will fail against the current hardcoded code (unless the current year happens to be 2025 — regardless, write the assertion to compute the year so it's correct going forward, and if it already passes because the clock is 2025, also assert the code no longer contains a hardcoded literal by reasoning about the diff in review).

- [ ] **Step 2: Run — verify current behavior**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/billing-service/pom.xml -q -Dtest='*BillingInvoice*' test`
Expected: passes only while the clock year is 2025; the point of the change is to remove the hardcoded literal.

- [ ] **Step 3: Derive the year**

In `allocateInvoiceId`, change the return from:
```java
return "INV-2025-0" + next;
```
to:
```java
return "INV-" + java.time.Year.now().getValue() + "-0" + next;
```

- [ ] **Step 4: Run — GREEN + full billing suite**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/billing-service/pom.xml -q test`
Expected: BUILD SUCCESS. Update any test that asserted the literal `INV-2025` to compute the year.

- [ ] **Step 5: Commit**

```bash
git add services/billing-service/src/main/java/com/custoking/ims/billingservice/persistence/BillingInvoiceRepository.java \
        services/billing-service/src/test/java/com/custoking/ims/billingservice/
git commit -m "fix(billing): derive invoice-id year from the current date (was hardcoded 2025)"
```

---

## Self-Review

**Spec coverage:** A (attendance school_id) → Task 1 Step 3. B (notification IDOR) → Task 1 Step 4. C (RBAC reads) → Task 2 Step 3. D (invoice year) → Task 3 Step 3. All four spec items covered; deferred items (RLS backstops, academic_year hardcoding, Operator) not built.

**Placeholder scan:** no TBD/TODO. "read the existing test first" points at named files (`ReportingFactReadIntegrationTest`, `RbacAuthorizationTest`, existing billing tests) — read-first instructions, not gaps. The exact SQL, the exact guard line, and the exact return expression are given in full.

**Consistency:** `requireSuperAdmin()` (existing) used identically in Task 1 Step 4 and Task 2 Step 3, both after the endpoint's existing token check. `school_id = :schoolId` predicate (A) uses the `schoolId` param already bound in `commandCenterSummary`. Year expression (D) `java.time.Year.now().getValue()` consistent between Step 1 assertion and Step 3 implementation.
