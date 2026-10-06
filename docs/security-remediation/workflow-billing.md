# Workflow and billing remediation evidence

Owner: workflow/billing implementation agent. Date: 2026-10-06. Changes are in the shared security worktree and have not been deployed by this agent. This report describes the owned portions of the saved register; it does not close cross-service or live-environment checks.

| Finding | Owned implementation and status | Evidence / remaining verification |
| --- | --- | --- |
| SEC-01 | Implemented and locally verified. Authenticated `workflow:act` actor required, active step permission and role enforced together, business entity permission enforced, explicit tenant check, actor attribution comes from server context; authoritative action records include SERVER provenance and a bounded request correlation ID. SUPERADMIN retains the existing permission/role override but cannot approve/reject its own initiated workflow. | Real PostgreSQL role/permission matrix covers SUPERADMIN, PRINCIPAL, ADMIN, SCHOOL_ADMIN, OPERATIONS, TEACHER; missing permission, wrong role, cross-school, anonymous, self-decision and independent SUPERADMIN delegation. Denied requests preserve version and action rows. Live route verification remains VER-01. |
| SEC-02 | Implemented and locally verified. `FOR UPDATE` serializes mutations; version advances with each mutation. Approve/reject require the version the caller read. Only PENDING submits; only IN_PROGRESS approves/rejects; only PENDING/IN_PROGRESS cancels; only APPROVED completes. Submit/cancel belong to initiator or SUPERADMIN. Completion requires domain fulfillment permission. Creation uses scoped advisory lock and unique entity index. | PostgreSQL concurrent decisions produce one progression and one action; stale loser gets 409. Concurrent creation returns one ID, with independent entities in two schools. Illegal state and action-insert failure leave no state/action changes. |
| SEC-25 | Implemented and locally verified. Payment DTO validates positive invoice/amount, whole-number JSON, mode, date, lengths and mandatory idempotency key. SUPERADMIN plus authenticated user ID required. Branch and attribution come from locked invoice and user context; client attribution is not writable. Invoice-scoped key/fingerprint provides stable replay and 409 on changed replay. Overpayments and cancelled-invoice payments rejected. New-write database constraints added without rewriting historical records. | Actual PostgreSQL same-key and competing-key races, replay drift, invalid/oversized amounts, dates, role denials, SQL-looking text, insert failure, audit failure and balance reconciliation. Payment event emitted once transactionally with original actor attribution. Refund/reversal transactions are deliberately separate domain commands; negative payment is never a refund. |
| SEC-26 | Billing portion implemented and locally verified. BigDecimal percentage calculations, HALF_UP rounding, checked arithmetic, percentage range 0..100 and two decimals maximum. Existing integer records remain whole rupees; currency/unit metadata makes this explicit. Configurable legacy GST is captured per newly issued invoice and read from that record on updates. | Boundary rounding fixture 101 at 12.50% gives 13; invalid/fractional/overflow/bad percentage rejects without silent truncation. Existing historical values are not recalculated. School-fee discount/share portion belongs to school-core owner. |
| SEC-34 | Sensitive billing compatibility invoice, customer and payment mutations now use validated DTOs with explicit writable properties. Legacy create/update compatibility DTOs preserve absent-field behavior. Workflow action identities are ignored in favor of authenticated context. | Bean-validation and controller delegation tests plus real repository tests. Broad architecture/convergence exclusions outside these services remain separately tracked. |
| SEC-18 | Operations quotation portion implemented and locally verified. PDF policy rejects encryption, script/action/form/attachment names recursively, including nested dictionaries/arrays and downloads of existing PDF objects. Traversal caps depth 100 and visited objects 100,000. | Valid PDF/image and harmless nested Launch/GoToR/SubmitForm/ImportData/RichMedia/Filespec fixtures tested. This is structural rejection, not a malware scanner or CDR certification. PdfReader parsing precedes traversal; parser isolation/adversarial resource tests remain part of VER-07. Quotation storage is GCS-only: no operations local file path/symlink read path exists. |
| SEC-30 | Operations deletion storage errors now return a generic message; existing read/write messages already avoid bucket/object exception details. | Privacy inventory/retention/backups/deletion drills remain operational verification. No destructive retention duration was invented or automatically applied. |
| SEC-31 | Billing/operations relay portion implemented and locally verified. Pub/Sub await defaults to 5 seconds, hard capped at 10, cancellation on timeout/interrupt. Batch clamped 1..10; production Pub/Sub dispatch budget 10 seconds with each publish capped to the remaining budget (database selection/updates add separate latency). Existing publish-before-mark, retry/dead-letter and transaction atomicity preserved. | Controlled unresolved futures cancel within test deadline; completed publish succeeds; existing actual PostgreSQL outbox tests pass. Cloud Run CPU/scale-to-zero and mail delivery remain environment verification. A bounded DB transaction still covers publishing; deployment pool/revision budgets remain VER-10/SEC-32. |
| VER-08 | Owned workflow/payment PostgreSQL scenarios locally verified. | Uses real PostgreSQL 16 containers, real Flyway migrations and transaction rollback, no H2 approximation or skips. Payment authoritative audit insert failure rolls back payment and invoice; workflow action insert failure rolls back progression. |
| VER-01 / VER-05 | Owned domain role/object/property and SQL-data tests implemented and locally verified. | This does not stand in for every gateway/canonical/diagnostic/live route or every parser/template sink across the system. |

