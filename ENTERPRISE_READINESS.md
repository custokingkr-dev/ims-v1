# Custoking IMS — Enterprise Readiness Assessment

> Generated: 2026-05-22 | Branch: `feature/v1` | Highest migration: **V127**

---

## 1. Status Table

| # | Area | Status | Key Files | Gap Description |
|---|------|--------|-----------|-----------------|
| 1 | **Pagination** | ⚠️ Partial | `AuditLogController`, `PageResponse.java`, `SupplyController`, `FirefightingController`, `StudentPhotoController` | Only audit logs + orders + FF use real `Pageable`. `StudentService.studentsPage` does **in-memory** slice (O(N) load). Fee list, user list, invoice list fully unbounded. |
| 2 | **DTO coverage** | ⚠️ Partial | `dto/` (17 files), 418 `Map<String,Object>` usages | 17 DTOs exist for inputs; API responses still use raw `Map`. `PageResponse<T>` introduced. New endpoints must use typed DTOs. |
| 3 | **RBAC – authorization** | ✅ Done | `PermissionConstants`, `RbacService`, `RbacPermissionEvaluator`, all controllers | Zero `hasRole`/`hasAnyRole` in business logic. Every controller method has `@PreAuthorize`. Permission-code based. |
| 4 | **RBAC – legacy fields** | ⚠️ Partial | `AppUserEntity.role/branchId/branchName` | Fields still written on user create; no `@Deprecated` Javadoc; no guard preventing business logic from reading them for authorization. |
| 5 | **RBAC – privilege escalation guard** | ❌ Missing | `RbacService.assignSchoolRole` | No check preventing a user from assigning a role with more privileges than their own. |
| 6 | **RBAC – audit completeness** | ⚠️ Partial | `AuditLogService`, `AuthService` | `LOGIN_SUCCESS`, `LOGIN_FAILURE` ✅. `STATUS_TRANSITION` for FF + Orders ✅. Missing: `STUDENT_CREATED`, `STUDENT_UPDATED`, `STUDENT_DELETED`, `FEE_COLLECTED`, `PAYMENT_RECORDED`, `SCHOOL_CREATED`, `ADMIN_USER_CREATED`, `ACCESS_DENIED`. |
| 7 | **Tenant isolation** | ⚠️ Partial | `TenantContext`, `TenantResolverFilter`, `TenantScopeService` | App-layer only (RLS disabled V117). Ownership checks on reads via `assertSchoolOwnership`. No `// TENANT-SAFE` annotations. No ArchUnit test. Create/update paths inconsistently protected. |
| 8 | **@Transactional** | ✅ Done | All service read methods | `@Transactional(readOnly=true)` on 51 read methods. `ReadTransactionTest` verifies. |
| 9 | **SchoolOnboardingService** | ❌ Missing | `SchoolService` (300 lines) | Onboarding logic embedded in `SchoolService`. No dedicated service. No `GET /schools/{id}/onboarding/status` endpoint. |
| 10 | **Workflow state machine** | ⚠️ Partial | `WorkflowService`, `WorkflowController`, 4 workflow entities | No `WorkflowTransitionValidator`. Status strings as literals. No `WorkflowStatusConstants`. |
| 11 | **Frontend shared layer** | ❌ Missing | `components/` (5 files), `hooks/` (2 files) | No `shared/` directory. No `Can.tsx`. No `StatusBadge`, `DataTable`, `EmptyState`, `SearchInput`. `useDebounce`, `usePagination` absent. |
| 12 | **Frontend permissions** | ⚠️ Partial | `hooks/usePermissions.ts`, `utils/permissions.ts` | Hook and utils exist but in wrong location. No `Can.tsx` permission-gate. Permission codes not mirrored from Java in a typed constant file. |
| 13 | **Frontend feature folders** | ⚠️ Partial | `features/{fees,firefighting,rbac,students,workspace}/` | Feature dirs exist but thin. Panels are monoliths: `HomePanel` 858 lines, `FeeStructurePanel` 582, `FeesPanel` 477. |
| 14 | **Frontend tests** | ⚠️ Partial | `hooks/usePermissions.test.ts`, `vitest.config.ts` | Vitest + RTL configured. Comprehensive `usePermissions` test exists. 0 component tests. `npm test` not in CI. |
| 15 | **Backend integration tests** | ⚠️ Partial | `integration/{AuthIntegrationTest,CrossSchoolAccessTest,RbacIntegrationTest}` | 12 test files. No student/fee/order/workflow tests. JaCoCo gate at 30%. |
| 16 | **DB indexes** | ⚠️ Partial | V3, V6, V114, V127 | `catalog_orders(school_id, created_at DESC)` ✅ V127. `fee_assignments` and `students.fee_status` not indexed for faceted queries. |
| 17 | **API standards** | ⚠️ Partial | All controllers | Versioned `/api/v1` ✅. `@Valid` on DTO inputs ✅. `GlobalExceptionHandler` consistent envelope ✅. Missing: `ApiErrorResponse` record type, `X-Request-Id` echo in response, sort-field allowlist. |
| 18 | **Secrets / configuration** | ✅ Done | `application-prod.yml`, `ApplicationSecurityValidator` | No hardcoded secrets. Startup fails without required secrets. Backend startup bootstrap has been removed. |
| 19 | **CI/CD pipeline** | ⚠️ Partial | `.github/workflows/ci.yml` | JaCoCo, OWASP, Gitleaks, Trivy, npm audit, fresh-DB Flyway ✅. `npm test` (Vitest) **not wired** in CI. No SBOM generation. |
| 20 | **Documentation** | ❌ Missing | `README.md`, `DEVELOPMENT_GUIDE.md` | `DEVELOPMENT_GUIDE.md` thorough. Missing: `docs/tenant-isolation.md`, `docs/workflow-transitions.md`, `docs/runbook.md`, `docs/rbac.md`, `CONTRIBUTING.md`. |

