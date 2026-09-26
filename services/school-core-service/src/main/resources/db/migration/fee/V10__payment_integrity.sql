-- Keep issued historical receipts unchanged, including known collisions. Ambiguous
-- historical numbers must be looked up by payment id, never arbitrarily selected.
ALTER TABLE fee.payment_records
    ADD COLUMN idempotency_key VARCHAR(128),
    ADD COLUMN request_fingerprint VARCHAR(64),
    ADD COLUMN paid_total_after BIGINT,
    ADD COLUMN net_payable_at_payment BIGINT,
    ADD COLUMN legacy_receipt_collision BOOLEAN NOT NULL DEFAULT FALSE;

-- The migration owner may also be subject to FORCE ROW LEVEL SECURITY. Both
-- maintenance reads must see every school; restore the caller's scope afterward.
DO $$
DECLARE previous_bypass TEXT := current_setting('app.bypass_rls', TRUE);
BEGIN
    PERFORM set_config('app.bypass_rls', 'on', TRUE);
    UPDATE fee.payment_records p SET legacy_receipt_collision = TRUE
    WHERE p.receipt_number IN (
        SELECT receipt_number FROM fee.payment_records
        WHERE receipt_number IS NOT NULL GROUP BY receipt_number HAVING COUNT(*) > 1
    );
    PERFORM set_config('app.bypass_rls', COALESCE(previous_bypass, ''), TRUE);
END $$;

CREATE UNIQUE INDEX uk_payment_school_idempotency
    ON fee.payment_records (school_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX uk_payment_receipt_number
    ON fee.payment_records (receipt_number)
    WHERE receipt_number IS NOT NULL AND NOT legacy_receipt_collision;

CREATE SEQUENCE fee.payment_receipt_seq;
-- A reserved new namespace avoids collisions with millisecond-based old numbers.
DO $$
DECLARE previous_bypass TEXT := current_setting('app.bypass_rls', TRUE);
BEGIN
    PERFORM set_config('app.bypass_rls', 'on', TRUE);
    PERFORM setval('fee.payment_receipt_seq', GREATEST(1, COALESCE((
        SELECT MAX(substring(receipt_number FROM '^RCPT-V2-([0-9]+)$')::bigint) + 1
        FROM fee.payment_records WHERE receipt_number ~ '^RCPT-V2-[0-9]{1,18}$'
    ), 1)), FALSE);
    PERFORM set_config('app.bypass_rls', COALESCE(previous_bypass, ''), TRUE);
END $$;

-- Runtime can allocate receipts, but cannot rewind the sequence with setval.
-- Existing payment_records DML is retained for the guarded student deletion path.
REVOKE ALL ON SEQUENCE fee.payment_receipt_seq FROM PUBLIC;
DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='app_rt') THEN
    REVOKE ALL ON SEQUENCE fee.payment_receipt_seq FROM app_rt;
    GRANT USAGE, SELECT ON SEQUENCE fee.payment_receipt_seq TO app_rt;
END IF; END $$;
