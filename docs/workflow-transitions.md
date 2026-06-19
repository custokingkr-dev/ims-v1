# Workflow Transitions

This document describes the status-transition models for every workflow-governed
entity in Custoking IMS.  All transitions are enforced by `WorkflowTransitionValidator`
on the service layer — controllers must not implement transition logic themselves.

---

## Table of contents

1. [Design principles](#design-principles)
2. [Catalog Order workflow](#catalog-order-workflow)
3. [Firefighting Request workflow](#firefighting-request-workflow)
4. [Fee / Payment lifecycle](#fee--payment-lifecycle)
5. [Approval workflow](#approval-workflow)
6. [Adding a new workflow](#adding-a-new-workflow)

---

## Design principles

- **Valid transitions are explicit** — every entity defines an allowlist of `(from, to)` pairs.
  Any attempt to move to an unlisted target state throws `ResponseStatusException(409 CONFLICT)`.
- **Terminal states** — once an entity reaches a terminal state (e.g. `FULFILLED`, `CLOSED`)
  no further transition is allowed unless explicitly re-opened by a privileged actor.
- **Permission gates** — some transitions additionally require a specific permission
  (e.g. `order:approve`).  The `@PreAuthorize` annotation on the controller enforces this;
  `WorkflowTransitionValidator` does not re-check permissions.
- **Audit trail** — every state change is recorded in the `audit_log` table via
  `AuditLogService.statusTransition(...)` before the entity is saved.

---

## Catalog Order workflow

### States

| Code | Meaning |
|------|---------|
| `DRAFT` | Created but not submitted for approval |
| `SUBMITTED` | Submitted by school admin; awaiting platform review |
| `APPROVED` | Approved by zone/platform admin; ready for fulfillment |
| `FULFILLED` | Goods dispatched / delivered |
| `REJECTED` | Rejected at any review stage |
| `CANCELLED` | Withdrawn by the requester before approval |

### Allowed transitions

```
DRAFT       → SUBMITTED   (actor: school admin,    permission: order:create)
SUBMITTED   → APPROVED    (actor: zone/platform,   permission: order:approve)
SUBMITTED   → REJECTED    (actor: zone/platform,   permission: order:approve)
SUBMITTED   → CANCELLED   (actor: school admin,    permission: order:cancel)
APPROVED    → FULFILLED   (actor: platform/ops,    permission: order:fulfill)
APPROVED    → CANCELLED   (actor: zone/platform,   permission: order:approve)
REJECTED    → SUBMITTED   (re-submission allowed,  permission: order:create)
```

### Terminal states

`FULFILLED`, `CANCELLED` — no further transitions permitted.

---

## Firefighting Request workflow

Firefighting requests track safety equipment requests from schools.

### States

| Code | Meaning |
|------|---------|
| `OPEN` | Request lodged; not yet acknowledged |
| `IN_REVIEW` | Under review by zone safety officer |
| `APPROVED` | Equipment approved for dispatch |
| `DISPATCHED` | Equipment physically dispatched |
| `CLOSED` | Request resolved and closed |
| `REJECTED` | Request declined |

### Allowed transitions

```
OPEN        → IN_REVIEW   (actor: zone_admin,    permission: firefighting:read)
IN_REVIEW   → APPROVED    (actor: zone_admin,    permission: firefighting:approve)
IN_REVIEW   → REJECTED    (actor: zone_admin,    permission: firefighting:approve)
APPROVED    → DISPATCHED  (actor: platform ops,  permission: firefighting:approve)
DISPATCHED  → CLOSED      (actor: school admin,  permission: firefighting:read)
REJECTED    → OPEN        (re-open by requester, permission: firefighting:read)
```

### Terminal states

`CLOSED` — no further transitions.

---

## Fee / Payment lifecycle

Fees are not a traditional workflow — they are a **running balance**, not a
state machine.  However, the following status labels are computed and stored on
`StudentEntity.feeStatus` for display purposes.

| Label | Condition |
|-------|-----------|
| `Pending` | No payments recorded yet (`paidAmount == 0`) |
| `Partial` | Some payments recorded (`0 < paidAmount < netPayable`) |
| `Paid` | Fully paid (`paidAmount >= netPayable`) |
| `Overdue` | Due date passed, outstanding balance > 0 (computed at read time) |

`feeStatus` is **not** a state machine — it is recalculated after each payment
or fee-assignment change.  There is no `WorkflowTransitionValidator` for fees.

---

## Approval workflow

The generic approval mechanism (backed by `approval_requests`) is used for
any entity that requires a two-step propose / approve flow.

### States

| Code | Meaning |
|------|---------|
| `PENDING` | Proposal submitted; awaiting approver action |
| `APPROVED` | Approved by the designated approver |
| `REJECTED` | Declined by the approver |
| `WITHDRAWN` | Requester cancelled before decision |

### Allowed transitions

```
PENDING   → APPROVED    (actor: approver role,  permission: approval:decide)
PENDING   → REJECTED    (actor: approver role,  permission: approval:decide)
PENDING   → WITHDRAWN   (actor: requester,      permission: approval:create)
REJECTED  → PENDING     (re-submit,             permission: approval:create)
```

### Terminal states

`APPROVED`, `WITHDRAWN` — no further transitions.

---

## Adding a new workflow

1. Define the `states` and `allowedTransitions` map in the relevant `*WorkflowTransitionValidator` class.
2. Add a `@PreAuthorize` expression on every transition endpoint using the appropriate `PermissionConstants.*`.
3. Call `auditLogService.statusTransition(entityType, entityId, oldStatus, newStatus, actorUserId)` before saving.
4. Add the new transitions to this document.
5. Add a test case in `WorkflowTransitionValidatorTest` covering at least:
   - A happy-path valid transition
   - An invalid-transition (expect `409 CONFLICT`)
   - A terminal-state guard (expect `409 CONFLICT`)
