CREATE TABLE IF NOT EXISTS superadmin_invoices (
    id          VARCHAR(255) NOT NULL,
    order_ref   VARCHAR(255),
    school      VARCHAR(255),
    school_id   BIGINT,
    description VARCHAR(255),
    qty         INTEGER      NOT NULL,
    rate        BIGINT       NOT NULL,
    amount      BIGINT       NOT NULL,
    gst_amount  BIGINT       NOT NULL,
    total       BIGINT       NOT NULL,
    status      VARCHAR(255),
    issued_at   VARCHAR(255),
    due_at      VARCHAR(255),
    notes       VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS superadmin_order_seq (
    id          VARCHAR(255) NOT NULL,
    order_seq   BIGINT       NOT NULL,
    invoice_seq BIGINT       NOT NULL,
    PRIMARY KEY (id)
);

DO $$
BEGIN
    IF to_regclass('public.superadmin_invoices') IS NOT NULL THEN
    INSERT INTO superadmin_invoices
        (id, order_ref, school, school_id, description, qty, rate, amount,
         gst_amount, total, status, issued_at, due_at, notes, created_at)
    SELECT
        id, order_ref, school, school_id, description, qty, rate, amount,
        gst_amount, total, status, issued_at, due_at, notes, created_at
    FROM public.superadmin_invoices
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.superadmin_order_seq') IS NOT NULL THEN
    INSERT INTO superadmin_order_seq (id, order_seq, invoice_seq)
    SELECT id, order_seq, invoice_seq
    FROM public.superadmin_order_seq
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;
