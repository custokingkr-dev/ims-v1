# `TenantContext` Tenant-Scope Enforcement — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the live cross-tenant BOLA leak by deriving every tenant-scoped query's `school_id` from the gateway-verified authenticated context (not client params), denying cross-tenant access by default.

**Architecture:** A request-scoped `TenantContext` (populated by a `TenantContextFilter` cloned from the existing `RequestCorrelationFilter`) plus a `TenantScope.resolveSchoolId(requested)` rule applied at the controller boundary. Repositories keep their `schoolId` parameter but always receive the *resolved* value. Copied per-service (no shared module). RLS (Task 1.3) is the DB backstop.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Servlet `OncePerRequestFilter`, Mockito + standalone `MockMvc` tests, Maven (`./mvnw.cmd -f services/<svc>/pom.xml test`).

**Spec:** `docs/superpowers/specs/2026-06-29-tenant-context-design.md`

## Global Constraints

- Security invariant: for any **non-superadmin** request, the effective `school_id` of every tenant-scoped query **equals** the gateway header `X-Authenticated-School-Id`; a client `schoolId` may only narrow within that scope, never widen it.
- Superadmin signal: gateway-verified header `X-Authenticated-Role` equal to `SUPERADMIN` (case-insensitive) — the only role that may widen scope.
- Deny-by-default: non-superadmin with **no** authenticated school → `403` on tenant-scoped endpoints. Eliminate every `schoolId == null ⇒ all rows` path for non-superadmin.
- Headers (set by `services/api-gateway/server.js`): `X-Authenticated-User-Id`, `X-Authenticated-Email`, `X-Authenticated-Role`, `X-Authenticated-School-Id`, `X-Authenticated-Zone-Id`.
- Copy-per-service: place new files in package `com.custoking.ims.<service>.security`. No `services/common` module, no root pom — each service builds independently.
- Keep existing internal service-token checks (`X-<Service>-Service-Token`) intact — the tenant filter is additive.
- 403 is raised as `org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN, ...)`.
- Zone-admin multi-school scoping is **out of scope** (deferred): a non-superadmin with no authenticated school gets no cross-school access.
- System/internal calls (valid service token, **no** `X-Authenticated-*` headers) are trusted: they pass an explicit `schoolId` and must **not** be routed through `resolveSchoolId` (it would 403). Only end-user request paths call `resolveSchoolId`.
- Tenant-scoped services (full enforcement): `student, attendance, fee, catalog, workflow, firefighting, reporting`. Platform services (targeted guards): `identity, tenant-school, audit, billing, notification`.
- `mvn test` env for identity-service needs `APP_JWT_SECRET` (≥32 chars) + `APP_AADHAR_SECRET` (≥16 chars).

---

### Task 1: Canonical `TenantContext` + filter + resolver + wire student-service (pilot)

**Files:**
- Create: `services/student-service/src/main/java/com/custoking/ims/studentservice/security/TenantContext.java`
- Create: `services/student-service/src/main/java/com/custoking/ims/studentservice/security/TenantContextFilter.java`
- Create: `services/student-service/src/main/java/com/custoking/ims/studentservice/security/TenantScope.java`
- Modify: `services/student-service/src/main/java/com/custoking/ims/studentservice/api/StudentReadController.java`
- Test: `services/student-service/src/test/java/com/custoking/ims/studentservice/security/TenantScopeTest.java`
- Test: `services/student-service/src/test/java/com/custoking/ims/studentservice/api/StudentTenantScopingTest.java`

**Interfaces (produced — the canonical API the other tasks copy):**
- `TenantContext` (package-relative): static `set(TenantContext)`, `get()` (never null — returns an all-null context when unset), `clear()`; instance `userId()/email()/role()/schoolId()/zoneId()` (Longs/String), `isSuperAdmin()`, `isAuthenticated()`.
- `TenantContextFilter`: `@Component @Order(Ordered.HIGHEST_PRECEDENCE + 10) OncePerRequestFilter` — sets context from headers, clears in `finally`.
- `TenantScope`: static `Long resolveSchoolId(Long requested)` (superadmin → requested; else locks to authed school, 403 on mismatch/none); static `void requireSuperAdmin()`.

