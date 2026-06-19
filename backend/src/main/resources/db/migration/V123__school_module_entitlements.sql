-- V123: School module entitlement system.
-- Each school can have specific modules enabled/disabled independently.
-- Access to a feature requires BOTH the RBAC permission AND the module being enabled.
-- This migration is forward-only and additive.

CREATE TABLE IF NOT EXISTS school_module_entitlements (
    id          BIGSERIAL PRIMARY KEY,
    school_id   BIGINT NOT NULL REFERENCES schools(id) ON DELETE CASCADE,
    module_code VARCHAR(50) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    plan        VARCHAR(50),           -- e.g. 'BASIC', 'STANDARD', 'PREMIUM'
    start_date  DATE,
    end_date    DATE,
    notes       TEXT,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by  BIGINT,

    CONSTRAINT uk_school_module UNIQUE (school_id, module_code),
    CONSTRAINT chk_module_dates CHECK (start_date IS NULL OR end_date IS NULL OR end_date >= start_date)
);

-- Supported module codes
-- STUDENTS, ATTENDANCE, FEES, INVOICES, PAYMENTS, ORDERS, FIREFIGHTING, REPORTS

CREATE INDEX IF NOT EXISTS idx_sme_school      ON school_module_entitlements (school_id);
CREATE INDEX IF NOT EXISTS idx_sme_school_code ON school_module_entitlements (school_id, module_code) WHERE enabled = TRUE;

COMMENT ON TABLE school_module_entitlements IS
    'Controls which operational modules are enabled per school. '
    'A user must hold the RBAC permission AND the school must have the module enabled.';

COMMENT ON COLUMN school_module_entitlements.module_code IS
    'One of: STUDENTS, ATTENDANCE, FEES, INVOICES, PAYMENTS, ORDERS, FIREFIGHTING, REPORTS';
