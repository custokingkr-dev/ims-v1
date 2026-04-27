-- V106 – Convert fee_bands.active_schedules_csv from oid back to text.
-- The V1 baseline defines this column as TEXT, but environments where
-- Hibernate ran with ddl-auto=update and the @Lob annotation was present
-- caused PostgreSQL to change the column type to oid (Large Object ref).
-- The entity now uses @Column(columnDefinition="TEXT"), so the DB column
-- must be text. This migration is a no-op on fresh DBs where the column
-- is already text.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name   = 'fee_bands'
          AND column_name  = 'active_schedules_csv'
          AND data_type    = 'oid'
    ) THEN
        ALTER TABLE fee_bands ADD COLUMN active_schedules_csv_new TEXT;

        -- Attempt to recover Large Object content; silently skip if the
        -- objects no longer exist (common in dev environments).
        BEGIN
            UPDATE fee_bands
               SET active_schedules_csv_new =
                       convert_from(lo_get(active_schedules_csv), 'UTF8')
             WHERE active_schedules_csv IS NOT NULL;
        EXCEPTION WHEN OTHERS THEN
            NULL;
        END;

        ALTER TABLE fee_bands DROP COLUMN active_schedules_csv;
        ALTER TABLE fee_bands RENAME COLUMN active_schedules_csv_new TO active_schedules_csv;
    END IF;
END $$;
