# Notebook order tracker: deploy to dev and probe

Scope: `custoking-dev`, Cloud Run in `asia-south2`. Every cloud command passes `--project=custoking-dev`; the
operator's default project may be production. Production is out of scope: `catalog_product_form_enabled` stays
`"false"` there, so no prod order reaches the tracker. Before that flag is ever flipped in production, prod needs the
same three invoker bindings, applied with the production flags (see [Production](#production)).

The tracker's service-to-service calls use internal routes guarded by Cloud Run IAM plus each service's
`MachineCallerFilter` allow-list ([review-fix design §3.1](../superpowers/specs/2026-10-20-notebook-tracker-review-fixes-design.md)):

| Caller → callee | Route | Callee allow-list env |
| --- | --- | --- |
| school-core → operations | `POST /api/v1/internal/notebook-trackers`, `POST .../{orderId}/image-access` | `NOTEBOOK_TRACKER_CALLER_SERVICE_ACCOUNTS` |
| operations → school-core | `GET /api/v1/internal/catalog-orders/{orderId}/draft-images`, `PUT .../tracker-status` | `CATALOG_TRACKER_CALLER_SERVICE_ACCOUNTS` |
| school-core → identity | `GET /api/v1/internal/operator-directory/resolve` | `OPERATOR_DIRECTORY_RESOLVE_CALLER_SERVICE_ACCOUNTS` |
| operations → identity | `GET /api/v1/internal/operator-directory/eligible` | `OPERATOR_DIRECTORY_ELIGIBILITY_CALLER_SERVICE_ACCOUNTS` |
| operations → identity | `POST /api/v1/internal/operator-directory/names` | `OPERATOR_DIRECTORY_ELIGIBILITY_CALLER_SERVICE_ACCOUNTS` |

## What the release carries

- Manifests (`deploy/cloudrun/`): the four allow-lists above, rendered from the existing `env` and `project_id`
  target parameters (`ims-<caller>-${env}@${project_id}.iam.gserviceaccount.com`), and the callers' peer URLs:
  school-core `CATALOG_TRACKER_BASE_URL` (`${operations_url}`) and `CATALOG_OPERATOR_DIRECTORY_BASE_URL`
  (`${identity_url}`), operations `OPERATIONS_IDENTITY_BASE_URL` (`${identity_url}`), each with `*_CLOUD_RUN_AUTH: auto`.
  `deploy/clouddeploy/targets-*.yaml` are unchanged. Because `deploy/cloudrun/*` changed, the dev release goes
  through Cloud Deploy rather than the fast direct path.
- Migrations: identity `V11`–`V12`, operations `workflow/V8`–`V12`, school-core `catalog/V27`–`V30`. Each Java
  service finishes its isolated migration job before its release is created ([release operator](release-operator.md)).
- Ship identity, school-core and operations from one commit: school-core now requires the `trackerVersion` that
  operations sends. While the services roll out one after another, a placement can hit an operations revision that
  does not have the internal route yet; that order stays "tracker pending" and the creation queue retries it. During
  that window the queue's `last_error` can read `operator directory answered HTTP 403`: a revision that predates the
  release answers an **empty-body 403** for any internal path it has no route for. That is not a missing grant (see *A
  403 on an internal route* below); wait until all three services serve the new revision.

Nothing in CI grants the invoker roles. Apply them separately, **before** the release (below): from the moment the new
revisions serve, every placement calls operations and identity, and without the bindings Cloud Run refuses those calls.

## Before the release: check the relay schedulers

Tracker creation (school-core) and status delivery (operations) are retried only by the existing relay trigger, which
Cloud Scheduler calls every minute. Confirm both jobs exist and are enabled (read-only):

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

Once the release serves, a 2xx relay response also carries the tracker's drain counts: `trackersCreated` from
school-core and `trackerStatusesDelivered` from operations.

## Grant the invoker roles

Dev today lacks three `roles/run.invoker` bindings this feature needs: school-core on operations, school-core on
identity, and operations on identity. They are declared in `$invokerMatrix` in
`scripts/configure-runtime-service-accounts.ps1`.

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
   part of this change; the three bindings below are this feature's entire IAM change and use the script's own flags:

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

## Check the deployed configuration

After the release, confirm the serving revisions carry the rendered values (read-only):

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
- None of the three sets `MACHINE_CALLER_LOCAL_TRUST`; it belongs only in `docker-compose.local.yml`, and Cloud Run's
  `K_SERVICE` disables it anyway.

Each peer URL is also the audience of the caller's identity token, so it must appear in the callee's
`SERVICE_OIDC_AUDIENCES`. The `*_url` target parameters and the first entry of each `service_oidc_audiences` are the
same deterministic `https://custoking-<service>-dev-<project number>.<region>.run.app` address.

## Post-deploy probe

If dev sign-in fails, the dev database may have been stopped by the cost controller; probe while it is running.

Users who were signed in before the release must sign out and back in, or reload the page, before "Notebook orders"
appears: the permissions their browser holds were issued before identity `V12` added the tracker's permission codes.
This only affects what the menu shows; every server check reads the current permissions.
Use a synthetic order at a test school whose notebook ordering uses the structured form (the `NOTEBOOKS` category
needs `form_enabled`; see [catalog order forms](../product/catalog-order-forms-expansion.md)).

1. **A new notebook order gets a tracker.** As the school's admin on the dev frontend
   (`https://custoking-frontend-dev-hd4wfwk7mq-em.a.run.app`), place a notebook order. Within a minute, as superadmin,
   open it from All Orders → View: the tracker screen opens on step 1 (Order Details), not the old order screen. Through
   the gateway, `GET /api/v1/supply/orders/{orderId}` shows `"trackerManaged": true`,
   `GET /api/v1/supply/orders/{orderId}/tracker-summary` shows `"trackerPending": false`, and
   `GET /api/v1/workflows/notebook-orders/{orderId}` answers 200.
2. **The tracker's status reaches the order.** Complete step 1 in the tracker. Within a minute the order's status
   (All Orders, or `status` in `GET /api/v1/supply/orders/{orderId}`) changes from `ORDER_DETAILS` to the next step's
   code: `DESIGNING_ACTIVITY` for a customised order, `PRODUCTION_PLANNING` for a wholesale one.
3. **Staff names resolve end to end.** Open a tracker whose steps are assigned (the order from step 1 after "Assign
   order", or any assigned one). The assignee and the note author must show **names**, not "User N". This is the only
   end-to-end proof of operations → identity names (`POST /api/v1/internal/operator-directory/names`); when it fails,
   operations logs one WARN a minute and the screen falls back to "User N".
4. **No machine call was refused.** Set `SINCE` to the time the **last** of the three services began serving the new
   revision (UTC, RFC 3339), so the empty-body 403s of the rollout window are not counted. This should return nothing:

   ```powershell
   $since = '2026-10-21T00:00:00Z'   # replace: when the last service started serving the new revision
   gcloud logging read "resource.type=`"cloud_run_revision`" AND resource.labels.service_name=(`"custoking-operations-service-dev`" OR `"custoking-identity-service-dev`" OR `"custoking-school-core-service-dev`") AND httpRequest.status=403 AND httpRequest.requestUrl:`"/api/v1/internal/`" AND timestamp>=`"$since`"" --project=custoking-dev --limit=20
   ```

The probe passes only when all four hold. Record the order ID and the timestamps, not the order's contents.

### When it fails

- **The order stays "tracker pending"** (step 1), or **the status does not move** (step 2): school-core's creation
  queue and operations' status outbox both retry on the existing relay trigger (`POST /api/v1/internal/outbox/relay`,
  Cloud Scheduler every minute) with a growing backoff, so nothing is lost while you fix the cause. The reason of the
  last failed creation is in `catalog.notebook_tracker_creation_queue.last_error` (for example `operator directory
  answered HTTP 403`; see *Reading the queues* below). Superadmin can retry a pending order at once with
  `POST /api/v1/supply/orders/{orderId}/tracker/ensure`. A creation that fails 48 times (about two days) is given up:
  school-core logs an ERROR with `event=tracker_creation_dead_lettered`, the row counts in the outbox dead-letter
  alert, and only that `ensure` call (it revives the row with fresh retries) starts it again.
- **A status is not delivered:** the reason is in `workflow.workflow_tracker_status_outbox.last_error`. A status that
  fails 48 times is given up the same way: operations logs an ERROR with `event=tracker_status_dead_lettered` (with
  `orderId` and `trackerVersion`) and the row counts in operations' outbox dead-letter figure. The order's next step
  change revives it with fresh retries; to retry it without one, see *Reading the queues*. A status school-core refuses
  because the order is cancelled (HTTP 409) or no longer exists (HTTP 404) is dropped at once, not retried: operations
  logs a WARN with `event=tracker_status_dropped`, `orderId` and `httpStatus`.
- **Reading the queues:** both tables force row-level security, and it binds the database owner too: the migration
  owner is NOSUPERUSER and NOBYPASSRLS. A plain `SELECT` therefore returns no rows, and a plain `UPDATE` reports
  `UPDATE 0` and changes nothing. Run each statement inside a transaction that turns the policy's bypass on for that
  transaction only. The `true` in `set_config` ends the bypass at `COMMIT`. Never disable row security instead.

  ```sql
  -- school-core's creation queue
  BEGIN;
  SELECT set_config('app.bypass_rls', 'on', true);
  SELECT order_id, attempts, dead_lettered_at, last_error FROM catalog.notebook_tracker_creation_queue WHERE order_id = '<orderId>';
  COMMIT;

  -- operations' status outbox
  BEGIN;
  SELECT set_config('app.bypass_rls', 'on', true);
  SELECT order_id, status, tracker_version, attempts, dead_lettered_at, last_error FROM workflow.workflow_tracker_status_outbox WHERE order_id = '<orderId>';
  COMMIT;
  ```

  To retry a dead-lettered status at once (a creation is retried with the `ensure` call above, not with SQL):

  ```sql
  BEGIN;
  SELECT set_config('app.bypass_rls', 'on', true);
  UPDATE workflow.workflow_tracker_status_outbox SET dead_lettered_at = NULL, attempts = 0, next_attempt_at = now() WHERE order_id = '<orderId>';
  COMMIT;
  ```

  Expect `UPDATE 1`, and the relay trigger delivers the status within a minute. `UPDATE 0` means the bypass was not
  on in that same transaction, or the order has no status queued.
- **A 403 on an internal route** has three causes, told apart by the body:
  - **Empty body:** the callee revision predates this release and has no such route (its `MachineCallerFilter` answers
    403 to every unregistered `/api/v1/internal/` path). Expected during the one-after-another rollout, and the queues
    retry; wait until all three services serve the new revision. If it persists after that, the route is missing from
    the callee: check its serving revision.
  - **`{"code":"MACHINE_CALLER_REJECTED"}`:** the callee's `*_CALLER_SERVICE_ACCOUNTS` value does not name the caller,
    or the identity token's audience is not in its `SERVICE_OIDC_AUDIENCES`. Check that value, then the audience.
  - **A Cloud Run IAM page (HTML, "Your client does not have permission"):** a missing invoker binding; Cloud Run
    refuses the request before it reaches the container. Check the grants.
- **A 404 on an internal route:** not a rollout symptom (an old revision answers 403, above). Check the order or path
  in the request. For operations → school-core `tracker-status`, a 404 means the order no longer exists, and the status
  is dropped (above).
- **A queue's count failed:** if a queue cannot be counted, its figures are missing from the `outbox.async.health`
  metrics until it recovers, and no alert says so. The only sign is an ERROR log, found with:

  ```powershell
  gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name=("custoking-operations-service-dev" OR "custoking-school-core-service-dev") AND jsonPayload.event="outbox_backlog_count_failed"' --project=custoking-dev --freshness=1d --limit=20
  ```

  Its `queue` field names the queue that could not be counted.
- **Abandoning a dead-lettered row:** when a dead-lettered row must never be delivered (its order is gone and will
  not return), delete it instead of reviving it; this ends its count in the dead-letter figure. In the same
  bypass-on transaction as above:

  ```sql
  -- operations' status outbox
  BEGIN;
  SELECT set_config('app.bypass_rls', 'on', true);
  DELETE FROM workflow.workflow_tracker_status_outbox WHERE order_id = '<orderId>' AND dead_lettered_at IS NOT NULL;
  COMMIT;

  -- school-core's creation queue
  BEGIN;
  SELECT set_config('app.bypass_rls', 'on', true);
  DELETE FROM catalog.notebook_tracker_creation_queue WHERE order_id = '<orderId>' AND dead_lettered_at IS NOT NULL;
  COMMIT;
  ```

  Expect `DELETE 1`. `DELETE 0` means the bypass was not on in that transaction, or the row is not dead-lettered. A
  creation row deleted this way is queued again by the next `ensure` call, so abandon it only for an order that is
  gone.
- **A tracker exists but nobody is assigned:** identity answered that the school has no notebook operator, or more
  than one. (When identity cannot be asked, the order stays "tracker pending" and is retried instead.) Superadmin opens
  the order and uses "Assign order" in the tracker's side panel.

## Production

Production is not part of this rollout. Before `catalog_product_form_enabled` is set to `"true"` in `custoking-prod`,
apply the same three invoker bindings there with the script's production flags, after the dry run shows an empty
`preflight`:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\configure-runtime-service-accounts.ps1 -ProjectId custoking-prod -Environment prod
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\configure-runtime-service-accounts.ps1 -ProjectId custoking-prod -Environment prod -Apply -AllowProduction -RuntimeDatabaseRolesPrepared
```

Pass `-RuntimeDatabaseRolesPrepared` only once the five services' runtime database roles and secrets are prepared and
verified in prod; the script refuses `-Apply` in prod without it. Check the prod relay schedulers the same way as above,
with `custoking-prod` and the `-prod` job names (the scheduler script needs `-Environment prod -AllowProduction`).

## Rollback

Roll the services back as in [rollback](rollback.md). The invoker bindings are harmless without the new revisions and
can stay. Migrations are forward-only, and two things outlive a rollback once the feature has been used:

- Earlier identity revisions ignore `identity.user_role_assignments.catalog_category`, so they would treat an
  item-scoped operator (for example NOTEBOOKS only) as a school-wide one. Revoke item-scoped assignments before
  rolling identity back.
- Orders placed on the tracker keep a tracker step code as their status, which earlier school-core revisions do not
  know.
