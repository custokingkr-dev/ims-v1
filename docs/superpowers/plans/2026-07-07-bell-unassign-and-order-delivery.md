# Bell-Chip Unassign + Order Delivery Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let admins unassign a class from a bell schedule, and add a real `DELIVERED` order state a superadmin/operator sets (making the "Delivered" stat genuine).

**Architecture:** Two small features in school-core-service + frontend. Delivery status rides the existing `catalog-order.upserted.v1` outbox event, so platform-service is untouched.

**Tech Stack:** Spring Boot 4.0.7 / Java 25 (`JdbcClient`, `@Transactional`, Flyway per-schema), React 18 + Vite + TS. Build/test: `.\mvnw.cmd -f services\school-core-service\pom.xml -q -Dtest=<T> test` (or `-DskipTests compile`); `cd frontend && npm run build`. Windows Bash tool: prefix `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH"`.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-07-bell-unassign-and-order-delivery-design.md`.
- Frontend `api` base is `/api/v1`; the FE reaches order endpoints via `/supply/orders/…` (gateway routes `/api/v1/supply/` → catalog/school-core; those paths live in `CatalogPublicCompatibilityController`, NOT `CatalogReadController` which is `/api/v1/catalog`).
- Delivery is **one-way** `APPROVED → DELIVERED`; only superadmin **or** operations may set it. `TenantContext.isSuperAdmin()` / `.isOperations()` exist.
- `markDelivered` must call `allowCrossSchoolReadForOperations()` first — this is a **deliberate** transaction-local RLS bypass so an operations user (who has no home school) can mark any school's order delivered; superadmin already bypasses RLS session-wide. (This intentionally extends the operator's normally read-only cross-school access to this one write.)
- Stat refinement changes definitions shipped in `8eb1bd9`: `deliveredCount` counts real `DELIVERED`; `active` excludes `DELIVERED` (not `APPROVED`), so an approved-but-undelivered order counts as active.
- Reuse existing style classes (`ck-btn`, `ck-btn-ghost`, `ck-btn-g`, `ck-alert`, `ts`, etc.); no new design system.
- No FE tests (repo convention); backend tests per task. Next free catalog Flyway version is **V6**.

---

### Task 1: Part A — bell "Applies to" chip unassign (backend + frontend)

**Files:**
- Modify: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/TimetableRepository.java` (`deleteClassSchedule`)
- Modify: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/TimetableController.java` (DELETE endpoint)
- Modify: `frontend/src/services/timetableApi.ts` (`unassignClass`)
- Modify: `frontend/src/pages/workspace/panels/setup/BellSchedulesPanel.tsx` (chip ×)
- Test: `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/TimetableRepositoryIntegrationTest.java`

**Interfaces:**
- Produces: `TimetableRepository.deleteClassSchedule(Long schoolId, String classId)`; `DELETE /api/v1/timetable/class-schedules/{classId}`; `unassignClass(classId)` in `timetableApi`.

- [ ] **Step 1: Write the failing test**

Add to `TimetableRepositoryIntegrationTest` (harness already seeds a school, creates schedules, and has `seedClass`):

```java
    @Test
    void deleteClassScheduleUnassignsTheClass() throws Exception {
        long schoolId = seedSchool();
        String classId = seedClass(schoolId, "6");
        var sched = repo.createSchedule(schoolId, "Std");
        long schedId = ((Number) sched.get("id")).longValue();
        repo.setClassSchedule(schoolId, classId, schedId);
        assertThat(repo.classSchedules(schoolId)).anySatisfy(m -> {
            assertThat(m.get("classId")).isEqualTo(classId);
            assertThat(((Number) m.get("scheduleId")).longValue()).isEqualTo(schedId);
        });

        repo.deleteClassSchedule(schoolId, classId);

        assertThat(repo.classSchedules(schoolId)).anySatisfy(m -> {
            assertThat(m.get("classId")).isEqualTo(classId);
            assertThat(m.get("scheduleId")).isNull();
        });
    }
```

- [ ] **Step 2: Run — verify it fails**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/school-core-service/pom.xml -q -Dtest=TimetableRepositoryIntegrationTest test`
Expected: FAIL — `deleteClassSchedule` does not exist.

- [ ] **Step 3: Add the repository method**

In `TimetableRepository`, next to `setClassSchedule`:

