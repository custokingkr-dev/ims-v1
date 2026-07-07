# Bell-Chip Unassign + Real Order Delivery Tracking — Design

**Date:** 2026-07-07
**Status:** Approved for planning
**Services touched:** school-core-service (`tenant_school` + `catalog` schemas), frontend. platform-service is untouched (delivery status rides the existing `catalog-order.upserted.v1` event).

Two independent, small features in one spec.

---

## Part A — Bell "Applies to" chip: direct unassign

### Problem
In the redesigned Bell Schedules tab, the "Applies to" chips show which classes use a schedule, and "+ Assign class" moves a class onto it. But there is **no way to unassign** a class back to *no* schedule — you can only reassign it to a different one. `school_class_bell_map` has a row per mapped class; `setClassSchedule` only upserts, never deletes.

### Design
- **Backend** (`school-core-service`):
  - New `TimetableRepository.deleteClassSchedule(Long schoolId, String classId)`:
    ```sql
    DELETE FROM tenant_school.school_class_bell_map WHERE school_id = :s AND class_id = :c
    ```
    `@Transactional`. No-op if no row exists.
  - New `TimetableController` endpoint `DELETE /api/v1/timetable/class-schedules/{classId}`, gated identically to the existing `PUT /api/v1/timetable/class-schedules/{classId}` (`requireToken(token, "tenant-school:write")` + `TenantScope.requireSchoolAdmin()`, `schoolId = TenantScope.resolveSchoolId(null)`).
  - No migration (table already exists; this only deletes rows).
- **Frontend**:
  - `timetableApi.ts`: `unassignClass(classId: string) => api.delete(\`/timetable/class-schedules/${encodeURIComponent(classId)}\`)`.
  - `BellSchedulesPanel.tsx`: render an **×** control on each assigned "Applies to" chip; clicking calls a `handleUnassignClass(classId)` that awaits `unassignClass(classId)` then `load()`. On reload the class has `scheduleId == null`, so it moves into the existing unassigned-classes prompt. Reuse the existing `errMsg`/`setError` pattern. Gated on the existing `canManage`.

### Testing
Backend integration test (extend `TimetableRepositoryIntegrationTest`): assign a class via `setClassSchedule`, then `deleteClassSchedule`, and assert `classSchedules()` shows that class with `scheduleId == null`.

---

## Part B — Real order delivery tracking

### Problem
The order lifecycle ends at `APPROVED` (superadmin final-approval); there is no `DELIVERED` state. The "Delivered" stat card currently proxies to `APPROVED` (the sensible-default shipped earlier) — it doesn't reflect real deliveries. We want a genuine delivered state a fulfiller sets.

### Decisions (settled during brainstorming)
- **Who / transition:** superadmin **or** operations user marks an order Delivered; **`APPROVED → DELIVERED` only** (reject if not currently APPROVED). **One-way** (no un-deliver).
- **Where:** the superadmin/operator **all-orders view** (`SaAllOrdersPanel`). School admins see the status but cannot set it.

### Design
- **Migration** (`school-core-service`, `catalog` schema, next free `V…`):
  ```sql
  ALTER TABLE catalog.catalog_orders
      ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMPTZ,
      ADD COLUMN IF NOT EXISTS delivered_by BIGINT;
  ```
  `status` is free-text `VARCHAR`; `DELIVERED` is a new value — no enum change, no backfill.
