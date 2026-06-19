# Contributing to Custoking IMS

Thank you for contributing! This guide covers the technical rules that keep the codebase
consistent and production-safe. Please read it before opening a PR.

---

## Table of contents

1. [Branch strategy](#branch-strategy)
2. [Local setup](#local-setup)
3. [Code rules — Backend](#code-rules--backend)
4. [Code rules — Frontend](#code-rules--frontend)
5. [Adding a new endpoint (checklist)](#adding-a-new-endpoint-checklist)
6. [Flyway migrations](#flyway-migrations)
7. [Tests](#tests)
8. [Pull request process](#pull-request-process)
9. [Security guidelines](#security-guidelines)

---

## Branch strategy

```
main           — production-ready; protected; requires PR + passing CI
feature/v1     — current development integration branch
feature/<name> — individual features/fixes; branch from feature/v1
```

- Branch from `feature/v1` (or `main` for hotfixes).
- Keep branches short-lived. Rebase before opening a PR to avoid merge conflicts.
- Delete branches after merging.

---

## Local setup

```bash
# Backend (no global Maven required — wrapper downloads Maven 3.9.9 on first run)
cd backend
APP_JWT_SECRET=your-32-char-secret \
APP_AADHAR_SECRET=your-16-char-secret \
SUPERADMIN_PASSWORD=your-password \
./mvnw spring-boot:run

# Frontend
cd frontend
npm ci
npm test        # Vitest unit tests
npm run dev     # dev server at http://localhost:5173
```

See `README.md` for the full Docker Compose setup.

---

## Code rules — Backend

### Authorization

**Never use `hasRole()` or inspect `user.getRole()` in business logic.**
Authorization flows entirely through the RBAC permission system.

```java
// ✅ Correct
@PreAuthorize(PermissionConstants.STUDENT_CREATE)

// ❌ Wrong — do not write raw SpEL strings
@PreAuthorize("@rbacService.hasPermission(authentication, 'student:create')")

// ❌ Wrong — no role checks
@PreAuthorize("hasRole('ADMIN')")
```

Every `@PreAuthorize` on a controller method **must** reference a constant from
`PermissionConstants.java`. If the constant doesn't exist yet, add it first.

### Tenant isolation

Every repository query that touches tenant-scoped data **must** include a `school_id` predicate:

```java
// ✅ Correct
List<StudentEntity> findBySchoolId(Long schoolId);

// ❌ Missing school scope — returns data across all tenants
List<StudentEntity> findAll();
```

Obtain the current school from `TenantContext.get()` — never from request parameters.

### Module entitlement

Every module-gated controller entry-point must call:

```java
moduleService.requireModule(TenantContext.get(), Module.STUDENTS);
```

before any business logic. `requireModule(null, module)` is a no-op for platform admins.

### Entity API non-obvious facts

| Entity | Non-obvious API |
|--------|----------------|
| `StudentEntity` | PK is `Long id`; admission field is `admissionNo` (not `admissionNumber`) |
| `FirefightingRequestEntity` | PK is `String code` — use `getCode()`, not `getId()` |
| `FeeAssignmentEntity` | Amounts in **paise** (not rupees); use `getNetPayable()` / `getPaidAmount()` |
| `PaymentRecordEntity` | No `status`, no `reconciliationStatus`, no `paymentMethod` field |
| `AppUserEntity` | `role` column is legacy display-only |
| `AuthUser` record | Accessor is `userId()` not `id()` |

### Audit logging

Every state-changing operation must write to the audit log:

```java
auditLogService.record(b -> b
    .action("student.created")
    .entityType("Student")
    .entityId(student.getId().toString())
    .build());
```

### Package style

Two coexisting package styles — **do not introduce a third**:

- `service/` — existing flat services; extend when modifying existing features
- `{domain}/domain/` — new domain packages (`catalog/`, `fees/`, etc.); use for new features

### No placeholder implementations

Do not merge stubs that return hard-coded data, `TODO` methods, or `throw new UnsupportedOperationException()`.
If a feature is not ready, keep it off the branch.

---

## Code rules — Frontend

- All CSS classes use the `ck-` prefix. Do not introduce external component libraries.
- All CSS values must reference `:root` CSS variables — no hard-coded colors or spacing.
- TypeScript strict mode is enabled — no `any` types without a comment explaining why.
- Access tokens live in memory only (`api.ts`). Never write tokens to `localStorage`.
- Gate every action behind `usePermissions().can('permission:code')`.

---

## Adding a new endpoint (checklist)

1. **Schema change?** → Add a `V<next>__<description>.sql` Flyway migration.
2. Add permission code to `permissions` table in the same migration.
3. Assign to role(s) via `role_permissions` in the same migration.
4. Add the Java constant to `PermissionConstants.java`.
5. Add `@PreAuthorize(PermissionConstants.YOUR_CONST)` on the controller method.
6. Add `@Valid` on the request DTO.
7. Call `moduleService.requireModule(TenantContext.get(), Module.X)` if module-gated.
8. Write to audit log via `auditLogService.record(...)`.
9. Add at least one unit test and one integration test.
10. Confirm `WHERE school_id = ...` is in every repository query.

---

## Flyway migrations

- Current highest migration: **V125**. New migrations must start at **V126+**.
- **Never modify an existing migration** after it has been applied to any environment.
- Migration files: `backend/src/main/resources/db/migration/V<N>__<description>.sql`
- Naming: `V126__add_notification_templates.sql` (underscores, lowercase words)
- Every migration must be idempotent where possible (use `IF NOT EXISTS`, `DO $$ ... $$`).
- Test migrations locally: `mvn flyway:migrate` against a fresh DB, or via the
  `db-migration-test` CI job.

---

## Tests

### Backend

```bash
# Unit tests (no Docker required)
cd backend && mvn test

# Integration tests (Testcontainers — Docker required)
cd backend && mvn verify -Pci
```

- Unit tests: `*Test.java`, Mockito mocks.
- Integration tests: `*IntegrationTest.java`, extend `AbstractIntegrationTest`.
- Security tests: use `SecurityMockMvcRequestPostProcessors.user()` with an explicit
  `Set<String>` of permissions — mock principals have no DB-backed assignments.

### Frontend

```bash
cd frontend && npm test        # Vitest unit tests (no browser required)
cd frontend && npm run build   # TypeScript type-check + Vite production bundle
```

---

## Pull request process

1. Open your PR against `feature/v1` (or `main` for hotfixes).
2. Fill in the PR template completely — incomplete PRs will be returned.
3. CI must be green before review:
   - backend-test, db-migration-test, frontend-build, secret-scan
   - owasp-scan and trivy-scan run on push to main/master only
4. One approval required from a code owner before merging.
5. Squash-merge preferred to keep `main` history linear.

---

## Security guidelines

- **Never commit secrets** — `.env`, `.env.*`, JWTs, passwords, API keys.
  Gitleaks scans every commit; it will fail CI and alert the team.
- **No plain-text password utilities.** All password handling goes through Spring Security's
  `BCryptPasswordEncoder`.
- **No hardcoded role names** in business logic — see RBAC rules above.
- **Validate all inputs** with Bean Validation (`@Valid`, `@NotBlank`, `@Size`, etc.) on
  every request DTO.
- Dependency vulnerability suppressions (`backend/.owasp-suppressions.xml`) require:
  - A CVE ID or NVD reference
  - A written justification
  - An expiry date no more than 6 months out
