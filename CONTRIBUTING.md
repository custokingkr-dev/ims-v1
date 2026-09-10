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
main         — production-ready; promoted from dev by PR
dev          — development integration branch; deploys to custoking-dev
codex/<name> — individual features/fixes; branch from dev
```

- Branch from `dev` (or `main` for hotfixes).
- Keep branches short-lived. Rebase before opening a PR to avoid merge conflicts.
- Delete branches after merging.
- Promotion to production is a `dev` -> `main` pull request, not a direct push.

---

## Local setup

```bash
# Backend (no global Maven required — wrapper downloads Maven 3.9.9 on first run).
# Run from the REPOSITORY ROOT: it is a 5-module Maven reactor (services/*-service).
# There is no `backend/` directory.
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

- **There is no global migration counter.** Each service — and within
  `school-core-service`, `platform-service` and `operations-service`, each *schema* — has its
  own independent Flyway sequence starting at `V1`. Twelve sequences exist today:

  | Service | Schema | Highest |
  |---|---|---|
  | billing-service | (service root) | V7 |
  | identity-service | (service root) | V6 |
  | operations-service | firefighting | V11 |
  | operations-service | workflow | V5 |
  | platform-service | audit | V1 |
  | platform-service | notification | V10 |
  | platform-service | reporting | V29 |
  | school-core-service | attendance | V9 |
  | school-core-service | catalog | V8 |
  | school-core-service | fee | V9 |
  | school-core-service | student | V35 |
  | school-core-service | tenant_school | V26 |

  Always `ls` the target directory and take the next number **in that sequence**. Picking a
  number from another sequence is silently destructive: a `V126` dropped into `catalog`
  (highest `V8`) applies fine, and then every later `V9`…`V125` is ignored as out-of-order.
- **Never modify an existing migration** after it has been applied to any environment.
- Migration files: `services/<service>/src/main/resources/db/migration/[<schema>/]V<N>__<description>.sql`
- Naming: `V27__add_notification_templates.sql` (underscores, lowercase words)
- Every migration must be idempotent where possible (use `IF NOT EXISTS`, `DO $$ ... $$`).
- Test migrations locally: `mvn flyway:migrate` against a fresh DB. (There is no
  `db-migration-test` CI job; migrations are exercised by the Testcontainers integration
  tests inside `service-test`.)

---

## Tests

### Backend

```bash
# Unit tests (no Docker required) — from the repository root
./mvnw -B test

# A single service
./mvnw -B -pl services/school-core-service test

# Integration tests (Testcontainers — Docker required)
./mvnw -B verify -Pci
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

1. Open your PR against `dev` (or `main` for hotfixes). `CI / PR` only runs for
   pull requests targeting `main` or `dev`.
2. Fill in the PR template completely — incomplete PRs will be returned.
3. CI must be green before review. **Not currently enforced** — `main` and `dev` have no
   branch protection, so a red PR *can* be merged (and PR #228 was). Enabling the guard is
   ready to go: see `docs/branch-protection-proposal.md` and
   `scripts/enable-branch-protection.sh`. `CI / PR` runs:
   - `service-test` and `docker-build`, per affected service
   - `secret-scan`, `duplicate-class-drift`, `static-architecture-audits`,
     `privacy-technical-controls`, `promotion-source-policy`
   - CodeQL `analyze` runs from the separate `Security / CodeQL` workflow
   - Trivy runs inside `docker-build` on **every** pull request and fails the
     build on any HIGH or CRITICAL finding — not only on pushes to `main`
4. **No approval is required or enforced today**, and there is no `CODEOWNERS` file. The
   repository is effectively single-maintainer, so a required-review rule would deadlock
   merges (GitHub forbids approving your own PR); the proposal above deliberately requires
   passing status checks instead.
5. Merge commits are enabled and are what `main` actually contains. Squash-merge is *not*
   enforced, and `main` history is not linear.

---

## Security guidelines

- **Never commit secrets** — `.env`, `.env.*`, JWTs, passwords, API keys.
  Gitleaks scans every commit; it will fail CI and alert the team.
- **No plain-text password utilities.** All password handling goes through Spring Security's
  `BCryptPasswordEncoder`.
- **No hardcoded role names** in business logic — see RBAC rules above.
- **Validate all inputs** with Bean Validation (`@Valid`, `@NotBlank`, `@Size`, etc.) on
  every request DTO.
- Dependency vulnerability suppressions require:
  - A CVE ID or NVD reference
  - A written justification
  - An expiry date no more than 6 months out