---

## 2. Implementation Plan

Groups are ordered smallest-blast-radius first. Build/test verification runs after each group.

| Group | Areas | Description | Risk |
|-------|-------|-------------|------|
| **A** | 4, 14 | `AppUserEntity` legacy field `@Deprecated` Javadoc. Extend `ControllerSecurityTest` to assert every write endpoint has `@PreAuthorize`. | Low |
| **B** | 2 | `shared/permissions/` layer: `permissions.ts` constants, promote `usePermissions.ts`, `Can.tsx` permission-gate, `PermissionProvider.tsx`. | Low |
| **C** | 8 | Extract `SchoolOnboardingService`. Add `/api/v1/schools/{id}/onboarding/status`. Frontend `OnboardingChecklist.tsx`. | Medium |
| **D** | 9 | Audit events: `STUDENT_CREATED/UPDATED/DELETED` in `StudentService`, `FEE_COLLECTED/PAYMENT_RECORDED` in `FeeService`, `SCHOOL_CREATED/ADMIN_CREATED` in `SchoolService`. | Low |
| **E** | 1 | `shared/components/`: `StatusBadge`, `EmptyState`, `LoadingState`, `DataTable`, `SearchInput`. `shared/hooks/`: `useDebounce`, `usePagination`. | Low |
| **F** | 10 | Frontend tests: `Can.test.tsx`, `StatusBadge.test.tsx`. Wire `npm test` in CI. | Low |
| **G** | 14 | `docs/tenant-isolation.md`, `docs/workflow-transitions.md`, `docs/runbook.md`, `docs/rbac.md`. `CONTRIBUTING.md`. | Low |
| **H** | 7 | `WorkflowStatusConstants.java`. Audit tenant-safety comments in services. | Low |
| **I** | 3, 6 | `StudentImportService` extraction. Backend faceted search foundation. | Medium |
| **J** | 5 | Privilege escalation guard in `RbacService`. | Medium |

---

## 3. Known Limitations and Technical Debt