## Contracts and policy

Workflow GET instance already returns top-level `version`. POST canonical `/api/v1/workflows/instances/{id}/approve` and `/reject`, and aliases `/api/v1/workflows/{id}/approve` and `/reject`, take `expectedVersion` alongside optional notes. Missing version returns 400; stale version returns 409. Reload before a fresh decision; do not automatically repeat a decision against a new version. The notes bound is 1,000 characters, matching the database column.

No initiator may approve/reject its own workflow, including SUPERADMIN. An independent authorized step approver or another SUPERADMIN performs that decision. A sole privileged actor cannot self-approve by supplying forged actor fields or a different client email. Submission/cancellation are still available to the initiator; another SUPERADMIN can perform those administrative actions. Required roles are read as configured, with case-insensitive exact comparison; no new BURSAR/PRINCIPAL role assumptions were inserted into the seeded definitions.

Payment POST `/api/v1/billing-payments` takes `{invoiceId, amount, paymentDate?, paymentMode, referenceNo?, notes?, idempotencyKey}`. Key pattern is `[A-Za-z0-9._:-]{8,128}`. Modes are CASH, UPI, BANK_TRANSFER, CHEQUE, CARD, OTHER. Reuse one key for a retry of the same user operation; issue a fresh key for a new operation. Fingerprint uses length-prefixed canonical writable fields, including whether a date was explicitly supplied. An omitted date is resolved once on first creation and does not change replay semantics the next day. Client branch/receivedBy fields have no authority.

Existing ledger integers are whole rupees, not paise. Changing units would require a separate data migration and receipt/report conversion; this change never reinterprets stored records. Item tax is calculated on each pre-discount line and rounded HALF_UP, preserving the previous tax-before-discount policy. Discount is rounded once on subtotal. `BILLING_LEGACY_GST_PERCENT` defaults 12.00 and affects new legacy invoices only; stored `tax_percent` is authoritative for later edits. Historical rates were 12%; defaults annotate that policy without rewriting historical tax totals.

## Migrations and preflight

Forward migrations: workflow V6/V7, firefighting V14, billing V8/V9/V10. V7/V14/V10 force existing tenant RLS policies on non-superuser table owners while preserving the contextless outbox exclusions; privileged owner backfills require deliberate transaction-local app.bypass_rls=on. They do not amend prior migration files or delete records. Before release, run these aggregate-only queries as an authorized read-only migration operator:

