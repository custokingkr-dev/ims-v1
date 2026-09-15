# Notebook Builder Implementation

Implemented on 2026-09-15. Product decisions are in
[the confirmed specification](notebook-order-form-builder.md). This is a local implementation,
not a production deployment.

## Delivered Behavior

- School and superadmin entry points share the typed notebook form and saved-order view.
- Customized books total exactly 1000 across all sizes and ruling lines. A draft may be below
  or above the target; placement may not. Non-customized books have no aggregate target.
- Printed pages round to the nearest multiple of 7, with midpoint ties downward. Both requested
  and normalized values are stored. Size, ruling, dimensions and rules are snapshotted per order.
- All 15 ruling options are seeded. King and Drawing Book remain disabled pending specifications;
  FA / A4 is one option. Superadmin can change specifications, options and rules without deployment.
- Placement sets pending pricing. Only superadmin can quote persisted line quantities; line prices
  and GST are stored in paise, while the frontend accepts rupees. Complete line coverage and an
  optimistic version are required. A quote is mandatory before final approval.
- Customized orders require artwork, design approval, final approval and a pre-delivery photo.
  Non-customized orders skip design approval, but still need quoting and final approval.
- Replacing artwork before final approval invalidates its design approval. Final-approved artwork
  and delivered-order attachments cannot change. Direct status aliases use the same guards.
- Legacy orders retain their original form/workflow. Compatibility summaries and reporting events
  include form/pricing metadata. Versioned projection updates reject stale events.

## Migrations

| Service/schema | Migration | Purpose |
| --- | --- | --- |
| identity | V7 | Superadmin catalog management/quotation permissions |
| school-core/catalog | V9 | Product categories, groups, options, dependencies, rules and seeds |
| school-core/catalog | V10 | Snapshots, lines, assets, pricing metadata and tenant constraints |
| school-core/tenant_school | V27 | Assigned Operations users can read school/entitlement references, without new write access |
| platform/reporting | V30 | Form/pricing metadata and source-version ordering |

Flyway runs these additively. Existing orders default to form version 1 and `NOT_APPLICABLE`
pricing. New global definitions have no tenant key; all definition writes require the superadmin
role. Order lines/assets use composite order-school foreign keys and forced row-level security.

## Configuration

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `CATALOG_PRODUCT_FORM_ENABLED` | `false` | Enable new structured notebook creation |
| `CATALOG_ASSETS_MODE` | `gcs` | Private GCS storage; `local` is for development only |
| `CATALOG_ASSETS_BUCKET` | `STUDENT_PHOTO_BUCKET` fallback | Existing private bucket or a dedicated private order bucket |
| `CATALOG_ASSETS_LOCAL_DIRECTORY` | `.data/catalog-assets` | Local development storage root |

In GCS mode, configure application-default credentials with object read/write/delete access to
the chosen private bucket. No public ACL or public object URL is used. Missing bucket configuration
returns 503 for attachment storage; it does not silently fall back to local disk.

The global feature flag coordinates both frontend creation callers through the definition API.
Disabling it restores legacy creation while existing version-2 orders remain guarded and editable
through their saved definition. Deactivating the notebook category or its form blocks notebook
ordering instead of bypassing requirements through the legacy editor.

Validate migrations, permissions, private storage and reporting delivery in the target environment
before enabling the flag. No cloud configuration, credentials or production feature flag was changed
by this implementation.

## API Surface

All public routes below are under `/api/v1`. The gateway supplies trusted service and tenant headers;
browser callers use their normal bearer session. Direct school-core calls require the catalog service
token and trusted tenant context as in the existing service.

- `GET /supply/product-catalog/categories` and `GET /supply/product-catalog/forms/{code}` read definitions.
- Superadmin CRUD lives under `/supply/product-catalog/{categories|groups|options|rules}`. Deletion
  deactivates by default; `hard=true` only deletes unreferenced records. Codes/ownership are immutable.