1. **In-memory student pagination** — `studentsPage()` loads all school students into memory then slices. Fix: `JpaSpecificationExecutor` + real DB pagination.
2. **`Map<String,Object>` API responses** — 418 usages. Gradual DTO replacement is the safe path. Comment `// TODO(dto)` when touching existing methods.
3. **RLS disabled** — V117 deliberately disabled Postgres RLS. App-layer isolation is the only enforcement. Future: dedicated `ims_app` PG role + JDBC `SET LOCAL app.current_school_id` interceptor.
4. **Domain events partially published** — Only `RbacService` publishes events. `SchoolCreatedEvent`, `FeeCollectedEvent`, `OrderApprovedEvent` exist but have no publishers.
5. **No privilege escalation guard** — A school admin could assign SUPERADMIN role bypassing controller-level checks. Service-layer guard needed.
6. **`HomePanel.tsx` 858 lines** — Should be split into `KPIStrip`, `ActivityFeed`, `QuickActions`, `AlertBanner`. Safe to do incrementally.
7. **No SBOM** — CycloneDX SBOM not in CI. Required for supply-chain security compliance.
8. **Sort-field injection** — List endpoints accepting `sortBy` do not allowlist column names. Must be fixed before exposing sorting in public APIs.
9. **CI missing `npm test`** — Vitest is configured; `npm run test` needs to be added to `ci.yml`.
10. **JaCoCo gate at 30%** — Should be raised to ≥ 70% on service packages after integration test coverage improves.
**Stack:** React 18 + TypeScript + Vite · Spring Boot 3.4 / Java 21 · PostgreSQL 16 + Flyway · GitHub Actions → GCP Cloud Run

---

## Executive Summary

This is a mature MVP, not a greenfield project. The core security stack (BCrypt, JWT, HttpOnly cookies, rate limiting, CORS, CSP headers, permission-based RBAC, Flyway with 125 migrations) is solid and **already at a level many production SaaS products never reach**. The real gaps are concentrated in four areas: **test coverage** (7 test files for 184 source files, 0 frontend tests), **pagination** (unbounded list APIs that will break at scale), **bean validation** (48 controller endpoints accept raw `Map<String,Object>` — no `@Valid` enforcement), and **documentation/runbook gaps**. Configuration, deployment, and tenant isolation are largely complete.

---

## Per-Area Status Table

