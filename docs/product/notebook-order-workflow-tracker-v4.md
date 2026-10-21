# Notebook Order Workflow Tracker

Version: **v4** (2026-10-19). Status: **implemented on branch `feat/notebook-order-tracker`; not yet merged or
deployed.** Built in `b83f4b1a`, then corrected by the
[2026-10-20 review fixes](../superpowers/specs/2026-10-20-notebook-tracker-review-fixes-design.md), whose owner decisions
D1 and D2 take precedence over this document; deployment steps are in the
[deploy runbook](../runbooks/notebook-tracker-deploy.md). No open questions remain;
everything below is decided by the product owner.
Supersedes [v3](notebook-order-workflow-tracker-v3.md), [v2](notebook-order-workflow-tracker-v2.md) and
[v1](notebook-order-workflow-tracker-v1.md), all kept unchanged. Revisions are new files; an earlier version
is never edited in place. This document is a specification only; no code, migration or configuration was
changed while writing it.

| Version | Date | Change |
| --- | --- | --- |
| v1 | 2026-10-11 | First draft from the request and the supplied prototype |
| v2 | 2026-10-15 | Recorded the first round of answers; added workflow/identity/tenant findings |
| v3 | 2026-10-19 | Records every answer to v2's questions and the follow-ups; removal narrowed to notebook orders; dev data drop deferred |
| v4 | 2026-10-19 | Resolves the last two questions: superadmin may approve the existing design while a change request is pending; the prototype time-slot values are the intended defaults |

## How to read this document

- **[Decided]** — stated by the product owner.
- **[Prototype]** — taken from `custoking_notebook_ops_prototype.html`.
- **[Repo]** — verified in this repository on branch `dev` (`426875da`); the file is cited.
- **[Open]** — not specified. None remain in this version.

## Scope

1. **[Decided]** For a placed **notebook** order, "View order" in All Orders shows the prototype's workflow
   screen to **superadmin and operators only**, built from the placed order's data. School admins keep the
   existing order view.
2. **[Decided]** Only each step's **work area** is built. The prototype's other tabs and its *Upcoming
   Actions* panel are not included.
3. **[Decided]** The tracker **begins at order placement**.
4. **[Decided]** Customised notebooks have **12 steps**. Wholesale (non-customised) notebooks **skip steps
   2, 3 and 4**, giving **9 steps: 1, 5, 6, 7, 8, 9, 10, 11, 12**; progress reads "x of 9".
5. **[Decided]** These changes apply to **notebook orders only**. Other catalogue categories keep their
   existing approval, quoting and status behaviour.

## Behaviour

### Sequence and drafts

- The left list shows **all steps** of the order's path. At the start every step is disabled except the first.
- A step is enabled only once its predecessor is **marked completed**; the tracker above the work area follows.
  Marking a step completed moves the user to the next one. Enforcement is **server-side**.
- Work is saved only through an explicit **Save Draft** button; nothing is auto-saved. Reopening a step shows
  the last saved draft.
- **Operators:** a step is locked once completed. **Superadmin only** can reopen or re-edit a completed step.
- When superadmin reopens a completed step, **later steps relock and revert to not-completed**; their saved data
  is kept as drafts. Each is completed again in order.
- **Superadmin cannot edit stage notes** but can edit all other step details. The assigned operator can edit a
  step's stage note again while superadmin has that step reopened.

### Assignment

- Operators are created **only by superadmin**, scoped to a **school** (school-wide) or to a **school and a
  catalogue item** (notebooks, fliers, ties, …). The existing school assignment is kept.
- At order placement the **item-specific operator** for that school and catalogue item is auto-assigned.
  If none exists, a **school-wide operator** is assigned. If **several operators match, none is picked and
  superadmin assigns manually**. Only superadmin assigns or reassigns, and only to operators.
- A **superadmin screen to create and manage these operator assignments is in scope.**
- **[Repo]** The current assignment table has `school_id` and `zone_id` only
  (`identity.user_role_assignments`); the catalogue-item dimension is new.

### Step 2 images and email

- At most **6** images in total. The **7th is rejected** with the message
  **"No more images allowed. Contact IT."**
- Only **current** images count; operators and superadmin can delete images. Allowed types: **PNG, JPEG, WEBP,
  PDF**, up to **5 MB** each. PDFs count toward the 6.
- The **Current Design Preview** shows the latest uploaded **image**; PDFs are ignored for the preview.
- An **email intent** is created **each time the count rises above 4** after having been 4 or fewer (so deleting
  down to 4 and uploading again creates another). **Recipients: all active superadmin users.** No email
  integration is built — only the structure so a sender can be added later.
- **[Repo]** Existing storage already accepts PNG/JPEG/WEBP/PDF with a 5 MB default and 10 MB cap, but its
  unique index allows **one current `DESIGN` asset per order**, so six drafts need their own records.

### Design approval (steps 2 and 3)

1. In step 2 the operator uploads drafts, uses a separate **Send for design approval** button, **then** marks
   step 2 completed. Step 3 unlocks on that completion.
2. **Only superadmin** approves or requests changes in step 3 (approval of the final draft image; comments
   optional).
