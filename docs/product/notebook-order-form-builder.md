# Notebook Order Form Builder

Last updated: 2026-09-15.
Status: implemented in the working branch with all eight product decisions confirmed on
2026-09-15. Verification and rollout details are recorded in
[Implementation Notes](notebook-order-form-builder-implementation.md). Not deployed to production.

## Scope

When a school `ADMIN` or `OPERATIONS` user opens the **Notebooks** tile in the catalog, they
should get a structured notebook intake form instead of today's free-text table: a customisation
category, a size subcategory, a ruling subcategory, a book count and a page count per line, plus
two file placeholders on customised orders (artwork before placement, a photo before delivery).

The category list, both subcategory lists, and the business conditions that adjust the numbers must
be **data owned by superadmin**, editable from the workspace without a deploy — superadmin can add,
update and deactivate products, options and conditions.

The following product decisions are confirmed and must be enforced by the backend:

1. Superadmin quotes after the school submits. Until quoted, show `PENDING_PRICING`.
2. A customised notebook order must contain **exactly 1000 books across all sizes combined**.
   Sum every ruling line across all selected sizes into one order total. Only superadmin can
   change the configured required total; school users choose how to distribute it.
3. Count **printed pages**, rounded to the **nearest multiple of 7**, including 198 -> 196.
4. All ruling options remain in the dropdown; seed no combination restrictions.
5. Incomplete sizes remain visible but disabled. FA and A4 are the same size option.
6. The supplied dimensions are accepted. Only superadmin can change dimensions, the quantity
   condition, or rounding settings, through workspace controls without deployment.
7. Non-customised notebooks skip design approval. Both types still require superadmin quoting
   and final approval. Customised orders retain artwork and pre-delivery-photo requirements.

This document preserves the confirmed requirements and original implementation proposal.
The implementation notes identify the shipped API, migration, storage and test details where
they differ from proposed names. Confirmed decisions below replace the original open questions.

## Pre-Implementation Baseline

Recorded before this feature was implemented. The links below describe the baseline, not the
current feature state; line numbers may have shifted during implementation.