```java
    @Transactional
    public void deleteClassSchedule(Long schoolId, String classId) {
        jdbc.sql("""
                DELETE FROM tenant_school.school_class_bell_map
                WHERE school_id = :s AND class_id = :c
                """)
                .param("s", schoolId)
                .param("c", classId)
                .update();
    }
```

- [ ] **Step 4: Add the controller endpoint**

In `TimetableController`, mirror the existing `@PutMapping("/api/v1/timetable/class-schedules/{classId}")` (`setClassSchedule`):

```java
    @DeleteMapping("/api/v1/timetable/class-schedules/{classId}")
    public void unassignClassSchedule(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable("classId") String classId) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSchoolAdmin();
        Long schoolId = TenantScope.resolveSchoolId(null);
        timetable.deleteClassSchedule(schoolId, classId);
    }
```

Ensure `import org.springframework.web.bind.annotation.DeleteMapping;` is present (it is — `deletePeriod` uses it).

- [ ] **Step 5: Run — verify it passes**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/school-core-service/pom.xml -q -Dtest=TimetableRepositoryIntegrationTest test`
Expected: PASS.

- [ ] **Step 6: Add the FE API function**

In `frontend/src/services/timetableApi.ts`, next to `setClassSchedule`:

```ts
export const unassignClass = (classId: string) => api.delete(`/timetable/class-schedules/${encodeURIComponent(classId)}`);
```

- [ ] **Step 7: Wire the chip ×**

In `BellSchedulesPanel.tsx`: import `unassignClass`. Add a handler near `handleClassScheduleChange`:

```tsx
  const handleUnassignClass = async (classId: string) => {
    try {
      setError('');
      await unassignClass(classId);
      await load();
    } catch (err: unknown) {
      setError(errMsg(err, 'Could not unassign class.'));
    }
  };
```

In the "Applies to" chip row, render an × on each assigned chip that calls it. The assigned chips are derived as `classSchedules.filter(c => c.scheduleId === selectedSchedule.id)`; each chip currently shows `className` — add a small × button: `<button className="ck-btn ck-btn-ghost" style={{ padding: '0 6px' }} onClick={() => handleUnassignClass(chip.classId)}>×</button>` inside the chip. On reload the class has `scheduleId == null` and appears in the unassigned-classes prompt.

- [ ] **Step 8: Build the frontend**

Run: `cd frontend && npm run build`
Expected: clean.

- [ ] **Step 9: Commit**

```bash
git add services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/TimetableRepository.java \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/TimetableController.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/TimetableRepositoryIntegrationTest.java \
        frontend/src/services/timetableApi.ts \
        frontend/src/pages/workspace/panels/setup/BellSchedulesPanel.tsx
git commit -m "feat(timetable): unassign a class from its bell schedule (chip ×)"
```

---

### Task 2: Part B backend — order delivery tracking

**Files:**
- Create: `services/school-core-service/src/main/resources/db/migration/catalog/V6__order_delivery_columns.sql`
- Modify: `services/school-core-service/.../persistence/CatalogReadRepository.java` (`markDelivered`; refine `orderStats`)
- Modify: `services/school-core-service/.../api/compat/CatalogPublicCompatibilityController.java` (`POST /api/v1/supply/orders/{id}/deliver`)
- Test: `services/school-core-service/.../persistence/CatalogOrderEventEmissionIntegrationTest.java`

**Interfaces:**
- Consumes: existing `requiredOrder(id)`, `emitOrderUpserted(row)`, `allowCrossSchoolReadForOperations()`, `count`/`sum`; `TenantContext.get().userId()`, `.isSuperAdmin()`, `.isOperations()`; the compat controller's `requireToken` + `command(...)` helpers.
- Produces: `CatalogReadRepository.markDelivered(String id, Long actorId) : CatalogOrderRow`; `POST /api/v1/supply/orders/{id}/deliver`.

- [ ] **Step 1: Write the failing tests**

Add to `CatalogOrderEventEmissionIntegrationTest` (harness seeds school id=1, constructs `repo`):

```java
    @Test
    void markDeliveredMovesApprovedToDeliveredAndEmits() {
        var created = repo.createOrder(Map.of("schoolId", 1, "category", "STATIONERY",
                "totalAmount", 1180, "status", "APPROVED"));
        long before = countOutbox();

        var delivered = repo.markDelivered(created.id(), 42L);

        assertThat(delivered.status()).isEqualTo("DELIVERED");
        String status = jdbc.sql("SELECT status FROM catalog.catalog_orders WHERE id = :id")
                .param("id", created.id()).query(String.class).single();
        assertThat(status).isEqualTo("DELIVERED");
        Long deliveredBy = jdbc.sql("SELECT delivered_by FROM catalog.catalog_orders WHERE id = :id")
                .param("id", created.id()).query(Long.class).single();
        assertThat(deliveredBy).isEqualTo(42L);
        assertThat(countOutbox()).isEqualTo(before + 1);
    }

    @Test
    void markDeliveredRejectsNonApprovedOrder() {
        var created = repo.createOrder(Map.of("schoolId", 1, "category", "STATIONERY",
                "totalAmount", 1180, "status", "PROCESSING"));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> repo.markDelivered(created.id(), 42L));
    }
