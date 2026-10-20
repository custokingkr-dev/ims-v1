# Notebook order tracker — review fixes (design)

**Date:** 2026-10-20
**Branch:** `feat/notebook-order-tracker` = `notebook-order-form` (b83f4b1a) + dev (cec7aff6)
**Product spec:** `docs/product/notebook-order-workflow-tracker-v4.md` (authoritative for behaviour)

A review of b83f4b1a found the defects below. This document records what each fix must achieve. The product
spec still governs tracker behaviour; where this document and the spec disagree, the spec wins, except for
the two owner decisions in §1.

## 1. Owner decisions (2026-10-20)

- **D1. Orders placed before the tracker keep the old flow.** A notebook order placed before this feature
  keeps the quote → approve → deliver flow it started in and appears in Order Approvals as before. Only orders
  placed after the feature ships use the tracker. No data is deleted or migrated.
- **D2. Item-scoped operators get tracker access only.** An OPERATIONS user whose only assignment at a school
  is item-scoped (for example NOTEBOOKS) may see and work that item's orders in the tracker and nothing else:
  no students, fees, attendance, other orders or other workflows at that school. Operators with a school-wide
  assignment keep their current access.

## 2. Findings and required outcomes

### Must fix
1. **CI authorization audit fails.** `OperatorScopeController` and `NotebookTrackerController` use bare
   `requireToken(token)`. Every user-facing method must use the scoped form; machine endpoints must move out
   of user-facing controllers.
2. **Machine calls use static shared tokens on public paths.** All tracker service-to-service calls move to
   the established pattern: `/api/v1/internal/**` controllers in `api/internal/`, Cloud Run IAM, and
   `MachineCallerFilter` with a per-route caller allow-list. No shared secrets remain.
3. **Nothing is wired for deployment.** Peer URLs and caller allow-lists go into `deploy/cloudrun/*.yaml`
   (composed from existing target params; `targets-*.yaml` must not change) and the invoker grants into
   `scripts/configure-runtime-service-accounts.ps1`.
4. **Operators cannot write to the tracker once deployed.** The OPERATIONS role has `workflow:read` but not
   `workflow:act`; tests injected the missing permission. The tracker gets its own permission codes.
5. **Item scope leaks** (D2): `effectivePermissions` ignores `catalog_category`; `loginPermissions` grants the
   full OPERATIONS set to item-only users; `branch_id` gives them a school context, and RLS then admits all of
   that school's rows.
6. **Old orders are stranded** (D1).

### Should fix
7. Order placement calls identity and operations inside its transaction while holding the order's row lock;
   a cold or slow peer leaves the order "tracker pending" with only a manual retry. Tracker creation must
   happen after commit and be retried automatically until it succeeds.
8. Tracker status reaches school-core by one fire-and-forget push after commit: a lost push is permanent and
   concurrent pushes can arrive out of order. Delivery must be retried and ordered, and school-core must accept
   only known status codes.
9. Design send/approve call school-core while holding the tracker row lock; school-core draft upload/delete
   calls operations while holding the order row lock. Peer calls happen before locks are taken.
10. Reopening step 2 or 3 keeps the old design approval, so replaced drafts can pass without approval.
11. A sign-off is not tied to the data it signed: edits after sign-off keep it, and reopening an earlier step
    brings a later step back already signed off.
12. Concurrent tracker creation for one order returns 409 instead of the existing tracker.
13. A stage note over 4000 characters returns 500 (the database CHECK) instead of 400.
14. Append-only tables (`workflow_internal_notes`, `workflow_follow_ups`) are granted UPDATE and DELETE; no
    database constraint enforces one ACTIVE step per tracker; `idx_wis_assignee_active` is redundant with V9.
15. Integration tests give operators no school context and inject permissions they do not hold in production,
    which hid findings 4 and 5.

### Frontend and developer experience
16. Tracker load failures silently fall back to the old order screen with every action hidden; every
    non-notebook order fires two failing tracker requests per view.
17. Set-up panel load failures show as empty lists ("No operator assignments", "No designers").
18. Revoking an operator assignment has no confirmation.
19. An unassigned order can only be assigned one step at a time (`assignOrder` is unused).
20. The worklist and set-up panels use `ck-product-*` classes without importing `product-form.css`.
21. Spec deviations: vendor card belongs on step 2 (not step 9); step 7 lacks vendor and contact; the header
    lacks delivery date and the size/pages summary; internal notes lack the author; step 6 lacks its "TBD" note;
    step 2 lacks the current-design preview, and "latest" must ignore PDFs; thumbnails download PDFs only to
    discard them.
22. Worklist due dates use the browser time zone; the tracker uses the school's.
23. Accessibility: set-up tabs lack tabpanel and arrow keys; worklist radios lack a fieldset; step-2 thumbnails
    are toggle buttons that do nothing; the stepper shows bare numbers.
24. About 12 school-core and identity calls are hand-written in the frontend with no OpenAPI contract.
25. `docker-compose.yml` (base) now stores uploads on container disk and carries tracker tokens; the base
    default must stay `gcs`, and local-only settings belong in `docker-compose.local.yml`.
26. Spec v4 and the implementation plan still say "not implemented".

