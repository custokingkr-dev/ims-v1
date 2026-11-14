# Item order workflows to the client SOP: design v1

Version **v1**, 2026-11-13. Status: **decided, not built.** The owner asked the lead designer to "complete it by yourself, take all the decisions needed, just document them". Every decision is below. Each one the client may want to change is repeated, with the default we chose, in [Appendix A](#appendix-a-questions-for-the-client). Writing this document changed no code, migration or configuration.

## Sources and precedence

| Source | Authority |
| --- | --- |
| Client SOP received 2026-11-13 | **What** each item's workflow must do: steps, codes, limits, call model, vendor categories |
| Reference prototype spec, merged from the client's walkthrough video of 2026-11-13 (notebook prototype, flier prototype, presenter's notepad, order-form sheet) | **How** screens look and behave. Its list of prototype bugs and shortcuts (its §8) is deliberately **not** copied; see [Appendix B](#appendix-b-prototype-artefacts-we-do-not-copy) |
| Code maps of `origin/dev` `53f75ef6` (the working tree is identical) | What exists today. File and line references below are to that commit |
| House design system: `frontend/src/styles/tokens.css`, `frontend/src/styles.css`, `PRODUCT.md`, `features/catalog/DESIGN.md` | Visual language of every new screen |

**Screen design tool.** The owner suggested UX Pilot. It is not available in this environment, so no external design tool was used. The screens in §4 follow the client's prototype for layout and behaviour, and the house design system for tokens, components and type.

**Credentials.** The walkthrough recording shows live credentials on screen. None are reproduced in this document or anywhere in the plan. The owner should rotate them; that is an owner action outside this work.

Tags used below: **[SOP]** from the client SOP · **[Proto]** from the prototype (verbatim when quoted) · **[Note]** from the presenter's notepad spec · **[Ours]** our wording or value, which the client has not given.

---

## 1. What the client wants vs what exists

| Area | Client wants | Exists on dev today | Gap |
| --- | --- | --- | --- |
| Engines | One standard multi-step workflow per item, with SLAs, checklists and supplier contacts [SOP §2] | Two engines. Notebooks use operations-service's tracker (12 steps, SLAs, gates). Nine other categories use school-core's PR #380 "production workflow": 9 fixed stages, each holding two free-text boxes | Non-notebook items have no fields, checklists, files, vendors, SLA, assignee, gates or history. Two engines to maintain |
| Order ID | `<ItemCode>-<SchoolCode>-<YY-MM-DD>-<4DigitNo>`, with item codes NB/FE/FX/CF/FB/BBK/RC/BCH | `CK-<seq>` from one global sequence, minted when the draft is created | No item code, school code, date or running number. No school "initials_location" code |
| Screens | Single-order page: summary KPIs, 12-step (or 9-step) stepper, stage list, stage panel with 4 tabs, right-hand widgets [Proto] | A tracker page with padlocked step chips and a fields-only work area. Product orders show a free-text stage list at the bottom of the order | Missing: KPIs, stepper, tabs, per-stage cards, live SLA timer, design preview, school feedback, upcoming actions |
| Step content | Per-step cards and checklists. NB steps 5, 7, 8, 9 and 10 are mandatory checklists; bulk printing is blocked until QC checks pass and a reviewer signs off | NB has partial content. Step 1 has nothing, step 6 is marked "TBD", step 9 has no checklist, step 11 has no links. Only steps 7, 11 and 12 are gated. Steps 8–10 checklists are optional | Missing fields, wording and gates |
| Design files | At most 4 files, 25 MB in total; .pdf, .cdr, .png, .jpeg | No count limit; 25 MB **per file**; .cdr rejected; webp accepted. Notebooks only | Limits, formats, and all items |
| Vendors | 18 categories; vendors with contacts and their own WhatsApp group; selected per order and filtered per step | 4 categories × 3 placeholder vendors, hard-coded in 5 places (Java, TypeScript, DB CHECK, OpenAPI, generated client). No contact details. Selection is superadmin-only | A real directory, its management screen, and who may select |
| Calls | Entity contacted, outcome, notes of at most 100 words [SOP §2] | Nothing | All of it |
| Logging | "Calls, changes, and approvals must be logged standardly"; an Activity history tab [Proto] | `workflow_actions` records step moves and approvals but nothing reads it. Edits, vendor, designer and link changes are not logged | A read API and complete logging |
| Messages | Introduction message per item with `{Name}` | None | Template, copy, open group |
| Word limits | Call notes 100, discrepancy notes 200, final validation notes 100 | Stage and internal notes 100 words; discrepancy note 1,000 characters | Per-field word limits |
| Upcoming actions | A "Live list" widget [Proto] | Rejected in tracker spec v2 | Derived list |
| Order list, board and search | Never shown in the video **[gap]** | NB-only table with no search, item filter or board | A new screen (§4.2) |

---

## 2. Decisions

Each decision gives the **choice**, the *alternatives* considered, and *why*.

### 2.1 Architecture

**D1. One workflow engine, in operations-service, generalised from the notebook tracker.**
- **Choice.** Every item's order workflow runs on operations-service's `workflow` schema engine, today's notebook tracker, driven by per-item templates. school-core keeps orders, order lines, files, order numbers and the order form.
- *Alternatives.* (a) Extend school-core's PR #380 workflow. The wiring is smaller, but SLAs, assignees, gates, sign-offs, reopen, notes, audit, worklist, operator RLS scope and the status outbox would all have to be rebuilt, leaving a second engine. (b) A new service. That breaks "no new external services" and is multi-week work.
- *Why.* The tracker already has roughly 70% of the machinery: tables keyed by definition (`definition_id` on steps, instance steps and SLA overrides), per-step SLA with per-school overrides, assignees, optimistic locking (`mutate`), gates, sign-offs, reopen, notes, a status outbox to school-core, RLS with the `app.operator_schools` clause, and a generated client. What remains is replacing the `TrackerStep` enum and its switches with data.

**D2. Templates are versioned JSON resources plus matching `workflow_steps` rows. Only SLAs are editable at runtime.**
- **Choice.**
  - There is one file per definition: `services/operations-service/src/main/resources/workflow-templates/<definitionId>.json`.
  - Templates are loaded and validated at startup. The service fails fast if a file is invalid, or if its step orders or codes differ from that definition's `workflow_steps` rows.
  - A step declares: order, code, title, description, phase, default SLA, `customisedOnly`, tabs, blocks (UI cards from a closed set), typed fields with limits, checklists with verbatim items, gates (rule types from a closed set), an optional sign-off, and the vendor categories it shows (format in §3.3).
  - Templates are served by `GET /api/v1/workflows/item-orders/templates/{definitionId}`, so the browser renders stages from the server's spec. The frontend copies of checklists, fields and sign-off steps (`frontend/src/features/tracker/steps.ts`) are deleted.
  - At runtime, superadmin can edit each step's default SLA (new) and per-school overrides (exists). Changing a checklist or field is a code change plus a deploy, and a migration if steps change.
- *Alternatives.* Templates editable in the DB through a template editor. That needs schema validation, template versioning for orders already in flight, and an editor UI: multi-week. Or one Java enum per item, which repeats today's hard-wiring.
- *Why.* "Customisable for items" and "Each step SLA" [Note] are met by per-item templates plus editable SLAs. JSON is reviewable, testable and diffable, and in-flight orders keep stable semantics.

**D3. school-core's `catalog.product_categories.workflow_definition` decides which category runs which template.**
- **Choice.** A null value means no workflow: the order keeps the existing approve, quote and deliver flow. Values are seeded per D8. Superadmin sets it for a new category in Catalog manager, choosing from the five templates. The column has a CHECK listing the five ids, so adding a template means a migration in both services.
- *Alternative.* Keep the list in the operations templates. But placement happens in school-core, which would then need a peer call or a copy.
- *Why.* One lookup at placement, as data. Setting the value to null is also the off switch for new placements of a category.

**D4. PR #380's product workflow is retired, and its in-flight orders move to the engine.**
- **Choice.**
  - Placing an order in any category with a `workflow_definition` starts an operations workflow (a generalised `startTracker`).
  - Migration V38 queues every open, placed product order that has a V36 workflow. The creation call carries the V36 progress. Operations marks those stages COMPLETED without re-applying gates, logs the action `STEP_IMPORTED`, and copies each stage's "Production update" and note into stage-note entries.
  - Stage codes map one to one, except `DESIGN_ACTIVITY`→`DESIGNING_ACTIVITY` and `FINAL_VERIFICATION`→`FINAL_APPROVAL`.
  - The V36 tables (`catalog.product_order_workflows`, `catalog.product_order_workflow_steps`) become read-only: the runtime role loses INSERT, UPDATE and DELETE. They are kept for one release, then dropped in a follow-up (out of scope). Not yet droppable: the creation queue still imports their progress (`NotebookTrackerCreationQueue`, including `product_order_workflow_steps.stage_note`), and `ProductOrderWorkflowService` still reads them for the retired `GET …/production-workflow`.
  - `GET …/production-workflow` stays, read-only. `PUT` and `POST` return 409 `WORKFLOW_MOVED`.
  - `ProductOrderWorkflowService.CATEGORIES`, the lazy creation in `view()`, `ProductOrderWorkflow.tsx` and the `productionWorkflowAvailable` flag are removed. For staff, `ProductOrderDetail` links to the order workflow instead.
- *Alternative.* Keep both engines side by side. That means two UIs and two status models for the same order.
- *Why.* One engine, and no progress is lost.

**D5. Every workflow order is tracker-managed. Quotes stay a commercial record for non-notebook items.**
- **Choice.**
  - The catalogue status of a workflow order is its current step code (for example `PRE_PRINT_QUALITY_CHECK`), or `COMPLETED`.
  - school-core validates incoming codes by the pattern `^[A-Z][A-Z0-9_]{1,63}$` instead of the hand-kept `TRACKER_STATUSES` copy (`CatalogOrderFormService.java:57-61`). It stores `attributes.workflowStep` = {order, title, total, completed} so lists can show the step without asking operations.
  - The workflow owns approval and delivery. The `DESIGN_APPROVED`, `APPROVED` and `DELIVERED` transitions and `returnOrder` stay refused for tracker-managed orders, exactly as they are today for notebooks.
  - Notebooks keep `pricing_status = NOT_APPLICABLE` (owner decision D1 of 2026-10-20).
  - Non-notebook orders keep their pricing state at placement, and superadmin may still save a quote until the workflow completes (the `quote` guard is relaxed for them). Billing is unchanged.
  - School admins keep the coarse view: "Processing" while the workflow runs, "Delivered" at `COMPLETED`.
- *Alternatives.* Drop quotes for every item, which loses commercial data for priced items. Or keep superadmin "approve" as a gate before production, which duplicates Head Approval, the SOP's own gate.
- *Why.* The least disruption to billing, and the SOP's gate is Head Approval.

**D6. Storage names are kept; API and code names are generalised.**
- **Kept:**
  - table names (`workflow_notebook_trackers`, `workflow_instance_steps` and the rest);
  - definition id `NOTEBOOK_ORDER_TRACKER` and the existing NB step codes, which are also order statuses;
  - permission codes `notebook-tracker:read|act`, which now cover every item;
  - the creation-queue table name, `catalog.notebook_tracker_creation_queue`.
- **Renamed:**
  - REST base `/api/v1/workflows/notebook-orders` → `/api/v1/workflows/item-orders`, with **no alias**: the browser is the only client and deploys with it on dev.
  - Internal path `/api/v1/internal/notebook-trackers` → `/api/v1/internal/order-workflows`. The old path stays as an alias for one release, so a school-core deployed before operations keeps creating workflows.
  - Java `NotebookTracker*` → `OrderWorkflow*`. The `TrackerStep` enum and `VendorDirectory` class are removed.
  - New instances get `entity_type = 'ITEM_ORDER'`. Existing ones keep `'NOTEBOOK_ORDER'`, and the generic workflow reads hide both.
- *Why.* Renaming tables or permissions buys nothing and touches RLS policies, the runtime ACL sync, identity seeds and live sessions. Names that users and developers see should not say "notebook" for a flier.

**D7. Wholesale notebooks still skip Steps 2–4 and run 9 of the 12 steps.**
- *Alternative.* Always 12 steps: SOP 4.1 lists 12 and says nothing about wholesale.
- *Why.* This was an owner decision in tracker spec v4. Wholesale notebooks carry no school title ("Non Customised refers to wholesale notebooks" [Sheet]), so design, CDR and approval have nothing to act on, and school-core already refuses design uploads for them (catalog V23). The question goes back to the client as Q18.

### 2.2 Item codes, templates per item, order numbers

**D8. Item codes, item nouns and templates.** Item codes live in `catalog.product_categories.order_code`, which is unique and matches `^[A-Z]{2,4}$`.

| Catalog category | Item code | Item noun (intro message) | Template (`workflow_definition`) | Basis |
| --- | --- | --- | --- | --- |
| NOTEBOOKS | NB | notebook | `NOTEBOOK_ORDER_TRACKER` (12 steps) | SOP 4.1 |
| FLIERS | FE | flier | `FLIER_ORDER_WORKFLOW` (9) | SOP 4.2 |
| FLEX | FX | flex | `FLEX_ORDER_WORKFLOW` (9) | SOP 4.3 |
| BILLBOOKS | BBK | bill book | `BILL_BOOK_ORDER_WORKFLOW` (9) | SOP 4.4 |
| CERTIFICATES | CF | certificate | `GENERAL_ITEM_ORDER_WORKFLOW` (9) | SOP code; our template |
| FLUTE_BOARDS | FB | flute board | `GENERAL_ITEM_ORDER_WORKFLOW` | SOP code; our template |
| REPORT_CARDS | RC | report card | `GENERAL_ITEM_ORDER_WORKFLOW` | SOP code; our template |
| BROCHURES | BCH | brochure | `GENERAL_ITEM_ORDER_WORKFLOW` | SOP code; our template |
| BELTS | BLT [Ours] | belt | `GENERAL_ITEM_ORDER_WORKFLOW` (keeps its PR #380 coverage) | Ours |
| STANDEES | STD [Ours] | standee | `GENERAL_ITEM_ORDER_WORKFLOW` | Ours |
| TIES | TIE [Ours] | tie | none (as today) | Ours |
| TROPHIES | TRP [Ours] | trophy | none (as today) | Ours |
| Legacy UNIFORMS, STATIONERY, IDCARDS, HOUSEKEEPING, EVENTS, HEALTH | none | — | none; no order number | — |

Superadmin sets the code, noun and template for any new category in Catalog manager. A category with no code gets no order number.

**D9. Items without an SOP section get the General item workflow.**
- **Choice.** Certificate, Flute Board, Report Card and Brochures (and Belts and Standees) run the SOP's shared 9-step shape: the same steps and gates as FE, FX and BBK. Their Step 6 checklist is a generic [Ours] one and they share the 4-item follow-up checklist [Ours].
- *Alternatives.* NB's 12 steps, whose board, factory and binding steps do not apply. Or no workflow, which would take away what PR #380 gave these categories.
- *Why.* "Every item follows a multi-step execution workflow" [SOP §2], and the 9-step shape is the SOP's own shape for non-notebook print items.

**D10. The order number is a new, immutable field; the `CK-<n>` id stays the key.**
- **Choice.** Add `catalog.catalog_orders.order_number`: unique, null for drafts and for categories without a code, minted once, and never updated (a trigger enforces this). `CK-<n>` remains the primary key and the key in every API path, storage key, foreign key, outbox event and billing `order_ref`.
- *Alternative.* Replace the id. That rewrites 6 composite foreign keys, GCS object keys, operations tables, the reporting primary key, billing references and outbox history.
- *Why.* No risk to existing references; people see the number.

**D11. Number format, sequence scope and minting (C6).**
- **Format.** `<ItemCode>-<SchoolOrderCode>-<YY-MM-DD>-<NNNN>`, for example `NB-DPS_GUR-26-11-13-0042`.
- **Date.** The placement date in the school's `time_zone`, not in UTC. In UTC, orders placed between 00:00 and 05:30 IST would carry the previous day's date.
- **NNNN.**
  - A running counter per item code per calendar year of that date, starting at 0001.
  - Kept in `catalog.order_number_counters` and taken with `INSERT … ON CONFLICT DO UPDATE … RETURNING`, never with `MAX()+1` (the lesson of catalog V5).
  - After 9999 it widens to 5 digits and stays unique.
- **When minted.** Inside the placing transaction, on both the form path and the legacy `placeOrder`.
  - Drafts get no number. A number minted at draft creation would be burned by abandoned drafts and would carry the draft's date.
  - Cancelled orders keep their number. Gaps are acceptable.
- **Uniqueness.** (item, year, counter) is unique by construction, and there is also a unique index on `order_number`. The maximum length is 34 characters; the column is `VARCHAR(40)`. The number is never used in a URL path.
- *Alternatives.*
  - A counter per item, school and date: almost always 0001, so the four digits mean nothing.
  - One global counter per date.
  - A counter per financial year (April–March): plausible, but the prototype's `NB-2026-0042` points to a calendar-year serial per item. Asked as Q25.

**D12. A new school order code, `tenant_school.schools.order_code`, rather than `short_code`.**
- **Format.** `<Initials>_<Location>`, matching `^[A-Z0-9]{2,8}_[A-Z0-9]{2,6}$`. It contains no hyphen, so an order number splits cleanly on `-`.
- **Default derivation.**
  - Initials: the first letter of each word of the school name, letters only, ignoring OF, THE, AND, FOR and `&`, up to 8 letters. With fewer than 2 letters, use the first 3 letters of the first word.
  - Location: the first 3 letters of the city, else of the state, else `IN`.
  - On a collision, append 2–9 to the initials (`DPS2_GUR`).
- **Setting and editing.**
  - A suggested code is prefilled in the onboarding form and can be changed there.
  - V31 backfills a code for every existing school.
  - Superadmin can edit the code at any time on the school screen. A change affects only future numbers; numbers already minted never change.
  - The code is unique and stored in upper case.
- *Alternative.* Reuse `short_code`. It cannot be changed after creation, has no format rule, and is already used in Drive folder names and export filenames, so changing its rules would break those.
- *Why.* The SOP format needs a location part, and short codes don't reliably have one.

**D13. Backfill.**
- V31 derives a code for every school.
- V37 numbers every non-draft order of a coded category, in placement order (`placed_at`, then `created_at`, then id), per item and year, and sets the counters to match.
- V38 queues the existing tracker-managed orders. Operations' idempotent create then fills in the missing `order_number` on their trackers.

**D14. Display and search.**
- The order number is the headline everywhere: lists, detail, approvals, and the billing invoice description. The CK id shows as a secondary "Ref CK-1015".
- Search is server-side, case-insensitive and substring: it matches the order number, the CK id and the school name.
- Rows without a number (drafts, legacy categories) show the CK id.

### 2.3 Steps, gating, approvals, SLAs

**D15 / D16. Step definitions.** The notebook template has 12 steps and the FE, FX, BBK and General templates have 9. The full content (fields, verbatim checklists, gates, SLAs and cards) is in §2.7.

**D17. Checklist wording.**
- Order of preference: the prototype's text, verbatim and in its original case; then SOP or [Note] text; otherwise our own wording, marked `"source": "ours"` in the template and listed in Appendix A.
- NB checklists keep today's item order, so the positions existing trackers have saved keep their meaning. Today's labels in `steps.ts` paraphrase the same items in the same order.

**D18. Navigation and editing.**
- **Viewing.** Any stage can be opened from the stepper or the stage list, locked ones included [Proto]. A locked stage is read-only with the banner "Opens for editing when <previous stage> is completed."
- **Editing.**
  - Only the ACTIVE stage is editable, by its assignee or by superadmin.
  - Superadmin may also edit COMPLETED stages, as today. Nobody edits a LOCKED stage.
  - This is what enforces "bulk printing blocked": Printing Press checks cannot be ticked until Pre-Print Quality Check is complete. It fixes prototype artefact 10.
- **Completing.** Stages complete strictly in order. Completing one starts the next stage's SLA clock.
- **Stage list.** It shows each stage's real status (Completed, In progress, Overdue, Pending). It never relabels the stage that happens to be open (artefact 1).

**D19. Gates.**
- `Complete` is refused with 409 `GATE_UNMET`, listing **every** unmet requirement, until the stage's gates pass. The screen shows the same list under the disabled button and in Upcoming actions.
- Gate types (a closed set in Java):

  | Gate type | Passes when |
  | --- | --- |
  | `FIELDS_PRESENT` | The listed fields are saved |
  | `ALL_CHECKED` | Every item of the checklist is ticked |
  | `CHOICE_IN` | The field holds one of the listed values |
  | `DESIGN_FILES_PRESENT` | At least one design file exists. The count is fetched from school-core with no transaction or lock held, then the gate is re-checked under the lock |
  | `VENDORS_SELECTED` | Each listed category, or any category, has a selected vendor |
  | `HEAD_APPROVED` | An approval exists and it binds the CDR link that is saved now |
  | `SIGNED_OFF` | A sign-off exists and nothing changed since |
  | `COUNTS_CONSISTENT` | Counts entered and mismatched → a discrepancy note is present |

- A stage already completed is never re-gated when gates change.

**D20. Approvals and sign-offs are authenticated server actions by superadmin.**
- **Choice.**
  - The actions: Head Approval (approve, request changes), the QC reviewer sign-off (NB Step 7), the consignment sign-off (NB Step 12 and 9-step Step 9), and internal team approval (9-step Step 8).
  - All are superadmin only, authenticated by the signed-in session. Passkey step-up was removed on 2026-11-12; see `docs/superpowers/specs/2026-11-12-school-order-step-up-design.md`.
  - Each records user id, display name and time. The prototype's typed "Reviewer name" and its "does not authenticate an approver" shortcut are not copied (artefact 4).
  - A sign-off becomes void if the stage's data changes afterwards (today's `sameValues` rule).
  - The QC sign-off ticks item 8, "Authorised quality reviewer releases bulk printing". A data save cannot tick item 8.
- *Alternative.* A new approver permission for heads who are not superadmin. No such role exists; asked as Q20.

**D21. Head Approval (C15).**
- **Approve.** A comment is required ("with justification" [SOP 4.2]; at most 100 words). The approval stores `approved_cdr_link`, the Step 3 link being approved.
- **Request changes.**
  - Also requires a comment, and sets the design state to `CHANGES_REQUESTED`.
  - It **reopens Designing Activity**: Step 2 becomes active and Steps 3–4 become locked. Saved data is kept and Step 2's SLA restarts.
  - The CDR link stays, but must be approved again, because approval binds to the link value.
- The old draft-image approval (`approved_draft_ref`, `design/send`) is retired. Existing values stay on old rows.

**D22. Pre-print school verification (C8).**
- **Choices:** `NOT_REQUIRED` "Not required", `PENDING` "Pending", `VERIFIED` "Verified", `CHANGES_REQUIRED` "Not verified – changes needed".
- **Gate.** The value must be `VERIFIED` or `NOT_REQUIRED`.
- **Changes needed.** On `CHANGES_REQUIRED`, superadmin can "Send back to Designing Activity", which reopens Step 2 with a reason. A wholesale NB order has no Step 2, so it waits at Step 7.
- **Migration.** Existing `NOT_REQUIRED_OR_PENDING` values become `PENDING` (V18).

**D23. SLAs.**
- **Clock.** 24/7 calendar hours by default, shown as "SLA · N hours · 24/7". `WORKING_DAYS` stays available for overrides.
- **Start and breach.** The clock starts when the previous stage completes; Step 1's starts at placement (artefact 2). Reopening a stage restarts its clock. A stage is breached when now > due.
- **Defaults.**
  - NB: 2/72/2/2/24/6/24/50/4/72/2/4 hours. Steps 7–9 use the current app's values (C14).
  - 9-step items: 2/72/2/2/24/24/72/4/4 hours. Steps 2, 4 and 9 come from the flier prototype; Steps 1, 3 and 5 copy NB; Steps 6, 7 and 8 are [Ours].
- **Editing.** Superadmin edits a template step's default and the per-school overrides on the Time slots tab, which gains a template selector. A new default applies to stages that start afterwards.
- **No alerts.** There are no notifications or escalations; no sender exists (out of scope). Overdue work is visible in list counts, filters, stage headers and Upcoming actions.

**D24. Notebook title-card quantities (Steps 5–6) are computed by school-core from the placed order lines.**
- **Rule.** King plus Jumbo King, and Long: `ceil(n/8)+13`. Jumbo Long and FA: `ceil(n/4)+25`. A size with zero notebooks needs zero sheets (artefact 3). The sample order gives **1,088 / 513 / 775 / 0**.
- **Size matching.** The current frontend's matcher (`titleCardQuantities`, `steps.ts:139-162`) is ported to Java. Lines that match no title-card size (for example Drawing books) are listed as "Not on a title card", with their count.
- **No snapshot.** Placed lines never change, so Step 6's "Saved notebook title card quantities" shows the same values without one.

**D25. Quantities for other items.**
- school-core's summary returns each placed line's quantity, with the label of its main selection. The quantity is `book_count`, which every v2 form uses as the line quantity.
- Step 5 shows them read-only as "<Item> quantities, from the school's order form".
- The summary's KPI tiles show the total and up to four lines.

### 2.4 Files, messages, limits

**D26. Design files (C1).**
- **Limits.** Every workflow item gets them: at most **4 current files per order, 25 MB in total** across them.
- **Formats.** **.pdf, .cdr, .png, .jpeg/.jpg.** WEBP is no longer accepted for new uploads; existing WEBP files stay.
- **When.** Uploading or deleting is allowed while Designing Activity is active, or after Head Approval requested changes.
- **Enforcement.** school-core checks under a row lock on the order, so two concurrent uploads cannot overshoot either limit.
- **Existing orders.** An order already over a limit keeps its files, but new uploads are refused until it is back under.
- **Retired.** The unsent "4 drafts" email intent, which had no sender.
- **Storage.** Unchanged: `DESIGN_DRAFT` assets in the private bucket.

**D27. Handling CDR files.**
- **Detection, by content.** Either a RIFF container whose form type begins `CDR` or `cdr` (CorelDRAW X3 and earlier), or a ZIP container with a `.cdr` name and an entry under `content/` (X4 and later).
- **Storage and serving.** Stored as `application/vnd.corel-draw`. Never previewed or rendered; always served as an attachment download.
- **Parsing.** Nothing beyond the header and entry check.

**D28. Introduction messages (C2).**
- **Text.** The SOP's wording per item, held in the template.
- **Filling `{Name}`.** The browser replaces `{Name}` from the "Your name" field, which is prefilled with the signed-in user's name and can be edited. The message can be edited before copying.
- **Sending.** "Copy message" and "Open <School> Group Chat ↗"; nothing is sent by the system.
- **Recording.** Step 1 has one checklist item [Ours], "Introduction message sent in the school group". Ticking it records who, when and the final text (`INTRO_SENT`).
- **Templates:**
  - NB: "My name is {Name}, and I will be your point of contact for all notebook-related orders. Please feel free to reach out for any queries, updates, or follow-ups."
  - FE / FX / BBK: "My name is {Name}, and I will be handling your flier order. Please reach out for any queries, updates, or follow-ups." The noun is flier, flex or bill book.
  - General: the same sentence, with the item noun from D8.

**D29. Word limits.** The server enforces them. A word is a whitespace-separated token, and each field also has a character cap. The screen shows counters such as "n/100 words".

| Text | Limit |
| --- | --- |
| Call notes | 100 words |
| Stage notes, internal notes | 100 words (unchanged) |
| Head Approval comments | required, 100 words [Ours] |
| Final validation notes (9-step Step 8) | required, 100 words |
| Discrepancy / approval notes (NB Step 11) | optional, 200 words |
| Conversion comments, QC reviewer comments, quality-check notes, design notes | 1,000 characters (unchanged pattern) |

**D30. Links, not uploads, for evidence documents (C10).**
- **Covered fields.** CDR storage, board vendor bills, print sample evidence, variance report, delivery challan, bills, and proof of delivery / invoice.
- **Field kind `URL`.** `https://` only, at most 1,000 characters. Rendered as an external link that opens in a new tab with `rel="noopener noreferrer"`.
- *Why.* The SOP says "link" each time. Uploads would add seven new file kinds and their storage rules for no gain.

### 2.5 Vendors, groups, calls, contacts, notes

**D31. Vendor directory model (C17).**
- **Categories.** The SOP's 18, by their SOP names: Board Suppliers, Lamination Vendors, Binders, Offset Printers, Digital Printers, Flex Printers, Photo Paper Printers, Bill Book Makers, Batch Makers, Flute Board Makers, Trophies & Medals, File Makers, Sashes, Diaries, ID Cards, Flaps, Gifting Item Vendors & Printers, Non-Woven Bag Vendors.
  - Codes are stable.
  - Superadmin may rename a label (for example if "Batch Makers" turns out to mean "Badge Makers") or deactivate a category.
- **Vendor fields.** Name; one or more categories; contact person; phone (required); alternate phone; email; city; WhatsApp group invite (`https://chat.whatsapp.com/…`); notes (500 characters); active.
- **Scope.** Vendors are global, not per school, like designers: a vendor can serve every school.
- **Sample vendors.** The prototype's 12 vendors are seeded as sample vendors, tagged "Sample" and without phones, so existing selections stay valid. Superadmin replaces or deactivates them.
- *Alternatives.* Per-school vendors: duplication, since vendors are Custoking's suppliers, not the school's. Or keep hard-coded lists: every vendor added would need code plus a migration.

**D32. Who edits the directory.**
- Superadmin creates, edits and deactivates vendors and categories (Workflow set-up → Vendors).
- Operators read active vendors through the stage cards.
- A deactivated vendor stays visible, marked "Inactive", on orders that already selected it.

**D33. Per-order vendor selection, and what each step shows.**
- **Who selects.**
  - Vendors are chosen at Production Planning (Step 5) by that stage's assignee while it is active, or by superadmin at any time. Today only superadmin can; the SOP has the point of contact assign them.
  - Selections are stored per order as `{categoryCode: [vendorId…]}` and logged as `VENDORS_SELECTED`.
- **Categories offered.**
  - NB Step 5 shows only its four categories.
  - 9-step items show all 18, with the suggested categories listed first and expanded:

    | Item | Suggested categories |
    | --- | --- |
    | FE | Digital, Offset, Photo Paper Printers |
    | FX | Flex Printers |
    | BBK | Bill Book Makers, Offset Printers, Digital Printers |
    | CF | Digital, Offset, Photo Paper Printers |
    | FB | Flute Board Makers |
    | RC | Offset Printers, Digital Printers, File Makers |
    | BCH | Offset Printers, Digital Printers |
    | BLT / STD | none (all collapsed) |

- **Gate.** NB needs at least one vendor in each of its four categories. 9-step items need at least one vendor in any category.
- **What later steps show.** Only selected vendors. When none are selected, an amber warning reads "No <category> selected for this order. Select them in Production Planning to show their group chats here."

  | Template | Step | Categories shown |
  | --- | --- | --- |
  | NB | 6 | Board Suppliers, Offset Printers |
  | NB | 7 | Offset Printers, plus the Internal Approval Group |
  | NB | 8 | Offset Printers, Lamination Vendors |
  | NB | 9 | Lamination Vendors, Binders, Offset Printers |
  | NB | 10 | Binders |
  | 9-step | 6–9 | All selected vendors, plus the Internal Approval Group on Steps 6 and 8 |

**D34. WhatsApp groups. Links are only opened; the system sends nothing.**
- **School group.**
  - Kept per school (the existing `SCHOOL_GROUP` link, set by superadmin), with an optional per-item override in `workflow_school_item_group_links`. Superadmin or the order's current-step assignee may set the override, since operators create item groups.
  - Resolution order: item link → school link → none.
  - When there is none, superadmin sees "No group chat saved for this school"; an assignee gets an inline "Save group link" field on Step 1.
- **Vendor group.** Stored on the vendor record.
- **Internal approval group.** One global link in `workflow_settings`, set by superadmin. The existing per-school `INTERNAL_APPROVAL` link, where one exists, overrides it.
- **Accepted links.** Only `https://chat.whatsapp.com/<10–64 characters>`, opened in a new tab.
- **Migration.** V19 copies the existing per-school links stored under vendor keys onto the sample vendors, where unambiguous. They are no longer read.

**D35. Call logging (C7).**
- **Entities [SOP].** School, Designer, Printing Press, Manufacturing Factory, Internal Approver.
- **Outcomes [SOP].** Connected, No Answer, Busy, Callback Requested, Delay Reported, Changes Requested, Approval Received.
- **An entry holds:**
  - the entity;
  - who: picked from the order's contacts, vendors or designer, or typed, at most 200 characters;
  - the outcome;
  - notes, at most 100 words. Notes are required for Delay Reported, Changes Requested and Callback Requested [Ours];
  - next follow-up date and time, required for Callback Requested;
  - the stage, defaulting to the current one.
- **Rules.** Entries are append-only and belong to the order. Anyone who can work the order may log one: an in-scope operator or superadmin, not only the assignee.
- **Where entries appear.** The School & vendor tab, Activity history, School Feedback (a school's "Changes Requested"), and Upcoming actions (callbacks).

**D36. Contacts card (School & vendor tab).**
- **School contact.** Name and phone, held per order. The phone defaults to the school record's.
- **Designer.** Picked from the directory by superadmin or the assignee. This fixes today's mismatch, where the select is shown to operators but the server returns 403.
- **Vendor / factory contact.** Name and phone. It reuses the tracker's `vendor_name`/`vendor_phone` columns, so existing values appear.
- Phones render as `tel:` links ("☎ Call"). The legacy single-vendor cards are removed.

**D37. Assignment (C16) is kept.**
- The assigned operator is the order's point of contact, shown on the order card. Superadmin assigns per order or per step, as today.
- **New.** An eligible operator may **Take this order** when it is unassigned. That happens when operator resolution at creation found none, or several.
- Step 1's "Your name" defaults to the signed-in user.

**D38. Notes.**
- **Stage notes** become append-only entries per stage: the "Stage notes & next action" card with a "Save activity note" button. Each is at most 100 words, and anyone who can work the order may add one.
  - V20 migrates each existing overwritten note as one entry. The old `stage_note` columns are kept but no longer used.
- **Internal notes** (sidebar) stay order-level, append-only entries of at most 100 words.
- Both appear in Activity history.

**D39. Upcoming actions are derived, not entered.**
- The widget is read-only and labelled "Live list". There are no manual items; tracker spec v2 rejected a hand-maintained list.
- The server computes up to 6 rows:
  - the last completed stage, ticked;
  - the current stage's deadline;
  - each unmet gate of the current stage;
  - each open callback (the latest Callback Requested per entity with no later call to that entity).
- Each row is labelled Overdue, Due today, Due tomorrow, or a date.

**D40. Activity and audit ("calls, changes and approvals must be logged standardly").**
- **What is logged.** Every change writes a row to `workflow_actions`:
  - `TRACKER_CREATED`, `STEP_COMPLETED`, `STEP_IMPORTED`, `STEP_ASSIGNED`, `ORDER_CLAIMED`;
  - `STEP_DATA_SAVED`, with the changed field names and checklist counts, never the full values;
  - `STEP_REOPENED` and `SENT_BACK`, each with its reason;
  - `STEP_SIGNED_OFF`, `HEAD_APPROVED`, `CHANGES_REQUESTED`;
  - `VENDORS_SELECTED`, `DESIGNER_SET`, `CONTACTS_SAVED`, `GROUP_LINK_SET`, `INTRO_SENT`.
- **How it is read.** `GET …/activity` returns these merged with notes, call logs and legacy follow-ups. The client also merges in design-file uploads and removals from school-core's asset list. Consecutive saves by one person on one stage within 10 minutes are grouped.
- **Admin edits** (vendors, categories, SLA defaults, the internal group) keep only `updated_by` and `updated_at`. A full admin audit trail is out of scope.

### 2.6 Migration, permissions, rollout, scope

**D41. Existing notebook trackers keep working.** All of this is dev data; prod is on hold.
- V18 gives every tracker `definition_id NOTEBOOK_ORDER_TRACKER`, `category NOTEBOOKS`, `item NB` and noun `notebook`.
- Steps, statuses, assignees, clocks, saved data, sign-offs and history are untouched. Completed steps are never re-gated.
- Saved fields that the new template no longer shows (workbook totals, `jobReference`, `quantitySent`, `totalTitleUnits`, `factory`, `titlesSent`, `factoryContact` and the like) are declared `legacy` in the template. They are accepted on save, so a merge never drops them, and shown read-only under "Earlier details".
- Checklist positions keep their meaning (D17).
- Vendor selections are remapped to the new category codes and vendor ids (V19). Stage notes become entries (V20). `NOT_REQUIRED_OR_PENDING` becomes `PENDING` (V18).
- A tracker sitting on Step 4 with a draft-based "SENT" state needs a CDR link at Step 3 before approval. If the link is missing, superadmin reopens Step 3. Trackers already past Step 4 are unaffected.
- V38 queues the trackers' orders, so they pick up their order numbers.
- Follow-up rows (`workflow_follow_ups`) are read-only; they appear in Activity history and can no longer be added.

**D42. Permissions.** "Assignee" means the operator assigned to the stage in question. Item-scoped operators are limited to the categories × schools they hold. School admins keep only the order status view.

| Action | Superadmin | School-wide operator (their schools) | Item-scoped operator (their schools × items) | School admin |
| --- | --- | --- | --- | --- |
| See the workflow list and detail | ✓ | ✓ all workflow items | ✓ their items | ✗ (order status only) |
| Save stage data, tick checklists, complete a stage | ✓ | if assignee | if assignee | ✗ |
| Upload or delete design files | ✓ | Step 2 assignee | Step 2 assignee | ✗ |
| Select vendors; set designer and contacts; set the item's school-group link | ✓ | current-step assignee | current-step assignee | ✗ |
| Log a call; add a stage or internal note | ✓ | ✓ | ✓ | ✗ |
| Head Approval, sign-offs, reopen, send back | ✓ | ✗ | ✗ | ✗ |
| Assign a stage or order | ✓ | ✗ | ✗ | ✗ |
| Take an unassigned order | — | if eligible | if eligible | ✗ |
| Vendor directory, designers, SLAs, school-wide groups, internal approval group | ✓ edit | read vendors | read vendors | ✗ |
| School order code; category order code and template | ✓ | ✗ | ✗ | ✗ |

- **No identity change is needed.** `notebook-tracker:read|act` are already held by the OPERATIONS role and by `ITEM_OPERATOR_PERMISSIONS`.
- **Operations now scopes by category.** An item-scoped operator's schools for each workflow category come from `TenantContext.operatorSchoolsFor(category)`. The RLS session variable `app.operator_schools` holds the union of those schools, and queries additionally filter on each row's (school, category) pair.

**D43. Rollout.**
- **Target.** Dev only. Prod is on hold, so there is no prod plan. The runbook now has a production section for when the hold ends: the single migration job per service while `main` serves, the prerequisites, the read-only pre-counts, and the rows to bring forward after operations serves.
- **Branch.** One integration branch, `feat/item-order-workflows`, cut from dev. Each work package is a branch merged into it, and each runs `CI / PR` through `workflow_dispatch`: PR CI only triggers on PRs into dev or main, and tests run only on PRs.
- **Merge.** One PR from the integration branch into dev, which runs full CI. Merging it deploys to dev.
- **No feature flag.** Setting a category's `workflow_definition` to null stops new placements of that category from starting a workflow.

**D44. Out of scope.**
- Sending WhatsApp messages, or any WhatsApp API.
- Email, push or SMS notifications, and SLA escalations.
- Business-hours calendars and holidays.
- Uploading bills, challans, variance reports or POD (they stay links).
- A template editor UI.
- A vendor portal.
- Reporting dashboards on workflow turnaround.
- Dropping the V36 tables (still read; see D4). The internal-path alias went in the contract release (see "Contract release: retiring the rollout bridges").
- Removing the notebook workbook-preview endpoint. Its UI is retired; the endpoint stays.
- Rotating the credentials seen in the recording (an owner action).
- Any prod work.

### 2.7 Step definitions (detail of D15–D16)

Card names are the prototype's. "Gate" lists what `Complete` requires. Every stage also has the Stage notes card and footer described in §4.3.

#### Notebook template: `NOTEBOOK_ORDER_TRACKER` (12 steps; wholesale skips 2–4)

| # | Code (status) | Title [Proto] | SLA | Phase |
| --- | --- | --- | --- | --- |
| 1 | `ORDER_DETAILS` | Order Details | 2 h | Intake & design |
| 2 | `DESIGNING_ACTIVITY` | Designing Activity | 72 h | Intake & design |
| 3 | `CDR_CONVERSION` | CDR Conversion | 2 h | Intake & design |
| 4 | `HEAD_APPROVAL` | Head Approval | 2 h | Intake & design |
| 5 | `PRODUCTION_PLANNING` | Production Planning | 24 h | Planning |
| 6 | `BOARD_PROCUREMENT` | Board Procurement | 6 h | Planning |
| 7 | `PRE_PRINT_QUALITY_GATE` | Pre-Print Quality Check | 24 h (current app) | Production |
| 8 | `PRINTING_PRESS` | Printing Press | 50 h (current app) | Production |
| 9 | `FACTORY_HANDOVER` | Factory Handover | 4 h (current app) | Production |
| 10 | `PRODUCTION_FOLLOW_UP` | Production Follow-up | 72 h | Production |
| 11 | `VALIDATION_COUNT` | Validation Count | 2 h | Verification |
| 12 | `DELIVERY_INVOICING` | Delivery & Invoicing | 4 h | Delivery |

**Step 1, Order Details.** "Verify order form, quantity, dimensions and notebook specifications." There is no School & vendor tab on this step [Proto].
- **Cards.**
  - "School order PDF link": field `orderPdfLink` (URL), placeholder "Paste link to the school order PDF", button "Save PDF link".
  - "School Group Chat & Introduction Message": school chip; "Your name"; "Message template" (D28); "Copy message"; "Open <School> Group Chat ↗"; helper "Copy the message, then open the mapped school WhatsApp group in a new tab to send it."
  - Checklist `introChecks` [Ours]: "Introduction message sent in the school group".
- **Gate.** `orderPdfLink` is saved and `introChecks` is complete.

**Step 2, Designing Activity.** "Coordinate school feedback, designer work, drafts and revisions."
- **Cards.**
  - "School Group Chat".
  - "Upload Design Files" (D26), with drop zone "Select files to upload" and the caption "Up to 4 files · 25 MB in total · PDF, CDR, PNG, JPEG". The caption is ours; the prototype's "25 MB per file" is replaced.
- **Gate.** At least one design file.

**Step 3, CDR Conversion.** "Convert the approved design into production-ready CDR artwork."
- **Fields.**
  - `cdrLink` (URL): label "Controlled storage link", placeholder "Paste SharePoint / Drive link".
  - `cdrNotes`: label "Conversion comments", placeholder "Fonts outlined, colours checked, artwork matches approved version".
  - The existing `cdrFilename` is a legacy field.
- **Checklist `cdrChecks`** [Note FE/FX/BBK wording, also applied to NB (C11)]: "Fonts outlined" · "Colours checked" · "Artwork matches approved version".
- **Button.** "Save CDR details".
- **Gate.** `cdrLink` is saved and `cdrChecks` is complete.

**Step 4, Head Approval.** "Obtain approval for the exact design version before production."
- **Cards.**
  - Info: "Approval applies to the CDR link saved in CDR Conversion. Your decision is recorded against your signed-in account."
  - "Design version for approval": the Step 3 link, or the warning "No CDR link saved yet. Go back to CDR Conversion and save the link first."
  - "Approval comments": required, placeholder "Add feedback or reason for rejection".
  - "✓ Approve design": disabled until a CDR link exists.
  - "Request changes" (D21).
- **Gate.** `HEAD_APPROVED`. Superadmin completes.

**Step 5, Production Planning.** "Confirm materials, factory capacity, quantities and production dates."
- **Cards.**
  - "School order folder" (badge "From Order Details"), or the warning "No folder link saved yet. Add the folder link in Order Details first."
  - "Notebook title card quantities" (D24).
  - "Supplier Contact Groups": the four categories.
  - "Select Vendors for This Order".
  - "Production planning checklist" `planningChecks` [Proto]: `NOTEBOOK ORDER SHARED WITH BINDER` · `BOARDS AVAILABILITY CHECKED` · `DESIGN STATUS CHECKED`.
  - Optional field `targetCompletion` (date): "Target completion date". It feeds the Estimated completion tile.
- **Gate.** `planningChecks` is complete, and each of the four categories has a vendor.

**Step 6, Board Procurement.** "Calculate title-board requirements and track procurement."
- **Cards.**
  - "Saved notebook title card quantities" (TYPE | SIZE | QUANTITY).
  - "Board vendor contacts".
  - "Offset printer contacts".
  - "Board Vendor Bills": field `boardBillLink` (URL), "Link to board vendor bills", placeholder "Paste Google Drive or PDF folder link", button "Save bill link".
- The "TBD" banner is removed.
- **Gate.** `boardBillLink` is saved.

**Step 7, Pre-Print Quality Check.** "Verify physical sample, margins, colours, material and approvals before bulk printing."
- **Cards.**
  - "Offset Printer Group Links" (kept; C12).
  - "Internal Approval Group Link".
  - "Mandatory pre-print quality gate", checklist `checks` [Proto]:
    1. School name, logo and spelling verified
    2. Latest approved artwork version confirmed
    3. Dimensions, bleed and safe margins checked
    4. Board material, GSM and print specifications verified
    5. Physical print sample inspected for defects
    6. School verification recorded where required
    7. Print quantity and title-wise breakup verified
    8. Authorised quality reviewer releases bulk printing (set by sign-off only)
- **Fields.**
  - `sampleLink` (URL): "Print sample evidence link".
  - `schoolVerification` (D22).
  - `qcComments`: "QC reviewer comments".
  - Button "Save QC evidence".
- **Sign-off.** Banner "Bulk printing blocked until all checks and reviewer sign-off are complete." Superadmin button "Record reviewer sign-off".
- **Gate.** Checks 1–7 and `sampleLink` are saved, verification is VERIFIED or NOT_REQUIRED, and the sign-off is recorded (it ticks item 8).

**Step 8, Printing Press.** "Handover approved artwork to the press and track quantities."
- **Cards.**
  - "Offset Printer Group Links".
  - "Lamination Vendor Group Links".
  - "Printing & Lamination Checklist" `pressChecks` [Proto]: `VERIFIED THE IMPRESSIONS FOR EACH DESIGN` · `DESIGN SENT FOR MAKING PLATES` · `DESIGN PLATES RECEIVED` · `PRINTING SCHEDULED/ EXPECTED COMPLETION TIME` · `COMPLETION OF PRINTING` · `RECEIVED FOR LAMINATION` · `SCHEDULED FOR LAMINATION` · `LAMINATION COMPLETED`.
  - Optional date `expectedReturnDate`, labelled "Expected completion" and shown beside item 4.
- **Gate.** All 8 ticked (SOP "mandatory").

**Step 9, Factory Handover.** "Reconcile printed titles and confirm making-factory receipt."
- **Cards.**
  - "Vendor Contact Groups" (Lamination Vendors, Binders, Offset Printers).
  - "Factory Handover Checklist" `handoverChecks` [Proto]: "Verified the titles received".
- **Gate.** The checklist is complete.

**Step 10, Production Follow-up.** "Log calls, progress updates, blockers and revised completion dates."
- **Cards.**
  - "Selected Binder Group Chats".
  - "Production Follow-up Checklist" `productionChecks` [Proto]: `CONFIRMED RECEIVING THE TITLE` · `FOLDING OF TITLE` · `RULED PAPER RECEIVED` · `PAPER COUNTING` · `PAPER FILLING` · `PINNING` · `CUTTING` · `PACKING`.
  - Optional date `revisedCompletion` [Ours], "Revised completion date". It feeds the Estimated completion tile.
- **Gate.** All 8 ticked.

**Step 11, Validation Count.** "Reconcile ordered, produced, accepted and dispatch quantities."
- **Cards.**
  - "Validation Documents": `varianceReportLink`, `deliveryChallanLink` and `billsLink` (all URL), button "Save document links".
  - "Discrepancy / approval notes": `discrepancyNote`, optional, at most 200 words.
  - Optional "Counts": produced, accepted and dispatched (existing fields; C9).
- **Gate.** All three links are saved, and `COUNTS_CONSISTENT` passes: if all counts are entered and they mismatch, a note is required.

**Step 12, Delivery & Invoicing.** "Record dispatch sign-off, delivery proof and invoice initiation."
- **Fields.**
  - `transporter` (required): "Transporter / vehicle", placeholder "Vehicle / courier".
  - `dispatchDate` (required).
  - Optional `lrNumber`: "LR / docket number".
  - "School Group Chat".
  - `proofLink` (URL, required): "Proof of delivery / invoice link", placeholder "Controlled document link".
  - "Executive consignment sign-off": Pending / Signed off, which is the sign-off action.
  - `invoiceStatus`: Not initiated / Initiated / Sent / Paid.
  - Optional `invoiceNumber`.
  - Button "Save dispatch & invoice details".
- **Gate.** Transporter, dispatch date and proof link are saved, and the consignment sign-off is recorded. The invoice status need not be Paid; superadmin can still edit it after completion.

#### Nine-step templates: FE, FX, BBK and General

| # | Code | Title [SOP] | SLA | Phase | Cards and fields | Gate |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `ORDER_DETAILS` | Order Details | 2 h | Intake & design | As NB Step 1, with the item's message | PDF link + intro ticked |
| 2 | `DESIGNING_ACTIVITY` | Designing Activity | 72 h [Proto] | Intake & design | School Group Chat; Upload Design Files; `designNotes` "Design notes / version details" [Proto flier] | ≥1 design file |
| 3 | `CDR_CONVERSION` | CDR Conversion | 2 h | Intake & design | As NB Step 3 (`cdrChecks` is [Note] wording) | CDR link + 3 checks |
| 4 | `HEAD_APPROVAL` | Head Approval | 2 h [Proto] | Intake & design | As NB Step 4; the CDR card is titled "CDR file submitted for approval" [Proto flier] | `HEAD_APPROVED` (superadmin) |
| 5 | `PRODUCTION_PLANNING` | Production Planning | 24 h | Planning | School order folder; "<Item> quantities" (D25); Supplier Contact Groups and Select Vendors (all 18, item suggestions first); optional `targetCompletion` | ≥1 vendor in any category |
| 6 | `PRE_PRINT_QUALITY_CHECK` | Pre-Print Quality Check | 24 h [Ours] | Production | Selected vendors; Internal Approval Group; `qcNotes` "Quality check notes", placeholder "Confirm artwork, size, paper GSM, quantity, bleed and spelling" [Proto]; item checklist (below) | Checklist complete |
| 7 | `PRODUCTION_FOLLOW_UP` | Production Follow-up | 72 h [Ours] | Production | Selected vendors; 4-item checklist [Ours] (below); optional `revisedCompletion` | Checklist complete |
| 8 | `FINAL_APPROVAL` | Final Approval (C4) | 4 h [Ours] | Verification | Selected vendors; Internal Approval Group; checklist "Finished items checked against approved specifications" [Note]; `finalValidationNotes`, required, ≤100 words; superadmin "Record internal approval" | Check + notes + sign-off |
| 9 | `DELIVERY_INVOICING` | Delivery & Invoicing | 4 h [Proto] | Delivery | As NB Step 12, plus `deliveryStatus` Pending / Delivered [Note]; "Selected Vendor WhatsApp Groups" | As NB Step 12, plus Delivered |

**Step 6 checklists.**

| Template | Items | Source |
| --- | --- | --- |
| FE | "Flier artwork and print specifications checked and approved" | [Proto]/[Note] |
| FX | "Checked the size of the flex" · "Verified the quantity of the flex" · "Verified the design file" | [Note] |
| BBK | "Verified the bill book size" · "Verified number of books and copies" · "Verified the design file" | [Note] |
| General | "Verified the item size and specifications" · "Verified the quantity" · "Verified the design file" | [Ours] |

**Step 7 checklist (all four templates, C13)** [Ours]:
1. "Print job confirmed with the vendor and schedule agreed"
2. "Printing completed"
3. "Finishing completed (cutting, lamination or binding as applicable)"
4. "Finished items counted, packed and ready for dispatch"

### 2.8 The client's own contradictions, decided (C1–C17)

| D | C | Topic | Decision (default) | Applied in |
| --- | --- | --- | --- | --- |
| D45 | C1 | Upload limit | 4 files, 25 MB **in total**, .pdf/.cdr/.png/.jpeg (SOP over the prototype) | D26 |
| D46 | C2 | NB intro text | The SOP's shorter text. It stays editable before copying | D28 |
| D47 | C3 | Invoice status lists | Not initiated / Initiated / Sent / Paid for every item. "Pending / Raised / Paid" is dropped | §2.7 |
| D48 | C4 | Name of 9-step Step 8 | "Final Approval". SOP spellings "Pre-Print Quality Check" and "Production Follow-up" | §2.7 |
| D49 | C5 | SOP step names | Prototype names on screen ("Order Details", "Printing Press"); the SOP names appear in the stage description tooltip | §2.7 |
| D50 | C6 | Order ID format and sequence | SOP format; counter per item per calendar year; prototype's `NB-2026-0042` not copied | D11 |
| D51 | C7 | "Manufacturing Factory" vs "making factory" | SOP label "Manufacturing Factory". Who: picked from the order's contacts and vendors, or typed | D35 |
| D52 | C8 | Pre-print school verification | Not required / Pending / Verified / Not verified. "Not verified" can send the order back to Designing Activity | D22 |
| D53 | C9 | Validation Count notes and counts | Notes optional, ≤200 words. Counts optional; a note is required only if counts mismatch | §2.7 |
| D54 | C10 | Proof of delivery | A link; transporter is free text plus optional LR/docket number | D30 |
| D55 | C11 | NB conversion comments | Free-text comments plus the same 3 checks as FE/FX/BBK | §2.7 |
| D56 | C12 | Offset printer links on NB Step 7 | Kept | §2.7 |
| D57 | C13 | 9-step follow-up checklist | 4 items, our wording | §2.7 |
| D58 | C14 | Unknown SLAs | NB 7–9 = 24/50/4 h; 9-step 1/3/5 copy NB, 6/7/8 = 24/72/4 h | D23 |
| D59 | C15 | "Request changes" target | Reopens Designing Activity | D21 |
| D60 | C16 | Operator assignment | Kept; plus "Take this order" | D37 |
| D61 | C17 | Vendor category naming | SOP names, including "Batch Makers"; labels editable by superadmin | D31 |

---

## 3. Data model, API and events

### 3.1 operations-service: `workflow` schema migrations

New files only; applied migrations are never edited. Backfills run under `SET LOCAL app.bypass_rls = 'on'`, as V7 requires for owner backfills on forced-RLS tables. Every new tenant table gets the existing three-part RLS policy (school, bypass, `app.operator_schools`), `FORCE ROW LEVEL SECURITY`, and grants to `app_rt`, which the `afterMigrate` ACL script then copies to `ims_operations_rt`.

**V17 `__item_order_workflow_definitions.sql`**
- Rename `NOTEBOOK_ORDER_TRACKER` to "Notebook order workflow".
- Insert the definitions `FLIER_ORDER_WORKFLOW`, `FLEX_ORDER_WORKFLOW`, `BILL_BOOK_ORDER_WORKFLOW` and `GENERAL_ITEM_ORDER_WORKFLOW`.
- `workflow_steps`: add `step_code VARCHAR(64)` and `phase VARCHAR(16)`, with CHECK `phase IN ('INTAKE','PLANNING','PRODUCTION','VERIFICATION','DELIVERY')`.
- Set the codes and phases on the 12 NB rows. Insert 9 rows for each new definition: names, codes, phases and default SLAs in HOURS from §2.7.
- Then make `step_code` and `phase` NOT NULL, and add `UNIQUE (definition_id, step_code)`.

**V18 `__generalise_order_trackers.sql`**
- `workflow_notebook_trackers`: add
  - `definition_id VARCHAR(100) NOT NULL DEFAULT 'NOTEBOOK_ORDER_TRACKER' REFERENCES workflow_definitions(id)`
  - `category_code VARCHAR(60) NOT NULL DEFAULT 'NOTEBOOKS'`
  - `item_code VARCHAR(4) NOT NULL DEFAULT 'NB' CHECK (item_code ~ '^[A-Z]{2,4}$')`
  - `item_noun VARCHAR(40) NOT NULL DEFAULT 'notebook'`
  - `order_number VARCHAR(40)`
  - `school_contact_name VARCHAR(200)`, `school_contact_phone VARCHAR(32)`
  - `approved_cdr_link VARCHAR(1000)`, `design_comment VARCHAR(1000)`
  - `imported_from VARCHAR(40) CHECK (imported_from IN ('PRODUCT_WORKFLOW_V36'))`
- Add `uk_wnt_order_number` (unique where not null) and `idx_wnt_category_school (category_code, school_id)`.
- Replace `ck_wnt_approved_ref` with `CHECK ((approved_draft_ref IS NULL AND approved_cdr_link IS NULL) OR design_state = 'APPROVED')`.
- Backfill: on Step 7 rows of `NOTEBOOK_ORDER_TRACKER`, change `schoolVerification` from `NOT_REQUIRED_OR_PENDING` to `PENDING`.

**V19 `__vendor_directory_and_group_links.sql`**
- **`workflow_vendor_categories`**
  - Columns: `code VARCHAR(40) PK CHECK (^[A-Z][A-Z0-9_]{1,39}$)`, `name VARCHAR(100) NOT NULL`, `sort_order SMALLINT`, `active BOOLEAN`, `updated_by`, `updated_at`.
  - Seed the 18 categories (D31). Codes: `BOARD_SUPPLIERS`, `LAMINATION_VENDORS`, `BINDERS`, `OFFSET_PRINTERS`, `DIGITAL_PRINTERS`, `FLEX_PRINTERS`, `PHOTO_PAPER_PRINTERS`, `BILL_BOOK_MAKERS`, `BATCH_MAKERS`, `FLUTE_BOARD_MAKERS`, `TROPHIES_MEDALS`, `FILE_MAKERS`, `SASHES`, `DIARIES`, `ID_CARDS`, `FLAPS`, `GIFTING`, `NON_WOVEN_BAGS`.
  - Global: no tenant key and no RLS, like `workflow_designers`.
- **`workflow_vendors`**
  - Columns:
    - `id BIGSERIAL PK`
    - `name VARCHAR(200) NOT NULL` (not blank)
    - `contact_person VARCHAR(200)`
    - `phone VARCHAR(32)` (the application requires it, except on sample rows)
    - `alt_phone VARCHAR(32)`, `email VARCHAR(254)`, `city VARCHAR(100)`
    - `whatsapp_group_url VARCHAR(2048) CHECK (… ~ '^https://chat\.whatsapp\.com/[A-Za-z0-9_-]{10,64}$')`
    - `notes VARCHAR(500)`
    - `sample BOOLEAN NOT NULL DEFAULT false`
    - `legacy_key VARCHAR(64) UNIQUE`
    - `active BOOLEAN NOT NULL DEFAULT true`
    - created and updated by and at
  - Global, no RLS.
- **`workflow_vendor_category_members`**: `(vendor_id → workflow_vendors ON DELETE CASCADE, category_code → workflow_vendor_categories, PK both)`.
- Seed the 12 prototype vendors as `sample = true`, with `legacy_key` set to the old id (`board_shree` and so on) and their memberships.
- Rewrite `workflow_notebook_trackers.vendor_selections`:
  - Category keys: `board`→`BOARD_SUPPLIERS`, `lamination`→`LAMINATION_VENDORS`, `binder`→`BINDERS`, `offset`→`OFFSET_PRINTERS`.
  - Vendor ids: each legacy key becomes the numeric vendor id, through `legacy_key`.
- Copy per-school vendor-key WhatsApp links onto `workflow_vendors.whatsapp_group_url`, only where all schools hold the same invite. The source rows are left in place and are no longer read.
- **`workflow_settings`** `(key VARCHAR(64) PK CHECK (key IN ('INTERNAL_APPROVAL_GROUP_URL')), value VARCHAR(2048) NOT NULL, updated_by BIGINT NOT NULL, updated_at)`. The value must match the invite pattern. Global; no seed value.
- **`workflow_school_item_group_links`** `(school_id, category_code VARCHAR(60), invite_url CHECK invite pattern, updated_by, updated_at, PK (school_id, category_code))`, with RLS.

**V20 `__call_logs_and_note_entries.sql`**
- **`workflow_call_logs`**
  - Columns:
    - `id BIGSERIAL PK`, `instance_id`, `school_id`, `step_order INTEGER`
    - `contacted VARCHAR(30) CHECK IN ('SCHOOL','DESIGNER','PRINTING_PRESS','MANUFACTURING_FACTORY','INTERNAL_APPROVER')`
    - `outcome VARCHAR(30) CHECK IN ('CONNECTED','NO_ANSWER','BUSY','CALLBACK_REQUESTED','DELAY_REPORTED','CHANGES_REQUESTED','APPROVAL_RECEIVED')`
    - `contact_label VARCHAR(200)`
    - `vendor_id → workflow_vendors`, `designer_id → workflow_designers`
    - `notes TEXT CHECK (char_length(notes) <= 1000)`
    - `next_follow_up_at TIMESTAMPTZ`
    - `created_by BIGINT NOT NULL`, `created_at`
    - FK `(instance_id, school_id)` → `workflow_instances ON DELETE CASCADE`
  - Indexes: `(instance_id, created_at)`, and `(school_id, next_follow_up_at)` where the follow-up is set.
  - RLS. `app_rt` gets SELECT and INSERT only: append-only, like V11.
- `workflow_internal_notes`: add `step_order INTEGER CHECK (step_order BETWEEN 1 AND 99)`. Null means an order-level internal note; set means a stage note.
- Copy each non-blank `workflow_instance_steps.stage_note` that has a `stage_note_saved_by` into `workflow_internal_notes` (author, body, `created_at` = `stage_note_saved_at`, `step_order`), once.

**Template rows vs instance rows.** The engine checks in code that an instance's steps belong to its tracker's `definition_id`. The migration test also asserts this.

### 3.2 school-core migrations

**`tenant_school` V31 `__school_order_code.sql`**
- Add `schools.order_code VARCHAR(16)`.
- Derive codes per D12 in a PL/pgSQL loop ordered by `id`, with collision suffixes.
- Then add `CHECK (order_code ~ '^[A-Z0-9]{2,8}_[A-Z0-9]{2,6}$')`, a unique index, and `NOT NULL`.

**`catalog` V37 `__order_numbers.sql`.** It runs after `tenant_school`; the per-schema Flyway beans are chained.
- `product_categories`: add `order_code VARCHAR(4) CHECK (^[A-Z]{2,4}$)` (unique where not null), `item_noun VARCHAR(40)`, and `workflow_definition VARCHAR(100) CHECK (IN the five template ids)`. Seed them per D8.
- `catalog_orders`: add `order_number VARCHAR(40)`, unique where not null. Add a `BEFORE UPDATE` trigger that raises when a non-null `order_number` changes.
- `order_number_counters (item_code VARCHAR(4), year SMALLINT CHECK (2000–2999), last_value INTEGER NOT NULL CHECK (≥ 0), PK (item_code, year))`.
  - It holds no tenant data, so it has no RLS policy and is added to the RLS guard test's list of global tables.
  - Grant SELECT, INSERT and UPDATE to `app_rt`.
- Backfill (D13):
  - Number every order with `status <> 'DRAFT'` whose category has an `order_code`.
  - Order them by `COALESCE(placed_at, created_at)` then `id`, within each item code and calendar year.
  - The date comes from `COALESCE(placed_at, created_at) AT TIME ZONE schools.time_zone`.
  - Then set each counter to its maximum.

**`catalog` V38 `__retire_product_order_workflows.sql`**
- Under bypass:
  - Mark open, placed orders of workflow categories (excluding notebooks) with `attributes.trackerManaged = true, trackerPending = true`. "Open" means not DRAFT, CANCELLED or a terminal status. Insert them into `notebook_tracker_creation_queue`, `ON CONFLICT DO NOTHING`.
  - Insert every existing tracker-managed order that has an `order_number` into the same queue (D13).
- Revoke INSERT, UPDATE and DELETE on `product_order_workflows` and `product_order_workflow_steps` from `app_rt`, and add a table comment "read-only since V38".

### 3.3 Template file format (operations resources)

```json
{
  "definitionId": "NOTEBOOK_ORDER_TRACKER",
  "title": "Notebook order workflow",
  "introMessage": "My name is {Name}, and I will be your point of contact for all notebook-related orders. Please feel free to reach out for any queries, updates, or follow-ups.",
  "steps": [{
    "order": 7, "code": "PRE_PRINT_QUALITY_GATE", "title": "Pre-Print Quality Check", "phase": "PRODUCTION",
    "description": "Verify physical sample, margins, colours, material and approvals before bulk printing.",
    "sla": {"value": 24, "unit": "HOURS"}, "customisedOnly": false, "icon": "ShieldCheck",
    "tabs": ["WORK", "SCHOOL_VENDOR", "CHECKLIST", "ACTIVITY"],
    "blocks": [
      {"type": "VENDOR_GROUPS", "title": "Offset Printer Group Links", "categories": ["OFFSET_PRINTERS"]},
      {"type": "INTERNAL_APPROVAL_GROUP"},
      {"type": "CHECKLIST", "checklist": "checks", "title": "Mandatory pre-print quality gate"},
      {"type": "FIELDS", "fields": ["sampleLink", "schoolVerification", "qcComments"], "save": "Save QC evidence"},
      {"type": "SIGN_OFF"}, {"type": "SEND_BACK"}
    ],
    "fields": {
      "sampleLink": {"kind": "URL", "label": "Print sample evidence link", "placeholder": "Photo / sample / approved proof link"},
      "schoolVerification": {"kind": "CHOICE", "label": "School verification", "choices": [["NOT_REQUIRED","Not required"],["PENDING","Pending"],["VERIFIED","Verified"],["CHANGES_REQUIRED","Not verified – changes needed"]]},
      "qcComments": {"kind": "TEXT", "max": 1000, "label": "QC reviewer comments", "placeholder": "Defects, corrections, exceptions..."}
    },
    "checklists": {"checks": {"items": ["School name, logo and spelling verified", "...", "Authorised quality reviewer releases bulk printing"], "signOffItem": 7, "source": "prototype"}},
    "gates": [
      {"type": "ALL_CHECKED", "checklist": "checks", "except": [7]},
      {"type": "FIELDS_PRESENT", "fields": ["sampleLink"]},
      {"type": "CHOICE_IN", "field": "schoolVerification", "values": ["VERIFIED", "NOT_REQUIRED"]},
      {"type": "SIGNED_OFF"}
    ],
    "signOff": {"label": "Record reviewer sign-off", "role": "SUPERADMIN", "banner": "Bulk printing blocked until all checks and reviewer sign-off are complete."}
  }]
}
```

- **Field kinds.** `TEXT` (max characters), `WORDS` (max words plus a 4,000-character cap), `URL` (https, at most 1,000), `DATE`, `WHOLE`, `NUMBER`, `CHOICE`, and checklists (stored as ticked indexes, as today).
- **Legacy fields.** `"legacy": true` fields are accepted and kept but only shown read-only.
- **Block types (closed set).** `ORDER_PDF_LINK`, `INTRO_MESSAGE`, `SCHOOL_GROUP`, `DESIGN_FILES`, `FIELDS`, `CHECKLIST`, `CDR_FOR_APPROVAL`, `HEAD_APPROVAL`, `ORDER_FOLDER`, `TITLE_CARD_QUANTITIES`, `SAVED_TITLE_CARDS`, `ITEM_QUANTITIES`, `VENDOR_DIRECTORY`, `VENDOR_SELECTION`, `VENDOR_GROUPS`, `INTERNAL_APPROVAL_GROUP`, `SIGN_OFF`, `SEND_BACK`, `LEGACY_DETAILS`.
- **Gate types (closed set).** As listed in D19.
- `VENDOR_SELECTION` takes `categories` (fixed) or `suggestedByItem` (a map from item code to categories).

### 3.4 Engine types (Java, operations; the shared interfaces of §5.2)

```java
// application/orderworkflow/template
record WorkflowTemplate(String definitionId, String title, String introMessage, List<StepSpec> steps) {
    List<StepSpec> pathFor(boolean customised); Optional<StepSpec> step(int order); }
record StepSpec(int order, String code, String title, String description, Phase phase, Sla defaultSla,
    boolean customisedOnly, List<Tab> tabs, List<BlockSpec> blocks, Map<String, FieldSpec> fields,
    Map<String, ChecklistSpec> checklists, List<GateSpec> gates, SignOffSpec signOff, boolean superadminCompletes) { }
interface WorkflowTemplates { WorkflowTemplate get(String definitionId); Collection<WorkflowTemplate> all(); }
// application/orderworkflow/rules
final class StepDataRules { static Map<String,Object> merge(StepSpec s, Map<String,Object> saved, Map<String,Object> raw);
    static boolean sameValues(Map<String,Object> a, Map<String,Object> b); }
final class Gates { static List<GateFailure> unmet(StepSpec s, GateFacts f); }
record GateFacts(Map<String,Object> data, boolean signedOff, String designState, String approvedCdrLink,
    String currentCdrLink, int designFileCount, Map<String, List<Long>> vendorSelections, long orderedCount) { }
record GateFailure(String code, String message) { }
final class WorkflowProgress { /* TrackerProgress generalised over List<StepSpec> */ }
// application/orderworkflow (seams between work packages)
interface OrderAccess { AccessibleOrder require(String orderId, boolean write); }
record AccessibleOrder(long instanceId, long schoolId, String orderId, String categoryCode, String definitionId,
    Integer activeStep, Role role, long userId) { }
interface VendorLookup { boolean inCategory(long vendorId, String category); List<VendorSummary> vendors(Collection<Long> ids);
    Set<String> categoryCodes(); }
interface GroupLinkResolver { ResolvedGroups resolve(long schoolId, String categoryCode, Collection<Long> vendorIds); }
interface CallbackSource { List<OpenCallback> openCallbacks(long instanceId); }
```

### 3.5 API endpoints

**operations-service**, base `/api/v1/workflows`. The gateway already routes this prefix to operations and injects the service token, so `routing.js` needs no change.
- "read" means superadmin, or an operator holding `notebook-tracker:read` with scope over the order's school and category.
- "act" means the same with `notebook-tracker:act`, plus the per-action rule in D42.

| Method and path | Who | Body / params → result |
| --- | --- | --- |
| `GET /item-orders` | read | `item` (repeatable category), `step`, `phase`, `assignee=me\|unassigned\|<id>`, `schoolId`, `sla=OVERDUE\|DUE_SOON\|ON_TRACK\|NO_TIMER`, `q`, `includeFinished`, `sort=due\|placed\|number`, `limit≤100`, `offset` → `{items, total, counts:{open, overdue, dueToday, unassigned, mine}}` |
| `GET /item-orders/templates`, `GET /item-orders/templates/{definitionId}` | read | Template specs (§3.3) with current default SLAs |
| `PUT /item-orders/templates/{definitionId}/steps/{step}/sla` | superadmin | `{value, unit}` |
| `GET /item-orders/{orderId}` | read | `OrderWorkflowView`: identity, number, definition, steps (status, assignee, clock, data, sign-off, version, `can`, `unmet`), design state, selections plus the selected vendors, designer, contacts, groups, latest notes, `upcomingActions`, server `now` |
| `PUT /item-orders/{orderId}/steps/{step}/data` | act | `{data, expectedVersion}`, a partial merge (replaces `/draft`) |
| `POST /item-orders/{orderId}/steps/{step}/complete` | act | `{expectedVersion}`; 409 `GATE_UNMET` with `unmet[]` |
| `POST /item-orders/{orderId}/steps/{step}/reopen` | superadmin | `{reason, expectedVersion}` (also "Send back to Designing Activity") |
| `POST /item-orders/{orderId}/steps/{step}/sign-off` | superadmin | `{expectedVersion}`; only on steps whose template has `signOff` |
| `POST /item-orders/{orderId}/head-approval/approve` | superadmin | `{comment, expectedVersion}` |
| `POST /item-orders/{orderId}/head-approval/request-changes` | superadmin | `{comment, expectedVersion}` |
| `PUT /item-orders/{orderId}/steps/{step}/assignee`, `PUT /item-orders/{orderId}/assignee` | superadmin | `{userId}` |
| `POST /item-orders/{orderId}/claim` | eligible operator, unassigned order | — |
| `PUT /item-orders/{orderId}/vendor-selections` | Step 5 assignee while active, or superadmin | `{selections, expectedVersion}` |
| `PUT /item-orders/{orderId}/designer` | current-step assignee, or superadmin | `{designerId}` |
| `PUT /item-orders/{orderId}/contacts` | current-step assignee, or superadmin | `{schoolContactName, schoolContactPhone, factoryContactName, factoryContactPhone}` |
| `GET /item-orders/{orderId}/whatsapp-groups` | read | `{school:{url, source}, internalApproval:{url, source}, vendors:[…]}` |
| `PUT /item-orders/{orderId}/whatsapp-groups/school` | current-step assignee, or superadmin | `{inviteUrl}` (item-level link) |
| `POST /item-orders/{orderId}/notes` | act (any in scope) | `{body, stepOrder?}` |
| `GET` and `POST /item-orders/{orderId}/calls` | read / act (any in scope) | `{contacted, outcome, contactLabel?, vendorId?, designerId?, stepOrder?, notes?, nextFollowUpAt?}` |
| `GET /item-orders/{orderId}/activity` | read | `before`, `limit` → merged timeline |
| `GET /designers`; `POST /designers`; `PUT /designers/{id}` | read / superadmin | Moved from `/notebook-orders/designers` |
| `GET /time-slots/{schoolId}?definitionId=`; `PUT` and `DELETE /time-slots/{schoolId}/{definitionId}/{step}` | read / superadmin | Per-school SLA overrides |
| `GET /vendor-categories`; `PUT /vendor-categories/{code}` | read / superadmin | `{name, active, sortOrder}` |
| `GET /vendors?category&q&includeInactive`; `POST /vendors`; `PUT /vendors/{id}` | read / superadmin | Vendor fields of D31 |
| `GET` and `PUT /settings/internal-approval-group` | read / superadmin | `{inviteUrl}` |
| `GET` and `PUT /school-whatsapp-groups/{schoolId}[/{target}]` | unchanged | Writes accept only `SCHOOL_GROUP` and `INTERNAL_APPROVAL` |
| Machine: `POST /api/v1/internal/order-workflows` (alias `/internal/notebook-trackers` for one release) | school-core service account | Today's create body plus `definitionId, categoryCode, itemCode, itemNoun, orderNumber, importedProgress[]`. Idempotent, and fills a missing `order_number` on an existing tracker |
| Machine: `POST /api/v1/internal/order-workflows/{orderId}/design-file-access` (alias `…/image-access`) | school-core service account | `{allowed, reason}` for any workflow order |

- **Removed:** `/notebook-orders/**`, including `design/send`, `design/approve`, `design/request-changes`, `vendor` (the single legacy vendor), `stage-note`, `internal-notes` and `follow-ups`.
- **New error codes.**
  - 409: `GATE_UNMET` (with `unmet[]`), `STEP_NOT_EDITABLE`, `HEAD_APPROVAL_STALE`.
  - 400: `INVALID_STEP_DATA` and `NOTE_TOO_LONG` (both unchanged), `INVALID_INVITE`.
  - 403: `NOT_ASSIGNEE`.

**school-core**

| Method and path | Who | Change |
| --- | --- | --- |
| `GET /api/v1/catalog/orders/{id}/tracker-summary` | superadmin, operators in scope | Now covers every workflow category. Adds `orderNumber, category, itemCode, itemLabel, schoolCity, quantities{total, lines[]}`, and `titleCards{rows[], unmatched}` for NB |
| `GET /api/v1/catalog/orders/page?q=` | existing | New server-side search (order number, id, school name) |
| `GET /api/v1/catalog/orders/by-number/{orderNumber}` | `order:read` | → `{id}`, for pasted numbers |
| `POST /api/v1/catalog/orders/{id}/assets` (kind `DESIGN_DRAFT`), and its delete | existing | D26/D27 limits; any workflow order; still asks operations' design-file access first |
| `PUT /api/v1/catalog/orders/{id}/quote` | superadmin | Allowed on tracker-managed non-notebook orders until COMPLETED (D5) |
| `GET …/production-workflow` | existing | Read-only. `PUT` and `POST` → 409 `WORKFLOW_MOVED` |
| `POST /api/v1/schools`, `PATCH /api/v1/schools/{id}` | superadmin | Adds `orderCode`: optional on create (derived when blank), editable on patch, validated and unique |
| Product category admin resource | superadmin (existing catalog builder permissions) | Adds `orderCode, itemNoun, workflowDefinition` |
| Machine `PUT /api/v1/internal/catalog-orders/{id}/tracker-status` | operations service account | Accepts any code matching the D5 pattern, plus `definitionId, stepOrder, stepTitle, totalSteps, completedSteps` |

### 3.6 Events and service-to-service calls

- **`catalog-order.upserted.v1`**: adds `orderNumber` and `itemCode` (additive) in both emitters (`CatalogReadRepository.java:708-726`, `CatalogOrderFormService.java:847-860`).
- **platform-service reporting V36 `__catalog_order_number.sql`**:
  - adds `reporting.fact_catalog_order.order_number`;
  - `CatalogFactProjector` and `CatalogFactReadRepository` carry it;
  - approvals show `invoiceNo = orderNumber ?? id` (`ReportingApprovalRepository.java:77-80`).
- **Tracker status push** (operations outbox, then school-core over HTTP; the mechanism is unchanged) gains the step fields of §3.5. No new Pub/Sub topics. Operations still emits no domain events.
- **Creation** stays a queue in school-core plus an idempotent machine call. The queue's claim now reads:
  - the category's `workflow_definition`, `order_code` and `item_noun`;
  - the order's `order_number`;
  - the summed line quantities;
  - for imports, the V36 rows (`stage_code, completed_at, updated_by, data.details, stage_note`).
- **Operator resolution.** Identity's operator resolver is called with the order's category instead of the `NOTEBOOKS` constant.
- **Workbook parser.** `NotebookOrderWorkbookParser.java:70-71` accepts either the CK id or the order number.

---

## 4. Screens

### 4.0 Design basis

- **Layout and behaviour** come from the prototype. **Tokens and components** come from the house design system:
  - `--ck-color-primary` (green) marks actions and the selected item;
  - success, warning and danger tokens carry status, never colour alone;
  - DM Sans for text and Fraunces for headings;
  - the 12 px type floor;
  - only the documented breakpoints: 640, 768, 1024 and 1280.
- The prototype's blue is not copied. `--ck-color-accent` stays status-only, as the house rules say.
- **Reused:**
  - `ModuleShell`, `Field`, `Stat`, `PanelMessage` (`pages/workspace/ui.tsx`);
  - `Modal`, `Paginator`;
  - `.ck-card`, `.ck-btn*`, `.ck-alert-am` and `.ck-alert-re`, `.ck-pill`, `.ck-table` and `.ck-table-wrap`;
  - `.ck-pipeline` and `.ck-pipe-*` (the board), `.ck-command-toast` (toasts), `CategoryIcon`, lucide icons;
  - the WAI-ARIA tab pattern from `NotebookAdminPanel.tsx:227-266`.
- **New CSS** goes in `features/orders/workflow/workflow.css` under the `.ck-wf-*` prefix, using tokens only.
- **Removed:** `tracker.css` and `.ck-product-workflow*`. The workflow stepper becomes the app's canonical stepper; firefighting's `.ck-step-bar` is left alone (out of scope).

### 4.1 Shell and navigation

- **Panels.** The panel `nb-work` is retitled **"Order workflows"** (lucide `Workflow` icon) and shown to superadmin and to operators, both school-wide and item-only. `sa-nb-admin` is retitled **"Workflow set-up"** (`Settings2`). The keys are unchanged, so bookmarks keep working. This also replaces the emoji fallback icons.
- **Deep links.** `?panel=nb-work&order=<CK id>` opens an order. Opening one pushes a history entry, so the browser's Back closes it. The breadcrumb reads "Order workflows › NB-DPS_GUR-26-11-13-0042".
- **Prototype header elements.** The prototype's global header is not rebuilt; the workspace top bar already shows the user.
  - Its search box becomes the list's search.
  - Its WORKFLOW selector becomes the list's Item filter.
- **Other entry points.** "All orders → View" on a tracker-managed order opens the same detail screen (`OrderDetailEntry`, generalised to every workflow category, including the "still being set up / Start tracker" state).

### 4.2 Order list and board (new; never shown in the video)

- **Header.** `ModuleShell` titled "Order workflows", subtitle "Placed orders moving through production".
- **Summary chips** (`Stat`, clickable; each applies its filter): Open · Overdue · Due today · Unassigned · Mine (operators).
- **Toolbar.** One row on wide screens; it wraps at narrower ones.
  - Search, placeholder "Search order number, school or old order ID".
  - **Item** chips: All · NB · FE · FX · BBK · CF · FB · RC · BCH · More ▾. They are multi-select and labelled by name on hover.
  - **Stage** select: lists phases, or the template's steps when exactly one item is selected.
  - **Assignee**: Anyone / Me / Unassigned / named operator (superadmin only).
  - **School**: superadmin and multi-school operators.
  - **SLA**: Any / Overdue / Due soon (within 4 h) / On track / No timer.
  - "Include completed" checkbox.
  - View toggle **List | Board**.
- Filters live in the URL query.
- **List.** A `.ck-table` inside `.ck-table-wrap`. Sorted by Due by default (overdue first), or by Placed (newest) or Order number. 50 rows per page with `Paginator`. Clicking a row opens the order.

  | Column | Content |
  | --- | --- |
  | Order | Number in bold, "Ref CK-1015" in small type beneath |
  | Item | Category icon + code |
  | School | Name · city |
  | Qty | Total quantity |
  | Stage | "5 · Production Planning", with a thin progress bar "4 of 12" |
  | Due | "Overdue 2d 5h" (danger), "Due in 3h" (warning), a date, or "—" |
  | Point of contact | Name, or an "Unassigned" pill |
  | — | "Open" |

- **Board.** `.ck-pipeline` with five phase columns: Intake & design · Planning · Production · Verification · Delivery. Column headers show counts.
  - Each `.ck-pipe-card` shows the number, school, item chip, stage name, a due chip and the assignee's initials.
  - At most 50 cards per column, then "Show more".
  - The board uses the same filters as the list.
- **Empty states.** "No orders are in production." / "No orders match these filters. Clear filters".
- **Load errors.** `LoadFailure`, with Retry.
- **Below 768 px.** Rows become stacked cards, and the filters collapse into a "Filters (n)" button that opens a `Modal` sheet.

### 4.3 Order workflow detail (mapped to the prototype's §3)

**Top bar.** "← Order workflows", the breadcrumb, and Refresh.

**Summary row** [Proto §3.1]:
- **Identity card.**
  - An item icon tile, "ORDER NUMBER", the number, and "Ref CK-…".
  - A status badge: In progress, Overdue (current stage breached) or Completed.
  - The school and city.
  - "Point of contact: <name>". Superadmin gets an "Assign…" menu; eligible operators get "Take this order" when unassigned.
- **KPI tiles.**
  - NB: "Total notebooks / All notebook types", "King size", "Long size", "Jumbo King", "Jumbo Long", "FA notebooks", plus "Other sizes" when greater than 0.
  - Other items: "Total <items>" and up to four line tiles.
- **Estimated completion tile.** It shows Step 10's (NB) or Step 7's revised completion date, else Step 5's target completion date, captioned "Target completion date". Without either, it shows the required-by date, captioned "Required by (from order)".
- **Progress ring.** "<p>% · <done> of <total> steps · Workflow progress".

**Horizontal stepper** [Proto §3.2]:
- A full-width card with numbered circles, connectors and labels.
- **States.**
  - Completed: success-filled with a check, and a success connector after it.
  - Current: primary ring.
  - Viewing: primary fill and a bold label (a completed stage being viewed keeps its check, in white).
  - Locked: neutral number.
- It is an `<ol>`. The current step carries `aria-current="step"`. Each item is a button that opens the stage.
- **Narrower screens.** Below 1024 px only the current and viewed labels show. Below 768 px it becomes "Step 5 of 12 · Production Planning", with ‹ › buttons.

**Body.** Three columns at 1280 px and wider (260 px | fluid | 300 px). Between 1024 and 1279 px it is two columns, with the sidebar wrapping below. Below 1024 px it is a single column, and the stage list becomes a "Stage" select.
- **Left: "ORDER WORKFLOW <n>".**
  - One row per stage: a badge (number, check, or lock for locked stages), the bold title, and a status line. Status lines read "Completed · 13 Nov, 12:19 am", "In progress · due 16 Nov, 4:00 pm", "Overdue · was due 26 Oct, 4:00 am", or "Pending".
  - The viewed row has a primary-soft background and primary text.
- **Centre: the stage panel**, below.
- **Right: the sidebar** (§4.4).

**Stage panel anatomy** [Proto §3.4]:
1. **Header.**
   - The stage icon tile, "Step N: <Title>", and the description.
   - At the right, "◷ SLA · N hours · 24/7" plus the timer:
     - **Completed**: success "✓ Completed".
     - **Running**: warning "● 0h 0m 2s elapsed · 3h 59m 57s remaining".
     - **Breached**: danger "⚠ SLA BREACHED · overdue by 2d 5h 51m 26s".
     - **Pending**: "○ Pending · timer starts when previous stage is completed".
     - **No SLA**: "No SLA for this stage".
   - The timer ticks every second in the browser, from the server's `startedAt` and `dueAt`, corrected for clock skew with the server `now` in the view. Its accessible label updates once a minute.
2. **Tabs.** Work area | School & vendor | Checklist | Activity history. Step 1 has no School & vendor tab [Proto].
3. **"Stage workspace" card.** A status badge and the description. On the active stage, add "What's left to complete", listing the `unmet` items.
4. **Stage cards**, from the template blocks (table below).
5. **"Stage notes & next action".**
   - Textarea, placeholder "Log the discussion, blocker, owner and next follow-up...".
   - An "n/100 words" counter and an outline button "Save activity note".
   - This stage's notes, newest first, beneath.
6. **Footer.**
   - **"← Previous stage"** moves the view only; it changes nothing.
   - **Primary button.**
     - "Complete stage & continue →" is enabled only when `can.complete` is true and `unmet` is empty. Completing saves any dirty fields first, then moves the view to the next stage and shows the toast "<Stage> completed".
     - When disabled, a reason line lists what is missing.
     - A completed stage shows "✓ Stage completed".
   - **Superadmin overflow menu** "⋯": "Reopen stage…", which asks for a reason in a `Modal`.
   - **Locked stages** carry the banner "Opens for editing when <previous stage> is completed." and every input is read-only.

**Stage cards (block types)**

| Block | Prototype card | Behaviour |
| --- | --- | --- |
| `ORDER_PDF_LINK` | "School order PDF link" | URL input; "Save PDF link" |
| `INTRO_MESSAGE` | "School Group Chat & Introduction Message" | School chip and order number; "Your name"; resizable "Message template" with `{Name}` substituted as you type; "Copy message" (clipboard, falling back to select-all); "Open <School> Group Chat ↗"; helper text; the [Ours] intro checkbox |
| `SCHOOL_GROUP` | "School Group Chat" | Chip; "Open <School> Group Chat ↗"; "Opens the WhatsApp group mapped to this order's school in a new tab." If no link is saved: an inline "Save group link" field for the assignee, a set-up pointer for superadmin |
| `DESIGN_FILES` | "Upload Design Files" | Dashed drop zone: "Select files to upload" / "Up to 4 files · 25 MB in total · PDF, CDR, PNG, JPEG". "Choose files" (multiple); "Upload selected files"; "Refresh file list". A usage meter "2 of 4 files · 18.4 of 25 MB". The file list shows name, size, uploader and time, with Preview/Download and Remove. Limits are checked in the browser before upload, and server errors are shown verbatim |
| `FIELDS` | e.g. CDR details, QC evidence, documents, dispatch | Template labels and placeholders (verbatim [Proto]); a card-level save button using the template's label; word counters on `WORDS` fields; URL fields show an "Open ↗" link once saved |
| `CDR_FOR_APPROVAL` | "Design version for approval" / "CDR file submitted for approval" | The Step 3 link, with when and by whom it was saved; or the amber missing-link warning |
| `HEAD_APPROVAL` | Approval comments + "✓ Approve design" / "Request changes" | Superadmin only. "Request changes" confirms "This reopens Designing Activity". Operators see "Only a superadmin approves the design", and the decision record once one exists |
| `ORDER_FOLDER` | "School order folder" | Badge "From Order Details": the link, or the amber warning |
| `TITLE_CARD_QUANTITIES` | "Notebook title card quantities" | Badge "Calculated from the school's order" (replaces "Auto-calculated from Excel"); table TYPE / SIZE / NOTEBOOKS IN ORDER / CARDS / SHEETS NEEDED; the prototype's formula helper and footnote; an "Not on a title card: n" note |
| `SAVED_TITLE_CARDS` | "Saved notebook title card quantities" | TYPE / SIZE / QUANTITY |
| `ITEM_QUANTITIES` | (flier quantities; not seen in the video) | Line / Details / Quantity, with a total |
| `VENDOR_DIRECTORY` | "Supplier Contact Groups" | One sub-card per category. Each row: initials circle, bold name, contact person · phone (`tel:`), type label, "Open group chat ↗" (disabled, with the reason, when the vendor has no link) |
| `VENDOR_SELECTION` | "Select Vendors for This Order" | Pill "Selections control later stages"; the prototype helper; checkboxes per category (suggested categories first and open); "Save vendor selections"; the amber banner naming the stages that use selections |
| `VENDOR_GROUPS` | Board/Offset/Lamination/Binder group links, "Vendor Contact Groups" | Selected vendors only; the amber warning when none are selected |
| `INTERNAL_APPROVAL_GROUP` | "Internal Approval Group Link" | "IA" tile, "Internal Approval Group", "Internal review and approval", "Open group chat ↗" |
| `CHECKLIST` | Every checklist | Badge "N/M complete" (warning until complete, then success). Checklists of 4 or more items get a progress bar and "X% complete". Each tick autosaves and shows a toast such as "Printing checklist updated". An item set by sign-off is read-only, with "Ticked by reviewer sign-off" |
| `SIGN_OFF` | Reviewer / consignment / internal approval sign-off | Amber banner from the template; the record "Signed off by <name> · <time>"; the superadmin button; operators see "A superadmin records this sign-off" |
| `SEND_BACK` | — | Only when verification is "Not verified": superadmin's "Send back to Designing Activity" |
| `LEGACY_DETAILS` | — | Collapsed "Earlier details recorded on this stage", read-only |

**The other tabs**
- **School & vendor** (from Step 2 on).
  - The "Contacts & follow-ups" card (D36): School contact, Designer, Vendor / factory contact, "☎ Call", "Save contacts".
  - The "Log a call or discussion" card (§4.6).
  - Call history.
- **Checklist.** Every checklist of the order, grouped by stage, with N/M and the items, read-only. Each group has "Go to stage". This fills the prototype's gap.
- **Activity history.**
  - "Order activity history": a timeline of actions, notes, calls, file events and legacy follow-ups. Each entry shows actor, time and stage. It can be filtered to All stages or This stage.
  - "Design revision history": Version (v1… in upload order, removed files included) | Filename | Uploaded | Status (Current / Removed).

### 4.4 Sidebar widgets [Proto §3.7], the same on every stage

1. **Current Design Preview.**
   - Shows the newest current design file. Images get a raster preview; PDF and CDR files get a file icon. Then the filename and "View file", which opens the file in a new tab; CDR files download instead.
   - The "↗" icon opens a `Modal` preview.
   - With no files: "No design files yet."
2. **School Feedback.**
   - "+ Add" opens the School & vendor tab with the call form preset to School / Changes Requested.
   - The contact's initials avatar, name, and "School contact".
   - "Logged feedback" boxes: the school's order-form notes ("From the order form"), then the latest two school "Changes Requested" call notes.
3. **Internal Notes.**
   - Textarea, placeholder "Add notes, discussions, next actions...", an n/100 counter, and "Save note".
   - The latest three notes, then "View all in Activity history".
4. **Upcoming Actions.** Pill "Live list"; the derived rows of D39, with done items ticked and the due state coloured.

The prototype's Assignment widget is not added; assignment lives on the identity card.

### 4.5 Vendor directory (Workflow set-up → Vendors; superadmin)

- **Left: categories.** The 18 categories, each with a vendor count and an "Inactive" tag where it applies. A row menu offers Rename and Deactivate/Activate.
- **Right: vendors.** A search box, "Add vendor", and a table: Name | Categories (chips) | Contact person | Phone | WhatsApp group ("Open ↗" or "—") | City | Status (Active / Inactive / Sample) | Edit.
- **Add/edit form** (`Modal`):
  - Fields: Name*, Categories* (multi-select), Contact person, Phone*, Alternate phone, Email, City, WhatsApp group invite, Notes, Active.
  - Validation messages appear inline. An invalid invite reads "Paste a WhatsApp group invite link (https://chat.whatsapp.com/…)".
- **Deactivating** asks for confirmation and explains that orders which already chose the vendor keep it, marked "Inactive".
- **Empty category.** "No vendors in Flex Printers yet." with "Add vendor".
- Operators never see this tab. They see vendors inside the Step 5 and later stage cards.

### 4.6 Call log (component in the School & vendor tab)

- **"Log a call or discussion"**, full-width fields [Proto]:
  - **Contacted**: the 5 entities.
  - **Who**: options built from the entity (school contact; designer; selected printers for Printing Press; selected binders and makers plus the factory contact for Manufacturing Factory), plus "Someone else", which shows a text field.
  - **Outcome**: the 7 outcomes.
  - **Call notes**: placeholder "Summary, commitment, next follow-up date...", with an n/100 counter.
  - **Next follow-up** (date and time): shown for Callback Requested, where it is required.
  - Button **"Log call"**. The prototype's label was unreadable; this is our wording.
- **Call history.** Newest first, 10 entries, then "Show all", which opens Activity history.
- **Each entry** shows an outcome chip, "<Entity> · <who>", the stage, time and author, and the notes. Chip tones:
  - success: Connected, Approval Received;
  - neutral: No Answer, Busy;
  - warning: Callback Requested, Changes Requested;
  - danger: Delay Reported.
- **Saving** shows the toast "Call logged".

### 4.7 Workflow set-up panel (superadmin)

Tabs, using the existing WAI-ARIA tab pattern: **Vendors** (§4.5) | **Designers** (existing) | **Time slots** | **Groups** | **Operators** (existing).
- **Time slots.** A template select, then a table of steps with the default SLA (value and unit, editable) and the per-school overrides (the existing screen, with "Use default").
- **Groups.** The global internal approval group link, plus the existing per-school School group and Internal approval links.

### 4.8 Codes, numbers and other screens

- **School order code.**
  - Onboarding (`SaSchoolsPanel`) gains an "Order code" field. It is prefilled with the derived suggestion and can be edited, or left blank to derive.
  - School edit (`SchoolManagementPage`, superadmin) shows it as an editable field, with the helper "Used in order numbers like NB-DPS_GUR-26-11-13-0001. Changing it affects only new orders."
- **Catalog manager → Products.** Adds the columns Order code, Item noun and Workflow (a select of the five templates, or None).
- **Order numbers elsewhere.**
  - All orders, School orders, Order approvals and the order detail headline show the order number, with "Ref CK-…" beneath.
  - Search in All orders becomes server-side (`q`).
  - The billing invoice description uses the order number. `orderRef` stays the CK id.
- **Product order detail.**
  - The free-text "Production workflow" section is removed.
  - Staff see "Open order workflow →" for tracker-managed orders.
  - Schools see the status, as before.

### 4.9 Responsive layout and accessibility

- **Widths checked:** 375, 768, 1024 and 1280 px. There is no horizontal page scroll; tables scroll inside `.ck-table-wrap`.
- **Keyboard.** All controls are reachable by keyboard. The stepper, stage list and tabs have visible focus rings. Tabs use arrow keys.
- **Status is never colour alone.** It always comes with an icon and a word.
- **Live regions.** Toasts use `role="status"`. Timers are not live regions.
- **Forms.** `Field` wires each control's `aria-describedby` to its error and its counter.
- **Type.** Nothing renders below 12 px, so new CSS does not reuse the 11 px `.ck-status` or the 10 px `th`.

---

## 5. Implementation plan

### 5.1 Work packages

Each work package owns its files exclusively; nobody edits another package's files. The seams are the interfaces in §3.4 and the contracts from WP0. All packages can start on day 1 against those seams.

**WP0: Contracts, generated clients, shared frontend helpers.** Engineer E1; first, about half a day; it is regenerated again at integration.
- **Owns:**
  - `contracts/openapi/orderWorkflowClient.v1.openapi.json` (new); `vendorDirectoryClient.v1.openapi.json` (new); `catalogClient.v1.openapi.json` (edit); `notebookTrackerClient.v1.openapi.json` (delete at integration); `contracts/openapi/README.md`;
  - `frontend/src/generated/**` (regenerated);
  - `services/api-gateway/api-route-inventory.json` (regenerated);
  - `frontend/src/features/orders/shared/**`: `orderLabel.ts`, `words.ts`, `invite.ts`, `phases.ts`, `slaFormat.ts`.
- **Acceptance.**
  - Every endpoint in §3.5 is described, with schemas named after the Java records.
  - `node scripts/generate-api-route-inventory.js` and `node scripts/generate-openapi-typescript-client.js --check` pass on the integration branch.
  - The gateway contract tests (`api-contract.test.js`, `server.test.js`) pass.
- **Tests.** Generator check. `words.test.ts`, with server-parity cases (multiple spaces, newlines, empty). `orderLabel.test.ts` (number, else Ref). `slaFormat.test.ts` ("2d 5h 51m 26s", "0h 0m 2s").

**WP1: Operations migrations, template JSON, migration and content tests.** Engineer E1, after WP0; about 1 day.
- **Owns:**
  - `services/operations-service/src/main/resources/db/migration/workflow/V17…V20`;
  - `src/main/resources/workflow-templates/*.json` (5 files);
  - `src/test/.../persistence/OrderWorkflowMigrationTest.java`;
  - `src/test/.../application/orderworkflow/TemplateContentTest.java`.
- **Acceptance.** Starting from a V16 database seeded with trackers (customised and wholesale; active at Steps 1, 4, 7 and 10; finished; holding stage notes, vendor selections and vendor-key links), migrating to V20 gives:
  - no tracker lost;
  - selections remapped to numeric ids;
  - each stage note copied once;
  - `PENDING` remapped;
  - definitions and steps that equal the JSON templates;
  - RLS, FORCE and grants on the new tenant tables;
  - call logs that cannot be updated or deleted by `app_rt`.
- **Tests.**
  - The Testcontainers migration test above.
  - `TemplateContentTest` asserts:
    - the verbatim checklist strings and counts (NB 1/3/3/8/8/1/8; FE 1, FX 3, BBK 3, General 3; follow-up 4);
    - every [Ours] item is marked `source: ours`;
    - the SLA defaults;
    - the intro texts.

**WP2a: Operations engine domain.** Engineer E2; about 1 day.
- **Owns** new packages only:
  - `application/orderworkflow/template/**`: the records, `WorkflowTemplates` and the JSON loader with consistency checks;
  - `application/orderworkflow/rules/**`: `StepDataRules` (from `StepData`, adding `URL`, `WORDS`, `legacy` and the sign-off-only item), `Gates` (from `CompletionGates`, returning every failure), `WorkflowProgress` (from `TrackerProgress`), `Sla`, `NoteRules` (with max words), `Counts`, `StepStatus`, `Role`, `WorkflowRuleViolation`, and `UpcomingActions` (pure);
  - `application/orderworkflow/{OrderAccess, AccessibleOrder, VendorLookup, GroupLinkResolver, CallbackSource}.java`.
- **Acceptance.** Unit tests prove:
  - the paths (NB customised 12, wholesale 9, 9-step);
  - merge semantics: partial save; legacy fields kept; unknown field → 400; non-https URL → 400; word limits; the sign-off item cannot be set by data;
  - every gate type, including the bulk-printing case (a LOCKED step is not editable);
  - sign-off is voided by a change;
  - upcoming-action ordering;
  - the loader rejects a template whose gates reference undeclared fields.
- **Tests.** `WorkflowProgressTest`, `StepDataRulesTest`, `GatesTest`, `TemplateLoaderTest`, `UpcomingActionsTest`, `NoteRulesTest`, all ported from the existing `application/tracker` tests.

**WP2b: Operations service and API.** Engineer E3; about 2 days.
- **Owns:**
  - `application/OrderWorkflowService.java` (from `NotebookTrackerService.java`, which is deleted);
  - `persistence/OrderWorkflowRepository.java` (from `NotebookTrackerRepository.java`), `persistence/TrackerStatusOutbox.java`, `persistence/WorkflowReadRepository.java` (hides `ITEM_ORDER` too);
  - `infrastructure/SchoolCoreCatalogClient.java`, `infrastructure/OperatorDirectoryClient.java`;
  - `api/OrderWorkflowController.java` (from `NotebookTrackerController.java`), `api/internal/OrderWorkflowMachineController.java`, `api/SchoolWhatsAppGroupController.java`;
  - operations' copy of `security/MachineCallerFilter.java`, and `security/TenantContext.java` (per-category scope helper);
  - deleting `application/tracker/**`;
  - its tests: renamed and updated `NotebookTracker*` tests, and `NotebookTrackerMigrationTest`.
- **Acceptance.** The §3.5 endpoints other than vendors, calls, activity and settings behave per D18–D23, D33 and D36–D38:
  - create is idempotent, fills a missing order number, and imports V36 progress;
  - list filters, search, paging and counts respect operator item scope;
  - the status push carries the step fields;
  - the machine aliases work;
  - `GATE_UNMET` lists every requirement.
- **Tests.** Integration tests (Testcontainers):
  - create NB customised and wholesale, FE, and an import;
  - every NB and FE gate through the API;
  - Step 8 data save refused while Step 7 is active;
  - request changes reopens Step 2 and later steps lock;
  - approval goes stale when the CDR link changes;
  - an operator who is not the assignee gets 403; an item-scoped FE operator cannot see NB; a school admin gets 403;
  - claiming an unassigned order;
  - RLS scoping with two schools;
  - controller tests for status codes.

**WP3: Operations vendors, call log, activity, settings, group links.** Engineer E2, after WP2a; about 1 day.
- **Owns** new files only:
  - vendors: `application/VendorDirectoryService.java`, `persistence/VendorRepository.java` (implements `VendorLookup`), `api/VendorDirectoryController.java`;
  - calls: `application/CallLogService.java` (implements `CallbackSource`), `persistence/CallLogRepository.java`, `api/OrderCallLogController.java`;
  - activity: `application/ActivityService.java`, `persistence/ActivityRepository.java`, `api/OrderActivityController.java`;
  - settings and groups: `application/WorkflowSettingsService.java`, `application/GroupLinkService.java` (implements `GroupLinkResolver`), `persistence/WorkflowSettingsRepository.java`, `api/WorkflowSettingsController.java`;
  - tests.
- **Acceptance.**
  - Vendor and category CRUD is superadmin only, and operators read active vendors.
  - Invites are validated.
  - Call-log rules hold: required notes for 3 outcomes, follow-up required for Callback Requested, the 100-word limit, append-only, and any in-scope actor may log.
  - The activity timeline merges and groups correctly and pages by `before`.
  - Group resolution order is item → school → none, and global → per-school for the internal group.
- **Tests.** Service and integration tests for each rule, plus RLS on call logs and item group links.

**WP4: school-core and platform reporting.** Engineer E4; about 2 days.
- **Owns:**
  - school-core migrations `tenant_school/V31`, `catalog/V37`, `catalog/V38`;
  - school-core code: `persistence/CatalogOrderFormService.java`, `persistence/CatalogReadRepository.java`, `persistence/NotebookTrackerCreationQueue.java`, `persistence/ProductOrderWorkflowService.java`, `api/ProductOrderWorkflowController.java`, `persistence/SchoolStructureReadRepository.java`, `persistence/ProductCatalogRepository.java`, `infrastructure/CatalogOrderAssetStorage.java`, `infrastructure/OperationsTrackerClient.java`, `catalog/domain/DesignDraftRules.java`, `api/internal/CatalogTrackerMachineController.java`, `api/CatalogOrderFormController.java`, `photoimport/NotebookOrderWorkbookParser.java`;
  - new school-core files: `catalog/domain/OrderNumbers.java`, `catalog/domain/SchoolOrderCode.java`, `catalog/domain/TitleCardQuantities.java`;
  - platform-service: `reporting/V36__catalog_order_number.sql`, `CatalogFactProjector.java`, `CatalogFactReadRepository.java`, `ReportingApprovalRepository.java`;
  - tests.
- **Acceptance.**
  - Order numbers are minted at placement on both paths, with the date in the school's time zone and the right counter. They are immutable, and backfilled.
  - School codes are derived, validated, unique and editable. Category codes, nouns and templates are admin-editable.
  - Placing FE, FX, BBK, CF, FB, RC, BCH, BLT or STD orders starts a workflow, and V36 orders are imported with their progress.
  - The summary covers every item, with NB title cards.
  - Design-file limits and CDR handling hold.
  - Quotes are allowed on tracker-managed non-notebook orders.
  - Statuses are accepted by pattern, and `workflowStep` is stored.
  - Product-workflow writes return 409.
  - Events carry `orderNumber`, and reporting stores it.
  - `/orders/page?q=` searches.
- **Tests.**
  - `OrderNumbersTest`: format; 00:30 IST is still today; widening past 9999.
  - A placement concurrency test: 20 parallel placements give distinct numbers.
  - `SchoolOrderCodeTest`: derivation, stop words, collisions.
  - V31/V37/V38 migration tests (Testcontainers).
  - `CatalogOrderAssetStorageTest`: CDR RIFF and ZIP accepted; a renamed `.zip` or a fake `.cdr` rejected; WEBP drafts rejected.
  - Design limit tests: the fifth file → 409; over 25 MB in total → 409; two concurrent uploads at 3 files → one wins.
  - `TitleCardQuantitiesTest`: 1,088 / 513 / 775 / 0, and the unmatched bucket.
  - Creation-queue tests for FE, imports and number sync.
  - A platform projector test.

**WP5: Frontend order workflow screens.** Engineer E5; about 2 days.
- **Owns:**
  - `frontend/src/features/orders/workflow/**`: `OrderWorkflowPage`, `SummaryRow`, `Stepper`, `StageList`, `StagePanel`, `SlaTimer`, `blocks/*`, `Sidebar`, `ChecklistTab`, `ActivityTab`, `OrderDetailEntry` (moved), `api.ts`, `types.ts`, `workflow.css`, `index.ts`;
  - `frontend/src/features/orders/list/**`;
  - `frontend/src/features/tracker/**` (deleted);
  - `pages/workspace/panels/NotebookWorklistPanel.tsx` (it renders the new list) and `pages/workspace/panels/TrackerStep.tsx`;
  - `pages/workspace/config.ts`, `pages/workspace/useWorkspaceNavigation.ts`, `pages/workspace/SupplyOrdersPanels.tsx`, `shared/display/icons.tsx`;
  - tests.
- **Acceptance.**
  - §4.1–4.4 are met, rendered entirely from the template spec. No checklist, field or sign-off list is hard-coded.
  - No artefact from Appendix B is copied.
  - Deep links and Back work.
  - Works at the four widths.
  - Uses tokens only and respects the 12 px floor.
- **Tests** (Vitest and RTL):
  - list filters round-trip through the URL query; board phase columns;
  - the detail page renders the NB and FE fixtures;
  - a locked stage is read-only; Complete is disabled with reasons;
  - every timer state, with fake timers;
  - checklist autosave sends a partial save and shows the toast;
  - Approve is disabled without a CDR link;
  - intro `{Name}` substitution and copy;
  - upload pre-checks (5th file, over 25 MB in total, `.webp`);
  - role-based queries for tabs and stepper semantics.

**WP6: Frontend vendor directory, call log, set-up, order numbers across screens.** Engineer E6; about 1.5 days.
- **Owns:**
  - `frontend/src/features/orders/vendors/**` and `frontend/src/features/orders/calls/**` (exports `CallLogPanel`, below);
  - `pages/workspace/panels/NotebookAdminPanel.tsx`, `pages/workspace/panels/SaAllOrdersPanel.tsx`, `AdminOrdersPanel.tsx`, `SaOrderApprovalsPanel.tsx`, `SaSchoolsPanel.tsx`;
  - `pages/SchoolManagementPage.tsx`;
  - `features/catalog/ProductOrderDetail.tsx`, `features/catalog/ProductOrderWorkflow.tsx` (deleted), `features/catalog/ProductCatalogManager.tsx`, `features/catalog/api.ts`, `features/catalog/types.ts`, `features/catalog/product-form.css` (drop `.ck-product-workflow*`);
  - tests.
- **Acceptance.**
  - §4.5–4.8 are met.
  - The order number shows everywhere, and the CK id stays searchable.
  - The invoice description uses the number.
  - The set-up tabs and the Time slots template selector work.
- **Tests.** Vendor form validation; superadmin-only rendering; call-form required rules and word counter; school order code field validation; the product detail link replaces the workflow section; All orders search sends `q`.

**Staffing for six engineers:** E1 WP0 → WP1 · E2 WP2a → WP3 · E3 WP2b · E4 WP4 · E5 WP5 · E6 WP6.

### 5.2 Seams fixed before work starts

- The Java interfaces in §3.4. WP2a commits them first, within the first hours; WP2b and WP3 code against them.
- The OpenAPI contracts from WP0. The frontend uses fixtures generated from them until the backend lands.
- The props of `CallLogPanel`, which WP6 builds and WP5 mounts:

  ```ts
  interface CallLogPanelProps {
    orderId: string;
    currentStep: number;
    contacts: { school?: Contact; designer?: Contact; factory?: Contact };
    vendors: VendorSummary[];
    preset?: { contacted: Contacted; outcome: Outcome };
    canLog: boolean;
    onLogged(): void;
  }
  ```

- WP5's `features/orders/workflow/index.ts` exports `OrderDetailEntry`; WP6 imports it from there.

### 5.3 Integration order

1. Cut `feat/item-order-workflows-sop` from dev. Merge WP0 (the agreed contracts).
2. Merge WP1 and WP4's migrations first, as their own commits within those packages. They touch independent schemas.
3. Merge WP2a, then WP2b and WP3; then WP4's application code.
4. WP0 regenerates the route inventory and clients against the merged controllers and fixes any contract drift.
5. Merge WP5, then WP6, which imports `OrderDetailEntry` and implements `CallLogPanel`.
6. Run `CI / PR` through `workflow_dispatch` on the integration branch. Run Maven with `clean`: the IDE builder can leave stale classes. Fix what fails.
7. Open one PR from the integration branch into dev. CI runs on it. Merge, and dev deploys.
8. Run the dev acceptance pass (§5.4). The dev database has been always on since mid-October 2026; if dev logins fail, check that the instance is running before anything else.

### 5.4 Dev acceptance pass

- An existing CK notebook tracker opens. It shows the right step, saved data, "Earlier details", remapped vendors and migrated notes. It now has an order number.
- A new customised NB order:
  - gets `NB-<code>-<date>-NNNN` and a workflow;
  - walk all 12 steps, hitting each gate;
  - the title-card table shows the formula's values;
  - Step 8 cannot be ticked before Step 7 completes;
  - Request changes returns the order to Step 2.
- A wholesale NB order shows 9 steps.
- A new FE order and a new CF order get the FE and General templates and walk all 9 steps.
- An in-flight product order with V36 progress resumes at the same stage.
- Design files: a 5th file is refused; a `.cdr` uploads and downloads; over 25 MB in total is refused.
- Vendors: superadmin adds a vendor with an invite; the Step 5 assignee selects it; later steps show it.
- Calls: log Callback Requested with a follow-up; it appears in Upcoming actions and Activity.
- An item-scoped FE operator sees only FE orders at their schools. A school admin sees status only.

### 5.5 Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| A V36 stage mapping is wrong for an odd order | Imports are logged as `STEP_IMPORTED` with the source stage, and superadmin can reopen. The test covers every V36 code |
| Counter-row contention during bursts | A single-row upsert is fast. The concurrency test proves numbers stay distinct |
| CDR detection rejects a valid file | Both container types are accepted, and the error tells the operator to upload a PDF export or a link. The test files come from real CorelDRAW X3 and X7 samples |
| Frontend and backend deploy minutes apart on dev | Dev only; the internal create alias covers the cross-service path |
| A template drifts from the `workflow_steps` rows | The startup consistency check fails the deploy, and `TemplateContentTest` catches it in CI |

---

## Appendix A: Questions for the client

Each question is already decided with the default shown, so building does not wait on answers. A different answer changes only the template JSON or a seed, unless noted.

| Q | Question | Our default |
| --- | --- | --- |
| Q1 (C1) | Is the 25 MB limit per file or in total across the 4 files? | 25 MB in total, at most 4 files |
| Q2 (C2) | Which NB intro text: the prototype's longer one or the SOP's shorter one? | The SOP's; operators can edit before copying |
| Q3 (C3) | Invoice status: "Pending / Raised / Paid" or "Not initiated / Initiated / Sent / Paid"? | Not initiated / Initiated / Sent / Paid, for every item |
| Q4 (C4) | Step 8 for 9-step items: "Final Approval" or "Final Verification"? | Final Approval |
| Q5 (C5) | Show the SOP names ("Order Details & Onboarding", "Printing Press Operations") or the prototype's short names? | The prototype's short names |
| Q6 (C6) | How should the 4-digit number run: per item per year, per school, per day? | Per item code per calendar year |
| Q7 (C7) | "Manufacturing Factory" or "Making factory", and is the factory always a binder? | "Manufacturing Factory"; who is picked from the order's contacts |
| Q8 (C8) | When the school does not verify the pre-print sample, which step does the order return to? | Designing Activity (superadmin action) |
| Q9 (C9) | Should Validation Count capture produced, accepted and dispatched counts, or only the documents? | Documents required; counts optional |
| Q10 (C10) | Proof of delivery: a link or an uploaded file? What vehicle details are wanted? | A link; free-text transporter/vehicle plus optional LR/docket number |
| Q11 (C11) | Should notebook CDR conversion use the same 3 checks as fliers, flex and bill books? | Yes, required |
| Q12 (C12) | Are offset printer links needed on notebook Pre-Print QC? | Yes, kept |
| Q13 (C13) | What are the 4 production follow-up steps for fliers, flex and bill books? | Our 4 items (§2.7) |
| Q14 (C14) | SLAs for notebook Steps 7–9 and the 9-step Steps 1, 3 and 5–8? | NB 24/50/4 h; 9-step 2/2/24/24/72/4 h (editable by superadmin) |
| Q15 (C15) | What does Head Approval "Request changes" do? | Reopens Designing Activity |
| Q16 (C16) | Do you want an assigned operator per order? | Yes, kept as the point of contact, plus "Take this order" |
| Q17 (C17) | Is "Batch Makers" really "Badge Makers"? One gifting category or two? | SOP names; superadmin can rename |
| Q18 | Should wholesale (non-customised) notebooks skip Design, CDR and Head Approval? | Yes, as decided in tracker spec v4 |
| Q19 | Do flier, flex and bill-book orders still need a superadmin quote? | Quotes are kept as records but no longer gate production |
| Q20 | Who may approve designs and sign off QC, consignment and final approval? Only superadmin? | Superadmin only |
| Q21 | Codes for Belts, Standees, Ties and Trophies? | BLT, STD, TIE, TRP |
| Q22 | What should a school code look like (e.g. `DPS_GUR`)? Is the 3-letter city prefix right? | Initials + `_` + first 3 letters of the city; editable |
| Q23 | Do Drawing books need title cards, and with what formula? | Not calculated; shown as "Not on a title card" |
| Q24 | Should Step 1 require "Introduction message sent" to be ticked? | Yes |
| Q25 | Should the running number reset each calendar year or each financial year (April)? | Calendar year |
| Q26 | Certificates, Flute Boards, Report Cards, Brochures: is the general 9-step flow right? Any item-specific checks? | General flow, with generic checks |
| Q27 | Should final approval for fliers, flex and bill books be recorded by a superadmin click after the WhatsApp approval? | Yes |

## Appendix B: Prototype artefacts we do not copy

| Prototype behaviour (spec §8) | What we do instead |
| --- | --- |
| The stage list labels the opened stage "In progress" | Real status (D18) |
| SLA timer starts at page load, and runs on Step 12 while Step 11 is open | Server clocks; a stage's clock starts when the previous one completes (D23) |
| Calculated sheet quantities show 0 | Formula computed on the server (D24) |
| Head approval recorded locally, approver not authenticated | Authenticated superadmin action with a record (D20) |
| Files "saved in the local application folder", "Failed to fetch" | Private storage in school-core (D26) |
| "Saved selections remain on this browser", "Recorded in this prototype" | Everything persisted on the server |
| Hard-coded KPIs, feedback, design preview, revision history and upcoming actions | Driven by data (§4.3, §4.4, D39) |
| Flier screens with the "Certificates" badge, notebook cover and NB order id | Per-item template, icon and number |
| Complete stays disabled at 100% on later stages | Locked stages are read-only; only the active stage completes (D18) |
| Printing Press ticks allowed while the pre-print gate is incomplete | LOCKED stages are not editable (D18) |
| Sample order id `NB-2026-0042` | SOP order number (D11) |
| Typed "Reviewer name" | Signed-in identity (D20) |

---

## Implementation notes

Started at the first integration step (contracts and seams, 2026-11-13). Each entry records where the build departs from, or adds to, this design, and why. Later integration steps append to this section.

Some package reports reached this step cut short: WP2b and WP3 not at all, and WP0, WP2a, WP4, WP5 and WP6 in part. Their entries below come from what was received plus what integration found in the code. Each package's own deviation list should be added in full when it is available.

### WP0: contracts and shared helpers

- **School-core schemas are not bound to Java records.** §5.1 asks for schemas named after the Java records. School-core answers these screens with maps, so its new schemas (`OrderWorkflowSummary`, `OrderNumberLookup`, `SchoolDetails` and the rest) are explicit, closed objects with no `x-ims-java-record` binding. Every operations-service schema is bound.
- **`tenantSchoolClient` gained `createSchool` and `updateSchool`.** §5.1 lists only the order workflow, vendor directory and catalog contracts. The onboarding and school screens need typed access to the new `orderCode` (D12), so the school create and patch endpoints were added to the existing tenant-school contract.
- **Head Approval comments are capped at 1,000 characters,** not 4,000 as drafted, because V18 stores them in `design_comment VARCHAR(1000)`.
- **No gateway change.** As §3.5 expected, every new browser path is already routed. All 61 new or changed operations resolve to their declared owner through the live route table.

### WP1: operations migrations and templates

- **`step_code` and `phase` are required only on the five item-order definitions** (`CHECK ck_wf_step_item_order_code`), not on every row as §3.1 says.
  - The same table holds the generic approval definitions (`SUPPLY_ORDER_DEFAULT`, `FIREFIGHTING_DEFAULT` and anything V1 copied from `public`), whose steps have no code or phase.
  - The generic engine and `WorkflowSecurityIntegrationTest` insert such steps.
  - The code format check and `UNIQUE (definition_id, step_code)` apply as designed.
- **V17 adds `workflow_steps.updated_by` and `updated_at`,** with column-level UPDATE grants for `app_rt` on the default SLA, `updated_by` and `updated_at`. D40 requires SLA default edits to record who and when; §3.1 left the columns out.
- **V19 stops with an error when a saved vendor selection matches no directory vendor,** naming the order and the entry, instead of dropping it. Silently losing an operator's selection is worse than a failed deploy. Empty lists are dropped as before.
- **Extra database checks back up the application:**
  - a non-sample vendor must have a phone (`ck_wv_phone_required`);
  - Callback Requested needs `next_follow_up_at`;
  - several text columns may not be blank;
  - `category_code` on trackers and item group links must match school-core's category code pattern;
  - a call log's `step_order` is 1–99.
- **The 9-step "Supplier Contact Groups" card (`VENDOR_DIRECTORY`) has no `suggestedByItem`;** the "Select Vendors" card (`VENDOR_SELECTION`) carries it. §3.3 and the loader allow suggestions only on the selection card. So the directory card lists all 18 categories, and the screen reads suggestions from the selection card.
- **Our own UI copy where the prototype and SOP give none.** Only checklists have a `source` field, so the rest cannot be marked [Ours] in the files. Our wording covers:
  - the 9-step descriptions for Steps 1 and 5–8;
  - some card titles ("Conversion checks", "Counts", "Reviewer sign-off", "Internal team approval");
  - some save labels ("Save target date", "Save notes", "Save counts");
  - the Final Approval sign-off banner;
  - the `finalValidationNotes` placeholder.
- **Complete gates follow §2.7.** On the 9-step templates, Step 6 `qcNotes` and the counts are not required.
- **Follow-up (out of scope).** V16's CHECK on `workflow_school_whatsapp_group_links.target_key` still lists the 12 vendor keys. Those rows are no longer read, and §3.5 limits writes to `SCHOOL_GROUP` and `INTERNAL_APPROVAL`, so a later migration can narrow it.

### WP2a: engine domain

- **The template loader accepts both `sla` (§3.3) and `defaultSla` (the §3.4 component name).** It treats keys starting with `$` or `_` as comments and refuses any other unknown key. It reports every problem at once, each with its file and JSON path.
- **The startup check that templates match the `workflow_steps` rows can be switched off** with `workflow.templates.verify-steps=false`, for test contexts without the workflow schema. Deployed services keep it on, so the §5.5 mitigation stands.
- **Additional loader rules.** The first stage may not be `customisedOnly`. Legacy fields may not be required or editable. A template's file name must match its `definitionId`.

### WP2b and WP3: operations service, vendors, calls, activity, settings

- **One machine controller serves the new paths and their aliases.** `OrderWorkflowMachineController` maps `/api/v1/internal/order-workflows`, `…/{orderId}/design-file-access`, and the one-release aliases `/notebook-trackers` and `…/{orderId}/image-access`. All four share school-core's caller allow-list (`NOTEBOOK_TRACKER_CALLER_SERVICE_ACCOUNTS`), so no deployment variable changes.
- **Fixed at integration: route scopes.** The vendor directory, call log, activity and settings controllers checked the workflow service token without naming the route's scope, which the service authorization audit (`audit-service-authorization-boundaries.ps1`) rejects. Each route now names `workflow:read` or `workflow:write`, like `OrderWorkflowController`.

### WP4: school-core and platform reporting

- **V38 queues only open tracker-managed orders,** not every tracker-managed order as D41 and §3.2 say.
  - *Why.* The creation queue never creates a workflow for a closed order and drops a closed order's row, so queueing finished or cancelled orders would do nothing.
  - *Effect.* Workflows of orders that were already COMPLETED or DELIVERED keep no order number in operations, and their rows there show the CK id.
  - *Follow-up.* The queue could send a number-only create for a closed order whose workflow already exists.
- **Status pushes refuse the order flow's own statuses** (`DRAFT`, `AWAITING_APPROVAL`, `APPROVED`, `DELIVERED` and the rest), although they match the D5 pattern. A push can therefore never move an order into the approval flow. No template uses one as a step code, and `scripts/tests/tracker_status_codes_test.py` keeps it that way.
- **Typed product orders now start workflows** (D3). The approval-flow lifecycle tests run with the category's template switched off, which is D43's off switch. Notebooks stay `NOT_APPLICABLE` for pricing; other items keep their pricing and can be quoted until the order is finished (Q19).

### WP5: order workflow screens

- **Breakpoints are measured on the room beside the navigation rail, not on the window.** A 1280 px window leaves about 1,164 px, so the thresholds are set to give §4.9's four widths: 1280 → three columns, 1024 → two, 768 → one with a stage select, 375 → one.
  - Below 1,024 px of room, the stepper shows only the current and viewed labels (§4.3).
  - It folds to one line only below 640 px of room, so a 768 px window keeps the full stepper.
- **Not yet seen in a live browser.** The screens were verified with Vitest and Testing Library only, against the real template files.

### WP6: vendors, calls, set-up and order numbers across screens

- **The vendor phone rule matches the server:** an optional leading `+`, at least 6 digits, and at most 32 characters. Call notes are measured without surrounding spaces, as the server stores them.
- **Activating a catalog category sends only `{active: true}`.** The server merges a PATCH into the stored row, so re-sending the screen's copy of the code, noun and template could overwrite another superadmin's newer change.
- **The old `NotebookPanels.test.tsx` and `NotebookMalformed.test.tsx` were deleted,** although they are in neither package's file list, because they imported the deleted `features/tracker` modules. Their cases were ported:
  - order-list cases to `features/orders/list/responses.test.tsx` (WP5);
  - set-up and All orders cases to `NotebookAdminPanel.test.tsx` and `SaAllOrdersPanel.test.tsx` (WP6).

### Integration: contracts and seams

- **Contracts.**
  - The route inventory was regenerated against the merged controllers.
  - Every operation in the 16 contracts resolves to its canonical controller and gateway owner, and every bound schema matches its Java record, including nested types and enums.
  - The notebook tracker contract and its generated client were deleted; nothing used them. Coverage is now 161 canonical operations.
- **Machine routes in every filter copy.** `MachineCallerFilter` is guarded as identical across the five services (`scripts/check-duplicate-class-drift.py`), so the two new routes were added to every copy and to every copy's test. The authorization audit's contract and its regression test (`service_authorization_machine_proof_test.py`) now name `OrderWorkflowMachineController` and its four routes.
- **A status push no longer ends an open, numbered order's queued creation.**
  - *Before.* Any push deleted the order's creation row, because it proved the workflow exists.
  - *Problem.* Under D13 that row also carries the order number to an existing workflow. An engine that predates order numbers keeps pushing statuses while it refuses the new create path, so the number could be lost.
  - *Now.* The row stays until a create succeeds. A closed order's row is still dropped.
  - Code: `CatalogOrderFormService.applyTrackerStatus`; test: `aStatusPushLeavesAnOpenNumberedOrdersCreationQueuedUntilItsNumberIsSent`.
- **Design-file access falls back to the alias on 404.**
  - The new engine never answers 404: an order without a workflow gets `allowed: false`. So a 404 means an engine deployed before this release, and school-core asks again on `/notebook-trackers/{id}/image-access`, which answers in the same shape.
  - A create is deliberately never retried on the alias: the older engine would make every item a notebook workflow.
  - Code: `OperationsTrackerClient`; three tests in `PeerClientsTest`.
- **Either service may deploy first on dev.**
  - *Operations first.* An older school-core creates through the alias with today's body, which the engine reads as a notebook order. Notebook step codes are unchanged, so the older school-core accepts every push and ignores the new step fields.
  - *School-core first.* Creations, including everything V38 queues, retry with backoff for about two days until the new engine answers. Design-file checks use the alias. The older engine's pushes still apply, and number sync waits for the new engine.
- **The status code check now reads the templates.** `scripts/tests/tracker_status_codes_test.py` read operations' deleted `TrackerStep` enum. It now checks that:
  - every template step code, and COMPLETED, matches school-core's `WORKFLOW_STATUS` and is not an order-flow status;
  - the notebook template keeps the twelve tracker codes;
  - the V36 stage codes, as `importedStepCode` maps them, land in order on every 9-step template.
- **Call logs stay append-only after an `app_rt` rebuild.** `scripts/create-app-rt-role.sql` re-revokes UPDATE and DELETE on `workflow_call_logs`, as it already did for the notes and follow-ups (WP1's handoff).

### Integration: operations service

The full operations-service suite passes with `clean`: 527 tests at the start of this step, none failing, and 528 after the fix below. The full application context also boots against a database migrated to V20. Its startup check reports that all five templates match `workflow_steps`.

WP2b and WP3 sent no deviation lists. The entries below come from reading their code against §3.5 and §2.5; items marked [Ours] are rules the design does not state.

- **Fixed at integration: vendor gates use the directory's current category names.**
  - *Before.* Superadmin may rename a category (D31), but the notebook vendor gate still said "Select a vendor in Binders" afterwards. `GateFacts` had a slot for the current names that the service never filled.
  - *Effect.* Complete's refusal and the unmet list under the disabled button used the SOP name, not the name the vendor cards show.
  - *Now.* `VendorLookup` gains `categoryNames()`, which `VendorRepository` implements. The service passes the names whenever the stage's vendor gate names its categories.
  - Test: `theVendorGateNamesACategoryAsTheDirectoryNamesItNow`. The vendor directory test also checks the renamed name.

**Order workflow API (WP2b)**

- **The list's `item` filter takes item codes, not categories.** `GET /item-orders?item=NB,FE` takes comma-separated codes. The contract, the server and the list screen agree. §3.5 said "repeatable category".
- **An operator's `assignee` filter** may name only `me`, `unassigned` or their own id. Any other id gets 403 [Ours].
- **More error codes than §3.5 lists.** A rule refusal carries `{code, message}`; any other refusal, such as malformed input, carries only `message`.

  | Status | Code | When |
  | --- | --- | --- |
  | 409 | `VERSION_CONFLICT` | A stale `expectedVersion` |
  | 409 | `STEP_NOT_ACTIVE`, `STEP_NOT_COMPLETED`, `STEP_NOT_ON_PATH` | The stage is in the wrong state, or not on this order's path |
  | 409 | `CDR_LINK_MISSING` | Approve with no CDR link saved |
  | 409 | `NO_SIGN_OFF` | Sign-off on a stage that has none |
  | 409 | `ORDER_ASSIGNED` | Claiming an order someone already holds |
  | 403 | `SUPERADMIN_ONLY` | A superadmin-only action |
  | 400 | `NOTE_REQUIRED` | A required comment, reason or note is empty |

- **Claim and assignment answers.**
  - Superadmin calling claim gets 409, since superadmin assigns instead.
  - Assigning an ineligible operator gets 400. An ineligible operator who claims gets 403. 503 means identity could not answer.
  - A completed stage cannot be reassigned.
- **An order outside an operator's schools and items answers 404,** like an order with no workflow, so the answer reveals nothing. School admins get 403 on every order workflow route.
- **Vendor selections.**
  - An order holds at most 20 vendors per category [Ours].
  - A newly chosen vendor must be active. A vendor the order already holds stays selectable after it is deactivated.
  - `expectedVersion` here is the order's version, not a stage's.
- **Contacts.**
  - The factory contact needs both a name and a phone, or neither [Ours].
  - Contact phones are 3 to 32 characters: digits, spaces, `+`, `-`, `.` and brackets, with at least one digit. This is looser than the vendor directory's rule (at least 6 digits), because the school contact's phone is prefilled from the school record. The contacts card does no phone check of its own.
- **Only an order with a design stage takes a designer** (409 otherwise), so a wholesale notebook order has none.
- **A stage not yet reached takes no stage notes:** 409 `STEP_NOT_EDITABLE` [Ours].
- **The status push works out its step fields at delivery.**
  - The outbox still stores only the status and version. The template, step number and title, path length and steps done come from the order's template and path when the push is sent.
  - That is sound because stages complete in order and an order's template and path never change.
  - If operations no longer knows the order or its template, the status is still pushed, without the step fields.
- **Imported V36 progress.**
  - A stage code that is not on the order's path is left out, with a warning in the log.
  - "Production update" details and notes become stage notes, clipped to the note limit with an ellipsis.
  - Notes are dropped, with a warning, when neither the stage's completer nor the order's placer is known, since a note needs an author.
  - When stages were imported as completed, the current stage's clock starts at import, not when the V36 stage started.
- **Creating without `definitionId` makes a notebook order,** so the alias accepts an older school-core's body unchanged. A non-notebook create without `itemNoun` uses the category code in lower case, with spaces for underscores.

**Vendors, calls and activity (WP3)**

- **Call log rules [Ours].**
  - A vendor can be named only on a Printing Press or Manufacturing Factory call, and only a vendor the order selected.
  - A designer can be named only on a Designer call. A call names a vendor or a designer, never both.
  - A follow-up may be at most an hour in the past and at most a year ahead.
  - Without `stepOrder`, a call belongs to the current stage, or to none once the order is finished.
  - `GET …/calls` takes `limit` (default 100, at most 200).
- **Activity takes two more parameters.**
  - `step` returns only that stage's entries, leaving out order-level internal notes.
  - `limit` defaults to 50, at most 200. A page can run past `limit` by entries that share its oldest timestamp, so `nextBefore` (strictly older) skips nothing.
  - Legacy follow-ups appear on the stage coded `PRODUCTION_FOLLOW_UP`.
- **Category edits merge.** `PUT /vendor-categories/{code}` keeps whichever of `name`, `active` and `sortOrder` is not sent. Positions run from 1 to 9999.

### Integration: school-core and platform

Both full suites pass with `clean`. Platform: 516 tests. School-core: 1,222 tests, none failing, both at the start of this step and after the fix below. The one skipped test needs the Windows symlink privilege and predates this work.

WP4's own deviation list did not reach integration. The WP4 entries above, and the ones below, come from reading its code against §2.2, §3.2, §3.5 and §3.6.

- **Fixed at integration: the counters table no longer gets DELETE.**
  - *Before.* V37 granted `app_rt` SELECT, INSERT and UPDATE on `order_number_counters`. But `scripts/create-app-rt-role.sql` sets default privileges that give `app_rt` full DML on every table the owner creates later, so in a real environment the new table also got DELETE.
  - *Problem.* Deleting a counter row restarts that item's running number for the year, so NNNN repeats (D11 promises it never does). A placement whose whole number repeats then fails on the unique index.
  - *Now.* V37 revokes DELETE and TRUNCATE after its grant. V37 has never run outside tests, so editing it is safe.
  - Test: `ItemOrderNumberMigrationTest` now sets those default privileges before migrating. Its DELETE assertion failed first, then passed.
- **Legacy (form v1) orders get a number but no workflow.** D4 says placing in any category with a template starts a workflow.
  - The legacy `placeOrder` path mints the number (D11) and keeps the approval flow.
  - V38 moves only structured (form v2) orders. The engine and the status push take structured orders only.
- **A category's template needs an item code.**
  - V37 adds `ck_product_categories_workflow_needs_code`. The category admin refuses a template on a category without a code: "Give the item an order code before choosing its workflow".
  - *Why.* Without a code the order gets no number to send to its workflow.
  - A code another category holds answers 409.
  - In a PATCH, an explicit `null` clears the code, noun or template; an omitted field keeps it (`ClearableTextDeserializer`).
- **A placed order keeps the template it was placed under.**
  - It is stored as `attributes.workflowDefinition`; V38 sets it on the orders it moves.
  - The creation queue prefers it over the category's current template.
  - If the category's item code is cleared while a creation waits, each attempt fails and retries. The row is given up on after 48 attempts, about two days.
- **School codes past nine collisions.** D12 appends 2–9. V31 and `SchoolOrderCode` go on to 10, 11 and so on, shortening the initials to stay within 8 characters. A one-letter first word is padded with X to make 2 letters.
- **V31 adds an insert trigger** that derives a code for a school inserted without one, such as from a script or a fixture, and upper-cases a given code. The application always supplies the code itself.
- **V37 re-emits `catalog-order.upserted.v1` for every order it numbers,** so reporting learns the backfilled numbers through its ordinary projector. The backfill is skipped on a database without `tenant_school`, such as a catalog-only test.
- **No RLS guard list to extend.** §3.2 asks for `order_number_counters` to be added to the RLS guard test's list of global tables, but no such list exists: `RuntimeDbRoleGuard` checks only tables that enable RLS. `ItemOrderNumberMigrationTest` asserts instead that the table has no RLS and exactly the designed grants.
- **`customised` now means "the design steps apply"** in the tracker summary and the create body. It is true for every non-notebook item and for customised notebooks (D7). Before, it was the notebook's customisation choice.
- **A status push stores `attributes.workflowStep` only when it carries all four parts:** step order, title, total and steps completed. A partial report stores nothing, so a list never shows half a step.
  - `definitionId` is stored separately, as `attributes.workflowDefinition`.
  - A step title is at most 200 characters.
- **`GET /orders/by-number/{orderNumber}` returns `{id, orderNumber}`;** §3.5 says `{id}`.
  - Text that cannot be an order number gets 400.
  - A number the caller may not read gets 404, the same as one that does not exist.
- **`q` search** takes at most 100 characters. `%` and `_` match themselves.
- **`catalog.order_design_email_intents` is no longer written,** since D26 retires the intent, but the table is kept. A later migration can drop it.
- **Platform: reporting keeps a stored number when an event has none** (`COALESCE`), so a replayed older event cannot erase it. Approvals return `orderNumber` beside `invoiceNo`.
- **Open follow-up, outside these services: an `app_rt` rebuild makes V38's tables writable again.**
  - `scripts/create-app-rt-role.sql` grants SELECT, INSERT, UPDATE and DELETE on every catalog table.
  - Run again on a migrated database, it restores what V38 revoked on `product_order_workflows` and `product_order_workflow_steps`, and DELETE on `order_number_counters`.
  - It needs the same re-revoke it now has for the workflow logs. This step edits only the two services, so the script is handed off.

### Integration: frontend

- **The detail page's columns follow the window, as §4.3 states them.** This supersedes the WP5 note above about measuring the room.
  - *Why.* The workspace navigation is pinned (248 px) by default. It is a 64 px rail when unpinned, and a drawer at 768 px and below.
  - So a 1024 px window with the navigation pinned leaves about 724 px of room, less a classic scrollbar. A 768 px window leaves 716 px. Room alone cannot tell them apart.
  - Measured on room, a pinned 1280 px window showed two columns and a pinned 1024 px window showed one.
  - *Now.* Media queries set the tiers: three columns from 1280 px, two with the sidebar below from 1024 px, and one with the "Stage" select below that.
  - The stepper shows only the current and viewed labels below 1024 px, and folds to one line below 768 px.
  - A container query still narrows the side columns to 220 | fluid | 260 when the page has less than 1,280 px of room. A pinned 1280 px window therefore gives the stage panel about 470 px.
  - Playwright checks the tiers on the production bundle with the navigation pinned (`e2e/order-workflows.spec.ts`).
- **First check in a real browser.** `e2e/order-workflows.spec.ts` runs the list, an NB order (from the shipped template) and Workflow set-up on the production bundle.
  - It checks colour contrast on the list and the set-up tabs, and on the order's four tabs and every one of its 12 stages.
  - It checks for sideways page scroll at 375, 768, 1024 and 1280 px.
  - **It found one defect, now fixed:** the viewed stage row's status line was secondary grey on the primary-soft background (4.4:1). It now uses the row's primary text, as §4.3 says.
- **The call log is wired to the order screen for real.**
  - *Show all opens Activity history (§4.6).* `CallLogPanel` takes an optional `onShowAll` beyond the §5.2 props. Without it, the panel lists every call itself.
  - *A preset is for one call.* The panel was keyed by the preset, so clearing the preset after a call was logged remounted it and dropped "Call logged". It is no longer keyed.
  - After a preset call the form starts clean, and the next "+ Add" applies the same preset again. Before, a second "+ Add" with the same preset was ignored.
  - `workflow.test.tsx` now mounts the real `CallLogPanel`; only its transport is mocked.
- **Deadlines are never drawn against the uncorrected device clock.**
  - `useServerClock` applied a new server-clock offset in an effect, after the render that first showed the list or the order. That render computed "Due in …", the stage timers and the overdue state from the device clock.
  - With the device clock two hours off, the first frame said "Due in 1h 4m" for a stage due in 3 hours. The list test caught it intermittently under load.
  - The offset now applies in the same render (`workflow/hooks.test.tsx`).
- **The reopen reason has a word counter** and is refused at 100 words in the browser, matching `NoteRules.REASON_MAX_WORDS`.
- **Text boxes stop at the server's character caps** (`NoteRules`): Head Approval comments and the reopen reason at 1,000, and stage and internal notes at 4,000. Before, a long comment within 100 words reached the server and was refused.
- **School create and edit use the generated tenant-school client** (`createSchool`, `updateSchool`) in `SaSchoolsPanel` and `SchoolManagementPage`. They send `orderCode`, so the contract now types it. The paths are unchanged.
- **One set of error helpers.** `workflowError`, `NOT_UNDERSTOOD`, `WORKFLOW_UNAVAILABLE` and `parseList` were defined twice (in `workflow/api.ts` and `vendors/api.ts`). They now live in `features/orders/shared/errors.ts`, and both modules re-export them.
- **Renamed and removed.**
  - `.ck-product-workflow-link` (the "Open order workflow" box) is now `.ck-order-workflow-link`, since §4.0 drops `.ck-product-workflow*`.
  - Removed as unused: `pathPosition`, `useElementWidth`, and the room constants.
  - Nothing of `features/tracker`, `ProductOrderWorkflow` or the notebook tracker client remains.
- **What the screens still name in code.** No checklist, field list or sign-off list is hard-coded.
  - *Field keys and a step code.* A few block meanings name a field key the engine also names: `cdrLink` (CDR for approval, Head Approval), `orderPdfLink` (order folder), and `revisedCompletion` and `targetCompletion` (the estimated completion tile).
  - The Head Approval block finds Designing Activity by its code, `DESIGNING_ACTIVITY`, for its wording.
  - *Fallback titles.* Block titles in `blocks/shared.tsx` are used only when a template gives none.
  - *Template ids.* The five definition ids are the contract's closed enum, with labels for the selects.
- **Every request is checked against the controllers.** `features/orders/integration.test.tsx` runs the real screens over a mocked HTTP transport: the list, an order, the call log, step save and complete, an order-number link, All orders → View, and Workflow set-up.
  - It fails if any request is not a canonical endpoint in `services/api-gateway/api-route-inventory.json`, which is generated from the Spring controllers.
  - The list and All orders unit tests keep their `OrderDetailEntry` stubs as isolation. The real wiring is covered there.
- **Test timing.**
  - One userEvent-heavy `SchoolManagementPage` case timed out at the 5 s default when the full suite ran in parallel. It now has the 10 s its sibling cases already have.
  - The `sa-portal-audit` case "bold inside a flowing alert stays in the sentence" counted alerts before the lazily loaded Approve & fulfill panel had rendered ("Loading module..."). It failed in two of four full Playwright runs. It now waits for the alert.

### Review fixes: scripts

- **Closed: an `app_rt` rebuild no longer undoes V37 and V38.** This resolves the open follow-up under "Integration: school-core and platform".
  - `scripts/create-app-rt-role.sql` takes back DELETE and TRUNCATE on `catalog.order_number_counters`, and INSERT, UPDATE, DELETE and TRUNCATE on `catalog.product_order_workflows` and `product_order_workflow_steps`, after its blanket catalog grant.
  - Each revoke selects from `pg_tables`, so it does nothing until school-core has created the table. A first run on an empty database is unchanged.
  - The script's final check now fails the run if `app_rt` still holds any of those privileges, or UPDATE or DELETE on the three workflow logs. A REVOKE by the owner cannot remove a grant another role made, so the check reads what `app_rt` actually holds.
  - This matters beyond `app_rt`: every school-core migration copies `app_rt`'s catalog privileges onto `ims_school_core_rt` (`afterMigrate__dedicated_runtime_acl.sql`). The next migration after a re-run now copies the narrowed set.
  - *Evidence.* `scripts/tests/create_app_rt_role_test.py` re-runs the script on a migrated PostgreSQL 16 database in Docker. It passes 6 of 6. Against the `origin/dev` script it fails 5 of 6.
  - *No rollout window.* This is an operator script, not a migration. Before V37 or V38 has run, the guarded revokes are no-ops and the migrations narrow the tables themselves.
- **Not fixed here: the same re-grant affects narrowings older than this branch.** The blanket table and sequence grants also restore what these versioned migrations took from `app_rt`:
  - catalog V8 and V24, student V24, V26 and V39 (including the column-level INSERT on `student.students`), tenant_school V30 and fee V10 (a sequence);
  - identity V8 and V9, firefighting V12 and V13, notification V11, V12, V15, V17 and V18, and reporting V32.
  - Each has its own semantics (column grants, sequences, append-only tables). They need a separate change with their own tests, not a blanket revoke here.

### Review fixes: frontend

- **Supplier Contact Groups lists the item's suggestions first on the 9-step templates.** Their directory card has no `suggestedByItem` (WP1 note above), so the screen takes them from the same stage's "Select Vendors" card, as that note intended. The suggested categories are open and the rest sit under "Other categories". No loader or template change.
- **SOP stage names (D49) come from the screen, not the template.** The template format (§3.3), the loader and the contract have no field for them.
  - The screen keeps a two-entry map by step code: `ORDER_DETAILS` → "Order Details & Onboarding" (every item) and `PRINTING_PRESS` → "Printing Press Operations" (notebooks). These are the only stages whose SOP name differs from the prototype's.
  - The name is the stage description's tooltip (`title`) and is read out with the description in the stage header.
  - *Follow-up.* An optional `sopTitle` on `StepSpec`, the loader and the templates would replace the map.
- **A stage not reached yet takes no stage notes.** The note form follows the step's own `canAddNote` only. Before, the order-level flag (which is for the sidebar's internal notes) showed the form on locked stages, and saving was refused with 409.
- **Refresh reads the order view again,** with its summary, design files and the school feedback calls. Before, it re-read only the summary, the template and the files, and the stages, gates, notes and assignee stayed as they were when the page opened. Retry is unchanged.
- **An upload or removal of a design file reads the order view again.** The "at least one design file" gate is worked out when the server builds the view, so Complete stayed disabled after the first upload until something else reloaded the view.
- **Unsaved vendor ticks and typed contacts survive other saves.** They follow the server only when the saved selection or saved contacts themselves change (`useServerDraft`). A checklist tick, a logged call or a designer pick no longer clears them. If someone else changes the saved value, theirs replaces the unsaved edits.
- **Design file uploaders are named, not shown as ids.**
  - School-core lists `uploadedBy` as the uploader's user id (a JSON number), although the catalog contract types it as a string.
  - The screen names it from the people the order view already names: point of contact, stage assignees, completers, last savers and sign-offs, note authors, the design decider and the signed-in user. Anyone else shows as "User 42", like the order's other unnamed authors.
  - *Follow-up (school-core, contract).* Return an `uploadedByName` and type `uploadedBy` as an integer.
- **An untouched school order code suggestion is sent as null.** The server then derives the code and adds the 2–9 suffix on a collision (D12). Only a code the superadmin typed is sent as typed, and refused if it is taken.
  - The suggestion shown already skips codes the listed schools use (DPS2_GUR).
  - The success message names the code the server stored. Since review round 2 the suggestion derives exactly as the server does (accented letters dropped, stop words skipped in the fallback, X padding), so the two agree.
  - Applies to the onboarding form (`SaSchoolsPanel`) and the add-school form (`SchoolManagementPage`).
- **A call is logged against the order's current stage (D35),** whichever stage is being viewed. A finished order's calls belong to the order. The form offers no stage choice, so "defaulting to the current one" is the only stage.
- **The introduction message records its final text.** The templates now declare `introText` (see the operations review fixes), and ticking "sent" saves the message as shown into it. The message box stops at that field's 1,000 characters. Activity history fills `{Name}` with the sender's name in older INTRO_SENT entries that stored the template; it shows none when the sender is unknown.
- **Nothing on the order screens renders below 12px:** field hints, word counters and inline errors (`.ts`) are raised inside `.ck-wf`, as the call log and set-up screens already did.
- **The Send back reason has the reopen reason's limits:** a word counter, refused over 100 words, capped at 1,000 characters.
- **Head Approval says why a superadmin has no decision form:** the stage is completed (reopen it), not yet in progress, or has no CDR link. "Only a superadmin approves the design" is now for operators only.
- **Board columns say when they count only loaded orders.** While more orders match than the board has loaded (100 at a time), each column reads "N loaded". The list endpoint returns no per-phase totals.
- **The "Due today" summary chip is a count, not a filter (deviation from §4.2).** §3.5 and the contract define no due-today filter (`sla` is OVERDUE, DUE_SOON, ON_TRACK or NO_TIMER), so the chip has nothing to apply. *Follow-up (operations, contract).* A `sla=DUE_TODAY` filter matching the `dueToday` count would let the chip filter.
- **WEBP design files from before D26 preview as pictures,** and the Current Design Preview tile names a non-picture file by its type (PDF, CDR, or its extension). Before, a WEBP file showed a "PDF" tile, and "View file" downloaded it.
- **"Call logged" uses the order screen's toast,** bottom right like the page's others. `CallLogPanel` takes an optional `onToast`, beyond the §5.2 props. Without it, the panel keeps its own status region mounted, so the confirmation is announced.
- **The list's School column shows "Name · city" where the page knows the city.** That is superadmin, whose school list carries it. Operators see the name alone. *Follow-up (operations, contract).* A `schoolCity` on `OrderWorkflowListItem` would give every user the city.
- **No rollout window.** These are browser changes only, and each works with the old and new service revisions:
  - the uploader is read whether it arrives as a number or a string;
  - `introText` is sent only when the served template declares it;
  - school-core has always accepted a null order code.

### Review fixes: operations

The full operations-service suite passes with `clean`: 539 tests, none failing (528 after the integration step, plus the 11 tests below).

- **Fixed: INTRO_SENT records the message as sent (D28).**
  - *Before.* The server recorded the template with only `{Item}` replaced, so every entry read "My name is {Name}, …" and the sender's name and edits were lost. The screen sends the composed text only into a Step 1 TEXT field whose key names the introduction, and no template declared one.
  - *Now.* Step 1 of all five templates declares `introText` (TEXT, at most 1,000 characters, "Introduction message as sent"), named by the INTRO_MESSAGE card's `fields`. The tick saves the message into it, and INTRO_SENT records that text. A client that sends none still records the template's text with its item named.
  - *Template format (§3.3).* INTRO_MESSAGE now takes `fields`: one field, of kind TEXT, not legacy, which the loader enforces. No record or contract shape changes, since `BlockSpec.fields` already exists.
  - Step 1 has no sign-off and no gate reads the field, so it never affects a sign-off or a completion.
  - Tests: `everyChangeIsRecordedAndTheIntroductionNamesTheItem`, `eachIntroductionCardKeepsTheMessageAsSent`, `anIntroductionCardKeepsTheMessageAsSentInOneTextField`.
- **Fixed: a finished stage keeps its sign-off when the invoice moves on (deviation from D20's wording).**
  - *Before.* D20 voids a sign-off on any change, and §2.7 lets superadmin edit the invoice status after completion. Marking a finished order Paid therefore removed the consignment sign-off: the Delivery stage showed "Pending" with a sign-off button, and signing again would record a later, misleading time.
  - *Now.* Before completion, any change still voids a sign-off. On a COMPLETED stage, which only superadmin edits (D18), the sign-off stands for what the stage's completion gates read; a change to a field or checklist no gate reads keeps it.
    - NB Step 12 and 9-step Step 9: `invoiceStatus`, `invoiceNumber` and `lrNumber` can change. The transporter, dispatch date, proof link and (9-step) delivery status still void it.
    - NB Step 7: the reviewer's `qcComments` can change. The checks, sample link and school verification still void it.
  - *Why gates and not a template list.* A `signOff.excludes` key would add a component to `SignOffSpec`, which the contract binds exactly (`x-ims-java-record`). The gates already say what a stage's completion rests on.
  - The rule is `StepDataRules.merge(step, saved, raw, completed)`; the three-argument form keeps D20's rule.
  - Tests: `aCompletedStageKeepsItsSignOffWhenOnlyDetailsNoGateReadsChange`. The notebook run also checks that the sign-off survives the post-completion Paid save and is voided by a changed proof link.
- **Fixed: General-item orders title Step 5's card with the item's noun (D25).** The General template's ITEM_QUANTITIES card had the fixed title "Item quantities". It now has none, so the screen's fallback applies: "<Item noun> quantities", for example "Certificate quantities". FE, FX and BBK keep their own titles. Test: `theQuantitiesCardNamesTheItem`.
- **Not changed: NB Step 8's "Expected completion" stays its own card (deviation from §2.7 Step 8).**
  - §2.7 puts the date beside checklist item 4. The block format (§3.3) cannot place a field inside a checklist, and the screen's checklist card cannot render one.
  - The untitled date card sits directly after the checklist card, with its "Save expected completion" button. The gate needs only the eight ticks, so nothing depends on where the date sits.
  - *Follow-up (template format, loader, contract, screen).* A CHECKLIST `itemFields` map from item index to field key, rendered beside the item and saved with the tick. Changing only the template now would hide the date until the screen supports it.
- **Fixed: writes by the previous revision during rollout (new migration V21).**
  - *The window.* A release runs this service's migrations before its new revision serves. For a few minutes, or longer if the new revision fails to start, the notebook tracker's revision keeps serving on the new schema, and open tabs keep calling it.
    - Its vendor-selection save rewrites the column as `{"board": ["board_shree"]}`. The engine could not parse that, so every call on the order answered 500 until the row was fixed by hand.
    - Its stage-note save writes `stage_note`, which nothing reads since V20, so the note disappeared.
  - *V21* runs in one transaction that locks both tables first. `lock_timeout` is 15 s, so a blocked release fails instead of queueing requests behind it. It:
    1. repeats V19's remap, only for rows that still hold the old category keys, with V19's stop-and-name guard. Rows V19 or the engine wrote are untouched;
    2. adds `ck_wnt_vendor_selections`: a selection is an object of category codes to lists of numbers. A later save by the old revision fails with 500 in the old screen, and a reload brings the new one;
    3. repeats V20's stage-note copy, which skips notes already copied;
    4. adds the trigger `copy_retired_stage_note`. Each stage note an old revision saves from now on is copied into the stage's entries in the same transaction, with its author and time. It runs with the saver's rights and row-level-security scope.
  - *Engine.* `OrderWorkflowRepository.vendorSelections` no longer throws. An id that is not a whole number, or a category that is not a list, selects nothing and is logged, so one bad row cannot fail the whole order.
  - *Not covered.* After V21, a rollback to the notebook tracker's revision cannot save vendor selections, because the check refuses its format. That revision could not read selections correctly after V19 anyway.
  - *Clean-up (next release).* Drop the trigger and its function together with the `stage_note` columns, once no revision that writes them can serve.
  - Tests: `OrderWorkflowRolloutWindowMigrationTest` (5) and `OrderWorkflowRepositoryVendorSelectionsTest` (2).
- **Fixed: notebook workflows created before order numbers report their step to school-core.**
  - *Before.* The old engine's status pushes carried no step report, and catalog V38's re-queued create only filled in the order number. All orders and the order detail therefore showed only the status in words, with no "5 · Production Planning / 4 of 12 steps done", until the order's next step change. Pushing the same version again would not help: school-core ignores a version no newer than the one it applied.
  - *Now.* When a create fills a missing number, the instance version moves and the current status is queued at that newer version, then pushed after commit. Every school-core revision applies it, and this release's stores the step report. The status itself is unchanged, so the catalogue order's version does not move and no event is sent.
    - A create that fills nothing pushes nothing, as before.
    - If an order's stages no longer fit its template, the number still fills and the push is skipped with a warning.
  - *No school-core change needed.* The finding's other half, school-core storing a report at an equal version, is not required for this path.
  - *Gap left: orders already finished.* V38 re-queues only open orders, so a notebook order finished before this release gets no step report. Its lists still show "Completed"; only the "12 of 12 steps done" line is missing. A back-fill would need pushes for finished orders that reach school-core's new revision, which the deploy order cannot guarantee.
  - *Window.* A re-queued create that reaches the previous operations revision is refused there and retried by school-core's queue until this revision serves.
  - Test: `creationIsIdempotentAndFillsAMissingOrderNumberOnce`.
- **Handoffs.**
  - *Contract (`orderWorkflowClient.v1`).* The `BlockSpec.fields` description should also name INTRO_MESSAGE ("the one TEXT field the tick saves the message as sent into"). This is a description change only, then a client regeneration.
  - *Frontend (optional).* `IntroMessageBlock` could take the field key from the card's `fields` instead of matching `/intro/i` on the stage's fields. Both find `introText` today.

### Review fixes: school-core and platform

- **Fixed: the previous school-core revision can no longer create, or lose, a workflow during a release (catalog V38 rewritten; it had never run outside tests).**
  - *The window.* School-core's migration job runs V37 and V38, then the new revision is deployed. The previous revision keeps serving until the new one is ready (dev), and in prod beside it through the Cloud Deploy canary (5/25/50 %). Operations deploys after school-core, so the previous engine serves meanwhile too. The previous school-core:
    - drained `notebook_tracker_creation_queue` every minute with no notion of templates. Every flier, flex and other order V38 queued, and every order the new revision queued during the canary, would have got a NOTEBOOK workflow on the wholesale path through the old engine's route, and lost its row, so it stayed on the wrong template for good (operations V18 then stamps it NOTEBOOK);
    - deleted an order's row whenever its workflow pushed a status, losing the row that carries the order number to an existing workflow (D13);
    - started a workflow by hand for any tracker-managed order, which after V38 includes fliers;
    - placed orders with no number, and fliers and the rest on approve, quote and deliver.
  - *Deviation from D6.* The queue's **table** is now `catalog.order_workflow_creation_queue`, which no earlier revision reads. The class keeps its name. V38:
    1. creates it with the old table's shape, RLS and grants, plus a `repair` flag;
    2. moves the old table's rows into it, with their retry state, in one statement;
    3. adds a `BEFORE INSERT` trigger on the old table that forwards each row into the new one, with fresh retries, and writes nothing to the old one. The previous revision's own attempt and drain then find nothing, and its status push deletes nothing that matters. The trigger runs as the caller, under the same RLS policy;
    4. adds a deferred constraint trigger on `catalog_orders`: an order that leaves DRAFT and still has no number at commit, in a category with an item code, is queued with `repair = true`. This release mints in the placing transaction, so its placements never match. The trigger never fails a placement; a failure is a PostgreSQL warning;
    5. then moves the open orders and queues the open tracker-managed ones as before (D4, D13), into the new table.
  - *Repair.* A claim leases a repair row, then, in a transaction of its own under the order's row lock:
    - mints its number, dated by its placement;
    - moves an open structured order of a template category onto its workflow, exactly as V38 does;
    - tells reporting when either changed.
    - Only then does it read the order's facts. A repair that fails keeps the backoff and records why.
  - *Also new.* A queued order that is not tracker-managed is dropped, not created. Only a placement under a template, V38 or a repair makes an order tracker-managed. A tie placed in the window therefore gets its number and nothing else.
  - *The health report* now names the queue `order_workflow_creation_queue`.
  - *Not deployed anywhere,* so there are no wrongly templated workflows to repair.
  - *Clean-up (next release).* Drop `notebook_tracker_creation_queue`, the forwarding trigger and the placement trigger with their functions, once no revision older than this one can serve.
  - Tests:
    - `ItemOrderNumberMigrationTest`: the previous revision's drain SQL, verbatim, finds nothing. Its enqueue is forwarded and revived, and is refused for another school. Its `done` deletes nothing. Its placement is queued for repair, and a minting placement is not. A refused repair never fails the placement. The new table has RLS and grants.
    - `ProductOrderWorkflowIntegrationTest`: a flier, a tie and a notebook placed the old way are numbered, the flier is moved, reporting is told, and both workflows are created under their templates. A repair is done only once.
- **Fixed: orders placed by the previous revision during a release get their number and their workflow.** This is the repair above (V38's commit-time trigger, then the claim). D13 now holds for them. An open flier, flex or other templated order moves onto its workflow as V38 moved the others (D3, D4). Its status stays where the old flow put it until the engine pushes, as with V38's own moves. Its number carries its placement date, but its running number comes after those minted in the meantime, because a counter never goes back.
- **Fixed: reporting gets the backfilled numbers (reporting V36 extended; it had never run).**
  - *The window.* Platform deploys last. The previous platform revision projects V37's re-emitted events, and this release's school-core events, with a projector that ignores `orderNumber`, and marks them processed. Nothing projects a processed event again, so those orders kept a null number.
  - *Now.*
    1. V36 copies the latest number from the inbox payloads onto every fact that lacks one, under the RLS bypass. A payload that is not JSON is skipped.
    2. A trigger copies the number of each `catalog-order.upserted.v1` event onto a fact that lacks one when any revision marks the event processed. It runs in the projector's transaction and under its RLS bypass.
    - A stored number is never overwritten; numbers are immutable.
    - The trigger never fails a projection.
    - It can be dropped once no older platform revision can serve.
  - Test: `CatalogOrderNumberMigrationTest` (3). It replays the previous revision's upsert, verbatim, as the runtime role with RLS forced.
- **Fixed: design-file checks reach the alias on the older engine.**
  - The older engine's `MachineCallerFilter` refuses an unregistered internal route with a bare 403 (no body) before any controller runs; it never answers 404. With the 404-only fallback, every design-file upload and removal answered 503 from the moment school-core was live until operations was. That could be indefinitely, if the operations release stopped.
  - `OperationsTrackerClient.imageAccess` now asks the alias on a 404 or a 403 with an empty body.
  - A 403 with a body still fails closed: `MACHINE_CALLER_REJECTED`, or Cloud Run IAM's page. Creates are still never sent to the alias.
  - The integration note "School-core first: … Design-file checks use the alias" now holds.
  - Tests: `PeerClientsTest`, three cases updated.
- **Fixed: a stored file's name ends in an extension of its detected type.**
  - A CorelDRAW file is checked by its 12-byte header only (D27). A RIFF/CDR header followed by a script, named `.hta`, `.html` or `.bat`, was stored, listed and downloaded under that name.
  - Now every upload keeps the uploader's extension only when it matches the detected type (png; jpg or jpeg; webp; pdf; cdr). Otherwise the type's extension is appended: `Final_Artwork.html` becomes `Final_Artwork.html.cdr`.
  - The content endpoint applies the same rule to the name it serves, which covers files stored earlier.
  - Test: `aStoredFileAlwaysCarriesTheExtensionOfItsDetectedType`.
- **Fixed: a removed design file names who removed it (catalog V39, D40).**
  - `catalog_order_assets.superseded_by` is set with `superseded_at` on removal, and when a replacement upload supersedes a file. The asset list and history return it as `supersededBy`, a user id, beside `uploadedBy`.
  - Removals by the previous revision during a release, and all earlier removals, stay unattributed.
  - Test: `aRemovedDesignFileNamesWhoRemovedIt`.
- **Fixed: a noun taken from a long label fits the engine.** With no item noun, the queue sends the lowercased label cut to 40 characters, the engine's limit (operations V18). The label itself may have 80. Before, every create of such a category answered 400 until it was dead-lettered. Test: `aNounTakenFromALongLabelFitsTheEngine`.
- **Handoffs.**
  - *Contract (`catalogClient.v1`, WP0).* Add `supersededBy` (integer or null) to `OrderAsset`, then regenerate `frontend/src/generated/catalogClient.ts`. `uploadedBy` is typed as a string but has always been a number (BIGINT).
  - *Frontend.* `ActivityTab` should name the remover with `uploaderName(file.supersededBy, people)` for the `file-out:` entries (today `actor: ''`). `designFileName()` should append `.cdr` to a CDR file whose name lacks it. New uploads already carry it; this is a fallback.
  - *Runbook.* `docs/runbooks/notebook-tracker-deploy.md` names `notebook_tracker_creation_queue`. The queue is now `order_workflow_creation_queue`, and rows with `repair = true` are previous-revision placements awaiting their number.

### Follow-ups closed

This closes the handoffs from "Review fixes: frontend", "Review fixes: operations" and "Review fixes: school-core and platform".

- **A removed design file names who removed it, and the contract types `uploadedBy` as a number.**
  - *Contract (`catalogClient.v1`, `OrderAsset`).* `uploadedBy` is an integer (int64) or null, as school-core has always returned it (BIGINT). The new `supersededBy` (integer or null) is the user id that catalog V39 records with a removal or a replacement. `frontend/src/generated/catalogClient.ts` is regenerated.
  - School-core already returns both fields in the asset list and the history (`CatalogOrderFormService.assetList`), so no service change was needed.
  - *Activity history.* The "Removed design file …" entries name the remover from `supersededBy`, the same way the uploader is named: from the people the order view names, otherwise "User 42". A removal recorded before V39 still names no one.
  - *File names.* `designFileName()` adds `.cdr` to a CorelDRAW file (`application/vnd.corel-draw`) whose stored name lacks it, as school-core now stores and serves one:
    - trailing dots are dropped;
    - `Final_Artwork.html` becomes `Final_Artwork.html.cdr`;
    - an unnamed file becomes `Design file 5.cdr`.
    The shown name and the download name both use this.
  - `uploaderName()` still accepts digits and names sent as strings, so it works with any school-core revision.
  - Tests: `names a CorelDRAW file with its extension …` (model) and `names who removed a design file …` (workflow). The fixtures no longer cast `uploadedBy`.
  - *Not done: `uploadedByName`* (suggested in the frontend review). The screen names uploaders and removers from the people in the order view. School-core would need identity's directory to name anyone else.
- **The introduction card's field comes from the card.**
  - *Contract (`orderWorkflowClient.v1`).* The `BlockSpec.fields` description now reads: "FIELDS / ORDER_PDF_LINK / LEGACY_DETAILS: the step fields the card shows, in order; INTRO_MESSAGE: the one TEXT field the tick saves the message as sent into". This matches the `BlockSpec` record's documentation.
  - *Screen.* `IntroMessageBlock` takes the first TEXT field named in the card's `fields`. It falls back to the `/intro/i` match only when the card names none.
  - Test: `records the message in the field the introduction card names, whatever its key`. The test uses a key without "intro", next to a decoy field whose key has it.
- **The "Due today" chip is now a filter (this closes the §4.2 deviation).**
  - *Operations.* `GET /item-orders?sla=DUE_TODAY` lists orders whose active step is due today in the order's own time zone (the school's), whether overdue or not.
    - The count and the filter share one predicate (`OrderWorkflowRepository.DUE_TODAY`), so the filter lists exactly what `counts.dueToday` counts.
    - A finished order has no active step, so it never matches.
    - The service accepts DUE_TODAY alongside the four SLA states; any other value still gets 400.
  - *Contract.* The `sla` query parameter's enum gains `DUE_TODAY`, with a description. `SlaState` (a row's state) is unchanged, because DUE_TODAY is a filter, not a state. The `dueToday` count's description now says the filter lists those orders.
  - *Screen.* The chip is now a button like the others, and shows as pressed while `sla=DUE_TODAY` is set. The SLA select offers "Due today", so the toolbar shows the filter the chip applied. `ListFilters.sla` takes its type from the query's enum (`SlaFilter`).
  - Tests:
    - `dueTodayListsExactlyTheOrdersTheChipCountsInEachOrdersTimeZone` covers a step due later today, one due early today (Sunday in UTC), one due just after midnight (still Monday in UTC), one overdue since yesterday, one with no timer, an order in Los Angeles where it is still Sunday, and a finished order. It also checks the assignee filters, as superadmin and as an operator.
    - `theDueTodayChipsFilterReachesTheService`, the chip case in the list test, and a filters case.
  - *Rollout.* `/item-orders` and the list screen are both new in this release, so no earlier revision of either has to accept the new value.
- **The runbook now uses the queue's new table.**
  - `docs/runbooks/notebook-tracker-deploy.md` has a new section, "The creation queue since catalog V38". It covers:
    - the new table and the forwarding trigger on the old one;
    - `repair = true` rows and what a claim does with them;
    - the WARNING left when queueing fails;
    - a read-only check to run after the migration job and again before the clean-up;
    - the next release's clean-up migration: drop the old table, both trigger functions and the placement trigger, and keep the `repair` column.
  - Every SQL snippet now reads `catalog.order_workflow_creation_queue`, with `repair`. The section also lists the health log's `queue` names.
  - `ensure` revives a row and keeps its `repair` flag, but it refuses an order that is not on a workflow. A repair row's order can be one of these, so a new SQL snippet revives such a dead-lettered row.
  - A repair row must never be abandoned while its order still exists: nothing would queue that order's number again.
  - *Evidence.* V38 and every runbook statement ran on a scratch PostgreSQL 16 over a minimal catalog schema: a placement by the previous revision queued for repair, a forwarded insert, then read, revive, abandon and the clean-up, which dropped both triggers. These ran as a superuser, so they check the syntax and the effect, not the RLS bypass.
  - *Closed since: the runbook's routes and probe.* `docs/runbooks/notebook-tracker-deploy.md` was rewritten for the SOP release and the contract release (see "Runbook rewrite" below). Its probe no longer expects `GET /api/v1/workflows/notebook-orders/{orderId}` to answer; through the gateway every such route answers 401, so only a signed-in session can show it is gone (`theOldNotebookOrderRoutesAreGone`).
- **Verification (all `clean` for Java).**
  - `node scripts/generate-openapi-typescript-client.js --check`: current (16 domains, 161 operations).
  - `node scripts/generate-api-route-inventory.js --check`: current (502 endpoints).
  - The generator, route-inventory and gateway contract `node --test` suites: 29 tests, none failing.
  - Operations: `OrderWorkflowServiceIntegrationTest` (43), `OrderWorkflowControllerTest` (13) and `OrderWorkflowExtrasControllerTest` (9), none failing.
  - Frontend: `tsc --noEmit` reports no errors. Vitest `src/features/orders` and `src/generated`: 24 files, 243 tests. `src/features/catalog` and `src/pages/workspace/panels`: 48 files, 314 tests. All pass.

### Review round 2 fixes

A second review looked only at the first round's fixes: rollout and migration safety on real data, and whether each fix is correct. Four findings were raised: two medium, two low. The fix agents for this round failed on a transient API error, so these four were fixed directly.

- **Fixed (medium): the dedicated runtime role can write the new creation queue the moment catalog V38 commits.**
  - *Problem.* Dev and prod connect as `ims_school_core_rt`, which gets privileges on new tables only from `afterMigrate__dedicated_runtime_acl.sql`. Flyway runs that once, after the last migration. V38's two triggers write `order_workflow_creation_queue` as the caller, and the previous revision's writes resume as soon as V38 commits. In that window, a notebook placement by the previous revision failed with "permission denied". Any other placement committed, but its repair row was lost with only a PostgreSQL warning, so that order was never numbered.
  - *Now.* V38 grants the new queue to `ims_school_core_rt`, when that role exists, in the same commit. `afterMigrate` then re-syncs the role to the same set.
  - Test: `thePreviousRevisionsEnqueueWorksAsTheDedicatedRoleTheMomentV38Commits` runs the previous revision's enqueue, verbatim, as a dedicated-style role after V38 and before `afterMigrate`. It failed with the grant removed and passes with it.
  - Operations V21's trigger writes `workflow_internal_notes` (a table that existed before this release) and reporting V36's trigger updates `fact_catalog_order`, so the dedicated roles already hold table-level privileges there. Reporting V36's trigger also catches every error.
- **Fixed (medium): orders the previous revision placed between catalog V37 and V38 get their number.**
  - *Problem.* V37 commits on its own, and the previous revision never mints. Orders it placed after V37, and before V38 took its locks (or for longer, if V38 failed and was re-run later), were queued without `repair`, or not at all. A claim mints only for repair rows.
  - *Now.* V38 step 2b queues every placed, unnumbered order of a coded category with `repair = true`. This is the same rule V37 numbers by. The claim mints the number, moves an open flier and the rest as step 1 does, and drops a closed order or one that runs on no workflow once it is numbered.
  - Test: `ordersThePreviousRevisionPlacedBetweenV37AndV38AreQueuedForRepair` (migrates to V37, places a flier, a tie and a uniform order as the previous revision, then migrates on).
- **Fixed (low): V38 cannot deadlock with the previous revision, and never stalls order writes.** The previous revision writes `catalog_orders` and the old queue in both orders, and V38's DDL needs SHARE ROW EXCLUSIVE on both.
  - V38 now takes both locks first, together, with `NOWAIT`. When either table is busy it releases both and tries again, for up to 15 seconds.
  - It then stops with a message saying nothing was changed and the migration job can be re-run.
  - `lock_timeout = 15s` bounds every later wait.
  - The runbook's "If V38 fails with a lock error" says so.
- **Fixed (low): the school order-code suggestion now derives exactly as the server does.**
  - The fallback for fewer than 2 initials now takes the first word that is not a stop word ("The Heritage" → HER, not THE), and pads with X ("X" → XX).
  - Accented letters are dropped, as `SchoolOrderCode` and tenant_school V31 drop them ("École" → C), instead of folded.
  - Tests: `codes.test.ts`.

### Contract release: retiring the rollout bridges

PR #382 left bridges for the one release in which an older revision could still serve beside it. This release drops the ones that no environment needs any more. It keeps the ones prod's first release with the item order workflows still needs, and says exactly when each may go. It also closes the paths that could still leave a placed order of a coded category without a number, which is what the kept placement trigger compensates for. Branch `chore/retire-sop-rollout-bridges`; migrations catalog V40 and V41 (school-core) and workflow V22 (operations).

**Who can still serve.** Checked before anything was dropped:
- Dev serves ed8aeba5 on school-core, operations and platform (100 % of traffic, image tag `sha-ed8aeba5…`).
- Prod serves origin/main (88fbf4ad). Its school-core has no creation queue and never mints. Its operations has no tracker, no `workflow_instance_steps`, no `/order-workflows` routes and no `/notebook-trackers` aliases (`git grep` on origin/main finds none of them).
- Dev data, read-only under the bypass on 2026-11-13: `catalog.notebook_tracker_creation_queue` 0 rows; `order_workflow_creation_queue` 0 rows; 0 non-blank `stage_note` values; 0 placed orders of a coded category without a number.

#### Dropped

- **The old creation queue (catalog V40, `V40__retire_notebook_tracker_creation_queue.sql`).**
  - *Before.* V38 kept `catalog.notebook_tracker_creation_queue` with a `BEFORE INSERT` trigger that forwarded every row into `order_workflow_creation_queue`, for a school-core older than ed8aeba5.
  - *Now.* V40 drops the table (its trigger, policy, index and foreign key go with it), then `catalog.forward_to_order_workflow_creation_queue()`.
  - *Leftover rows.* The table holds a row only if a writer bypassed the trigger (`session_replication_role = replica`, a disabled trigger, a data load). V40 first forwards any such row exactly as the trigger would have: a new row with fresh retries; a row already queued is due again now with fresh retries, keeping its `repair` flag and last error (`V40:41-49`, the conflict rule of `V38:99-100`). It logs the order ids it moved (NOTICE).
  - *Orphaned leftover rows.* Replica mode also skips the old table's foreign key, so a leftover row can name an order that does not exist, or not at that school. The trigger could never have forwarded such a row (the new queue's foreign key refuses it), and `ON DELETE CASCADE` would have removed it with its order. V40 forwards only rows whose `(order_id, school_id)` exists in `catalog_orders` and drops the others with the table, naming them in a WARNING (`V40:35-37`, `:44`, `:56-58`): "V40: dropped rows left in catalog.notebook_tracker_creation_queue whose order does not exist at that school: CK-506 (school 2), CK-999 (school 1)". *Before this fix* one such row failed V40 with a bare foreign-key violation on every re-run.
  - *Bypass.* V40 sets `app.bypass_rls = 'on'` (`V40:17`). Without it the migration owner sees no row under FORCE RLS, and the drop would silently discard every leftover row. The test below shows that.
  - *Locks.* Dropping the table drops its foreign key's integrity triggers on `catalog_orders`, so V40 needs ACCESS EXCLUSIVE on both tables. V38's own comment, "they cost nothing" (`V38:27`), understated this. V40 takes both locks together with `NOWAIT`, releases them when either is busy, and retries for 15 seconds (`V40:19-30`). Then it stops: "nothing was changed, re-run the migration job". Readers are never queued behind the wait.
- **The retired stage-note columns (workflow V22, `V22__retire_stage_note_columns.sql`).**
  - *Before.* `workflow_instance_steps.stage_note`, `stage_note_saved_at` and `stage_note_saved_by` stayed after V20, and V21's trigger `copy_retired_stage_note` copied each note an old revision still saved into the stage's entries.
  - *Now.* V22, in one transaction:
    1. sets the bypass and a 15 s `lock_timeout`, and takes ACCESS EXCLUSIVE on `workflow_instance_steps` with the same `NOWAIT` retry loop (`V22:14-33`);
    2. repeats V21 step 3a's copy, verbatim (`V22:35-45`). It copies each authored note that is in no entry yet, once;
    3. stops and names every note still in no entry, as V19 and V21 stop on what they cannot map (`V22:47-68`). Such a note has no recorded author, so V20 and V21 never copied it. The message names `instance <id> (order <CK-…>) step <n>` and says nothing was changed. It then says: as the migration owner, in a transaction that first runs `SET LOCAL app.bypass_rls = 'on'`, copy the note into `workflow_internal_notes` with its author, or clear it; commit; re-run the job;
    4. drops the trigger, then its function, then the three columns (`V22:70-76`). The trigger depends on the columns, so it goes first.
  - *Why the message names the bypass.* Both tables force row-level security. Without the bypass the migration owner sees no row: its `UPDATE … SET stage_note = NULL` changes 0 rows with no error, and its `INSERT` into `workflow_internal_notes` is refused. Following the earlier message as written fixed nothing, and the re-run stopped again.
  - *Not touched:* `catalog.product_order_workflow_steps.stage_note` (catalog V36). It is another table, and ed8aeba5 still reads it (`NotebookTrackerCreationQueue.withImportedProgress`).
  - On prod's first release the table is created empty in the same job (workflow V8), so steps 2 and 3 find nothing.
- **The one-release internal-path aliases (design D6).**
  - *Before.* Operations served `POST /api/v1/internal/notebook-trackers` and `POST /api/v1/internal/notebook-trackers/{orderId}/image-access` beside the order-workflow paths. School-core's `OperationsTrackerClient.imageAccess` asked the alias again on a 404 or an empty-body 403.
  - *Now.*
    - `OrderWorkflowMachineController` maps only `/order-workflows` and `/order-workflows/{orderId}/design-file-access`.
    - The two alias lines are gone from `MachineCallerFilter.callerProperty`, in all five identical copies (billing, identity, operations, platform, school-core). `scripts/check-duplicate-class-drift.py` reports 5 identical copies. An alias path is now an unregistered internal route: 403 before any controller, even for school-core's account.
    - `OperationsTrackerClient.imageAccess` asks the design-file route once. A 404 or a bare 403 now fails closed like any other refusal.
    - `scripts/audit-service-authorization-boundaries.ps1` drops the two alias contracts. `scripts/tests/service_authorization_machine_proof_test.py` drops their cases.
    - `services/api-gateway/api-route-inventory.json` is regenerated: 500 endpoints, 21 internal (was 502 and 23). No OpenAPI contract or generated client named the aliases.
  - *Why it is safe.* Only an operations older than ed8aeba5 had the alias without the new path. Only a school-core older than ed8aeba5 called the alias. Neither can serve after this release (see *Rollback*). Prod's origin/main has neither path, so the alias never helped prod. In prod's first release the new school-core's design-file checks fail closed (503) until the new operations serves, with or without the alias.

#### Kept, and when each may go

- **V38's placement trigger, `trg_catalog_orders_queue_unnumbered_placement`, with `catalog.queue_unnumbered_placement()`.**
  - *Why.* During prod's first release with the item order workflows, origin/main's school-core keeps serving through the canary. It places orders, and moves them out of DRAFT by superadmin's status override, without minting. The trigger queues each for repair at commit. V38 step 2b only catches orders placed before V38 took its locks.
  - *When it may be dropped.* In a later migration, only when both hold. V40 records this on the function and the trigger (`COMMENT ON`, `V40:65-76`):
    1. every school-core revision that can serve, in every environment, mints on the legacy status update (this release or later; see *Fixed* below);
    2. prod's first release with the item order workflows has completed its school-core canary, and the rollback target is that release or later.
- **The `repair` column of `order_workflow_creation_queue` and the repair code (`NotebookTrackerCreationQueue.repair`).** They stay while repair rows can be written. Two writers can write them: V38's placement trigger (above), and, since catalog V41, `catalog.apply_legacy_catalog_mappings()` (see *Added* below). So they stay as long as that function exists, even after the placement trigger goes. Prod's V38 step 2b also creates repair rows at promotion.
- **Reporting V36's trigger `trg_reporting_event_inbox_catalog_order_number`.** Not touched. It fills `orderNumber` for a projector that does not write it. It may go only when both hold:
  1. no platform revision older than ed8aeba5 can serve in any environment. Reporting V36 came with PR #382 (89d14a36), so dev's platform at 53f75ef6 predates it, and dev can still roll platform back there. This release declares rollback below ed8aeba5 unsupported for school-core and operations only, not for platform. Dropping the trigger would make that rollback silently lose order numbers in reporting; so either keep the trigger, or declare that rollback unsupported in the same release;
  2. prod's first release with the item order workflows has completed its platform canary, and the rollback target is that release or later (origin/main's platform projector ignores `orderNumber`).