| # | Area | Status | Real gap | Key files |
|---|------|--------|----------|-----------|
| 1 | Security hardening | ✅ | Minor: JaCoCo gate not enforced in CI; no `.env.example` for backend | `SecurityConfig.java`, `JwtAuthFilter.java`, `LoginRateLimiter.java`, `SecurityHeadersFilter.java`, `ApplicationSecurityValidator.java`, `application-prod.yml` |
| 2 | RBAC & permission model | ✅ | Minor: 2 methods in `WorkspaceController` use `@rbacService.hasPermission(authentication, 'x')` inline string rather than `PermissionConstants.*` constant | `PermissionConstants.java`, `RbacPermissionEvaluator.java`, `CrossSchoolAccessTest.java`, `ControllerSecurityTest.java` |
| 3 | Multi-tenancy & school isolation | ✅⚠️ | `SupplyOrderRepository` has **zero methods** — all queries through `JpaRepository.findAll()` would be cross-tenant; must verify actual call sites use `findBySchool_Id`. Write ownership-verification test at HTTP level (cross-school POST/PATCH). | `TenantScopeService.java`, `SupplyOrderService.java`, `CrossSchoolAccessTest.java` |
| 4 | Database & Flyway production readiness | ✅⚠️ | Backend startup bootstrap has been removed and extracted services own their schemas. Key indexes exist (V3, V6, V114). Missing: composite index on `(school_id, admission_no)` for unique-per-school student lookup. No `IF NOT EXISTS` on `V1` baseline unique constraints — safe but brittle on re-run. | `V3__add_performance_indexes.sql`, `V6__supply_firefighting_indexes.sql`, `application-prod.yml` |
| 5 | Backend architecture cleanup | ⚠️ | **48 controller endpoints** accept `@RequestBody Map<String,Object>` with no `@Valid` / bean-validation — biggest real gap in this area. `GlobalExceptionHandler` is solid. DTOs exist only for Auth, School, Zone. `@Transactional` is class-level on `WorkspaceService` — fine. No structured logging for MDC fields beyond `requestId`. | `GlobalExceptionHandler.java`, `WorkspaceController.java`, `FeeCollectionController.java`, `SupplyController.java`, `FirefightingController.java` |
| 6 | Frontend enterprise readiness | ⚠️ | `HomePanel.tsx` was just rebuilt (858 → correct size). Feature folders exist for fees, firefighting, rbac, students, workspace but **orders** and **reports** are missing. TypeScript: several `any` usages in `UnifiedWorkspacePage.tsx`. Loading/empty states: present in most panels. API error surfacing: present via `useApiData`. | `src/features/`, `src/services/api.ts`, `src/pages/workspace/panels/` |
| 7 | Workflow & approval consistency | ⚠️ | Status transition enums exist (`FirefightingRequestStatus`, `OrderStatus`) but **illegal transitions are not validated server-side** — services accept any `action` string. Frontend shows action buttons based on status but backend does not reject illegal transitions with a clear 4xx. | `FirefightingService.java`, `SupplyOrderService.java`, `common/domain/FirefightingRequestStatus.java` |
| 8 | Reporting & dashboard data quality | ✅⚠️ | Workspace/dashboard queries are tenant-scoped. No pagination on dashboard; dashboard is a single-row aggregate so that's fine. Large list APIs (students, fees, orders) are **not paginated** — returns all rows. | `WorkspaceService.java`, `StudentService.java`, `FeeService.java` |
| 9 | Performance & scalability | ❌ | **Biggest real gap.** `StudentRepository.findBySchool_IdOrderByFullNameAsc` returns all students unbounded. `FeeAssignmentRepository.findByAcademicYear_IdAndStudent_School_Id` returns all fee records. `SupplyOrderRepository` has no `findBySchool_Id` with `Pageable`. No `Page<>` responses anywhere. N+1 not confirmed but `WorkspaceService.workspace()` fetches entire student list + attendance + fees in one request — will degrade. | `StudentRepository.java`, `FeeAssignmentRepository.java`, `SupplyOrderRepository.java`, `WorkspaceService.java` |
| 10 | Configuration & deployment readiness | ✅⚠️ | Dockerfiles exist, CI/CD is thorough. **No `backend/.env.example`** (only `frontend/.env.example` exists). GitHub Actions pinned to `@v4` tags, not commit SHAs — minor but noted. `trivy-action@master` is floating (security risk). No Dependabot/Renovate config found. | `.github/workflows/ci.yml`, `frontend/.env.example` |
| 11 | Testing | ❌ | **Biggest gap by volume.** 7 test files for 184 source files. `CrossSchoolAccessTest` is excellent (28 scenarios). `ControllerSecurityTest` is good. **0 frontend tests.** No Vitest/RTL in `package.json`. JaCoCo is configured to collect but **no `check` goal / minimum threshold** — CI passes regardless of coverage. | `backend/src/test/`, `frontend/package.json` (no test runner) |
| 12 | Documentation | ⚠️ | README exists (143 lines) with architecture, local setup, basic testing. Missing: `docs/` directory, `docs/rbac.md`, `docs/tenant-isolation.md`, `docs/runbook.md`, `CONTRIBUTING.md`, PR template, backend `.env.example`, GCP Cloud Run deployment notes, credential rotation procedure. | `README.md`, `DEVELOPMENT_GUIDE.md` |

---

## Detailed Findings Per Area

### Area 1 — Security hardening ✅ (mostly complete)

