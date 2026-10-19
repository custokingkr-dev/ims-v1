# Notebook Order Workflow Tracker

Version: **v2** (2026-10-15). Status: **proposed, not implemented.**
Supersedes [v1](notebook-order-workflow-tracker-v1.md), which is kept unchanged. Revisions are new
files (`-v3`, …); an earlier version is never edited in place. This document is a specification only;
no code, migration or configuration was changed while writing it.

| Version | Date | Change |
| --- | --- | --- |
| v1 | 2026-10-11 | First draft from the product request and the supplied prototype |
| v2 | 2026-10-15 | Records the product owner's answers to v1's open questions; adds findings from the workflow, identity and tenant schemas; lists the questions that remain |

## How to read this document

- **[Decided]** — stated by the product owner (the original request, or the answers to v1's questions).
- **[Prototype]** — taken from `custoking_notebook_ops_prototype.html`.
- **[Repo]** — verified in this repository on branch `dev` (`426875da`); the file is cited.
- **[Open]** — not specified and not derivable from the repository; collected in
  [Open questions](#open-questions). Nothing marked **[Open]** has a default in this document.

## Scope

1. **[Decided]** For a placed **notebook** order, "View order" in All Orders shows the prototype's
   workflow screen, to **superadmin and operators only**. It shows details fetched from the order placed
   by the school admin or superadmin. School admins keep the existing order view.
2. **[Decided]** Only each step's **work area** is built. The prototype's other tabs
   (School & vendor, Checklist, Activity history) are not part of this feature.
3. **[Decided]** The **tracker begins at order placement.**
4. **[Decided]** The tracker replaces the existing approval, quoting and delivery flow
   (see [Removals](#removals-decided)).

### Which orders and how many steps

- **[Decided]** **Customised** notebooks: all **12** steps.
- **[Decided]** **Non-customised (Wholesale)** notebooks: steps **2, 3 and 4 do not exist**. The order has
  **9 steps: 1, 5, 6, 7, 8, 9, 10, 11, 12**, step 5 follows step 1, and progress reads "x of 9".
- **[Repo]** Notebook orders from the structured form are *form version 2*; "Customised / Wholesale" is
  the `CUSTOMIZATION` option group.

## Decisions

### Sequence and enabling — [Decided]

1. The left list shows **all steps** at all times (all 12, or the 9 above for Wholesale).
2. At the start every step is disabled except the first. A step is enabled only when its predecessor is
   **marked completed**. The tracker above the work area follows the same state.
3. Enforcement is **server-side**: saving or completing a locked step is rejected.
4. Marking a step completed moves the user to the next step.

### Draft and completion — [Decided]

1. Work is saved by an explicit **Save Draft** button. Nothing is auto-saved.
2. Reopening a step shows the **last saved draft** for that step.
3. **Operators:** a step is **locked once completed**.
4. **Superadmin only** can reopen or re-edit a completed step.

### Assignment — [Decided]

1. Steps are **auto-assigned to an operator**. Every school has at least one operator.
2. Only **superadmin** can assign or reassign work, and only **to operators**.
3. **Operators may also be created per catalogue item** (notebooks, fliers, ties, and so on). The existing
   school assignment is **kept**, not replaced. **[Repo]** The existing assignment has a school and a zone
   but no catalogue dimension (`identity.user_role_assignments`: `school_id`, `zone_id`). **[Open]** how the two
   combine, and which operator is chosen automatically — Q1.

### Step 2 images — [Decided]

1. **At most 6** images in total (hard cap); the 7th is not accepted.
2. Uploads 1–4 are normal. The **5th and 6th are accepted**.
3. An **email intent is created once, when the total first exceeds 4** (the 5th image).
4. **Recipients: all active superadmin users.**
5. **No email integration is built.** The feature prepares the structure so an email can be sent when the
   count exceeds 4; actually sending is out of scope. **[Repo]** The only mailer in the repo is the
   password-reset SMTP mailer in identity-service, disabled by default; it is not reused here.
6. **Current Design Preview** (right column) always shows the **latest image uploaded in step 2**.

### Step approvals and sign-offs — [Decided]

| Step | Who | Rule |
| --- | --- | --- |
| 3 Head Approval | Superadmin only | Approves the final draft image; comments optional; **"Request changes" exists**; the step cannot complete until approved |
| 8 Pre-Print Quality Gate | Superadmin sign-off | **All eight checks are not required**; the superadmin sign-off **is** required |
| 11 Validation Count | Operator | Operator **types all counts**; reconciliation is required; **completion is allowed on mismatch with a discrepancy note** |
| 12 Delivery & Invoicing | Executive sign-off | Executive consignment sign-off is required |

**[Repo]** Every non-GET request by a `SUPERADMIN` needs a fresh passkey verification at the gateway
([server.js:925-932](../../services/api-gateway/server.js#L925-L932)). Superadmin approvals, sign-offs,
reassignment, reopening, contact entry and time-slot edits will therefore prompt for the passkey.

### Time slots — [Decided]

1. A time slot is shown for each step, as in the prototype.
2. Time slots are **database-configurable for each step**, and **customisable per school per step by
   superadmin only**.
3. **Default values follow the prototype labels** for now (table below).
4. Breach rule as stated: *"when the timer is breached, it is marked as red if the task is marked
   completed."* **[Open]** exact meaning — Q4.

### Contacts and feedback — [Decided]

1. **School contact** comes from **school onboarding**. **[Repo]** The school record has only
   `contact_email` and `contact_phone` (`tenant_school.schools`); there is no contact-person name column.
   **[Open]** Q6.
2. **Vendor contact** is assigned by **superadmin**, **one vendor per order**.
3. The **designer card stays** in step 2. **[Open]** where its data comes from — Q6.
4. **School Feedback** shows the order's placement notes (`catalog_orders.notes`, `varchar(255)`).
   Operators see contacts read-only.

### Notes — [Decided]

1. **Stage notes:** one editable text per step, **at most 100 words**.
2. **Internal notes** panel: included. The prototype's *Upcoming Actions* panel is **not** included.
3. **Step 10 follow-ups** are **repeated entries**.

### Header and Step 1 — [Decided]

1. Size and pages are **summarised clearly in the header**. **Cover GSM is dropped.**
2. **Step 1 fields are read-only** (fetched from the order).
3. **[Repo]** An order has one line per size/ruling combination, each with its own page count, so the
   summary must handle several lines (design below).

### Step content

| Step | Work area | Status |
| --- | --- | --- |
| 1 Order Details | School, order ID, quantity, delivery date, specifications — read-only | [Decided] |
| 2 Designing Activity | Vendor and designer cards; draft upload (rules above); revision list | [Decided] / [Prototype] |
| 3 Head Approval | Choose design version, comments, Approve, Request changes | [Decided] |
| 4 CDR Conversion | Final CDR filename, storage link, comments — **kept as prototype, to be finalised later** | [Decided] |
| 5 Production Planning | Paper / inner GSM, factory capacity confirmed, start date, target completion, planning notes | [Decided] ("yes" to the v1 draft) |
| 6 Board Procurement | **Prototype UI kept, with a "TBD" note below the step name**; calculation to be finalised | [Decided] |
| 7 Printing Press | Printing vendor, job reference, quantity sent, expected return date, contact | [Prototype] |
| 8 Pre-Print Quality Gate | Prototype checklist and fields; **superadmin sign-off** required | [Decided] / [Prototype] |
| 9 Factory Handover | Making factory, job reference, titles sent, titles received, contact | [Prototype] |
| 10 Production Follow-up | Repeated entries: % complete, next follow-up date, contact | [Decided] / [Prototype] |
| 11 Validation Count | Ordered (from the order), produced, accepted, dispatch — **typed by the operator**; discrepancy note | [Decided] |
| 12 Delivery & Invoicing | Transporter, dispatch date, LR number, invoice number, delivery proof link, executive sign-off, invoice status | [Prototype] / [Decided] |

### Default time slots (from the prototype labels)

| # | Step | Default |
| --- | --- | --- |
| 1 | Order Details | 4 hours |
| 2 | Designing Activity | 2 working days |
| 3 | Head Approval | 1 working day |
| 4 | CDR Conversion | 4 hours |
| 5 | Production Planning | 4 hours |
| 6 | Board Procurement | 1 working day |
| 7 | Printing Press | 4 hours |
| 8 | Pre-Print Quality Gate | "Before bulk print" — **not a duration** [Open, Q4] |
| 9 | Factory Handover | 4 hours |
| 10 | Production Follow-up | "Daily follow-up" — **not a duration** [Open, Q4] |
| 11 | Validation Count | 4 hours |
| 12 | Delivery & Invoicing | 1 working day |

## Removals — [Decided]

- The frontend order statuses **`DESIGN_APPROVAL`, `CUSTOKING_APPROVED`, `FULFILLED`, `DELIVERED`** are
  removed. The order's progress is shown through the tracker instead.
- The **Order Approvals screens and the existing final-approval step** are removed.
- **Superadmin quoting is removed.**
- The existing design-approval, final-approval and delivery fields are removed.
- **Existing orders are dropped** — the product owner states they are test data.

**[Repo] What these touch** (found by search; not an exhaustive list):

- `design_status`, `superadmin_approval_status`: school-core and platform-service (reporting).
- `approved_design_asset_id`: school-core and the frontend.
- `delivered_at`, `delivered_by`: school-core and platform-service.
- `vendor_paid_at`: school-core, operations-service and platform-service.
- Pricing: `pricing_status`, `quoted_at`, `quoted_by`, line prices and `gst`/totals exist on the order tables
  and in reporting migration `V30__catalog_form_pricing.sql`.
- The Order Approvals panel is a shared screen
  ([SaOrderApprovalsPanel.tsx](../../frontend/src/pages/workspace/panels/SaOrderApprovalsPanel.tsx));
  platform-service writes approval decisions back into school-core through `ApprovalCommandClient`.
- The operations-service workflow seed includes `SUPPLY_ORDER_DEFAULT` ("Supply Order Approval",
  2 steps) — the existing approval definition.

**[Open]** The exact list of removed columns, the replacement for `status`, whether removal applies to all
catalogue categories or only notebooks, and the scope of the data drop are in Q12. No destructive
migration is written until Q12 is answered.

## Verified baseline in the repository

- **[Repo]** In both All Orders panels, "View" on a version-2 order opens `ProductOrderDetail`
  ([SaAllOrdersPanel.tsx:258](../../frontend/src/pages/workspace/panels/SaAllOrdersPanel.tsx#L258),
  [AdminOrdersPanel.tsx:154](../../frontend/src/pages/workspace/panels/AdminOrdersPanel.tsx#L154)); the
  tracker attaches there.
- **[Repo]** Order data is in the school-core `catalog` schema with forced row-level security on
  `school_id`: `catalog_orders` (`notes`, `required_by_date`, `placed_by`, `placed_at`, `version`, …),
  `catalog_order_lines` (`book_count`, `page_count`, `option_selections`, …) and `catalog_order_assets`.
- **[Repo]** `catalog_order_assets` has one current asset per order per kind (`DESIGN`, `PRE_DELIVERY_PHOTO`,
  `PRINT_REFERENCE`, with `PRINT_REFERENCE` exempt) and accepts PNG, JPEG, WEBP and PDF only, 5 MB default
  and 10 MB cap per file (`CatalogOrderAssetStorage`). Up to six step-2 images therefore need their own
  records; the existing `DESIGN` rule allows one.
- **[Repo]** The operations-service `workflow` schema (`V1`, `V6`):
  - `workflow_definitions` (id, name, active),
  - `workflow_steps` (definition, `step_order`, `step_name`, `required_permission`, `required_role`,
    `auto_approve`),
  - `workflow_instances` (definition, `entity_type`, `entity_id`, `school_id`, `current_step`, `status`,
    `initiated_by`, `version`; status limited to PENDING / IN_PROGRESS / APPROVED / REJECTED / CANCELLED /
    COMPLETED; unique per `(school_id, entity_type, entity_id)`),
  - `workflow_actions` (instance, `step_order`, `action`, actor, `notes varchar(1000)`, `acted_at`).
  **It has no assignee, no per-step data or draft, no time slot, no per-step start/completion timestamps and
  no reopen concept.** Its API (`/api/v1/workflows`) is read-only for definitions, steps, instances and
  actions.
- **[Repo]** The workflow tables live in **operations-service**, while order data lives in **school-core**.
  Services own their own schemas (the repo has database-boundary audits), so the tracker would read order
  data through an API rather than by joining schemas.
- **[Repo]** `OPERATIONS` already holds `workflow:read` and `workflow:act`. Whether `SUPERADMIN` holds them
  was not verified.

## Proposed design (to be confirmed — not requirements)

- **Reuse the workflow tables, extended** (per the decision to reuse them): a new definition for the
  notebook tracker; one instance per order keyed by `(school_id, 'NOTEBOOK_ORDER', order id)`; one action
  per completion, reopening or reassignment. Because the tables lack assignee, draft data, time slot and
  per-step timestamps, additive columns or companion tables in the same schema are needed — **[Open] Q13**.
- A Wholesale order's instance omits steps 2–4 (a separate definition, or skipped steps — **[Open] Q13**).
- Step values are stored per step as validated structured data; stage notes (≤100 words) are validated on
  the server.
- **Email intent structure:** when the step-2 image count first exceeds 4, one record is written (order,
  count, recipients = active superadmin users, created-at, status "not sent"). Nothing sends it.
- Per-step time slot defaults in a table; per-school-per-step overrides in another, writable by superadmin only.
- Reassignment, reopening and completion are append-only history with actor and time.
- Optimistic versioning (as on `catalog_orders.version`) so two users cannot silently overwrite a step.
- **Header summary (design):** total books; a compact breakdown of size × ruling with pages per line; the
  delivery date; the progress ring. This is a layout proposal for review, not a requirement.
- **Frontend:** a new tracker component opened from the existing view branches for notebook orders, rendered
  only for `SUPERADMIN` and `OPERATIONS`.

## Acceptance criteria

1. A school admin does not see the tracker; superadmin and a qualifying operator do; an operator who does not
   qualify cannot open it.
2. A new customised order shows 12 steps with only step 1 enabled; a Wholesale order shows 9 steps with only
   step 1 enabled and no steps 2–4. Saving or completing a locked step is rejected by the server.
3. **Save Draft** stores values without completing; reopening shows the last saved draft; nothing is auto-saved.
4. Completing a step enables the next, updates progress ("x of 12" or "x of 9") and locks the step for operators;
   only superadmin can reopen or edit it.
5. Step 2 accepts images 1–6 and rejects the 7th. One email-intent record is created when the total first exceeds
   4 and none after that. The right-hand preview shows the most recent step-2 image.
6. Step 3 can be completed only by superadmin; comments are optional; "Request changes" exists.
7. Step 8 completes only with the superadmin sign-off; the eight checklist items are not required.
8. Step 11: the operator enters ordered/produced/accepted/dispatch counts; a mismatch can be completed with a
   discrepancy note.
9. Step 12 completes only with the executive sign-off.
10. Contacts and time slots can be changed only by superadmin; time slots are read from the database per step and
    per school.
11. Stage notes reject more than 100 words.
12. The removed statuses, approval screens, quoting and delivery fields no longer appear anywhere in the product.
13. Tests cover sequence enforcement, role scoping, the image cap and the single email intent, concurrent edits,
    reassignment history and the Wholesale 9-step path.

## Open questions

These still need an answer. None has a default.

**Q1. Operator selection.** Operators can be scoped by school and by catalogue item. For a notebook order, must
an operator match both the school **and** the catalogue item, or either? When several qualify, which one is
auto-assigned? Where is the catalogue-item scope stored (the existing assignment has no such field)?

**Q2. Reopening.** When superadmin reopens a completed step while later steps are already completed, do the later
steps stay completed or relock?

**Q3. "Request changes" in step 3.** What happens next — does the order return to step 2, and is step 3 locked
until a new draft is uploaded? Does step 2 reopen for the operator?

**Q4. Time slots.** (a) "Marked red if the task is marked completed": is that red when a step is completed
**after** its deadline, or red while it is still open past its deadline? (b) When does the clock start — when the
step is enabled? (c) Numeric defaults for "Before bulk print" and "Daily follow-up". (d) How "working days" are
counted (weekends, holidays).

**Q5. Step 2 images.** Does the total of 6 count deleted or superseded images? Can images be deleted? Which file
types are allowed (the prototype also lists PDF, CDR and other formats; the current storage accepts
PNG/JPEG/WEBP/PDF)? What does the user see when the 7th is rejected?

**Q6. Contacts.** (a) School contact: the school record has only email and phone — where does the contact-person
name come from? (b) Vendor: which fields? One vendor per order, but the prototype has a *printing vendor* (step 7)
and a *making factory* (step 9) — are they the same vendor? (c) Designer: who supplies the designer's data?

**Q7. Step 8.** Are the eight checklist items still shown (optional) or removed? Does the "school verification"
field remain? Is the superadmin's name recorded as the reviewer?

**Q8. Step 11.** What counts as a mismatch: any of produced / accepted / dispatch differing from the ordered quantity
entered at order placement, or only specific pairs? Is the discrepancy note required whenever there is a mismatch?

**Q9. Step 12.** Who is the "executive" who signs off — superadmin or another role?

**Q10. Step 6 "TBD".** Are the prototype's fields editable and saved, or displayed only? Can the step be completed
normally while the calculation is TBD?

**Q11. Notes.** Is the Internal Notes panel one text or a log (the prototype keeps a list)? What is its word
limit, and who can see it? Can stage notes be edited after the step is completed (and by whom)?

**Q12. Removal scope (destructive — needs explicit confirmation).** (a) Does removing statuses, the approvals
screen and quoting apply to **all** catalogue categories or only notebooks? The approvals screen and approval
write-back are shared. (b) Exact columns to remove — the design/approval/delivery fields, **`vendor_paid_*`**
(used by vendor-dues in operations-service and reporting), and the pricing fields (`pricing_status`, `quoted_*`,
line prices, `gst`, totals)? (c) What does the order `status` column hold afterwards? (d) What happens to the
`SUPPLY_ORDER_DEFAULT` workflow definition and any reports that read the removed fields? (e) Which data is
dropped: the local database only, the deployed dev environment, reporting copies in platform-service, and stored
files in the private bucket? (f) Do older (form version 1) orders also go?

**Q13. Workflow reuse.** The existing tables lack assignee, per-step draft data, time slot and per-step timestamps.
May they be extended with additive columns, or should companion tables sit beside them in operations-service? Is
the Wholesale 9-step flow a second definition or the same definition with steps skipped? Does `SUPERADMIN` get
`workflow:read` / `workflow:act`, or are new permission names wanted?
