# Catalog Order Forms: Notebook Revision and Four New Categories

Source: five order-form prototypes supplied 2026-09-23 (flex, flier, bill book, belt, notebook).
Those prototypes are the specification for what follows; this document records what they require,
what already exists, and what has to be built.

Status: notebook revision implemented. All four new categories are built on the backend and render
through the shared order form. Remaining: enabling the feature flag, and deciding whether the
flier size stays free text.

## Where the current implementation stands

The data model is already generic — `product_categories`, `product_option_groups`,
`product_options`, `product_form_rules`, `catalog_order_lines`, `catalog_order_assets`. The rule
engine evaluates `ROUND_TO_MULTIPLE` and `MIN_VALUE`/`MAX_VALUE` per line, and
`REQUIRE_QUANTITY_TOTAL` across the order, with every rule conditional on selections.

Two things are not generic:

1. **The entry point is hardcoded to one category.**
   ```java
   public boolean handlesCreation(String category) {
       return "NOTEBOOKS".equalsIgnoreCase(category.trim()) && products.isEnabled();
   }
   ```
   `product_categories.form_enabled` already exists and is the right source of truth. Until this
   reads that column, any new category silently falls through to the legacy order path — the
   unguarded branch the 2026-09-22 security review flagged.

2. **A line can only carry option selections plus `bookCount` and `pageCount`.** Three of the four
   new forms need free-form per-line values that are not selections from a fixed list:

   | Form | Needs |
   | --- | --- |
   | Flex | `length_ft`, `breadth_ft` — decimals entered per line |
   | Flier | `size` — free text (`A4`, `8.5" x 11"`); `gsm` — integer entered per row |
   | Bill book | all fixed options; count only |
   | Belt | all fixed options; count only |

   Bill book and belt fit the existing model unchanged. Flex and flier do not.

Also relevant: **`CATALOG_PRODUCT_FORM_ENABLED` is set nowhere**, so even the notebook form is dark
in every environment. Building more forms behind that flag ships nothing until it is turned on.

## Notebook revision (implemented)

The prototype changes a decision recorded as confirmed on 2026-09-15.

| Rule | Recorded 2026-09-15 (D-2) | Prototype 2026-09-23 | Effect |
| --- | --- | --- | --- |
| Customised quantity | Exactly 1000 books across **all sizes and ruling lines combined**; below or above blocks placement | **Floor of 1000 per ruling line**, and only for Customised; non-customised has no minimum | Materially different: a per-line minimum instead of an exact order total |

The prototype's own code is unambiguous:

```js
var CUSTOM_MIN = 1000;
function minFor(type){ return type === 'Customised' ? CUSTOM_MIN : 1; }
// on add:  count: Math.max(q, min)
// on edit: r.count = Math.max(min, r.count + delta)
```

It **floors** — it raises a low count to 1000 rather than rejecting it. That is a clamp, like
`ROUND_TO_MULTIPLE` already performs for pages, not a validation like `MIN_VALUE`. So the engine
gains a `FLOOR_VALUE` rule type that clamps and records the adjustment in `appliedRules`, keeping
the audit trail the rounding rule established.

Unchanged and already correct: pages snap to the nearest multiple of 7 with a floor of 7
(`roundToSeven` matches `round(..., 7, "NEAREST", 7)`, so 198 → 196); rows left at zero are
skipped; schools never enter prices.

Also from the prototype: **King is 19 × 26 cm**. The seed carries it as `PENDING_SPEC` with null
dimensions, which renders it visible but unorderable. It becomes `CONFIRMED` at 190 × 260 mm.
Drawing book stays `PENDING_SPEC` — the prototype gives it no dimensions either.

## The four new categories

Common to all four: a school/customer, an optional required-by date, per-line counts with rows at
zero skipped, a running total, draft saving, and an order reference on submission. All reuse the
existing order, asset, approval and quotation flow. None of them prices anything school-side.

### Flex

