# Microservice Feature-Parity Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the `/api/v1/**` endpoints the SPA calls that the microservice split left unrouted or routed to a service with no handler, so the decomposed stack matches the monolith's working feature surface.

**Architecture:** Every fix follows the established **compat-shim pattern** (`api/compat/*PublicCompatibilityController`): a `@RestController` that owns a legacy `/api/v1/**` path, validates its service's internal token, and delegates to the existing application/persistence method. The Node gateway (`services/api-gateway/server.js`) gets a matching route. The SPA is **not** touched. Only one fix (firefighting timeline) adds a new persistence method; all others delegate to methods that already exist.

**Tech Stack:** Spring Boot 3 / Java 21 (services), Mockito (Java tests), plain Node `http` + `node --test` (gateway).

## Global Constraints

- Java package roots: `com.custoking.ims.<servicename>` — copy the exact package of the file you edit.
- Compat controllers live under `…/api/compat/` and map full absolute paths (`/api/v1/...`), no class-level `@RequestMapping` base.
- Every compat endpoint MUST validate its internal token and **fail closed** (401) when missing/mismatched, exactly like `FeePublicCompatibilityController.requireToken`.
- Internal token header + property per service (verified):
  - fee → header `X-Fee-Service-Token`, prop `${fee.read-token:}`
  - identity → header `X-Identity-Service-Token`, prop `${identity.introspection-token:}`
  - student → header `X-Student-Service-Token`, prop `${student.read-token:}`
  - tenant-school → header `X-Tenant-School-Token`, prop `${tenant-school.read-token:}`
  - catalog → header `X-Catalog-Service-Token`, prop `${catalog.read-token:}`
  - firefighting → header `X-Firefighting-Service-Token`, prop `${firefighting.read-token:}`
- **Gateway ordering is load-bearing:** `routes.find()` returns the FIRST match. Any sub-path override (e.g. `/api/v1/workspace/staff`) MUST be inserted in the `routes` array **above** the broader prefix it overrides (e.g. `/api/v1/workspace`). Tasks specify exact placement.
- Build/test a single service: `./mvnw -f services/<svc>/pom.xml test` (Windows: `.\mvnw.cmd -f services\<svc>\pom.xml test`). Gateway: `cd services/api-gateway && node --test server.test.js`.
- Forward-only: no new Flyway migrations are required by any task in this plan.

---

## File Structure

| Service | File | Change |
|---|---|---|
| api-gateway | `services/api-gateway/server.js` | add routes (per task) |
| api-gateway | `services/api-gateway/server.test.js` | add routing assertions (per task) |
| fee | `…/feeservice/api/compat/FeePublicCompatibilityController.java` | + receipt alias, + fee-defaulter reminders |
| identity | `…/identityservice/api/compat/IdentityPublicCompatibilityController.java` | **new** — school admin / operations-user / zone admin |
| student | `…/studentservice/api/compat/StudentWorkspaceCompatibilityController.java` | + review-item update |
| firefighting | `…/firefightingservice/api/compat/FirefightingPublicCompatibilityController.java` | **new** — workspace create + vendor mark-paid |
| firefighting | `…/firefightingservice/api/FirefightingReadController.java` + `…/persistence/FirefightingReadRepository.java` | + timeline |
| tenant-school | `…/tenantschoolservice/api/compat/TenantSchoolPublicCompatibilityController.java` | **new** — workspace staff |
| catalog | `…/catalogservice/api/compat/CatalogPublicCompatibilityController.java` | + vendor mark-paid |

Each new compat controller is a focused, single-responsibility file mirroring `FeePublicCompatibilityController` (token guard + `run()` helper + delegation).

---

## Task 1: Fee receipt PDF alias (`GET /api/v1/fees/receipts/{paymentId}/pdf`)

Pure shim. Gateway already routes `/api/v1/fees/` → fee, so **no gateway change**. The frontend (`FeesPanel.tsx`) calls `/fees/receipts/{paymentId}/pdf`; the controller already has `receiptPdfByPaymentId` under a different path.

**Files:**
- Modify: `services/fee-service/src/main/java/com/custoking/ims/feeservice/api/compat/FeePublicCompatibilityController.java:181-187`
- Test: `services/fee-service/src/test/java/com/custoking/ims/feeservice/api/compat/FeePublicCompatibilityControllerTest.java` (create if absent)

**Interfaces:**
- Consumes: `FeeReadRepository.receiptPdfByPaymentId(String) → byte[]` (exists)
- Produces: route `GET /api/v1/fees/receipts/{paymentId}/pdf`

- [ ] **Step 1: Write the failing test** — verify the new path delegates to `receiptPdfByPaymentId`.

