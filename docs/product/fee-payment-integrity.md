# Fee payment integrity contract

Implemented for the school-core collection routes:

- `POST /api/v1/fees/payments`
- `POST /api/v1/payments`
- `POST /api/v1/workspace/fees/record-payment`

Every collection requires a nonblank `idempotencyKey` in its JSON body (maximum 128 characters). Clients generate one key per logical collection **before** the first submission and keep the entire request stable across retries. Missing keys return 400; a previously used tenant/key with different collection details returns 409. Do not replace a key after a timeout or an authorization/validation error on a retry. Repeating the original request returns the original payment, receipt and balance snapshot without another domain write or outbox event.

The key is scoped to the student's school and the fee-payment operation. An assignment row lock, conditional balance increment, and tenant/key uniqueness constraint protect concurrent collections. Receipt numbers use a database sequence in the `RCPT-V2-` namespace and a unique index. Gaps in sequence numbers after rollback are expected; they do not represent missing payments.

Clients should send both `assignmentId` and `academicYearId` from the selected ledger row. The repository checks school, student and year consistency. If neither selector is supplied, only the current-year assignment is eligible. Historical debt must be settled using its original assignment or year; the collection never rewrites an assignment's academic year or the current enrollment's fee status. `actorId` and `recordedBy` in request bodies cannot set audit attribution: the trusted `TenantContext` user is used.

Migration `fee/V10__payment_integrity.sql` preserves all previously issued receipt numbers. Existing duplicate numbers are marked `legacy_receipt_collision`, not silently renumbered or deleted. Historical lookup by an ambiguous number returns 409 with guidance to open the receipt by payment ID. Payment-ID receipts remain available. The uniqueness index covers new and noncolliding existing receipts.

The migration's duplicate scan and receipt-sequence initialization explicitly enable the existing maintenance RLS policy only for those transaction-local reads/writes, then restore the connection's original setting. This is necessary when the migration owner is subject to forced tenant isolation: otherwise hidden schools could be omitted. A nonsuperuser/no-bypass migration-owner fixture covers cross-school historical collisions, a hidden reserved receipt number, and scope restoration on the reused connection. Runtime tenant policies are unchanged.

Both fee payment UIs save unresolved requests under a user-and-school-scoped browser recovery key before submitting. They persist the exact financial request, including its timestamp, idempotency key, assignment/year, amount, mode and any entered notes; free-text notes are necessary for exact payload replay. Student display names are not persisted. Recovery storage is removed after authoritative success. No automatic expiry discards an unresolved key. Damaged or inaccessible storage blocks a new collection with ledger/receipt review guidance. Manual clearing requires the operator to acknowledge that the ledger was checked; clearing recovery data does not cancel a recorded payment. A failure to remove recovery data after success is shown as a saved payment with cleanup guidance, never as a failed collection.

Deploy the migration before the new server, and update API clients together with the server contract. Older clients that omit the key fail closed with 400; there is no unprotected compatibility path. The supplied frontend uses JSON-body keys rather than an `Idempotency-Key` header.

Fee report installment reads are batched by assignment and distinct plan. A regression fixture with 21 students performs four SELECTs rather than per-student schedule reads. Changed late-fee accruals still require individual updates and their transactional outbox events; this is deliberate financial event persistence, not a remaining schedule-read loop.