```

Also update the existing `orderStatsExposesActiveDeliveredServiceAndTermSpend` test: change the UNIFORMS order's status from `"APPROVED"` to `"DELIVERED"`, and assert `activeOrders == 2` still (the two PROCESSING) plus `deliveredCount == 1` (the DELIVERED one); add one more order with status `"APPROVED"` and assert it now counts in `activeOrders` (so `activeOrders == 3`) and NOT in `deliveredCount`.

- [ ] **Step 2: Run — verify failure**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/school-core-service/pom.xml -q -Dtest=CatalogOrderEventEmissionIntegrationTest test`
Expected: FAIL — `markDelivered` missing / `delivered_by` column missing / stat definitions not yet refined.

- [ ] **Step 3: Migration**

Create `services/school-core-service/src/main/resources/db/migration/catalog/V6__order_delivery_columns.sql`:

```sql
-- Real delivery tracking: a fulfiller (superadmin/operations) marks an APPROVED order DELIVERED.
-- status is free-text VARCHAR, so 'DELIVERED' is a new value with no enum change or backfill.
ALTER TABLE catalog.catalog_orders
    ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS delivered_by BIGINT;
```

- [ ] **Step 4: Add `markDelivered` (mirror `approveBySuperadmin`)**

In `CatalogReadRepository`, after `approveBySuperadmin`:

```java
    @Transactional
    public CatalogOrderRow markDelivered(String id, Long actorId) {
        // Deliberate cross-school write allowance: an operations user has no home school, so grant
        // the same transaction-local RLS bypass used for the all-orders read (superadmin already
        // bypasses session-wide). This is the one operator write we intentionally allow cross-school.
        allowCrossSchoolReadForOperations();
        CatalogOrderRow current = requiredOrder(id);
        if (!"APPROVED".equalsIgnoreCase(current.status())) {
            throw new IllegalStateException("Only approved orders can be marked delivered. Current status: " + current.status());
        }
        jdbc.sql("""
                UPDATE catalog.catalog_orders
                SET status = 'DELIVERED', delivered_at = now(), delivered_by = :actorId, version = version + 1
                WHERE id = :id
                """)
                .param("id", id)
                .param("actorId", actorId)
                .update();
        CatalogOrderRow updated = requiredOrder(id);
        emitOrderUpserted(updated);
        return updated;
    }
```

(`IllegalStateException` matches the sibling `markDesignApproved` wrong-status pattern the `command(...)` helper already maps to 400.)

- [ ] **Step 5: Refine `orderStats`**

In `orderStats`, change the `activePred` string and the `deliveredCount` queries (both the `schoolId == null` and the scoped branch):

```java
        String activePred = "UPPER(status) NOT IN ('DRAFT','REJECTED','RETURNED','DELIVERED')";
```
and each `deliveredCount` line from `… WHERE … UPPER(status) = 'APPROVED'` to `… UPPER(status) = 'DELIVERED'`. Update the explanatory comment above (APPROVED is now active/awaiting-delivery; DELIVERED is the real fulfilled state).

- [ ] **Step 6: Add the `/deliver` endpoint**

In `CatalogPublicCompatibilityController`, after `superadminReject` (mirror its shape; import `HttpStatus`/`ResponseStatusException` are already present):

```java
    @PostMapping("/api/v1/supply/orders/{id}/deliver")
    public Object markDelivered(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @PathVariable String id) {
        requireToken(token, "catalog:read");
        if (!(TenantContext.get().isSuperAdmin() || TenantContext.get().isOperations())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only superadmin or operations can mark orders delivered");
        }
        return command(() -> catalog.markDelivered(id, TenantContext.get().userId()));
    }
```

