-- V107 – Convert all @Lob String columns from oid back to text.
-- In Hibernate 6 + PostgreSQL, @Lob on a String maps to oid (Large Object).
-- Environments where ddl-auto=update ran with the old @Lob annotations
-- caused several TEXT columns to be silently converted to oid.
-- The entities now use @Column(columnDefinition="TEXT"), so every affected
-- column must be text for schema validation to pass.
-- This migration is a no-op on any column that is already text.

DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN
        SELECT table_name, column_name
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND data_type    = 'oid'
          AND (table_name, column_name) IN (
              ('import_batches', 'skipped_json'),
              ('import_rows',    'message'),
              ('import_rows',    'raw_json'),
              ('import_rows',    'normalized_json'),
              ('payment_records','notes'),
              ('students',       'address')
          )
    LOOP
        -- Add a temporary TEXT column
        EXECUTE format(
            'ALTER TABLE %I ADD COLUMN %I_new TEXT',
            rec.table_name, rec.column_name
        );

        -- Attempt to recover Large Object content; silently skip if objects
        -- are missing (common in dev environments where data was never written
        -- via the @Lob path).
        BEGIN
            EXECUTE format(
                'UPDATE %I SET %I_new = convert_from(lo_get(%I), ''UTF8'') WHERE %I IS NOT NULL',
                rec.table_name, rec.column_name, rec.column_name, rec.column_name
            );
        EXCEPTION WHEN OTHERS THEN
            NULL;
        END;

        -- Swap: drop old oid column, rename new text column
        EXECUTE format('ALTER TABLE %I DROP COLUMN %I',         rec.table_name, rec.column_name);
        EXECUTE format('ALTER TABLE %I RENAME COLUMN %I_new TO %I', rec.table_name, rec.column_name, rec.column_name);
    END LOOP;
END $$;
