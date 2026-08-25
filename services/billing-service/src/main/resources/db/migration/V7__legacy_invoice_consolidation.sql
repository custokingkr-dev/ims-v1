-- Non-destructive bridge from the legacy superadmin invoice model to the canonical
-- customer/invoice/item/payment model. The legacy table remains authoritative for
-- compatibility reads until route telemetry proves that its clients have migrated.

ALTER TABLE billing.billing_customers
    ADD COLUMN IF NOT EXISTS legacy_school_id BIGINT;

CREATE UNIQUE INDEX IF NOT EXISTS uq_billing_customers_legacy_school
    ON billing.billing_customers (legacy_school_id);

ALTER TABLE billing.billing_invoices
    ADD COLUMN IF NOT EXISTS legacy_source VARCHAR(64),
    ADD COLUMN IF NOT EXISTS legacy_source_id VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS uq_billing_invoices_legacy_source
    ON billing.billing_invoices (legacy_source, legacy_source_id);

ALTER TABLE billing.billing_invoice_items
    ADD COLUMN IF NOT EXISTS legacy_source_id VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS uq_billing_invoice_items_legacy_source
    ON billing.billing_invoice_items (invoice_id, legacy_source_id);

ALTER TABLE billing.billing_payments
    ADD COLUMN IF NOT EXISTS legacy_source_id VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS uq_billing_payments_legacy_source
    ON billing.billing_payments (legacy_source_id);

CREATE OR REPLACE FUNCTION billing.sync_legacy_invoice_to_canonical()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_customer_id BIGINT;
    v_invoice_id BIGINT;
    v_invoice_date DATE;
    v_due_date DATE;
    v_status VARCHAR(255);
    v_payment_status VARCHAR(255);
    v_approval_status VARCHAR(255);
    v_invoice_no VARCHAR(255);
    v_customer_name VARCHAR(255);
