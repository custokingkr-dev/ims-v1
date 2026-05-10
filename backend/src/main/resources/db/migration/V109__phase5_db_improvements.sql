-- V109 – Phase 5 Database Enterprise Readiness
-- Adds optimistic locking (version), missing indexes, soft-delete, and check constraints.
-- All changes are idempotent (IF NOT EXISTS / IF EXISTS guards).

-- ── Optimistic locking: version columns ─────────────────────────────────────

ALTER TABLE students              ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE fee_assignments       ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payment_records       ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE firefighting_requests ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE catalog_orders        ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- ── Soft delete columns ──────────────────────────────────────────────────────

ALTER TABLE students  ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE students  ADD COLUMN IF NOT EXISTS deleted_by VARCHAR(255);
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS deleted_by VARCHAR(255);

-- ── Additional audit columns ─────────────────────────────────────────────────

ALTER TABLE students          ADD COLUMN IF NOT EXISTS created_by VARCHAR(255);
ALTER TABLE students          ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);
ALTER TABLE payment_records   ADD COLUMN IF NOT EXISTS created_by VARCHAR(255);
ALTER TABLE catalog_orders    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255);
ALTER TABLE catalog_orders    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);
ALTER TABLE firefighting_requests ADD COLUMN IF NOT EXISTS created_by VARCHAR(255);
ALTER TABLE firefighting_requests ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

-- ── Missing indexes for query performance ────────────────────────────────────
-- (idx_audit_log_school_ts and idx_audit_log_user_id already exist from V101)

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename='students' AND indexname='idx_students_school_admission') THEN
        CREATE INDEX idx_students_school_admission ON students (school_id, admission_no);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename='students' AND indexname='idx_students_school_class_section') THEN
        CREATE INDEX idx_students_school_class_section ON students (school_id, class_id, section_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename='fee_assignments' AND indexname='idx_fee_assignments_year_student') THEN
        CREATE INDEX idx_fee_assignments_year_student ON fee_assignments (academic_year_id, student_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename='payment_records' AND indexname='idx_payments_paid_at') THEN
        CREATE INDEX idx_payments_paid_at ON payment_records (paid_at DESC);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename='catalog_orders' AND indexname='idx_catalog_orders_school_status') THEN
        CREATE INDEX idx_catalog_orders_school_status ON catalog_orders (school_id, status, created_at DESC);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename='firefighting_requests' AND indexname='idx_ff_requests_school_status') THEN
        CREATE INDEX idx_ff_requests_school_status ON firefighting_requests (school_id, status, created_at DESC);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename='audit_log' AND indexname='idx_audit_log_actor_ts') THEN
        CREATE INDEX idx_audit_log_actor_ts ON audit_log (actor_user_id, timestamp DESC);
    END IF;
END $$;

-- ── Check constraints for data integrity ─────────────────────────────────────

ALTER TABLE payment_records
    DROP CONSTRAINT IF EXISTS chk_payment_amount_positive;
ALTER TABLE payment_records
    ADD CONSTRAINT chk_payment_amount_positive CHECK (amount > 0);

ALTER TABLE fee_assignments
    DROP CONSTRAINT IF EXISTS chk_fee_net_payable_non_negative;
ALTER TABLE fee_assignments
    ADD CONSTRAINT chk_fee_net_payable_non_negative CHECK (net_payable >= 0);