Ensure `com.custoking.ims.schoolcoreservice.security.TenantContext` is imported (add if missing).

- [ ] **Step 7: Run the focused tests, then the full suite**

Run: `JAVA_HOME='C:\Program Files\Java\jdk-25.0.3' PATH="$JAVA_HOME/bin:$PATH" ./mvnw.cmd -f services/school-core-service/pom.xml -q -Dtest=CatalogOrderEventEmissionIntegrationTest test`
Expected: PASS. Then run the full suite once: `… ./mvnw.cmd -f services/school-core-service/pom.xml -q test` — expected BUILD SUCCESS (no regression in other catalog tests from the stat/status changes).

- [ ] **Step 8: Commit**

```bash
git add services/school-core-service/src/main/resources/db/migration/catalog/V6__order_delivery_columns.sql \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/CatalogReadRepository.java \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/compat/CatalogPublicCompatibilityController.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/CatalogOrderEventEmissionIntegrationTest.java
git commit -m "feat(catalog): real order delivery tracking (APPROVED->DELIVERED) + stat refinement"
```

---

### Task 3: Part B frontend — "Mark delivered" action + status label

**Files:**
- Modify: `frontend/src/pages/workspace/panels/SaAllOrdersPanel.tsx` ("Mark delivered" button)
- Modify: `frontend/src/pages/workspace/utils.ts` (`prettyOrderStatus` DELIVERED)

**Interfaces:**
- Consumes: `POST /supply/orders/{id}/deliver` (Task 2), the panel's existing `load()`.

- [ ] **Step 1: Add the DELIVERED label**

In `utils.ts` `prettyOrderStatus`, before the final `return`:

```ts
  if (value === 'DELIVERED') return 'Delivered';
```

- [ ] **Step 2: Add the "Mark delivered" action**

In `SaAllOrdersPanel.tsx`, add a handler mirroring `acceptOrder`:

```tsx
  const markDelivered = async (orderId: string) => {
    try {
      await api.post(`/supply/orders/${orderId}/deliver`);
      await load();
    } catch (e: any) {
      setToast(e?.response?.data?.message || 'Could not mark delivered.');
    }
  };
```

(Use whatever error surface the panel already uses — it has a `toast`/`setToast`; match the sibling `acceptOrder`/`openInvoiceFromOrder` error handling.)

In the row's action cell, when `String(row.status).toUpperCase() === 'APPROVED'`, render a button: `<button className="ck-btn ck-btn-g" onClick={() => markDelivered(row.id)}>Mark delivered</button>` (alongside/instead of the existing Accept/Invoice action for that row per the existing conditional structure — keep the Invoice action available too if that's the current behavior).

- [ ] **Step 3: Build**

Run: `cd frontend && npm run build`
Expected: clean.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/workspace/panels/SaAllOrdersPanel.tsx frontend/src/pages/workspace/utils.ts
git commit -m "feat(fe): Mark-delivered action on approved orders + Delivered status label"
```

---

## Self-Review

**Spec coverage:** Part A (§ unassign) → Task 1 (repo + endpoint + FE chip + test). Part B migration → Task 2 Step 3; `markDelivered` + one-way APPROVED→DELIVERED + cross-school bypass + emit → Task 2 Step 4; stat refinement → Task 2 Step 5; `/deliver` endpoint + superadmin/operations gate → Task 2 Step 6; FE action + DELIVERED label → Task 3. Error table (400 non-approved, 403 wrong role) → Task 2 Steps 4/6. Tests → Tasks 1 & 2. Reporting-note (no platform change; event carries status) → satisfied by `emitOrderUpserted` in Step 4.

**Placeholder scan:** no TBD/TODO; every code step has complete code. The two "match the panel's existing error surface / conditional structure" notes in Task 3 are because `SaAllOrdersPanel`'s exact action-cell JSX must be read at implementation — the handler and button code are given in full.

**Type consistency:** `deleteClassSchedule(Long, String)` def (Task 1 Step 3) == call (Step 4). `markDelivered(String, Long)` def (Task 2 Step 4) == controller call (Step 6) == test call (Step 1). `unassignClass(classId)`, `markDelivered(orderId)` FE names consistent within their tasks. Status string `'DELIVERED'` identical across migration comment, repo, stat queries, prettyOrderStatus, and tests.