- **The V36 tables `catalog.product_order_workflows` and `product_order_workflow_steps`.** Not touched. ed8aeba5 still reads them (`ProductOrderWorkflowService`, `NotebookTrackerCreationQueue.withImportedProgress`).
- **A create without `definitionId` is still a notebook order** (`OrderWorkflowService.item`). No caller sends one any more: every school-core that can serve sends the template. It is input tolerance, not a route, so it stays; removing it is a follow-up.

#### Fixed: superadmin's status override places the order when it takes it out of DRAFT

- *Before.* `CatalogReadRepository.updateOrderStatus`, for an order of the older form (`form_version != 2`), ran `UPDATE … SET status = :status` and nothing else. Superadmin reaches it through `PATCH /orders/{id}/status`, `PATCH /api/v1/supply|sa/orders/{id}/status` and the All orders "Update status" select (`SaAllOrdersPanel.tsx`). A draft of a coded category (for example a legacy `NOTEBOOKS` or `TIES` draft) left DRAFT with no number, no `placed_at` and no `placed_by`. Only V38's trigger queued it for repair, so its event went out without a number and its number came a drain later, dated by the drain.
- *Now.* It reads the status under the order's row lock (`SELECT … FOR UPDATE`, `CatalogReadRepository.java:456`). Only when the override takes a draft out of DRAFT does it place the order as `placeOrder` does (`:377-423`):
  - it records the placement: `placed_at = COALESCE(placed_at, now)`, `placed_by = COALESCE(placed_by, superadmin)` (`:464`);
  - it calls `OrderNumberAllocator.mint(jdbc, id)` in the same transaction (`:472`). The number is dated by that placement. The event and outbox row it then emits carry the number. The trigger finds the order numbered at commit and queues nothing.
  - `mint` does nothing for a category without an order code, or an order that already has a number. It serialises the running number as a placement does: the update holds the order's row lock, then the counter row is taken with a single-row upsert and held until commit.
