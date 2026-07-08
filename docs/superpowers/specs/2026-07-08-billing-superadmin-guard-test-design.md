# Billing Superadmin-Gate Guard Test — Design

**Date:** 2026-07-08
**Status:** Approved for planning
**Services touched:** billing-service (test only).

Billing RLS is **deferred** (research recommendation): billing-service has no `TenantAwareDataSource` at all, every controller method is already `requireSuperAdmin()`-gated, writes are request-scoped (no projector), and the tenant columns are largely a platform-global ledger — so full RLS + datasource wiring would protect nothing live. Instead, add a cheap regression guard for the exact risk (a future endpoint forgetting the gate).

## The safeguard
An architecture/reflection test asserting that **every** request-mapped method in `BillingInvoiceController` and `BillingPublicCompatibilityController` enforces superadmin. Concretely: for each `@GetMapping/@PostMapping/@PutMapping/@DeleteMapping/@RequestMapping` handler method in those controllers, assert the method (or its call path) references `TenantScope.requireSuperAdmin()`.

Implementation options (pick the simplest that's reliable in this repo):
- Reflection + source/bytecode scan: enumerate the controllers' handler methods; assert each contains a call to `TenantScope.requireSuperAdmin()` (e.g. via reading the compiled method or a source-file regex over the two controller files asserting each mapping annotation is followed by a `requireSuperAdmin()` before the method returns).
- Simplest reliable: a source-file test that parses the two controller `.java` files and asserts each `@…Mapping` handler body contains `requireSuperAdmin()`. If a handler is added without the gate, the test fails.

The test lives in the existing `billing-service` test module. No production code, no schema, no datasource change.

## Deferral trigger (documented)
Add real per-school RLS (with the required `TenantAwareDataSource` + GUC wiring) as part of the FIRST PR that introduces a genuinely school-facing billing read/write endpoint (e.g. finishing the `schoolInvoices()` self-service scoping noted in `BillingPublicCompatibilityController` ~:116-119) — that endpoint needs `TenantContext`/GUC wiring anyway, so the marginal cost is far lower than building it speculatively now.

## Testing
The guard test itself is the deliverable. Verify it PASSES against the current (fully-gated) controllers, and (temporarily, to prove it's non-vacuous) that removing a `requireSuperAdmin()` from a handler would make it FAIL — assert the test genuinely inspects each handler, not just that the string appears once in the file.

## Files
A new test under `services/billing-service/src/test/java/.../` (e.g. `BillingSuperadminGateArchTest.java`). No production changes.