| Concern | Current state |
| --- | --- |
| Catalog tiles | Hard-coded in the frontend: [config.ts:301-309](../../frontend/src/pages/workspace/config.ts#L301-L309) (`CATALOG_TILES`) |
| Category list (API) | Hard-coded in Java: [CatalogReadRepository.java:37-54](../../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/CatalogReadRepository.java#L37-L54) |
| Notebook form | Free-text table (type / size / pages / qty / unit ₹) at [CatalogPanel.tsx:244-270](../../frontend/src/pages/workspace/panels/CatalogPanel.tsx#L244-L270); local React state only, no server-side shape |
| Order persistence | One row in `catalog.catalog_orders`; all line items serialised into the opaque `order_data TEXT` column ([V1__catalog_schema.sql](../../services/school-core-service/src/main/resources/db/migration/catalog/V1__catalog_schema.sql)) |
| Order creation | [CatalogReadRepository.java:243-308](../../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/CatalogReadRepository.java#L243-L308) — no validation of `order_data` contents |
| Notebook workflow | `NOTEBOOKS` already requires design approval then superadmin approval: [CatalogReadRepository.java:637-643](../../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/CatalogReadRepository.java#L637-L643) |
| Delivery | `markDelivered` only checks `status = APPROVED`: [CatalogReadRepository.java:420-442](../../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/CatalogReadRepository.java#L420-L442) |
| Superadmin catalog admin | Placeholder panel with no backend: [SaCatalogPanel.tsx](../../frontend/src/pages/workspace/panels/SaCatalogPanel.tsx) |
| File storage | Private GCS bucket + V4 signed URLs via impersonated credentials: [StudentPhotoStorage.java](../../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/infrastructure/StudentPhotoStorage.java). Used only for student photos and import files today — no order attachments exist |
| Gateway routing | `route('catalog', '/api/v1/supply/')` is a **prefix** match ([server.js:137](../../services/api-gateway/server.js#L137), matcher at [server.js:345-361](../../services/api-gateway/server.js#L345-L361)) |
| Tenant isolation | `catalog_orders` / `annual_plan_items` carry `school_id NOT NULL` under RLS with a three-disjunct policy (own school / `bypass_rls` / operator school set): [V7__operator_scope.sql](../../services/school-core-service/src/main/resources/db/migration/catalog/V7__operator_scope.sql) |
| Global catalog tables | `catalog_items`, `supply_orders`, `annual_plan_entries` have no `school_id` and no RLS — the precedent for platform-owned reference data |

Two consequences worth stating up front:

- Because `/api/v1/supply/` is a prefix route, every endpoint below can live under
  `/api/v1/supply/product-catalog/**` and needs **no gateway change**.
- `catalog_items` is an empty legacy table with no seed and no writer. It is not a usable base for
  this feature and should be left alone (or retired separately).

### Stale guidance to ignore

[CONTRIBUTING.md](../../CONTRIBUTING.md) tells you to add a constant to `PermissionConstants.java` and use
`@PreAuthorize`. That class does not exist in any split service. The live pattern in
school-core-service is `TenantScope.requirePermissionIfAuthenticated("code")` plus
`TenantScope.requireSuperAdmin()` where applicable, and `ModuleEntitlementGuard.requireModuleEnabled`
for module gating — see [CatalogReadController.java](../../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/CatalogReadController.java). Follow the code.

The migration-sequence table in CONTRIBUTING **is** current: `catalog` is at `V8`, `identity` at `V6`.

## Design Principles

1. **The form is data.** Products, option groups, options and conditions live in tables. Adding
   "Jumbo King" or changing the rounding multiple is a superadmin edit, not a release.
2. **The server is authoritative.** Every condition is applied in school-core-service on write. The
   browser mirrors the arithmetic for instant feedback; it is never trusted.
3. **Rules are a closed enum, not an expression language.** A small set of typed rules
   (`REQUIRE_QUANTITY_TOTAL`, `ROUND_TO_MULTIPLE`, `MIN_VALUE`, `MAX_VALUE`, `REQUIRE_ASSET`) covers the stated
   requirements and stays reviewable, testable and injection-free. Resist a generic DSL.
4. **Option codes are forever.** Historical orders reference them. Superadmin "delete" deactivates;
   hard delete is allowed only when nothing references the option.
5. **Orders are self-describing.** Each stored line snapshots the option code *and* its label and
   spec text, so a five-year-old order still reads correctly after the catalog is edited.

## Data Model

All tables go in the existing `catalog` schema owned by school-core-service.

### Platform-owned (global, no `school_id`, no RLS)

**`catalog.product_categories`** — replaces the hard-coded list in
[CatalogReadRepository.categories()](../../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/CatalogReadRepository.java#L37-L54).

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

Seeded **empty** in MVP, which means every combination is allowed. All active ruling options
remain in the dropdown for every orderable size, as confirmed in decision D-5.

**`catalog.product_form_rules`** — the conditions.

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `BIGSERIAL` PK | |
| `category_code` | `VARCHAR(40)` FK | |
| `rule_type` | `VARCHAR(40)` | closed enum, below |
| `target_field` | `VARCHAR(40)` NULL | `BOOK_COUNT` / `PAGE_COUNT`; null for order-level rules |
| `match_options` | `JSONB` | `{"CUSTOMIZATION":"CUSTOMIZED"}` — all keys must match (AND); an absent group is a wildcard; `{}` matches every line |
| `params` | `JSONB` | rule-type-specific |
| `priority` | `INT` | ascending; applied in order |
| `message` | `VARCHAR(200)` | shown in the UI when the rule fires |
| `active` | `BOOLEAN` | |
| audit columns | | |

Rule types for MVP:

| `rule_type` | `params` | Effect |
| --- | --- | --- |
| `REQUIRE_QUANTITY_TOTAL` | `{"value":1000,"comparison":"EQ","scope":"ORDER","stage":"ON_PLACE"}` | Requires exactly 1000 books summed across all sizes and ruling lines in a customised order. Shows progress on drafts; rejects placement if the total is below or above the configured value. |
| `ROUND_TO_MULTIPLE` | `{"multiple": 7, "mode": "NEAREST", "minimum": 7}` | Rounds `target_field`. `mode` ∈ `NEAREST` / `UP` / `DOWN`. |
| `MIN_VALUE` / `MAX_VALUE` | `{"value": 10}` | Rejects the line with `message`. |
| `REQUIRE_ASSET` | `{"assetKind":"DESIGN","stage":"ON_PLACE"}` | Blocks a transition until the asset exists. `stage` ∈ `ON_PLACE` / `BEFORE_DELIVERY`. |

The form requirements become four seeded rows. The quantity requirement uses one order-wide
total across every selected size and ruling.

```text
CUSTOMIZATION=CUSTOMIZED             -> REQUIRE_QUANTITY_TOTAL BOOK_COUNT {value:1000, comparison:EQ, scope:ORDER, stage:ON_PLACE}
(no match constraints)               → ROUND_TO_MULTIPLE PAGE_COUNT  {multiple:7, mode:NEAREST, minimum:7}
CUSTOMIZATION=CUSTOMIZED             → REQUIRE_ASSET     —           {assetKind:DESIGN, stage:ON_PLACE}
CUSTOMIZATION=CUSTOMIZED             → REQUIRE_ASSET     —           {assetKind:PRE_DELIVERY_PHOTO, stage:BEFORE_DELIVERY}
```

`REQUIRE_QUANTITY_TOTAL` is an aggregate validation, not a line-level assignment. School users
distribute books among ruling lines; only superadmin can edit the required total and rule settings.
MVP uses the closed values `scope = ORDER`, `comparison = EQ` and `stage = ON_PLACE`.
Validate the configured target as a positive integer. Do not split the total by size, ruling or
printed-page count, expose a school-side unlock, or silently change individual line quantities.
Non-customised orders do not match this rule and have no 1000-book total requirement.

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
| `applied_rules` | `JSONB` | e.g. a `ROUND_TO_MULTIPLE` result with `field: PAGE_COUNT`, `from: 198`, `to: 196`, and the rule id |
| `unit_price_paise` | `BIGINT` NULL | unset until superadmin quotes |
| `line_total_paise` | `BIGINT` NULL | server-computed from quoted price and normalised book count |
| `created_at`, `created_by` | | |

Index `(school_id, order_id, line_no)`.

`applied_rules` records per-line adjustments. Record aggregate quantity validation results on the
order, including the rule id, grouping, requested total, actual total and participating line ids;
an aggregate check must not be represented as if it independently changed each line.

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
[V7__operator_scope.sql](../../services/school-core-service/src/main/resources/db/migration/catalog/V7__operator_scope.sql) —
own school **or** `app.bypass_rls` **or** `app.operator_schools`. Copying only the V4 two-disjunct
policy would silently lock operations users out of the new tables while leaving orders visible.

### Existing order metadata

Extend `catalog.catalog_orders` in the same feature migration:

| Column | Type | Notes |
| --- | --- | --- |
| `form_version` | `INT NOT NULL DEFAULT 1` | 1 = existing orders; 2 = structured notebook orders |
| `order_selections` | `JSONB` NULL | server-validated order-level options, including customisation, with label snapshots |
| `form_snapshot` | `JSONB` NULL | definition and rule parameters used by this order, including dimensions and quantity scope |
| `pricing_status` | `VARCHAR(30) NOT NULL DEFAULT 'NOT_APPLICABLE'` | `PENDING_PRICING` / `QUOTED` for new notebook orders; existing orders keep their current amounts |
| `quoted_at` | `TIMESTAMPTZ` NULL | |
| `quoted_by` | `BIGINT` NULL | trusted authenticated superadmin id |
| `quantity_rule_results` | `JSONB` NULL | aggregate validation results for the latest saved lines |
| `approved_design_asset_id` | `BIGINT` NULL | artwork version approved for this order; must reference an asset belonging to the same order and school |

Use the stored definition for subsequent order checks so catalog edits affect future orders
without silently changing submitted quantities, dimensions or requirements. Never trust a
client-provided snapshot, pricing status or quotation actor. Draft editing and placement must
use the same saved order and definition; any explicit rebase to a newer definition must display
the differences before submission.

### Migrations

| File | Contents |
| --- | --- |
| `catalog/V9__product_form_builder.sql` | Five global tables + seed data (below) |
| `catalog/V10__catalog_order_lines_and_assets.sql` | Two tenant tables, order form/pricing metadata, indexes, RLS policies, `app_rt` sequence grants |
| `identity/V7__catalog_builder_permissions.sql` | `catalog:manage` and `catalog:quote` permissions + `SUPERADMIN` assignments |

`catalog` is at `V8` and `identity` at `V6` today — confirm with `ls` before writing, and never
reuse a number from another sequence. Grant sequence usage to `app_rt` behind the
`pg_roles` existence check, following the pattern in
[V5__catalog_order_id_sequence.sql](../../services/school-core-service/src/main/resources/db/migration/catalog/V5__catalog_order_id_sequence.sql).

### Seed data

`NOTEBOOKS` product, three groups, 23 options, four rules. The customised quantity rule is seeded
with an exact order-wide total of 1000 across all sizes and rulings. All seed decisions are confirmed.

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
| `FA_A4` | FA / A4 notebook | 21 cm × 29.7 cm | 210 | 297 | CONFIRMED |
| `DRAWING_BOOK` | Drawing book | — | NULL | NULL | PENDING_SPEC |

FA and A4 are confirmed to be the same option. The supplied dimensions are accepted, including
Long at 170 x 270 mm and Jumbo Long at 180 x 240 mm. All dimension/specification fields are
editable only by superadmin. King and Drawing book remain visible but disabled until their
missing specifications are completed; an incomplete option cannot be marked `CONFIRMED`.

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
  rules = rules from the trusted definition for this order, ordered by (priority, id)
  for each line:
    selections = orderSelections + line.selections
    for each line-level rule where matches(rule.match_options, selections):
      apply rule to line, recording {ruleId, type, field, from, to} in appliedRules
  evaluate aggregate quantity rules over all matching lines
  return NormalisationResult(lines, aggregateResults, violations)
```

`matches` is a subset test: every key in `match_options` must be present in `selections` with the
same value. `{}` matches everything. Validate group ownership and `scope` before merging selections:
a line cannot override the order-level customisation or introduce an unknown group/option.
Drafts may retain unmet aggregate totals as validation feedback, but placement must reject them.
For customised orders, calculate `actualTotal = sum(line.bookCount)` across every line and
require `actualTotal == configuredTarget` on placement. The initial target is 1000. Adding or
removing a size does not create another target; every size contributes to the same order total.

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

The 198 -> 196 result is explicitly approved. Every number in this table is a count of
**printed pages**, not sheets or leaves. Only superadmin can edit `params.mode`,
`params.multiple` or `params.minimum`; school users see the result and cannot bypass the rule.
Reject nonpositive multiples and invalid bounds. For a configured even multiple, `NEAREST`
rounds an exact midpoint down, consistent with the formula above; cover this in shared fixtures.

### Evaluation order

Validate positive integer line quantities, normalise printed page counts, then evaluate aggregate
book totals. No seeded rule overwrites individual book quantities. Apply rule priority within
each phase and retain each result. The order total spans every matching line across all sizes,
rulings and printed-page counts.

### Frontend mirror

The UI displays the configured required total when the user selects Customized and updates the
actual aggregate as line quantities change. Show rounded printed-page counts on blur. Implement
quantity aggregation and page rounding as pure TypeScript functions consuming the same rule JSON
as the server. Show one order-wide quantity summary, with actual total, configured target and
the amount still needed or over the target. Do not show separate targets for each size.

To keep the two implementations honest, check in a shared fixture
`contracts/catalog-form-rule-fixtures.json` — a table of `{rules, input, expected}` cases — and
assert it from **both** the JUnit engine test and a Vitest test. Any divergence fails CI.

The server re-normalises on write and returns per-line adjustments and aggregate validation
results. Show the required total, actual total and difference; do not claim the server assigned
1000 books to each line.

## API

All under the existing `/api/v1/supply/` prefix route — **no gateway change required**. New
controller `CatalogProductFormController` in school-core-service, alongside the existing
[CatalogPublicCompatibilityController](../../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/compat/CatalogPublicCompatibilityController.java).

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
[CatalogReadController.java:166-174](../../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/CatalogReadController.java#L166-L174).

`DELETE` semantics:

- Default → soft delete (`active = false`). The option disappears from new forms; old orders still
  render because they snapshot label and spec.
- `?hard=true` → allowed only when no `catalog_order_lines.option_selections` references the code.
  Otherwise `409 Conflict` with the referencing order count in the message. Never cascade.

Every write DTO is a `record` with Bean Validation (`@NotBlank`, `@Size`, `@Pattern` on codes:
`^[A-Z][A-Z0-9_]{1,59}$`), and `rule_type` / `mode` / `stage` are validated against the closed enums
before any JSONB is persisted.

### Draft editing and quotation

| Method | Path | Permission | Behaviour |
| --- | --- | --- | --- |
| `PATCH` | `/api/v1/supply/orders/{id}` | `order:update` | Update a version-2 draft's selections, lines and requested date; revalidate and normalise using its stored definition |
| `PUT` | `/api/v1/supply/orders/{id}/quote` | `catalog:quote` + superadmin | Quote a submitted version-2 notebook order before final approval |

Apply the existing order module and tenant checks to both. The draft endpoint rejects edits
after submission, and cannot change status, prices, school ownership or server-owned metadata.
Use the order's existing optimistic version for both writes; return `409` for a stale edit.

Quotation input identifies every persisted line and supplies its unit price in paise plus the
order GST amount in paise. Reject unknown, duplicate or omitted lines and negative amounts.
The server calculates line totals, subtotal and total amount from the persisted quantities;
it never accepts caller-computed totals. Store line prices, totals, `QUOTED`, `quoted_by` and
`quoted_at`, increment the version and emit the existing order update in one transaction.
School/operations users cannot quote, even if they submit quotation fields to another endpoint.

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
[StudentPhotoStorage](../../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/infrastructure/StudentPhotoStorage.java)
but with two deliberate differences:

- **No re-encoding.** Student photos are resized to a 512px JPEG. Print artwork must be stored
  byte-exact, so validate and store as-is; only compute the SHA-256 for content addressing.
- **PDF accepted** alongside `image/jpeg`, `image/png`, `image/webp`. Validate by magic bytes, not
  by the client-supplied `Content-Type`. Apply the same decoded-pixel guard as
  `validatePixelCount` to images.

Object key: `schools/{storageId}/catalog-orders/{orderId}/{assetKind}/{sha256}-{sanitizedName}`.

Size limit: the gateway rejects bodies over `GATEWAY_MAX_BODY_BYTES`, default **8 MiB**
([server.js:41](../../services/api-gateway/server.js#L41)). Set the service-side limit to **5 MiB** to
leave multipart overhead, and state the limit in the UI. If real print artwork exceeds this, raise
the gateway variable deliberately rather than discovering it as a 413 in production.

## Order Flow Changes

### Creating a notebook order

For new structured notebook orders, `createOrder` performs the following steps:

1. Parse `orderData.orderSelections` and `orderData.lines` into validated input. Accept the
   current transport's JSON-string `orderData` as well as a JSON object, through the existing
   parser. Require at least one line; do not silently accept missing structured data.
2. Reject a line selecting a `PENDING_SPEC` option, a missing required group, or a dependency the
   `product_option_dependencies` table forbids.
3. Run `ProductFormRuleEngine.normalise(...)` and collect line adjustments and aggregate results.
4. Return `400` with `fieldErrors` for invalid selections/counts (reuse `ValidationExceptionHandler`).
   An unmet aggregate target is saved as draft feedback and becomes a blocking placement error.
5. Insert a `DRAFT` order with `form_version = 2`, trusted order selections and form snapshot,
   `PENDING_PRICING`, and one `catalog_order_lines` row per line in the same transaction. Reject
   caller-supplied advanced statuses. Leave line prices null and initialise the existing
   non-null subtotal/GST/total columns to zero as storage placeholders. Reject school-supplied
   prices; display pending pricing rather than a free or final zero-value quote.
6. Also write a compatibility `items[]` array into `order_data` with `{name, qty}` per line, so
   [orderItemsSummary](../../frontend/src/pages/workspace/utils.ts#L157-L177) and the existing order lists
   keep working without modification.
7. Return the applied rules alongside the order so the UI can explain any adjustment.

Step 6 is the cheap move that keeps every existing orders screen — admin, superadmin, zone —
rendering item summaries correctly on day one. Pricing labels and asset access still require
the explicit UI changes below.

Persist and reuse the returned draft id. An upload needs this id because assets reference the
order: save the draft, upload its artwork, then place that same order. On failure, retain the id
and successful uploads for retry instead of creating another order. Reopening a draft reloads
its saved selections, lines and assets. Show the pre-delivery upload on the saved order details
as well as the initial form so it remains available after placement.

### Pricing

Superadmin quoting after school submission is part of MVP, not a deferred feature:

- School forms collect requirements and quantities, with no editable unit price or GST fields.
- Draft and submitted notebook orders display **Pending pricing** until a superadmin quote is
  saved. A stored placeholder zero must never be presented as an accepted free quote.
- Superadmin can quote after placement, including while customised artwork awaits review.
  Final approval is blocked until `pricing_status = QUOTED` and any required design is approved.
- Once quoted, the school order detail and superadmin views show the quoted line prices, GST
  and total. Amounts are server-computed in paise, using the normalised quantities.
- Quote changes are allowed before final approval and record the trusted actor and timestamp.
  Approved order edits are outside this draft/quote API.

### Placing and delivering

- `placeOrder` validates the aggregate quantity requirement and `REQUIRE_ASSET` rules with
  `stage = ON_PLACE` using the saved definition and selections. A customised order with no
  `DESIGN` asset fails with `400` and a clear message, then moves to `DESIGN_APPROVAL` when valid.
- A version-2 **non-customised** notebook order skips design approval and moves directly to
  `PROCESSING`, with `design_status = NOT_REQUIRED` and superadmin approval pending. It still
  needs a quote and final superadmin approval; it is not automatically approved.
- `markDesignApproved` binds approval to the current artwork asset id. Replacing approved
  artwork before final approval invalidates that design approval and requires review again.
  Reject artwork replacement after final approval in this MVP.
- `approveBySuperadmin` requires a saved quote and, for customised notebooks, approval of the
  current artwork. Use the same customisation-aware policy in create, place and approve checks;
  the current category-only `requiresDesignApproval(NOTEBOOKS)` is insufficient.
- `markDelivered` → evaluate `REQUIRE_ASSET` rules with `stage = BEFORE_DELIVERY`. A customised
  order with no non-superseded `PRE_DELIVERY_PHOTO` cannot be marked delivered. This is the
  "second photo before order delivery" placeholder, enforced where it actually matters.

These checks are shared by all controller paths. Guard `createOrder` and `updateOrderStatus`
as well as the named transition methods: neither an initial `status` nor a direct status PATCH
may bypass quantity, artwork, quotation, design-approval or delivery checks. Restrict direct
status updates for version-2 orders to supported transitions and run the same checks atomically
with the write. Persist the approved artwork reference in the order metadata.

Existing version-1 orders keep their recorded workflow. Never infer `NON_CUSTOMIZED` from a
missing legacy customisation value and skip their pending approval automatically.

### Existing callers and rollback

Both school and superadmin notebook creation must use the structured form before enabling the
feature. `SaNewOrderPanel` currently sends `notebookRows` without the required selections;
changing the shared repository alone would break `/sa/orders`. Reuse the builder for its
notebook branch in this release, preserving its school selector. Update any notebook clone or
reorder callers to produce the same validated payload.

The environment flag controls new creation in both the frontend and backend. After enablement,
old or malformed notebook payloads receive an actionable refresh/update error, not a silent
validation bypass. For rollback, the legacy creation UI and backend branch must be switched
together; reading and processing already-created version-2 orders must remain available.

### Events

`catalog-order.upserted.v1` ([CatalogReadRepository.java:597-612](../../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/CatalogReadRepository.java#L597-L612))
is consumed by `CatalogFactProjector` in platform-service. Preserve existing fields and add
`formVersion` and `pricingStatus` so reporting-backed order views can distinguish pending pricing
from a quoted zero amount. Extend the projection with backward-compatible defaults for older
events and a migration at the next available reporting version. Quote saves emit updated totals
through the same outbox transaction; test replay and projection updates.

Keep line detail out of the reporting fact model. It remains available through school-core's
order API. Catalog configuration edits and quotations must also record the actor and changed
values through the existing audit mechanism.

## Frontend

### School-side notebook form

Replace the `activeCat === 'NOTEBOOKS'` branch in
[CatalogPanel.tsx:244-270](../../frontend/src/pages/workspace/panels/CatalogPanel.tsx#L244-L270) with a
generic `<ProductFormBuilder categoryCode="NOTEBOOKS" />`, driven entirely by
`GET /supply/product-catalog/forms/NOTEBOOKS`:

```text
Notebooks order
  Customisation: Customized / Non-customized
  Pricing: Pending pricing
  [Customized] Design artwork: upload; required to place
  [Customized] Pre-delivery photo: upload; required before delivery
  Lines: Size | Ruling | Books | Printed pages | Remove
  Printed pages example: 198 -> 196
  [Customized] All sizes combined: 1000 books required; actual total and difference shown
  Add line
  Save draft / Place order
```

The quantity summary covers every size and ruling in the order. For example, 400 Long books
plus 600 Jumbo Long books meets the 1000-book requirement.

Behaviour:

- Options with `specStatus = PENDING_SPEC` (King, Drawing book) render disabled with
  "Specification pending from Custoking" — visible, so schools know the size exists.
- All 15 active ruling options remain available in the dropdown for every orderable size.
- Quantity feedback shows the configured required total alongside the sum of matching lines.
  School users edit the allocation, while the condition itself is editable only by superadmin.
- Label the field **Printed pages** and show both requested and normalised values, for example
  "Rounded from 198 to 196". Do not silently discard the requested number.
- Upload placeholders show filename, size, an inline preview for images, and a Replace action.
- The two required uploads are visible but not blocking on **Save draft**; they block **Place order**
  and **Mark delivered** respectively, with the server as the real gate.
- Saved order details support draft reopening, artwork review and the later pre-delivery upload.
- Remove the school-entered price and GST controls for version-2 notebooks. Show **Pending
  pricing** until quoted; then display the superadmin quote. Non-customised orders show their
  direct processing/approval path with no design task.

Keep to the existing conventions: `ck-` prefixed classes, `:root` CSS variables only, no new
component library, and gate the panel's actions on `usePermissions().can(...)`.

### Superadmin builder

Replace the [SaCatalogPanel](../../frontend/src/pages/workspace/panels/SaCatalogPanel.tsx) placeholder with
three tabs plus a preview:

- **Products** — list, add, edit, deactivate; toggle `form_enabled`.
- **Form** — per product, the option groups and their options. Add/rename/reorder/deactivate an
  option; edit `spec_text`, `width_mm`, `height_mm` and flip `PENDING_SPEC` → `CONFIRMED`.
- **Conditions** — guided rule builder, no JSON editing:
  `When [Customization] is [Customized], require exactly [1000] books across the whole order`.
  The editable target applies to all sizes combined. Expose page-rounding multiple/mode/minimum
  in the same guided editor. Dimension and rule writes require superadmin on the server as well as UI.
- **Preview** — renders the live school-side form from the current definition, so superadmin sees
  the effect before saving.

Deactivation is one click; hard delete is behind a confirm dialog that surfaces the server's
`409` referencing-order count as the reason it is refused.

In the existing superadmin order review/detail view, add the quotation step: persisted lines,
normalised quantities, editable unit prices and GST, server-computed totals, and save quote.
Show the pricing and design prerequisites beside the final approval action. Reuse the school
builder for the notebook branch of `SaNewOrderPanel`, with its existing school selector.

## Security, Tenancy and Audit

- New permissions `catalog:manage` and `catalog:quote`, seeded in `identity/V7` and assigned to
  `SUPERADMIN` only. Schools and operations users cannot edit catalog rules, dimensions or quotes.
- Global tables are superadmin-write / all-read; they carry no tenant data and correctly have no
  RLS, matching `catalog_items`.
- The two new tenant tables carry `school_id NOT NULL` and the three-disjunct policy. Extend
  [CatalogRlsIntegrationTest](../../services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/security/CatalogRlsIntegrationTest.java)
  to cover both, including the operator-scope disjunct.
- Uploaded assets are school-scoped; the content endpoint resolves the order's `school_id` and
  applies the same scope checks as `markDelivered` before streaming bytes.
- Preserve per-line adjustments, aggregate results, definition snapshots, quotation changes and
  approved artwork references. Record configuration and quote mutations through the existing
  audit/outbox mechanism. CONTRIBUTING's `auditLogService` does not exist in the split services.
- Order assets are business documents, not student PII, but they live in the same private bucket
  with no public URLs. Do not relax that.

## Testing

Backend (school-core-service):

| Test | Type | Covers |
| --- | --- | --- |
| `ProductFormRuleEngineTest` | unit | Confirmed rounding table and configurable modes; exact order total across sizes/rulings; below/above target rejection; non-customised exemption; configurable target; phase/priority ordering; requested values and audit results |
| `ProductFormRuleFixtureTest` | unit | Asserts `contracts/catalog-form-rule-fixtures.json` |
| `CatalogProductFormControllerTest` | unit, standalone MockMvc (pattern: [CatalogValidationTest](../../services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/api/CatalogValidationTest.java)) | Validation `fieldErrors`; `403` for a non-superadmin write; `401` without the service token |
| `CatalogProductFormIntegrationTest` | Testcontainers | CRUD, soft delete, `409` on referenced hard delete, seed correctness |
| `CatalogOrderLineIntegrationTest` | Testcontainers | Structured create/update; positive counts; no line override of order customisation; pending-spec rejection; snapshots survive catalog edits; compatibility summaries; draft totals vs. placement checks |
| `CatalogOrderAssetIntegrationTest` | Testcontainers | Upload/retry/supersede; artwork required for customised placement; photo required for customised delivery; approval references current artwork; cross-tenant read denied |
| `CatalogOrderQuoteIntegrationTest` | Testcontainers | Pending-pricing placeholders; superadmin-only quote; persisted-quantity calculations; complete line coverage; stale version conflict; approval requires quote; outbox totals |
| `CatalogOrderTransitionIntegrationTest` | Testcontainers | Customised design route vs. non-customised direct processing; create/status-PATCH cannot bypass gates; legacy orders retain their recorded workflow |
| `CatalogRlsIntegrationTest` (extend) | Testcontainers | RLS on the two new tables incl. operator scope |

Frontend:

| Test | Covers |
| --- | --- |
| `ProductFormBuilder.test.tsx` | All ruling dropdown options; one order-wide quantity summary updated by line/size changes; printed-page rounding; disabled incomplete sizes; FA/A4 as one option; pending pricing |
| `productFormRules.test.ts` | The shared fixture file — must match the JUnit results exactly |
| `SaCatalogPanel.test.tsx` | CRUD wiring, deactivate vs. delete, `409` surfaced |
| Notebook order workflow tests | School and superadmin structured payloads; save/reopen same draft; upload/place retry without duplicate order; later delivery upload; quote display; non-customised skips design |

Extend platform projection tests to cover absent pricing metadata in old events, pending vs.
quoted display state, and quote-update replay. Include both creation callers and status aliases
in integration coverage; validating only the new form does not verify the server's gates.

Shared quantity fixtures must include customised orders with 400 Long + 600 Jumbo Long books
(valid), totals of 999 and 1001 (placement errors), multiple rulings and page counts contributing
to the same total, and a superadmin-configured target other than 1000. Verify that a non-customised
order can have another positive total and that unmet targets remain saveable as draft feedback.

## Rollout

1. Apply catalog, identity and reporting migrations with the confirmed seeds and legacy-compatible
   defaults. No new form enabled yet.
2. Read API + rule engine + `GET /forms/{code}`. Backend tests green.
3. Superadmin builder UI. Superadmin can edit the seeded definition; nothing else changes.
4. School and superadmin notebook forms, draft editing, order details and quoting behind
   `catalog.product-form.enabled` (per-environment property). Dev first.
5. Verify order lines/assets, shared transition gates, conditional design approval, pricing
   projections, and draft/upload retries end to end before enabling new creation.
6. Enable in production; retain legacy creation behind the coordinated frontend/backend flag
   for one release. Keep version-2 order handling operational during rollback.
7. Remove the legacy notebook branch and decide the fate of the now-redundant `notebook_cover_logo`,
   `notebook_delivery_mode` and `notebook_spine_name` columns on `catalog_orders` (keep writing them
   from the new form, or retire them in a later migration — do not leave them silently null).

A config flag is not a placeholder implementation; each step above ships complete behaviour.

## Non-Goals

- Migrating uniforms, stationery, ID cards, housekeeping, events or health to the builder. The
  design is generic and they can follow later; MVP changes only notebooks.
- A vendor-facing portal or vendor-side asset access.
- A general-purpose expression language for rules.
- Automatic price-list calculation. MVP uses a superadmin quote after submission.
- Workflow redesign beyond conditional notebook design approval and the required quotation gate.
- Line-level reporting. Only form/pricing metadata and quoted totals extend the current projection.

## Product Decisions

Recorded from the product owner's answers on 2026-09-15, in the same order as the eight questions
asked during review. These supersede the original proposal's open questions.

| Decision | Confirmed requirement |
| --- | --- |
| D-1 Pricing | Superadmin quotes after school submission. Use `PENDING_PRICING` and a superadmin price step in MVP. |
| D-2 Quantity | Exactly 1000 books across all sizes and ruling lines combined in each customised order. Only superadmin may alter the configured required total. |
| D-3 Rounding | Nearest multiple of 7, including 198 -> 196. Only superadmin may edit rounding settings. |
| D-4 Page unit | Printed pages, not sheets or leaves. |
| D-5 Combinations | Let all ruling options appear in the dropdown; no seeded size/ruling restrictions. |
| D-6 Incomplete sizes | Keep incomplete sizes visible but disabled. FA and A4 are the same option. |
| D-7 Dimensions | Supplied dimensions are correct. Only superadmin can configure dimension specifications. |
| D-8 Design approval | Non-customised notebooks need no design approval. Customised orders retain it. |

### Quantity confirmation

The product owner clarified: "all sizes combined total 1000". A customised order can therefore
contain 400 Long books and 600 Jumbo Long books, with each size further split across ruling
lines, as long as the entire order totals exactly 1000. A total below or above the configured
target blocks placement. Superadmin can edit that target through the rule settings.

All eight product decisions are settled. No product clarification remains open in this spec.

## Effort Estimate

The original estimate below is retained as a historical baseline for one engineer. It excluded
pricing and predated the aggregate quantity rule, conditional design approval and superadmin
form compatibility changes. It is not the delivery estimate for this revised scope.

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

Original baseline: approximately **4 weeks**, excluding pricing. Re-estimate the confirmed scope,
including quotation storage/API/UI, aggregate validation, both notebook entry points,
workflow guards and pricing projection coverage. Pricing is now required in MVP.