- *Any other override changes the status only, and numbers nothing.* An order that left DRAFT earlier without a number keeps none:
  - one placed before its category had an order code stays unnumbered (by design; see the sweep below);
  - one the previous revision (origin/main) placed is numbered by its repair, not by the override.
  - *Why.* An earlier version of this fix minted after every override. A routine override such as PROCESSING → DELIVERED then quietly numbered an old order in its placement year's series while its siblings stayed unnumbered.
- *Also fixed: a legacy order created already placed.* `CatalogReadRepository.createOrder` accepts any status. The controllers always pass DRAFT (`CatalogReadController.java:158`, `CatalogPublicCompatibilityController.java:114`), but the method mints after its insert now (`:361`). It already records `placed_at` for a non-DRAFT insert. V38's trigger fires only on an update out of DRAFT, so nothing else would ever number such an order.

#### Added: the legacy catalog consolidation queues its placed orders for their number (catalog V41)

- *Before.* `catalog.apply_legacy_catalog_mappings()` (catalog V8) is still live: an operator runs it by hand (`docs/operations/database-consolidation-runbook.md` §2, `SELECT * FROM catalog.apply_legacy_catalog_mappings()`). It inserts each mapped `catalog.supply_orders` row into `catalog_orders` with `status = upper(source.status)` and `category = upper(source.category)` (`V8:82-97`), and never sets `order_number`. A legacy `Notebooks` order of status `Placed` became a placed `NOTEBOOKS` order (code NB) without a number, for good. V37 numbered only the orders placed before it. V38's trigger fires only on `UPDATE OF status` out of DRAFT (`V38:130-135`), never on this INSERT. D13 was broken for these orders.
- *Now.* `V41__legacy_catalog_mappings_queue_placed_orders_for_numbers.sql` replaces the function with the same body, except:
  - the supply-order INSERT returns its rows (`V41:34-65`);
  - a second statement in the same `WITH` queues each inserted order that is not DRAFT and whose category has an order code for repair, by V38 step 2b's rule: `ON CONFLICT (order_id) DO UPDATE SET repair = true` (`V41:66-75`).
