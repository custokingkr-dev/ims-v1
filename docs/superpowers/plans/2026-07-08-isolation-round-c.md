# Isolation Follow-ups Round C Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Three independent follow-ups — per-school permission resolution at login (identity, security), reporting fact/dim RLS + projector-bypass (platform, defense-in-depth), and a billing superadmin-gate guard test (billing, cheap regression guard).

**Architecture:** Independent, different services → buildable in parallel. Task 1 = query swap + operator mitigation in `IdentityAuthService`. Task 2 = one RLS migration + a transaction-local bypass line in 8 projector upserts + an integration test. Task 3 = a source/reflection test.

**Tech Stack:** Spring Boot 4.0.7 / Java 25 (identity, platform, billing), Postgres RLS.

## Global Constraints

- Specs: `docs/superpowers/specs/2026-07-08-permission-codes-per-school-design.md`, `…-reporting-rls-projector-design.md`, `…-billing-superadmin-guard-test-design.md`.
- Backend TDD. Do NOT commit `.claude/settings.local.json`.
- Build/test: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/<svc>/pom.xml -q -Dtest=<T> test`.

---

### Task 1: identity-service — per-school permission resolution at login

**Files:** `IdentityAuthService.java`, `RbacReadRepository.java` / `RbacLookupRepository.java`, tests.

- [ ] **Step 1: Write failing tests.** In an `IdentityAuthService`-level test (mock the rbac repo): (a) user with role→CODE_A at school 1 and role→CODE_B at school 2, `branch_id=1` → login permissions contain CODE_A not CODE_B (assert `effectivePermissions(userId,1,zone)` used, not `permissionCodes`); (b) platform-global assignment + school-1 role → both codes; (c) **operator regression**: user has active OPERATIONS assignments at schools {2,3} but `branch_id=1` (no assignment at 1) → login permissions STILL include the OPERATIONS role's codes (mitigation b). Update `IdentityAuthServiceRotationTest` mocks (they currently stub `permissionCodes`/`roleNames`).

- [ ] **Step 2: Run — RED.** `-Dtest='IdentityAuthService*,Rbac*,JwtServiceTest'`.

- [ ] **Step 3: Implement.** In `IdentityAuthService`, compute login permissions as: the sorted union of `rbac.effectivePermissions(user.getId(), user.getBranchId(), user.getZoneId())` AND — when `rbac.operatorSchoolIds(user.getId())` is non-empty — the OPERATIONS role's permission codes (add a repo method e.g. `permissionCodesForRole("OPERATIONS")`, or reuse an existing role→codes query; READ `RbacReadRepository`/`RbacLookupRepository` first). Apply at BOTH `issueSession` (JWT) and `responseFor` (response body) so they match. Consolidate the duplicate `operatorSchoolIds` onto one canonical repo; delete the now-dead `RbacLookupRepository.permissionCodes` (+ its direct tests/mocks). Keep `roleNames` unchanged (display-only, `AuthResponse.roles`).

- [ ] **Step 4: Run — GREEN + full identity suite.** `-q test`. Fix any test asserting the old union behavior.

- [ ] **Step 5: Commit** — `fix(identity): resolve login permissions per active school (drop cross-school union; keep operator role codes)`

---

### Task 2: platform-service — reporting fact/dim RLS + projector-bypass

**Files:** new `reporting/V22__enable_rls_facts_dims.sql` (CONFIRM next free version — highest is V21), the 8 projector upsert repo methods (+ a `ProjectorRls` helper), new `ReportingFactRlsIntegrationTest.java`.

- [ ] **Step 1: Confirm version + read mirrors.** List `.../db/migration/reporting/`; use next free `V<n>`. READ `reporting/V6__enable_rls.sql` (policy shape) and `.../security/ReportingRlsIntegrationTest.java` (harness). Identify the 8 projector upsert methods: `AttendanceFactReadRepository.upsert`, `FeeFactReadRepository.upsertFeeAssignment` + `upsertPayment`, `CatalogFactReadRepository.upsert`, `FirefightingFactReadRepository.upsert`, `StudentReviewFactReadRepository.upsert`, `DimensionProjectionRepository.upsertSection` + `upsertStudent` (confirm exact names/signatures).

- [ ] **Step 2: Write the failing RLS integration test.** `ReportingFactRlsIntegrationTest` (app_rt NOBYPASSRLS + TenantAwareDataSource). Per fact table: isolation read (school-10 ctx sees only 10, 20 only 20, superadmin all, no-context none); WITH CHECK blocks a cross-tenant insert; and — critical — with NO TenantContext, calling the real repository upsert SUCCEEDS (projector-style). Run → RED (RLS not enabled; and once enabled without the bypass, the projector-upsert assertions would fail).

- [ ] **Step 3: Add the migration + projector bypass.** Create `V22__enable_rls_facts_dims.sql`: `ENABLE ROW LEVEL SECURITY` + `tenant_isolation` policy (standard `school_id` shape, `DROP POLICY IF EXISTS` first) on the 8 tables ONLY. Do NOT touch `dim_school`/`dim_academic_year`/`command_center_feed`/`reporting_event_inbox`/`billing_invoice_read`. Add a `ProjectorRls.allow(jdbcClient)` helper that runs `SELECT set_config('app.bypass_rls','on', true)` (transaction-local); call it as the first statement of each of the 8 `@Transactional` upsert methods. Add a top migration comment documenting the projector-bypass requirement for future fact tables.

- [ ] **Step 4: Run — GREEN + full platform suite.** `-q test`. All existing reporting read/projector tests must still pass (reads are request-scoped/self-scoped; projector writes now bypass). Investigate any failure (a projector missing the bypass will RED the projector-upsert assertion).

- [ ] **Step 5: Commit** — `feat(platform): RLS backstop on reporting fact/dim tables with transaction-local projector bypass`

---

### Task 3: billing-service — superadmin-gate guard test

**Files:** new test under `services/billing-service/src/test/java/.../` (e.g. `BillingSuperadminGateArchTest.java`). No production changes.

- [ ] **Step 1: Write the guard test.** Assert every request-mapped handler method in `BillingInvoiceController` and `BillingPublicCompatibilityController` enforces superadmin. Simplest reliable approach: read the two controller `.java` source files; for each method annotated with `@GetMapping/@PostMapping/@PutMapping/@DeleteMapping/@RequestMapping`, assert its method body contains a `requireSuperAdmin()` call. (Reflection/bytecode is acceptable if cleaner, but the source-scan is deterministic here.) The test must inspect EACH handler (not merely that the string appears once in the file) — so removing the gate from any one handler makes it fail.

- [ ] **Step 2: Run — verify it PASSES** against the current fully-gated controllers. Then briefly confirm non-vacuity: reason about (or temporarily verify) that a handler without `requireSuperAdmin()` would fail the assertion. `-Dtest='*SuperadminGate*'` then full `-q test`.

- [ ] **Step 3: Commit** — `test(billing): assert every billing controller handler is superadmin-gated (regression guard; RLS deferred)`

---

## Self-Review

**Spec coverage:** perm-resolution (effectivePermissions + operator mitigation b + delete dead union) → Task 1. reporting RLS (8 tables + projector bypass + test) → Task 2. billing guard test → Task 3. Deferred (identity RLS, notification RLS, billing RLS) documented in specs, not built.
**Placeholder scan:** "confirm version / read mirror / confirm exact method names" are read-first instructions at named files. The policy SQL, the operator mitigation, and the 8-table list are given in full.
**Consistency:** effectivePermissions(userId, branchId, zoneId) + OPERATIONS-role-codes-when-operator consistent across issueSession + responseFor. RLS policy shape identical to reporting/V6. Projector-bypass = transaction-local, matching the catalog precedent.