BEGIN
    -- A tenant assignment is mandatory in the canonical model. Null-school rows are
    -- retained in the legacy table and exposed by the reconciliation view for an
    -- operator to map; silently assigning them to a guessed school would be unsafe.
    IF NEW.school_id IS NULL THEN
        RETURN NEW;
    END IF;

    v_customer_name := COALESCE(NULLIF(btrim(NEW.school), ''), 'Legacy school ' || NEW.school_id);

    INSERT INTO billing.billing_customers
        (code, name, branch_id, branch_name, active, legacy_school_id)
    VALUES
        ('LEGACY-SCHOOL-' || NEW.school_id, v_customer_name,
         NEW.school_id, v_customer_name, TRUE, NEW.school_id)
    ON CONFLICT (legacy_school_id) DO UPDATE
        SET name = EXCLUDED.name,
            branch_id = EXCLUDED.branch_id,
            branch_name = EXCLUDED.branch_name,
            active = TRUE
    RETURNING id INTO v_customer_id;

    v_invoice_date := CASE
        WHEN NEW.issued_at IS NOT NULL AND pg_input_is_valid(NEW.issued_at, 'date')
            THEN NEW.issued_at::date
        ELSE NEW.created_at::date
    END;
    v_due_date := CASE
        WHEN NEW.due_at IS NOT NULL AND pg_input_is_valid(NEW.due_at, 'date')
            THEN NEW.due_at::date
        ELSE v_invoice_date + 14
    END;

    v_payment_status := CASE
        WHEN upper(COALESCE(NEW.status, '')) IN ('PAID', 'SETTLED') THEN 'PAID'
        WHEN upper(COALESCE(NEW.status, '')) IN ('PARTIALLY PAID', 'PARTIAL') THEN 'PARTIAL'
        ELSE 'UNPAID'
    END;
    v_status := CASE
        WHEN upper(COALESCE(NEW.status, '')) IN ('CANCELLED', 'CANCELED', 'VOID') THEN 'CANCELLED'
        WHEN v_payment_status = 'PAID' THEN 'PAID'
        ELSE 'ISSUED'
    END;
    v_approval_status := CASE
        WHEN upper(COALESCE(NEW.status, '')) = 'DRAFT' THEN 'DRAFT'
        WHEN upper(COALESCE(NEW.status, '')) IN ('PENDING', 'PENDING APPROVAL') THEN 'PENDING'
        ELSE 'APPROVED'
    END;
    -- Keep the exact original identifier in legacy_source_id. The canonical display
    -- number is namespaced and hash-suffixed so it cannot collide with new invoices.
    v_invoice_no := left('LEGACY-' || regexp_replace(NEW.id, '[^A-Za-z0-9-]', '-', 'g'), 238)
                    || '-' || substr(md5(NEW.id), 1, 8);

    INSERT INTO billing.billing_invoices
        (invoice_no, customer_id, branch_id, branch_name, invoice_date, due_date,
         subtotal, discount_percent, discount_amount, tax_amount, grand_total,
         paid_amount, balance_amount, status, payment_status, approval_status,
         notes, created_at, legacy_source, legacy_source_id)
    VALUES
        (v_invoice_no, v_customer_id, NEW.school_id, v_customer_name,
         v_invoice_date, v_due_date, NEW.amount, 0, 0, NEW.gst_amount, NEW.total,
         CASE WHEN v_payment_status = 'PAID' THEN NEW.total ELSE 0 END,
         CASE WHEN v_payment_status = 'PAID' THEN 0 ELSE NEW.total END,
         v_status, v_payment_status, v_approval_status, NEW.notes, NEW.created_at,
         'superadmin_invoices', NEW.id)
    ON CONFLICT (legacy_source, legacy_source_id) DO UPDATE
        SET customer_id = EXCLUDED.customer_id,
            branch_id = EXCLUDED.branch_id,
            branch_name = EXCLUDED.branch_name,
            invoice_date = EXCLUDED.invoice_date,
            due_date = EXCLUDED.due_date,
            subtotal = EXCLUDED.subtotal,
            tax_amount = EXCLUDED.tax_amount,
            grand_total = EXCLUDED.grand_total,
            paid_amount = EXCLUDED.paid_amount,
            balance_amount = EXCLUDED.balance_amount,
            status = EXCLUDED.status,
            payment_status = EXCLUDED.payment_status,
            approval_status = EXCLUDED.approval_status,
            notes = EXCLUDED.notes
    RETURNING id INTO v_invoice_id;

    INSERT INTO billing.billing_invoice_items
        (invoice_id, description, quantity, unit_price, tax_rate, line_total, legacy_source_id)
    VALUES
        (v_invoice_id, COALESCE(NULLIF(btrim(NEW.description), ''), 'Legacy invoice item'),
         NEW.qty, NEW.rate,
         CASE WHEN NEW.amount = 0 THEN 0
              ELSE round((NEW.gst_amount::numeric * 100) / NEW.amount, 2) END,
         NEW.total, NEW.id)
    ON CONFLICT (invoice_id, legacy_source_id) DO UPDATE
        SET description = EXCLUDED.description,
            quantity = EXCLUDED.quantity,
            unit_price = EXCLUDED.unit_price,
            tax_rate = EXCLUDED.tax_rate,
            line_total = EXCLUDED.line_total;

    IF v_payment_status = 'PAID' THEN
        INSERT INTO billing.billing_payments
            (invoice_id, branch_id, branch_name, payment_date, amount, payment_mode,
             reference_no, notes, received_by, created_at, legacy_source_id)
        VALUES
            (v_invoice_id, NEW.school_id, v_customer_name, v_invoice_date,
             NEW.total, 'LEGACY', NEW.order_ref,
             'Migrated from legacy invoice status', 'Legacy migration',
             NEW.created_at, NEW.id)
        ON CONFLICT (legacy_source_id) DO UPDATE
            SET invoice_id = EXCLUDED.invoice_id,
                branch_id = EXCLUDED.branch_id,
                branch_name = EXCLUDED.branch_name,
                payment_date = EXCLUDED.payment_date,
                amount = EXCLUDED.amount,
                reference_no = EXCLUDED.reference_no,
                notes = EXCLUDED.notes;
    ELSE
        -- Remove only the bridge-owned mirror when a legacy invoice is moved back
        -- out of a paid state. User-entered canonical payments have no legacy ID.
        DELETE FROM billing.billing_payments
        WHERE legacy_source_id = NEW.id
          AND payment_mode = 'LEGACY';
    END IF;

    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_sync_legacy_invoice_to_canonical
    ON billing.superadmin_invoices;
