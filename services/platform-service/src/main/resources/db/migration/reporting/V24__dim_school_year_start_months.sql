ALTER TABLE reporting.dim_school
    ADD COLUMN IF NOT EXISTS academic_year_start_month INTEGER NOT NULL DEFAULT 4;

ALTER TABLE reporting.dim_school
    ADD COLUMN IF NOT EXISTS financial_year_start_month INTEGER NOT NULL DEFAULT 4;

ALTER TABLE reporting.dim_school
    DROP CONSTRAINT IF EXISTS chk_dim_school_academic_year_start_month;

ALTER TABLE reporting.dim_school
    ADD CONSTRAINT chk_dim_school_academic_year_start_month
        CHECK (academic_year_start_month BETWEEN 1 AND 12);

ALTER TABLE reporting.dim_school
    DROP CONSTRAINT IF EXISTS chk_dim_school_financial_year_start_month;

ALTER TABLE reporting.dim_school
    ADD CONSTRAINT chk_dim_school_financial_year_start_month
        CHECK (financial_year_start_month BETWEEN 1 AND 12);
