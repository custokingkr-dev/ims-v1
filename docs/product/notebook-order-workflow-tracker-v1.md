# Notebook Order Workflow Tracker

Version: **v1** (2026-10-11). Status: **proposed, not implemented.**
This file is immutable once reviewed. Revisions are written as `notebook-order-workflow-tracker-v2.md`
and so on; v1 is not edited in place. This document is a specification only; no code, migration or
configuration was changed while writing it.

| Version | Date | Change |
| --- | --- | --- |
| v1 | 2026-10-11 | First draft from the product request and the supplied prototype |

## How to read this document

Every statement is tagged by origin so nothing here is silently assumed:

- **[Requested]** — stated by the product owner in the request for this feature.
- **[Prototype]** — taken from `custoking_notebook_ops_prototype.html` (the supplied prototype).
- **[Repo]** — verified in this repository on branch `dev` (`426875da`); the file is cited.
- **[Open]** — not specified and not derivable from the repository. These are collected in
  [Open Questions](#open-questions) and must be answered before the affected part is built.
  Nothing marked **[Open]** has a default in this document.

## Scope

**[Requested]** For a placed notebook order, the "View order" action in the **All Orders** section
shows the workflow screen from the prototype. The screen is shown **only to superadmin and
operators**. It displays details fetched from the order placed by the school admin or superadmin.

**[Requested]** Only the **work area** of each prototype step is incorporated. The order flow is
listed in full on the left, steps unlock one after another, and the tracker above the work area
unlocks the same way. Every step has a **stage notes** section. Work is **reallocatable between
users**.

### Out of scope (as requested)

**[Requested]** "Only incorporate the work area" for each step. The prototype's other tabs
(School & vendor, Checklist, Activity history) are therefore not part of this feature.
**[Open]** The prototype's right-hand *Internal Notes* and *Upcoming Actions* panels and its top
search box were not mentioned in the request (see Q12).

## Prototype inspection

`custoking_notebook_ops_prototype.html` is a single static page. Findings that shape this spec:

1. **All state is local.** **[Prototype]** Data is read and written through
   `localStorage["custokingPrototype"]`. Nothing is fetched from, or sent to, a server.
2. **All data is sample data.** **[Prototype]** The order (`NB-2026-0042`, Delhi Public School,
   10,000 notebooks, A4, 200 pages, 300 GSM cover, 15 Dec 2026), the school contact
   (Rajesh Kumar, +91 98765 43210), the designer (Priya Singh), the signed-in user
   (Anita Sharma, Operations) and the four design drafts are hard-coded defaults.
3. **There is no authentication or role model.** **[Prototype]** Its own approval gate says
   "this demo records the decision locally; it does not authenticate an approver".
4. **Stage navigation is unrestricted.** **[Prototype]** Clicking any stage in the stepper or the
   left list opens it. Only the *Complete stage* button is gated (`canComplete`). This conflicts
   with the requested behaviour (steps enabled one after another) and must **not** be carried over.
5. **Layout:** header with order metrics and a progress ring, a 12-step stepper, a left stage list
   (12), a centre panel (stage header with SLA, four tabs, work area) and a right column
   (design preview, school feedback, internal notes, upcoming actions).
6. **Interactions that are prototype-only and need real replacements:** `prompt()` for feedback,
   `tel:` and `mailto:` links, object-URL image previews, and browser-side file "storage".

### The 12 steps in the prototype

| # | Name | Prototype owner label | Prototype SLA label |
| --- | --- | --- | --- |
| 1 | Order Details | Operations | 4 hours |
| 2 | Designing Activity | Design team | 2 working days |
| 3 | Head Approval | Approving head | 1 working day |
| 4 | CDR Conversion | Design team | 4 hours |
| 5 | Production Planning | Operations | 4 hours |
| 6 | Board Procurement | Procurement | 1 working day |
| 7 | Printing Press | Operations | 4 hours |
| 8 | Pre-Print Quality Gate | Quality team | Before bulk print |
| 9 | Factory Handover | Operations | 4 hours |
| 10 | Production Follow-up | Operations | Daily follow-up |
| 11 | Validation Count | Operations / QC | 4 hours |
| 12 | Delivery & Invoicing | Dispatch / Accounts | 1 working day |

**[Prototype]** The "owner" labels are text only; they are not users. The SLA labels are free text
and are not all durations ("Before bulk print", "Daily follow-up") — see Q7.

## Verified baseline in the repository

- **[Repo]** Notebook orders created by the structured form are *form version 2*. In both
  All Orders panels, "View" on a version-2 order opens `ProductOrderDetail`:
  [SaAllOrdersPanel.tsx:258](../../frontend/src/pages/workspace/panels/SaAllOrdersPanel.tsx#L258)
  (superadmin) and
  [AdminOrdersPanel.tsx:154](../../frontend/src/pages/workspace/panels/AdminOrdersPanel.tsx#L154)
  ("View order"). The new screen attaches at this point.
- **[Repo]** `ProductOrderDetail` already derives `canOperate` as superadmin or `OPERATIONS`
  ([ProductOrderDetail.tsx:18](../../frontend/src/features/catalog/ProductOrderDetail.tsx#L18)).
- **[Repo]** Order data lives in the `catalog` schema: `catalog_orders` (including `school_id`,
  `required_by_date`, `notes`, `placed_by`, `placed_at`, `status`, `design_status`,
  `superadmin_approval_status`, `approved_design_asset_id`, `form_snapshot`, `order_selections`,
  `version`), `catalog_order_lines` (per line: `option_selections`, `book_count`, `page_count`,
  requested counts, prices) and `catalog_order_assets`. All three use forced row-level security
  keyed on `school_id`, with composite `(order_id, school_id)` foreign keys.
- **[Repo]** Notebook option groups are `CUSTOMIZATION` (Custom / Wholesale), `SIZE` and `RULING`.
  There is **no** cover-GSM or paper-GSM option group (queried from the local catalog database).
- **[Repo]** Asset storage (`CatalogOrderAssetStorage`) accepts only PNG, JPEG, WEBP and PDF, validated
  by file signature; 5 MB default and 10 MB absolute cap per file; private storage (GCS, or a local
  directory in development). Asset kinds are `DESIGN`, `PRE_DELIVERY_PHOTO`, `PRINT_REFERENCE`.
  A unique index allows **one current asset per order per kind** except `PRINT_REFERENCE`
  (`idx_catalog_order_current_assets`), so multiple current `DESIGN` drafts do not fit the
  existing table as it is.
- **[Repo]** Operators are scoped to schools through role assignments carrying a `school_id`
  (`RbacReadRepository.operatorSchoolIds`), exposed on the session as `ops_schools`.
- **[Repo]** The gateway requires a **fresh passkey verification for every non-GET request made by a
  `SUPERADMIN`** ([server.js:925-932](../../services/api-gateway/server.js#L925-L932)). Every superadmin
  write in this feature (approval, SLA edits, contacts, reallocation) will therefore prompt for
  passkey verification. Operators are not subject to this rule on these routes.
- **[Repo]** The only email-sending code found is `PasswordResetMailer` in identity-service,
  SMTP-based and disabled by default (`identity.password-reset.enabled=false`). The notification
  service is built around MSG91 delivery. No general-purpose email sender for order events was found.
  See Q4.
- **[Repo]** `operations-service` already has `workflow_definitions / steps / actions / instances`
  tables. This spec does not assume they are reused (see Q13).
- **[Repo]** Existing order-level states already include design and approval concepts
  (`design_status`, `superadmin_approval_status`, `approved_design_asset_id`, status values such as
  `DESIGN_APPROVAL`). Their relationship to the new Stage 3 is not defined (see Q3).

## Requirements

### R1. Entry and visibility — [Requested]

1. The tracker opens from "View order" in All Orders for a notebook order.
2. It is visible to **superadmin and operators only**. School admins keep the existing order view.
3. Operators see only schools they are assigned to. **[Repo]** This reuses the existing
   operator-school assignment; **[Open]** whether it is sufficient for this screen (Q11).
4. The screen is built from data of the placed order, whoever placed it (school admin or superadmin).

### R2. Sequential flow — [Requested]

1. The left list shows **all 12 steps** at all times.
2. A step is enabled only when its predecessor is **completed**; later steps are visibly disabled.
3. The tracker above the work area follows the same state (completed / active / locked).
4. Completing a step enables the next one and moves the user to it.
5. Enforcement must be **server-side**; hiding the UI is not sufficient. A request to save or complete
   a locked step is rejected.
6. **[Open]** Whether a completed step can be reopened (Q9).

### R3. Reallocation — [Requested]

1. Each step has an assignee who can be changed to a different user ("reallocated").
2. **[Open]** Who may reallocate, which users are eligible, and what happens to stage data already
   entered (Q1, Q2).
3. Proposed record (design choice, not a requirement): every reallocation is stored with who, when,
   from, to, so the history is auditable.

### R4. Stage notes — [Requested]

Every step has a stage-notes section. **[Open]** Whether notes are one editable text per step (as in
the prototype, where saving overwrites) or an append-only log (Q10).

### R5. Time slot per step — [Requested]

1. Each step shows a time slot to complete the task, as in the prototype.
2. The time slot is **customisable per school, per step, by superadmin only**.
3. **[Open]** The unit model, the default values and how "working days" are counted (Q7).

### R6. Contacts — [Requested]

School and vendor contact details are provided **only by superadmin**. Operators see them; they
cannot change them. **[Open]** exactly which contact fields exist beyond name and phone (Q5).
The prototype's designer contact is **[Open]** (Q5).

### R7. Right-hand column — [Requested]

1. **Current Design Preview** always shows the **latest image uploaded in Step 2**.
2. **School Feedback** shows the notes entered **during order placement**, if any.
   **[Repo]** The existing field is `catalog_orders.notes` (`varchar(255)`).
   **[Open]** whether it is the only source (Q6).

### R8. Header — [Prototype] with data from the order

Order ID, school name, quantity, size, pages, delivery date and the progress ring
(completed steps of 12). **[Open]** Several prototype metrics have no source or do not map
one-to-one onto a multi-line order (Q8).

## Step specification

For every step the work area is the prototype's work area. "Gate" is a rule that must pass before
the step can be marked completed. Gates marked **[Prototype]** are carried from the prototype and
must be confirmed; the sequential rule in R2 applies to all steps regardless.

### Step 1 — Order Details — [Requested]

- **[Requested]** Shows the order details fetched from the order placed by the school admin or
  superadmin. Completing it moves the order to step 2.
- **[Prototype]** Prototype fields: school name, order ID, total quantity, delivery date,
  notebook specifications (free text).
- **[Open]** Whether these are read-only (fetched) or editable by operators (Q8). The prototype makes
  them editable; the request says "fetched".

### Step 2 — Designing Activity — [Requested]

- **[Requested]** Shows the **vendor details provided by superadmin**, and lets users upload **design
  drafts and revisions**.
- **[Requested]** **Maximum 4 images** can be uploaded. **If more are uploaded, an email is
  triggered.**
- **[Prototype]** The prototype also shows a designer card and school contact card with call buttons,
  a draft grid with status chips, and a revision-history table (version, file, status).
- **[Repo]** Constraints from existing storage: PNG/JPEG/WEBP/PDF only, 5 MB default and 10 MB cap
  per file, and one current `DESIGN` asset per order (needs a different model for multiple drafts).
- **[Open]** The meaning of "max 4" and of the email trigger — Q4.

### Step 3 — Head Approval — [Requested]

- **[Requested]** Done **only by superadmin**. The superadmin **approves the final draft image** and may
  add **optional comments**.
- **[Prototype]** Prototype fields: choose the design version, comments, *Approve design* and
  *Request changes* buttons. **[Prototype]** Step cannot complete until approved.
- **[Repo]** Superadmin writes require passkey verification (see baseline).
- **[Open]** Interaction with the existing design-approval state (Q3), and whether "Request changes"
  is in scope — the request mentions approval only (Q3).

### Step 4 — CDR Conversion — [Requested]

- **[Requested]** The step refers to "Coral Draw revision" (CDR).
- **[Prototype]** Fields: final CDR filename, controlled storage link, conversion comments.
- **[Open]** Whether the CDR file is uploaded or only linked. The existing storage does not accept
  `.cdr` files (Q14).

### Step 5 — Production Planning — [Requested: "draft this stage in similar format"]

- **[Prototype]** Fields used as the draft: paper / inner GSM, factory capacity confirmed, production
  start date, target completion date, planning notes.
- **[Open]** The prototype's default values ("70 GSM", "Pending") are sample values, not confirmed.
  The field list above is a draft for confirmation, not an approved design.

### Step 6 — Board Procurement — [Requested]

- **[Requested]** The step is **populated based on a calculation provided by superadmin**.
- **[Prototype]** The calculation shown is: boards to procure =
  `max(0, ceil(units ÷ usable pieces per board × (1 + wastage% ÷ 100) − available usable boards))`,
  with inputs total title units, usable pieces per board, wastage %, available boards, plus a
  procurement reference.
- **[Open]** Which inputs are "provided by superadmin" and which are entered by operators, and the
  default yield and wastage (the prototype's 20 and 5 are sample values) — Q15.

### Step 7 — Printing Press — [Requested]

- **[Requested]** Vendor details and the other details in the prototype's work area.
- **[Prototype]** Fields: printing vendor, vendor job reference, quantity sent, expected return date,
  contact number.
- **[Requested]** Vendor details are provided by superadmin (R6).

### Step 8 — Pre-Print Quality Gate — [Requested: "similar to provided checkboxes and fields"]

- **[Prototype]** Eight checklist items: school name/logo/spelling verified; latest approved artwork
  confirmed; dimensions, bleed and safe margins checked; board material, GSM and print specifications
  verified; physical print sample inspected; school verification recorded where required;
  print quantity and title-wise breakup verified; authorised quality reviewer releases bulk printing.
  Plus: print sample evidence link, school verification (Not required/pending, Verified, Changes
  required), QC reviewer comments, reviewer name and a reviewer sign-off action.
- **[Prototype]** Gate: all eight checks ticked **and** a reviewer sign-off recorded.
- **[Open]** Who the "authorised reviewer" is in the real system (Q16).

### Step 9 — Factory Handover — [Requested: "similar UI, only work area"]

- **[Prototype]** Fields: making factory, factory job reference, printed titles sent, titles
  received, factory contact.

### Step 10 — Production Follow-up — [Requested]

- **[Prototype]** Fields: production completed (%), next follow-up date, factory contact number, and a
  "log follow-up" action. The prototype's call/communication log belongs to a tab that is out of scope.
- **[Open]** Whether a follow-up is a single set of values or repeated entries over time (Q10).

### Step 11 — Validation Count — [Requested: "necessary backend data to be fetched"]

- **[Prototype]** Fields: ordered, produced, accepted and dispatch quantities, discrepancy notes.
- **[Prototype]** Gate: accepted = dispatch quantity and > 0 (and accepted ≤ produced ≤ ordered
  for the "ok" indicator).
- **[Requested]** The ordered quantity is fetched from the order. **[Open]** Whether produced/accepted
  quantities come from earlier steps (for example titles received in step 9) or are typed (Q17).

### Step 12 — Delivery & Invoicing — [Requested: "similar to UI provided"]

- **[Prototype]** Fields: transporter/vehicle, dispatch date, consignment/LR number, invoice number,
  proof-of-delivery/invoice link, executive consignment sign-off (Pending / Signed off),
  invoice status (Not initiated / Initiated / Sent / Paid).
- **[Prototype]** Gate: sign-off = Signed off.
- **[Repo]** The order already has delivery and vendor-payment fields (`delivered_at`, `delivered_by`,
  `vendor_paid_at`). **[Open]** Whether completing step 12 should drive them (Q3).

## Proposed design (to be confirmed — not requirements)

These are design proposals that follow existing repository conventions. They can change once the open
questions are answered.

### Data

- A workflow record per notebook order and one record per step, in the `catalog` schema alongside
  the order tables, with the same composite `(order_id, school_id)` foreign key and forced
  row-level-security pattern. Per step: status (locked / active / completed), assignee user, time
  slot, completed-at, completed-by, step-specific values (validated per step on the server) and
  stage notes.
- A per-school, per-step time-slot setting maintained by superadmin, with defaults.
- School and vendor contact records maintained by superadmin.
- Design drafts as their own records referencing stored assets, so more than one draft can exist
  (the current one-current-`DESIGN`-per-order index does not allow this).
- Reallocation history and stage completion history stored append-only.
- Optimistic versioning like `catalog_orders.version`, to prevent two users overwriting a step.

### API

- Read the full workflow for an order; save a step's values; complete a step; reallocate a step;
  upload a draft; set contacts; set time slots. Authorisation is enforced server-side per action:
  superadmin-only for approval, contacts and time slots; superadmin and operators for the rest,
  scoped to assigned schools. Exact routes and permission names are **[Open]** (Q13).

### Frontend

- A new component opened from the existing "View"/"View order" branches for notebook orders, shown
  only when the user's role is `SUPERADMIN` or `OPERATIONS`; school admins continue to see
  `ProductOrderDetail`. Visual design follows the prototype layout, using the application's existing
  styling.

## Acceptance criteria

1. As a school admin, "View order" does not show the tracker; as superadmin and as an assigned operator
   it does; an operator not assigned to the school cannot open it.
2. On a new order, only step 1 is enabled; steps 2–12 are visibly disabled in both the left list and
   the tracker. Calling the save/complete API for a locked step is rejected.
3. Completing step *n* enables step *n+1*; the header ring and "x of 12 steps" update.
4. Step 1 shows the real order's school, ID, quantity, delivery date and specifications.
5. Step 2: the fifth image is handled as defined in Q4; the right-hand preview always shows the most
   recently uploaded Step 2 image.
6. Step 3 can be completed only by superadmin; comments are optional; the approval records which draft
   was approved.
7. Contacts and time slots can be changed only by superadmin; operators see them read-only.
8. A step can be reassigned and the history records who changed it and when.
9. Each step has a stage-notes section, and saved notes survive a reload.
10. School Feedback shows the placement notes when present and an empty state when absent.
11. Tests cover: sequence enforcement, role and school scoping, upload limits, superadmin-only
    actions, concurrent edit rejection and the reallocation history.

## Open questions

These need an answer from the product owner before the affected part is built. None has a default.

1. **Reallocation — who:** which roles may reallocate a step (superadmin only, the current assignee,
   any operator)? Can a step be unassigned?
2. **Reallocation — to whom:** may a step be assigned to any user, or only to superadmin and
   operators? Must an operator assignee be assigned to the order's school? What happens to
   in-progress data on reallocation?
3. **Relationship to existing order states:** how do steps 3, 11 and 12 relate to the existing
   `design_status`, `superadmin_approval_status`, `approved_design_asset_id`, delivery and
   vendor-payment fields and the existing quoting/final-approval flow? Does "Request changes" in
   step 3 exist? Does this tracker replace the existing approval screen or sit beside it?
4. **Step 2 image limit and email:** is the limit of 4 per upload, in total, or per revision? When a
   fifth image is uploaded, is it accepted and an email sent, or rejected? Who receives the email,
   what does it say, and through which provider (no general order-email sender exists in the repo)?
5. **Contacts:** which fields (name, phone, email, role) for the school contact and for the vendor, and
   is there one vendor per order or per step? Does the designer card in step 2 remain?
6. **School feedback source:** is `catalog_orders.notes` (255 characters) the only source, or also
   per-line notes? Is feedback ever added after placement?
7. **Time slots:** representation of values (hours, working days, "before bulk print", "daily
   follow-up"), the default for each step, whether the timer is displayed, what happens on breach, and
   the working-day calendar.
8. **Header and Step 1 data:** the order may have several size/ruling lines, each with its own page
   count; how should the single "Size" and "Pages" tiles summarise them? "Cover GSM" has no source in
   the order data. Are Step 1 fields read-only? Is the order ID shown the existing order ID?
9. **Reopening:** can a completed step be reopened, by whom, and does that relock later steps?
10. **Notes and repeated entries:** is stage notes a single text per step or an append-only log? Are
    follow-ups in step 10 repeated entries?
11. **Operator scope:** is assignment to the order's school (existing `ops_schools`) the correct scope
    for operators on this screen?
12. **Right column extras:** should Internal Notes and Upcoming Actions from the prototype be included?
13. **Platform:** should this reuse the existing `operations-service` workflow tables or live with the
    order data? Which permission names apply (existing `catalog:manage`, `workflow:act` or new ones)?
14. **CDR file:** upload or link? If an upload, which file types and size, given storage accepts only
    PNG/JPEG/WEBP/PDF today?
15. **Board calculation:** which inputs are set by superadmin, the default yield and wastage, and are
    operators allowed to edit any input?
16. **Quality reviewer:** who is the "authorised reviewer" and who may record the sign-off?
17. **Quantities in steps 9–11:** are produced/accepted/received values entered or carried forward?
18. **Which orders:** does the tracker apply to every notebook order or only customised ones? Existing
    product rules say non-customised notebooks skip design approval
    ([notebook-order-form-builder.md](notebook-order-form-builder.md), decision 7), which conflicts
    with steps 2–4 applying unchanged. It must also be decided for orders placed before this feature
    exists and for legacy (form version 1) orders.
