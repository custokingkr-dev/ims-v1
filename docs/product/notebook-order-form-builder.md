# Notebook Order Form Builder

Last updated: 2026-09-15.
Status: proposal — not implemented.

## Scope

When a school `ADMIN` or `OPERATIONS` user opens the **Notebooks** tile in the catalog, they
should get a structured notebook intake form instead of today's free-text table: a customisation
category, a size subcategory, a ruling subcategory, a book count and a page count per line, plus
two file placeholders on customised orders (artwork before placement, a photo before delivery).

The category list, both subcategory lists, and the business conditions that adjust the numbers must
be **data owned by superadmin**, editable from the workspace without a deploy — superadmin can add,
update and deactivate products, options and conditions.

Two conditions are required on day one and must be enforced by the backend, not only the browser:

1. Customised + `Long` size → book count is forced to **1000**.
2. Page count is rounded to the **nearest multiple of 7**.

This document describes what exists today, the proposed design, and the decisions that still need a
product answer before implementation starts. It is a proposal; none of the tables, endpoints or
components below exist in the repository yet.

## What Exists Today

Verified against the current tree.

| Concern | Current state |
| --- | --- |
| Catalog tiles | Hard-coded in the frontend: [config.ts:301-309](frontend/src/pages/workspace/config.ts#L301-L309) (`CATALOG_TILES`) |
| Category list (API) | Hard-coded in Java: [CatalogReadRepository.java:37-54](services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/CatalogReadRepository.java#L37-L54) |
| Notebook form | Free-text table (type / size / pages / qty / unit ₹) at [CatalogPanel.tsx:244-270](frontend/src/pages/workspace/panels/CatalogPanel.tsx#L244-L270); local React state only, no server-side shape |
| Order persistence | One row in `catalog.catalog_orders`; all line items serialised into the opaque `order_data TEXT` column ([V1__catalog_schema.sql](services/school-core-service/src/main/resources/db/migration/catalog/V1__catalog_schema.sql)) |
| Order creation | [CatalogReadRepository.java:243-308](services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/CatalogReadRepository.java#L243-L308) — no validation of `order_data` contents |
| Notebook workflow | `NOTEBOOKS` already requires design approval then superadmin approval: [CatalogReadRepository.java:637-643](services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/CatalogReadRepository.java#L637-L643) |
| Delivery | `markDelivered` only checks `status = APPROVED`: [CatalogReadRepository.java:420-442](services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/CatalogReadRepository.java#L420-L442) |
| Superadmin catalog admin | Placeholder panel with no backend: [SaCatalogPanel.tsx](frontend/src/pages/workspace/panels/SaCatalogPanel.tsx) |
| File storage | Private GCS bucket + V4 signed URLs via impersonated credentials: [StudentPhotoStorage.java](services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/infrastructure/StudentPhotoStorage.java). Used only for student photos and import files today — no order attachments exist |
| Gateway routing | `route('catalog', '/api/v1/supply/')` is a **prefix** match ([server.js:137](services/api-gateway/server.js#L137), matcher at [server.js:345-361](services/api-gateway/server.js#L345-L361)) |
| Tenant isolation | `catalog_orders` / `annual_plan_items` carry `school_id NOT NULL` under RLS with a three-disjunct policy (own school / `bypass_rls` / operator school set): [V7__operator_scope.sql](services/school-core-service/src/main/resources/db/migration/catalog/V7__operator_scope.sql) |
| Global catalog tables | `catalog_items`, `supply_orders`, `annual_plan_entries` have no `school_id` and no RLS — the precedent for platform-owned reference data |

Two consequences worth stating up front:

- Because `/api/v1/supply/` is a prefix route, every endpoint below can live under
  `/api/v1/supply/product-catalog/**` and needs **no gateway change**.
- `catalog_items` is an empty legacy table with no seed and no writer. It is not a usable base for
  this feature and should be left alone (or retired separately).

### Stale guidance to ignore

[CONTRIBUTING.md](CONTRIBUTING.md) tells you to add a constant to `PermissionConstants.java` and use
`@PreAuthorize`. That class does not exist in any split service. The live pattern in
school-core-service is `TenantScope.requirePermissionIfAuthenticated("code")` plus
`TenantScope.requireSuperAdmin()` where applicable, and `ModuleEntitlementGuard.requireModuleEnabled`
for module gating — see [CatalogReadController.java](services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/CatalogReadController.java). Follow the code.

The migration-sequence table in CONTRIBUTING **is** current: `catalog` is at `V8`, `identity` at `V6`.

## Design Principles

1. **The form is data.** Products, option groups, options and conditions live in tables. Adding
   "Jumbo King" or changing the rounding multiple is a superadmin edit, not a release.
2. **The server is authoritative.** Every condition is applied in school-core-service on write. The
   browser mirrors the arithmetic for instant feedback; it is never trusted.
3. **Rules are a closed enum, not an expression language.** A small set of typed rules
   (`SET_QUANTITY`, `ROUND_TO_MULTIPLE`, `MIN_VALUE`, `MAX_VALUE`, `REQUIRE_ASSET`) covers the stated
   requirements and stays reviewable, testable and injection-free. Resist a generic DSL.
4. **Option codes are forever.** Historical orders reference them. Superadmin "delete" deactivates;
   hard delete is allowed only when nothing references the option.
5. **Orders are self-describing.** Each stored line snapshots the option code *and* its label and
   spec text, so a five-year-old order still reads correctly after the catalog is edited.

## Data Model

All tables go in the existing `catalog` schema owned by school-core-service.

### Platform-owned (global, no `school_id`, no RLS)

**`catalog.product_categories`** — replaces the hard-coded list in
[CatalogReadRepository.categories()](services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/CatalogReadRepository.java#L37-L54).

| Column | Type | Notes |
| --- | --- | --- |
| `code` | `VARCHAR(40)` PK | `NOTEBOOKS`, `UNIFORMS`, … |
| `label` | `VARCHAR(80)` | |
| `emoji` | `VARCHAR(16)` | |
| `description` | `VARCHAR(200)` | |
| `order_type` | `VARCHAR(20)` | `Recurring` / `One-time` / `Service` |
| `form_enabled` | `BOOLEAN` | `true` = rendered by the form builder; `false` = legacy hand-written form |
| `sort_order` | `INT` | |
| `active` | `BOOLEAN` | |
| `created_at`, `created_by`, `updated_at`, `updated_by` | | |

**`catalog.product_option_groups`** — the configurable dimensions of a product.

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `BIGSERIAL` PK | |
| `category_code` | `VARCHAR(40)` FK → `product_categories` | |
| `code` | `VARCHAR(40)` | `CUSTOMIZATION`, `SIZE`, `RULING` |
| `label` | `VARCHAR(80)` | "Category", "Subcategory 1", "Subcategory 2" |
| `level` | `INT` | cascade order, 1-based |
| `selection_type` | `VARCHAR(10)` | `SINGLE` (MVP) / `MULTI` |
| `required` | `BOOLEAN` | |
| `scope` | `VARCHAR(10)` | `ORDER` (chosen once) or `LINE` (chosen per line) |
| `active` | `BOOLEAN` | |

Unique on `(category_code, code)`.

`scope` matters: customisation is chosen once for the order, while size and ruling vary line by
line. Modelling it explicitly avoids hard-coding that split in the UI.

**`catalog.product_options`** — the selectable values.

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `BIGSERIAL` PK | |
| `group_id` | `BIGINT` FK → `product_option_groups` | |
| `code` | `VARCHAR(60)` | `CUSTOMIZED`, `LONG`, `FOUR_RULE` |
| `label` | `VARCHAR(120)` | |
| `spec_text` | `VARCHAR(120)` | display spec, e.g. `17 cm × 27 cm` |
| `width_mm` | `INT` NULL | structured spec for vendor packs / validation |
| `height_mm` | `INT` NULL | |
| `spec_status` | `VARCHAR(20)` | `CONFIRMED` or `PENDING_SPEC` |
| `sort_order` | `INT` | |
| `active` | `BOOLEAN` | |

Unique on `(group_id, code)`.

`spec_status = PENDING_SPEC` exists for **King**, whose dimensions are "to be mentioned later by
superadmin". A pending option is returned by the API but rendered disabled with a "spec pending"
note, and the server rejects an order line that selects it. When superadmin fills in the
dimensions, the option flips to `CONFIRMED` and becomes orderable — no deploy.

**`catalog.product_option_dependencies`** — optional restriction of which level-N options are valid
under a given level-(N−1) option (for example, whether *Drawing books* may take *Single rule*).

| Column | Type |
| --- | --- |
| `id` | `BIGSERIAL` PK |
| `parent_option_id` | `BIGINT` FK → `product_options` |
| `child_option_id` | `BIGINT` FK → `product_options` |
| `allowed` | `BOOLEAN` |

Seeded **empty** in MVP, which means every combination is allowed. See open question O-4.

**`catalog.product_form_rules`** — the conditions.

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `BIGSERIAL` PK | |
| `category_code` | `VARCHAR(40)` FK | |
| `rule_type` | `VARCHAR(40)` | closed enum, below |
| `target_field` | `VARCHAR(40)` NULL | `BOOK_COUNT` / `PAGE_COUNT`; null for order-level rules |
| `match_options` | `JSONB` | `{"CUSTOMIZATION":"CUSTOMIZED","SIZE":"LONG"}` — all keys must match (AND); an absent group is a wildcard; `{}` matches every line |
| `params` | `JSONB` | rule-type-specific |
| `priority` | `INT` | ascending; applied in order |
| `message` | `VARCHAR(200)` | shown in the UI when the rule fires |
| `active` | `BOOLEAN` | |
| audit columns | | |

Rule types for MVP:

| `rule_type` | `params` | Effect |
| --- | --- | --- |
| `SET_QUANTITY` | `{"value": 1000, "lock": true}` | Sets `target_field` to `value`. `lock` makes the field read-only in the UI; the server overwrites regardless. |
| `ROUND_TO_MULTIPLE` | `{"multiple": 7, "mode": "NEAREST", "minimum": 7}` | Rounds `target_field`. `mode` ∈ `NEAREST` / `UP` / `DOWN`. |
| `MIN_VALUE` / `MAX_VALUE` | `{"value": 10}` | Rejects the line with `message`. |
| `REQUIRE_ASSET` | `{"assetKind":"DESIGN","stage":"ON_PLACE"}` | Blocks a transition until the asset exists. `stage` ∈ `ON_PLACE` / `BEFORE_DELIVERY`. |

The three stated requirements become four seeded rows — no Java branch, no frontend `if`:

```text
CUSTOMIZATION=CUSTOMIZED, SIZE=LONG  → SET_QUANTITY      BOOK_COUNT  {value:1000, lock:true}
(no match constraints)               → ROUND_TO_MULTIPLE PAGE_COUNT  {multiple:7, mode:NEAREST, minimum:7}
CUSTOMIZATION=CUSTOMIZED             → REQUIRE_ASSET     —           {assetKind:DESIGN, stage:ON_PLACE}
CUSTOMIZATION=CUSTOMIZED             → REQUIRE_ASSET     —           {assetKind:PRE_DELIVERY_PHOTO, stage:BEFORE_DELIVERY}
```

### Tenant-owned (`school_id NOT NULL`, RLS)

**`catalog.catalog_order_lines`**

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `BIGSERIAL` PK | |
| `order_id` | `VARCHAR(255)` FK → `catalog_orders(id)` ON DELETE CASCADE | |
| `school_id` | `BIGINT NOT NULL` | tenant key |
| `line_no` | `INT` | |
| `option_selections` | `JSONB` | `{"SIZE":{"code":"LONG","label":"Long","spec":"17 cm × 27 cm"}, …}` — snapshot, not a FK |
| `requested_book_count` | `INT` | what the user typed |
| `book_count` | `INT NOT NULL` | after rules |
| `requested_page_count` | `INT` | |
| `page_count` | `INT NOT NULL` | after rules |
| `applied_rules` | `JSONB` | `[{"ruleId":3,"type":"SET_QUANTITY","field":"BOOK_COUNT","from":12,"to":1000}]` |
| `unit_price_paise` | `BIGINT` NULL | see open question O-1 |
| `line_total_paise` | `BIGINT` NULL | |
| `created_at`, `created_by` | | |

Index `(school_id, order_id, line_no)`.

`applied_rules` is the audit trail for auto-population. When a school asks "why does my order say
1000 books", the answer is a row, not a guess.

**`catalog.catalog_order_assets`**

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `BIGSERIAL` PK | |
| `order_id` | `VARCHAR(255)` FK → `catalog_orders(id)` | |
| `school_id` | `BIGINT NOT NULL` | |
| `asset_kind` | `VARCHAR(30)` | `DESIGN` / `PRE_DELIVERY_PHOTO` |
| `storage_key` | `VARCHAR(500)` | GCS object key |
| `content_type` | `VARCHAR(100)` | |
| `size_bytes` | `BIGINT` | |
| `checksum_sha256` | `CHAR(64)` | |
| `original_filename` | `VARCHAR(200)` | sanitised |
| `uploaded_by` | `BIGINT` | |
| `uploaded_at` | `TIMESTAMPTZ` | |
| `superseded_at` | `TIMESTAMPTZ` NULL | re-uploads supersede rather than overwrite |

Index `(school_id, order_id, asset_kind) WHERE superseded_at IS NULL`.

Both tenant tables must get the **three-disjunct** RLS policy copied from
[V7__operator_scope.sql](services/school-core-service/src/main/resources/db/migration/catalog/V7__operator_scope.sql) —
own school **or** `app.bypass_rls` **or** `app.operator_schools`. Copying only the V4 two-disjunct
policy would silently lock operations users out of the new tables while leaving orders visible.

### Migrations

| File | Contents |
| --- | --- |
| `catalog/V9__product_form_builder.sql` | Four global tables + seed data (below) |
| `catalog/V10__catalog_order_lines_and_assets.sql` | Two tenant tables, indexes, RLS policies, `app_rt` sequence grants |
| `identity/V7__catalog_manage_permission.sql` | `catalog:manage` permission + `SUPERADMIN` assignment |

`catalog` is at `V8` and `identity` at `V6` today — confirm with `ls` before writing, and never
reuse a number from another sequence. Grant sequence usage to `app_rt` behind the
`pg_roles` existence check, following the pattern in
[V5__catalog_order_id_sequence.sql](services/school-core-service/src/main/resources/db/migration/catalog/V5__catalog_order_id_sequence.sql).

### Seed data

`NOTEBOOKS` product, three groups, 23 options, four rules.

**`CUSTOMIZATION`** (level 1, scope `ORDER`, required):

| Code | Label |
| --- | --- |
| `CUSTOMIZED` | Customized |
| `NON_CUSTOMIZED` | Non-customized |

**`SIZE`** (level 2, scope `LINE`, required):

| Code | Label | `spec_text` | `width_mm` | `height_mm` | `spec_status` |
| --- | --- | --- | --- | --- | --- |
| `LONG` | Long | 17 cm × 27 cm | 170 | 270 | CONFIRMED |
| `JUMBO_LONG` | Jumbo Long | 18 cm × 24 cm | 180 | 240 | CONFIRMED |
| `KING` | King | — | NULL | NULL | **PENDING_SPEC** |
| `JUMBO_KING` | Jumbo King | 19 cm × 26 cm | 190 | 260 | CONFIRMED |
| `FA_A4` | FA / A4 notebook | 21 cm × 29.7 cm | 210 | 297 | CONFIRMED † |
| `DRAWING_BOOK` | Drawing book | — | NULL | NULL | PENDING_SPEC |

† The requirement gives "FA notebooks / A4 notebooks" as a single option with no dimensions. The
seed above uses the ISO A4 standard (210 × 297 mm). Confirm this before seeding — if FA and A4 are
distinct trade sizes they need two options, not one.

**`RULING`** (level 3, scope `LINE`, required) — 15 options, in the order given:

`SINGLE_RULE`, `DOUBLE_RULE`, `FOUR_RULE`, `SQUARE_RULE`, `BROAD_RULE`, `MATH_RULE`,
`SPECIAL_MATH_RULE`, `ONE_SIDE_SINGLE_RULE`, `BIG_SQUARE_RULE`, `PLAIN`, `SKY_RULE`, `FIVE_RULE`,
`THREE_RULE`, `ONE_SIDE_FOUR_RULE`, `ONE_SIDE_BROAD_RULE`.

The other six existing categories (`UNIFORMS`, `STATIONERY`, `IDCARDS`, `HOUSEKEEPING`, `EVENTS`,
`HEALTH`) are seeded into `product_categories` with `form_enabled = false` so `GET /categories`
becomes table-driven without changing any other form.

## Rule Engine

A single class — suggested `catalog/domain/ProductFormRuleEngine` in school-core-service — takes a
category code and a list of raw lines, and returns normalised lines plus violations.

```text
normalise(categoryCode, orderSelections, lines):
  rules = active rules for categoryCode, ordered by (priority, id)
  for each line:
    selections = orderSelections + line.selections
    for each rule where matches(rule.match_options, selections):
      apply rule to line, recording {ruleId, type, field, from, to} in appliedRules
  return NormalisationResult(lines, violations)
```

`matches` is a subset test: every key in `match_options` must be present in `selections` with the
same value. `{}` matches everything.

### Rounding semantics

For integer `n` and multiple `m = 7`, let `r = n mod m`:

- `NEAREST`: `r <= floor(m/2)` → `n - r`, else `n + (m - r)`. With `m = 7`, `r ≤ 3` rounds down and
  `r ≥ 4` rounds up. **No tie is possible for an odd multiple**, so no tie-break rule is needed.
- `UP`: `r == 0 ? n : n + (m - r)`
- `DOWN`: `n - r`
- `minimum` is applied last and only to a positive input: a rounded value below `minimum` is raised
  to `minimum`. An input of `0` or a missing page count is a **validation error**, not a rounded 0.

Worked examples with `{multiple:7, mode:NEAREST, minimum:7}`:

| Requested | `r` | Stored | Note |
| --- | --- | --- | --- |
| 196 | 0 | 196 | already a multiple |
| 198 | 2 | 196 | **rounds down — fewer pages than requested** |
| 200 | 4 | 203 | rounds up |
| 150 | 3 | 147 | rounds down |
| 152 | 5 | 154 | rounds up |
| 4 | 4 | 7 | rounded to 7, at minimum |
| 1 | 1 | 7 | rounded to 0, raised to minimum |
| 0 | — | error | page count is required |

The 198 → 196 row is why O-2 below needs an answer. `mode` is a per-rule column, so switching to
"never fewer than requested" is a superadmin edit, not a code change.

### Condition 1 precedence

`SET_QUANTITY` targets `BOOK_COUNT` and `ROUND_TO_MULTIPLE` targets `PAGE_COUNT`, so the two
required rules never interact. If a future rule pair does target the same field, `priority` decides,
and `applied_rules` records each step — make the engine log both, not just the final value.

### Frontend mirror

The UI needs the 1000 to appear the instant the user picks *Customized + Long*, and the rounded page
count to appear on blur. Round-tripping every keystroke to the server is not acceptable UX, so the
two arithmetic rule types (`SET_QUANTITY`, `ROUND_TO_MULTIPLE`) are re-implemented as a pure
function in TypeScript that consumes the same rule JSON from the API.

To keep the two implementations honest, check in a shared fixture
`contracts/catalog-form-rule-fixtures.json` — a table of `{rules, input, expected}` cases — and
assert it from **both** the JUnit engine test and a Vitest test. Any divergence fails CI.

The server still re-normalises on write and returns the applied rules in the create-order response,
so the UI can show "Book count set to 1,000 by policy" from the server's own answer.

## API

All under the existing `/api/v1/supply/` prefix route — **no gateway change required**. New
controller `CatalogProductFormController` in school-core-service, alongside the existing
[CatalogPublicCompatibilityController](services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/compat/CatalogPublicCompatibilityController.java).

### Read (school admin / operations)

| Method | Path | Permission | Returns |
| --- | --- | --- | --- |
| `GET` | `/api/v1/supply/product-catalog/categories` | `order:read` | Active products (replaces the hard-coded `/supply/catalog-categories`) |
| `GET` | `/api/v1/supply/product-catalog/forms/{categoryCode}` | `order:read` | Full form definition: groups, options (incl. `specStatus`), dependencies, rules |

Module-gated with `moduleGuard.requireModuleEnabled(schoolId, "ORDERS")` like every other catalog
endpoint. Respond with `Cache-Control: private, max-age=60` and an `ETag`; the payload is small and
global, so no server-side cache is needed in MVP.

### Write (superadmin only)

| Method | Path | Body |
| --- | --- | --- |
| `POST` / `PATCH` / `DELETE` | `/api/v1/supply/product-catalog/categories[/{code}]` | product CRUD |
| `POST` / `PATCH` / `DELETE` | `/api/v1/supply/product-catalog/groups[/{id}]` | option-group CRUD |
| `POST` / `PATCH` / `DELETE` | `/api/v1/supply/product-catalog/options[/{id}]` | option CRUD |
| `POST` / `PATCH` / `DELETE` | `/api/v1/supply/product-catalog/rules[/{id}]` | rule CRUD |

Each guarded by `TenantScope.requirePermissionIfAuthenticated("catalog:manage")` **and**
`TenantScope.requireSuperAdmin()`, matching the existing pattern at
[CatalogReadController.java:166-174](services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/CatalogReadController.java#L166-L174).

`DELETE` semantics:

- Default → soft delete (`active = false`). The option disappears from new forms; old orders still
  render because they snapshot label and spec.
- `?hard=true` → allowed only when no `catalog_order_lines.option_selections` references the code.
  Otherwise `409 Conflict` with the referencing order count in the message. Never cascade.

Every write DTO is a `record` with Bean Validation (`@NotBlank`, `@Size`, `@Pattern` on codes:
`^[A-Z][A-Z0-9_]{1,59}$`), and `rule_type` / `mode` / `stage` are validated against the closed enums
before any JSONB is persisted.

### Order assets

| Method | Path | Permission | Notes |
| --- | --- | --- | --- |
| `POST` | `/api/v1/supply/orders/{id}/assets` | `order:update` | `multipart/form-data`: `file`, `assetKind` |
| `GET` | `/api/v1/supply/orders/{id}/assets` | `order:read` | Metadata list, current + superseded |
| `GET` | `/api/v1/supply/orders/{id}/assets/{assetId}/content` | `order:read` | Authenticated stream |

Serve bytes through an authenticated endpoint rather than handing signed URLs to the browser, so
tenancy is checked server-side on every fetch. This matches the existing
`/students/{id}/photo/content` pattern the frontend already consumes.

Storage: a new `CatalogAssetStorage` component in `infrastructure`, modelled on
[StudentPhotoStorage](services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/infrastructure/StudentPhotoStorage.java)
but with two deliberate differences:

- **No re-encoding.** Student photos are resized to a 512px JPEG. Print artwork must be stored
  byte-exact, so validate and store as-is; only compute the SHA-256 for content addressing.
- **PDF accepted** alongside `image/jpeg`, `image/png`, `image/webp`. Validate by magic bytes, not
  by the client-supplied `Content-Type`. Apply the same decoded-pixel guard as
  `validatePixelCount` to images.

Object key: `schools/{storageId}/catalog-orders/{orderId}/{assetKind}/{sha256}-{sanitizedName}`.

Size limit: the gateway rejects bodies over `GATEWAY_MAX_BODY_BYTES`, default **8 MiB**
([server.js:41](services/api-gateway/server.js#L41)). Set the service-side limit to **5 MiB** to
leave multipart overhead, and state the limit in the UI. If real print artwork exceeds this, raise
the gateway variable deliberately rather than discovering it as a 413 in production.

## Order Flow Changes

### Creating a notebook order

`createOrder` gains a normalisation step for products where `form_enabled = true`:

1. Parse `orderData.lines` into raw lines.
2. Reject a line selecting a `PENDING_SPEC` option, a missing required group, or a dependency the
   `product_option_dependencies` table forbids.
3. Run `ProductFormRuleEngine.normalise(...)`.
4. Return `400` with `fieldErrors` if there are violations (reuse `ValidationExceptionHandler`).
5. Insert `catalog_orders` as today, **plus** one `catalog_order_lines` row per line, in the same
   transaction.
6. Also write a compatibility `items[]` array into `order_data` with `{name, qty}` per line, so
   [orderItemsSummary](frontend/src/pages/workspace/utils.ts#L157-L177) and the existing order lists
   keep working without modification.
7. Return the applied rules alongside the order so the UI can explain any adjustment.

Step 6 is the cheap move that keeps every existing orders screen — admin, superadmin, zone —
rendering correctly on day one.

### Placing and delivering

- `placeOrder` → evaluate `REQUIRE_ASSET` rules with `stage = ON_PLACE`. A customised order with no
  `DESIGN` asset fails with `400` and a clear message. Notebooks already move to `DESIGN_APPROVAL`
  on place, so the uploaded artwork becomes exactly what the design approver reviews — no new status
  is needed.
- `markDelivered` → evaluate `REQUIRE_ASSET` rules with `stage = BEFORE_DELIVERY`. A customised
  order with no non-superseded `PRE_DELIVERY_PHOTO` cannot be marked delivered. This is the
  "second photo before order delivery" placeholder, enforced where it actually matters.

Both checks live in the repository methods, not the controller, so the compatibility controller and
the `/api/v1/catalog/**` controller get them for free.

### Events

`catalog-order.upserted.v1` ([CatalogReadRepository.java:597-612](services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/CatalogReadRepository.java#L597-L612))
is consumed by `CatalogFactProjector` in platform-service. **Leave the payload unchanged in MVP.**
The envelope contract requires consumers to ignore unknown fields, so adding to it is safe, but the
reporting fact model has no place to put line detail. If line-level reporting is needed later, emit
a separate `catalog-order-line.upserted.v1` rather than growing this payload.

## Frontend

### School-side notebook form

Replace the `activeCat === 'NOTEBOOKS'` branch in
[CatalogPanel.tsx:244-270](frontend/src/pages/workspace/panels/CatalogPanel.tsx#L244-L270) with a
generic `<ProductFormBuilder categoryCode="NOTEBOOKS" />`, driven entirely by
`GET /supply/product-catalog/forms/NOTEBOOKS`:

```text
Notebooks order
├─ Customisation  ( ) Customized   ( ) Non-customized        ← order-scope group
├─ [Customized only] Design artwork        [ Upload ]  *required to place
├─ [Customized only] Pre-delivery photo    [ Upload ]  *required before delivery
└─ Lines
   ┌───────────┬──────────────┬────────────┬───────┬──────────┐
   │ Size      │ Ruling       │ Books      │ Pages │          │
   ├───────────┼──────────────┼────────────┼───────┼──────────┤
   │ Long   ▾  │ Four rule ▾  │ 1000 🔒    │ 200   │ remove   │
   │           │              │ set by     │ → 203 │          │
   │           │              │ policy     │       │          │
   └───────────┴──────────────┴────────────┴───────┴──────────┘
   + Add line
```

Behaviour:

- Options with `specStatus = PENDING_SPEC` (King, Drawing book) render disabled with
  "Specification pending from Custoking" — visible, so schools know the size exists.
- A locked field shows the rule's `message` beneath it and is `readOnly`, not `disabled`, so the
  value still submits and screen readers still announce it.
- Page count shows the rounded value and the original as helper text ("Rounded from 200 to 203") —
  never silently mutate what the user typed.
- Upload placeholders show filename, size, an inline preview for images, and a Replace action.
- The two required uploads are visible but not blocking on **Save draft**; they block **Place order**
  and **Mark delivered** respectively, with the server as the real gate.

Keep to the existing conventions: `ck-` prefixed classes, `:root` CSS variables only, no new
component library, and gate the panel's actions on `usePermissions().can(...)`.

### Superadmin builder

Replace the [SaCatalogPanel](frontend/src/pages/workspace/panels/SaCatalogPanel.tsx) placeholder with
three tabs plus a preview:

- **Products** — list, add, edit, deactivate; toggle `form_enabled`.
- **Form** — per product, the option groups and their options. Add/rename/reorder/deactivate an
  option; edit `spec_text`, `width_mm`, `height_mm` and flip `PENDING_SPEC` → `CONFIRMED`.
- **Conditions** — guided rule builder, no JSON editing:
  `When [Customization] is [Customized] and [Size] is [Long], then set [Book count] to [1000] [✓ lock]`.
- **Preview** — renders the live school-side form from the current definition, so superadmin sees
  the effect before saving.

Deactivation is one click; hard delete is behind a confirm dialog that surfaces the server's
`409` referencing-order count as the reason it is refused.

## Security, Tenancy and Audit

- New permission `catalog:manage`, seeded in `identity/V7` and assigned to `SUPERADMIN` only.
  Existing roles are untouched — schools read the form, they do not edit it.
- Global tables are superadmin-write / all-read; they carry no tenant data and correctly have no
  RLS, matching `catalog_items`.
- The two new tenant tables carry `school_id NOT NULL` and the three-disjunct policy. Extend
  [CatalogRlsIntegrationTest](services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/security/CatalogRlsIntegrationTest.java)
  to cover both, including the operator-scope disjunct.
- Uploaded assets are school-scoped; the content endpoint resolves the order's `school_id` and
  applies the same scope checks as `markDelivered` before streaming bytes.
- `applied_rules` on each line plus the existing outbox emission give a complete audit of what the
  system changed and why. CONTRIBUTING's `auditLogService` does not exist in the split services;
  audit flows through the outbox to platform-service.
- Order assets are business documents, not student PII, but they live in the same private bucket
  with no public URLs. Do not relax that.

## Testing

Backend (school-core-service):

| Test | Type | Covers |
| --- | --- | --- |
| `ProductFormRuleEngineTest` | unit | The full rounding table above; 1000-lock; wildcard vs. exact match; priority ordering; `applied_rules` contents |
| `ProductFormRuleFixtureTest` | unit | Asserts `contracts/catalog-form-rule-fixtures.json` |
| `CatalogProductFormControllerTest` | unit, standalone MockMvc (pattern: [CatalogValidationTest](services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/api/CatalogValidationTest.java)) | Validation `fieldErrors`; `403` for a non-superadmin write; `401` without the service token |
| `CatalogProductFormIntegrationTest` | Testcontainers | CRUD, soft delete, `409` on referenced hard delete, seed correctness |
| `CatalogOrderLineIntegrationTest` | Testcontainers | Order create persists normalised lines; `PENDING_SPEC` rejection; `order_data.items` compatibility shim |
| `CatalogOrderAssetIntegrationTest` | Testcontainers | Upload/replace/supersede; place blocked without `DESIGN`; deliver blocked without `PRE_DELIVERY_PHOTO`; cross-tenant read denied |
| `CatalogRlsIntegrationTest` (extend) | Testcontainers | RLS on the two new tables incl. operator scope |

Frontend:

| Test | Covers |
| --- | --- |
| `ProductFormBuilder.test.tsx` | Cascade rendering from a fixture definition; 1000 lock; rounding helper text; disabled `PENDING_SPEC` option |
| `productFormRules.test.ts` | The shared fixture file — must match the JUnit results exactly |
| `SaCatalogPanel.test.tsx` | CRUD wiring, deactivate vs. delete, `409` surfaced |

## Rollout

1. `catalog/V9`, `catalog/V10`, `identity/V7` — tables, seed, permission. No behaviour change yet.
2. Read API + rule engine + `GET /forms/{code}`. Backend tests green.
3. Superadmin builder UI. Superadmin can edit the seeded definition; nothing else changes.
4. School-side form behind `catalog.product-form.enabled` (per-environment property). Dev first.
5. Order lines, assets and the place/deliver gates.
6. Enable in production; keep the legacy notebook branch reachable for one release as a rollback.
7. Remove the legacy notebook branch and decide the fate of the now-redundant `notebook_cover_logo`,
   `notebook_delivery_mode` and `notebook_spine_name` columns on `catalog_orders` (keep writing them
   from the new form, or retire them in a later migration — do not leave them silently null).

A config flag is not a placeholder implementation; each step above ships complete behaviour.

## Non-Goals

- Migrating uniforms, stationery, ID cards, housekeeping, events or health to the builder. The
  design is generic and they can follow later; MVP changes only notebooks.
- A vendor-facing portal or vendor-side asset access.
- A general-purpose expression language for rules.
- Changing the existing approval workflow, statuses or the reporting fact model.
- Superadmin's own `New order request` form ([SaNewOrderPanel](frontend/src/pages/workspace/panels/SaNewOrderPanel.tsx)),
  which has its own notebook branch. It should adopt the builder in a follow-up so the two notebook
  forms do not diverge further — see O-5.

## Open Questions

These need a product answer. Each one changes stored data, so guessing is expensive.

**O-1 — Pricing.** `catalog_orders.subtotal`, `gst` and `total_amount` are `NOT NULL`. Today the
school user types a unit price per row ([CatalogPanel.tsx:264](frontend/src/pages/workspace/panels/CatalogPanel.tsx#L264))
and the frontend adds 12% GST. The new form specifies count and pages but no price. Options:
(a) a superadmin price list keyed by size × ruling × page band, server-computed; (b) submit at zero
value and have superadmin set the price at approval. Recommendation: **(b)** for MVP with an
explicit `PENDING_PRICING` marker and a superadmin price step, then (a) as a follow-on — a school
user typing a manufacturing price is the real anomaly here. This is the largest unresolved piece.

**O-2 — Rounding direction.** "Nearest multiple of 7" means 198 pages becomes 196 — fewer than
requested. If the intent is "never short the school", the mode should be `UP`. The rule is
configurable either way; the seed value needs a decision.

**O-3 — Is 1000 a lock or a default?** The spec says "auto-populated to 1000". `lock: true` makes it
read-only; `lock: false` pre-fills it and lets the school change it. Recommendation: **lock**, since
it reads as a minimum-order-quantity policy, with `lock` flippable by superadmin.

**O-4 — Invalid combinations.** Should *Drawing book* accept *Single rule*? Should *Plain* be
available in every size? The dependency table exists and is seeded empty (everything allowed). If
real restrictions exist, they should be seeded — otherwise schools will order impossible products.

**O-5 — Missing size specs.** King is explicitly "to be mentioned later". Drawing book has no
dimensions in the requirement either; both ship as `PENDING_SPEC` and are unorderable until filled.
Confirm that a *visible but disabled* option is the wanted behaviour rather than hiding them. Also
confirm whether "FA notebooks / A4 notebooks" is one size or two (see the seed-table footnote).

**O-6 — Size labels.** As given, Long is 17 × 27 cm and Jumbo Long is 18 × 24 cm — the "jumbo"
variant is 3 cm shorter. That may well be the trade convention, but it is worth confirming before it
is printed on purchase orders.

**O-7 — Page count semantics.** Does "pages" mean printed pages or leaves/sheets? A 200-page
notebook is 100 leaves. The label must say which, and the multiple-of-7 rule must apply to the
same unit the vendor uses.

**O-8 — Design approval for non-customised orders.** Notebooks currently always route through
`DESIGN_APPROVAL` ([CatalogReadRepository.java:637-639](services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/CatalogReadRepository.java#L637-L639)).
A non-customised notebook arguably has nothing to approve. Skipping it would speed those orders up
but changes behaviour for every existing notebook order. Recommendation: **keep current behaviour in
MVP**; add a `REQUIRE_DESIGN_APPROVAL` rule type later if the product wants it configurable.

## Effort Estimate

Rough, assuming the open questions are answered before step 2 and one engineer:

| Step | Estimate |
| --- | --- |
| Migrations + seed | 1 day |
| Rule engine + tests | 2 days |
| Read/CRUD API + tests | 3 days |
| Asset storage + upload endpoints + gates | 3 days |
| School-side form component | 3 days |
| Superadmin builder UI | 4 days |
| Order-line persistence + compatibility shim | 2 days |
| Integration tests, RLS coverage, rollout | 2 days |

Approximately **4 weeks**, with pricing (O-1) excluded and tracked separately.
