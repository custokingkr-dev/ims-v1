# School-Scoped Fee Bands

**Date:** 2026-07-06
**Status:** Approved (design)
**Service:** `school-core-service` (`fee` schema) and `frontend`

---

## Context

Fee bands are currently **global**: `fee.fee_bands` and `fee.fee_items` have no `school_id`
and no RLS (V7 enables RLS only on `fee_assignments` / `payment_records`). So every school
sees and can edit every other school's bands, and a band can be assigned to students across
multiple schools. This surfaced as a bug: a school admin cannot delete a band because it is
assigned to **another** school's students — the delete guard counts assignments under the
admin's RLS scope (sees 0), then `DELETE fee_bands` hits the other school's assignment via the
FK. Blocking is correct, but the model is wrong: **fee configuration should be per-school.**

Decision: make fee bands (and their items) school-owned, with a **copy-per-school** migration
that splits today's shared bands into independent per-school copies.

## Decisions (locked during brainstorming)

- **Ownership: copy-per-school, full isolation.** Each band belongs to exactly one school. A
  band shared by N schools today becomes N independent copies. No global "template" tier.
- **Existing bands migrated by copy-per-school:** for each (band, school) that has assignments,
  create a school-owned copy of the band + its items and repoint that school's assignments to
  the copy; then drop the originals.
