# Design — Input Validation: Validated DTOs (Phase 1, Task 1.7 — identity pilot)

> Review ID: `SEC-P1-2`. Parent program plan: `docs/superpowers/plans/2026-06-28-architecture-remediation-program.md` (Task 1.7, "(rolling) [EXPAND per service]").
> Scope: **pilot on identity-service** — establish the reusable pattern + validation-error contract; other services replicate in later increments.
> Status: **approved design** → next step is the bite-sized implementation plan.

## Problem

identity-service's write endpoints accept `@RequestBody Map<String,Object>` and reach into the map with `body.get("field")` helpers (`text`, `longValue`, `requiredString`). Untyped bodies mean: no schema, no automatic validation, silent nulls/`ClassCastException` on wrong types, and per-endpoint ad-hoc checks. There is no consistent 400 response for a malformed payload. Task 1.7 replaces these with validated DTO records (`@Valid` + jakarta.validation) and a single validation-error handler returning an SPA-friendly 400 — piloted on identity so the pattern + error contract are proven before rolling to other services.

## Goals / Non-goals

**Goals**
- Replace the `Map<String,Object>` bodies on identity's **RBAC write endpoints** (`createRole`, `updateRole`, `assignPlatformRole`, `assignSchoolRole`, `assignZoneRole`) and **`password-reset`** with `@Valid` DTO records carrying jakarta.validation constraints.
- Add a per-service `@RestControllerAdvice` that turns a validation failure into a consistent **400** body: `{ "message": "Validation failed", "fieldErrors": { field: message } }` (SPA-compatible: keeps `message`; adds per-field detail).
- Enforce validation at the **controller boundary** with minimal blast radius: the controller converts the validated DTO back into the `Map` the existing `RbacCommandRepository`/`UserDirectoryReadRepository` already consume (no repository/service rewrite).
- Prove it with MockMvc tests (invalid → 400 `fieldErrors`; valid → passes through), and keep existing identity tests green.

**Non-goals (deferred / out of scope)**
- The remaining identity write endpoints (`disable`/`enable` — optional bodies carrying only actor metadata; the provisioning + `compat` user-creation endpoints) — same pattern, deferred to the next identity increment.
- Threading DTOs **all the way through** the repositories (typed repo methods) — cleaner but a bigger change; boundary-only is the pilot's deliberate choice.
- Rolling the pattern to the other ~11 services — each is its own later increment (add the validation starter + DTOs + a copy of the advice).
- Read endpoints, query-param validation beyond what a DTO covers, and any schema change (this is API-layer only).

## Decisions (resolved during brainstorming)

1. **Pilot = identity-service.** It already has `spring-boot-starter-validation` + one `@Valid` DTO (login), and its RBAC write endpoints are clear `Map`-bodied targets.
2. **Error contract = `{ message, fieldErrors }`.** `message` (`"Validation failed"`) preserves the SPA's existing `error.response.data.message` display; `fieldErrors` is a `Map<String,String>` (field → first message). Applies to `MethodArgumentNotValidException` (body `@Valid`) and `ConstraintViolationException` (any `@Validated` param). Existing `ResponseStatusException` throws are NOT intercepted (their behavior is unchanged).
3. **DTO depth = controller boundary only.** Controllers accept `@Valid <Cmd>Request` records, then build the exact `Map<String,Object>` the existing repository methods read (same keys), and call them unchanged. Validation is enforced before any business logic runs.

## In-scope endpoints + DTOs (identity)

| Endpoint | Controller method | DTO (record) | Constraints |
|---|---|---|---|
| `POST /rbac/roles` | `RbacReadController.createRole` | `CreateRoleRequest(String name, String description, Long actorId)` | `@NotBlank name` |
| `PUT /rbac/roles/{roleId}` | `updateRole` | `UpdateRoleRequest(String description, List<String> permissions, Long actorId)` | none required (typed structure only — mirrors current optional handling; avoids rejecting a description-only update) |
| `POST /rbac/users/{userId}/roles/platform` | `assignPlatformRole` | `AssignPlatformRoleRequest(String role, Long assignedBy)` | `@NotBlank role` |
| `POST /rbac/users/{userId}/roles/school` | `assignSchoolRole` | `AssignSchoolRoleRequest(String role, Long schoolId, Long assignedBy)` | `@NotBlank role`, `@NotNull schoolId` |
| `POST /rbac/users/{userId}/roles/zone` | `assignZoneRole` | `AssignZoneRoleRequest(String role, Long zoneId, Long assignedBy)` | `@NotBlank role`, `@NotNull zoneId` |
| `POST /users/{id}/password-reset` | `UserDirectoryController.resetPassword` | `PasswordResetRequest(String password, Long actorId, String actorEmail)` | `@NotBlank password`, `@Size(min=8) password` |

(Exact fields/keys are taken from what each repository method reads today — `RbacCommandRepository` reads `name`/`description`/`actorId`/`role`/`schoolId`/`zoneId`/`assignedBy`/`permissions`; `UserDirectoryReadRepository.resetPassword` reads `password`/`actorId`/`actorEmail`. The DTO→Map conversion at the boundary MUST preserve those exact keys.)