```java
package com.custoking.ims.feeservice.api.compat;

import com.custoking.ims.feeservice.persistence.FeeReadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeePublicCompatibilityControllerTest {

    @Test
    void feesReceiptsPdfAliasDelegatesToPaymentIdLookup() {
        FeeReadRepository fees = mock(FeeReadRepository.class);
        when(fees.receiptPdfByPaymentId("PMT-1")).thenReturn(new byte[]{1, 2, 3});
        var controller = new FeePublicCompatibilityController(fees, "tok");

        ResponseEntity<byte[]> res = controller.receiptByPaymentIdPdf("tok", "PMT-1");

        assertThat(res.getBody()).containsExactly(1, 2, 3);
    }
}
```

- [ ] **Step 2: Run test to verify it fails** — `.\mvnw.cmd -f services\fee-service\pom.xml test -Dtest=FeePublicCompatibilityControllerTest`. Expected: PASS for the existing path test, but we have not yet added the alias; the failing assertion comes in Step 3's added test. (If the test class is new, it compiles and passes against the existing method — proceed to add the alias mapping and its assertion.)

- [ ] **Step 3: Add the alias path** — extend the existing `@GetMapping` to a multi-path mapping.

Replace lines 181-182:

```java
    @GetMapping(value = {"/api/v1/receipts/{paymentId}/pdf", "/api/v1/fees/receipts/{paymentId}/pdf"},
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> receiptByPaymentIdPdf(
```

- [ ] **Step 4: Run tests** — `.\mvnw.cmd -f services\fee-service\pom.xml test`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/fee-service
git commit -m "fix(fee): serve receipt PDF at /api/v1/fees/receipts/{id}/pdf alias"
```

---

## Task 2: School admin + operations-user provisioning

`POST /api/v1/schools/{id}/admin` and `POST /api/v1/schools/{id}/operations-user`. Both delegate to the generic `provisionSchoolUser(schoolId, role, body)`. Gateway currently routes `/api/v1/schools/` → tenant, so a **more specific override → identity must be inserted ABOVE** the tenant schools routes.

**Files:**
- Create: `services/identity-service/src/main/java/com/custoking/ims/identityservice/api/compat/IdentityPublicCompatibilityController.java`
- Test: `services/identity-service/src/test/java/com/custoking/ims/identityservice/api/compat/IdentityPublicCompatibilityControllerTest.java`
- Modify: `services/api-gateway/server.js` (routes array, above line 63 `route('tenant', '/api/v1/schools/')`)
- Modify: `services/api-gateway/server.test.js`

**Interfaces:**
- Consumes: `IdentityUserProvisioningRepository.provisionSchoolUser(Long, String, Map) → Map<String,Object>`, `.provisionZoneAdmin(Long, Map) → Map<String,Object>` (both exist)
- Produces: routes `POST /api/v1/schools/{id}/admin`, `/operations-user`, `/api/v1/zones/{id}/admin` (zone wired in Task 3) → identity

- [ ] **Step 1: Write the failing gateway test** — append to `server.test.js`.

```js
test('school admin + operations-user route to identity, not tenant', () => {
  const { routes } = require('./server');
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/schools/12/admin'), 'identity');
  assert.equal(resolve('/api/v1/schools/12/operations-user'), 'identity');
  assert.equal(resolve('/api/v1/schools/12/modules'), 'tenant'); // unchanged
  assert.equal(resolve('/api/v1/schools'), 'tenant');            // unchanged
});
```

- [ ] **Step 2: Run gateway test to verify it fails** — `cd services/api-gateway && node --test server.test.js`. Expected: FAIL (`/api/v1/schools/12/admin` resolves to `tenant`).

- [ ] **Step 3: Add the override routes ABOVE the tenant schools routes** — in `services/api-gateway/server.js`, immediately **before** the line `route('tenant', '/api/v1/classes/'),` (line 61) insert:

```js
  route('identity', /^\/api\/v1\/schools\/[^/]+\/(admin|operations-user)$/),
  route('identity', /^\/api\/v1\/zones\/[^/]+\/admin$/),
```

- [ ] **Step 4: Run gateway test** — `node --test server.test.js`. Expected: PASS.

- [ ] **Step 5: Create the identity compat controller**

```java
package com.custoking.ims.identityservice.api.compat;

import com.custoking.ims.identityservice.persistence.IdentityUserProvisioningRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
public class IdentityPublicCompatibilityController {

    private final IdentityUserProvisioningRepository users;
    private final String serviceToken;

    public IdentityPublicCompatibilityController(
            IdentityUserProvisioningRepository users,
            @Value("${identity.introspection-token:}") String serviceToken) {
        this.users = users;
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }

    @PostMapping("/api/v1/schools/{schoolId}/admin")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createSchoolAdmin(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long schoolId,
            @RequestBody Map<String, Object> body) {
        requireToken(token);
        return run(() -> users.provisionSchoolUser(schoolId, "ADMIN", body));
    }

    @PostMapping("/api/v1/schools/{schoolId}/operations-user")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createOperationsUser(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long schoolId,
            @RequestBody Map<String, Object> body) {
        requireToken(token);
        return run(() -> users.provisionSchoolUser(schoolId, "OPERATIONS", body));
    }