- **Unassigned bands (no assignments, unattributable) are dropped** with their items. (`fee_bands`
  has no owner/creator column, so they can't be attributed; they are by definition unused.)
- **Going forward:** creating a band tags it with the creator's `school_id`; RLS auto-scopes all
  band/item reads. Superadmin bypasses RLS (sees all) and must specify the target school when
  creating.
- **Tests: yes.** Testcontainers coverage of the migration (shared → N copies, assignments
  repointed, items copied, unassigned dropped, RLS enforced) and the scoped repo. This rewrites
  financial data, so it is tested.
- **Delete band** becomes clean once bands are same-school (the count guard is accurate); the
  FK-catch backstop from the recent hotfix stays.

---

## Data model & migration (`fee` schema)

### Migration V8 (`fee` history — current max is V7)

Three phases in one forward-only migration (runs as `appuser`/owner → RLS-exempt, sees all rows):

```sql
-- Phase 1: add nullable school_id to bands + items.
ALTER TABLE fee.fee_bands ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE fee.fee_items ADD COLUMN IF NOT EXISTS school_id BIGINT;

-- Phase 2: copy-per-school backfill.
DO $$
DECLARE r RECORD; new_band TEXT;
BEGIN
  FOR r IN
      SELECT DISTINCT band_id, school_id
      FROM fee.fee_assignments
      WHERE school_id IS NOT NULL
  LOOP
      new_band := gen_random_uuid()::text;
      INSERT INTO fee.fee_bands
          (id, name, class_from, class_to, discount, active_schedules_csv,
           created_at, updated_at, academic_year_id, school_id)
      SELECT new_band, name, class_from, class_to, discount, active_schedules_csv,
             created_at, now(), academic_year_id, r.school_id
      FROM fee.fee_bands WHERE id = r.band_id;

      INSERT INTO fee.fee_items
          (id, name, frequency, amount, created_at, updated_at, band_id, school_id)
      SELECT gen_random_uuid()::text, name, frequency, amount, created_at, now(), new_band, r.school_id
      FROM fee.fee_items WHERE band_id = r.band_id;

      UPDATE fee.fee_assignments
         SET band_id = new_band
       WHERE band_id = r.band_id AND school_id = r.school_id;
  END LOOP;

  -- Originals (and truly-unassigned bands) still have school_id IS NULL → drop them + their items.
  DELETE FROM fee.fee_items WHERE band_id IN (SELECT id FROM fee.fee_bands WHERE school_id IS NULL);
  DELETE FROM fee.fee_bands WHERE school_id IS NULL;
END $$;

-- Phase 3: enforce + RLS (mirrors fee_assignments V7 policy).
ALTER TABLE fee.fee_bands ALTER COLUMN school_id SET NOT NULL;
ALTER TABLE fee.fee_items ALTER COLUMN school_id SET NOT NULL;

ALTER TABLE fee.fee_bands ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON fee.fee_bands;
CREATE POLICY tenant_isolation ON fee.fee_bands
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE fee.fee_items ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON fee.fee_items;
CREATE POLICY tenant_isolation ON fee.fee_items
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

CREATE INDEX IF NOT EXISTS idx_fee_bands_school_year ON fee.fee_bands (school_id, academic_year_id);
CREATE INDEX IF NOT EXISTS idx_fee_items_school_band ON fee.fee_items (school_id, band_id);
```

**Migration properties**
- Deterministic in structure (not in generated ids — each env mints its own copy ids; that is
  fine for a one-time forward-only data migration).
- `fee_assignments.school_id` is `NOT NULL` since V6, so every assignment is attributable; the
  `IS NOT NULL` filter is belt-and-suspenders.
- `payment_records` reference `fee_assignments(id)` (unchanged), not bands, so repointing
  `band_id` on assignments does not touch payments.
- **Irreversible + rewrites financial data.** Dev first (prod is gated); the plan includes the
  Testcontainers test as the correctness gate before it ever reaches prod.

---

## Backend (`school-core-service`, `fee`)

Once RLS is on `fee_bands`/`fee_items`, **reads auto-scope** for the runtime `app_rt` role, and
superadmin (GUC `app.bypass_rls=on`) sees all. The code changes:

- **`FeeReadRepository.createBand(...)`**: set `school_id` on the inserted band (and on each
  inserted item) from the resolved tenant school. The RLS `WITH CHECK` requires
  `school_id = current tenant`, so an admin creating a band writes their own school; a superadmin
  must supply the target `schoolId` (validated).
- **`createItem` / `updateItem`**: stamp/keep `school_id` from the owning band.
- **Band/list/query methods** (`bands`, `bandWithItems`, `bandRecord`, `matchBand`, the
  fee-structure/dashboard reads): keep working via RLS; where a superadmin passes an explicit
  `schoolId` (via `schoolScopedParams`), add `AND school_id = :schoolId` so superadmin views a
  single school rather than all bands merged. Follow the existing `schoolScopedParams` idiom.
- **`deleteBand(...)`**: unchanged in shape — now the assignment count is same-school and
  accurate, so the guard yields the correct numbered 400; the `DataIntegrityViolationException`
  catch stays as a backstop.
- **Controllers** (`FeeReadController` `/api/v1/fees`, `FeePublicCompatibilityController`
  `/api/v1/fee-structure/**`): pass the resolved school into create; no contract change to the
  frontend.

---

## Frontend (`frontend`)

- `FeeStructurePanel` already sends `schoolScopedParams` on its reads and calls
  `POST /fee-structure/band` for create. With server-side scoping + RLS it **mostly just works**:
  a school admin now sees only their own bands and can create/edit/delete them.
- Confirm the create path carries the school (school admins implicitly via TenantScope;
  superadmin must have a selected school in context). If superadmin fee management needs an
  explicit school picker for *creating* bands, add it (the panel is already school-aware for
  reads).
- No new components expected; verify the delete flow now succeeds for a school's own unshared
  bands.

---

## Scoping, auth, entitlement

- Unchanged token/permission gates; the new enforcement is **RLS on `fee_bands`/`fee_items`**
  (defense in depth) plus explicit `school_id` filtering for superadmin views.
- Superadmin (`app.bypass_rls=on`) retains cross-school visibility/management.

## Testing

**Backend (Testcontainers `postgres:16`, migrate `fee` [+ `tenant_school`/`student` as the
existing fee tests do]):**
- **Migration correctness:** seed a global band assigned in **two** schools (+ items) and a
  second band assigned in **one** school and a **third** band with **no** assignments; run the
  migration; assert: the shared band became **2** school-owned copies (each with copied items),
  each school's assignments repointed to *its* copy, the single-school band became 1 copy, the
  unassigned band **and its items are gone**, and no band/item has a null `school_id`.
- **RLS:** as `app_rt` with a school GUC, only that school's bands/items are visible; a
  cross-tenant insert (WITH CHECK) is rejected; superadmin bypass sees all.
- **Scoped repo:** `createBand` stamps `school_id`; `deleteBand` on a school's own unshared band
  succeeds; delete of a band with same-school assignments returns the numbered 400.

**Frontend:** `npm run build`; a manual dev check — as a school admin, see only your bands,
create one, delete an unassigned one (succeeds), and confirm another school's bands are not
visible.

## Rollout / risk

- **Irreversible financial-data migration.** Ship to **dev** first (prod gated); the
  Testcontainers migration test is the gate. Before prod promotion, take a DB snapshot/backup and
  spot-check band/assignment counts pre/post.

## Out of scope (YAGNI / later)

- A global fee-band **template** library schools can clone from (explicitly rejected — full
  isolation chosen).
- Cross-school band copy/share tooling.
- Reworking `fee_assignments`/`payment_records` scoping (already school-scoped).
- Any change to the fee **amounts / discounts / payment** logic — this is purely an ownership +
  isolation change.