```sql
SELECT school_id, entity_type, count(*) AS duplicated_groups
FROM (SELECT school_id, entity_type, entity_id FROM workflow.workflow_instances
      GROUP BY school_id, entity_type, entity_id HAVING count(*) > 1) d
GROUP BY school_id, entity_type;
SELECT count(*) AS invalid_payment_amounts FROM billing.billing_payments WHERE amount <= 0;
SELECT count(*) AS invalid_invoice_balances FROM billing.billing_invoices
 WHERE grand_total < 0 OR paid_amount < 0 OR paid_amount > grand_total
    OR balance_amount <> grand_total - paid_amount;
SELECT count(*) AS invalid_legacy_money FROM billing.superadmin_invoices
 WHERE qty <= 0 OR rate < 0 OR amount < 0 OR gst_amount < 0 OR total <> amount + gst_amount;
SELECT count(*) AS invalid_items FROM billing.billing_invoice_items
 WHERE quantity <= 0 OR unit_price < 0 OR tax_rate < 0 OR tax_rate > 100 OR line_total < 0;
```

Workflow duplicate groups intentionally block the unique-index migration and need explicit historical reconciliation. NOT VALID monetary/state checks enforce new writes and updates while retaining historical rows for review; invalid historical records must be reconciled before constraints are marked VALID. An update of an anomalous historical row can fail; never remove a constraint to hide that failure. `received_by_user_id`, key and fingerprint remain nullable for historical payments with no claim that old records were replay-protected.

Code rollback must keep forward schema migrations and legitimate payment/action audit records. Reverting to the old payment/workflow write behavior reopens the findings; rollback should disable affected mutations or restore a corrected forward build. New mandatory version/key requirements must be rolled out with callers. PDF structural restrictions should remain enforced on downloads; a code rollback cannot revoke copies already downloaded. No live database, storage metadata, Cloud Run policy or deployment was modified by this agent.

## Validation commands

From repository root, PowerShell:

```powershell
.\mvnw.cmd -f services/operations-service/pom.xml test '-Dstyle.color=never'
.\mvnw.cmd -f services/billing-service/pom.xml test '-Dstyle.color=never'
.\mvnw.cmd -f services/operations-service/pom.xml test '-Dtest=WorkflowSecurityIntegrationTest,WorkflowValidationTest,QuotationDocumentStorageTest,PublishDeadlineTest' '-Dstyle.color=never'
.\mvnw.cmd -f services/billing-service/pom.xml test '-Dtest=BillingPaymentSecurityIntegrationTest,BillingPaymentValidationTest,BillingValidationTest,BillingPublicCompatibilityControllerTest,PublishDeadlineTest' '-Dstyle.color=never'
```

Full service checkpoints: operations 168 passed, billing 74 passed, zero failures/errors/skips. Subsequent expanded workflow matrix/concurrent creation: 30 targeted passed including 13 real PostgreSQL workflow cases. Subsequent payment audit rollback/original attribution changes: 28 targeted passed, including 9 real PostgreSQL payment cases. Final full checkpoints after shared guard fixture correction: operations 173 passed, billing 76 passed, zero failures/errors/skips. After FORCE RLS forward migrations: operations 31 targeted and billing 20 targeted passed, zero skips. Additional bounded local pool load: 1 passed; see capacity-and-delivery.md for measured results. The shared RuntimeDbRoleGuard/TenantScope/connection cleanup changes are owned by the identity/platform agent and included in full-suite checkpoints.

Latest final full-source suites: operations188 and billing90 passed, zero failures/errors/skips, both native Maven exit0. These totals include migration-only/runtime policy additions. Logs: operations-final-full.log and billing-final-full.log. The first billing invocation logged BUILD SUCCESS but PowerShell5 surfaced a delayed-fork-exit stderr warning as a shell error; re-execution with explicit stderr/exit handling succeeded natively and settled the gate.
