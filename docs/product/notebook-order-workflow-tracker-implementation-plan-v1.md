# Notebook Order Workflow Tracker — Implementation Plan

Version: **v1** (2026-10-19). Implements [spec v4](notebook-order-workflow-tracker-v4.md).
Branch: `notebook-order-form`, created from `origin/dev` at `8cb87e5b`. Revisions of this plan are new
files; this one is not edited in place.

Tags: **[Spec]** from spec v4 · **[Repo]** verified in this repository · **[Plan]** an implementation choice
that spec v4 does not dictate · **[Flag]** a place where the spec cannot be followed literally or needs a
product decision before that phase.

## Verified constraints that shape the plan

- **[Repo]** The gateway already routes `/api/v1/workflows/` to operations-service
  ([routing.js](../../services/api-gateway/routing.js)); tracker endpoints under that prefix need no new route
  but do need the route inventory and API contract updated (`api-route-inventory.json`, `api-contract.js`).
- **[Repo]** Workflow write endpoints are guarded by a service token plus `workflow:act`
  (`WorkflowReadController`); `OPERATIONS` already holds `workflow:read` / `workflow:act`.
- **[Repo]** Locally the outbox publisher only logs (`*_OUTBOX_REQUIRE_REAL_PUBLISHER: "false"`), so **event-driven
  creation of a tracker cannot work locally**. Placement must create the tracker through a synchronous call.
- **[Repo]** Internal machine-to-machine paths (`/api/v1/internal/...`) authenticate with Google OIDC tokens and
  cannot be exercised locally; peer calls that work locally use a shared-token header on ordinary paths
  (as `ModuleEntitlementClient` does).
- **[Repo]** The existing asset index allows one current `DESIGN` asset per order, and storage lives in school-core.
- **[Repo]** The Spring profile in local docker-compose is `prod`, and the OTel bean issue means the
  five Java services only start with local overrides that are **not in the repo**.

## Architecture choices

1. **[Spec]** Tracker state lives in the operations-service `workflow` schema.
2. **[Flag]** Spec v4 says "add columns to the existing workflow tables". Per-step state (draft, completed data,
   assignee, start/finish times, stage note) is one row **per order per step**; none of the four existing tables
   can hold that as extra columns. The plan adds columns where they fit (definition steps get default time slots;
   instances get operator/vendor/designer references) and **adds new tables for per-instance step state, time-slot
   overrides, internal notes, designers and follow-ups**. This is a reading of the decision, not a reversal; say
   so if you want it done differently.
3. **[Plan]** Business rules (sequence, reopen, working-time deadlines, note limits, step-11 mismatch, image
   cap) are a pure, framework-free Java package with unit tests, so they can be verified without a database.
4. **[Plan]** The order's draft images and the email-intent record live in **school-core** next to the order assets
   (where the storage and the order's `school_id` security already are); operations-service stores only the
   tracker.
5. **[Plan]** The tracker is created at placement by school-core calling operations-service
   (`POST /api/v1/workflows/instances`-style idempotent create, shared-token header). **[Flag]** If that call fails
   at placement, the plan is to place the order and mark the tracker "pending creation", retried on the next view,
   rather than failing the school's order. Confirm.

## Phases

Each phase ends with tests run and the stack rebuilt and exercised, and is reported before the next begins.

### Phase 1 — Tracker rules (operations-service, pure logic) — **starting now**
Step catalogue and the 12-step / 9-step paths; sequence enabling; complete, reopen (later steps revert); working-day
deadline and overdue calculation with weekend pause; 100-word note validation; step-11 mismatch; image-cap and
email-intent rule. Unit tests for each.

### Phase 2 — Tracker persistence and API (operations-service)
Flyway migration (new definition + per-step state, time slots and overrides, designers, internal notes, follow-ups,
vendor); repositories under forced row-level security; endpoints `GET/POST /api/v1/workflows/notebook-orders/...`
(read, save draft, complete, reopen, reassign, notes, vendor, designer, time slots); superadmin gets
`workflow:read` / `workflow:act` (identity migration); gateway route inventory and contract updated; integration tests.

### Phase 3 — Operators scoped by catalogue item (identity-service)
Catalogue-item dimension on operator assignments; superadmin-only create/list/disable API; auto-selection rules
(item-specific, else school-wide, several matches → unassigned); passkey step-up already applies to superadmin writes.

### Phase 4 — Draft images and email intent (school-core)
New draft asset kind exempt from the one-current rule; 6-image cap; delete; PNG/JPEG/WEBP/PDF, 5 MB; the
"No more images allowed. Contact IT." rejection; email-intent record each time the count rises above 4;
placement hook that creates the tracker; notebook orders stop using the approval/quote/delivery/vendor-paid path
and show the current step, "Draft" or "Cancelled" as status — **without** changing other categories.

### Phase 5 — Frontend
Tracker screen opened from "View"/"View order" for notebook orders (superadmin and operators only), all 12 step
work areas, header summary, design preview, school feedback, internal notes, time-slot colouring; the superadmin
operator-assignment screen; designer-list and vendor entry.

### Phase 6 — Verification and docs
Repository test catalogue for the touched services, local end-to-end run as superadmin and as an operator
(including the Wholesale 9-step path), update of the operations notes, and a record of what was and was not verified.

## Not in this work

Dev-environment data drop (deferred by the product owner); any email sending; changes to non-notebook
categories; committing or pushing. Nothing is committed until you ask.

## Known local-environment gaps (not caused by this feature)

- The `OTEL_SDK_DISABLED` / `SPRING_PROFILES_ACTIVE` overrides needed to run the stack locally are in a scratchpad
  file, not the repo. A repo fix is a separate decision.
- The passkey prompt appears for every superadmin write; local WebAuthn settings are unset, so those writes may be
  blocked locally. Operator-side flows are unaffected.