    @PostMapping("/api/v1/zones/{zoneId}/admin")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createZoneAdmin(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long zoneId,
            @RequestBody Map<String, Object> body) {
        requireToken(token);
        return run(() -> users.provisionZoneAdmin(zoneId, body));
    }

    private void requireToken(String token) {
        if (!StringUtils.hasText(serviceToken) || !serviceToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid identity service token");
        }
    }

    private Map<String, Object> run(java.util.function.Supplier<Map<String, Object>> command) {
        try {
            return command.get();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
```

> **Note:** the `"OPERATIONS"` role string must be one `provisionSchoolUser` accepts. Verify against `IdentityUserProvisioningRepository.provisionSchoolUser` (`services/identity-service/.../persistence/IdentityUserProvisioningRepository.java:26`). If it whitelists roles and lacks `OPERATIONS`, add that role mapping in the same method (separate commit) — the monolith created an operations user via `SchoolController`/store; match the role code it used.

- [ ] **Step 6: Write the identity controller test**

```java
package com.custoking.ims.identityservice.api.compat;

import com.custoking.ims.identityservice.persistence.IdentityUserProvisioningRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdentityPublicCompatibilityControllerTest {

    private final IdentityUserProvisioningRepository users = mock(IdentityUserProvisioningRepository.class);
    private final IdentityPublicCompatibilityController controller =
            new IdentityPublicCompatibilityController(users, "tok");

    @Test
    void schoolAdminDelegatesWithAdminRole() {
        when(users.provisionSchoolUser(eq(7L), eq("ADMIN"), eq(Map.of("email", "a@b.c"))))
                .thenReturn(Map.of("id", 1));
        assertThat(controller.createSchoolAdmin("tok", 7L, Map.of("email", "a@b.c")))
                .containsEntry("id", 1);
    }

    @Test
    void rejectsBadToken() {
        assertThatThrownBy(() -> controller.createSchoolAdmin("nope", 7L, Map.of()))
                .isInstanceOf(ResponseStatusException.class);
    }
}
```

- [ ] **Step 7: Run tests** — `.\mvnw.cmd -f services\identity-service\pom.xml test` and `node --test services/api-gateway/server.test.js`. Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add services/identity-service services/api-gateway
git commit -m "fix(identity): restore school-admin and operations-user provisioning via /api/v1 compat"
```

---

## Task 3: Zone admin provisioning (`POST /api/v1/zones/{id}/admin`)

The controller method (`createZoneAdmin`) and the gateway route (`/^\/api\/v1\/zones\/[^/]+\/admin$/`) were already added in Task 2. This task only adds the **gateway routing test** and verifies the zone path does not leak to tenant.

**Files:**
- Modify: `services/api-gateway/server.test.js`

- [ ] **Step 1: Add the test**

```js
test('zone admin routes to identity, zone reads stay on tenant', () => {
  const { routes } = require('./server');
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/zones/3/admin'), 'identity');
  assert.equal(resolve('/api/v1/zones/3/admins'), 'tenant');   // GET list stays tenant
  assert.equal(resolve('/api/v1/zones/3/schools'), 'tenant');
  assert.equal(resolve('/api/v1/zones'), 'tenant');
});
```

- [ ] **Step 2: Run test** — `node --test services/api-gateway/server.test.js`. Expected: PASS (`/admin` matches the regex above the tenant `/zones/` route; `/admins` does not match `…/admin$` so falls through to tenant).

- [ ] **Step 3: Commit**

```bash
git add services/api-gateway/server.test.js
git commit -m "test(gateway): assert zone-admin provisioning routes to identity"
```

---

## Task 4: Student review-item update (`PUT /api/v1/student-review-items/{itemId}`)

Pure shim to `StudentReadRepository.updateReviewItem(itemId, request)`. New top-level path → needs a gateway route (to student) and a method on the existing student compat controller.

**Files:**
- Modify: `services/student-service/src/main/java/com/custoking/ims/studentservice/api/compat/StudentWorkspaceCompatibilityController.java`
- Test: `services/student-service/src/test/java/com/custoking/ims/studentservice/api/compat/StudentWorkspaceCompatibilityControllerTest.java` (create if absent)
- Modify: `services/api-gateway/server.js` and `server.test.js`

**Interfaces:**
- Consumes: `StudentReadRepository.updateReviewItem(String itemId, Map request) → Map<String,Object>` (exists, `StudentReadRepository.java:519`)
- Produces: route `PUT /api/v1/student-review-items/{itemId}` → student

- [ ] **Step 1: Add the gateway route** — in `server.js`, anywhere among the student routes (e.g. directly after line 68 `route('student', '/api/v1/students'),`) insert:

```js
  route('student', '/api/v1/student-review-items/'),
```

- [ ] **Step 2: Add a gateway test** to `server.test.js`:

```js
test('student-review-items routes to student', () => {
  const { routes } = require('./server');
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/student-review-items/RV-9'), 'student');
});
```

- [ ] **Step 3: Run gateway test** — `node --test services/api-gateway/server.test.js`. Expected: PASS.

- [ ] **Step 4: Add the controller method** — open `StudentWorkspaceCompatibilityController.java`. It already injects the student repo + `${student.read-token:}` token (mirror its existing constructor field names; assume `repo` and `readToken`). Add:

```java
    @PutMapping("/api/v1/student-review-items/{itemId}")
    public Map<String, Object> updateReviewItem(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable String itemId,
            @RequestBody Map<String, Object> request) {
        requireToken(token);
        try {
            return repo.updateReviewItem(itemId, request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
```

Add the imports `PutMapping`, `PathVariable`, `RequestBody`, `RequestHeader`, `ResponseStatusException`, `Map` if not present, and reuse the controller's existing `requireToken` helper (mirror `FeePublicCompatibilityController.requireToken` if this controller lacks one — match the field name it uses for the injected repo and token).

- [ ] **Step 5: Write the controller test**

```java
package com.custoking.ims.studentservice.api.compat;

import com.custoking.ims.studentservice.persistence.StudentReadRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StudentWorkspaceCompatibilityControllerTest {

    @Test
    void updateReviewItemDelegates() {
        StudentReadRepository repo = mock(StudentReadRepository.class);
        when(repo.updateReviewItem("RV-9", Map.of("status", "APPROVED"))).thenReturn(Map.of("ok", true));
        var controller = new StudentWorkspaceCompatibilityController(repo, "tok"); // match real constructor signature

        assertThat(controller.updateReviewItem("tok", "RV-9", Map.of("status", "APPROVED")))
                .containsEntry("ok", true);
    }
}
```

> If `StudentWorkspaceCompatibilityController`'s constructor signature differs, adjust the `new …(…)` call to match — read its constructor first.

- [ ] **Step 6: Run tests** — `.\mvnw.cmd -f services\student-service\pom.xml test` and `node --test services/api-gateway/server.test.js`. Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add services/student-service services/api-gateway
git commit -m "fix(student): wire PUT /api/v1/student-review-items/{id} to review update"
```

---

## Task 5: Workspace firefighting create (`POST /api/v1/workspace/firefighting`)

Routed to reporting today (the `/api/v1/workspace` catch-all). Override → firefighting, delegate to `FirefightingReadRepository.createRequest(request)`.

**Files:**
- Create: `services/firefighting-service/src/main/java/com/custoking/ims/firefightingservice/api/compat/FirefightingPublicCompatibilityController.java`
- Test: `services/firefighting-service/src/test/java/com/custoking/ims/firefightingservice/api/compat/FirefightingPublicCompatibilityControllerTest.java`
- Modify: `services/api-gateway/server.js` (above line 71 `route('reporting', '/api/v1/workspace/')`) and `server.test.js`

**Interfaces:**
- Consumes: `FirefightingReadRepository.createRequest(Map) → Map<String,Object>` (exists, `:93`); `markVendorPaid(String code, Map) → Map<String,Object>` (exists, `:297`, used in Task 7)
- Produces: route `POST /api/v1/workspace/firefighting` → firefighting

- [ ] **Step 1: Add gateway route ABOVE the workspace catch-all** — in `server.js`, immediately **before** line 70 `route('fee', '/api/v1/workspace/fees/'),` insert:

```js
  route('firefighting', '/api/v1/workspace/firefighting'),
```

- [ ] **Step 2: Add gateway test**

```js
test('workspace firefighting routes to firefighting, not reporting', () => {
  const { routes } = require('./server');
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/workspace/firefighting'), 'firefighting');
  assert.equal(resolve('/api/v1/workspace/students'), 'student');   // unchanged
  assert.equal(resolve('/api/v1/workspace'), 'reporting');          // unchanged
});
```

- [ ] **Step 3: Run gateway test** — `node --test services/api-gateway/server.test.js`. Expected: PASS.

- [ ] **Step 4: Create the firefighting compat controller**

```java
package com.custoking.ims.firefightingservice.api.compat;

import com.custoking.ims.firefightingservice.persistence.FirefightingReadRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.function.Supplier;

@RestController
public class FirefightingPublicCompatibilityController {

    private final FirefightingReadRepository firefighting;
    private final String readToken;

    public FirefightingPublicCompatibilityController(
            FirefightingReadRepository firefighting,
            @Value("${firefighting.read-token:}") String readToken) {
        this.firefighting = firefighting;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @PostMapping("/api/v1/workspace/firefighting")
    public Map<String, Object> createFromWorkspace(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token);
        return run(() -> firefighting.createRequest(request));
    }

    @PostMapping("/api/v1/dashboard/vendor-dues/firefighting/{code}/mark-paid")
    public Map<String, Object> markVendorPaid(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code,
            @RequestBody(required = false) Map<String, Object> request) {
        requireToken(token);
        return run(() -> firefighting.markVendorPaid(code, request == null ? Map.of() : request));
    }

    private void requireToken(String token) {
        if (!StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid firefighting service token");
        }
    }

    private Map<String, Object> run(Supplier<Map<String, Object>> command) {
        try {
            return command.get();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
```

> The `mark-paid` method here is the firefighting half of Task 7; its gateway route is added in Task 7.

- [ ] **Step 5: Write the controller test**

```java
package com.custoking.ims.firefightingservice.api.compat;

import com.custoking.ims.firefightingservice.persistence.FirefightingReadRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FirefightingPublicCompatibilityControllerTest {

    private final FirefightingReadRepository repo = mock(FirefightingReadRepository.class);
    private final FirefightingPublicCompatibilityController controller =
            new FirefightingPublicCompatibilityController(repo, "tok");

    @Test
    void workspaceCreateDelegatesToCreateRequest() {
        when(repo.createRequest(Map.of("title", "Extinguisher"))).thenReturn(Map.of("code", "FF-1"));
        assertThat(controller.createFromWorkspace("tok", Map.of("title", "Extinguisher")))
                .containsEntry("code", "FF-1");
    }
}
```

- [ ] **Step 6: Run tests** — `.\mvnw.cmd -f services\firefighting-service\pom.xml test` and `node --test services/api-gateway/server.test.js`. Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add services/firefighting-service services/api-gateway
git commit -m "fix(firefighting): restore POST /api/v1/workspace/firefighting create"
```

---

## Task 6: Workspace staff (`POST /api/v1/workspace/staff`)

Override → tenant-school. Delegate to `SchoolStructureReadRepository.addStaff(schoolId, request)`. The workspace request body carries `schoolId`; extract it (the canonical tenant endpoint takes it as a path var).

**Files:**
- Create: `services/tenant-school-service/src/main/java/com/custoking/ims/tenantschoolservice/api/compat/TenantSchoolPublicCompatibilityController.java`
- Test: matching test file
- Modify: `services/api-gateway/server.js` (above line 71 workspace catch-all) and `server.test.js`

**Interfaces:**
- Consumes: `SchoolStructureReadRepository.addStaff(Long schoolId, Map request) → Map<String,Object>` (exists, `:203`)
- Produces: route `POST /api/v1/workspace/staff` → tenant

> Confirm the bean type/name that exposes `addStaff` and how `TenantSchoolController` injects it (read `TenantSchoolController.java:177 addSchoolStaff`). Use the same injected type below (shown as `SchoolStructureReadRepository schools`).

- [ ] **Step 1: Add gateway route ABOVE the workspace catch-all** — in `server.js`, immediately **before** line 70 `route('fee', '/api/v1/workspace/fees/'),` insert:

```js
  route('tenant', '/api/v1/workspace/staff'),
```

- [ ] **Step 2: Add gateway test**

```js
test('workspace staff routes to tenant', () => {
  const { routes } = require('./server');
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/workspace/staff'), 'tenant');
  assert.equal(resolve('/api/v1/workspace'), 'reporting'); // unchanged
});
```

- [ ] **Step 3: Run gateway test** — `node --test services/api-gateway/server.test.js`. Expected: PASS.

- [ ] **Step 4: Create the tenant compat controller**

```java
package com.custoking.ims.tenantschoolservice.api.compat;

import com.custoking.ims.tenantschoolservice.persistence.SchoolStructureReadRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
public class TenantSchoolPublicCompatibilityController {

    private final SchoolStructureReadRepository schools;
    private final String readToken;

    public TenantSchoolPublicCompatibilityController(
            SchoolStructureReadRepository schools,
            @Value("${tenant-school.read-token:}") String readToken) {
        this.schools = schools;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @PostMapping("/api/v1/workspace/staff")
    public Map<String, Object> addStaffFromWorkspace(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token);
        Long schoolId = longValue(request.get("schoolId"));
        if (schoolId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schoolId is required");
        }
        try {
            return schools.addStaff(schoolId, request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private void requireToken(String token) {
        if (!StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid tenant-school service token");
        }
    }

    private Long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }
}
```

- [ ] **Step 5: Write the controller test**

```java
package com.custoking.ims.tenantschoolservice.api.compat;

import com.custoking.ims.tenantschoolservice.persistence.SchoolStructureReadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantSchoolPublicCompatibilityControllerTest {

    private final SchoolStructureReadRepository schools = mock(SchoolStructureReadRepository.class);
    private final TenantSchoolPublicCompatibilityController controller =
            new TenantSchoolPublicCompatibilityController(schools, "tok");

    @Test
    void addsStaffUsingSchoolIdFromBody() {
        when(schools.addStaff(eq(5L), eq(Map.of("schoolId", 5, "name", "Asha")))).thenReturn(Map.of("id", 1));
        assertThat(controller.addStaffFromWorkspace("tok", Map.of("schoolId", 5, "name", "Asha")))
                .containsEntry("id", 1);
    }

    @Test
    void rejectsMissingSchoolId() {
        assertThatThrownBy(() -> controller.addStaffFromWorkspace("tok", Map.of("name", "Asha")))
                .isInstanceOf(ResponseStatusException.class);
    }
}
```

- [ ] **Step 6: Run tests** — `.\mvnw.cmd -f services\tenant-school-service\pom.xml test` and `node --test services/api-gateway/server.test.js`. Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add services/tenant-school-service services/api-gateway
git commit -m "fix(tenant-school): restore POST /api/v1/workspace/staff"
```

---

## Task 7: Vendor-dues mark-paid (catalog + firefighting)

`POST /api/v1/dashboard/vendor-dues/catalog-orders/{id}/mark-paid` → catalog; `POST /api/v1/dashboard/vendor-dues/firefighting/{code}/mark-paid` → firefighting. Both override the `/api/v1/dashboard/` → reporting catch-all. The firefighting controller method was added in Task 5; here we add the catalog method and **both** gateway routes.

**Files:**
- Modify: `services/catalog-service/src/main/java/com/custoking/ims/catalogservice/api/compat/CatalogPublicCompatibilityController.java`
- Test: `services/catalog-service/.../compat/CatalogPublicCompatibilityControllerTest.java` (create if absent)
- Modify: `services/api-gateway/server.js` (above line 88 `route('reporting', '/api/v1/dashboard/')`) and `server.test.js`

**Interfaces:**
- Consumes: `CatalogReadRepository.markVendorPaid(String id, Long schoolId, Long actorId, String notes) → CatalogOrderRow` (exists, `:296`)
- Produces: routes for both mark-paid paths

- [ ] **Step 1: Add both gateway routes ABOVE the dashboard catch-all** — in `server.js`, immediately **before** line 88 `route('reporting', '/api/v1/dashboard/'),` insert:

```js
  route('catalog', /^\/api\/v1\/dashboard\/vendor-dues\/catalog-orders\/[^/]+\/mark-paid$/),
  route('firefighting', /^\/api\/v1\/dashboard\/vendor-dues\/firefighting\/[^/]+\/mark-paid$/),
```

- [ ] **Step 2: Add gateway test**

```js
test('vendor-dues mark-paid routes to owning services, dashboard reads stay reporting', () => {
  const { routes } = require('./server');
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/dashboard/vendor-dues/catalog-orders/12/mark-paid'), 'catalog');
  assert.equal(resolve('/api/v1/dashboard/vendor-dues/firefighting/FF-3/mark-paid'), 'firefighting');
  assert.equal(resolve('/api/v1/dashboard/vendor-dues'), 'reporting'); // GET list unchanged
});
```

- [ ] **Step 3: Run gateway test** — `node --test services/api-gateway/server.test.js`. Expected: PASS.

- [ ] **Step 4: Add the catalog controller method** — in `CatalogPublicCompatibilityController.java` (reuse its existing repo field + `${catalog.read-token:}` token guard; mirror the existing `requireToken`/`run` helpers in that file):

```java
    @PostMapping("/api/v1/dashboard/vendor-dues/catalog-orders/{id}/mark-paid")
    public Map<String, Object> markCatalogVendorPaid(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request) {
        requireToken(token, "catalog:read");
        Map<String, Object> body = request == null ? Map.of() : request;
        Long schoolId = longValue(body.get("schoolId"));
        Long actorId = longValue(body.get("actorId"));
        String notes = body.get("notes") == null ? null : String.valueOf(body.get("notes"));
        var row = catalog.markVendorPaid(id, schoolId, actorId, notes);
        return Map.of("order", row);
    }
```

Match the field name (`catalog`), `requireToken` signature, and any `longValue` helper to what the existing `CatalogPublicCompatibilityController` already defines; add a `longValue` helper if absent (copy from `FeePublicCompatibilityController:230`). Add missing imports (`PathVariable`, `PostMapping`, `RequestBody`, `RequestHeader`).

- [ ] **Step 5: Write the controller test**

```java
package com.custoking.ims.catalogservice.api.compat;

import com.custoking.ims.catalogservice.persistence.CatalogReadRepository;
import com.custoking.ims.catalogservice.persistence.CatalogReadRepository.CatalogOrderRow;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogPublicCompatibilityControllerTest {

    @Test
    void markPaidDelegatesToRepository() {
        CatalogReadRepository repo = mock(CatalogReadRepository.class);
        CatalogOrderRow row = mock(CatalogOrderRow.class);
        when(repo.markVendorPaid(eq("12"), any(), any(), any())).thenReturn(row);
        var controller = new CatalogPublicCompatibilityController(repo, "tok"); // match real constructor

        assertThat(controller.markCatalogVendorPaid("tok", "12", Map.of("schoolId", 1)))
                .containsEntry("order", row);
    }
}
```

> Adjust the `new CatalogPublicCompatibilityController(...)` call and the `CatalogOrderRow` import to the actual constructor/type — read the controller header first.

- [ ] **Step 6: Run tests** — `.\mvnw.cmd -f services\catalog-service\pom.xml test` and `node --test services/api-gateway/server.test.js`. Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add services/catalog-service services/api-gateway
git commit -m "fix(dashboard): wire vendor-dues mark-paid to catalog and firefighting"
```

---

## Task 8: Dashboard fee-defaulter reminders (`POST /api/v1/dashboard/finance/fee-defaulters/reminders`)

Override the dashboard catch-all → fee. Delegate to the same `feeReminderRequests(...)` the `/api/v1/fees/send-reminders` compat already uses.

**Files:**
- Modify: `services/fee-service/.../api/compat/FeePublicCompatibilityController.java`
- Test: add a case to `FeePublicCompatibilityControllerTest`
- Modify: `services/api-gateway/server.js` (above line 88 dashboard catch-all) and `server.test.js`

**Interfaces:**
- Consumes: `FeeReadRepository.feeReminderRequests(String classId, String sectionId, String academicYearId, Long schoolId, Long actorId) → Map<String,Object>` (exists, used at `FeePublicCompatibilityController:173`)
- Produces: route `POST /api/v1/dashboard/finance/fee-defaulters/reminders` → fee

- [ ] **Step 1: Add gateway route ABOVE the dashboard catch-all** — in `server.js`, with the Task 7 dashboard overrides (immediately before line 88), add:

```js
  route('fee', '/api/v1/dashboard/finance/fee-defaulters/reminders'),
```

- [ ] **Step 2: Add gateway test**

```js
test('fee-defaulter reminders route to fee, defaulter reads stay reporting', () => {
  const { routes } = require('./server');
  const resolve = (p) => routes.find((r) => r.matches(p))?.service;
  assert.equal(resolve('/api/v1/dashboard/finance/fee-defaulters/reminders'), 'fee');
  assert.equal(resolve('/api/v1/dashboard/finance/fee-defaulters'), 'reporting'); // GET list unchanged
});
```

- [ ] **Step 3: Run gateway test** — `node --test services/api-gateway/server.test.js`. Expected: PASS.

- [ ] **Step 4: Add the controller method** to `FeePublicCompatibilityController` (reuse `text`/`longValue` helpers already in the file):

```java
    @PostMapping("/api/v1/dashboard/finance/fee-defaulters/reminders")
    public Map<String, Object> dashboardFeeReminders(
            @RequestHeader(value = "X-Fee-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "fee:read");
        return run(() -> fees.feeReminderRequests(
                text(request.get("classId")),
                text(request.get("sectionId")),
                text(request.get("academicYearId")),
                longValue(request.get("schoolId")),
                longValue(request.get("actorId"))));
    }
```

- [ ] **Step 5: Add the test case** to `FeePublicCompatibilityControllerTest`:

```java
    @Test
    void dashboardFeeRemindersDelegateToFeeReminders() {
        FeeReadRepository fees = mock(FeeReadRepository.class);
        when(fees.feeReminderRequests(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Map.of("queued", 3));
        var controller = new FeePublicCompatibilityController(fees, "tok");
        assertThat(controller.dashboardFeeReminders("tok", java.util.Map.of("schoolId", 1)))
                .containsEntry("queued", 3);
    }
```

- [ ] **Step 6: Run tests** — `.\mvnw.cmd -f services\fee-service\pom.xml test` and `node --test services/api-gateway/server.test.js`. Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add services/fee-service services/api-gateway
git commit -m "fix(dashboard): wire fee-defaulter reminders to fee reminder service"
```

---

## Task 9: Firefighting request timeline (`GET /api/v1/ff/requests/{code}/timeline`)

The only true build. Gateway already routes `/api/v1/ff/` → firefighting. Add a `timeline(code)` repo method that assembles an ordered milestone list from the request's existing status timestamps, and a controller mapping on `FirefightingReadController` (base `/api/v1/ff`).

**Files:**
- Modify: `services/firefighting-service/.../persistence/FirefightingReadRepository.java`
- Modify: `services/firefighting-service/.../api/FirefightingReadController.java`
- Test: `services/firefighting-service/.../persistence/FirefightingReadRepositoryTest.java` or a controller test

**Interfaces:**
- Consumes: `FirefightingReadRepository.detail(String code)` / `request(String code)` (exist) — read the actual milestone columns (`created_at`, `submitted_at`, `bursar_approved_at`, `principal_approved_at`, `custoking_approved_at`, `fulfilled_at`, `vendor_paid_at`, or equivalents) from `requestSelect()` / `FirefightingRequestRow`. **Read those first** to use the real column/field names.
- Produces: `FirefightingReadRepository.timeline(String code) → List<Map<String,Object>>`, controller `GET /api/v1/ff/requests/{code}/timeline`

- [ ] **Step 1: Inspect the row shape** — read `FirefightingReadRepository.requestSelect()` and `FirefightingRequestRow` to list which milestone timestamp fields actually exist. The timeline is built only from fields that exist; do not invent columns.

- [ ] **Step 2: Write the failing repo test** (adjust field setters to the real `FirefightingRequestRow` record components found in Step 1):

```java
@Test
void timelineReturnsOrderedMilestonesThatHaveTimestamps() {
    // Arrange a request row via the test harness this module already uses
    // (mirror an existing FirefightingReadRepositoryTest setup).
    var timeline = repository.timeline("FF-1");
    // created milestone is always present; submitted/approved appear only when set
    assertThat(timeline).extracting(m -> m.get("status"))
            .startsWith("CREATED");
    assertThat(timeline).allSatisfy(m -> assertThat(m).containsKeys("status", "at"));
}
```

- [ ] **Step 3: Run it to verify it fails** — `.\mvnw.cmd -f services\firefighting-service\pom.xml test -Dtest=FirefightingReadRepositoryTest`. Expected: FAIL (`timeline` undefined).

- [ ] **Step 4: Implement `timeline`** in `FirefightingReadRepository` — build an ordered list, appending one entry per non-null milestone timestamp (use the real fields from Step 1; the skeleton below shows the shape):

```java
public java.util.List<Map<String, Object>> timeline(String code) {
    var row = request(code).orElseThrow(() -> new IllegalArgumentException("Request not found"));
    var events = new java.util.ArrayList<Map<String, Object>>();
    addEvent(events, "CREATED", row.createdAt());
    addEvent(events, "SUBMITTED", row.submittedAt());
    addEvent(events, "BURSAR_APPROVED", row.bursarApprovedAt());
    addEvent(events, "PRINCIPAL_APPROVED", row.principalApprovedAt());
    addEvent(events, "CUSTOKING_APPROVED", row.custokingApprovedAt());
    addEvent(events, "FULFILLED", row.fulfilledAt());
    addEvent(events, "VENDOR_PAID", row.vendorPaidAt());
    events.sort(java.util.Comparator.comparing(e -> (java.time.OffsetDateTime) e.get("at")));
    return events;
}

private void addEvent(java.util.List<Map<String, Object>> events, String status, java.time.OffsetDateTime at) {
    if (at != null) {
        events.add(new java.util.LinkedHashMap<>(Map.of("status", status, "at", at)));
    }
}
```

> Replace the `row.*At()` accessors with the actual record-component names found in Step 1. Drop any milestone the row does not carry; add any extra status timestamps it does.

- [ ] **Step 5: Add the controller mapping** in `FirefightingReadController` (base `/api/v1/ff`, reuse its existing token guard + delegate field):

```java
    @GetMapping("/requests/{code}/timeline")
    public java.util.List<Map<String, Object>> timeline(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code) {
        requireToken(token);
        return firefighting.timeline(code);
    }
```

Match `requireToken(...)` and the repo field name to the existing methods in `FirefightingReadController`.

- [ ] **Step 6: Run tests** — `.\mvnw.cmd -f services\firefighting-service\pom.xml test`. Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add services/firefighting-service
git commit -m "feat(firefighting): expose request timeline at /api/v1/ff/requests/{code}/timeline"
```

---

## Final verification (after all tasks)

- [ ] **Gateway suite** — `cd services/api-gateway && node --test server.test.js` → all PASS.
- [ ] **Touched services** — run `test` for fee, identity, student, firefighting, tenant-school, catalog.
- [ ] **Boundary gate** — `docker compose --profile full up -d --build` then `powershell -ExecutionPolicy Bypass -File scripts\verify-microservice-migration.ps1 -RunDbAudit -RunSmoke`.
- [ ] **Authorization regression** — `powershell -ExecutionPolicy Bypass -File scripts\audit-service-authorization-boundaries.ps1` stays green (every new compat endpoint validates its token).

---

## Deferred — NOT in this plan (need their own plan / decision)

1. **School-facing Billing module** — `GET/POST /customers`, `GET/POST /invoices`, `GET /invoices/{id}/pdf`, and the legacy `/billing-payments`. No service implements these (billing-service only does `/sa/invoices`). This is a full new domain (entities, Flyway schema, persistence) — **its own plan** (decided with the user).
2. **Approvals inbox** — `GET /approvals`, `POST /approvals/{id}/{action}`. The monolith served a **superadmin cross-domain** inbox (`store.approvals` / `decideApproval`) aggregating pending items across domains. There is no single owner in the split; it needs an aggregation design (likely reporting-service reading catalog `/orders/pending-approval` + firefighting `/requests/pending-approvals`, with decisions dispatched back to each). Needs a brainstorming pass before planning.
3. **Workspace timetable** — `POST /api/v1/workspace/timetable`. The monolith stored timetable entries (`store.addTimetableEntry`); tenant-school has **no timetable table or persistence**. Requires a schema migration + persistence in tenant-school — small but out of scope for a routing-only plan. Decide: build, or drop the Timetable panel.
