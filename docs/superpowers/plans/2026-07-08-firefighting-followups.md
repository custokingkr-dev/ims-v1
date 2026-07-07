# Firefighting Follow-ups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two firefighting FE cosmetics (dedup helpers, nav live-refresh) + backend FIREFIGHTING module-entitlement enforcement in operations-service.

**Architecture:** FE-only refactor/UX for the cosmetics. For entitlement, a new operations→school-core `RestClient` (mirroring identity's `TenantSchoolClient`) feeds a request interceptor that 403s non-superadmin callers at schools lacking the FIREFIGHTING module; fail-open on lookup error.

**Tech Stack:** Spring Boot 4.0.7 / Java 25, React 18 + Vite + TS. Backend build/test (Windows Bash tool): `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/operations-service/pom.xml -q -Dtest=<T> test`; frontend `cd frontend && npm run build`.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-08-firefighting-followups-design.md`.
- The module-code field returned by school-core `GET /api/v1/schools/{id}/modules/active` is `moduleCode` (record `ModuleEntitlementRow`); the module we check is `FIREFIGHTING` (compare uppercased).
- Entitlement guard: **superadmin bypasses** (`TenantContext.get().isSuperAdmin()`); a null authenticated `schoolId` bypasses. Block (403) only when the lookup **succeeds** and FIREFIGHTING is absent. **Fail-open** (allow, log a warning) when the lookup throws — firefighting availability must not depend on a live school-core call.
- operations `TenantContext` carries `userId/email/role/schoolId/zoneId` (populated by `TenantContextFilter` from `X-Authenticated-*` headers); `isSuperAdmin()` exists. There is NO existing WebMvcConfigurer in the service.
- Cross-service client mirrors `identity-service/.../infrastructure/TenantSchoolClient.java`: `X-Tenant-School-Token` header + Cloud Run OIDC (`auto` on `*.run.app`, minted from the metadata server) + config prefix `operations.tenant-school.*` with EMPTY defaults (when unset, the client is not configured → guard fails-open, so local/dev without the peer doesn't break firefighting).
- Reuse existing patterns/styles; no new design system. No FE tests (repo convention) — verify FE with `npm run build`. Backend gets tests (TDD).

---

### Task 1: Frontend — dedup FF helpers + nav live-refresh (A + B)

**Files:**
- Create: `frontend/src/pages/workspace/panels/ffUtils.ts`
- Modify: `frontend/src/pages/workspace/panels/FirefightingDashboardPanel.tsx`, `FirefightingOrdersPanel.tsx`, `FirefightingApprovalsPanel.tsx`
- Modify: `frontend/src/pages/UnifiedWorkspacePage.tsx`

**Interfaces:**
- Produces: `ffUtils.ts` exporting `interface FfTrackItem`, `formatFfDate(value?: string): string`, `labelFfStatus(status: string): string`.

- [ ] **Step 1: Create the shared helper module**

Create `frontend/src/pages/workspace/panels/ffUtils.ts`. Move the EXISTING `FfTrackItem` interface, `formatFfDate`, and `labelFfStatus` implementations verbatim from `FirefightingDashboardPanel.tsx` (lines ~8-33) into it as exports. (They are byte-identical in Dashboard and Orders — copy one, don't rewrite.)

- [ ] **Step 2: Import the shared helpers, delete local copies**

In `FirefightingDashboardPanel.tsx` and `FirefightingOrdersPanel.tsx`: delete the local `FfTrackItem`/`formatFfDate`/`labelFfStatus` definitions; add `import { type FfTrackItem, formatFfDate, labelFfStatus } from './ffUtils';`. In `FirefightingApprovalsPanel.tsx` (which uses `formatFfDate(req.createdAt)` from the earlier fix): import `formatFfDate` from `./ffUtils` and remove any local copy it introduced.

- [ ] **Step 3: Nav live-refresh (B)**

In `UnifiedWorkspacePage.tsx`, the `activeModules` fetch effect (~line 81-87) keys on `[user?.branchId, isPlatformAdmin]`. Make it re-run when the workspace refreshes: add a `refreshNonce` state (`const [refreshNonce, setRefreshNonce] = useState(0);`), increment it at the end of the existing `refresh()` (~line 130) (`setRefreshNonce(n => n + 1);`), and add `refreshNonce` to the modules effect's dependency array. Keep the fail-open sentinel (`Set<string> | null`, `.catch(() => setActiveModules(null))`) unchanged. Now a mid-session entitlement change is reflected the next time the workspace refreshes, without a full reload.

- [ ] **Step 4: Build**

Run: `cd frontend && npm run build`
Expected: clean (no TS errors; no duplicate-identifier or unused-import errors after moving the helpers).

Manual acceptance: firefighting panels render identically (helpers unchanged); triggering a workspace refresh re-fetches the school's active modules.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/workspace/panels/ffUtils.ts frontend/src/pages/workspace/panels/Firefighting{Dashboard,Orders,Approvals}Panel.tsx frontend/src/pages/UnifiedWorkspacePage.tsx
git commit -m "refactor(fe): shared firefighting timeline helpers; nav modules re-fetch on refresh"
```

---

### Task 2: operations-service — FIREFIGHTING module-entitlement enforcement (C)

**Files:**
- Create: `services/operations-service/src/main/java/com/custoking/ims/operationsservice/infrastructure/ModuleEntitlementClient.java`
- Create: `services/operations-service/.../security/FirefightingModuleInterceptor.java` + `.../config/WebMvcConfig.java` (interceptor + registration)
- Modify: `services/operations-service/src/main/resources/application.yml` (config)
- Modify: `cloudbuild.yaml` (operations `deploy_service` env, line ~156)
- Test: `services/operations-service/.../infrastructure/ModuleEntitlementClientTest.java` + `.../security/FirefightingModuleInterceptorTest.java`

**Interfaces:**
- Produces: `ModuleEntitlementClient.activeModules(Long schoolId) : java.util.Set<String>` (uppercased module codes; throws/propagates on peer failure so the guard can fail-open on catch); `FirefightingModuleInterceptor` (HandlerInterceptor) registered for `/api/v1/ff/**` and `/api/v1/workspace/firefighting`.

- [ ] **Step 1: Write the failing guard test**

Create `FirefightingModuleInterceptorTest` (plain unit test, mock the `ModuleEntitlementClient` and `TenantContext`):
```java
// non-superadmin, school WITHOUT firefighting → interceptor returns false / throws 403
// non-superadmin, school WITH firefighting → allowed (true)
// superadmin → allowed regardless (client not consulted)
// null schoolId → allowed
// client throws (lookup failure) → allowed (fail-open)
```
Use Mockito to stub `moduleEntitlementClient.activeModules(schoolId)` returning `Set.of("FIREFIGHTING")` / `Set.of()` / throwing; drive `TenantContext.set(...)` for the role/school. Assert the interceptor's `preHandle` outcome (returns true / throws `ResponseStatusException(403)`).

Also create `ModuleEntitlementClientTest`: with a stubbed RestClient/response for `/schools/{id}/modules/active` returning `[{"moduleCode":"FIREFIGHTING"}, {"moduleCode":"students"}]`, assert `activeModules(1L)` == `Set.of("FIREFIGHTING","STUDENTS")` (uppercased); and that a second call within the TTL does not re-fetch (cache). (Mirror how `identity`'s client tests / other operations infra tests construct the collaborator.)

- [ ] **Step 2: Run — verify failure**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/operations-service/pom.xml -q -Dtest='ModuleEntitlementClientTest,FirefightingModuleInterceptorTest' test`
Expected: FAIL — classes don't exist.

- [ ] **Step 3: Create `ModuleEntitlementClient`**

Mirror `identity-service/src/main/java/com/custoking/ims/identityservice/infrastructure/TenantSchoolClient.java` (READ IT FIRST) — same RestClient construction, `X-Tenant-School-Token` header, Cloud Run OIDC (`cloudRunIdentityToken()` via the GCE metadata server, `auto` mode on `*.run.app`), and timeouts. Config prefix `operations.tenant-school.*`:
```java
@Component
public class ModuleEntitlementClient {
    private final RestClient restClient;      // base-url operations.tenant-school.base-url
    private final HttpClient metadataClient;  // for OIDC (mirror TenantSchoolClient)
    private final String baseUrl, token, cloudRunAuthMode;
    private final long ttlMs;                 // operations.tenant-school.module-cache-ttl-ms:60000
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();
    private record Cached(Set<String> modules, long expiresAt) {}

    // constructor: @Value("${operations.tenant-school.base-url:}") etc. (mirror TenantSchoolClient)

    /** Active module codes (UPPERCASE) for a school. Throws on peer/config failure so the caller fails-open. */
    public Set<String> activeModules(Long schoolId) {
        if (schoolId == null || !StringUtils.hasText(baseUrl) || !StringUtils.hasText(token)) {
            throw new IllegalStateException("tenant-school module lookup not configured");
        }
        Cached hit = cache.get(schoolId);
        if (hit != null && hit.expiresAt() > monotonicNow()) return hit.modules();
        List<Map<String, Object>> rows = restClient.get()
                .uri("/api/v1/schools/{id}/modules/active", schoolId)
                .headers(this::applyHeaders)   // X-Tenant-School-Token + OIDC bearer (mirror TenantSchoolClient)
                .retrieve().body(new ParameterizedTypeReference<>() {});
        Set<String> codes = (rows == null ? List.<Map<String,Object>>of() : rows).stream()
                .map(r -> String.valueOf(r.get("moduleCode")).toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        cache.put(schoolId, new Cached(codes, monotonicNow() + ttlMs));
        return codes;
    }
}
```
> IMPORTANT: `Date.now()`/`System.currentTimeMillis()` is fine in production code (the no-`Date.now` rule applies only to workflow scripts). Use `System.nanoTime()/1_000_000` or `System.currentTimeMillis()` for `monotonicNow()`. Match `TenantSchoolClient`'s exact OIDC/header code — do not invent a new variant.

- [ ] **Step 4: Create the interceptor + registration**

`FirefightingModuleInterceptor implements HandlerInterceptor`:
```java
@Override
public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
    TenantContext ctx = TenantContext.get();
    if (ctx.isSuperAdmin() || ctx.schoolId() == null) return true;   // bypass
    try {
        if (!client.activeModules(ctx.schoolId()).contains("FIREFIGHTING")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This school does not have the Urgent Procurement module enabled");
        }
    } catch (ResponseStatusException e) {
        throw e;
    } catch (Exception e) {
        // fail-open: entitlement lookup failed — do not break firefighting availability
        log.warn("firefighting entitlement lookup failed for school {}, allowing", ctx.schoolId(), e);
    }
    return true;
}
```
Register it via a new `@Configuration WebMvcConfig implements WebMvcConfigurer` (none exists yet):
```java
@Override public void addInterceptors(InterceptorRegistry reg) {
    reg.addInterceptor(firefightingModuleInterceptor)
       .addPathPatterns("/api/v1/ff/**", "/api/v1/workspace/firefighting");
}
```
(Interceptors run AFTER the servlet filters, so `TenantContextFilter` has already populated `TenantContext`.)

- [ ] **Step 5: Config**

In `services/operations-service/src/main/resources/application.yml`, add (empty defaults so the guard is inert/fail-open when unset):
```yaml
operations:
  tenant-school:
    base-url: ${OPERATIONS_TENANT_SCHOOL_BASE_URL:}
    token: ${OPERATIONS_TENANT_SCHOOL_TOKEN:}
    cloud-run-auth: ${OPERATIONS_TENANT_SCHOOL_CLOUD_RUN_AUTH:auto}
    module-cache-ttl-ms: ${OPERATIONS_TENANT_SCHOOL_MODULE_CACHE_TTL_MS:60000}
```
(Nest under the existing `operations:` / relevant top-level key as the file's structure dictates — read it first.)

- [ ] **Step 6: cloudbuild — operations peer wiring**

In `cloudbuild.yaml`, the operations `deploy_service` (line ~156) env string: append `OPERATIONS_TENANT_SCHOOL_BASE_URL=$${TENANT_SCHOOL_URL},OPERATIONS_TENANT_SCHOOL_CLOUD_RUN_AUTH=auto` (mirror how identity-service's deploy passes `TENANT_SCHOOL_BASE_URL=$${TENANT_SCHOOL_URL},TENANT_SCHOOL_CLOUD_RUN_AUTH=auto`). Wire `OPERATIONS_TENANT_SCHOOL_TOKEN` to the same tenant-school read-token secret the gateway/identity use (via the deploy's `--set-secrets` / secret mechanism — mirror how identity gets its tenant-school token). Ensure `TENANT_SCHOOL_URL` is resolved for the operations deploy the same way it is for identity/platform (it's the school-core service URL). Validate YAML parses.

- [ ] **Step 7: Run — GREEN + full operations suite**

Focused tests PASS, then full operations suite: `… ./mvnw.cmd -f services/operations-service/pom.xml -q test` → BUILD SUCCESS. (The suite runs without the peer configured → the client's empty-config path makes the guard fail-open, so existing firefighting tests are unaffected.)

- [ ] **Step 8: Commit**

```bash
git add services/operations-service/src/main/java/com/custoking/ims/operationsservice/infrastructure/ModuleEntitlementClient.java \
        services/operations-service/src/main/java/com/custoking/ims/operationsservice/security/FirefightingModuleInterceptor.java \
        services/operations-service/src/main/java/com/custoking/ims/operationsservice/config/WebMvcConfig.java \
        services/operations-service/src/main/resources/application.yml \
        cloudbuild.yaml \
        services/operations-service/src/test/java/com/custoking/ims/operationsservice/
git commit -m "feat(operations): enforce FIREFIGHTING module entitlement (school-core client + interceptor, fail-open)"
```

---

## Self-Review

**Spec coverage:** A (dedup helpers) → Task 1 Steps 1-2. B (nav live-refresh) → Task 1 Step 3. C1 (client) → Task 2 Step 3. C2 (interceptor guard, superadmin/null bypass, fail-open) → Task 2 Steps 1/4. C3 (config + cloudbuild) → Task 2 Steps 5-6. Error table (403 / fail-open / bypass) → Task 2 Steps 1/4. Tests (mocked client guard + client code-set/cache) → Task 2 Step 1. Deferred items (per-user permission propagation, separation-of-duties) explicitly NOT built.

**Placeholder scan:** no TBD/TODO. The "mirror TenantSchoolClient / read the file first" directives point at a named existing file to copy the OIDC/header/RestClient specifics from (rather than reproduce 200 lines verbatim) — the client's method body, config keys, cache, and fail-open contract are given in full. The application.yml nesting "as the file dictates" is a read-first instruction, not a gap.

**Type/behavior consistency:** `activeModules(Long): Set<String>` used identically in the client (Task 2 Step 3) and the interceptor (Step 4). Module code `FIREFIGHTING` (uppercased) consistent between the client's extraction and the interceptor's `.contains`. Config prefix `operations.tenant-school.*` consistent between application.yml (Step 5) and the client's `@Value` (Step 3), and the env var names (`OPERATIONS_TENANT_SCHOOL_*`) consistent between application.yml and cloudbuild (Step 6). Fail-open contract (client throws on failure → interceptor catches → allow) consistent between Steps 3, 4, and 1.
