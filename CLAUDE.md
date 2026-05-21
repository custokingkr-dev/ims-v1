# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Repository Layout

```
ims-v1/
├── backend/          Spring Boot 3.4 + Java 21 API
├── frontend/         React 18 + Vite + TypeScript SPA
├── deploy/gcp/       Cloud Run / GCP deployment scripts
├── docker-compose.yml
└── PRD.md            Full product requirements document
```

---

## Commands

### Backend (`cd backend`)

```bash
# Run locally (connects to localhost:5432/postgres by default)
mvn clean spring-boot:run

# Run with explicit secrets (required — startup fails without them)
APP_JWT_SECRET=your-32-char-secret APP_AADHAR_SECRET=your-16-char-secret mvn spring-boot:run

# Unit tests only (fast, no Docker needed)
mvn test

# Unit + integration tests (Testcontainers spins up a real Postgres)
mvn verify -Pci

# Run a single test class
mvn test -Dtest=AuthServiceTest

# Run a single integration test
mvn verify -Pci -Dit.test=AuthIntegrationTest

# Build Docker image
docker build -t custoking-ims-backend .

# OWASP dependency check (also runs in CI)
mvn dependency-check:check
```

### Frontend (`cd frontend`)

```bash
npm install
npm run dev        # dev server at http://localhost:5173 (proxies /api → localhost:8080)
npm run build      # production build to dist/
npm run preview    # serve production build locally
```

### Full stack (root)

```bash
docker-compose up --build    # postgres + backend + frontend on ports 5432/8080/80
```

---

## Default Credentials

- Superadmin: `superadmin@custoking.com` / set via `SUPERADMIN_PASSWORD` env var
- Demo admin: `admin@demo.custoking.com` / set via `DEMO_ADMIN_PASSWORD` env var (only created when this var is set)

---

## Architecture

### Request Pipeline (filter order matters)

Every HTTP request passes through filters in this sequence:

1. **`RequestCorrelationFilter`** — sets `X-Request-Id` MDC field
2. **`SecurityHeadersFilter`** — adds CSP, HSTS, X-Frame-Options headers
3. **`LoginRateLimiter`** — 5 failed attempts / 15 min per email (in-memory)
4. **`JwtAuthFilter`** — validates JWT, loads `AppUserDetails` (with permissions `Set<String>`)
5. **`TenantResolverFilter`** — resolves `schoolId` from RBAC assignments → sets `TenantContext` (ThreadLocal)
6. **Spring Security RBAC** — `@PreAuthorize("@rbacService.hasPermission(authentication, 'code')")`
7. **Controller** — calls `moduleService.requireModule(TenantContext.get(), Module.X)` before any business logic
8. **Service / Repository** — all DB queries include `WHERE school_id = TenantContext.get()`

### Multi-Tenancy

Isolation is **application-level only** — PostgreSQL Row-Level Security was disabled in V117. `TenantContext` is a `ThreadLocal` holding the current `schoolId` (null for platform admins). `TenantDataSourceConfig` also writes `app.current_school_id` as a Postgres session variable on every connection borrow (retained for future RLS re-enablement). Platform admins (`isSuperadmin() == true` on `TenantScope`) bypass all tenant filters.

### RBAC

**Never use `hasRole()` or check `user.getRole()` in business logic.** The legacy `role` column on `app_users` is display-only. Authorization flows entirely through:

- `RbacService.hasPermission(authentication, "permission:code")` — used in `@PreAuthorize`
- Permission codes are rows in the `permissions` table; role-permission mapping is in `role_permissions`
- At login, all permission codes for the user are loaded into `AppUserDetails.permissions` (fast-path `Set<String>`)
- `UserRoleAssignmentEntity.isEffective()` — checks `active && validFrom <= now && validUntil >= now`

All new `@PreAuthorize` expressions must use `PermissionConstants.*` string constants.

### Module Entitlements

Schools subscribe to modules (STUDENTS, FEES, ATTENDANCE, ORDERS, FIREFIGHTING, PAYMENTS, INVOICES, REPORTS). Every module controller entry-point calls:

```java
moduleService.requireModule(TenantContext.get(), Module.STUDENTS);
```

`requireModule(null, module)` is a no-op — platform admins bypass entitlement checks.

### Package Structure

Two coexisting package styles — **do not create more**:

