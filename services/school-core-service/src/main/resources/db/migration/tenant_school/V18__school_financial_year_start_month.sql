ALTER TABLE tenant_school.schools
    ADD COLUMN IF NOT EXISTS financial_year_start_month INTEGER NOT NULL DEFAULT 4;

ALTER TABLE tenant_school.schools
    DROP CONSTRAINT IF EXISTS chk_school_financial_year_start_month;

ALTER TABLE tenant_school.schools
    ADD CONSTRAINT chk_school_financial_year_start_month
        CHECK (financial_year_start_month BETWEEN 1 AND 12);

DO $$
BEGIN
    IF to_regclass('tenant_school.outbox_events') IS NOT NULL THEN
        INSERT INTO tenant_school.outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
        SELECT 'SchoolUpserted:'||id, 'school.upserted.v1', 'School', id::text, id,
               jsonb_build_object(
                   'id', id,
                   'name', name,
                   'shortCode', short_code,
                   'city', city,
                   'state', state,
                   'active', active,
                   'academicYearStartMonth', academic_year_start_month,
                   'financialYearStartMonth', financial_year_start_month)
        FROM tenant_school.schools;
    END IF;
END $$;
