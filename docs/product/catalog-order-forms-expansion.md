# Catalog Order Forms: Notebook Revision and Four New Categories

Source: five order-form prototypes supplied 2026-09-23 (flex, flier, bill book, belt, notebook).
Those prototypes are the specification for what follows; this document records what they require,
what already exists, and what has to be built.

Status: built and enabled in dev and stage, with both product decisions confirmed (see the end of
this document). Production is deliberately still off pending a walkthrough of one real order per
category in dev — no order has yet been submitted through any of these forms in any environment.

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

**Correction, 2026-09-25 — the size claim below was wrong.** This document previously recorded
"King is 19 × 26 cm", and V11 confirmed King at 190 × 260 on that basis. The prototype's markup says
otherwise:

```html
<option value="King"       data-dim="">King</option>
<option value="Jumbo King" data-dim="19 x 26 cm">Jumbo King — 19×26 cm</option>
```

19 × 26 cm is **Jumbo King's**, which the original V9 seed already had right. V11 both invented a
specification for King and duplicated Jumbo King's. V16 restores King to `PENDING_SPEC` with null
dimensions — visible but unorderable, which is what "no agreed size" means here. Drawing book stays
`PENDING_SPEC` for the same reason: the prototype gives it no dimensions either.

Two wordings also follow the prototype rather than the original seed: the order-scope group is
labelled **Category** (not "Customization") with the choices **Custom** and **Wholesale**, and the
notebook per-line count reads **Quantity**. The option *codes* are unchanged, because every rule's
`match_options` and every existing order reference them.

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

**Correction, 2026-09-23.** Step 5 was reported complete on the strength of the form component
alone, and the component was only half the frontend. The two panels that *host* it —
`CatalogPanel` (school side) and `SaNewOrderPanel` (superadmin) — chose the category from a
hardcoded array and rendered the builder only when `activeCat === 'NOTEBOOKS'`. So the four new
categories were seeded, migrated and served over the API, and still had no tile to click. Both
panels now build their tiles from `/supply/product-catalog/categories` and treat any
`formEnabled` category as orderable; the hardcoded lists survive only as a source of icons and of
the legacy hand-written forms.

The same omission hid a second defect: `ProductFormBuilder`'s `categoryCode` prop defaulted to
`'NOTEBOOKS'` and is what gets posted as the order's `category`. Any order placed through a new
category's form would have been filed as a notebook order. The category now comes from the
definition, which cannot disagree with the form being displayed.

## Uploads and print references (2026-09-25)

Every prototype offers an upload and all of them are optional, but the model could only express a
*required* asset (`REQUIRE_ASSET`). Four of the five categories therefore had no upload at all, and
the print-reference block on bill books and fliers did not exist. V18 adds what they show:

| | Upload | Accepts | Cap |
| --- | --- | --- | --- |
| Notebooks | Sample design | PNG, JPEG, WebP, PDF | 5 MB |
| Bill books | Bill book image + several print references | PNG, JPEG, WebP | 5 MB |
| Belts | Belt image | PNG, JPEG, WebP | 5 MB |
| Flex | Flex PDF | PDF | **10 MB** |
| Fliers | Flier image + several print references | PNG, JPEG, WebP | 5 MB |

Three things this required. `OFFER_ASSET` is an optional upload that never blocks placement, so the
existing `REQUIRE_ASSET` keeps its meaning — where both name the same kind, as on a customised
notebook, the offer is dropped and one field is shown. `PRINT_REFERENCE` is a kind that accumulates:
the unique index that keeps one current asset per kind now excludes it, because a reference set is a
set rather than a replacement. And the size cap moved from a single constant onto the rule, since
the flex PDF is 10 MB where every image field is 5 MB; the table's own check allows 10 MB and no
seeded rule can raise a cap past it.

"Share the content (optional)" beside those images is an order-scope typed value, stored in the new
`catalog_orders.attributes` — the counterpart of `catalog_order_lines.attributes`, so it needed no
new table.

**Still not built:** the prototypes' "Placed this session" table and the image lightbox. Those are
page furniture rather than the order form, and the form lives inside the existing workspace
navigation.

## Report cards, an eighth category (2026-09-25) - and the first price shown to a school

Ordering works like certificates: a line built from an after-folding size (A4, A5), inner pages
(0, 4, 8, 12, 16, 20, 24) and whether folding is required, with a **minimum of 50 per line** that
rejects rather than raising.

**This is the first form that shows a school a price**, which cuts against the rule recorded for
notebooks: "Schools do not enter prices. Superadmin quotes after submission." Two things keep both
true. The school still types no price - every figure is computed - and the estimate is labelled
*"Estimate only. The Custoking quote is the price of record."* The quote flow is untouched.

The rates are transcribed from the prototype, which attributes them to a source sheet:

| | A4 | A5 |
| --- | --- | --- |
| D7, per card | 16 | 8 |
| D8, per card per signature of 4 inner pages | 14 | 7 |

D9 is 250 when folding is required, otherwise 80 / 150 / 200 by quantity band (=100, =200, above).
E9 charges 6 per unit once quantity reaches 150 and a flat 150x6 below it, and falls back to D9 when
there are no inner pages at all. **D9 is excluded from the cost per card**, which the prototype
states explicitly and is easy to get wrong:

    cost per card = (D7 + D8 + E9) / quantity

Every rate is seeded on a `COST_ESTIMATE` rule rather than written into the frontend, so a wrong
rate is a data fix rather than a release. If any rate is missing or malformed the estimate is
hidden rather than shown as zero.