**Confirmed present:**
- BCrypt via `PasswordUtil` (no plain-text utility exists)
- JWT signing key validated at startup (`ApplicationSecurityValidator` — fails if `APP_JWT_SECRET` < 32 chars or `APP_AADHAR_SECRET` < 16 chars)
- HttpOnly refresh cookie at `path=/api/v1/auth`, `SameSite=Strict`, `Secure=true` in prod
- `LoginRateLimiter`: 5 failed attempts / 15 min per email (in-memory)
- `SecurityHeadersFilter`: CSP, HSTS, X-Frame-Options, X-Content-Type-Options
- CORS: driven by `APP_CORS_ALLOWED_ORIGINS`
- `GlobalExceptionHandler`: clean error envelope, no stack traces in responses, correlationId on 500s
- Swagger disabled in prod (`SWAGGER_ENABLED:false`)
- Secrets: all env-only with no committed usable fallback for prod (dev defaults for local only)
- Login page: no credential hint text (checked — only placeholder "••••••••" for password)

**Real remaining gaps:**
1. No `backend/.env.example` documenting every required environment variable
2. No documented credential-rotation procedure (JWT key, DB password)
3. GitHub Actions `trivy-action@master` is a floating ref — should be pinned to a commit SHA

**Assessment:** Production-safe as-is. Gaps are documentation/process, not code.

---

### Area 2 — RBAC & permission model ✅ (complete)

**Confirmed present:**
- `PermissionConstants` with named constants for every permission code
- `RbacPermissionEvaluator` wired as `rbacService` in SpEL
- Every controller method has `@PreAuthorize` — audited `WorkspaceController`, `SupplyController`, `FirefightingController`, `FeeCollectionController`
- Two inline strings in `WorkspaceController` (`'timetable:manage'`, `'staff:manage'`) — these should use `PermissionConstants` constants
- `CrossSchoolAccessTest`: 28 integration test scenarios covering ADMIN, OPERATIONS, ACCOUNTANT, VIEWER, ZONE_ADMIN, SUPERADMIN, expired/revoked/future assignments
- `ControllerSecurityTest`: MockMvc-level security tests for all major controllers
- Role → permission seeding in Flyway V112/V118/V119/V122

**Real remaining gap:**
- Two inline permission strings in `WorkspaceController` (`'timetable:manage'` and `'staff:manage'`): these permission codes should be in `PermissionConstants` to prevent typos

---

### Area 3 — Multi-tenancy & school isolation ✅⚠️ (solid but one repo gap)

**Confirmed present:**
- `TenantContext` (ThreadLocal) set by `TenantResolverFilter` from RBAC assignments — not from request params
- `TenantScopeService.canAccessSchool()` / `hasPlatformAccess()` used throughout
- Every service follows the pattern: `TenantContext.get() != null ? TenantContext.get() : requestedSchoolId` — so non-platform users are always scoped to their own school even if they pass a spoofed `schoolId` query param
- `SupplyOrderService.assertSchoolOwnership()` called on update/approve operations
- `CrossSchoolAccessTest` test #25 explicitly verifies: Admin A passing `?schoolId=schoolBId` gets School A data

**Real remaining gap:**
- `SupplyOrderRepository` contains **zero custom methods** (just `extends JpaRepository<SupplyOrderEntity, String>`). `catalogOrderRepository.findBySchool_Id(schoolId)` is on `CatalogOrderRepository` — which does have the method. The service layer uses `catalogOrderRepository`, not `SupplyOrderRepository` for scoped queries. `SupplyOrderRepository` appears unused — but this should be verified and either populated or removed to prevent future misuse.
- No HTTP-level integration test proving "Admin A cannot PATCH Admin B's supply order" — the service-layer `assertSchoolOwnership` exists but isn't tested via real HTTP

---

### Area 4 — Database & Flyway production readiness ✅⚠️