CREATE TRIGGER trg_sync_legacy_invoice_to_canonical
AFTER INSERT OR UPDATE OF school, school_id, description, qty, rate, amount,
    gst_amount, total, status, issued_at, due_at, notes
ON billing.superadmin_invoices
FOR EACH ROW
EXECUTE FUNCTION billing.sync_legacy_invoice_to_canonical();

-- Fire the bridge for pre-existing, deterministically mappable rows. This is
-- intentionally an UPDATE rather than a duplicate SQL backfill so the exact same
-- transformation is used for historical and future compatibility writes.
UPDATE billing.superadmin_invoices
SET school_id = school_id
WHERE school_id IS NOT NULL;

CREATE OR REPLACE VIEW billing.legacy_invoice_migration_issues
WITH (security_invoker = true)
AS
SELECT
    legacy.id AS legacy_invoice_id,
    legacy.school_id,
    CASE
        WHEN legacy.school_id IS NULL THEN 'SCHOOL_MAPPING_REQUIRED'
        WHEN canonical.id IS NULL THEN 'CANONICAL_INVOICE_MISSING'
        WHEN canonical.subtotal <> legacy.amount
          OR canonical.tax_amount <> legacy.gst_amount
          OR canonical.grand_total <> legacy.total THEN 'AMOUNT_MISMATCH'
        WHEN canonical.payment_status = 'PAID'
          AND NOT EXISTS (
              SELECT 1 FROM billing.billing_payments payment
              WHERE payment.invoice_id = canonical.id
                AND payment.legacy_source_id = legacy.id
          ) THEN 'PAYMENT_MIRROR_MISSING'
        WHEN canonical.payment_status <> 'PAID'
          AND EXISTS (
              SELECT 1 FROM billing.billing_payments payment
              WHERE payment.invoice_id = canonical.id
                AND payment.legacy_source_id = legacy.id
                AND payment.payment_mode = 'LEGACY'
          ) THEN 'PAYMENT_MIRROR_UNEXPECTED'
        ELSE NULL
    END AS issue
FROM billing.superadmin_invoices legacy
LEFT JOIN billing.billing_invoices canonical
  ON canonical.legacy_source = 'superadmin_invoices'
 AND canonical.legacy_source_id = legacy.id
WHERE legacy.school_id IS NULL
   OR canonical.id IS NULL
   OR canonical.subtotal <> legacy.amount
   OR canonical.tax_amount <> legacy.gst_amount
   OR canonical.grand_total <> legacy.total
   OR (canonical.payment_status = 'PAID' AND NOT EXISTS (
       SELECT 1 FROM billing.billing_payments payment
       WHERE payment.invoice_id = canonical.id
         AND payment.legacy_source_id = legacy.id
   ))
   OR (canonical.payment_status <> 'PAID' AND EXISTS (
       SELECT 1 FROM billing.billing_payments payment
       WHERE payment.invoice_id = canonical.id
         AND payment.legacy_source_id = legacy.id
         AND payment.payment_mode = 'LEGACY'
   ));

CREATE OR REPLACE VIEW billing.legacy_invoice_migration_summary
WITH (security_invoker = true)
AS
SELECT
    count(*) AS legacy_rows,
    count(*) FILTER (WHERE legacy.school_id IS NOT NULL) AS eligible_rows,
    count(canonical.id) AS migrated_rows,
    count(*) FILTER (WHERE legacy.school_id IS NULL) AS mapping_required_rows,
    count(*) FILTER (WHERE issue.issue IS NOT NULL) AS issue_rows,
    COALESCE(sum(legacy.total), 0) AS legacy_total,
    COALESCE(sum(canonical.grand_total), 0) AS canonical_total
FROM billing.superadmin_invoices legacy
LEFT JOIN billing.billing_invoices canonical
  ON canonical.legacy_source = 'superadmin_invoices'
 AND canonical.legacy_source_id = legacy.id
LEFT JOIN billing.legacy_invoice_migration_issues issue
  ON issue.legacy_invoice_id = legacy.id;
