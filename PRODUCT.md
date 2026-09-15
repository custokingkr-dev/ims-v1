# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Record Scope

This concise record captures the notebook procurement decisions confirmed by the product owner
on 2026-09-15. It does not redefine other Custoking IMS modules or introduce a new visual identity.

## Users

- School administrators submit notebook requirements and manage their school's orders.
- Operations staff work within their assigned schools and handle design/delivery steps.
- Superadmin owns catalog definitions, specifications, quantity/rounding rules, quotations and
  final order approval.

## Product Purpose

Collect consistent notebook requirements, preserve what was ordered, and move each order through
the applicable quotation, approval and delivery stages.

## Capabilities and Constraints

- Customized orders total exactly 1000 books across every size and ruling line combined. Only
  superadmin can change that target.
- Page counts mean printed pages. Default rounding is nearest multiple of seven, including
  198 -> 196; superadmin can change rounding settings.
- All ruling choices remain available. Incomplete dimensions are visible but disabled; FA and
  A4 are one size. Only superadmin can edit dimensions.
- Schools do not enter prices. Superadmin quotes after submission.
- Non-customized notebooks need no design approval. Customized books require artwork and a
  pre-delivery photo; both order types require a quote and final superadmin approval.
- Saved orders retain their specifications and rule snapshots when the catalog later changes.
- School data and attachments remain tenant-scoped and private.

## Evidence on Hand

- [Confirmed specification](docs/product/notebook-order-form-builder.md)
- [Implementation and rollout notes](docs/product/notebook-order-form-builder-implementation.md)
- Existing application source and tests under `frontend/` and `services/`

Local demonstration records are test data, not customer evidence. Production deployment and
live storage access are not implied by local verification.

## Product Principles

1. Keep superadmin-owned business rules configurable without letting school callers override them.
2. Validate the whole order, including requirements spread across multiple sizes and rulings.
3. Preserve requested values, quoted amounts, specifications and approval evidence for review.
4. Enforce the same permissions and workflow checks through every API entry point.