**Confirmed present:**
- 125 Flyway migrations, CI runs them on a fresh DB (`AbstractIntegrationTest` / Testcontainers)
- V3 + V6 + V114 add comprehensive indexes: `school_id`, `(school_id, status)`, `(school_id, academic_year_id)`, `(student_id, academic_year_id)`, `receipt_number`, etc.
- Backend startup bootstrap removed from production configuration ✅
- `DDL-auto: validate` in both default and prod profiles
- Flyway DBA user separated from app user

**Real remaining gaps:**
- No composite unique index on `(school_id, admission_no)` — currently `findByAdmissionNoIgnoreCase` without school scope could theoretically match across schools
- `V1__baseline_schema.sql` likely has unique constraints without `IF NOT EXISTS` — safe as Flyway won't re-run, but worth noting
- JaCoCo is configured to collect but has **no `check` goal** — coverage is reported but not enforced in CI

---

### Area 5 — Backend architecture cleanup ⚠️ (real gap: DTO validation)

**Confirmed present:**
- `GlobalExceptionHandler` handles all standard cases (400/403/409/413/500), no stack traces in responses
- Consistent error envelope: `{status, error, message, timestamp, requestId}`
- `@Transactional` at class level on service classes
- MDC `requestId` echoed in error responses
- DTOs with `@Valid` exist for: `LoginRequest`, `SchoolCreateRequest`, `SchoolAdminRequest`, `SchoolUpdateRequest`, `SchoolOperationsUserRequest`, `ZoneCreateRequest`, `ZoneAdminRequest` (9 files)

**Real remaining gap — the biggest in this area:**
- **48 controller endpoints** accept `@RequestBody Map<String, Object>` with no `@Valid` enforcement. Examples: `WorkspaceController.addStudent()`, `WorkspaceController.recordPayment()`, `SupplyController.createOrder()`, `FirefightingController.create()`. Services do ad-hoc null checks internally, but malformed payloads can produce 500s instead of 400s. This is the single largest code-quality gap in the backend.
- `WorkspaceController.workspace()` takes `@RequestHeader(value = "Authorization", required = false)` — this pattern is redundant since Spring Security already validates the JWT in the filter chain. Minor noise.

---

### Area 6 — Frontend enterprise readiness ⚠️

**Confirmed present:**
- `src/features/` exists for: `fees`, `firefighting`, `rbac`, `students`, `workspace`
- API calls centralized in `src/services/api.ts`
- `useApiData` hook with error/loading state
- `usePermissions` hook with `can()`, `canAny()`, `canAll()`
- Loading states in major panels
- Auth flow: memory token + HttpOnly cookie refresh, 401 interceptor

**Real remaining gaps:**
- **0 frontend tests** — no Vitest, no RTL, no `test` npm script
- `features/orders` and `features/reports` missing (duplicated logic inline in `AdminOrdersPanel`, `SaAllOrdersPanel`)
- `UnifiedWorkspacePage.tsx` uses `any` in several places (`liveOrders: any[] | null`, etc.)
- `SupplyOrderRepository` has no typed pagination — mirrors backend gap

---

### Area 7 — Workflow & approval consistency ⚠️

**Confirmed present:**
- `FirefightingRequestStatus` enum exists
- `OrderStatus` enum exists
- Status progression documented in domain enums
- Frontend renders action buttons conditionally based on status

**Real remaining gap:**
- **Backend does not enforce valid state transitions.** `FirefightingService.approveBursar()` does not check that the request is currently in `AWAITING_BURSAR` status before approving — it will update any status. Same for supply order status changes via `PATCH /orders/{id}/status`. An illegal transition (e.g., approving an already-FULFILLED request) returns 200 instead of 409/422.
- No `WorkflowEngine` or state-machine validation — transitions are implemented as ad-hoc if/switch statements

---

### Area 8 — Reporting & dashboard data quality ✅⚠️

**Confirmed present:**
- Dashboard queries tenant-scoped via `TenantContext`
- `WorkspaceService.workspace()` correctly derives schoolId from context
- Superadmin can pass `schoolId` query param — filter respected

