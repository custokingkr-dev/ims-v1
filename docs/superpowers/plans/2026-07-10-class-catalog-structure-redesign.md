# Class Catalog and School Structure Redesign

## Current Verified Behavior

- `tenant_school.school_classes` is a global class catalog.
- Migration `V16__seed_class_catalog_and_backfill_sections.sql` seeds only numeric classes `1` through `12`.
- A school's active classes are derived from `schools.configured_class_count`: the first N catalog rows by `sort_order, name`.
- A school's active sections are derived from `schools.configured_section_count`: sections `A` through that count, uniformly for every active class.
- `PUT /api/v1/schools/{id}/structure` currently accepts `classCount` in `1..12` and `sectionCount` in `1..26`.
- Bulk import resolves the uploaded `Class` value against the global catalog, then resolves `Section` against this school's active section rows.

## Problem

The product now needs classes before `1`, but the current count-first model cannot safely support that by simply inserting new catalog rows before class `1`.

Example: if pre-class-1 catalog rows are inserted with lower sort order than `1`, an existing school with `configured_class_count = 12` would no longer mean classes `1..12`; it would mean the first 12 rows in the new catalog ordering. That would unintentionally deactivate higher numeric classes for existing schools unless the migration also changes the structure model.

No source-of-truth labels for pre-class-1 classes are present in the repo. Do not invent labels such as Nursery/LKG/UKG without an explicit product decision.

## Required Product Decisions

1. Canonical pre-class-1 class labels and ordering.
2. Whether every school gets those classes by default, or only schools that explicitly select them.
3. Whether "No. of classes" should continue to mean a total count, or whether school setup should become an explicit class checklist.
4. Whether section count remains uniform for every class, or sections can vary per class.
5. Existing-school migration rule for `configured_class_count = 12` so current class `1..12` schools are preserved.
6. Fee structure behavior for any non-numeric classes, because fee setup currently has class range dropdowns hardcoded to `1..12`.

## Recommended Implementation

1. Introduce an explicit per-school class activation table, for example `tenant_school.school_class_activations(school_id, class_id, active, sort_order)`, or make `tenant_school.school_sections.active` the authoritative activation source and stop deriving active classes from the first N catalog rows.
2. Seed the approved class catalog with stable IDs, labels, and sort orders after the labels/order are approved.
3. Backfill existing schools by explicitly activating the same numeric classes they have today, preserving current behavior.
4. Replace structure setup UI from `No. of classes` to explicit class selection plus section count, or to explicit class/section matrix if per-class sections are required.
5. Update bulk import to validate uploaded classes against the school's explicitly active classes, not a count.
6. Update fee setup class selectors to use catalog-backed classes rather than fixed `1..12` numeric ranges.
7. Add migration and integration tests proving an existing `1..12` school remains `1..12` after pre-class-1 catalog rows are added.

## Shipped Stopgap

Bulk import validation now requires the section row to be active for the school's configured setup before previewing a row as valid, and confirm import rechecks the same condition. This prevents imports into inactive leftover sections after a school structure shrink.
