-- Reporting fact tables projected from school-core (fee) outbox events
-- (fee-assignment.upserted.v1, payment.recorded.v1), per Reporting Decoupling SP2.
-- Mirrors reporting.billing_invoice_read (V9) / reporting.dim_* (V11) posture exactly:
-- these are contextless event-consumer projections, so they intentionally stay WITHOUT
-- RLS, matching the sibling posture documented in V8__enable_rls.sql.
CREATE TABLE IF NOT EXISTS fact_fee_assignment (
    id               VARCHAR(255) PRIMARY KEY,
    student_id       BIGINT,
    school_id        BIGINT,
    academic_year_id VARCHAR(255),
    net_payable      BIGINT,
    paid_amount      BIGINT,
    due_amount       BIGINT,
    status           VARCHAR(32),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS fact_payment (
    id            VARCHAR(255) PRIMARY KEY,
    assignment_id VARCHAR(255),
    school_id     BIGINT,
    student_id    BIGINT,
    amount        BIGINT,
    paid_at       TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_fact_fee_assignment_school_id ON fact_fee_assignment (school_id);
CREATE INDEX IF NOT EXISTS idx_fact_payment_school_id ON fact_payment (school_id);
CREATE INDEX IF NOT EXISTS idx_fact_payment_assignment_id ON fact_payment (assignment_id);