**Real remaining gaps:**
- No pagination on any list API — all return unbounded arrays (see Area 9)
- No date-range filtering on `GET /api/v1/fees/report` (no `from`/`to` params)

---

### Area 9 — Performance & scalability ❌ (real gap)

**Root cause:** No `Pageable` parameter in any repository or controller endpoint.

**Specific unbounded queries:**
- `StudentRepository.findBySchool_IdOrderByFullNameAsc(Long)` → returns ALL students
- `FeeAssignmentRepository.findByAcademicYear_IdAndStudent_School_Id(String, Long)` → returns ALL fee records
- `CatalogOrderRepository.findBySchool_Id(Long)` → returns ALL orders
- `FirefightingRequestRepository.findBySchool_IdOrderByCreatedAtDesc(Long)` → returns ALL FF requests
- `AuditLogRepository` (unknown — not audited but likely similar)
- `WorkspaceService.workspace()` fetches entire student list + attendance sections + fee summary in ONE synchronous call — will degrade proportionally with school size

**Impact:** At 15 schools with ~300 students each = 4,500 rows per list query. At 1 lakh+ users the current pattern causes OOM and timeout.

---

### Area 10 — Configuration & deployment readiness ✅⚠️

**Confirmed present:**
- Dockerfiles for both apps
- `ci.yml`: unit tests → integration tests (Testcontainers) → JaCoCo report → OWASP → npm audit → Gitleaks → Trivy → Docker smoke test
- `deploy.yml`: GCP Cloud Run
- `application-prod.yml`: all secrets env-only, bootstrap disabled, Swagger disabled, HTTPS cookie
- `frontend/.env.example`: documents `VITE_API_BASE_URL`

**Real remaining gaps:**
- **No `backend/.env.example`** — new developers/deployers don't know what env vars are required
- `trivy-action@master` is floating (should be pinned to commit SHA)
- Other Actions use `@v4` tags — acceptable but SHA-pinning is more secure
- No Dependabot or Renovate config found

---

### Area 11 — Testing ❌ (biggest gap)

**Current state:**
- 7 backend test files for 184 source files
- `CrossSchoolAccessTest`: 28 scenarios — excellent but only covers access control
- `ControllerSecurityTest`: MockMvc security layer — good
- `AuthIntegrationTest`, `RbacIntegrationTest`, `AuthServiceTest`, `AuthControllerTest`: auth coverage
- **No service-level unit tests** for `FeeService`, `StudentService`, `FirefightingService`, `SupplyOrderService`, `WorkspaceService`
- **No paise/money-math tests** (critical — amounts stored as `long` in paise)
- **No workflow transition tests** (critical — illegal transitions not tested)
- **0 frontend tests** — no Vitest, no RTL
- **JaCoCo has no `check` goal** — coverage is measured but not enforced. CI always passes regardless of coverage.

**Infrastructure in place:**
- Testcontainers dependency present and working
- `AbstractIntegrationTest` base class with shared setup
- Maven CI profile (`-Pci`) runs integration tests

---

### Area 12 — Documentation ⚠️

**Confirmed present:**
- `README.md` (143 lines): architecture, local setup, testing, env vars section
- `DEVELOPMENT_GUIDE.md`: detailed developer notes (architecture, entity APIs, checklist)
- `PRD.md`: product requirements

**Missing:**
- `docs/rbac.md` — role/permission model with all codes
- `docs/tenant-isolation.md` — app-layer model + safe path to DB RLS
- `docs/runbook.md` — DB failover, migration rollback, secret rotation, stuck approval
- `CONTRIBUTING.md` — PR requirements (tests, migration review, no secrets)
- `.github/pull_request_template.md`
- `backend/.env.example` — every required env var
- GCP Cloud Run deployment notes (required env vars, Cloud SQL, Secret Manager)
- Credential rotation procedure (JWT key, DB password)

---

## Prioritized Implementation Plan

### Group A — Zero-code-change documentation + config (1 day)
> Safe to ship immediately; no risk of regression

