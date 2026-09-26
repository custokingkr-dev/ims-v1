\set ON_ERROR_STOP on
\pset tuples_only on
\pset format unaligned

-- Aggregate-only receipt readiness evidence. Run against an explicitly selected
-- database with an existing read-authorized connection. No receipt numbers,
-- student identifiers, payment details, secrets, or idempotency keys are emitted.
-- Safe before and after fee/V10; to_jsonb permits absent pre-migration columns.
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '15s';
SET LOCAL lock_timeout = '2s';

WITH visibility AS (
    SELECT (r.rolsuper OR r.rolbypassrls OR (pg_has_role(current_user, c.relowner, 'USAGE') AND NOT c.relforcerowsecurity)
            OR NOT c.relrowsecurity) AS complete_scope
    FROM pg_class c JOIN pg_roles r ON r.rolname = current_user
    WHERE c.oid = 'fee.payment_records'::regclass
), receipts AS (
    SELECT receipt_number, count(*) AS records, count(DISTINCT school_id) AS schools,
           count(*) FILTER (WHERE COALESCE((to_jsonb(p)->>'legacy_receipt_collision')::boolean, false)) AS marked
    FROM fee.payment_records p
    WHERE receipt_number IS NOT NULL
    GROUP BY receipt_number
), duplicate_keys AS (
    SELECT count(*) AS records
    FROM fee.payment_records p
    WHERE to_jsonb(p)->>'idempotency_key' IS NOT NULL
    GROUP BY school_id, to_jsonb(p)->>'idempotency_key'
    HAVING count(*) > 1
), schema_state AS (
    SELECT
      (SELECT count(*) = 5 FROM information_schema.columns
       WHERE table_schema = 'fee' AND table_name = 'payment_records'
         AND column_name IN ('idempotency_key', 'request_fingerprint', 'paid_total_after', 'net_payable_at_payment', 'legacy_receipt_collision')) AS columns_present,
      EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('fee.uk_payment_school_idempotency') AND indisunique AND indisvalid) AS idempotency_index_valid,
      EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('fee.uk_payment_receipt_number') AND indisunique AND indisvalid) AS receipt_index_valid,
      to_regclass('fee.payment_receipt_seq') IS NOT NULL AS sequence_present
)
SELECT jsonb_build_object(
    'check', 'fee-payment-integrity',
    'observedAtUtc', current_timestamp,
    'readOnly', current_setting('transaction_read_only') = 'on',
    'completeDatabaseVisibility', (SELECT complete_scope FROM visibility),
    'interpretation', CASE WHEN (SELECT complete_scope FROM visibility)
        THEN 'Database-wide aggregate counts; existing receipt collisions are preserved by V10.'
        ELSE 'INCOMPLETE: RLS may hide other schools. Counts describe only visible rows and do not certify migration readiness.' END,
    'visiblePaymentCount', (SELECT count(*) FROM fee.payment_records),
    'visiblePaymentsMissingSchool', (SELECT count(*) FROM fee.payment_records WHERE school_id IS NULL),
    'visiblePaymentsWithoutReceipt', (SELECT count(*) FROM fee.payment_records WHERE receipt_number IS NULL),
    'duplicateReceiptGroups', (SELECT count(*) FROM receipts WHERE records > 1),
    'paymentsInDuplicateReceiptGroups', (SELECT COALESCE(sum(records), 0) FROM receipts WHERE records > 1),
    'duplicateGroupsAcrossSchools', (SELECT count(*) FROM receipts WHERE records > 1 AND schools > 1),
    'duplicateGroupsFullyMarkedLegacy', (SELECT count(*) FROM receipts WHERE records > 1 AND marked = records),
    'duplicateSchoolIdempotencyKeys', (SELECT count(*) FROM duplicate_keys),
    'v10Schema', (SELECT to_jsonb(schema_state) FROM schema_state),
    'appRtCanUseReceiptSequence', CASE
        WHEN to_regclass('fee.payment_receipt_seq') IS NOT NULL AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt')
        THEN has_sequence_privilege('app_rt', 'fee.payment_receipt_seq', 'USAGE') ELSE NULL END
);
ROLLBACK;