- `POST /supply/orders` creates a draft; `/sa/orders` retains compatibility with the superadmin caller.
- `GET /supply/orders/{id}/form` returns the snapshot, selections, persisted lines, active assets,
  rule results, pricing state, approved artwork ID and current version.
- `PATCH /supply/orders/{id}` edits a draft using `version`, `orderData`, optional date and notes.
- `PUT /supply/orders/{id}/quote` accepts `version`, `lines: [{id, unitPricePaise}]` and `gstPaise`.
- Existing `place`, `design-approved`, `superadmin-approve` and `deliver` actions use the shared guards.
- `POST /supply/orders/{id}/assets` accepts multipart `assetKind` and `file`.
- `GET /supply/orders/{id}/assets` includes superseded metadata. Authenticated content lives at
  `/supply/orders/{id}/assets/{assetId}/content`; `DELETE` supersedes the active attachment.

Creation/placement require `order:create`; draft/asset changes require `order:update`; reads require
`order:read`. Design approval and delivery additionally require Operations or superadmin. Final
approval and quoting additionally require superadmin. The normal school module gate remains active.

Assets are byte-preserving PNG/JPEG/WebP/PDF documents up to 5 MiB. Pre-delivery evidence must be an
image. Images are decoded and limited to 40 megapixels; PDFs must be unencrypted, script-free and
1-200 pages. WebP decoding uses TwelveMonkeys ImageIO. Identical active uploads are idempotent;
superseded versions remain private and auditable. Storage keys use an immutable per-upload UUID.

## Verification

Final local checks on 2026-09-15:

| Check | Result |
| --- | --- |
| Identity, school-core and platform Maven suites | 1,151 tests passed; no failures, errors or skips |
| API gateway suite and route inventory contract | 78 tests passed; inventory check passed |
| Frontend Vitest suite | 212 tests passed across 42 files |
| Playwright suite | 54 tests passed, including five notebook workflow cases |
| Frontend production build and Java packaging | Passed |
| Local identity, school-core and platform health | All `UP`; frontend returned HTTP 200 |

The first school-core packaging attempt encountered a Windows lock from the running local service;
stopping that process and rerunning packaging succeeded. Three PowerShell-based Python CI checks
were skipped because `pwsh` is unavailable on this machine; the new shared-fixture selection case
was also exercised directly with Windows PowerShell and passed.

The implementation consolidates the proposed line/asset/quote/transition test classes into
`CatalogOrderFormIntegrationTest`. It covers real PostgreSQL transactions, snapshots, exact totals,
private attachments, quote coverage/versioning, aliases, and an actual `app_rt` Operations connection
with the real module guard. Storage, controller, catalog CRUD, legacy, projection and RLS tests add
focused coverage. Both rule implementations consume `contracts/catalog-form-rule-fixtures.json`;
changing that fixture selects both frontend and school-core in CI.

Local end-to-end API verification used real login, gateway, non-owner database credentials and local
private storage: 400 Long + 600 Jumbo Long books, 198 -> 196 pages, missing-artwork rejection, cross-school
denial, school quotation denial, stale quote rejection, design/final approval, missing-delivery-photo
rejection, successful delivery, and a 25-book non-customized order proceeding without artwork.

Frontend browser coverage is in `frontend/e2e/notebook-orders.spec.ts`. Desktop (1440 px) and mobile
(390 px) evidence and the independent Impeccable review are stored locally under `.impeccable/review/`.
The reviewer used the skill's fallback reviewer instructions because this harness has no specialized
agent loader. Its final disposition is `ship` for the three scored corrections: mobile quotation
field visibility, explicit GST currency, and persisted product context. All three were resolved;
that verdict is not a blanket assessment of unrelated application surfaces.
Runtime logs and local smoke helpers are under ignored `artifacts/notebook-dev/`.
The [scoped frontend design record](../../frontend/src/features/catalog/DESIGN.md) documents the
built form, catalog manager, attachment and responsive quotation conventions.

Production bucket access and cloud event delivery still require verification during deployment;
the local smoke uses local private files and does not contact live messaging providers.