1. **`backend/.env.example`** — document all required env vars
2. **`docs/rbac.md`** — dump the permission model
3. **`docs/tenant-isolation.md`** — document app-layer model + RLS path
4. **`docs/runbook.md`** — incident procedures
5. **`CONTRIBUTING.md`** + PR template
6. **README** update with GCP/Cloud Run deployment notes
7. Pin `trivy-action@master` to a commit SHA in `ci.yml`
8. Add two missing `PermissionConstants` entries (`TIMETABLE_MANAGE`, `STAFF_MANAGE`) and update `WorkspaceController`

### Group B — Test infrastructure (high value, low risk) (2–3 days)
> Tests only — no production code changes

1. **Frontend:** Add Vitest + RTL, write tests for `usePermissions`, API interceptor, and Fees panel
2. **Backend:** Add `FeeServiceTest` (paise math, defaulter banding), `FirefightingWorkflowTest` (legal/illegal transitions), `SupplyOrderWorkflowTest`
3. **Add JaCoCo `check` goal** to `pom.xml` at ≥50% line coverage on `service/**` (achievable with Group B tests; raise to ≥70% after more tests)
4. Wire `npm test` into `ci.yml`

### Group C — Pagination (medium risk — API contract change) (2 days)
> Requires frontend wiring; announce as new response shape

1. Add `Pageable` + `Page<>` to: `StudentRepository`, `FeeAssignmentRepository`, `CatalogOrderRepository`, `FirefightingRequestRepository`
2. Update corresponding service + controller to accept `?page=0&size=20`
3. Wire `StudentPanel`, `FeesPanel`, `AdminOrdersPanel`, `FirefightingDashboardPanel` to paginated calls
4. Add migration V126: composite unique index `(school_id, admission_no)` on students

### Group D — DTO validation (medium risk) (2–3 days)
> Replace raw `Map<String,Object>` in controllers with typed DTOs + `@Valid`

1. Priority controllers: `WorkspaceController` (addStudent, recordPayment, createOrder, createFirefighting), `SupplyController` (createOrder, savePlanItem), `FirefightingController` (create, update)
2. Do NOT refactor services simultaneously — keep service signatures accepting maps initially, convert incrementally

### Group E — Workflow state-machine validation (low-risk server change) (1 day)
1. Add `validateTransition(currentStatus, targetStatus)` guard at top of each approve/reject/fulfill method in `FirefightingService` and `SupplyOrderService`
2. Return `409 Conflict` with a clear message on illegal transitions
3. Add tests for illegal transitions to Group B tests

### Group F — Frontend feature folders (low risk) (1 day)
1. Create `src/features/orders/` and `src/features/reports/`
2. Migrate duplicated order logic from `AdminOrdersPanel` into the feature
3. Replace `any` in `UnifiedWorkspacePage.tsx` with proper types

---

## What to Leave Alone

| Item | Reason |
|------|--------|
| PostgreSQL RLS | V117 documents why half-configured RLS is worse than app-layer isolation. Do not re-enable without a dedicated connection pool, `SET LOCAL app.current_school_id`, and a superadmin bypass — design only, see `docs/tenant-isolation.md` |
| `hasRole()`/`hasAnyRole()` | None found — authorization is permission-based throughout |
| Demo credentials in login UI | None found — login page has no hint text |
| `GlobalExceptionHandler` | Already correct — no stack traces, consistent envelope |
| Existing Flyway migrations | Never modify; new migrations start at V126 |
| Auth/JWT implementation | Already production-grade |
| Existing test suite | 28-scenario `CrossSchoolAccessTest` is correct — extend, don't replace |

---

## Build/Test Verification (current state)

```bash
# Backend — currently passes
cd backend && mvn clean test
# → 7 test classes pass; no JaCoCo minimum enforced

# Frontend — currently passes  
cd frontend && npm run build
# → Zero TS errors, clean build

# Integration tests — passes (requires Docker)
cd backend && mvn verify -Pci
```

---

*This document should be updated after each implementation group is merged.*