While adding it, `validateRule` turned out to reject `OFFER_ASSET`, which V18 introduced - a
superadmin editing any optional upload would have been refused. Fixed here along with the new rule
type.

## Certificates, a seventh category (2026-09-25)

No matrix: a line is built one at a time from a category, size, GSM and count, the way bill books
and fliers are.

| | |
| --- | --- |
| Category | Generic, Individual customisation |
| Size | A3, A4, A5 |
| GSM | 250, 300 |
| Per line | Count, **minimum 20** |
| Uploads | Certificate image, plus several print references. PNG/JPEG/WebP, 5 MB each, both optional |

From the prototype's source: `var MIN_COUNT = 20;` and *"Minimum order quantity is 20 per size/GSM
line."* The minimum **rejects** rather than raising, so it is `MIN_VALUE` like the flier's 3000, not
the `FLOOR_VALUE` that silently raises a customised notebook line. `findGroup(state.cat)` keeps one
group per category inside a single order, so the category is a **line-scope** choice rather than an
order-wide one: Generic and Individual customisation certificates can share one order.

**One conflict, left unbuilt.** This prototype also shows "Share the content (optional)" beside the
print references. That is the field removed on the same day when the note became flier-only, so
adding it back here would contradict that decision. The uploads are in; the text field is not. It is
a single seeded row to add if the note is wanted on certificates after all.

## Ties, a sixth category (2026-09-25)

From a prototype supplied after the first five. Shaped like belts: a tie type chosen once, then a
table of lengths each with its own count, rows left at zero skipped, and an optional image.

| | |
| --- | --- |
| Tie type | Satin Tie with logo, Satin Tie, Cloth Tie, Readymade ties |
| Length | 10, 11, 12, 14, 16 inch, plus **Long Tie** fixed at 48 inch |
| Per line | Count, with no minimum or rounding |
| Upload | Tie image, PNG/JPEG/WebP, 5 MB, optional |

Two details taken from the prototype's source rather than its appearance, because the difference
matters:

```js
var LENGTHS_STANDARD = ["10 inch","11 inch","12 inch","14 inch","16 inch"];
var LONG_TIE_ROW = "Long Tie · 48 inch";
/* Every tie type gets the 10-16 inch range, plus the Long Tie row (fixed 48 inch) - Long Tie is
   a subcategory available under each of the 4 tie types, not a separate type. */
```

So **Long Tie is a sixth length, not a fifth type**, and **no length depends on the type** - unlike
belts, there are no dependency rows. The list is exactly those five lengths; there is no 13 or
15 inch.

Ties needed **no frontend change at all**. The tiles and the form are driven by the definition, so
seeding a category is now enough to make it orderable - which is the whole point of the earlier fix.

## Two more, later on 2026-09-25

**A wholesale notebook run offers no upload.** It is printed from stock, so there is no artwork to
attach. The sample design is matched to `CUSTOMIZATION = CUSTOMIZED`, which hides the field for
wholesale with no code change - the rule engine already filtered offers by their match.

**Bill books collect a note after all.** This reverses part of the decision taken earlier the same
day, when the note became flier-only and the `PRINT_CONTENT` group was removed from bill books.
`notes_enabled` is now true for fliers and bill books. The earlier test asserted "fliers alone" and
was rewritten rather than worked around; the decision changed, so the test had to.

## Two changes on 2026-09-25

**Artwork no longer blocks a customised notebook order.** The 2026-09-15 record said a customised
order requires artwork and a pre-delivery photo; the upload is now optional, which also matches the
prototype's own "Sample design (optional)" label. The upload itself stays, as the optional sample
design every category offers, and the pre-delivery photo is untouched because it belongs to a later
stage. Two tests that asserted a placement *fails* without artwork now assert it succeeds; they
encoded the old rule, so changing them is the point rather than a workaround.

**The free-text note belongs to fliers alone.** It used to appear on every category, and the
"Share the content" group added for bill books and fliers duplicated it, so that group is gone.
`product_categories.notes_enabled` carries the distinction, beside `form_enabled` and `paged`, so
the form reads it from the definition rather than naming a category in code — the hardcoding that
made the four new categories invisible in the first place.

Still open: whether "sample image upload under every option" means one upload per category renamed
consistently, one per line in an order, or one per selectable option defined in the catalog. Not
built pending that answer.

## Decisions taken by the product owner

1. **The notebook quantity change reverses D-2, and that reversal is confirmed (2026-09-23).** The
   customised rule is a floor of 1000 on each ruling line, not an exact combined total of 1000. D-2
   of 2026-09-15 is superseded; `notebook-order-form-builder.md` still records the original wording,
   so read this document alongside it.
2. **King has no agreed size** and stays `PENDING_SPEC`. The 19 × 26 cm reading was an error of
   mine, corrected 2026-09-25: those are Jumbo King's dimensions. Nothing was decided here, so
   there is no decision to revisit — but if King *does* have a size, someone has to supply it.
3. **Drawing book** remains unorderable until someone supplies dimensions.
4. **Flier size stays free text, confirmed 2026-09-23.** Anything typed is accepted, so `A4`, `a4`
   and `A-4` are distinct values in reporting. This was chosen deliberately over a seeded list for
   the flexibility the prototype shows; if reporting on flier sizes later matters, normalising the
   stored value is the cheaper fix than migrating orders to a fixed list.