## 3. Design

### 3.1 Machine calls (findings 1–3, 9)
| Edge | Callee route (method unchanged from the branch) | Caller allow-list env (callee manifest) |
|---|---|---|
| school-core → operations: create tracker | `/api/v1/internal/notebook-trackers` | `NOTEBOOK_TRACKER_CALLER_SERVICE_ACCOUNTS` |
| school-core → operations: image access | `/api/v1/internal/notebook-trackers/{orderId}/image-access` | `NOTEBOOK_TRACKER_CALLER_SERVICE_ACCOUNTS` |
| operations → school-core: draft images | `/api/v1/internal/catalog-orders/{orderId}/draft-images` | `CATALOG_TRACKER_CALLER_SERVICE_ACCOUNTS` |
| operations → school-core: tracker status | `/api/v1/internal/catalog-orders/{orderId}/tracker-status` | `CATALOG_TRACKER_CALLER_SERVICE_ACCOUNTS` |
| school-core → identity: resolve operator | `/api/v1/internal/operator-directory/resolve` | `OPERATOR_DIRECTORY_RESOLVE_CALLER_SERVICE_ACCOUNTS` |
| operations → identity: operator eligible | `/api/v1/internal/operator-directory/eligible` | `OPERATOR_DIRECTORY_ELIGIBILITY_CALLER_SERVICE_ACCOUNTS` |

- `{orderId}` matches `[A-Za-z0-9_-]{1,255}` in the filter registry, never `.+`.
- Callers fetch the identity token from the metadata server with `audience=<peer base URL>&format=full` and
  cache it per audience for 45 minutes (the gateway's policy). They forward no user-context headers.
- **Local trust.** `MachineCallerFilter` admits registered internal routes without a token only when
  `MACHINE_CALLER_LOCAL_TRUST=true` AND the `K_SERVICE` environment variable is absent (Cloud Run always sets
  it). It logs a WARN at startup when active. Unregistered internal routes stay 403. Only
  `docker-compose.local.yml` sets it.

### 3.2 Permissions and item scope (findings 4, 5, 15)
- Identity migration V12 adds `notebook-tracker:read` and `notebook-tracker:act`, granted to SUPERADMIN and
  OPERATIONS. The tracker checks these, not `workflow:*`.
- `effectivePermissions` counts only rows with `catalog_category IS NULL`.
- `loginPermissions` adds the OPERATIONS codes only when the user has a school-wide operator school. When the
  user has item scopes, it adds exactly `workspace:access`, `notebook-tracker:read`, `notebook-tracker:act`,
  `order:read`, `order:update`.
- For an OPERATIONS user with item scopes, the introspection `branchId` and the JWT `sid` are null unless
  `branch_id` is one of their school-wide operator schools. Users without item scopes are unaffected.
- Operations' tracker list/view/mutate also check the school against the caller's operator schools and NOTEBOOKS
  scopes (defence in depth beside RLS).

### 3.3 Placement and status delivery (findings 6–8)
- At PLACE, a notebook (form v2) order gets `attributes.trackerManaged = true` and `trackerPending = true`, and
  a row in a new `catalog.notebook_tracker_creation_queue`, all in the placing transaction. No peer call happens
  inside it. After commit the row is attempted once; the existing `POST /api/v1/internal/outbox/relay` trigger
  (Cloud Scheduler, every minute) drains the rest with backoff. Success deletes the row and clears
  `trackerPending`.
- An order is **tracker-managed** iff `attributes.trackerManaged = true`. Notebook orders that are not
  tracker-managed keep every pre-feature behaviour (D1).
- Operations writes a coalescing row per order to a new `workflow.workflow_tracker_status_outbox` inside the
  mutating transaction, carrying the status and the instance version. After commit it pushes once; the
  operations relay trigger drains the rest. A row is deleted only if its version is still the one sent.
- School-core applies a status only if its `trackerVersion` is newer than the stored
  `attributes.trackerStatusVersion`; an older or equal version is a successful no-op. Only the twelve step codes
  and `COMPLETED` are accepted.

### 3.4 Tracker correctness (findings 9–14)
- Reopening step 2 sets the design state to `NOT_SENT`; reopening step 3 sets `SENT`; both clear
  `approved_draft_ref`. Reopening any step ≤ 3 applies the rule for the lowest design step it reverts.
- Reopening clears sign-offs on the reopened step and every later step. Saving a step draft whose data
  differs from the stored data (ignoring the sign-off) clears that step's sign-off.
- Operations migration V11: revoke UPDATE and DELETE on the two append-only tables from `app_rt`; add a
  partial unique index enforcing one ACTIVE step per instance; drop `idx_wis_assignee_active`.

### 3.5 Frontend (findings 16–24)
Implements spec v4's screens as written, with the error, confirmation, accessibility and contract fixes above.
The tracker opens only for tracker-managed notebook orders.

## 4. Out of scope
- Deleting dev test data (spec v3 defers it).
- Sending design-draft emails (spec: intent records only).
- Hour-based deadlines running overnight: spec v4 §Time slots says hours count continuously and pause only at
  weekends. That is intended.
- Enabling the product form in prod (`catalog_product_form_enabled` stays `"false"` there).