3. After **Request changes**, step 2 **stays completed**; the operator may **upload and delete images** (within
   the cap) and **resubmit** for approval.
4. **[Decided]** While a change request is pending, **superadmin can still open step 3 and approve the existing
   design**; approval is not blocked until the operator resubmits.

### Sign-offs and gates

| Step | Who | Rule |
| --- | --- | --- |
| 3 Head Approval | Superadmin | Cannot complete until the design is approved |
| 8 Pre-Print Quality Gate | Superadmin | **Superadmin sign-off required**. The eight checklist items are shown as **optional** tick boxes; the school-verification field stays |
| 11 Validation Count | Operator | Operator types ordered/produced/accepted/dispatch counts. A **mismatch** is `NOT (accepted = dispatch AND accepted ≤ produced ≤ ordered)`; the mismatch **requires a discrepancy note** and **completion is then allowed**. "Ordered" is the count entered at order placement |
| 12 Delivery & Invoicing | **Superadmin** | Executive consignment sign-off required |

**[Repo]** Every non-GET request by a `SUPERADMIN` needs a fresh passkey verification at the gateway
([server.js:925-932](../../services/api-gateway/server.js#L925-L932)); approvals, sign-offs, reopening,
reassignment, contacts, time slots and operator management by superadmin will prompt for it.

### Time slots

- Each step has a time slot, **database-configurable per step** and **customisable per school per step by
  superadmin only**.
- **Defaults** are the prototype's values (below). Days are **working days: Monday to Friday**; holidays are
  not excluded. Hour-based steps count continuously but **the clock pauses over Saturday and Sunday**.
- A step's clock **starts when the step is enabled**.
- A step is shown **red when it is overdue while still open**.
- **Steps 8 and 10 have no time slot and no timer.**

| # | Step | Default |
| --- | --- | --- |
| 1 | Order Details | 4 hours |
| 2 | Designing Activity | 2 working days |
| 3 | Head Approval | 1 working day |
| 4 | CDR Conversion | 4 hours |
| 5 | Production Planning | 4 hours |
| 6 | Board Procurement | 1 working day |
| 7 | Printing Press | 4 hours |
| 8 | Pre-Print Quality Gate | none |
| 9 | Factory Handover | 4 hours |
| 10 | Production Follow-up | none |
| 11 | Validation Count | 4 hours |
| 12 | Delivery & Invoicing | 1 working day |

**[Decided]** These prototype values are the intended defaults.

### Contacts

- **School contact:** the school's `contact_email` and `contact_phone` from onboarding only (**[Repo]** no
  contact-person name exists, and none is added). Shown read-only to operators.
- **Vendor:** assigned by superadmin, **one per order**; fields **name and phone**.
- **Step 9 factory:** **separate typed text**, not the vendor record.
- **Designer card (step 2):** chosen per order from a **designer list maintained by superadmin**; fields
  **name and phone**.
- **School Feedback** shows the order's placement notes (`catalog_orders.notes`, 255 characters).

### Notes

- **Stage notes:** one editable text per step, at most **100 words**. The **assigned operator writes them on every
  step**, including steps 3, 8 and 12. Locked once the step is completed (editable again only while superadmin
  has it reopened, and only by the operator).
- **Internal notes:** a **log** of entries with author and time, **100 words per entry**, visible to
  **superadmin and operators**.
- **Step 10 follow-ups** are repeated entries.

### Header and Step 1

- Header: order ID, school, delivery date, progress ring, and a **clear summary of size and pages**
  (several size/ruling lines are summarised). **Cover GSM is dropped.**
- **Step 1 is read-only** (fetched from the order).

## Step content

| Step | Work area |
| --- | --- |
| 1 Order Details | School, order ID, quantity, delivery date, specifications — read-only |
| 2 Designing Activity | Vendor card; designer card; image upload (up to 6); Send for design approval; revision list |
| 3 Head Approval | Choose design version, comments, Approve, Request changes — superadmin only |
| 4 CDR Conversion | Final CDR filename, storage link, comments — kept as the prototype, to be finalised later |
| 5 Production Planning | Paper / inner GSM, factory capacity confirmed, start date, target completion, planning notes |
| 6 Board Procurement | Prototype UI with a **"TBD" note below the step name**; fields editable and saved as drafts; the step can be completed normally |
| 7 Printing Press | Printing vendor, job reference, quantity sent, expected return date, contact |
| 8 Pre-Print Quality Gate | Prototype checklist (optional), sample link, school verification, comments, superadmin sign-off |
| 9 Factory Handover | Making factory (typed), job reference, titles sent, titles received, contact |
| 10 Production Follow-up | Repeated entries: % complete, next follow-up date, contact |
| 11 Validation Count | Ordered (from the order), produced, accepted, dispatch, discrepancy note |
| 12 Delivery & Invoicing | Transporter, dispatch date, LR number, invoice number, delivery proof link, superadmin sign-off, invoice status |

## What changes for notebook orders

- Notebook orders **stop using** the existing design-approval, final-approval, quoting, pricing, delivery and
  vendor-paid fields and the statuses `DESIGN_APPROVAL`, `CUSTOKING_APPROVED`, `FULFILLED`, `DELIVERED`.
  The Order Approvals screens and final-approval step do not apply to notebook orders.
- **The columns remain** because other categories still use them; this is not a schema removal.
- The order `status` column **shows the current tracker step** (for example "Printing Press"); **draft orders show
  "Draft" and cancelled orders show "Cancelled"**.
- **Deferred:** dropping data from the dev environment is **not part of this work**. No data-deleting migration is
  written.
- **[Repo]** These fields are read in school-core, platform-service reporting and the frontend (including
  `vendor_paid_at` in operations-service vendor-dues and reporting), so notebook orders must be excluded from
  those paths without changing other categories.

## Verified baseline in the repository

- **[Repo]** In both All Orders panels "View" on a form-version-2 order opens `ProductOrderDetail`
  ([SaAllOrdersPanel.tsx:258](../../frontend/src/pages/workspace/panels/SaAllOrdersPanel.tsx#L258),
  [AdminOrdersPanel.tsx:154](../../frontend/src/pages/workspace/panels/AdminOrdersPanel.tsx#L154)); the tracker
  attaches there for notebook orders.
- **[Repo]** Order data is in the school-core `catalog` schema with forced row-level security on `school_id`
  (`catalog_orders`, `catalog_order_lines`, `catalog_order_assets`).
- **[Repo]** The operations-service `workflow` schema has `workflow_definitions`, `workflow_steps`,
  `workflow_instances` (unique per `(school_id, entity_type, entity_id)`, status limited to PENDING / IN_PROGRESS /
  APPROVED / REJECTED / CANCELLED / COMPLETED) and `workflow_actions` (`notes varchar(1000)`). It has **no
  assignee, per-step data, time slot or per-step timestamps**, and the order data is in a different service.
- **[Repo]** `OPERATIONS` already holds `workflow:read` and `workflow:act`; whether `SUPERADMIN` does was not verified.
- **[Repo]** The seeded `SUPPLY_ORDER_DEFAULT` workflow definition is the existing 2-step supply-order approval.

## Design (follows the decisions above)

- **Workflow tables are reused and extended** with additive columns in the operations-service `workflow` schema:
  assignee, per-step draft and completed data, time slot, step start and completion timestamps, completed-by,
  and reopen history. A **new definition** for the notebook tracker; Wholesale uses **the same definition with
  steps 2–4 skipped**. One instance per order, keyed `(school_id, 'NOTEBOOK_ORDER', order id)`.
- **Permissions:** `SUPERADMIN` is granted `workflow:read` and `workflow:act` by migration; no new permission names.
- The tracker reads order data from school-core **through an API**, not by joining schemas.
- **New:** per-step time-slot defaults and per-school overrides; superadmin-maintained designer list; vendor
  record per order; catalogue-item scope on operator assignments; design-draft records (up to 6, with type and
  size); email-intent records (order, count, recipients, created-at, status "not sent").
- Optimistic versioning (as `catalog_orders.version`) so two users cannot silently overwrite a step.
- **Frontend:** a tracker component opened from the existing view branches for notebook orders, shown only to
  `SUPERADMIN` and `OPERATIONS`; layout follows the prototype.

## Acceptance criteria

1. School admins do not see the tracker; superadmin and the assigned operator do; an operator who is neither
   school-wide nor item-specific for the order cannot open it.
2. A new customised order shows 12 steps, a Wholesale order 9; only step 1 is enabled; the server rejects saving
   or completing a locked step.
3. Save Draft stores values; reopening shows the last draft; nothing auto-saves.
4. Completing a step unlocks the next and locks it for operators; only superadmin reopens it; reopening reverts
   later steps to not-completed with data kept.
5. Item-specific operator is auto-assigned; with no item-specific operator the school-wide operator is; several
   matches leave the order unassigned for superadmin.
6. Step 2: 6 files max; the 7th shows "No more images allowed. Contact IT."; only current files count; one email
   intent is created each time the count rises above 4; the preview shows the latest image and ignores PDFs.
7. Send for design approval precedes completing step 2; only superadmin approves or requests changes; after a
   change request the operator can upload, delete and resubmit, and superadmin can still approve the existing
   design while the request is pending.
8. Step 8 completes only with superadmin sign-off; step 12 only with superadmin sign-off; step 11 completes on
   mismatch only with a discrepancy note.
9. Stage notes reject over 100 words; superadmin cannot edit them; they lock on completion and reopen for the
   operator only while superadmin has the step reopened. Internal notes entries reject over 100 words.
10. Time slots come from the database per step and per school; weekends pause the clock; an open overdue step shows
    red; steps 8 and 10 show no timer.
11. The order status shows the current step, "Draft" or "Cancelled"; notebook orders no longer use the removed
    statuses or approval screens; other categories are unchanged.
12. Tests cover sequence, role and school scoping, operator auto-assignment, image cap and email intents, reopen
    reverting, working-day timing, and the Wholesale path.

## Open questions

None. Every question raised in v1–v3 has been answered by the product owner. Implementation has not started and
waits for an explicit go-ahead.
