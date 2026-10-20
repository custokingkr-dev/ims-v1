-- Existing integer records are whole rupees, not silently reinterpreted as paise.
ALTER TABLE superadmin_invoices ADD COLUMN tax_percent NUMERIC(8,2) NOT NULL DEFAULT 12.00;
ALTER TABLE superadmin_invoices ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'INR';
ALTER TABLE superadmin_invoices ADD COLUMN monetary_unit VARCHAR(20) NOT NULL DEFAULT 'WHOLE_RUPEE';
ALTER TABLE billing_invoices ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'INR';
ALTER TABLE billing_invoices ADD COLUMN monetary_unit VARCHAR(20) NOT NULL DEFAULT 'WHOLE_RUPEE';
ALTER TABLE superadmin_invoices ADD CONSTRAINT ck_legacy_invoice_money CHECK
 (qty > 0 AND rate >= 0 AND amount >= 0 AND gst_amount >= 0 AND total = amount + gst_amount AND tax_percent BETWEEN 0 AND 100) NOT VALID;
ALTER TABLE billing_invoice_items ADD CONSTRAINT ck_billing_item_money CHECK
 (quantity > 0 AND unit_price >= 0 AND tax_rate BETWEEN 0 AND 100 AND line_total >= 0) NOT VALID;
