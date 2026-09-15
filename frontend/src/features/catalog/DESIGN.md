---
name: Operate Catalog
description: Scoped visual contracts for notebook intake, catalog configuration, and order review.
colors:
  primary: "#166b49"
  primary-soft: "#e8f4ee"
  surface: "#ffffff"
  app-ground: "#f4f6f8"
  text-primary: "#1f2933"
  text-secondary: "#667085"
  text-muted: "#66707f"
  border-subtle: "#e3e7ec"
  border-default: "#d3d9e1"
  warning: "#a45b16"
  warning-soft: "#fff3e6"
  information: "#2f68b2"
  danger: "#b42318"
  danger-soft: "#fdecea"
typography:
  title:
    fontFamily: "'DM Sans', system-ui, sans-serif"
    fontSize: "1.125rem"
    fontWeight: 700
    lineHeight: 1.5
    letterSpacing: "0"
  section-title:
    fontFamily: "'DM Sans', system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 700
    lineHeight: 1.5
    letterSpacing: "0"
  body:
    fontFamily: "'DM Sans', system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "'DM Sans', system-ui, sans-serif"
    fontSize: "0.8125rem"
    fontWeight: 500
    lineHeight: 1.5
  supporting:
    fontFamily: "'DM Sans', system-ui, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 400
    lineHeight: 1.5
rounded:
  xs: "4px"
  sm: "8px"
  pill: "999px"
spacing:
  "1": "4px"
  "2": "8px"
  "3": "12px"
  "4": "16px"
  "5": "20px"
  "6": "24px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.surface}"
    rounded: "{rounded.pill}"
    padding: "9px 15px"
  button-ghost:
    backgroundColor: "transparent"
    textColor: "{colors.text-secondary}"
    rounded: "{rounded.pill}"
    padding: "9px 15px"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-primary}"
    typography: "{typography.label}"
    rounded: "{rounded.xs}"
    padding: "9px 10px"
  tab-selected:
    backgroundColor: "transparent"
    textColor: "{colors.primary}"
    padding: "12px 8px"
  status-pending:
    backgroundColor: "{colors.warning-soft}"
    textColor: "{colors.warning}"
    padding: "3px 8px"
  quantity-complete:
    textColor: "{colors.primary}"
    padding: "16px 0"
  alert-error:
    backgroundColor: "{colors.danger-soft}"
    textColor: "{colors.danger}"
    rounded: "{rounded.sm}"
    padding: "11px 14px"
---

# Design System: Operate Catalog

## Overview

**Creative North Star: "Operate task UI"**

This is an implementation record for `frontend/src/features/catalog`. It extends the existing task interface: compact controls, clear order state, quiet surfaces, and visible evidence for each decision. The name describes the incumbent direction supplied for this extension; it is not a newly chosen brand identity or an invented user preference.

The shared [token stylesheet](../../styles/tokens.css) remains the source of truth. This frontmatter records the reused subset for this feature; reconcile it when those source tokens change. [Global styles](../../styles.css) supply the body, buttons, alerts, and status indicators; [feature styles](product-form.css) supply the local forms and responsive behavior. The [product record](../../../../PRODUCT.md) owns business rules. The [surface brief](.impeccable/surfaces/notebook-order-workflow.md) owns the workflow and integration scope.

**Key Characteristics:**

- Compact labeled controls and readable numerical comparisons.
- White inputs on a cool-gray application ground.
- Green commands and focus, with meaningful warning, information, and error colors.
- Flat sections separated by thin rules.
- Order evidence remains visible across desktop and mobile layouts.

## Colors

The palette combines the incumbent green action color with neutral task surfaces and distinct status colors.

### Primary

- **Primary green:** filled commands, selected catalog tabs, keyboard focus, and a satisfied quantity condition.
- **Soft green:** successful feedback and quoted status.

### Secondary

- **Warning amber and soft warning:** pending pricing and an unmet combined quantity.
- **Information blue:** the requested-to-normalized printed-page adjustment.
- **Danger red and soft danger:** validation and request errors. These colors carry meaning in the feature; they do not establish a second brand accent.

### Neutral

- **White surface and cool application ground:** editable controls, disabled fields, table headings, and attachment preview backgrounds.
- **Primary, secondary, and muted text:** values, secondary commands, and supporting evidence.
- **Subtle and default borders:** section divisions and control boundaries respectively.

**The Status With Words Rule.** Pair each status color with explicit text stating the condition or outcome.

## Typography

DM Sans serves local section headings, body text, labels, and supporting evidence. It inherits the existing body line height. The surrounding workspace retains its own page-title treatment; this document does not extend that display treatment into compact form sections.

### Hierarchy

- **Title:** local h2 headings, using the title role.
- **Section title:** attachment and other local h3 headings, using the section-title role.
- **Body:** order details and dense task content.
- **Label:** field names and table content; numerical tables use tabular figures.
- **Supporting:** specifications, file metadata, requirements, and adjustment evidence.

The token floor is 12px. The inherited global status chip currently declares 11px; that carried inconsistency is not a new typography token or guidance for future text. No viewport-scaled type is introduced here.

**The Compact Heading Rule.** Use the local DM Sans title roles for form sections and keep heading letter spacing at zero.

