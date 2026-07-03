CREATE TABLE IF NOT EXISTS fee_bands (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255),
    class_from INTEGER NOT NULL,
    class_to INTEGER NOT NULL,
    discount DOUBLE PRECISION NOT NULL,
    active_schedules_csv TEXT,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    academic_year_id VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS fee_items (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255),
    frequency VARCHAR(255),
    amount BIGINT NOT NULL,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    band_id VARCHAR(255) NOT NULL REFERENCES fee_bands(id)
);

CREATE TABLE IF NOT EXISTS fee_assignments (
    id VARCHAR(255) PRIMARY KEY,
    schedule VARCHAR(255),
    band_discount DOUBLE PRECISION NOT NULL,
    manual_discount DOUBLE PRECISION NOT NULL,
    surcharge DOUBLE PRECISION NOT NULL,
    net_payable BIGINT NOT NULL,
    paid_amount BIGINT NOT NULL,
    assigned_by BIGINT,
    assigned_at TIMESTAMPTZ,
    updated_by BIGINT,
    updated_at TIMESTAMPTZ,
    student_id BIGINT NOT NULL,
    band_id VARCHAR(255) NOT NULL REFERENCES fee_bands(id),
    academic_year_id VARCHAR(255) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_fee_assignment_student_year UNIQUE (student_id, academic_year_id),
    CONSTRAINT chk_fee_net_payable_non_negative CHECK (net_payable >= 0)
);

CREATE TABLE IF NOT EXISTS payment_records (
    id VARCHAR(255) PRIMARY KEY,
    amount BIGINT NOT NULL,
    mode VARCHAR(255),
    notes TEXT,
    paid_at TIMESTAMPTZ,
    recorded_by BIGINT,
    receipt_number VARCHAR(255),
    created_at TIMESTAMPTZ,
    student_id BIGINT NOT NULL,
    assignment_id VARCHAR(255) REFERENCES fee_assignments(id),
    version BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_fee_bands_year ON fee_bands(academic_year_id);
CREATE INDEX IF NOT EXISTS idx_fee_items_band ON fee_items(band_id);
CREATE INDEX IF NOT EXISTS idx_fee_assignments_student ON fee_assignments(student_id);
CREATE INDEX IF NOT EXISTS idx_fee_assignments_band_year ON fee_assignments(band_id, academic_year_id);
CREATE INDEX IF NOT EXISTS idx_fee_assignments_year_student ON fee_assignments(academic_year_id, student_id);
CREATE INDEX IF NOT EXISTS idx_payment_records_student ON payment_records(student_id);
CREATE INDEX IF NOT EXISTS idx_payment_records_receipt ON payment_records(receipt_number);
CREATE INDEX IF NOT EXISTS idx_payment_records_assignment ON payment_records(assignment_id);
CREATE INDEX IF NOT EXISTS idx_payments_paid_at ON payment_records(paid_at DESC);

DO $$
BEGIN
    IF to_regclass('public.fee_bands') IS NOT NULL THEN
    INSERT INTO fee_bands
        (id, name, class_from, class_to, discount, active_schedules_csv,
         created_at, updated_at, academic_year_id)
    SELECT id, name, class_from, class_to, discount, active_schedules_csv,
           created_at, updated_at, academic_year_id
    FROM public.fee_bands
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.fee_items') IS NOT NULL THEN
    INSERT INTO fee_items
        (id, name, frequency, amount, created_at, updated_at, band_id)
    SELECT id, name, frequency, amount, created_at, updated_at, band_id
    FROM public.fee_items
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.fee_assignments') IS NOT NULL THEN
    INSERT INTO fee_assignments
        (id, schedule, band_discount, manual_discount, surcharge, net_payable,
         paid_amount, assigned_by, assigned_at, updated_by, updated_at, student_id,
         band_id, academic_year_id, version)
    SELECT id, schedule, band_discount, manual_discount, surcharge, net_payable,
           paid_amount, assigned_by, assigned_at, updated_by, updated_at, student_id,
           band_id, academic_year_id, version
    FROM public.fee_assignments
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.payment_records') IS NOT NULL THEN
    INSERT INTO payment_records
        (id, amount, mode, notes, paid_at, recorded_by, receipt_number, created_at,
         student_id, assignment_id, version, created_by)
    SELECT id, amount, mode, notes, paid_at, recorded_by, receipt_number, created_at,
           student_id, assignment_id, version, created_by
    FROM public.payment_records
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;
