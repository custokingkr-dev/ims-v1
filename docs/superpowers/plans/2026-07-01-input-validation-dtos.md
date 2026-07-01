# Input-Validation DTOs Implementation Plan (Phase 1, Task 1.7 — identity pilot)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `Map<String,Object>` request bodies on identity-service's RBAC write endpoints + `password-reset` with `@Valid` DTO records, and add a global validation-error handler returning a consistent `{message, fieldErrors}` 400 — establishing the reusable pattern + error contract other services will replicate.

**Architecture:** Controllers accept `@Valid <Cmd>Request` records (jakarta.validation annotations) and, at the boundary, rebuild the exact `Map<String,Object>` the existing repositories already consume (no repo/service rewrite). A `@RestControllerAdvice` turns a validation failure into a 400 with an SPA-friendly `{message:"Validation failed", fieldErrors:{field:msg}}` body.

**Tech Stack:** Java 21, Spring Boot 3.5.16, `spring-boot-starter-validation` (already on identity's classpath), jakarta.validation, Spring MVC `@RestControllerAdvice`, JUnit 5 + MockMvc (standalone).

**Spec:** `docs/superpowers/specs/2026-07-01-input-validation-dtos-design.md`

## Global Constraints

- **Error contract (exact):** validation failures → HTTP 400 with body `{ "message": "Validation failed", "fieldErrors": { "<field>": "<message>", … } }`. `fieldErrors` keeps the FIRST message per field (`putIfAbsent`).
- **Advice scope:** the `@RestControllerAdvice` handles ONLY `MethodArgumentNotValidException` (body `@Valid`) and `ConstraintViolationException` (param `@Validated`). It must NOT handle `ResponseStatusException` or generic `Exception` — existing error behavior is unchanged.
- **Boundary-only:** each in-scope controller method takes `@Valid @RequestBody <Cmd>Request req`, then builds a null-tolerant `new HashMap<>()` with the EXACT keys the repository reads today, and calls the unchanged repository method. Use `HashMap` (not `Map.of`) because optional fields may be null.
- **Exact Map keys the repos read (must be preserved verbatim):** createRole/updateRole → `name`, `description`, `permissions`, `actorId`; assignPlatformRole → `role`, `assignedBy`; assignSchoolRole → `role`, `schoolId`, `assignedBy`; assignZoneRole → `role`, `zoneId`, `assignedBy`; resetPassword → the controller calls `users.resetPassword(id, password, actorId, actorEmail)` (positional — no map).
- **`requireToken(token, "identity:write")` stays the FIRST statement** in every changed method (before the body is used), unchanged.
- **DTO package:** `com.custoking.ims.identityservice.api.dto`. Records, immutable.
- **Tests use standalone MockMvc** with the advice registered: `MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ValidationExceptionHandler()).build()` — model on the existing `services/identity-service/src/test/java/com/custoking/ims/identityservice/api/IdentityControllersTest.java`.
- No schema change, no read-endpoint change; existing identity tests stay green (adapt only where a Map-body test now posts a DTO shape). `AuthController` already shows the template: `@Valid @RequestBody record IntrospectionRequest(@NotBlank String token)`.

---

### Task 1: ValidationExceptionHandler + createRole DTO (prove the contract end-to-end)

**Files:**
- Create: `services/identity-service/src/main/java/com/custoking/ims/identityservice/api/ValidationExceptionHandler.java`
- Create: `services/identity-service/src/main/java/com/custoking/ims/identityservice/api/dto/CreateRoleRequest.java`
- Modify: `services/identity-service/src/main/java/com/custoking/ims/identityservice/api/RbacReadController.java` (`createRole` only)
- Test: `services/identity-service/src/test/java/com/custoking/ims/identityservice/api/RbacValidationTest.java`

**Interfaces (produced — used by Task 2):** `ValidationExceptionHandler` (`@RestControllerAdvice`); the DTO+`@Valid`+boundary-HashMap pattern; the standalone-MockMvc-with-advice test wiring.

- [ ] **Step 1: Write the failing test `RbacValidationTest.java`**

Model MockMvc setup on `IdentityControllersTest`. Register the advice.
```java
package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.persistence.RbacCommandRepository;
import com.custoking.ims.identityservice.persistence.RbacReadRepository;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RbacValidationTest {
    RbacCommandRepository commands;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        commands = mock(RbacCommandRepository.class);
        RbacReadRepository reads = mock(RbacReadRepository.class);
        // Construct the controller the same way IdentityControllersTest does (read its constructor args);
        // set the internal token so requireToken passes. If the controller reads the required token from a
        // field/@Value, set it via the same mechanism IdentityControllersTest uses.
        RbacReadController controller = new RbacReadController(/* reads, commands, …as in IdentityControllersTest */);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ValidationExceptionHandler())
                .build();
    }

    @Test
    void createRole_blankName_returns400WithFieldError() throws Exception {
        mvc.perform(post("/roles").header("X-Identity-Service-Token", VALID_TOKEN)
                        .contentType("application/json").content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.name").exists());
        verifyNoInteractions(commands);   // validation short-circuits before business logic
    }

    @Test
    void createRole_validName_callsRepositoryWithKeys() throws Exception {
        when(commands.createRole(anyMap())).thenReturn(java.util.Map.of("id", 1));
        mvc.perform(post("/roles").header("X-Identity-Service-Token", VALID_TOKEN)
                        .contentType("application/json").content("{\"name\":\"AUDITOR\",\"description\":\"d\"}"))
                .andExpect(status().isCreated());
        var captor = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(commands).createRole(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("AUDITOR", captor.getValue().get("name"));
        org.junit.jupiter.api.Assertions.assertEquals("d", captor.getValue().get("description"));
    }
}
```
Read `IdentityControllersTest` first to copy the EXACT `RbacReadController` constructor call + how it supplies a valid `X-Identity-Service-Token` (`VALID_TOKEN`), then fill the placeholders.

- [ ] **Step 2: Run it — verify it fails** — `./mvnw.cmd -q -f services/identity-service/pom.xml test -Dtest=RbacValidationTest` → FAIL (compile: `ValidationExceptionHandler`/`CreateRoleRequest` missing; createRole still takes `Map`).

- [ ] **Step 3: Create `ValidationExceptionHandler.java`**
```java
package com.custoking.ims.identityservice.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Turns bean-validation failures into a consistent SPA-friendly 400. Copied per service. */
@RestControllerAdvice
public class ValidationExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onBodyValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return badRequest(fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> onParamValidation(ConstraintViolationException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v -> {
            String path = v.getPropertyPath().toString();
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            fieldErrors.putIfAbsent(field, v.getMessage());
        });
        return badRequest(fieldErrors);
    }

    private ResponseEntity<Map<String, Object>> badRequest(Map<String, String> fieldErrors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Validation failed");
        body.put("fieldErrors", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
```

- [ ] **Step 4: Create `CreateRoleRequest.java`**
```java
package com.custoking.ims.identityservice.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record CreateRoleRequest(@NotBlank String name, String description, List<String> permissions, Long actorId) {}
```

- [ ] **Step 5: Change `createRole` to the DTO + boundary Map** (in `RbacReadController`)
```java
@PostMapping("/roles")
@ResponseStatus(HttpStatus.CREATED)
public Object createRole(
        @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
        @jakarta.validation.Valid @RequestBody com.custoking.ims.identityservice.api.dto.CreateRoleRequest req) {
    requireToken(token, "identity:write");
    java.util.Map<String, Object> body = new java.util.HashMap<>();
    body.put("name", req.name());
    body.put("description", req.description());
    body.put("permissions", req.permissions());
    body.put("actorId", req.actorId());
    return commands.createRole(body);
}
```
(Add imports for `jakarta.validation.Valid` and the DTO instead of the fully-qualified names if you prefer — match the file's import style.)

- [ ] **Step 6: Run the test — verify it passes** — `…-Dtest=RbacValidationTest` → PASS (both cases). Then full `…/identity-service/pom.xml test` → green (if an existing test posted a Map body to `/roles`, adapt its JSON to the DTO shape — same fields).

- [ ] **Step 7: Commit**
```bash
git add services/identity-service/src/main/java/com/custoking/ims/identityservice/api/ValidationExceptionHandler.java \
        services/identity-service/src/main/java/com/custoking/ims/identityservice/api/dto/CreateRoleRequest.java \
        services/identity-service/src/main/java/com/custoking/ims/identityservice/api/RbacReadController.java \
        services/identity-service/src/test/java/com/custoking/ims/identityservice/api/RbacValidationTest.java
git commit -m "feat(identity): validated CreateRoleRequest DTO + global validation-error handler"
```

---

### Task 2: DTOs for the remaining RBAC endpoints + password-reset

**Files:**
- Create: `…/api/dto/UpdateRoleRequest.java`, `AssignPlatformRoleRequest.java`, `AssignSchoolRoleRequest.java`, `AssignZoneRoleRequest.java`, `PasswordResetRequest.java`
- Modify: `…/api/RbacReadController.java` (`updateRole`, `assignPlatformRole`, `assignSchoolRole`, `assignZoneRole`)
- Modify: `…/api/UserDirectoryController.java` (`resetPassword`)
- Test: extend `RbacValidationTest.java` + add `UserDirectoryValidationTest.java`

**Interfaces:** Consumes Task 1's `ValidationExceptionHandler` + the DTO/boundary/test pattern.

- [ ] **Step 1: Create the 5 DTO records**
```java
// UpdateRoleRequest.java — no required field (typed structure only; mirrors current optional handling)
public record UpdateRoleRequest(String description, java.util.List<String> permissions, Long actorId) {}

// AssignPlatformRoleRequest.java
public record AssignPlatformRoleRequest(@jakarta.validation.constraints.NotBlank String role, Long assignedBy) {}

// AssignSchoolRoleRequest.java
public record AssignSchoolRoleRequest(@jakarta.validation.constraints.NotBlank String role,
                                      @jakarta.validation.constraints.NotNull Long schoolId, Long assignedBy) {}

// AssignZoneRoleRequest.java
public record AssignZoneRoleRequest(@jakarta.validation.constraints.NotBlank String role,
                                    @jakarta.validation.constraints.NotNull Long zoneId, Long assignedBy) {}

// PasswordResetRequest.java
public record PasswordResetRequest(@jakarta.validation.constraints.NotBlank
                                   @jakarta.validation.constraints.Size(min = 8) String password,
                                   Long actorId, String actorEmail) {}
```
(All in package `com.custoking.ims.identityservice.api.dto`, each its own file. Add proper imports.)

- [ ] **Step 2: Convert the 4 RBAC methods** (in `RbacReadController`) — each: `@Valid @RequestBody <Dto> req`; `requireToken` first; build a `new HashMap<>()` with the exact keys, call the unchanged repo method:
  - `updateRole(token, @PathVariable Long roleId, @Valid UpdateRoleRequest req)` → map `description`/`permissions`/`actorId` → `commands.updateRole(roleId, body)`.
  - `assignPlatformRole(token, @PathVariable Long userId, @Valid AssignPlatformRoleRequest req)` → map `role`/`assignedBy` → `commands.assignPlatformRole(userId, body)`.
  - `assignSchoolRole(...)` → map `role`/`schoolId`/`assignedBy` → `commands.assignSchoolRole(userId, body)`.
  - `assignZoneRole(...)` → map `role`/`zoneId`/`assignedBy` → `commands.assignZoneRole(userId, body)`.

- [ ] **Step 3: Convert `resetPassword`** (in `UserDirectoryController`) → `@Valid @RequestBody PasswordResetRequest req`; `requireToken` first; call `users.resetPassword(id, req.password(), req.actorId(), req.actorEmail())` (positional — no map).

- [ ] **Step 4: Extend the tests** — in `RbacValidationTest` add: `assignSchoolRole_missingSchoolId_returns400` (post `{"role":"ADMIN"}` → 400, `fieldErrors.schoolId` exists, repo never called) + a valid case asserting the repo is called with `role`/`schoolId` keys. In a new `UserDirectoryValidationTest` (same standalone+advice setup, mock `UserDirectoryReadRepository`): `resetPassword_shortPassword_returns400` (`{"password":"x"}` → 400, `fieldErrors.password`) + valid → `users.resetPassword(id, "longenough", …)` called.

- [ ] **Step 5: Run** `…-Dtest=RbacValidationTest,UserDirectoryValidationTest` → PASS; then full `…/identity-service/pom.xml test` → green. If `IdentityControllersTest` (or its compat sibling) posts Map bodies to any converted endpoint, adapt those JSON payloads to the DTO shape (same field names) — do NOT weaken assertions.

- [ ] **Step 6: Commit**
```bash
git add services/identity-service/src/main/java/com/custoking/ims/identityservice/api/dto/*.java \
        services/identity-service/src/main/java/com/custoking/ims/identityservice/api/RbacReadController.java \
        services/identity-service/src/main/java/com/custoking/ims/identityservice/api/UserDirectoryController.java \
        services/identity-service/src/test/java/com/custoking/ims/identityservice/api/RbacValidationTest.java \
        services/identity-service/src/test/java/com/custoking/ims/identityservice/api/UserDirectoryValidationTest.java
git commit -m "feat(identity): validated DTOs for assign-role + password-reset write endpoints"
```

---

### Task 3: Tracker + ARCHITECTURE_REVIEW + verification

**Files:**
- Modify: `docs/superpowers/plans/2026-06-28-architecture-remediation-program.md` (Task 1.7 status)
- Modify: `ARCHITECTURE_REVIEW.md` (SEC-P1-2 note)

- [ ] **Step 1: Update the program tracker** — under **Task 1.7**, add `Status (2026-07-01): identity pilot done` referencing this plan; note: identity RBAC write endpoints (createRole/updateRole/assign platform+school+zone) + password-reset now use `@Valid` DTO records with a `{message, fieldErrors}` 400 via a per-service `ValidationExceptionHandler`; boundary-only (repos unchanged); remaining identity endpoints + the other services replicate the pattern (rolling). Append only.

- [ ] **Step 2: Update `ARCHITECTURE_REVIEW.md`** — near `SEC-P1-2` (grep; else the input-validation/DTO section), append a dated (2026-07-01) note: the validated-DTO pattern + error contract are piloted in identity; other services `[EXPAND per service]`. Append only.

- [ ] **Step 3: Verification**
```bash
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -f services/identity-service/pom.xml test
```
Expected: green, including `RbacValidationTest` + `UserDirectoryValidationTest` and all pre-existing identity tests.

- [ ] **Step 4: Commit** — `git add` the two docs; `git commit -m "docs(ops): input-validation DTOs — identity pilot (Task 1.7) tracker + architecture-review"`

---

## Self-Review

**Spec coverage:**
- 5 RBAC write endpoints + password-reset → DTOs + `@Valid` + boundary → Tasks 1 (createRole), 2 (rest). ✓
- `{message, fieldErrors}` contract via `@RestControllerAdvice` → Task 1 Step 3. ✓
- Advice handles only MethodArgumentNotValid/ConstraintViolation (not ResponseStatusException) → Task 1 Step 3 (only those two `@ExceptionHandler`s). ✓
- Boundary-only (rebuild the exact Map keys; repos unchanged) → Global Constraints + Tasks 1/2. ✓
- `requireToken` stays first → Global Constraints + each converted method. ✓
- Tests: invalid→400 fieldErrors + repo-not-called; valid→repo called with keys → Task 1 Step 1, Task 2 Step 4. ✓
- Existing tests adapted, stay green → Task 1 Step 6 / Task 2 Step 5. ✓
- Deferred endpoints (disable/enable, provisioning, compat) not in scope → Spec Non-goals; plan touches only the 6 endpoints. ✓
- No schema change → confirmed (API-layer only). ✓

**Placeholder scan:** the advice, all 6 DTOs, the createRole conversion, and the test bodies are given in full. The one implementer-resolved detail is the exact `RbacReadController` constructor call + `VALID_TOKEN` supply in the test (Task 1 Step 1 says read `IdentityControllersTest` and copy it) — named, not vague.

**Type/name consistency:** DTOs in `…api.dto`; `ValidationExceptionHandler` in `…api`; boundary keys (`name`/`description`/`permissions`/`actorId`; `role`/`schoolId`/`zoneId`/`assignedBy`; positional resetPassword) match the repo reads; test wiring (`standaloneSetup(...).setControllerAdvice(new ValidationExceptionHandler())`) consistent across Tasks 1–2.

## Risks

- **Boundary Map key drift** → the "valid payload" test asserts the repo is called with the expected keys (Task 1 Step 1, Task 2 Step 4).
- **Existing Map-body test breakage** → Tasks 1/2 adapt those JSON payloads (same fields) without weakening.
- **Advice not registered in standalone MockMvc** → `.setControllerAdvice(new ValidationExceptionHandler())` in every validation test (Global Constraints).
- **`@Valid` on a record with no constraints is a no-op** (updateRole) → intentional; the DTO still gives type-safety; documented in the spec.
- **`requireToken` order** → each converted method keeps it as the first statement so a bad token still 403s before validation (Global Constraints).
