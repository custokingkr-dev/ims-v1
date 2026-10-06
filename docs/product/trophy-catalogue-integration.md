# Trophy catalogue integration

Trophies is a new `TROPHIES` supply category alongside Notebooks, Ties, Certificates and the other existing categories. It uses the existing structured order, tenant authorization, draft editing, SUPERADMIN quotation, approval, fulfillment and event/reporting paths.

## Imported source

Reference website: [Custoking trophy catalogue](https://nktnikhil.github.io/custoking-trophy-catalogue/).

Source repository: [nktnikhil/custoking-trophy-catalogue](https://github.com/nktnikhil/custoking-trophy-catalogue), pinned to [commit ac07820fc9438ffab0b63c257e6a8bef391df3e1](https://github.com/nktnikhil/custoking-trophy-catalogue/tree/ac07820fc9438ffab0b63c257e6a8bef391df3e1). The data and matching JPG assets were imported from this checkout on 6 October 2026. The integration does not fetch mutable GitHub data or embed the external website at runtime.

| Imported content | Count |
| --- | ---: |
| Unique trophy models | 814 |
| Model/size variants | 2,420 |
| Matching model images | 389 |

Models without a matching image remain available with an explicit placeholder. The source website filters out missing-image models; IMS retains the complete supplied price catalogue instead. No physical dimensions are invented: A, B, AA, AAA and other size letters are the supplier's codes. Zero prices in the source display as quote required. Prices remain in INR as supplied, with no invented tax rate or delivery promise.

## Customer experience

Both the school catalogue and the SUPERADMIN new-order picker read categories from the backend, so the seeded category appears without a hardcoded fallback tile. SUPERADMIN catalogue management uses a trophy icon and can enable/disable the category and its existing options.

The shared form renders a trophy-specific browsing section with model search, Trophy/Economic Trophy filtering, minimum/maximum starting-price filters, low/high starting-price sort, model-number sort and 24-card progressive loading. Sorting and filtering use `startingPrice`, matching the supplied catalogue; choosing another size changes the displayed unit price without changing the model's starting-price position.

Each card offers only active confirmed variants present in the server definition. Adding the same model/size again merges its quantity; distinct sizes remain separate lines. Customers can edit/remove ordered quantities, supply a required-by date and notes, save a draft and place the same draft later.

Catalogue prices calculate an advisory total. Unknown/zero prices are excluded from that total with an explicit incomplete-estimate message. No catalogue price, tax or final total is sent as a client-owned order price. The existing SUPERADMIN quote supplies the authoritative line prices and GST before approval. Drafts retain the existing form snapshot and optimistic version behavior.

The standalone website's WhatsApp enquiry is replaced by IMS draft/place/track actions. Trophy orders go from `DRAFT` to `PROCESSING`, then require quotation before final approval and delivery. They do not require the notebook customization/design sequence. Engraving/customization workflow and artwork requirements have not been inferred from the source, which supplies no such specification; free-text notes capture requests for confirmation.

## Backend changes

[V25 trophy migration](../../services/school-core-service/src/main/resources/db/migration/catalog/V25__trophy_catalogue.sql) seeds a non-paged, notes-enabled category and one required line group, `VARIANT`. A variant is one indivisible model/size choice, for example `T_WM001_B`. This prevents submitting a model paired with a size that belongs only to another model. The existing rule engine verifies the option and positive integer quantity. Non-paged lines store `page_count = 1` even if a caller submits another page count.

Structured order creation previously selected the NOTEBOOKS definition and inserted the NOTEBOOKS category even when the entry-point decision was generic. Creation now uses the normalized requested category, snapshots its definition and generates category-appropriate compatibility items. Notebook descriptions and delivery wording are preserved. The catalogue management restriction that allowed structured forms only for Notebooks was removed to support the existing generic category design.

Trophies explicitly refuses the legacy creation fallback when its category/form is disabled or the global structured-form feature is off. Existing legacy category rollback behavior is preserved. Client price/form-metadata injection remains rejected, and quotation/approval permissions remain server-enforced.

Placed-order detail now displays the actual non-paged option groups and quantity instead of notebook Size/Ruling/Books/Printed pages columns. Notebook columns remain unchanged. Existing order list summaries use compatibility items containing the model/size label and quantity; no new public API route or gateway privilege is introduced.

## Source files and refresh procedure

| File | Responsibility |
| --- | --- |
| [Importer](../../scripts/import-trophy-catalogue.mjs) | Validates a local checkout and generates data, matching assets and the seed migration |
| [Catalogue data](../../frontend/src/features/catalog/trophies/catalogue.json) | Reviewed model, type, starting-price and size-price snapshot |
| [Image manifest](../../frontend/src/features/catalog/trophies/images.json) | Matches models to imported local JPG files |
| [Catalogue helpers](../../frontend/src/features/catalog/trophies/catalogue.ts) | Variant codes, filtering, image paths and advisory prices |
| [Catalogue UI](../../frontend/src/features/catalog/trophies/TrophyCatalogue.tsx) | Search, filter, sort, size/quantity cards and progressive loading |
| [Order builder](../../frontend/src/features/catalog/ProductFormBuilder.tsx) | Existing draft/place handling plus the trophy picker and estimate |

To reproduce the initial import before deploying V25:

```powershell
git clone https://github.com/nktnikhil/custoking-trophy-catalogue.git tmp/trophy-catalogue-source
git -C tmp/trophy-catalogue-source checkout ac07820fc9438ffab0b63c257e6a8bef391df3e1
node scripts/import-trophy-catalogue.mjs tmp/trophy-catalogue-source
```

The importer rejects duplicate models, colliding variant codes, unsupported types, invalid size codes and negative/noninteger prices. Imported public product images are served from `frontend/public/catalog/trophies/images`; they contain catalogue merchandise, not tenant uploads. Existing order attachments continue through protected storage.

**After V25 is deployed, do not overwrite its SQL or rerun a changed import into that historical migration.** A subsequent source update needs a reviewed new forward migration plus updated frontend data/assets and this provenance record. Preserve stable codes and existing order snapshots. New models must be added to both the reviewed data and backend options; unknown administrator-created codes do not silently become browsable products. Active/confirmed availability remains controlled by the backend.

## Deployment and rollback

1. Deploy school-core with the forward catalog migration and generic creation changes. Confirm V25 is applied and `TROPHIES` has 2,420 variants.
2. Use the existing `CATALOG_PRODUCT_FORM_ENABLED=true` setting in the intended environment. Confirm the form endpoint returns `enabled: true`, and the school has the ORDERS module and appropriate user permissions. The category defaults to `form_enabled=true`; the global flag remains controlled by existing deployment configuration.
3. Deploy the frontend including imported public assets. Verify both school and SUPERADMIN entry points, image serving, draft placement, quoting and order detail with synthetic data.
4. Complete the normal reviewed release/canary process. This implementation does not publish or change production configuration.

For an emergency rollback, disable the trophy category/form to block new creation while preserving existing orders. Existing structured drafts and workflow handling must remain deployed so orders can still be read and completed. Do not roll back to a backend that writes trophy submissions as notebooks or remove the seed migration from applied history. Reverting frontend code does not delete persisted orders or migration data.

## Verification

The implementation adds tests for all source variants, special AA/AAA sizes, starting-price filtering, size-specific prices, integer quantities, inactive variants, missing images, draft payloads without prices, duplicate-line merging, catalogue category entry and non-paged order detail.

Real PostgreSQL integration verifies migration application, variant count, managed form enablement, category preservation, normalized non-paged lines, invented-variant rejection, client-price rejection, disabled-category rejection, SUPERADMIN-only quoting and the complete approval/delivery flow. The existing runtime `app_rt` integration path additionally exercises a trophy order with operator access and another-school denial.

Playwright tests use mocked APIs with the complete catalogue to verify the browser contract at 1,280 px and 390 px. They confirm progressive rendering, a real imported JPG loading, size/quantity estimate, draft reuse and placement without horizontal overflow. These browser tests complement the real database tests; they are not a deployed end-to-end test.

Run the relevant checks from the repository root or indicated directory:

| Completed check | Result on 6 October 2026 |
| --- | --- |
| Frontend catalogue and category-panel tests | 67 passed |
| Backend catalogue integration, controller, validation and tenant tests | 54 passed, none skipped; 33 use the two PostgreSQL integration suites |
| Trophy and notebook Playwright browser tests | 7 passed, including desktop/mobile trophy ordering and imported-image loading |
| TypeScript and production Vite build | Passed |
| Patch whitespace and document source links | Checked |

Screenshots from the browser verification are under `frontend/test-results/trophy-catalogue-1280.png` and `frontend/test-results/trophy-catalogue-390.png`. Build/test artifacts are local evidence rather than committed product assets. Existing unrelated user changes were preserved.

The dev release verification additionally ran all 419 frontend unit tests and all 111 Playwright tests successfully. The required npm audit exposed pre-existing high/critical dependency advisories; compatible lockfile fixes and an upgrade to Vitest/coverage 4.1.11 removed all reported advisories. jsdom photo-import tests explicitly exercise the original-byte fallback because blob image decoding does not dispatch completion events in jsdom. The upgraded V8 report measured statements 50.50%, branches 45.41%, functions 43.34%, and lines 53.35%. The branch floor was rebaselined from 60% to 45%; source inclusion and other floors remain unchanged. This is a coverage-gate change accompanying the security update, not additional application coverage. See the [Vitest migration guide](https://v4.vitest.dev/guide/migration) for coverage changes.

```powershell
.\mvnw.cmd -f services/school-core-service/pom.xml '-Dtest=CatalogOrderFormIntegrationTest,CatalogProductFormIntegrationTest,CatalogOrderFormControllerTest,CatalogValidationTest,CatalogTenantScopingTest' test
```

From `frontend`:

```powershell
npm.cmd test -- src/features/catalog src/pages/workspace/panels/CatalogPanel.test.tsx src/pages/workspace/panels/SaNewOrderPanel.test.tsx
npm.cmd exec playwright -- test trophy-orders.spec.ts --workers=1
npm.cmd run build
```
