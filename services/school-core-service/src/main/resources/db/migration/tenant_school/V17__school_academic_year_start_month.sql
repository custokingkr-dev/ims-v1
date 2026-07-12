ALTER TABLE tenant_school.schools
    ADD COLUMN IF NOT EXISTS academic_year_start_month INTEGER NOT NULL DEFAULT 4;

ALTER TABLE tenant_school.schools
    DROP CONSTRAINT IF EXISTS chk_school_academic_year_start_month;

ALTER TABLE tenant_school.schools
    ADD CONSTRAINT chk_school_academic_year_start_month
        CHECK (academic_year_start_month BETWEEN 1 AND 12);