## Components (identity-service, package `…identityservice.api.dto` + `…api`)

### 1. DTO records — `…identityservice/api/dto/*.java`
One `record` per endpoint (table above), fields matching the current map keys, jakarta.validation annotations on the required fields. Records are immutable and self-documenting; a controller reads `req.name()` etc.

### 2. `@Valid` on the controller methods
Each in-scope method's `@RequestBody Map<String,Object> body` becomes `@Valid @RequestBody <Cmd>Request req`. The method then builds the `Map` the repository expects (null-safe — a `HashMap`, not `Map.of`, since optional fields may be null) and calls the unchanged repository method. The `X-Identity-Service-Token` header check (`requireToken`) stays exactly as-is, before the body is used.

### 3. `ValidationExceptionHandler` — `…identityservice/api/ValidationExceptionHandler.java` (`@RestControllerAdvice`)
```java
@ExceptionHandler(MethodArgumentNotValidException.class)  // @Valid body
  → 400 { "message": "Validation failed", "fieldErrors": { <field>: <default message>, … } }
@ExceptionHandler(ConstraintViolationException.class)     // @Validated param (if any)
  → 400 same shape (field = last path node)
```
`fieldErrors` keeps the first message per field. It does NOT handle `ResponseStatusException` or generic `Exception` (existing behavior preserved). Copied per-service in later increments (no shared module — consistent with the codebase convention).

## Data flow

```
POST /rbac/roles  {name:"", description:"x"}   (invalid — blank name)
  → requireToken(...) passes
  → @Valid CreateRoleRequest binding → MethodArgumentNotValidException (name @NotBlank)
  → ValidationExceptionHandler → 400 { message:"Validation failed", fieldErrors:{ name:"must not be blank" } }
POST /rbac/roles  {name:"AUDITOR"}   (valid)
  → @Valid passes → controller builds Map{name:"AUDITOR", description:null, actorId:null}
  → commands.createRole(map)  (unchanged) → 201
```

## Error handling

| Case | Response |
|---|---|
| Missing/blank/invalid-type field on an in-scope endpoint | 400 `{ message:"Validation failed", fieldErrors:{…} }` |
| Malformed JSON (unparseable body) | Spring's default 400 (`HttpMessageNotReadableException`) — optionally handled by the advice for a consistent `message`; acceptable either way for the pilot |
| Missing/invalid internal service token | unchanged (`requireToken` → 403/401 `ResponseStatusException`) — runs before validation |
| Valid payload | passes through to the existing repository/service |

## Testing strategy

- **Per in-scope endpoint (MockMvc, standalone or `@WebMvcTest` with the service mocked + a valid token):**
  - invalid payload (missing/blank required field; wrong type) → **400**, body has `message="Validation failed"` and the offending field in `fieldErrors`; the mocked command repository is **never called** (validation short-circuits before business logic).
  - valid payload → the controller calls the (mocked) repository once with a `Map` carrying the expected keys; returns the normal status (201/200/204).
- **`ValidationExceptionHandler` body-shape test** — a MethodArgumentNotValidException maps to the exact `{message, fieldErrors}` JSON.
- **Existing identity controller/RBAC tests** — adapt call sites from `Map` bodies to the DTO JSON (do not weaken assertions); keep green.
- No DB/Testcontainers needed (API-layer only).

## Rollout

Pure API-layer hardening; no migration, no schema change, no runtime-behavior change for valid payloads. Backward compatible for well-formed clients; malformed clients now get a clean 400 instead of a 500/silent-null. Other services replicate by: adding `spring-boot-starter-validation` (identity already has it), DTOs for their write endpoints, `@Valid`, and a copy of `ValidationExceptionHandler`.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Boundary Map conversion drops/renames a key the repo reads → silent behavior change | The DTO→Map keys are copied verbatim from what each repo method reads today; the "valid payload" test asserts the repo is called with the expected keys |
| The advice shadows or reformats existing `ResponseStatusException` errors → SPA breakage | The advice handles ONLY `MethodArgumentNotValidException`/`ConstraintViolationException`; ResponseStatusException is untouched |
| SPA can't read the new 400 | Body keeps `message` (what api.ts reads); `fieldErrors` is additive |
| `Map.of` NPE on null optional fields | Build the boundary map with a `HashMap` (null-tolerant) |
| Over-tight constraints reject currently-valid callers | Constraints mirror what the code already treats as required (`requiredString(...)`); optional fields stay optional |

## Open items (deferred, not blocking)

- Remaining identity write endpoints (disable/enable, provisioning, compat user-creation) — next identity increment, same pattern.
- Thread DTOs through the repositories (typed methods) — a later cleanliness pass.
- Roll to the other services — one increment each (`[EXPAND per service]`).
- A shared validation-error contract doc other services copy from (once ≥2 services have it).
