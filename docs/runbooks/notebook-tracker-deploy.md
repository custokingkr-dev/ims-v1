# Notebook order tracker: deploy to dev and probe

Scope: `custoking-dev`, Cloud Run in `asia-south2`. Every cloud command passes `--project=custoking-dev`; the
operator's default project may be production. Production is out of scope: `catalog_product_form_enabled` stays
`"false"` there, so no prod order reaches the tracker.

The tracker's service-to-service calls use internal routes guarded by Cloud Run IAM plus each service's
`MachineCallerFilter` allow-list ([review-fix design §3.1](../superpowers/specs/2026-10-20-notebook-tracker-review-fixes-design.md)):

| Caller → callee | Route | Callee allow-list env |
| --- | --- | --- |
| school-core → operations | `POST /api/v1/internal/notebook-trackers`, `POST .../{orderId}/image-access` | `NOTEBOOK_TRACKER_CALLER_SERVICE_ACCOUNTS` |
| operations → school-core | `GET /api/v1/internal/catalog-orders/{orderId}/draft-images`, `PUT .../tracker-status` | `CATALOG_TRACKER_CALLER_SERVICE_ACCOUNTS` |
| school-core → identity | `GET /api/v1/internal/operator-directory/resolve` | `OPERATOR_DIRECTORY_RESOLVE_CALLER_SERVICE_ACCOUNTS` |
| operations → identity | `GET /api/v1/internal/operator-directory/eligible` | `OPERATOR_DIRECTORY_ELIGIBILITY_CALLER_SERVICE_ACCOUNTS` |

## What the release carries

- Manifests (`deploy/cloudrun/`): the four allow-lists above, rendered from the existing `env` and `project_id`
  target parameters (`ims-<caller>-${env}@${project_id}.iam.gserviceaccount.com`), and the callers' peer URLs:
  school-core `CATALOG_TRACKER_BASE_URL` (`${operations_url}`) and `CATALOG_OPERATOR_DIRECTORY_BASE_URL`
  (`${identity_url}`), operations `OPERATIONS_IDENTITY_BASE_URL` (`${identity_url}`), each with `*_CLOUD_RUN_AUTH: auto`.
  `deploy/clouddeploy/targets-*.yaml` are unchanged. Because `deploy/cloudrun/*` changed, the dev release goes
  through Cloud Deploy rather than the fast direct path.
- Migrations: identity `V11`–`V12`, operations `workflow/V8`–`V11`, school-core `catalog/V27`–`V28`. Each Java
  service finishes its isolated migration job before its release is created ([release operator](release-operator.md)).
- Ship identity, school-core and operations from one commit: school-core now requires the `trackerVersion` that
  operations sends. While the services roll out one after another, a placement can hit an operations revision that
  does not have the internal route yet; that order stays "tracker pending" and the creation queue retries it.

Nothing in CI grants the invoker roles. Apply them separately, before or right after the release.

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
3. **No machine call was refused.** This should return nothing:

   ```powershell
   gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name=("custoking-operations-service-dev" OR "custoking-identity-service-dev" OR "custoking-school-core-service-dev") AND httpRequest.status=403 AND httpRequest.requestUrl:"/api/v1/internal/"' --project=custoking-dev --freshness=1h --limit=20
   ```

The probe passes only when all three hold. Record the order ID and the timestamps, not the order's contents.

### When it fails

- **The order stays "tracker pending"** (step 1), or **the status does not move** (step 2): school-core's creation
  queue and operations' status outbox both retry on the existing relay trigger (`POST /api/v1/internal/outbox/relay`,
  Cloud Scheduler every minute) with a growing backoff, so nothing is lost while you fix the cause. Superadmin can
  retry a pending order at once with `POST /api/v1/supply/orders/{orderId}/tracker/ensure`.
- **A 403 on an internal route:** a missing invoker binding (Cloud Run refuses the request before it reaches the
  container) or an allow-list that does not name the caller (the service answers `MACHINE_CALLER_REJECTED`). Check
  the grants first, then the callee's `*_CALLER_SERVICE_ACCOUNTS` value.
- **A 404 on an internal route:** the callee revision predates this release; it resolves once every service has
  rolled out.

## Rollback

Roll the services back as in [rollback](rollback.md). The invoker bindings are harmless without the new revisions and
can stay. Migrations are forward-only, and two things outlive a rollback once the feature has been used:

- Earlier identity revisions ignore `identity.user_role_assignments.catalog_category`, so they would treat an
  item-scoped operator (for example NOTEBOOKS only) as a school-wide one. Revoke item-scoped assignments before
  rolling identity back.
- Orders placed on the tracker keep a tracker step code as their status, which earlier school-core revisions do not
  know.