- [ ] **Step 1: Write the failing resolver unit test**

Create `TenantScopeTest.java`:

```java
package com.custoking.ims.studentservice.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class TenantScopeTest {

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void schoolUser_lockedToAuthenticatedSchool_whenNoneRequested() {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(10L, TenantScope.resolveSchoolId(null));
    }

    @Test
    void schoolUser_matchingRequestedSchool_allowed() {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(10L, TenantScope.resolveSchoolId(10L));
    }

    @Test
    void schoolUser_crossTenantRequest_forbidden() {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> TenantScope.resolveSchoolId(99L));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void nonSuperadmin_withoutSchool_forbidden() {
        TenantContext.set(new TenantContext(2L, "z@x", "ZONE_ADMIN", null, 5L));
        assertThrows(ResponseStatusException.class, () -> TenantScope.resolveSchoolId(10L));
    }

    @Test
    void superadmin_widensToRequested_orAll() {
        TenantContext.set(new TenantContext(3L, "s@x", "SUPERADMIN", null, null));
        assertEquals(77L, TenantScope.resolveSchoolId(77L));
        assertNull(TenantScope.resolveSchoolId(null));
    }

    @Test
    void emptyContext_isNotSuperadmin_andForbidsScopedAccess() {
        assertFalse(TenantContext.get().isSuperAdmin());
        assertThrows(ResponseStatusException.class, () -> TenantScope.resolveSchoolId(1L));
    }
}
```