## Layout

Use unframed sections with thin horizontal divisions. Section headers and action rows wrap; child fields use zero minimum width to permit shrinkage. The reused spacing subset is recorded above.

[Feature styles](product-form.css) implement the following local geometry:

- General form fields use two equal columns, then one column at 520px and below.
- Editable order lines use four tracks on desktop: two wider selection tracks and two numerical tracks. At 900px and below they become two equal columns.
- Attachment sections use two columns, becoming one at 900px and below.
- Order detail is centered with a maximum width of 1100px.
- At 600px and below, each order-table row becomes a two-column labeled group. Size, ruling, books, printed pages, unit price, and line total remain available together when the user's quotation state includes all six fields. Column headings remain in the accessible table structure; each visible cell repeats its label.
- Totals align to the end with a maximum width of 340px. The monetary input can shrink while its unit label remains visible.
- Catalog tabs keep their labels intact and can scroll horizontally. Action rows wrap; controls have a minimum height of 36px, increasing to 40px at 520px and below. Icon controls keep their fixed 36px width.

These three feature breakpoints are observed local implementation values. They do not replace the shared stylesheet's documented breakpoint vocabulary.

**The Complete Line Rule.** Keep a quoted line's specification, quantity, price, and total readable together on small screens.

## Elevation & Depth

The feature introduces no shadows. Thin borders and changes in surface tone distinguish inputs, table headings, attachment areas, and feedback. The surrounding workspace owns its existing containers and elevation.

Buttons inherit the existing brightness response and filter/opacity transition (150ms). Fields inherit a border-color transition (130ms). Keyboard focus uses a green outline (2px) with an offset (3px); focus around file selection follows the upload label. These details live in the sidecar because the frontmatter schema has no focus or motion fields.

## Shapes

Inputs have small corners using the xs radius. Existing primary and ghost commands retain the pill radius. Feedback uses the sm radius. Form sections and asset sections use horizontal borders without decorative card frames.

Attachment images use containment so the entire artwork or photograph remains inspectable. The feature displays actual selected or uploaded files, with a document icon for non-image content.

## Components

### Buttons

Primary commands use green and white; secondary commands use a transparent ground and a neutral border. Disabled buttons inherit lower opacity and a disabled cursor. Lucide icons identify commands, while icon-only buttons expose both a title and an accessible name. See [ProductFormBuilder](ProductFormBuilder.tsx), [ProductOrderDetail](ProductOrderDetail.tsx), and [ProductCatalogManager](ProductCatalogManager.tsx).

### Inputs / Fields

Labels remain visible above native selects and inputs. Disabled fields use the application ground and muted text. Pending specifications stay listed as disabled options with an explicit reason. Page adjustment feedback stays beside its input; it appears after blur, while order detail preserves the requested value beside the accepted one. See [ProductFormBuilder](ProductFormBuilder.tsx).

### Navigation

The catalog has Products, Form, Conditions, and Preview tabs. The active tab uses a green underline and a stronger weight. Existing tab roles, selected state, and panel associations remain part of the component. The order detail exposes a back command and a separately named refresh command. This is local navigation; the application shell is outside this record.

### Status and Feedback

Pending pricing and Quoted are explicit badges. Validation uses alert semantics, while successful saves, loading, and combined quantity feedback use status semantics. The aggregate quantity strip places the all-sizes count and remaining or excess quantity on the same divided band. Its state comes from rule evaluation, not a hard-coded display target.

### Attachments

[OrderAssetField](OrderAssetField.tsx) pairs the requirement stage with its title, then shows a preview or document icon, filename, size, and upload state. Filenames can wrap anywhere. The preview is 96 by 88px, reducing to 68 by 72px at the narrow breakpoint. The controls offer choose/replace, selected-file removal, and downloaded content as appropriate. Empty and failed-preview states remain textual and actionable.

### Quotation and Approval

[ProductOrderDetail](ProductOrderDetail.tsx) uses the same table and mobile row structure for saved values and editable prices. Monetary labels state rupees explicitly, including **GST amount (Rs.)**; GST is displayed as an amount. Pending prices remain named instead of appearing as a misleading zero. Approval and delivery commands expose their prerequisites and disabled state.

### Catalog Rows

[ProductCatalogManager](ProductCatalogManager.tsx) presents products, option groups, options, and conditions as separated rows with text state and adjacent commands. Native checkboxes represent binary settings; select controls represent rule and option sets. Preview uses the same form component as order intake, keeping visual behavior shared.

The [sidecar](.impeccable/design.json) contains static, self-contained examples of these existing primitives. It does not implement application state or replace the React components.

## Do's and Don'ts

### Do:

- Do reuse shared color, type, radius, and spacing tokens.
- Do keep order evidence, status text, and monetary units visible.
- Do preserve native field labels and named icon commands.
- Do retain complete labeled quotation rows at small widths.
- Do show actual attachment content with containment and wrapping filenames.

### Don't:

- Don't use status colors as decorative fills in this feature.
- Don't apply workspace display typography to local form headings.
- Don't turn the feature's observed breakpoint exceptions into global defaults.
- Don't inherit the carried 11px status text as a new typography standard.