- **Backend** (`CatalogReadRepository` + a supply-orders controller):
  - `markDelivered(String id, Long actorId)` (`@Transactional`):
    - Cross-school write allowance for the operator: call `allowCrossSchoolReadForOperations()` (the existing transaction-local `app.bypass_rls='on'` helper) at the top so an operations user can mutate any school's order (superadmin already bypasses RLS session-wide). *(Rename is out of scope; the helper's bypass applies to writes in the same transaction too.)*
    - Load the order (`requiredOrder(id)` — RLS enforced for non-bypassed callers). If `UPPER(status) <> 'APPROVED'` → `IllegalArgumentException("Only approved orders can be marked delivered")` (→ 400).
    - `UPDATE catalog.catalog_orders SET status = 'DELIVERED', delivered_at = now(), delivered_by = :actorId, version = version + 1 WHERE id = :id` (mirror the SET-clause shape the sibling `approveBySuperadmin`/`placeOrder` updates use; `delivered_by` is the actor's numeric user id).
    - `emitOrderUpserted(...)` for the updated order (so the reporting catalog fact projects the new status — no new reporting code; the existing `catalog-order.upserted.v1` payload already carries `status`).
    - Return the updated `CatalogOrderRow`.
  - New endpoint `POST /api/v1/supply/orders/{id}/deliver` in the supply-orders controller (the one that already exposes `/supply/orders/{id}/place`, `/superadmin-approve`, etc.). Gate: require the catalog service token **and** superadmin-or-operations role (mirror the auth guard the sibling superadmin/operations order actions use — `TenantContext.isSuperAdmin() || TenantContext.isOperations()`; reject others with 403). `actorId = TenantContext.get().userId()`. Map `IllegalArgumentException` → 400.
- **Stat refinement** (`orderStats`, both branches — refines the definitions shipped in commit `8eb1bd9`):
  - `deliveredCount` counts `UPPER(status) = 'DELIVERED'` (was `'APPROVED'`).
  - `activePred` changes to `UPPER(status) NOT IN ('DRAFT','REJECTED','RETURNED','DELIVERED')` (was `…,'APPROVED'`). So an approved-but-undelivered order counts as **active** (in-flight, awaiting delivery); only a real DELIVERED order leaves the active set. `termSpend` (non-draft Σ) and `activeServices` (active service categories) follow the refined `activePred` unchanged in shape.
- **Frontend**:
  - `SaAllOrdersPanel.tsx`: on rows with `UPPER(status) === 'APPROVED'`, show a **"Mark delivered"** button (alongside the existing Accept/Invoice actions) → `api.post(\`/supply/orders/${id}/deliver\`)` → reload the orders list. Surface errors via the existing pattern.
  - `utils.ts` `prettyOrderStatus`: add `if (value === 'DELIVERED') return 'Delivered';`.
  - `AdminOrdersPanel` (school-admin) needs no change beyond already reading the status; its "Delivered" stat card now reflects real `DELIVERED` orders via the refined `orderStats`.

### Reporting note
No platform-service change. `markDelivered` emits `catalog-order.upserted.v1` with `status='DELIVERED'`; the existing `CatalogFactProjector` upserts the fact by order id, so `reporting.fact_catalog_order.status` becomes `DELIVERED` automatically.

### Error handling
| Condition | HTTP | Message |
|-----------|------|---------|
| `deliver` on a non-APPROVED order | 400 | "Only approved orders can be marked delivered" |
| `deliver` by a non-superadmin/operations caller | 403 | (role-guard message) |
| unassign / deliver order not found | 400 | existing "…not found" |

### Testing
- **Catalog** (extend `CatalogOrderEventEmissionIntegrationTest`): create an order, approve it to `APPROVED` (or seed status APPROVED via `createOrder` with `status:"APPROVED"`), call `markDelivered` → status is `DELIVERED`, `delivered_at`/`delivered_by` set, a `catalog-order.upserted.v1` outbox row emitted; calling `markDelivered` on a non-APPROVED order throws `IllegalArgumentException`.
- **Stats** (extend the `orderStatsExposesActiveDeliveredServiceAndTermSpend` test): with a `DELIVERED` order and an `APPROVED` order, assert `deliveredCount` counts only DELIVERED and `activeOrders` counts the APPROVED (now active).
- **Timetable** (Part A test above).
- No FE tests (repo convention).

## Files
**school-core-service**
- Create: `src/main/resources/db/migration/catalog/V…__order_delivery_columns.sql`
- Modify: `.../persistence/TimetableRepository.java` (`deleteClassSchedule`)
- Modify: `.../api/TimetableController.java` (DELETE class-schedule endpoint)
- Modify: `.../persistence/CatalogReadRepository.java` (`markDelivered`; refine `orderStats` deliveredCount + activePred)
- Modify: the supply-orders controller (`POST /supply/orders/{id}/deliver`)
- Test: `.../persistence/TimetableRepositoryIntegrationTest.java`, `.../persistence/CatalogOrderEventEmissionIntegrationTest.java`

**frontend**
- Modify: `src/services/timetableApi.ts` (`unassignClass`)
- Modify: `src/pages/workspace/panels/setup/BellSchedulesPanel.tsx` (chip ×)
- Modify: `src/pages/workspace/panels/SaAllOrdersPanel.tsx` ("Mark delivered")
- Modify: `src/pages/workspace/utils.ts` (`prettyOrderStatus` DELIVERED)