- **`service/`** — old flat services (authoritative implementations used by all controllers)
- **`{domain}/domain/`** — new domain services (`catalog/`, `fees/`, `firefighting/`, `payments/`, `schools/`, `students/`) — compile but not yet wired to controllers
- **`auth/`** — fully migrated (old `controller/AuthController.java` was deleted)

When adding new features, prefer the new domain package style. When extending existing features, use the existing `service/` class to avoid duplicate Spring bean names.

### Frontend Auth

- Access token: **memory only** (`let accessToken` in `api.ts`) — never localStorage
- Refresh token: HttpOnly `Secure SameSite=Strict` cookie at path `/api/v1/auth`
- On mount, `AuthProvider` calls `refreshToken()` to restore session from cookie
- 401 interceptor auto-retries once with a fresh token; on failure redirects to `/login`
- Permissions loaded from `/api/v1/auth/me`, stored in `AuthContext.permissions: string[]`
- `usePermissions()` hook: `can(code)`, `canAny(codes[])`, `canAll(codes[])`

---

## Key Non-Obvious Facts

### Entity APIs

| Entity | Non-obvious API |
|--------|----------------|
| `StudentEntity` | PK is `Long id`; admission field is `admissionNo` (not `admissionNumber`) |
| `FirefightingRequestEntity` | PK is `String code` — use `getCode()`, not `getId()` |
| `FeeAssignmentEntity` | Amounts are `long` in **paise**; `getNetPayable()` / `getPaidAmount()` — no `getAmount()` |
| `PaymentRecordEntity` | No `status`, no `reconciliationStatus`, no `paymentMethod` field |
| `AppUserEntity` | `role` column is legacy display-only; actual auth via RBAC tables |
| `AuthUser` model record | Accessor is `userId()` not `id()` |

### Repository Gotchas

- `FeeAssignmentRepository.findByStudent_IdAndAcademicYear_Id` returns `Optional` (not `List`)
- `PaymentRecordRepository.sumAmountBySchoolId(Long)` returns `long`
- `FirefightingRequestEntity` PK queries use `findByCode(String)` not `findById(Long)`

### Configuration

- Startup **fails** if `APP_JWT_SECRET` (≥32 chars) or `APP_AADHAR_SECRET` (≥16 chars) are absent or weak — enforced by `ApplicationSecurityValidator`
- `app.bootstrap-users=true` (default) seeds demo data including students, fees, and catalog orders on first run
- Flyway runs as a DBA user (`FLYWAY_USERNAME`) separate from the app user (`SPRING_DATASOURCE_USERNAME`) — the app user needs only SELECT/INSERT/UPDATE/DELETE
- Swagger UI is disabled in production (`SWAGGER_ENABLED=false`) and protected by `system:swagger` permission when enabled

### Flyway Migrations

Current highest migration: **V125**. New migrations start at V126+. Never modify existing migrations.

---

## Adding a New Endpoint (Checklist)

1. Create/update the entity in `entity/` if schema changes needed → add a `V126__...sql` migration
2. Add `@PreAuthorize(PermissionConstants.YOUR_PERM)` on the controller method
3. Add permission constant to `PermissionConstants.java`
4. Add/seed the permission row in the next migration (`permissions` table)
5. Assign permission to appropriate roles in `role_permissions`
6. Call `moduleService.requireModule(TenantContext.get(), Module.X)` for module-gated endpoints
7. Write to audit log via `auditLogService.record(b -> b.action("...").build())`
8. Add `@Valid` on the request DTO class

---

## Testing

- **Unit tests**: Mockito — files named `*Test.java`, run with `mvn test`
- **Integration tests**: Testcontainers PostgreSQL — files named `*IntegrationTest.java`, run with `mvn verify -Pci`
- `AbstractIntegrationTest` provides the shared `@SpringBootTest` + Testcontainers setup
- `ControllerSecurityTest`: when mocking a user principal with `SecurityMockMvcRequestPostProcessors.user()`, pass the in-memory permissions `Set` explicitly — mock principals have no DB-backed assignments

---

## CI/CD

`.github/workflows/ci.yml` runs on every push:
1. Backend: `mvn verify -Pci` (unit + integration tests + JaCoCo coverage)
2. OWASP dependency-check (fails on CVSS ≥ 7; suppressions in `.owasp-suppressions.xml`)
3. Frontend: `npm ci && npm run build`
4. Docker smoke test: builds image, starts container, hits `/actuator/health`