- [ ] **Step 2: Run it to verify it fails (classes don't exist)**

Run: `./mvnw.cmd -q -f services/student-service/pom.xml test -Dtest=TenantScopeTest`
Expected: FAIL — compilation error, `TenantContext`/`TenantScope` not found.

- [ ] **Step 3: Create `TenantContext.java`**

```java
package com.custoking.ims.studentservice.security;

public final class TenantContext {

    private static final ThreadLocal<TenantContext> HOLDER = new ThreadLocal<>();

    private final Long userId;
    private final String email;
    private final String role;
    private final Long schoolId;
    private final Long zoneId;

    public TenantContext(Long userId, String email, String role, Long schoolId, Long zoneId) {
        this.userId = userId;
        this.email = email;
        this.role = role;
        this.schoolId = schoolId;
        this.zoneId = zoneId;
    }

    public static void set(TenantContext ctx) { HOLDER.set(ctx); }

    public static TenantContext get() {
        TenantContext ctx = HOLDER.get();
        return ctx != null ? ctx : new TenantContext(null, null, null, null, null);
    }

    public static void clear() { HOLDER.remove(); }

    public Long userId() { return userId; }
    public String email() { return email; }
    public String role() { return role; }
    public Long schoolId() { return schoolId; }
    public Long zoneId() { return zoneId; }

    public boolean isSuperAdmin() { return role != null && role.equalsIgnoreCase("SUPERADMIN"); }

    public boolean isAuthenticated() { return userId != null || (role != null && !role.isBlank()); }
}
```

- [ ] **Step 4: Create `TenantScope.java`**

```java
package com.custoking.ims.studentservice.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class TenantScope {

    private TenantScope() {}

    /**
     * Resolve the effective school id for the current request.
     * Superadmin may widen (returns {@code requested}, possibly null = all schools).
     * Otherwise the request is locked to the authenticated school; a cross-tenant
     * request (or absence of an authenticated school) is rejected with 403.
     */
    public static Long resolveSchoolId(Long requested) {
        TenantContext ctx = TenantContext.get();
        if (ctx.isSuperAdmin()) {
            return requested;
        }
        Long authed = ctx.schoolId();
        if (authed == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "no tenant scope for request");
        }
        if (requested != null && !requested.equals(authed)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "cross-tenant access denied");
        }
        return authed;
    }

    public static void requireSuperAdmin() {
        if (!TenantContext.get().isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "superadmin required");
        }
    }
}
```

- [ ] **Step 5: Create `TenantContextFilter.java`**

```java
package com.custoking.ims.studentservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        TenantContext.set(new TenantContext(
                parseLong(request.getHeader("X-Authenticated-User-Id")),
                trimToNull(request.getHeader("X-Authenticated-Email")),
                trimToNull(request.getHeader("X-Authenticated-Role")),
                parseLong(request.getHeader("X-Authenticated-School-Id")),
                parseLong(request.getHeader("X-Authenticated-Zone-Id"))));
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static Long parseLong(String value) {
        if (!StringUtils.hasText(value)) return null;
        try { return Long.parseLong(value.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
```

- [ ] **Step 6: Run the resolver test to verify it passes**

Run: `./mvnw.cmd -q -f services/student-service/pom.xml test -Dtest=TenantScopeTest`
Expected: PASS (6 tests).

- [ ] **Step 7: Wire `StudentReadController` to resolve scope**

Apply the **two transformation recipes** to every tenant-scoped endpoint.

Recipe A — `schoolId` from a request param/path: resolve before use.
```java
// before:  return new StudentListResponse(students.list(schoolId, classId, sectionId, limit), students.count(schoolId));
// after:
Long scope = TenantScope.resolveSchoolId(schoolId);
return new StudentListResponse(students.list(scope, classId, sectionId, limit), students.count(scope));
```

Recipe B — `schoolId` inside a `@RequestBody Map` consumed by the repo: extract, resolve, write the resolved value back into the map before delegating. Add this private helper to the controller:
```java
private void applyResolvedSchool(Map<String, Object> request) {
    Long requested = request.get("schoolId") == null ? null
            : Long.valueOf(String.valueOf(request.get("schoolId")));
    request.put("schoolId", TenantScope.resolveSchoolId(requested));
}
```

Add the import `import com.custoking.ims.studentservice.security.TenantScope;`.

Exact endpoints to change in `StudentReadController.java`:
- Recipe A (param): `list` (schoolId → scope for `list` + `count`, lines 47-49), `workspaceStudents` (line 71), `idCardReviewStatus` (line 169), `fullNameVerificationStatus` (line 185), `reviewCampaigns` (line 232), `reviewItems` (line 243), `campaignReviewItems` (line 257).
- Recipe B (body map): `create` (line 87), `previewImport` (line 104), `confirmImport` (line 119), `initiateIdCardReview` (line 161), `initiateFullNameVerification` (line 177) — call `applyResolvedSchool(request);` immediately after `requireToken(...)` and before the `execute(() -> students...)` call.
- Item-scoped mutations without `schoolId` in the body — `updateReviewItem` (line 188), `verifyFullName` (line 197): the review item's school is derived inside the repo. Guard by resolving the **item's** school: add a repo read `Long schoolIdForReviewItem(String itemId)` to `StudentReadRepository` (a `SELECT school_id FROM student.student_review_items WHERE id = :itemId`), then in the controller:
```java
Long itemSchool = students.schoolIdForReviewItem(itemId);
TenantScope.resolveSchoolId(itemSchool);   // throws 403 if the item is not in the caller's school
```
  immediately after `requireToken(...)`. (Superadmin passes through; a school user editing another school's item gets 403.)
- Leave `get`/`workspaceStudent`/`attachPhoto`/`importStatus`/`imports/batches`/`imports/rows`/`import/template` and the legacy pass-throughs unchanged for now — single-record reads by surrogate id are covered by the RLS backstop (1.3); note this in the report.

- [ ] **Step 8: Write the failing controller scoping test**

Create `StudentTenantScopingTest.java` (standalone MockMvc, mocked repo):

```java
package com.custoking.ims.studentservice.api;

import com.custoking.ims.studentservice.persistence.StudentReadRepository;
import com.custoking.ims.studentservice.security.TenantContext;
import com.custoking.ims.studentservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StudentTenantScopingTest {

    private final StudentReadRepository repo = mock(StudentReadRepository.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new StudentReadController(repo, "tok"))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void crossTenantSchoolId_isForbidden() throws Exception {
        mvc.perform(get("/api/v1/students?schoolId=99")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isForbidden());
        verify(repo, never()).list(anyLong(), any(), any(), anyInt());
    }

    @Test
    void omittedSchoolId_scopesToAuthenticatedSchool() throws Exception {
        when(repo.list(eq(10L), any(), any(), anyInt())).thenReturn(List.of());
        when(repo.count(10L)).thenReturn(0L);
        mvc.perform(get("/api/v1/students")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10"))
                .andExpect(status().isOk());
        verify(repo).list(eq(10L), any(), any(), anyInt());
        verify(repo).count(10L);
    }

    @Test
    void superadmin_canTargetAnySchool() throws Exception {
        when(repo.list(eq(99L), any(), any(), anyInt())).thenReturn(List.of());
        when(repo.count(99L)).thenReturn(0L);
        mvc.perform(get("/api/v1/students?schoolId=99")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "SUPERADMIN"))
                .andExpect(status().isOk());
        verify(repo).list(eq(99L), any(), any(), anyInt());
    }
}
```

- [ ] **Step 9: Run it; verify it fails, then passes after wiring**

Run: `./mvnw.cmd -q -f services/student-service/pom.xml test -Dtest=StudentTenantScopingTest`
Expected: FAIL before Step 7 wiring is complete (cross-tenant returns 200 / repo invoked), PASS after. Then run the whole service: `./mvnw.cmd -q -f services/student-service/pom.xml test` → all green.

- [ ] **Step 10: Commit**

```bash
git add services/student-service/src/main/java/com/custoking/ims/studentservice/security \
        services/student-service/src/main/java/com/custoking/ims/studentservice/api/StudentReadController.java \
        services/student-service/src/main/java/com/custoking/ims/studentservice/persistence/StudentReadRepository.java \
        services/student-service/src/test/java/com/custoking/ims/studentservice/security/TenantScopeTest.java \
        services/student-service/src/test/java/com/custoking/ims/studentservice/api/StudentTenantScopingTest.java
git commit -m "feat(student): enforce tenant scope from authenticated context (TenantContext)"
```

---

### Tasks 2–7: Replicate to the remaining tenant-scoped services

Each task below is self-contained and identical in shape: **(a)** copy the three canonical files, **(b)** apply Recipe A / Recipe B (from Task 1) to the enumerated call-sites, **(c)** add a `*TenantScopingTest` mirroring `StudentTenantScopingTest` for that service's primary list endpoint, **(d)** run `./mvnw.cmd -q -f services/<svc>/pom.xml test`, **(e)** commit.

**Copy step (every task):** copy `TenantContext.java`, `TenantContextFilter.java`, `TenantScope.java` from `services/student-service/.../security/` into `services/<svc>/src/main/java/com/custoking/ims/<svc-pkg>/security/`, and change the first line `package com.custoking.ims.studentservice.security;` to `package com.custoking.ims.<svc-pkg>.security;`. (The three files contain no other service-specific identifiers.) Import `com.custoking.ims.<svc-pkg>.security.TenantScope` in the edited controllers.

Service → package map: `attendance`→`attendanceservice`, `fee`→`feeservice`, `catalog`→`catalogservice`, `workflow`→`workflowservice`, `firefighting`→`firefightingservice`, `reporting`→`reportingservice`.

#### Task 2: attendance-service
**Files:** create `.../attendanceservice/security/{TenantContext,TenantContextFilter,TenantScope}.java`; modify `AttendanceReadController.java`; test `AttendanceTenantScopingTest.java`.
- Recipe A: `records()` (`AttendanceReadController.java:51`), `dailySummary()` (line 60), `sectionInfo()` (line 70), `sectionRegister()` (line 80) — resolve `schoolId` before calling the repo; remove reliance on the existing partial `sectionRegister` null-bypass (the resolved value is never null for non-superadmin).
- Recipe B: `submitDay()` (line 119) — `applyResolvedSchool(request)` after the token check.
- Test: tenant-A requesting school B on `GET /api/v1/attendance/records?schoolId=99` → 403; omitted → repo called with A; superadmin → any.
- Commit: `feat(attendance): enforce tenant scope from authenticated context`

#### Task 3: fee-service
**Files:** create `.../feeservice/security/...`; modify `FeeReadController.java`; test `FeeTenantScopingTest.java`.
- Recipe A: `feeReport()` (`FeeReadController.java:204`), `feeOverdue()` (line 210), `feesModule()` (line 237), `feeOverdueCount()` (line 243).
- Recipe B: `feeReminderRequests()` (line 229).
- Leave global fee-band catalog endpoints (`createBand`/`updateBand`) unchanged (not school-scoped) — note in report.
- Test: tenant-A requesting school B on the fee report endpoint → 403; omitted → A; superadmin → any.
- Commit: `feat(fee): enforce tenant scope from authenticated context`

#### Task 4: catalog-service
**Files:** create `.../catalogservice/security/...`; modify `CatalogReadController.java`; test `CatalogTenantScopingTest.java`.
- Recipe A: `orders()` (`CatalogReadController.java:59`), `orderStats()` (line 148), `annualPlanItems()` (line 165), `annualPlan()` (line 175).
- Recipe B: `createOrder()` (line 84), `markVendorPaid()` (line 140).
- Superadmin-only approval endpoints (`approveBySuperadmin`, `rejectBySuperadmin`, and `pendingApprovalOrders` which lists all schools): call `TenantScope.requireSuperAdmin();` at the top of each.
- Test: tenant-A requesting school B on `orders` → 403; `pendingApprovalOrders` as non-superadmin → 403; superadmin → ok.
- Commit: `feat(catalog): enforce tenant scope from authenticated context`

#### Task 5: workflow-service
**Files:** create `.../workflowservice/security/...`; modify `WorkflowReadController.java`; test `WorkflowTenantScopingTest.java`.
- Recipe A: `instances()` (`WorkflowReadController.java:59`), `pending()` (line 69).
- Recipe B: `createOrGetInstance()` (the body endpoint that inserts `school_id`).
- Leave shared `definitions()` (no `schoolId`) unchanged.
- Test: tenant-A requesting school B on `instances` → 403; omitted → A; superadmin → any.
- Commit: `feat(workflow): enforce tenant scope from authenticated context`

#### Task 6: firefighting-service
**Files:** create `.../firefightingservice/security/...`; modify `FirefightingReadController.java`; test `FirefightingTenantScopingTest.java`.
- Recipe A: `requests()` (`FirefightingReadController.java:42`), `pending()` (line 53), `stats()` (line 61).
- Recipe B: `createRequest()` (body endpoint).
- The Custoking approval endpoint (`approveCustoking()`) is a platform action: `TenantScope.requireSuperAdmin();` at the top.
- Test: tenant-A requesting school B on `requests` → 403; `stats` no longer returns cross-tenant for null; superadmin → any.
- Commit: `feat(firefighting): enforce tenant scope from authenticated context`

#### Task 7: reporting-service (special case — remove client `superAdmin`)
**Files:** create `.../reportingservice/security/...`; modify `ReportingReadController.java` and `ReportingCommandRepository.java`; test `ReportingTenantScopingTest.java`.
- Recipe A on every `@RequestParam Long schoolId` read endpoint (`summary, vendorDues, reorderSignals, dashboardCommandCenter, lowAttendanceSections, lowAttendanceStudents, feeDefaulters, feed, actions, commandCenterSummary, invoices, invoiceStats, academicEvents, eventContributions, classPhotographyPaymentStatus, broadcasts` — ~16 endpoints): replace `schoolId` with `TenantScope.resolveSchoolId(schoolId)`.
- **Remove the client-controlled `superAdmin` flag**: in `acceptAction()` / `dismissAction()` (`ReportingReadController.java` ~138-154) stop reading `body.get("superAdmin")` and `body.get("schoolId")` as the authority; instead pass `TenantContext.get().isSuperAdmin()` and `TenantScope.resolveSchoolId(<actorSchoolId from body>)`. Update `ReportingCommandRepository.requireActionAccess(...)` (~line 279) so its `superAdmin`/`actorSchoolId` arguments come from the resolved context, not the request body. Keep the signature but feed it trusted values.
- `commandCenterSummary(?platform=true)`: only honor `platform=true` when `TenantContext.get().isSuperAdmin()`; otherwise force school scope.
- Test: tenant-A requesting school B on `summary` → 403; `acceptAction` with `superAdmin:true` in the body but a tenant-A token must NOT bypass scope (still 403 for cross-tenant); superadmin token → cross-tenant allowed.
- Commit: `feat(reporting): derive tenant scope and superadmin from authenticated context, not request body`

---

### Task 8: Platform-service guards — identity, audit, tenant-school

**Files (per service):** create `.../<svc-pkg>/security/{TenantContext,TenantContextFilter,TenantScope}.java` (copy + repackage as in Tasks 2–7); modify the controllers below; add a `*TenantScopingTest`.
Package map: `identity`→`identityservice`, `audit`→`auditservice`, `tenant-school`→`tenantschoolservice`.

- **identity-service** — `UserDirectoryController` (`branchId`/`zoneId` params, ~line 40) and `RbacReadController.userPermissions()` (`schoolId`/`zoneId`, ~line 83): a non-superadmin may only read their own school's directory → wrap the `branchId`/`schoolId` with `TenantScope.resolveSchoolId(...)`; platform-wide listing (no school) requires `TenantScope.requireSuperAdmin()`.
- **audit-service** — `AuditIngestController` GET query (`schoolId` param, ~line 79): `TenantScope.resolveSchoolId(schoolId)` so a school admin reads only their own audit rows; full/unscoped export requires `requireSuperAdmin()`. The ingest POST path is service-token internal (system context) — leave unchanged.
- **tenant-school-service** — section/class/staff reads that accept `schoolId` (`TenantSchoolController` ~line 195; the compat `addStaffFromWorkspace`/`addTimetableFromWorkspace` already seen): replace the client `schoolId` with `TenantScope.resolveSchoolId(...)`. Registry-wide reads (`/zones`, `/sa/schools`, full school list) and registry mutations: `requireSuperAdmin()`. (The compat controllers currently take `schoolId` from the body and only *fall back* to the header — change them to resolve via `TenantScope`.)
- Tests (one per service): non-superadmin cross-school read → 403; own-school → ok; superadmin/platform read → ok.
- Run each: `./mvnw.cmd -q -f services/<svc>/pom.xml test` (identity needs `APP_JWT_SECRET`/`APP_AADHAR_SECRET` env).
- Commit (one commit): `feat(identity,audit,tenant-school): scope end-user reads to authenticated tenant`

---

### Task 9: Platform-service guards — billing, notification

**Files (per service):** create `.../<svc-pkg>/security/{TenantContext,TenantContextFilter,TenantScope}.java` (copy + repackage); modify controllers; add tests.
Package map: `billing`→`billingservice`, `notification`→`notificationservice`.

- **billing-service** — `BillingInvoiceController` `/api/v1/...sa/...` endpoints (Custoking↔school B2B, superadmin-only): add `TenantScope.requireSuperAdmin();` at the top of `list`, `create`, `stats`, and the customer/payment admin endpoints.
- **notification-service** — broadcast/sender-profile admin endpoints reachable by an end user: `requireSuperAdmin()` on cross-tenant broadcast/admin reads; per-school notification log writes (system context via service token) unchanged.
- Tests: non-superadmin → 403 on the `/sa/` invoice list and the admin broadcast read; superadmin → ok.
- Run each service's tests.
- Commit: `feat(billing,notification): require superadmin for platform/cross-tenant endpoints`

---

### Task 10: Full verification gate

**Files:** none (verification only).

- [ ] **Step 1: Run the full unit test catalog**

Run:
```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
powershell -ExecutionPolicy Bypass -File scripts\invoke-microservice-tests.ps1
```
Expected: 14/14 green (all Java services incl. the new tenant-scoping tests, gateway, frontend).

- [ ] **Step 2: Boundary verifier stays green**

Run (full stack from the app_rt work still applies):
```powershell
docker compose --profile full up -d --build
powershell -ExecutionPolicy Bypass -File scripts\ensure-app-rt-local.ps1
powershell -ExecutionPolicy Bypass -File scripts\verify-microservice-migration.ps1 -RunDbAudit
```
Expected: all audit steps OK (incl. the app_rt audit from Task 1.1).

- [ ] **Step 3: Commit any audit-baseline updates if the verifier requires them; otherwise no-op.**

```bash
git commit --allow-empty -m "chore: tenant-context verification gate green"
```

---

## Self-Review

**Spec coverage:**
- Request-scoped `TenantContext` from headers → Task 1 (`TenantContextFilter`), copied in Tasks 2–9. ✓
- `resolveSchoolId` 3-branch rule (superadmin widen / lock / 403) → Task 1 (`TenantScope`) + tests. ✓
- All 7 tenant-scoped services → Tasks 1–7. ✓
- reporting client-`superAdmin` removal → Task 7. ✓
- 5 platform services deny-cross-tenant → Tasks 8–9. ✓
- Eliminate `schoolId==null ⇒ all` for non-superadmin → Recipe A/B (resolved value never null unless superadmin). ✓
- System/internal call bypass → Global Constraints + only end-user paths call `resolveSchoolId`. ✓
- Superadmin via `X-Authenticated-Role` → `TenantContext.isSuperAdmin()`. ✓
- Per-service tests (cross-tenant 403 / omitted→own / superadmin→any) → each task. ✓
- Deferred zone-admin, BOLA CI suite (1.5), RLS (1.3) → explicitly out of scope. ✓

**Placeholder scan:** none — canonical code is complete; replication tasks give the exact copy step, the two named recipes from Task 1, exact call-site `file:line` lists, and concrete test assertions. ✓

**Type/name consistency:** `TenantContext` (set/get/clear/isSuperAdmin/schoolId), `TenantScope.resolveSchoolId(Long)`/`requireSuperAdmin()`, filter `@Order(HIGHEST_PRECEDENCE+10)`, package `com.custoking.ims.<svc-pkg>.security` — consistent across all tasks. The only new repo method introduced is `StudentReadRepository.schoolIdForReviewItem(String)` (Task 1, Step 7). ✓

## Risks
- A missed query path → RLS backstop (1.3) + BOLA suite (1.5). Item-scoped single-record reads left unguarded in Task 1 Step 7 are flagged for RLS coverage.
- Body-Map `schoolId` parsing: `applyResolvedSchool` coerces via `String.valueOf` then `Long.valueOf` — matches the existing `longValue` helpers in the codebase.
- Standalone MockMvc + `addFilters(new TenantContextFilter())` exercises the real filter so header→context→403 is end-to-end per service.