| Aspect | Specification |
| --- | --- |
| Type (line) | Star flex, Normal flex, Backlit flex, Star backlit flex, Flex with black background |
| Per line | Length (ft), Breadth (ft), Count |
| Asset | PDF, max 10 MB |
| Rules | None — no minimum, no rounding |

Needs per-line decimals. Area (`length × breadth × count`) is the natural quotation basis; the
prototype does not compute it, so superadmin quotes from the stored dimensions.

### Flier

| Aspect | Specification |
| --- | --- |
| Per group | Size — free text, e.g. `A4`, `A5`, `8.5" x 11"` |
| Per row | GSM (e.g. 100, 130, 170), Count — **minimum 3000** |
| Assets | Flier image PNG/JPG/WEBP max 5 MB; optional print-reference images |
| Constraint | Single-page fliers only |

The 3000 minimum is a rejection in the prototype (`min 3000` on the input, "a count of at least
3000"), unlike the notebook floor — so `MIN_VALUE` fits, no new rule type.

### Bill book

| Aspect | Specification |
| --- | --- |
| Size | A3, A4, A5 |
| Pages | 50, 100, 200 |
| Perforation | Yes, No |
| Sequence | White up, Color up |
| Slips per page | 1, 2, 4, 8 |
| Per line | Count |
| Assets | Images PNG/JPG/WEBP max 5 MB |
| Rules | None |

Fits the existing model with no changes — five single-select option groups at line scope.

### Belt

| Aspect | Specification |
| --- | --- |
| Belt type | Cloth Belt, Satin Belt |
| Buckle type | Bronze, Plastic |
| Lengths | 70, 75, 80, 85, 90, 95 cm — a matrix of count per length |
| Assets | Belt image PNG/JPG/WEBP max 5 MB |
| Rules | None — "Count is a plain entry — no minimum or rounding rules" |

Fits the existing model. Lengths load only after both type and buckle are chosen, which the
existing dependency mechanism (`checkDependencies`) already expresses.

## Build order

1. **Un-hardcode the entry point.** `handlesCreation` reads `product_categories.form_enabled`
   instead of the `NOTEBOOKS` literal. Without this nothing else is reachable, and a half-built
   category would fall through to the legacy path. Smallest change, largest consequence.
2. **Bill book and belt.** Seed-only: categories, option groups, options, dependency for belt
   lengths. They prove the generic path end to end without touching the model.
3. **Per-line attributes.** Extend `catalog_order_lines` with a validated `attributes` JSONB plus
   an `product_option_groups.input_type` of `TEXT`/`INTEGER`/`DECIMAL`, so a group can capture a
   typed value instead of a selection. The rule engine normalises and validates these the way it
   already does counts.
4. **Flex and flier.** Seed plus the new input types; flier additionally gets a `MIN_VALUE` of 3000
   on count.
5. **Frontend.** One form component per category driven by the definition, rather than five
   bespoke forms. The existing notebook form is the closest model.
6. **Enable.** Set `CATALOG_PRODUCT_FORM_ENABLED=true` per environment, dev first, once each
   category's rules are seeded — an enabled but unseeded category rejects every order.

Steps 1 to 5 are done. The form component was already parameterised by category and definition, so
it needed three changes rather than four new forms: a group renders a typed input when its
`input_type` is not `SELECT`, the printed-page field appears only for a paged category, and the
count is labelled from the category rather than assumed to be books. `product_categories.paged`
carries that last distinction so the form does not have to guess from the rules.

## Decisions that need a product owner

1. **The notebook quantity change reverses D-2**, which was recorded as confirmed on 2026-09-15.
   Implemented here as the prototype specifies. If the exact-1000-combined rule was the intended
   policy and the prototype is a draft, this must be reverted before the form is enabled.
2. **King at 19 × 26 cm** is taken as confirmed, superseding `PENDING_SPEC`.
3. **Drawing book** remains unorderable until someone supplies dimensions.
4. **Flier size is free text.** Anything typed is accepted, so `A4`, `a4` and `A-4` are distinct
   values in reporting. A fixed list would be cheaper to report on later.
