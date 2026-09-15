---
version: 1
slug: "notebook-order-workflow"
primary_target: "ProductFormBuilder.tsx"
related_targets: ["ProductOrderDetail.tsx","OrderAssetField.tsx","ProductCatalogManager.tsx","product-form.css"]
---

# Notebook Order Workflow

## Scope and Mode

This brief is local to `frontend/src/features/catalog`. It records the implemented extension to the incumbent Operate task UI. Its primary target and related targets are relative to this feature boundary. School administrators enter requirements, superadmin configures and quotes, and authorized operations staff review evidence and advance the applicable workflow.

## Task and Evidence

The first form view identifies the notebook order and pending pricing, followed by order-level choices and labeled order lines. The combined quantity band exposes the actual and required totals. Requested printed pages and their normalized result remain visible. Attachment areas state when each file is needed. Save draft and Place order are distinct commands.

Saved order detail begins with navigation, order identity, and state. The specification rows, quotation amounts, attachment evidence, and eligible approval or delivery commands follow. Mobile quotation rows retain all applicable fields and visible labels. GST is visibly an amount in rupees.

Catalog management exposes Products, Form, Conditions, and Preview. Editable conditions use labeled controls, and Preview renders the shared intake component.

## Direction Contract

- **THESIS:** Make notebook requirements, configured rules, and saved-order evidence reviewable within the existing task interface.
- **OWN-WORLD:** Inherit the established DM Sans controls, white and cool-gray surfaces, green actions, thin divisions, and meaningful status colors. Exact values are in [DESIGN.md](../../DESIGN.md).
- **STORY:** A user enters requirements, reviews normalization and combined quantity, attaches stage-specific evidence, saves or places, and then sees quotation and the eligible approval/delivery actions.
- **FIRST VIEWPORT:** Intake identifies the product and pricing state above its first labeled fields; saved detail identifies the order and state above line evidence. Catalog presents product selection and local tabs. This is a record of the implemented surfaces, not an approved composition mockup.
- **FORM:** A precise local extension of existing forms, rows, tables, and controls. No new identity, concept seed, or comp round was established for this work.
- **FINISH:** The existing finish review and verdict are linked below; the scoped design documentation records source contracts. No new raster assets are produced by this documentation pass.

## Integration References

All paths below are relative to this brief.

- [CatalogPanel](../../../../pages/workspace/panels/CatalogPanel.tsx): school notebook intake.
- [AdminOrdersPanel](../../../../pages/workspace/panels/AdminOrdersPanel.tsx): school order detail.
- [SaNewOrderPanel](../../../../pages/workspace/panels/SaNewOrderPanel.tsx): selected-school intake.
- [SaAllOrdersPanel](../../../../pages/workspace/panels/SaAllOrdersPanel.tsx): superadmin order detail.
- [SaOrderApprovalsPanel](../../../../pages/workspace/panels/SaOrderApprovalsPanel.tsx): approval detail.
- [SaCatalogPanel](../../../../pages/workspace/panels/SaCatalogPanel.tsx): catalog configuration.

## Authority and Verification Limits

[PRODUCT.md](../../../../../../PRODUCT.md), the [confirmed specification](../../../../../../docs/product/notebook-order-form-builder.md), and [implementation notes](../../../../../../docs/product/notebook-order-form-builder-implementation.md) own product and rollout facts. This brief does not duplicate those business rules.

The [finish review](../../../../../../.impeccable/review/finish-review.md) and [verdict](../../../../../../.impeccable/review/finish-verdict.md) record resolution of the three scored findings: mobile quotation reflow, explicit GST units, and persisted product context. Ship applies to those scored fixes.

Previously reviewed captures are `notebook-desktop.png`, `notebook-mobile.png`, `catalog-desktop.png`, `catalog-mobile.png`, `quote-desktop.png`, and `quote-mobile.png` under the repository-root `.impeccable/review/`. This documentation pass inspected source and the recorded verdict; it did not repeat browser QA, detectors, integration tests, or production verification. No new visual direction or user preference is inferred.
