# Order workflows (formerly the notebook order tracker): deploy, probe and clean up

This runbook covers a release of the order workflows to `custoking-dev` or `custoking-prod` (Cloud Run, `asia-south2`):
what each release carries, the order in which services change and what users see in between, the checks that prove a
release worked, what to do when it does not, and the clean-up of the rollout bridges. The design is
[item order workflows to the client SOP](../product/item-order-workflows-sop-v1.md). "The engine" below is the order
workflow engine in operations-service that every item's workflow runs on; it grew out of the notebook order tracker.
The file keeps its old name because other documents link to it.

- **Status on 2026-11-13.** Every dev service has served the SOP release (PR #382, `ed8aeba5`) since that day. The
  [contract release](#what-the-contract-release-removes), which removes the one-release bridges, comes next. Production
  is on hold (owner decision, 2026-11-12); it serves `main` or older, with none of this. See [Production](#production).
- Every cloud command passes `--project`: the operator's default project may be production. Times are UTC.
- The `gcloud logging read` commands are written for Bash (Git Bash or Cloud Shell). Windows PowerShell 5.1 does not
  escape the double quotes inside a filter when it calls a native program, so a filter pasted there can lose them. The
  `.ps1` scripts run from PowerShell as before.
- Never write a password, token or cookie into this file, a ticket or a chat.

## Routes

### Browser routes

Browser calls go through the gateway, or through the frontend URL, whose nginx forwards `/api/v1/` to the gateway. The
gateway does not rewrite paths. Without a session, every path below answers **401** `{"message":"Unauthorized"}` from
the gateway before any service is asked, whether or not the service still has that route.

**Operations**, base `/api/v1/workflows` (contracts `orderWorkflowClient.v1` and `vendorDirectoryClient.v1`):

- *read* means superadmin, or an OPERATIONS user holding `notebook-tracker:read` whose scope covers the order's school
  and category. An order outside that scope answers 404 `Order workflow not found`, the same as an order with no
  workflow.
- *act* means the same with `notebook-tracker:act`.
- School admins get 403 on every route here. They see an order's status through school-core only.
- A superadmin-only action asked by anyone else answers 403 `SUPERADMIN_ONLY`.

| Method and path (`…` is `/item-orders/{orderId}`) | Who |
| --- | --- |
| `GET /item-orders` (`item`, `step`, `phase`, `assignee`, `schoolId`, `sla`, `q`, `includeFinished`, `sort`, `limit`, `offset`) | read |
| `GET /item-orders/templates`, `GET /item-orders/templates/{definitionId}` | read |
| `GET …`, `GET …/activity`, `GET …/calls`, `GET …/whatsapp-groups` | read |
| `PUT …/steps/{step}/data`, `POST …/steps/{step}/complete` | act, and assigned to that stage |
| `POST …/notes`, `POST …/calls` | act |
| `PUT …/vendor-selections` | Step 5's assignee while it is active, or superadmin |
| `PUT …/designer`, `PUT …/contacts`, `PUT …/whatsapp-groups/school` | the current stage's assignee, or superadmin |
| `POST …/claim` | an eligible operator, on an unassigned order (superadmin gets 409) |
| `POST …/steps/{step}/reopen`, `POST …/steps/{step}/sign-off`, `POST …/head-approval/approve`, `POST …/head-approval/request-changes`, `PUT …/steps/{step}/assignee`, `PUT …/assignee`, `PUT /item-orders/templates/{definitionId}/steps/{step}/sla` | superadmin |
| `GET /designers`, `GET /time-slots/{schoolId}?definitionId=`, `GET /vendor-categories`, `GET /vendors`, `GET /settings/internal-approval-group` | read (operators see active vendors only) |
| `POST /designers`, `PUT /designers/{id}`, `PUT` and `DELETE /time-slots/{schoolId}/{definitionId}/{step}`, `PUT /vendor-categories/{code}`, `POST /vendors`, `PUT /vendors/{id}`, `PUT /settings/internal-approval-group` | superadmin |
| `GET /school-whatsapp-groups/{schoolId}`, `PUT /school-whatsapp-groups/{schoolId}/{target}` (paths unchanged) | superadmin, or an operator assigned to the whole school / superadmin. Since SOP the keys are only `SCHOOL_GROUP` and `INTERNAL_APPROVAL` |

Controllers: `OrderWorkflowController`, `OrderCallLogController`, `OrderActivityController`,
`WorkflowSettingsController`, `VendorDirectoryController`, `SchoolWhatsAppGroupController`.

**School-core**, changed by the SOP release. `CatalogOrderFormController` serves both `/api/v1/catalog/orders` and
`/api/v1/supply/orders`; `…` below is either.

| Method and path | Who | Change |
| --- | --- | --- |
| `GET …/by-number/{orderNumber}` | `order:read`, for orders the caller may read | New. Returns `{id, orderNumber}`; 400 for text that cannot be a number; 404 otherwise |
| `GET /api/v1/catalog/orders/page?q=` | unchanged | New `q`: number, id or school name, at most 100 characters |
| `GET …/{id}` | unchanged | Adds `trackerManaged`, `orderNumber`, `itemCode` and `workflowStep` (`{order, title, total, completed}`) |
| `GET …/{id}/tracker-summary` | superadmin, operators | Any workflow category. Adds `orderNumber`, `itemCode`, `itemLabel`, `schoolCity`, `quantities`, `titleCards` |
| `POST …/{id}/tracker/ensure` | superadmin, operators | Any workflow category. 400 for an order that is not on a workflow, or is closed |
| `PUT …/{id}/quote` | superadmin | Now also allowed on a non-notebook workflow order, until it completes |
| `PUT` and `POST /api/v1/catalog/orders/{id}/production-workflow/stages/{stageCode}[/complete]` | — | 409 `WORKFLOW_MOVED`. The `GET` still reads the retired product workflow |
| `POST /api/v1/schools`, `PATCH /api/v1/schools/{id}` | superadmin | `orderCode` |
| `POST` and `PATCH /api/v1/supply/product-catalog/categories[/{code}]` | superadmin | `orderCode`, `itemNoun`, `workflowDefinition`; an explicit `null` clears one |

**Removed by the SOP release:** all 25 `/api/v1/workflows/notebook-orders/**` routes (`NotebookTrackerController`,
including the designers and time slots that moved) and the `notebookTrackerClient.v1` contract. Test:
`OrderWorkflowControllerTest.theOldNotebookOrderRoutesAreGone`. Through the gateway these paths still answer 401
without a session. Only a signed-in request shows the 404 (see [Signed in as superadmin](#signed-in-as-superadmin)).

### Machine routes

Service-to-service calls use internal routes. Cloud Run IAM guards them, and so does the callee's `MachineCallerFilter`
allow-list. The gateway refuses every `/api/v1/internal/**` path with **404**
`{"message":"No service route is configured for this API path"}` before authentication, including the same path
behind a diagnostic alias such as `/workflow-api/v1/internal/…`.

| Caller → callee | Route | Callee allow-list env | Since |
| --- | --- | --- | --- |
| school-core → operations | `POST /api/v1/internal/order-workflows` | `NOTEBOOK_TRACKER_CALLER_SERVICE_ACCOUNTS` | SOP release |
| school-core → operations | `POST /api/v1/internal/order-workflows/{orderId}/design-file-access` | same | SOP release |
| school-core → operations | `POST /api/v1/internal/notebook-trackers`, `POST /api/v1/internal/notebook-trackers/{orderId}/image-access` | same | Tracker release. An alias since the SOP release; the contract release removes it |
| operations → school-core | `GET /api/v1/internal/catalog-orders/{orderId}/draft-images`, `PUT …/tracker-status` | `CATALOG_TRACKER_CALLER_SERVICE_ACCOUNTS` | Tracker release. Since SOP the status body adds `definitionId`, `stepOrder`, `stepTitle`, `totalSteps`, `completedSteps` |
| school-core → identity | `GET /api/v1/internal/operator-directory/resolve` | `OPERATOR_DIRECTORY_RESOLVE_CALLER_SERVICE_ACCOUNTS` | Tracker release. Since SOP it passes the order's `catalogCategory` |
| operations → identity | `GET /api/v1/internal/operator-directory/eligible`, `POST …/names` | `OPERATOR_DIRECTORY_ELIGIBILITY_CALLER_SERVICE_ACCOUNTS` | Tracker release |

- **Create** answers 201 for a new workflow and 200 for an existing one, filling in a missing order number. A create
  without `definitionId` makes a notebook workflow, so an older school-core's body still works.
- **School-core creates only on the new path**, never on the alias: an engine older than the SOP release would make
  every item a notebook workflow. Until the contract release, school-core asks the design-file question again on the
  alias when the new path answers 404, or 403 with an empty body. Those answers mean an engine that predates the route.
  The SOP engine never answers 404 there; an order without a workflow gets `allowed: false`.
- **The filter's answers.** A registered path called by the wrong service gets 403 `{"code":"MACHINE_CALLER_REJECTED"}`.
  Any unregistered `/api/v1/internal/` path gets 403 with an **empty body**. The five services' filter copies are
  identical (`scripts/check-duplicate-class-drift.py`).

### Screens

The app has five routes: `/login`, `/reset-password`, `/dashboard`, `/schools` and `/zones`. Screens are `?panel=` keys
on `/dashboard`:

- `?panel=nb-work`: **Order workflows** (the key is kept for bookmarks). Superadmin, or OPERATIONS with
  `notebook-tracker:read`; item-only operators land here. `&order=<CK id or order number>` opens one order.
- `?panel=sa-nb-admin`: **Workflow set-up** (superadmin), with the Vendors, Designers, Time slots, Groups and Operators
  tabs.
- `?panel=sa-all-orders`: **All orders** (superadmin). **View** on a workflow order opens its workflow, and the status
  column shows the step from `workflowStep`.
- `?panel=sa-schools` holds a school's order code. `?panel=sa-catalog` holds a category's order code, noun and template.

## What each release carries

### The SOP release (PR #382, `ed8aeba5`)

- **Deploy path.** No `deploy/` or `.github/` file changed, so dev used the fast direct Cloud Run path. No environment
  variable changed: the new internal paths reuse `NOTEBOOK_TRACKER_CALLER_SERVICE_ACCOUNTS`. No invoker binding is new.
- **school-core**
  - `tenant_school` V31: `schools.order_code`, derived for every school, and an insert trigger that derives one for a
    school inserted without it.
  - `catalog` V37: each category's `order_code`, `item_noun` and `workflow_definition`, seeded. Ten categories get a
    template; ties and trophies get a code only. Adds `catalog_orders.order_number` (immutable) and
    `order_number_counters` (no DELETE or TRUNCATE). Numbers every placed order of a coded category, and re-emits
    `catalog-order.upserted.v1` for each. It commits on its own.
  - `catalog` V38: takes `catalog_orders` and the old queue together, `NOWAIT`, retrying for up to 15 seconds. Then:
    - creates `catalog.order_workflow_creation_queue` (with `repair`), granted to the runtime roles in the same commit;
    - adds the forwarding trigger on the old queue and the placement-repair trigger on `catalog_orders`;
    - moves the old queue's rows;
    - moves open structured orders of template categories other than notebooks onto their workflows;
    - queues every open tracker-managed order (so its workflow gets its number), and queues for repair every placed,
      unnumbered order of a coded category;
    - makes the retired V36 product workflow tables read-only.
  - `catalog` V39: `catalog_order_assets.superseded_by`.
- **operations**
  - `workflow` V17: the five definitions and their step codes. Stops if the notebook definition does not have exactly
    steps 1–12.
  - V18: trackers become order workflows (definition, category, item code, noun, number). Every existing one is
    `NOTEBOOK_ORDER_TRACKER`.
  - V19: the vendor directory, settings and group links. Remaps vendor selections, and stops, naming each one, if a
    selection matches no directory vendor.
  - V20: append-only call logs. Stage notes are copied into the stage's entries.
  - V21: `lock_timeout` 15 s and a plain `LOCK … IN ACCESS EXCLUSIVE MODE` on `workflow_notebook_trackers`. Repeats
    V19's remap (with the same stop-and-name guard) and V20's copy. Adds `ck_wnt_vendor_selections` and the
    `copy_retired_stage_note` trigger.
- **platform**: `reporting` V36 adds `fact_catalog_order.order_number` and fills it from the inbox. Its trigger
  `trg_reporting_event_inbox_catalog_order_number` copies the number whenever any revision marks a catalog event
  processed.
- **identity, billing**: rebuilt for the `MachineCallerFilter` copy; no migration.
- **frontend**: Order workflows, Workflow set-up, and order numbers across screens.

*Dev result, read-only, 2026-11-13* (as the owner, with the bypass on):

- Flyway heads: catalog 39, workflow 21, reporting 36, applied 11:34–11:38 UTC.
- Both creation queues are empty, and no step row holds a non-blank `stage_note`.
- No placed order of a coded category lacks a number. No numbered order's reporting fact lacks it. (12 of 19 facts have
  no number, all legitimately: drafts, uncoded categories and two seed rows.)
- All four bridges are present.

### The contract release

It removes what only a revision older than the SOP release could use, and keeps two bridges that older revisions in
prod still need. The details, and why it is safe, are under [Clean-up](#clean-up).

- `catalog` V40 (`V40__retire_notebook_tracker_creation_queue.sql`): drops the old queue, its forwarding trigger and
  `catalog.forward_to_order_workflow_creation_queue()`.
- `workflow` V22 (`V22__retire_stage_note_columns.sql`): drops `copy_retired_stage_note`, its function and the three
  `workflow_instance_steps.stage_note*` columns.
- The internal aliases `/api/v1/internal/notebook-trackers` and `…/{orderId}/image-access` go, with school-core's
  fallback to them. Removing their entries from `MachineCallerFilter` changes all five identical copies, so all five
  Java services are rebuilt.
- School-core's legacy superadmin status update mints the order number when an order leaves DRAFT.

### Production's first release

One promotion takes prod from `main` to the dev head. Each Java service runs every pending version in one migration job
before its first canary, while `main` serves:

- identity V11–V12;
- school-core: `tenant_school` V31, `catalog` V27–V39, and V40 if the contract release is promoted with it;
- operations: `workflow` V8–V21, and V22 likewise;
- platform: `reporting` V36.

The other schemas are the same on `main` and dev. See [Production](#production) for what serves between services.

## Release order and the windows between services

Both release paths deploy in this order: gateway → identity → frontend → school-core → operations → billing → platform
(`scripts/invoke-direct-cloudrun-release.ps1`, `scripts/invoke-clouddeploy-release.ps1`). Each Java service's
migration job runs just before that service deploys, while its previous revision still serves. The job migrates the
service's schemas one after another, each with a Flyway run of its own (`MigrationOnlyMain`). Flyway commits each
version on its own, then runs that schema's `afterMigrate__dedicated_runtime_acl.sql`. So afterMigrate runs once per
schema: five times in school-core's job, twice in operations' and three times in platform's.

- **Dev, direct path.** `gcloud run deploy` moves all traffic once the new revision is ready, and only then does the
  next service start. In a run that completes, each window lasts minutes.
  - A run that stops leaves its window open. Dev's release runs are cancel-in-progress
    (`.github/workflows/build-release.yml:28-30`), so a merge to `dev` during a release cancels the run partway, and a
    later service's failure stops it too. Either can leave, say, school-core new and operations previous.
  - The next push does not close it by itself: it deploys only the services that push changed, since its base is the
    push's `before` (`build-release.yml:93-99`). A service the stopped run did not reach stays previous until a run
    includes it. To close the window, dispatch the workflow for `dev` with the dev head as `commit_sha`, which includes
    every service ([release operator runbook](release-operator.md)). Do not re-run the stopped run after a later run
    has deployed: it would put its older image back on any service both runs changed.
  - Do not merge to `dev` while a release rolls out.
- **Prod, Cloud Deploy** (`-WaitForRollout -AutoAdvanceCanary`, at most two rollouts at once). Each service goes through
  5, 25 and 50 % before stable, and a group starts only when the one before it has completed. The groups run in this
  order: gateway; identity; frontend; school-core; operations with billing; platform. During a canary, browser calls
  and service-to-service calls alike are split between the two revisions by the same percentages.

What serves in each window of an SOP release (dev on 2026-11-13; prod's first release):

1. **Identity migrated or new; the rest previous.** Prod only: identity V11–V12 add item-scoped operators and the
   `notebook-tracker:*` permission codes. A session issued before identity's new revision lacks those codes: sign out
   and back in, or reload, before Order workflows appears. Server checks always read the current permissions. Dev's SOP
   release changed no identity data, so dev needed no re-login.
2. **Frontend new; school-core and operations previous.** The new screens are served before operations has
   `/item-orders`, so Order workflows and Workflow set-up show errors until operations serves. In prod this lasts until
   operations' canary starts.
3. **School-core migrated (V37, V38); previous school-core serving.** The previous revision places orders without
   numbers. The placement-repair trigger queues each one with `repair = true`, so it is numbered later. On dev, the
   previous revision's queue writes are forwarded into the new queue.
4. **School-core new; operations previous.**
   - The previous engine has no `/api/v1/internal/order-workflows` route, so its filter answers every create,
     including everything V38 queued, with an empty-body 403. `last_error` reads `order workflow engine answered HTTP
     403`. The queue retries after 2, 4, 8, 16 and 32 minutes, then hourly, and gives up after 48 attempts (about two
     days).
   - Design-file uploads and removals: on dev, school-core asked the tracker's own `…/notebook-trackers/{orderId}/
     image-access` route instead. In prod, `main`'s operations has neither path, so they answer 503 `The order workflow
     is not available` until operations serves.
5. **Operations migrated (V17–V21); previous operations serving.** On dev, the tracker's revision kept serving on the
   new schema. After V21, its vendor-selection saves fail (500) and its stage-note saves are copied by V21's trigger. In
   prod, `main` does not know these tables.
6. **Operations new.** It starts only if the templates match `workflow_steps` (see
   [The operations startup check](#the-operations-startup-check)). Creations succeed at their next attempt; in prod a
   row may wait up to an hour (see [Production](#production)). Status pushes reach school-core. During a prod canary,
   a create, or a page load of Order workflows, reaches the new revision only in the canary's share, so it may fail once
   and work on the next try.
7. **Platform migrated (V36); previous platform serving.** The previous projector ignores `orderNumber`; the reporting
   trigger copies the number as each event is marked processed.

The contract release has no user-visible window on dev: each previous revision is the SOP one, which uses none of what
it removes (see [Clean-up](#what-the-contract-release-removes)). V40 and V22 each hold an ACCESS EXCLUSIVE lock for a
moment.

## Before the release

### Check the relay schedulers

Workflow creation (school-core) and status delivery (operations) are retried only by the relay trigger, which Cloud
Scheduler calls every minute. Confirm both jobs exist and are enabled (read-only):

```powershell
gcloud scheduler jobs list --project custoking-dev --location asia-south1
```

The list must show `ims-school-core-service-async-relay-dev` and `ims-operations-service-async-relay-dev`, each on
`* * * * *` and `ENABLED` (verified for dev on 2026-10-21). If either is missing or paused, recreate it with
`scripts/configure-async-relay-scheduler.ps1`, limited to those two services: dry run first, then apply.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& .\scripts\configure-async-relay-scheduler.ps1 -ProjectId custoking-dev -Environment dev -Service school-core-service,operations-service"
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& .\scripts\configure-async-relay-scheduler.ps1 -ProjectId custoking-dev -Environment dev -Service school-core-service,operations-service -Apply"
```

A 2xx relay response also carries the drain counts: `trackersCreated` from school-core and `trackerStatusesDelivered`
from operations.

### Grant the invoker roles

The engine needs three `roles/run.invoker` bindings: school-core on operations, school-core on identity, and operations
on identity. They are declared in `$invokerMatrix` in `scripts/configure-runtime-service-accounts.ps1`. Dev has had them
since the tracker release (October 2026), and the SOP release needs no new one. Prod lacked all three on 2026-11-12. The
steps below are for dev; prod is under [Production](#production).

1. Dry run. It makes no changes and prints the identity matrix and a `preflight` block:

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\configure-runtime-service-accounts.ps1 -ProjectId custoking-dev -Environment dev
   ```

   Check that the `invokes` array of `school-core-service` is `operations-service, identity-service`, that of
   `operations-service` is `school-core-service, identity-service`, and that every `preflight` array is empty.

2. Apply:

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\configure-runtime-service-accounts.ps1 -ProjectId custoking-dev -Environment dev -Apply
   ```

   Every binding the script makes is additive; the rest of its run re-applies grants dev already has. If a
   `preflight` array is not empty, `-Apply` stops before changing anything. Do not create resources to satisfy it as
   part of this change. The three bindings are this feature's entire IAM change, and these commands make them with the
   script's own flags:

   ```powershell
   gcloud run services add-iam-policy-binding custoking-operations-service-dev --project=custoking-dev --region=asia-south2 --member=serviceAccount:ims-school-core-dev@custoking-dev.iam.gserviceaccount.com --role=roles/run.invoker --quiet
   gcloud run services add-iam-policy-binding custoking-identity-service-dev --project=custoking-dev --region=asia-south2 --member=serviceAccount:ims-school-core-dev@custoking-dev.iam.gserviceaccount.com --role=roles/run.invoker --quiet
   gcloud run services add-iam-policy-binding custoking-identity-service-dev --project=custoking-dev --region=asia-south2 --member=serviceAccount:ims-operations-dev@custoking-dev.iam.gserviceaccount.com --role=roles/run.invoker --quiet
   ```

3. Verify (read-only). `ims-school-core-dev` must be an invoker of operations; `ims-school-core-dev` and
   `ims-operations-dev` must be invokers of identity:

   ```powershell
   gcloud run services get-iam-policy custoking-operations-service-dev --project=custoking-dev --region=asia-south2
   gcloud run services get-iam-policy custoking-identity-service-dev --project=custoking-dev --region=asia-south2
   ```

### Check the deployed configuration

The SOP release changed no environment variable. After any release, confirm the serving revisions carry these values
(read-only):

```powershell
gcloud run services describe custoking-school-core-service-dev --project=custoking-dev --region=asia-south2 --format="yaml(spec.template.spec.containers[0].env)"
gcloud run services describe custoking-operations-service-dev --project=custoking-dev --region=asia-south2 --format="yaml(spec.template.spec.containers[0].env)"
gcloud run services describe custoking-identity-service-dev --project=custoking-dev --region=asia-south2 --format="yaml(spec.template.spec.containers[0].env)"
```

- school-core: `CATALOG_TRACKER_BASE_URL=https://custoking-operations-service-dev-1087017280590.asia-south2.run.app`,
  `CATALOG_OPERATOR_DIRECTORY_BASE_URL=https://custoking-identity-service-dev-1087017280590.asia-south2.run.app`,
  `CATALOG_TRACKER_CALLER_SERVICE_ACCOUNTS=ims-operations-dev@custoking-dev.iam.gserviceaccount.com`,
  `CATALOG_PRODUCT_FORM_ENABLED=true`.
- operations: `OPERATIONS_IDENTITY_BASE_URL=https://custoking-identity-service-dev-1087017280590.asia-south2.run.app`,
  `NOTEBOOK_TRACKER_CALLER_SERVICE_ACCOUNTS=ims-school-core-dev@custoking-dev.iam.gserviceaccount.com`.
- identity: `OPERATOR_DIRECTORY_RESOLVE_CALLER_SERVICE_ACCOUNTS=ims-school-core-dev@custoking-dev.iam.gserviceaccount.com`,
  `OPERATOR_DIRECTORY_ELIGIBILITY_CALLER_SERVICE_ACCOUNTS=ims-operations-dev@custoking-dev.iam.gserviceaccount.com`.
- None of the three sets `MACHINE_CALLER_LOCAL_TRUST`. It belongs only in `docker-compose.local.yml`, and Cloud Run's
  `K_SERVICE` disables it anyway.

Each peer URL is also the audience of the caller's identity token, so it must appear in the callee's
`SERVICE_OIDC_AUDIENCES`. The `*_url` target parameters and the first entry of each `service_oidc_audiences` are the
same deterministic `https://custoking-<service>-<env>-<project number>.<region>.run.app` address.

## Running the SQL in this runbook

- **Where.** The Cloud SQL instances (`custoking-db-dev`, `custoking-db-prod`) have private addresses only. Run SQL from
  a disposable `postgres:16` Cloud Run job on Direct VPC egress (`--network=default --subnet=default
  --vpc-egress=private-ranges-only`). Connect as the owner `appuser`, with the password from Secret Manager
  (`db-password-<env>`), and use the host and database named in the service's `SPRING_DATASOURCE_URL`. The read-only
  jobs of the [production data ledger](../GCP-MIGRATION-DATA-LEDGER-PROD-2026-08-18.md) were run this way. Delete the
  job afterwards. A job against prod needs the owner's approval.
- **Row-level security.** The tables force it, and it binds the owner too: `appuser` is NOSUPERUSER and NOBYPASSRLS. A
  plain `SELECT` therefore returns no rows, and a plain `UPDATE` reports `UPDATE 0` and changes nothing. Neither gives
  an error. Every statement here runs in a transaction that turns the policy's bypass on for that transaction only:
  the `true` in `set_config` ends it at `COMMIT`. Never disable row security instead.
- **Read-only.** Read-only checks start with `SET default_transaction_read_only = on;`, so a mistake cannot write.

**How the SQL here was tested.** On 2026-11-14 every SQL block in this file ran on scratch PostgreSQL 16 databases.
The migrations were applied as a NOSUPERUSER, NOBYPASSRLS owner, with `app_rt`'s grants from
`scripts/create-app-rt-role.sql` and each schema's afterMigrate.

- *Dev's path.* The real tenant_school V1–V31, catalog V1–V39, workflow V1–V21 and reporting V1–V36. Then the contract
  release's catalog V40 and workflow V22, as committed for review (`cc85c058`). Then the final clean-up. Without the
  bypass, the owner saw 0 of 7 orders.
- *Prod's path.* `main`'s heads (tenant_school V30, catalog V26, workflow V7, reporting V35), where the prod pre-count
  ran. Then every later version, through V40 and V22, in one run per schema. For these four schemas, the versions
  applied matched the prod column of the table under [Migration job results](#migration-job-results).
- *Locks.* A reader held `catalog_orders`, then `workflow_instance_steps`, for 20 s. In two runs, V40 and V22 each
  stopped after 16–21 s (150 tries 0.1 s apart, slower on a busy machine) with its `stayed busy` message, and its
  version was not applied. A new reader with a 1 s statement timeout, started during the retries, was answered each
  time, so it never waited on the lock. With a 3 s reader, each finished in 3–5 s.
- *What was not real.* psql applied each version in one transaction, as Flyway does, but Flyway itself did not run:
  its history tables were created in Flyway's shape and filled by the test. The legacy status update was its SQL
  statement, run as `ims_school_core_rt` in a request scoped to one school, not the Java code.

## Migration job results

`scripts/invoke-service-migration-job.ps1` runs each Java service's migrations before that service deploys:

- It creates a Cloud Run job `ims-migrate-<service without -service>-<env>-<yyyyMMddHHmmss>-<8 hex>`, for example
  `ims-migrate-school-core-dev-…` (`scripts/invoke-service-migration-job.ps1:58`). The job runs `MigrationOnlyMain`
  as `ims-db-migration-<env>`, with `FLYWAY_USERNAME=appuser`, a 300 s timeout and no retries.
- It waits for the job, then deletes it. The job's logs stay.
- Deployed services never migrate (`APP_MIGRATIONS_ENABLED: "false"`).

On success the job prints one line per schema to stdout, so to `textPayload` (this process has no Spring logging),
then `Owner migrations completed for <service>`:

```text
OWNER_MIGRATION_RESULT service=<service> schema=<schema> version=<n> migrationsExecuted=<k> success=true
```

The script requires a line for every schema the service owns, and writes them to
`release-evidence/migrations/<service>.json` in the run's `release-evidence-<env>-<sha>` artifact. To read them:

```bash
E=dev   # or prod
P=custoking-$E
gcloud logging read "resource.type=\"cloud_run_job\" AND resource.labels.job_name:\"ims-migrate-\" AND textPayload:\"OWNER_MIGRATION_RESULT\"" --project=$P --freshness=1d --format="value(timestamp,textPayload)"
```

Expected `version/migrationsExecuted`, one value per schema, in the job's order:

| Service | Schemas | SOP release on dev (done) | Contract release on dev | Prod's first release, from `main`'s heads |
| --- | --- | --- | --- | --- |
| identity | identity | 12/0 | 12/0 | 12/2 |
| school-core | tenant_school, student, attendance, fee, catalog | 31/1, 39/0, 11/0, 11/0, 39/3 | 31/0, 39/0, 11/0, 11/0, 40/1 | 31/1, 39/0, 11/0, 11/0, 40/14 (39/13 without the contract release) |
| operations | workflow, firefighting | 21/5, 14/0 | 22/1, 14/0 | 22/15 (21/14), 14/0 |
| billing | billing | 10/0 | 10/0 | 10/0 |
| platform | reporting, notification, audit | 36/1, 18/0, 3/0 | 36/0, 18/0, 3/0 | 36/1, 18/0, 3/0 |

A release runs jobs only for the services it deploys. The prod column assumes prod is at `main`'s heads (identity V10,
tenant_school V30, catalog V26, workflow V7, reporting V35). Read prod's heads before the release
([Production](#production)); if they are lower, expect more.

### When a migration job fails

- **Where the reason is.** The Actions log says only `Migration CLI failed; credentials and database diagnostics are
  intentionally omitted.` The job's own log (stderr) says only `Owner migration failed; deployment must remain blocked.
  Consult restricted database logs.` `MigrationOnlyMain` swallows the exception because it may carry credentials.
- **The reason is only in the Cloud SQL instance's PostgreSQL log.** That covers the 15-second `stayed busy` messages
  of V38, V40 and V22, the stop-and-name lists, and lock timeouts. Set `JOB_START` to just before the job ran:

  ```bash
  JOB_START=2026-11-13T11:30:00Z   # replace
  gcloud logging read "resource.type=\"cloudsql_database\" AND resource.labels.database_id=\"$P:custoking-db-$E\" AND logName=\"projects/$P/logs/cloudsql.googleapis.com%2Fpostgres.log\" AND severity>=WARNING AND timestamp>=\"$JOB_START\"" --project=$P --limit=50 --format="value(timestamp,severity,textPayload,jsonPayload.message)"
  ```

  The payload field of this log was not checked against a live entry when this was written, so both fields are
  printed. If nothing shows, drop `severity>=WARNING` and add the version as a bare search term, such as `"V38:"`.
- **What a failure leaves.** Each version is one transaction, so the version that failed changed nothing, and the
  versions before it stay applied. That service and every later one are not deployed; the previous revisions keep
  serving.
  - *One exception: billing in the Cloud Deploy path.* That path serves prod always, and dev only when the push
    changed the deployment configuration (`scripts/resolve-affected-ci-targets.ps1:118-128`) or the run forces a full
    deploy. Neither the SOP nor the contract release changes it, so on dev both use the direct path.
  - Operations and billing run in one wave of two. Operations' migration job, release and rollout all come before
    billing's migration job (`scripts/invoke-clouddeploy-release.ps1:95-170`). The wait that advances the canary runs
    only after the whole wave (`:176-187`).
  - So if billing's migration job or release fails, operations' rollout already exists. In prod it stays at its first
    canary phase, 5 %: nothing advances it, and the workflow skips its *Confirm Cloud Deploy rollouts* step after the
    failure. On dev's standard pipeline it completes on its own.
  - Billing has no pending migration in the SOP or the contract release, so this is unlikely here. If it happens,
    settle operations' rollout before re-running the release job. Find it, then advance it one phase at a time as the
    release script does (`scripts/wait-clouddeploy-rollout-group.ps1:61-64`), or roll operations back
    ([rollback](rollback.md)):

    ```bash
    gcloud deploy rollouts list --delivery-pipeline=custoking-operations-service-prod --release=<rel-prod-…> --project=custoking-prod --region=asia-south2 --format="table(name.basename(),state)"
    gcloud deploy rollouts describe <rollout> --delivery-pipeline=custoking-operations-service-prod --release=<rel-prod-…> --project=custoking-prod --region=asia-south2 --format="yaml(state,phases)"
    gcloud deploy rollouts advance <rollout> --delivery-pipeline=custoking-operations-service-prod --release=<rel-prod-…> --phase-id=<next pending phase> --project=custoking-prod --region=asia-south2
    ```

- **A lock failure** is safe to re-run. It reads one of these:
  - `V38: catalog_orders and notebook_tracker_creation_queue stayed busy for 15 seconds; nothing was changed, re-run
    the migration job`;
  - `V40: catalog_orders and notebook_tracker_creation_queue stayed busy for 15 seconds; nothing was changed, re-run
    the migration job`;
  - `V22: workflow_instance_steps stayed busy for 15 seconds; nothing was changed, re-run the migration job`;
  - `canceling statement due to lock timeout`, from V21's plain lock or any other wait.

  Re-run the failed release job. The dev direct path skips each service that already serves the new image, then runs
  the failed service's job again, which starts at the version that failed. For prod, follow
  [the release operator runbook](release-operator.md).
- **A stop-and-name failure** is a data problem, and re-running alone fails again:
  - `V19: vendor selections that match no directory vendor: …` or `V21: vendor selections saved since V19 that match no
    directory vendor: …` names each order and selection. Decide with the owner which directory vendor each one means,
    then fix the rows. Silently dropping a selection is worse than a failed deploy.
  - `V17: NOTEBOOK_ORDER_TRACKER must have exactly steps 1-12 before codes are assigned` means the `workflow_steps` rows
    were edited by hand. Restore them.
  - `V22: stage notes that are in no stage entry (no recorded author, so V20 and V21 did not copy them): instance <id>
    (order <id>) step <n>; …` names each stage note it cannot copy. With the owner, copy each into
    `workflow.workflow_internal_notes` with the author it should carry, or clear its `stage_note`, then re-run.

## Post-deploy checks

Run these after the last of school-core, operations and platform serves the new revision; in prod, after platform's
rollout completes. Set up the shell:

```bash
E=dev   # or prod
P=custoking-$E
FE=$(gcloud run services describe custoking-frontend-$E --project=$P --region=asia-south2 --format="value(status.url)")
GW=$(gcloud run services describe custoking-api-gateway-$E --project=$P --region=asia-south2 --format="value(status.url)")
SINCE=2026-11-13T11:39:00Z   # replace: when the last of the three became Ready (UTC)
SVC="resource.type=\"cloud_run_revision\" AND resource.labels.service_name=(\"custoking-school-core-service-$E\" OR \"custoking-operations-service-$E\" OR \"custoking-platform-service-$E\")"
```

For dev, `$FE` is `https://custoking-frontend-dev-hd4wfwk7mq-em.a.run.app`. The deterministic
`https://custoking-frontend-dev-1087017280590.asia-south2.run.app` works as well; both are allowed cookie origins.
`SINCE` is the `lastTransitionTime` of the `Ready` condition of the last of the three serving revisions:

```bash
for s in school-core-service operations-service platform-service; do
  r=$(gcloud run services describe custoking-$s-$E --project=$P --region=asia-south2 --format="value(status.latestReadyRevisionName)")
  gcloud run revisions describe "$r" --project=$P --region=asia-south2 --format="yaml(metadata.name,status.conditions)"
done
```

### Without signing in

```bash
for p in /api/v1/workflows/item-orders /api/v1/workflows/notebook-orders /api/v1/catalog/orders/by-number/NB-X-26-01-01-0001 /api/v1/internal/order-workflows /api/v1/internal/catalog-orders/CK-1/tracker-status; do
  printf '%s %s\n' "$(curl -s -o /dev/null -w '%{http_code}' "$FE$p")" "$p"
done
curl -s -w ' %{http_code}\n' "$GW/workflow-api/v1/internal/order-workflows"
```

- **Expect** 401 for the first three, and 404 `No service route is configured for this API path` for the internal
  paths and the diagnostic alias. Only the gateway URL serves the alias: the frontend answers its own page (200, HTML)
  for any path outside `/api/v1/`.
- **What this proves.** The frontend reaches the gateway, the gateway sends `/api/v1/workflows/**` and the catalog paths
  to an authenticated upstream, and it never carries a browser request to an internal route.
- **What it cannot prove.** That a route exists, or that one is gone: the gateway answers 401 before any service is
  asked. The removed `/notebook-orders` path answers 401 too. A route's presence or absence shows only with a session.
- **Measured on dev, 2026-11-13 19:03 UTC**, through the deterministic frontend URL and the gateway (and, for
  `item-orders` and `internal/order-workflows`, through the `hd4wfwk7mq` frontend URL), with no credentials:
  - 401: `/api/v1/workflows/item-orders`, `…/item-orders/templates`, `/api/v1/workflows/notebook-orders` and
    `…/notebook-orders/CK-1015`, `/api/v1/workflows/vendors`, `/api/v1/catalog/orders/by-number/…` and
    `/api/v1/supply/orders/CK-1015/tracker-summary`.
  - 404: `/api/v1/internal/order-workflows`, `/api/v1/internal/notebook-trackers`,
    `/api/v1/internal/catalog-orders/CK-1015/tracker-status`, and on the gateway `/workflow-api/v1/internal/…` and
    `/catalog-api/v1/internal/…`.
  - The operations and school-core Cloud Run URLs answered Cloud Run's own HTML 403 ("Your client does not have
    permission"), so neither is public.

### Migration jobs

Read the `OWNER_MIGRATION_RESULT` lines ([Migration job results](#migration-job-results)) and compare them with the
table. Every line must say `success=true`, with the expected version.

### The operations startup check

At start-up operations compares the five templates with `workflow_steps` and `workflow_vendor_categories`
(`TemplateDatabaseCheck`). On a mismatch the revision fails to start and the deploy fails. On success it logs one INFO
line per instance start:

```bash
gcloud logging read "resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"custoking-operations-service-$E\" AND jsonPayload.message:\"Order workflow templates match the database\" AND timestamp>=\"$SINCE\"" --project=$P --limit=5 --format="value(timestamp,resource.labels.revision_name,jsonPayload.message)"
```

Expect a line from the serving revision, naming `NOTEBOOK_ORDER_TRACKER`, `FLIER_ORDER_WORKFLOW`,
`FLEX_ORDER_WORKFLOW`, `BILL_BOOK_ORDER_WORKFLOW` and `GENERAL_ITEM_ORDER_WORKFLOW`. If the revision scaled to zero
since it started, widen the time window. Spring Boot services log to `jsonPayload` (fields `message`, `level`,
`logger_name`, and each `kv()` argument such as `event` or `orderId` at the top level). A `textPayload` filter finds
none of their lines.

### Logs since the release

Each of these should return nothing:

```bash
# Application errors, and 5xx request logs, on the three services.
gcloud logging read "$SVC AND severity>=ERROR AND timestamp>=\"$SINCE\"" --project=$P --limit=50 --format="value(timestamp,resource.labels.service_name,jsonPayload.message,httpRequest.status,httpRequest.requestUrl)"
# Given-up creations or statuses, and queues that could not be counted.
gcloud logging read "$SVC AND jsonPayload.event=(\"tracker_creation_dead_lettered\" OR \"tracker_status_dead_lettered\" OR \"outbox_backlog_count_failed\") AND timestamp>=\"$SINCE\"" --project=$P --limit=20 --format="value(timestamp,resource.labels.service_name,jsonPayload.message)"
# Refused machine calls. A rollout's empty-body 403s come before SINCE.
gcloud logging read "$SVC AND httpRequest.status=403 AND httpRequest.requestUrl:\"/api/v1/internal/\" AND timestamp>=\"$SINCE\"" --project=$P --limit=20 --format="value(timestamp,resource.labels.service_name,httpRequest.requestUrl)"
# Calls on the old alias. Expect none once school-core and operations both serve the SOP release.
gcloud logging read "resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"custoking-operations-service-$E\" AND httpRequest.requestUrl:\"/api/v1/internal/notebook-trackers\" AND timestamp>=\"$SINCE\"" --project=$P --limit=20 --format="value(timestamp,httpRequest.status,httpRequest.requestUrl)"
# PostgreSQL warnings from the two bridges.
gcloud logging read "resource.type=\"cloudsql_database\" AND resource.labels.database_id=\"$P:custoking-db-$E\" AND (\"could not be queued for repair\" OR \"could not be copied to reporting\") AND timestamp>=\"$SINCE\"" --project=$P --limit=20 --format="value(timestamp,textPayload,jsonPayload.message)"
```

- INFO `Order <id> was placed without an order number and is repaired: number <n>` (school-core) is expected while a
  revision that does not mint still serves, and on the SOP release after a legacy status update (see
  [The creation queue](#the-creation-queue-since-catalog-v38)). Once the contract release's mint fix serves on every
  school-core revision, each such line names a placement path that still does not mint: report it. It is the only
  lasting trace of a repair, and the final clean-up's pre-check reads it.
- WARN `Tracker for order <id> not created on attempt <n>: <reason>` is expected in window 4, before SINCE.
- Each service logs INFO `outbox.async.health` every minute. `jsonPayload.health.outbox` holds `pendingCount`,
  `deadLetterCount` and `oldestPendingAgeSeconds`. School-core's figure includes the creation queue, and operations'
  includes the status outbox.

### Database

Read-only, as described in [Running the SQL](#running-the-sql-in-this-runbook):

```sql
-- Read-only. Run as the migration owner (appuser), who sees no rows of a forced-RLS table without the bypass.
SET default_transaction_read_only = on;
BEGIN;
SELECT set_config('app.bypass_rls', 'on', true);
-- 1. Schema heads (the last Flyway row of each schema; success must be true).
(SELECT 'tenant_school' AS schema, version, success FROM tenant_school.flyway_schema_history_tenant_school ORDER BY installed_rank DESC LIMIT 1)
UNION ALL (SELECT 'catalog', version, success FROM catalog.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1)
UNION ALL (SELECT 'workflow', version, success FROM workflow.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1)
UNION ALL (SELECT 'reporting', version, success FROM reporting.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1);
-- 2. The creation queue, by kind. A dead-lettered row needs action (see "When it fails").
SELECT repair, dead_lettered_at IS NOT NULL AS dead_lettered, count(*) AS queued_rows,
       max(attempts) AS max_attempts, min(next_attempt_at) AS next_due
FROM catalog.order_workflow_creation_queue GROUP BY 1, 2 ORDER BY 1, 2;
-- 3. Open orders still waiting for their workflow: falls to 0 once the queue drains.
SELECT count(*) AS open_orders_tracker_pending FROM catalog.catalog_orders
WHERE attributes @> '{"trackerPending": true}'
  AND status NOT IN ('DRAFT', 'CANCELLED', 'DELIVERED', 'FULFILLED', 'REJECTED', 'RETURNED', 'COMPLETED');
-- 4. Placed orders of a coded category without a number, with their queue row: none once repair rows drain.
SELECT co.id, co.category, co.status, q.repair, q.attempts, q.dead_lettered_at
FROM catalog.catalog_orders co
JOIN catalog.product_categories pc ON pc.code = co.category AND pc.order_code IS NOT NULL
LEFT JOIN catalog.order_workflow_creation_queue q ON q.order_id = co.id
WHERE co.status <> 'DRAFT' AND co.order_number IS NULL
ORDER BY co.id;
-- 5. Reporting facts that lack a number their order has: 0 once platform serves this release.
SELECT count(*) AS facts_missing_number FROM reporting.fact_catalog_order f
JOIN catalog.catalog_orders co ON co.id = f.id
WHERE f.order_number IS NULL AND co.order_number IS NOT NULL;
-- 6. The rollout bridges that exist ('O' = enabled).
SELECT t.tgrelid::regclass AS on_table, t.tgname, t.tgenabled FROM pg_trigger t
WHERE t.tgname IN ('trg_notebook_tracker_creation_queue_forward', 'trg_catalog_orders_queue_unnumbered_placement',
                   'copy_retired_stage_note', 'trg_reporting_event_inbox_catalog_order_number')
ORDER BY 2;
COMMIT;
```

- **1.** SOP release: tenant_school 31, catalog 39, workflow 21, reporting 36. Contract release: catalog 40,
  workflow 22.
- **2–5.** No dead-lettered row. Repair rows only while a revision that does not mint serves, and then none. 0 orders
  still pending, no rows from 4, and 0 facts from 5, once the queue and the inbox have drained (minutes on dev; in prod
  up to an hour after operations serves, unless you bring the rows forward).
- **6.** SOP release: all four bridges, each `O`. After the contract release: only
  `trg_catalog_orders_queue_unnumbered_placement` and `trg_reporting_event_inbox_catalog_order_number`.

After the contract release, also check that what it drops is gone. Expect three nulls and 0:

```sql
SET default_transaction_read_only = on;
SELECT to_regclass('catalog.notebook_tracker_creation_queue') AS old_queue,
       to_regprocedure('catalog.forward_to_order_workflow_creation_queue()') AS forward_function,
       to_regprocedure('workflow.copy_retired_stage_note()') AS note_copy_function,
       (SELECT count(*) FROM information_schema.columns
        WHERE table_schema = 'workflow' AND table_name = 'workflow_instance_steps' AND column_name LIKE 'stage_note%')
           AS stage_note_columns;
```

The contract release also brings the mint fix. Record `MINT_FIX_FROM` for the final clean-up's log read, as under
*Record when the mint fix serves* in [What stays, and when it can go](#what-stays-and-when-it-can-go).

### Signed in as superadmin

Sign in to the frontend as a superadmin. On dev, use a test account, and never write its credentials anywhere. If dev
sign-in fails, check `gcloud sql instances describe custoking-db-dev --project=custoking-dev
--format="value(state,settings.activationPolicy)"` first. The dev database has been always on since mid-October 2026.

To call an API path with the session, open the order's workflow, then use the browser's developer tools:

- In Network, right-click the `item-orders/<orderId>` request and choose Copy → Copy as fetch.
- Paste it into the Console, change the path, and append `.then(r => console.log(r.status))`.
- The copied request carries the session's access token. Use it in that console only.

1. **The screens load.** `/dashboard?panel=nb-work` lists orders, and `/dashboard?panel=sa-nb-admin` opens its five
   tabs.
2. **The old routes are gone.** The copied request answers 200 as copied, and 404 with `item-orders` changed to
   `notebook-orders`. This is the only check here that proves a removed route is gone.
3. **A new notebook order gets its number and workflow.** As a test school's admin, place a customised notebook order.
   Within a minute, as superadmin:
   - All orders shows a number `NB-<school code>-<YY-MM-DD>-<NNNN>`, and View opens the workflow on step 1, Order
     Details, with 12 steps (9 for a wholesale order).
   - `GET /api/v1/supply/orders/<orderId>` shows `"trackerManaged": true`, the `orderNumber` and `"itemCode": "NB"`.
   - `GET /api/v1/supply/orders/<orderId>/tracker-summary` shows `"trackerPending": false`.
   - `GET /api/v1/catalog/orders/by-number/<orderNumber>` returns `{id, orderNumber}`.
   - `/dashboard?panel=nb-work&order=<orderNumber>` opens the order.
4. **Another item gets its own template.** A new flier (FE) or certificate (CF) order opens with 9 steps on
   `FLIER_ORDER_WORKFLOW` or `GENERAL_ITEM_ORDER_WORKFLOW` (the view's `definitionId`).
5. **The step reaches the order.** Complete step 1 of the notebook order: save the School order PDF link and tick
   "Introduction message sent in the school group" (`NOTEBOOK_ORDER_TRACKER.json`, step 1's `orderPdfLink` and
   `introChecks`). Within a minute the order's `status` moves from `ORDER_DETAILS` to `DESIGNING_ACTIVITY`
   (`PRODUCTION_PLANNING` for a wholesale notebook). Its `workflowStep` reads `{"order": 2, …, "total": 12, "completed":
   1}`, and All orders shows the step.
6. **Staff names resolve.** An order with assigned stages shows the assignee's and note authors' **names**, not "User
   N". This is the only end-to-end proof of operations → identity names (`POST
   /api/v1/internal/operator-directory/names`). When it fails, operations logs one WARN a minute and the screen falls
   back to "User N".
7. **Design files reach the engine.** On an order in Designing Activity, upload a design file and remove it.

The release passes when the unsigned probes, the migration jobs, the startup check, the logs and the database checks
hold, and the seven signed-in checks pass. In prod, place orders only with the owner's agreement; otherwise run checks
1, 2 and 6 on existing orders. Record order IDs and timestamps, not the orders' contents.

## When it fails

- **The order stays "tracker pending", or its status does not move.** School-core's creation queue and operations'
  status outbox both retry on the relay trigger (`POST /api/v1/internal/outbox/relay`, every minute) with a growing
  backoff, so nothing is lost while you fix the cause.
  - The last creation failure is in `catalog.order_workflow_creation_queue.last_error`, for example `order workflow
    engine answered HTTP 403`, `operator directory answered HTTP 403`, or `the order could not be repaired: …` on a
    `repair` row. See *Reading the queues* below.
  - Superadmin, or one of the order's operators, can retry an open workflow order at once with `POST
    /api/v1/supply/orders/{orderId}/tracker/ensure`. It revives a dead-lettered row with fresh retries and keeps its
    `repair` flag.
  - `ensure` refuses an order that is not on a workflow, with 400 (`CatalogOrderFormService.ensureTracker`). For a
    legacy (form v1) order it answers `This order uses the legacy form`. For a structured one it answers `Only a placed
    order on an order workflow has one; this order follows order approval`. A `repair` row's order can be either. On
    the SOP release the legacy status update leaves form v1 orders unnumbered, and the trigger queues them. A flier the
    previous revision placed moves onto its workflow only through the repair, and a tie never does. Revive such a row
    with SQL (below).
  - A creation that fails 48 times (about two days) is given up. School-core logs an ERROR with
    `event=tracker_creation_dead_lettered`, and the row counts in the outbox dead-letter alert.
- **A status is not delivered.** The reason is in `workflow.workflow_tracker_status_outbox.last_error`.
  - A status that fails 48 times is given up the same way. Operations logs an ERROR with
    `event=tracker_status_dead_lettered` (with `orderId` and `trackerVersion`), and the row counts in operations' outbox
    dead-letter figure. The order's next step change revives it; to retry it without one, see *Reading the queues*.
  - A status school-core refuses because the order is cancelled (409) or gone (404) is dropped at once, not retried.
    Operations logs a WARN with `event=tracker_status_dropped`, `orderId` and `httpStatus`.
  - Operations WARN `Order <id> gets its number but no status push: …` means the order's stages no longer fit its
    template. The number was filled in; only the step report was skipped.
- **Reading the queues.** Run each statement in a bypass-on transaction
  ([Running the SQL](#running-the-sql-in-this-runbook)).

  ```sql
  -- school-core's creation queue (catalog V38 and later)
  BEGIN;
  SELECT set_config('app.bypass_rls', 'on', true);
  SELECT order_id, repair, attempts, next_attempt_at, dead_lettered_at, last_error FROM catalog.order_workflow_creation_queue WHERE order_id = '<orderId>';
  COMMIT;

  -- operations' status outbox
  BEGIN;
  SELECT set_config('app.bypass_rls', 'on', true);
  SELECT order_id, status, tracker_version, attempts, dead_lettered_at, last_error FROM workflow.workflow_tracker_status_outbox WHERE order_id = '<orderId>';
  COMMIT;
  ```

  To retry a dead-lettered status at once:

  ```sql
  BEGIN;
  SELECT set_config('app.bypass_rls', 'on', true);
  UPDATE workflow.workflow_tracker_status_outbox SET dead_lettered_at = NULL, attempts = 0, next_attempt_at = now() WHERE order_id = '<orderId>';
  COMMIT;
  ```

  Expect `UPDATE 1`, and the relay trigger delivers the status within a minute. `UPDATE 0` means the bypass was not
  on in that same transaction, or the order has no status queued.

  A creation is retried with `ensure`, not with SQL, except a dead-lettered `repair` row whose order `ensure` refuses.
  Revive that one the way `ensure` does. It keeps its `repair` flag, so the next claim repairs the order first:

  ```sql
  BEGIN;
  SELECT set_config('app.bypass_rls', 'on', true);
  UPDATE catalog.order_workflow_creation_queue SET dead_lettered_at = NULL, attempts = 0, next_attempt_at = now() WHERE order_id = '<orderId>' AND repair AND dead_lettered_at IS NOT NULL;
  COMMIT;
  ```

  Expect `UPDATE 1`; the relay trigger repairs the order within a minute. `UPDATE 0` means the bypass was not on, or
  the row is not a dead-lettered repair row.
- **A 403 on an internal route** has three causes, told apart by the body:
  - **Empty body.** The callee revision predates the route: its `MachineCallerFilter` answers 403 to every unregistered
    `/api/v1/internal/` path. This is expected while services roll out one after another, and the queues retry. Wait
    until every service serves the new revision. If it persists after that, the route is missing from the callee:
    check its serving revision.
  - **`{"code":"MACHINE_CALLER_REJECTED"}`.** The callee's `*_CALLER_SERVICE_ACCOUNTS` value does not name the caller,
    or the identity token's audience is not in its `SERVICE_OIDC_AUDIENCES`. Check that value, then the audience.
  - **A Cloud Run IAM page** (HTML, "Your client does not have permission"). An invoker binding is missing, and Cloud
    Run refuses the request before it reaches the container. Check the grants.
- **Design-file upload or removal answers 503 `The order workflow is not available`.** School-core could not get an
  answer from the engine.
  - Before the contract release, school-core asks the alias when the new path answers 404 or an empty-body 403. In prod
    this answer is expected until operations serves, because `main`'s engine has neither path.
  - After the contract release, there is no fallback: the engine must serve the new path.
  - A 403 with a body fails closed, as above.
- **A 404 on an internal route** is not a rollout symptom, because an old revision answers 403. Check the order or path
  in the request. For operations → school-core `tracker-status`, a 404 means the order no longer exists, and the status
  is dropped (above).
- **Operations does not start.** A template that does not match `workflow_steps` stops the revision at start-up, so the
  deploy fails and the previous revision keeps serving. The cause is in the failed revision's log. Fix the code or the
  migration, not the data.
- **A queue's count failed.** If a queue cannot be counted, its figures are missing from the `outbox.async.health`
  metrics until it recovers, and no alert says so. The only sign is an ERROR log, found with:

  ```bash
  gcloud logging read "$SVC AND jsonPayload.event=\"outbox_backlog_count_failed\"" --project=$P --freshness=1d --limit=20 --format="value(timestamp,resource.labels.service_name,jsonPayload.queue)"
  ```

  Its `queue` field names the queue: `order_workflow_creation_queue` for school-core's creation queue
  (`notebook_tracker_creation_queue` before catalog V38), `workflow_tracker_status_outbox` for operations' status
  outbox.
- **Abandoning a dead-lettered row.** When a dead-lettered row must never be delivered (its order is gone and will
  not return), delete it instead of reviving it; that removes it from the dead-letter figure. In the same bypass-on
  transaction as above:

  ```sql
  -- operations' status outbox
  BEGIN;
  SELECT set_config('app.bypass_rls', 'on', true);
  DELETE FROM workflow.workflow_tracker_status_outbox WHERE order_id = '<orderId>' AND dead_lettered_at IS NOT NULL;
  COMMIT;

  -- school-core's creation queue
  BEGIN;
  SELECT set_config('app.bypass_rls', 'on', true);
  DELETE FROM catalog.order_workflow_creation_queue WHERE order_id = '<orderId>' AND dead_lettered_at IS NOT NULL;
  COMMIT;
  ```

  Expect `DELETE 1`. `DELETE 0` means the bypass was not on in that transaction, or the row is not dead-lettered. The
  next `ensure` call queues a deleted creation row again, so abandon one only for an order that is gone. A `repair` row
  is the only record that its order still needs a number, and nothing queues it again (`ensure` queues a plain row). So
  never abandon a `repair` row of an order that still exists.
- **A workflow exists but nobody is assigned.** Identity answered that the school has no operator for the order's
  category, or more than one. (When identity cannot be asked, the order stays "tracker pending" and is retried
  instead.) Superadmin opens the order and assigns it in the workflow's side panel.

## The creation queue since catalog V38

Catalog V38 moved school-core's creation queue to a new table, `catalog.order_workflow_creation_queue`. The SQL in
this runbook reads it; only the SOP-release check reads the old one. Until the contract release the old table,
`catalog.notebook_tracker_creation_queue`, stays and is always empty. Why: during a release the previous school-core
revision keeps serving (on dev until the new one is ready, in prod beside it through the canary), and the tracker
revision's drain would give any queued order a notebook workflow
([design, "Review fixes: school-core and platform"](../product/item-order-workflows-sop-v1.md#review-fixes-school-core-and-platform)).

- **The forwarding trigger** (until the contract release). `trg_notebook_tracker_creation_queue_forward` is a `BEFORE
  INSERT` trigger on the old table, with function `catalog.forward_to_order_workflow_creation_queue()`. It puts each row
  a tracker revision queues into the new table, with fresh retries, and writes nothing to the old one. That revision's
  own attempt and drain then find nothing, and its status push deletes nothing that matters. The trigger runs with the
  caller's rights, under the same row-level security policy.
- **`repair = true` rows.** The deferred constraint trigger `trg_catalog_orders_queue_unnumbered_placement` (on
  `catalog.catalog_orders`, function `catalog.queue_unnumbered_placement()`) catches an order that leaves DRAFT and
  still has no number when its transaction commits, in a category with an item code. It queues that order with
  `repair = true`. Two kinds of placement need it:
  - every placement by a school-core older than the SOP release, which never mints a number (prod's `main`);
  - on the SOP release, the legacy superadmin status update. A legacy (form v1) order moved out of DRAFT through `PATCH
    /api/v1/{catalog|supply|sa}/orders/{id}/status`, or All orders → Update status, is not numbered in that transaction
    (`CatalogReadRepository.updateOrderStatus`). The contract release makes that path mint. Both placing paths of the
    SOP release (the structured form's PLACE and the legacy `placeOrder`) mint in the placing transaction.

  The next claim of a repair row, in a transaction of its own under the order's row lock:
  1. mints the number, dated by the placement;
  2. moves an open structured order of a template category (a flier, flex and the rest) onto its workflow, as V38 moved
     the orders open at the release;
  3. tells reporting when either changed (school-core logs `Order <id> was placed without an order number and is
     repaired: number <number>`);
  4. sets `repair = false`, then creates the workflow as for any other row. An order that runs on no workflow (a tie,
     say) needed only its number, so its row is dropped.

  A repair that fails keeps the backoff and records `the order could not be repaired: <reason>` in `last_error`. The
  trigger never fails a placement. When it cannot queue the order, PostgreSQL logs a WARNING in the Cloud SQL
  instance's PostgreSQL log, and nothing else says so: `Catalog order <id> was placed without an order number and could
  not be queued for repair: <reason>`. Once the contract release serves, a repair row is expected only while a revision
  older than the SOP release serves. A later one means a placement path that does not mint. The repair still numbers
  the order, but report it.
- **Orders placed between V37 and V38.** V37 commits on its own. The previous revision keeps placing orders without
  numbers until V38 takes its locks, or for longer if V38 fails and is re-run later. V38 queues every such order of a
  coded category with `repair = true`, so the same claim numbers them.
- **If V38 fails with a lock error.** V38 first takes `catalog_orders` and `notebook_tracker_creation_queue` together,
  never waiting while it holds one, so it cannot deadlock with the previous revision. See
  [When a migration job fails](#when-a-migration-job-fails).

## Clean-up

### What the contract release removes

| Object | Created by | Why nothing serving needs it |
| --- | --- | --- |
| `catalog.notebook_tracker_creation_queue`, its trigger `trg_notebook_tracker_creation_queue_forward`, and `catalog.forward_to_order_workflow_creation_queue()` | catalog V28; V38 | Only a school-core from the tracker release writes the old table. SOP school-core queues into `order_workflow_creation_queue`, and `main` predates the table |
| `copy_retired_stage_note` and `workflow.copy_retired_stage_note()`, then `workflow.workflow_instance_steps.stage_note`, `stage_note_saved_at` and `stage_note_saved_by` | workflow V8; V21 | Only the tracker's operations wrote stage notes. SOP operations reads and writes step rows with explicit column lists that leave them out, and `main` predates the table |
| `POST /api/v1/internal/notebook-trackers` and `…/{orderId}/image-access`, and school-core's fallback to them | Tracker release | SOP school-core never creates on the alias, and asks it about design files only when the new path answers 404 or an empty-body 403, which the SOP engine never does. `main` never calls operations |

`catalog.product_order_workflow_steps.stage_note` is a different column and stays: the creation queue still imports
it. The retired V36 tables stay too; school-core still reads them.

- **Order inside each migration.**
  - V40 locks the old queue and `catalog_orders` in ACCESS EXCLUSIVE mode with the `NOWAIT` loop. It moves any leftover
    row into the new queue under the bypass, then drops the table (the trigger goes with it), then the function. The
    function cannot be dropped while the trigger exists.
  - V22 first locks `workflow_instance_steps` in ACCESS EXCLUSIVE mode with the same `NOWAIT` loop, so nothing writes
    a note between its copy and its check. It copies the last stage notes under the bypass, and stops, naming each
    note it cannot copy. It drops the trigger before the columns, which cannot be dropped while the trigger exists.
  - A guard written without the bypass sees no rows, passes, and then drops data.
- **Locks.** Dropping the old queue drops its foreign key into `catalog_orders`, which takes ACCESS EXCLUSIVE on
  `catalog_orders`. While V40 holds it, order reads and writes wait. V22 needs ACCESS EXCLUSIVE on
  `workflow_instance_steps`, which every workflow list and detail read touches. Both take their lock with the `NOWAIT`
  loop, which never queues behind a reader, so readers are not stalled while it retries. Each stops after about 15
  seconds with its own `stayed busy` message, having changed nothing; re-run the job
  ([When a migration job fails](#when-a-migration-job-fails)).
- **Safe on dev.** Every dev service serves the SOP release, so each previous revision during the contract release is
  an SOP one, which uses none of the three. On 2026-11-13 dev had 0 rows in the old queue and 0 non-blank stage notes.
  Confirm before the release, read-only:

  ```sql
  -- SOP release only, before the contract release (both objects are gone after catalog V40 and workflow V22).
  SET default_transaction_read_only = on;
  BEGIN;
  SELECT set_config('app.bypass_rls', 'on', true);
  -- The old queue: always 0 (the forwarding trigger stores nothing).
  SELECT count(*) AS old_queue_rows FROM catalog.notebook_tracker_creation_queue;
  -- Stage notes that were never copied into the stage's entries, and notes with no recorded author (both must be 0
  -- before workflow V22 drops the columns; V22 stops and names them otherwise).
  SELECT count(*) FILTER (WHERE NOT EXISTS (
             SELECT 1 FROM workflow.workflow_internal_notes n
             WHERE n.instance_id = s.instance_id AND n.step_order = s.step_order AND n.body = s.stage_note))
             AS stage_notes_not_copied,
         count(*) FILTER (WHERE s.stage_note_saved_by IS NULL) AS stage_notes_without_author
  FROM workflow.workflow_instance_steps s
  WHERE btrim(coalesce(s.stage_note, '')) <> '';
  COMMIT;
  ```

- **Safe in prod's single job.** Prod's previous revisions are `main` or older, which predate the tracker. They have no
  `notebook_tracker_creation_queue` (catalog V28) and no `workflow_instance_steps` (workflow V8), and never call the
  aliases.
  - `notebook_tracker_creation_queue` is created (V28) and dropped (V40) inside school-core's one migration job.
  - `workflow_instance_steps` is created (V8) inside operations' one job, and is still empty when V22 drops its
    `stage_note` columns. The table stays.
- **Rollback floor.** After the contract release, dev's school-core and operations must not go back below the SOP
  release. A tracker-release school-core inserts into the dropped table inside the placing transaction, so every
  notebook placement fails. A tracker-release operations selects `stage_note`, so every workflow read fails. A one-step
  dev rollback lands on the SOP revision, which is safe. Prod's rollback to `main` does not touch any of the three.

### What stays, and when it can go

| Bridge | What it does | Who still needs it |
| --- | --- | --- |
| `trg_catalog_orders_queue_unnumbered_placement` and `catalog.queue_unnumbered_placement()` (catalog V38) | Queues for repair an order that leaves DRAFT without a number | Every school-core older than the SOP release (prod's `main`). Also the SOP release's legacy status update, until the contract release's mint fix serves |
| `trg_reporting_event_inbox_catalog_order_number` and `reporting.copy_catalog_order_number()` (reporting V36) | Copies an event's number onto a fact that lacks it when any revision marks the event processed | Every platform older than the SOP release (prod's `main`), whose projector ignores `orderNumber` |

The `repair` column stays as long as the code reads it. The placement trigger is load-bearing on dev today:

- On the scratch database above, the legacy branch of `updateOrderStatus`, its UPDATE run verbatim as
  `ims_school_core_rt` in a request scoped to one school, moved a FLEX draft out of DRAFT with no number.
- The trigger alone queued it, with `repair = true`, at commit.
- The clean-up SQL this runbook carried before, which started with `DROP TRIGGER IF EXISTS
  trg_catalog_orders_queue_unnumbered_placement`, would have left such orders unnumbered for good. Do not use it.

**Conditions.** Ship the final clean-up only when all of these hold:

1. The contract release's mint fix serves on every school-core revision in dev and in prod.
2. The release that brought the SOP release and the mint fix to prod has completed: school-core's and platform's
   rollouts reached stable, and no `main` revision of either takes traffic.
3. The clean-up ships in a later release than that one, in every environment. A one-step rollback then lands on that
   release, never on `main`: prod's `gcloud deploy targets rollback` picks the last successful release, and dev moves to
   the immediately older revision. Migrations are shared, so merge the clean-up to `dev` only after condition 2 holds
   in prod. Merged earlier, it would reach prod in the same promotion as V38.
4. The read-only pre-check below is clean in dev and in prod: both its SQL and its log reads. The log reads are what
   show that no placement path still leaves DRAFT without a number.

To see what serves in prod, check each service's traffic and its last rollout:

```bash
gcloud run services describe custoking-school-core-service-prod --project=custoking-prod --region=asia-south2 --format="yaml(status.traffic)"
gcloud deploy rollouts list --delivery-pipeline=custoking-school-core-service-prod --release=<rel-prod-…> --project=custoking-prod --region=asia-south2 --format="table(name.basename(),state)"
```

Run the same for `platform-service`. The release names are in the release run's `release-evidence-prod-<sha>`
(`deployment.json`).

**Record when the mint fix serves.** The pre-check's log read needs `MINT_FIX_FROM`: the first time at which the mint
fix serves on every school-core revision and the release's own repair rows have drained. Record it after the release
that brings the mint fix (the contract release on dev; in prod, the release that carries it):

1. Wait until the fix serves everywhere. On dev, that is when the new school-core revision is Ready (found as for
   `SINCE`). In prod, it is when school-core's rollout completes. `main`'s placements during the canary log the repair
   line legitimately, and are repaired within minutes.
2. Then run pre-check query 2 until it returns no rows, and record that time (UTC) in the release notes.

If it was not recorded, use school-core's Ready or rollout-completion time plus one hour. A line just after that may
still be a release leftover whose repair kept failing: look its order up before acting on it.

**Pre-check** (read-only, in dev and again in prod). It has two parts: SQL for what is queued now, and a log read for
what was repaired before. The SQL alone cannot show that a placement path stopped skipping the number:

- A repair row lasts about a minute. The next claim repairs the order and clears `repair`
  (`NotebookTrackerCreationQueue.repair`). The row is then deleted, at once for an order on no workflow, or when its
  workflow is created. So a path that skipped minting days before the pre-check leaves no row.
- A row's `created_at` cannot date the placement either. The trigger's `ON CONFLICT (order_id) DO UPDATE SET repair =
  true` keeps an existing row's older `created_at`.
- The lasting trace of a repair is school-core's INFO line `Order <id> was placed without an order number and is
  repaired: number <n>`. It is logged whenever a repair changes its order, by numbering it or moving it onto its
  workflow (`NotebookTrackerCreationQueue.repair`). The trigger queues a repair only for an order that left DRAFT with
  no number, so after the mint fix every such line names one.

```sql
-- Read-only. Before the final clean-up, in dev and again in prod.
SET default_transaction_read_only = on;
BEGIN;
SELECT set_config('app.bypass_rls', 'on', true);
-- 1. Placed orders of a coded category still without a number (expect none; each needs a live repair row until numbered).
SELECT co.id, co.category, co.status, q.repair, q.attempts, q.dead_lettered_at
FROM catalog.catalog_orders co
JOIN catalog.product_categories pc ON pc.code = co.category AND pc.order_code IS NOT NULL
LEFT JOIN catalog.order_workflow_creation_queue q ON q.order_id = co.id
WHERE co.status <> 'DRAFT' AND co.order_number IS NULL
ORDER BY co.id;
-- 2. Repair rows still queued (expect none). A dead-lettered one must be revived first. A pending one is an order placed
--    without a number moments ago, or a repair that keeps failing (see last_error). Neither is clean.
SELECT order_id, attempts, next_attempt_at, dead_lettered_at, last_error
FROM catalog.order_workflow_creation_queue WHERE repair ORDER BY order_id;
-- 3. Reporting facts that lack a number their order has (expect 0).
SELECT count(*) AS facts_missing_number FROM reporting.fact_catalog_order f
JOIN catalog.catalog_orders co ON co.id = f.id
WHERE f.order_number IS NULL AND co.order_number IS NOT NULL;
-- 4. Both bridges are still there; each clean-up migration drops its pair without IF EXISTS.
SELECT p.oid::regprocedure AS function, t.tgrelid::regclass AS on_table, t.tgname, t.tgenabled
FROM pg_proc p LEFT JOIN pg_trigger t ON t.tgfoid = p.oid
WHERE p.oid IN (to_regprocedure('catalog.queue_unnumbered_placement()'), to_regprocedure('reporting.copy_catalog_order_number()'));
COMMIT;
```

The log read. `MINT_FIX_FROM` is the time recorded after the release that brought the mint fix to that environment
(see *Record when the mint fix serves*, above):

```bash
E=dev   # or prod
P=custoking-$E
MINT_FIX_FROM=2026-11-20T00:00:00Z   # replace with the recorded time (UTC)
# How many days the _Default bucket keeps.
gcloud logging buckets describe _Default --location=global --project=$P --format="value(retentionDays)"
# Every repair since the mint fix served everywhere. Expect none.
gcloud logging read "resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"custoking-school-core-service-$E\" AND jsonPayload.message:\"was placed without an order number and is repaired\" AND timestamp>=\"$MINT_FIX_FROM\"" --project=$P --limit=50 --format="value(timestamp,resource.labels.revision_name,jsonPayload.message)"
```

- **Where the line is kept.** The line is INFO, so it lands only in the `_Default` bucket. The compliance sink keeps
  only request logs, errors, and audit or security events (`deploy/gcp/observability/compliance_logging.tf`).
- **How far back it reaches.** A new project's `_Default` keeps 30 days unless changed. The old `custoking` project's
  was set to 7 to save cost (`docs/PLANNED-CHANGES-EXECUTION-2026-08-11.md:206`). The first command prints the real
  figure. If `MINT_FIX_FROM` is older than that, the read covers only the retained days, and a line from before them is
  gone. So run the read once per retention period from `MINT_FIX_FROM` until the clean-up ships (weekly if it keeps 7
  days), and record each result with its window in the release notes. The pre-check counts as clean only when those
  windows cover the whole time since `MINT_FIX_FROM`.
- **What it cannot show.** It sees only placements that happened in its window. A path nobody used then is not
  covered. For the legacy status update, the evidence is the contract release's own test
  (`ProductOrderWorkflowIntegrationTest.aLegacyStatusOverrideOutOfDraftMintsTheNumberInThatTransaction`, `b2186ea9`).

Clean means: no rows from 1 or 2; 0 from 3; both bridges listed in 4; and no line from the log reads, whose windows
together cover the time since `MINT_FIX_FROM`. Each line names an order placed without a number after the mint fix
served. Find the path that placed it, from the order's request logs just before the line, and keep the trigger until
that path mints.

**The migrations.** These are migrations, not steps to run by hand. Each takes its own lock with the `NOWAIT` loop, as
V38 does: Flyway commits every version separately, so no earlier version's lock is still held. Each sweeps under the
bypass before it drops anything, and drops without `IF EXISTS`. Replace `<n>` and `<m>` with the next free versions.

```sql
-- catalog V<n>__retire_unnumbered_placement_repair.sql: only once every condition above holds.
-- Dropping a trigger takes ACCESS EXCLUSIVE on catalog_orders. Take it without queueing behind a reader (NOWAIT), retry
-- for up to 15 seconds, and stop having changed nothing, as catalog V38 does.
SET LOCAL lock_timeout = '15s';
DO $$
BEGIN
    FOR attempt IN 1..150 LOOP
        BEGIN
            LOCK TABLE catalog.catalog_orders IN ACCESS EXCLUSIVE MODE NOWAIT;
            RETURN;
        EXCEPTION WHEN lock_not_available THEN
            PERFORM pg_sleep(0.1);
        END;
    END LOOP;
    RAISE EXCEPTION 'V<n>: catalog_orders stayed busy for 15 seconds; nothing was changed, re-run the migration job';
END $$;

-- A last sweep, as V38 step 2b: an order placed without a number before the lock is queued for repair, so it still gets
-- one (the repair column and the code stay). The owner sees no rows of a forced-RLS table without the bypass.
SET LOCAL app.bypass_rls = 'on';
INSERT INTO catalog.order_workflow_creation_queue (order_id, school_id, repair)
SELECT co.id, co.school_id, true
FROM catalog.catalog_orders co
JOIN catalog.product_categories pc ON pc.code = co.category AND pc.order_code IS NOT NULL
WHERE co.status <> 'DRAFT' AND co.order_number IS NULL
ON CONFLICT (order_id) DO UPDATE SET repair = true;

-- No IF EXISTS: a missing trigger means this database is not in the state this migration was written for.
DROP TRIGGER trg_catalog_orders_queue_unnumbered_placement ON catalog.catalog_orders;
DROP FUNCTION catalog.queue_unnumbered_placement();
```

```sql
-- reporting V<m>__retire_order_number_copy.sql: only once every condition above holds.
SET LOCAL lock_timeout = '15s';
DO $$
BEGIN
    FOR attempt IN 1..150 LOOP
        BEGIN
            LOCK TABLE reporting.reporting_event_inbox IN ACCESS EXCLUSIVE MODE NOWAIT;
            RETURN;
        EXCEPTION WHEN lock_not_available THEN
            PERFORM pg_sleep(0.1);
        END;
    END LOOP;
    RAISE EXCEPTION 'V<m>: reporting_event_inbox stayed busy for 15 seconds; nothing was changed, re-run the migration job';
END $$;

-- A last copy, as V36 step 1: a fact that still lacks a number its events carry gets it. fact_catalog_order forces RLS.
SET LOCAL app.bypass_rls = 'on';
WITH events AS (
    SELECT CASE WHEN pg_input_is_valid(payload, 'jsonb') THEN payload::jsonb END AS body, received_at
    FROM reporting.reporting_event_inbox
    WHERE event_type = 'catalog-order.upserted.v1'
), numbered AS (
    SELECT DISTINCT ON (body ->> 'id') body ->> 'id' AS order_id, body ->> 'orderNumber' AS order_number
    FROM events
    WHERE jsonb_typeof(body) = 'object' AND body ->> 'id' IS NOT NULL
      AND length(body ->> 'orderNumber') BETWEEN 1 AND 40
    ORDER BY body ->> 'id', received_at DESC
)
UPDATE reporting.fact_catalog_order fact
SET order_number = numbered.order_number
FROM numbered
WHERE fact.id = numbered.order_id AND fact.order_number IS NULL;

DROP TRIGGER trg_reporting_event_inbox_catalog_order_number ON reporting.reporting_event_inbox;
DROP FUNCTION reporting.copy_catalog_order_number();
```

Evidence from the scratch database of 2026-11-13 (the migrations as V41 and V37):

- With a reader holding `catalog_orders` for 20 s, the catalog migration stopped after 16 s with its `stayed busy`
  message, and the trigger was still there.
- With a reader holding it for 3 s, the migration finished in about 3 s. A new reader with a 1 s statement timeout,
  started during the retries, was not blocked.
- The sweep queued both placed, unnumbered orders (a flier and a tie) for repair, and the trigger and function were
  gone.
- The reporting migration filled the one fact that lacked its number, then dropped its trigger and function.
- On 2026-11-14 both ran again, after the contract release's real V40 and V22. The catalog one's sweep reached the
  three placed, unnumbered orders, each already queued for repair (`INSERT 0 3`). The reporting one filled the one
  fact. Pre-check query 4 then listed neither pair.

## Production

Production is on hold (owner decision, 2026-11-12). When it resumes, the promotion carries this whole feature in one
release. Follow [the release operator runbook](release-operator.md) and do the following.

**Before the promotion**

- **Release prerequisites, outside this runbook.** On 2026-11-13 prod had not had its runtime database role cutover.
  The deployed prod Cloud Deploy targets (last updated 2026-09-26) lacked the runtime database role parameters, prod
  had no `*-runtime-db-password-prod` secrets, and the services still connected as `app_rt`. Promoting before that
  cutover and a prod configuration reconciliation renders dev-only values and stops the release partway. Re-check
  both first.
- **The three invoker bindings.** Prod lacked all three on 2026-11-12. Without them, every new prod workflow order stays
  "tracker pending" and is dead-lettered after about two days. Apply them with the script's production flags, after the
  dry run shows an empty `preflight`:

  ```powershell
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\configure-runtime-service-accounts.ps1 -ProjectId custoking-prod -Environment prod
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\configure-runtime-service-accounts.ps1 -ProjectId custoking-prod -Environment prod -Apply -AllowProduction -RuntimeDatabaseRolesPrepared
  ```

  Pass `-RuntimeDatabaseRolesPrepared` only once the five services' runtime database roles and secrets are prepared
  and verified in prod; the script refuses `-Apply` in prod without it. If the whole script cannot run yet, the three
  bindings alone are the `gcloud run services add-iam-policy-binding` commands above, with `prod` for `dev` in every
  name.
- **The relay schedulers.** Check them as above, with `custoking-prod` and the `-prod` job names. The scheduler script
  needs `-Environment prod -AllowProduction`.
- **What the order form flag does now.** `CATALOG_PRODUCT_FORM_ENABLED` decides whether the structured order form is
  offered. With it off, new placements use the legacy form: they get an order number but no workflow. There is no
  workflow flag (design D43). A category's `workflow_definition` set to null stops its new placements from starting a
  workflow, and V38 still moves open structured orders onto workflows whatever the flag says.
  - `deploy/clouddeploy/targets-prod.yaml` says `"false"`, but on 2026-11-12 the serving prod school-core had `true`,
    and the deployed target had no such parameter, so the manifest's literal `"true"` ships.
  - Read both before promoting, and decide with the owner:

    ```bash
    gcloud run services describe custoking-school-core-service-prod --project=custoking-prod --region=asia-south2 --format="yaml(spec.template.spec.containers[0].env)"
    gcloud deploy targets describe school-core-service-prod --project=custoking-prod --region=asia-south2 --format="yaml(deployParameters)"
    ```

- **What the migrations will touch.** Read prod's heads and counts first, against `main`'s schema, read-only:

  ```sql
  -- Prod, before the first SOP release: what catalog V37 and V38 will touch. Read-only; runs on main's schema.
  SET default_transaction_read_only = on;
  BEGIN;
  SELECT set_config('app.bypass_rls', 'on', true);
  -- Schema heads now (compare with the table in "Migration job results").
  (SELECT 'tenant_school' AS schema, version FROM tenant_school.flyway_schema_history_tenant_school ORDER BY installed_rank DESC LIMIT 1)
  UNION ALL (SELECT 'catalog', version FROM catalog.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1)
  UNION ALL (SELECT 'workflow', version FROM workflow.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1)
  UNION ALL (SELECT 'reporting', version FROM reporting.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1);
  -- Placed orders V37 will number (the categories it gives a code).
  SELECT category, count(*) AS placed_orders FROM catalog.catalog_orders
  WHERE category IN ('NOTEBOOKS', 'FLIERS', 'FLEX', 'BILLBOOKS', 'CERTIFICATES', 'FLUTE_BOARDS', 'REPORT_CARDS',
                     'BROCHURES', 'BELTS', 'STANDEES', 'TIES', 'TROPHIES')
    AND status <> 'DRAFT'
  GROUP BY 1 ORDER BY 1;
  -- Open structured orders V38 will move onto a workflow (the template categories other than notebooks).
  SELECT category, status, count(*) AS orders_to_move FROM catalog.catalog_orders
  WHERE category IN ('FLIERS', 'FLEX', 'BILLBOOKS', 'CERTIFICATES', 'FLUTE_BOARDS', 'REPORT_CARDS', 'BROCHURES',
                     'BELTS', 'STANDEES')
    AND form_version = 2
    AND status NOT IN ('DRAFT', 'CANCELLED', 'DELIVERED', 'FULFILLED', 'REJECTED', 'RETURNED', 'COMPLETED')
  GROUP BY 1, 2 ORDER BY 1, 2;
  COMMIT;
  ```

  Each order counted in the last query gets a workflow. Prod has no V36 progress to import, because catalog V36 arrives
  in the same job and creates its tables empty. So each of these orders starts at step 1, and once operations pushes,
  its status becomes `ORDER_DETAILS`, whatever approval or production state it was in. School-core accepts that push for
  any open structured order on a workflow (`CatalogOrderFormService.applyTrackerStatus`). Review the list with the
  owner before promoting.

**During the release.** The release runs in one Cloud Deploy pass, with the windows listed under
[Release order](#release-order-and-the-windows-between-services). What differs from dev:

- `main` is every previous revision. It never touches the tracker's tables or routes, so the forwarding trigger and
  V21's stage-note trigger have nothing to do.
- Every order `main` places after V37 is numbered later. V38 queues those placed before its lock, and the
  placement-repair trigger queues those placed after it, until school-core's rollout completes. The reporting trigger
  fills in numbers until platform's rollout completes.
- The frontend serves the new screens long before operations does. Order workflows answers errors until operations'
  canary starts, and works for every request only once its rollout completes.
- Design-file changes on workflow orders answer 503 from school-core's canary until operations serves.
- Creations wait with the backoff from school-core's canary until operations serves: up to an hour per attempt.

**After the release**

1. Run the [post-deploy checks](#post-deploy-checks) with `E=prod`.
2. Users signed in before identity's new revision must sign out and back in, or reload, to see Order workflows. Server
   checks always read the current permissions.
3. Once operations' rollout has completed, bring forward every creation still waiting on the backoff, instead of
   waiting up to an hour or reviving them one at a time with `ensure`. The relay trigger then takes five a minute:

   ```sql
   BEGIN;
   SELECT set_config('app.bypass_rls', 'on', true);
   UPDATE catalog.order_workflow_creation_queue SET next_attempt_at = now()
   WHERE dead_lettered_at IS NULL AND next_attempt_at > now();
   COMMIT;
   ```

   `UPDATE 0` with rows still waiting means the bypass was not on. A dead-lettered row is left alone; revive it as
   under [When it fails](#when-it-fails).
4. If the release carries the contract release's mint fix, record `MINT_FIX_FROM` once school-core's rollout has
   completed, as under *Record when the mint fix serves* in
   [What stays, and when it can go](#what-stays-and-when-it-can-go). The final clean-up's log read starts there.

## Rollback

Roll the services back as in [rollback](rollback.md). The invoker bindings are harmless without the new revisions and
can stay. Migrations are forward-only, and these outlive a rollback:

- **Identity.** Earlier identity revisions ignore `identity.user_role_assignments.catalog_category`, so they would treat
  an item-scoped operator (for example NOTEBOOKS only) as a school-wide one. Revoke item-scoped assignments before
  rolling identity back to a revision older than the tracker release.
- **Statuses.** A workflow order's status is its step code (for example `PRE_PRINT_QUALITY_CHECK` or `FINAL_APPROVAL`
  on the 9-step templates), which earlier school-core revisions do not know.
- **Frontend.** Roll the frontend back together with operations. A frontend older than the SOP release calls the
  removed `/api/v1/workflows/notebook-orders/**` routes, and they answer 404.
- **Operations back to the tracker release.** It cannot save vendor selections after workflow V21, because the check
  refuses its format, and it could not read them correctly after V19 anyway.
- **After the contract release.** Dev's school-core and operations must not go below the SOP release (see
  [Clean-up](#what-the-contract-release-removes)). A one-step rollback is safe.
- **After the final clean-up.** A school-core older than the mint fix leaves legacy status placements unnumbered, and a
  platform older than the SOP release leaves new facts without numbers. Nothing repairs either. The conditions under
  [Clean-up](#what-stays-and-when-it-can-go) keep a one-step rollback above both.
