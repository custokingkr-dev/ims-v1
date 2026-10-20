-- Preserve historical records; constraints enforce new writes without rewriting old money.
ALTER TABLE billing_payments ADD COLUMN idempotency_key VARCHAR(128);
ALTER TABLE billing_payments ADD COLUMN request_fingerprint VARCHAR(64);
ALTER TABLE billing_payments ADD COLUMN received_by_user_id BIGINT;
CREATE UNIQUE INDEX uk_billing_payment_invoice_replay ON billing_payments(invoice_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
ALTER TABLE billing_payments ADD CONSTRAINT ck_billing_payment_positive CHECK (amount > 0) NOT VALID;
ALTER TABLE billing_payments ADD CONSTRAINT ck_billing_payment_replay_pair CHECK
    ((idempotency_key IS NULL AND request_fingerprint IS NULL) OR
     (idempotency_key IS NOT NULL AND request_fingerprint IS NOT NULL AND received_by_user_id > 0)) NOT VALID;
ALTER TABLE billing_invoices ADD CONSTRAINT ck_billing_invoice_balances CHECK
    (grand_total >= 0 AND paid_amount >= 0 AND paid_amount <= grand_total
     AND balance_amount = grand_total - paid_amount) NOT VALID;