- The next drain mints its number (dated by the legacy order date), tells reporting, and drops the row: a legacy order (`form_version` 1) runs on no workflow. Ledger updates and the result row are unchanged.
- `CREATE OR REPLACE` keeps V8's owner-only privileges and takes no table lock. Its `COMMENT` says to keep the repair column and code while the function exists (`V41:129-132`).
- *Not re-queued:* orders the function migrated earlier. V37 numbered every placed coded order when it ran. Dev had no placed coded order without a number on 2026-11-13 (read-only, under the bypass). Prod runs V37 to V41 in one migration job. Neither dev nor prod was queried again for `legacy_catalog_migration_map` rows (this work had no database access).

#### Sweep: every write that can leave a placed order of a coded category without a number

- *Fixed in this release:*
  - `CatalogReadRepository.updateOrderStatus`, legacy branch (above);
  - `CatalogReadRepository.createOrder` with a non-DRAFT status (above);
  - `catalog.apply_legacy_catalog_mappings()` (catalog V41, above).
- *Already mint:* `placeOrder` (legacy) and `CatalogOrderFormService.transition` with PLACE (`CatalogReadRepository.java:423`, `CatalogOrderFormService.java:273`). `NotebookTrackerCreationQueue.repair` mints; its other updates change only `attributes`.
- *Never take an order out of DRAFT:*
  - `CatalogOrderFormService.create` always inserts DRAFT;
  - `transition`'s other actions, `returnOrder`, `applyTrackerStatus` and `assetChanged` start only from non-DRAFT statuses (the last two refuse or skip DRAFT);
  - the legacy `markDesignApproved`, `approveBySuperadmin`, `markDelivered` and `returnBySuperadmin` also start only from non-DRAFT statuses. A placed order keeps its number (catalog V37's trigger refuses a change).
- *One-off, before V37:* catalog V1's copy of `public.catalog_orders` (`V1:118-138`). V37 numbered those.
- *Local only:* `scripts/ensure-local-dev-users.ps1:337` inserts local probe orders of `NOTEBOOK`, a category without an order code, with no status.
- *No other writer:* no other service writes `catalog.catalog_orders`. The legacy retirement scripts (`scripts/generate-legacy-public-retirement-*.ps1`) only drop `public.*` tables.
- *By design: a category given an order code later.* This is not a write to `catalog_orders`, but it changes which orders count as coded.
  - Superadmin can set, change or clear a category's `orderCode` on an existing category: Catalog manager (`ProductCatalogManager.tsx:94`), `PATCH /api/v1/supply/product-catalog/categories/{code}`, `ProductCatalogRepository.update`.
  - Once an uncoded category (for example UNIFORMS) gets a code, its orders placed before stay placed and unnumbered. Nothing numbers them.
  - This is the rule, not a gap. A number is minted at placement (D11) and only when the category has a code then (D8: "A category with no code gets no order number"). `ProductCatalogRepository.java:265` says "Changing the code affects only numbers minted afterwards", as D12 says of school codes. Numbering them later would give old placements running numbers after newer ones. Through the repair it would also move an open structured order onto a workflow mid-flight. That is a product decision for the owner, not part of this release.
  - The legacy override no longer numbers such an order as a side effect (above). `aCategoryGivenAnOrderCodeNumbersOnlyItsLaterPlacements` pins the rule.
  - So "placed orders of coded categories without a number = 0" is a point-in-time fact, not an invariant. After a category gains a code, the count can rise legitimately.

#### Rollback consequences

- **Dev, below ed8aeba5: unsupported from this release on.**
  - School-core at 53f75ef6 enqueues into `notebook_tracker_creation_queue` inside the placing transaction. After V40, every notebook placement and every hand-started workflow fails with "relation does not exist", and so do its drain and its health query.
  - Operations at 53f75ef6 selects `stage_note` in every step read. After V22, every tracker read fails.
- **Dev, to ed8aeba5: supported.** Neither service at ed8aeba5 reads a dropped object. Its school-core asks the new design-file path first, which this release's operations serves. Its operations still registers the aliases, which nothing calls. A school-core at ed8aeba5 does not mint on the legacy status override, which is why V38's placement trigger stays (condition 1).
- **Prod, to origin/main: unaffected.** Main names none of the dropped objects or paths. Its placements without numbers are queued by the kept trigger and wait for the next roll-forward, as before.
- **Catalog V41 and the override fix change nothing for a rollback.** No revision calls `apply_legacy_catalog_mappings()`; an operator does. The repair rows it writes are drained by every school-core from ed8aeba5 on; under origin/main they wait for the roll-forward, like the trigger's. A school-core at ed8aeba5 overrides without placing or minting, and the kept trigger repairs it.
- **Deploy order.** Either service may deploy first on dev: every school-core that can serve calls only the order-workflow paths, and every operations that can serve has them.

#### Tests

All Java runs with `clean`. Each new test that is meant to catch a defect was run against the code without the fix, and failed.

- `CreationQueueRetirementMigrationTest` (school-core, 6), pinned to catalog V40 since V41 follows. Migrations run as a non-superuser, non-BYPASSRLS owner, as in every deployed environment.
  - `aBusyCatalogOrdersTableStopsTheMigrationWithinAboutFifteenSecondsHavingChangedNothing`: a reader holds `catalog_orders`; V40 retries about 15 s, stops with its message, and leaves the old table, its 4 rows and Flyway at 39.
  - `theOldQueueItsForwardingTriggerAndItsFunctionAreGone`.
  - `rowsLeftInTheOldQueueAreForwardedExactlyAsItsTriggerWouldHaveForwardedThem`: rows written with `session_replication_role = replica`, in two schools; a new row gets fresh retries, a dead-lettered repair row is revived and keeps `repair` and its last error. *Without V40's bypass this failed:* CK-501's row was dropped with the table and CK-502 stayed dead-lettered.
  - `rowsLeftInTheOldQueueForAnOrderThatDoesNotExistAreDroppedNotForwarded`: replica-mode rows for CK-999 (no such order) and CK-506 at the wrong school are dropped, not forwarded, and CK-506 itself is untouched. *Without the fix the whole class failed in `@BeforeAll`:* "insert or update on table order_workflow_creation_queue violates foreign key constraint". The Flyway log shows the WARNING naming both.
  - `thePlacementTriggerStaysAndStillQueuesAnUnnumberedPlacementForRepair`: as `ims_school_core_rt`, origin/main's placement is queued with `repair`; a minting placement and an uncoded category are not. Both comments state the drop conditions.
  - `theNewQueueKeepsItsRowLevelSecurityAndTheRuntimeRolesKeepTheirPrivileges`: FORCE RLS and `tenant_isolation`; `app_rt` and `ims_school_core_rt` hold the same privileges on every catalog table after `afterMigrate`; another school's row is refused.
- `StageNoteRetirementMigrationTest` (operations, 7). Same owner set-up.
  - `aBusyStepsTableStopsTheMigrationWithinAboutFifteenSecondsHavingChangedNothing`.
  - `aNoteWithNoRecordedAuthorStopsTheMigrationNamedAndNothingIsChanged`: names `instance <id> (order CK-403) step 3` only, and tells the operator to work "As the migration owner, in a transaction that first runs SET LOCAL app.bypass_rls = 'on'"; columns, trigger, function and Flyway 21 are unchanged; the copy rolled back. *Without the guard this failed:* V22 migrated and dropped the note. *Against the earlier V22 message it failed* on the bypass sentence.
  - `theRemedyTheMessageGivesNeedsTheBypassItNames`: as the migration owner without the bypass, the clear updates 0 rows and the copy is refused ("row-level security"). The set-up then follows the message exactly, as the owner, with `SET LOCAL app.bypass_rls = 'on'`, and re-runs the job.
  - `theTriggerItsFunctionAndTheColumnsAreGone`, after that re-run.
  - `everyNoteIsAStageEntryOnceWithItsAuthorAndTime`: an authored note written with V21's trigger bypassed is copied once with its author and time; notes already in entries are not duplicated; a blank note is ignored. *Without V22's bypass this failed:* the note was lost and the author-less note was dropped silently.
  - `theServingRevisionsStepQueriesStillWorkAsTheRuntimeRole`: `OrderWorkflowRepository.findByOrder`, `steps` and `saveStepData` as `app_rt`.
  - `theDedicatedRuntimeRoleKeepsTheRuntimeRolesPrivilegesOnTheSteps`.
- `ProductOrderWorkflowIntegrationTest` (runtime role, forced RLS, real transactions, V38's trigger present):
  - `aLegacyStatusOverrideOutOfDraftMintsTheNumberInThatTransaction`: a legacy TIES draft overridden to IN_PROGRESS gets `placed_at` and `placed_by` 99 (superadmin), returns and stores `TIE-SA_IN-<its placement day>-0001`, queues no repair row, and its event carries the number. UNIFORMS gets none. An already-numbered notebook keeps its number. A TIES order placed a month ago without a number, overridden to DELIVERED, stays unnumbered and keeps its placer. Counters end at NB=1, TIE=1. *Without the fix it failed:* against ed8aeba5 the TIES number was null; against cc85c058 (which minted after every override) it failed at "an order that left DRAFT earlier is not numbered by a later override" ("expected: null but was TIE-SA_IN-26-10-15-0002"); with only the placement recording removed it failed at "the override recorded the placement".
  - `aCategoryGivenAnOrderCodeNumbersOnlyItsLaterPlacements`: superadmin gives UNIFORMS the code UNF through `ProductCatalogRepository.update`. An order placed before stays unnumbered, even after an override to DELIVERED; the next placement is `UNF-SA_IN-…-0001`. *Against cc85c058 it failed:* "expected: null but was UNF-SA_IN-26-11-14-0001".
  - `aPlacedLegacySupplyOrderTheConsolidationMigratesIsNumberedByTheNextDrain`: a legacy `Notebooks`/`Placed` supply order mapped to school 1 and applied by the function is queued for repair; the drain numbers it `NB-SA_IN-26-03-02-0001` (its legacy order date), creates no workflow, drops the row and tells reporting. *Without V41 it failed:* no queue row.
- `LegacyCatalogConsolidationIntegrationTest.aPlacedOrderOfACodedCategoryIsQueuedForItsNumber`: placed Notebooks and delivered ` ties ` are queued with `repair`; a draft and a stationery order are not; a re-run queues nothing. *Without V41 it failed:* nothing was queued.
- `CatalogOrderEventEmissionIntegrationTest.aLegacyOrderCreatedAlreadyPlacedIsNumberedAtOnceAndTheEventCarriesIt`. *Without the fix it failed:* the number was null.
- Aliases:
  - `MachineCallerFilterTest.theRetiredNotebookTrackerAliasesAreRefusedEvenToTheirFormerCaller`, in all five services; `localTrustNeverOpensAnUnregisteredOrNoncanonicalInternalRoute` now lists the alias paths. Both failed against the ed8aeba5 filter.
  - `OrderWorkflowMachineControllerTest.theRetiredNotebookTrackerPathsAreGone` (failed against the ed8aeba5 controller) and `aCreateWithoutATemplateIsPassedOnWithNone`.
  - `PeerClientsTest.imageAccessNeverAsksTheRetiredNotebookTrackerAlias` (failed against the ed8aeba5 client: it asked the alias and was allowed) and `imageAccessFailsClosedOnEveryOtherRefusal`.
- Pinned, not weakened:
  - `ItemOrderNumberMigrationTest` now migrates catalog to V39, not the latest. Its rollout-window tests use the old queue V40 drops.
  - `ProductOrderWorkflowIntegrationTest.anOrderThePreviousRevisionPlacedIsNumberedMovedOntoItsWorkflowAndCreated` no longer inserts into the dropped table. V38's trigger still queues all three placements for repair, and the rest of the test is unchanged. The forwarding itself stays covered at V39 by `ItemOrderNumberMigrationTest`. The class stays at the latest schema: its other 19 tests cover current behaviour.
  - `OrderWorkflowRolloutWindowMigrationTest` and `OrderWorkflowMigrationTest` were already pinned to workflow V21 and V20.
- Full suites, `./mvnw -B -fae clean test` from the root, at the branch head: billing 101, identity 225 (1 skipped), operations 550, platform 520, school-core 1246 (1 skipped); BUILD SUCCESS, no failures or errors. `MigrationOnlyMainIntegrationTest` runs catalog V40 and V41 and workflow V22 on empty schemas as the migration-only owner.
- Checks, at the branch head: `node scripts/generate-api-route-inventory.js --check` (current, 500 endpoints); `node scripts/generate-openapi-typescript-client.js --check` (current, 16 domains, 161 canonical operations); `python scripts/check-duplicate-class-drift.py` (all 10 guarded classes consistent). Run at cc85c058, before the review fixes, which touch no contract, route, gateway or filter: the generator and gateway contract `node --test` suites (29 pass), the gateway suite (117 pass), and `python -m unittest scripts/tests/service_authorization_machine_proof_test.py` (22 pass, Windows PowerShell 5.1).

#### The runbook

`docs/runbooks/notebook-tracker-deploy.md` is updated in the same release (see "Runbook rewrite" below): the machine-route table, the contract release's migrations (V40, V41, V22), V40's NOTICE and WARNING, V22's stop with the bypass, the point-in-time nature of "placed orders of a coded category without a number", and the legacy consolidation's repair rows (also in `docs/operations/database-consolidation-runbook.md` §2).

#### Corrections to earlier statements in this document

- **D6 (`:94`).** "Kept: the creation-queue table name, `catalog.notebook_tracker_creation_queue`." False since catalog V38, which moved the queue to `catalog.order_workflow_creation_queue` (the deviation at `:1747`). Catalog V40 drops the old table.
- **D6 (`:97`), the §3.5 table (`:840-841`), Out of scope (`:446`), WP2 acceptance (`:1182`), the risk table (`:1335`), `:1438`, `:1477-1483`, `:1541`, `:1775-1779` and `:1840`.** The `/notebook-trackers` alias paths and school-core's fallback to them are gone in this release. Either service may still deploy first on dev, because both directions now use the order-workflow paths. The V36 tables in `:446` are still kept.
- **D38 (`:379`).** "The old `stage_note` columns are kept but no longer used." Workflow V22 drops them.
- **§3.2 (`:732`).** V38 inserts into `order_workflow_creation_queue`, not `notebook_tracker_creation_queue`.
- **`:1724`.** The stage-note clean-up is done: workflow V22.
- **`:1751`.** "This release mints in the placing transaction, so its placements never match." False for one path: ed8aeba5's superadmin status override on a legacy order took a draft out of DRAFT without minting or recording the placement, and relied on the trigger. Fixed in this release (see *Fixed* above).
- **D13 (`:168-172`) and `:1765` ("D13 now holds for them").** D13 did not hold for every path. The legacy catalog consolidation (`catalog.apply_legacy_catalog_mappings()`) inserted placed orders of coded categories without a number, and nothing queued them. Catalog V41 fixes that. D13 also holds only for orders placed while their category had a code: a category given a code later keeps its earlier orders unnumbered, by design (D8, D11).
- **`:1761` and `:1833`.** The plan to drop the placement trigger and `catalog.queue_unnumbered_placement()` "next release" is withdrawn. Only the old table, its forwarding trigger and its function are dropped (catalog V40). The placement trigger stays until the two conditions under *Kept*. The runbook's ready-made clean-up SQL, which starts with `DROP TRIGGER IF EXISTS trg_catalog_orders_queue_unnumbered_placement`, must not be used; the runbook is updated separately.
- **`:1837`.** The clean-up rehearsed there "dropped both triggers"; the real one keeps the placement trigger.
- **`V38:27` (a migration comment, not editable).** "They cost nothing once that revision is gone": dropping the old table needs ACCESS EXCLUSIVE on `catalog_orders`, which V40 takes with a `NOWAIT` retry loop.

### Runbook rewrite

`docs/runbooks/notebook-tracker-deploy.md` is now "Order workflows (formerly the notebook order tracker): deploy,
probe and clean up". The file name is kept, since other documents link to it.

- **Routes.**
  - *Before.* It listed the tracker's machine routes, with the alias as the create path.
  - *Now.* Every `/item-orders`, set-up and vendor route, with who may call it. The school-core routes the SOP release
    changed. The machine routes, with the alias marked until the contract release. The 25 removed `/notebook-orders/**`
    routes. The `?panel=` screens.
- **What each release carries.** The SOP release's migrations, per service, with their locks and stop-and-name
  guards. The contract release. Prod's first release as one job per service while `main` serves.
- **Windows.** What serves between gateway → identity → frontend → school-core → operations → billing → platform, on
  dev's direct path and through prod's 5/25/50 % canaries. For example, in prod design-file changes answer 503 from
  school-core's canary until operations serves, because `main`'s engine has neither the new path nor the alias.
  - A dev run that is cancelled (dev runs are cancel-in-progress) or stops on a failure leaves its window open. The
    next push deploys only what it changed, so the runbook closes the window with a dispatch on the dev head
    (`commit_sha`), which includes every service. Do not merge to `dev` while a release rolls out.
- **Migration job failures.** Where the reason is (only the Cloud SQL PostgreSQL log), which messages are safe to
  re-run (the `stayed busy` messages of V38, V40 and V22; lock timeouts) and which are data problems (the stop-and-name
  lists of V17, V19, V21 and V22).
  - One case is not "nothing later deploys". In prod's Cloud Deploy path, operations and billing run in one wave, and
    operations' rollout exists before billing's migration job runs. If billing's job fails, operations stays at its
    5 % canary phase, and nothing advances it. Settle that rollout before re-running.
- **Post-deploy checks prove only what they can.**
  - Without a session, every `/api/v1/workflows/**` path answers 401, including the removed `/notebook-orders`, and
    `/api/v1/internal/**` answers 404. So an unsigned probe cannot show a route is gone. A signed-in superadmin call
    can, by copying a request from the browser's developer tools.
  - `OWNER_MIGRATION_RESULT` lines (`textPayload`). `MigrationOnlyMain` hides a failure's reason, which is only in the
    Cloud SQL PostgreSQL log.
  - The operations startup line `Order workflow templates match the database`.
  - ERROR and event queries on `jsonPayload`.
  - A read-only SQL bundle under the bypass: schema heads, queue state, pending orders, placed orders without a
    number, facts without a number, bridges present.
- **Clean-up corrected.**
  - *Before.* The runbook's clean-up SQL began `DROP TRIGGER IF EXISTS trg_catalog_orders_queue_unnumbered_placement`,
    with no lock handling and no row guard.
  - *Problem.* Prod's `main` and the SOP legacy status update both place orders that only that trigger numbers.
  - *Now.* The runbook gives conditions, a read-only pre-check, and the two final migrations (placement trigger;
    reporting number copy). Each uses the V38 NOWAIT lock loop in ACCESS EXCLUSIVE mode and drops without `IF EXISTS`. The
    reporting one makes a last copy under the bypass first. The catalog one has no sweep (see below).
  - *The pre-check reads logs too.* SQL shows only what is queued now, and a repair row lives about a minute. So the
    pre-check also reads school-core's INFO line `… was placed without an order number and is repaired` since
    `MINT_FIX_FROM`. It repeats the read once per `_Default` retention period, so the windows cover the whole time.
- **Evidence.** Every SQL block in the runbook ran on scratch PostgreSQL 16 databases (2026-11-14). The migrations were
  applied as a NOSUPERUSER, NOBYPASSRLS owner, with `app_rt`'s grants and each schema's afterMigrate.
  - *Dev's path.* The real tenant_school V1–V31, catalog V1–V39, workflow V1–V21 and reporting V1–V36. Then the
    contract release's real catalog V40 and workflow V22 (`cc85c058`), then the final clean-up. Without the bypass the
    owner saw 0 of 7 orders. V22 stopped and named the one author-less note, changing nothing; it applied once the note
    was cleared.
  - *Prod's path.* `main`'s heads (tenant_school V30, catalog V26, workflow V7, reporting V35), where the prod
    pre-count ran. Then every later version through V40 and V22, one run per schema: 31/1, 40/14, 22/15 and 36/1, as
    the runbook's table says.
  - *Locks.* With a reader holding `catalog_orders`, then `workflow_instance_steps`, for 20 s, V40 and V22 each stopped
    after 16–21 s (two runs) with its `stayed busy` message and was not applied. A new reader with a 1 s statement
    timeout, started during the retries, was answered each time. With a 3 s reader, each finished in 3–5 s. On
    2026-11-13 the final clean-up's catalog migration behaved the same way.
  - *Not real.* Flyway itself did not run: psql applied each version in one transaction, and the test filled history
    tables of Flyway's shape. The legacy status update was its SQL statement, run as `ims_school_core_rt` in a request
    scoped to one school, not the Java code.
  - The unsigned probes ran against dev on 2026-11-13 19:03 UTC.
- **Prod finding.** Prod's `main` has no catalog V36, so V38 moves prod's open structured orders of template categories
  with no progress to import. They restart at step 1, and their status becomes `ORDER_DETAILS` once operations pushes,
  whatever approval or production state they were in. The runbook's pre-count lists them for the owner before the
  promotion.

- **No sweep in the final placement-trigger clean-up (integration fix).** The runbook's draft of the final catalog migration repeated V38 step 2b: queue every placed, unnumbered order of a coded category for repair. After this release that would be wrong. An order placed before superadmin gave its category an order code stays unnumbered by design (D8, D11; `aCategoryGivenAnOrderCodeNumbersOnlyItsLaterPlacements`), and the repair would number it. Once the conditions for that clean-up hold, no path places an order without minting, so the sweep has nothing legitimate to find. The runbook's pre-check (query 1) and post-deploy query 4 now say the same: such orders are expected there, and a rise with no category edit is the defect.
- **The final clean-up takes the next free numbers.** The runbook's scratch drafts were numbered catalog V41 and reporting V37; catalog V41 is now the consolidation fix, so the real ones take catalog V42 or later.
