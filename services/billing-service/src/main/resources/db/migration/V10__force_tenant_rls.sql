-- FORCE applies existing policies to non-superuser table owners; do not change relay exclusions.
-- Owner backfills requiring deliberate privileged scope must SET LOCAL app.bypass_rls='on'.
DO $$
DECLARE tenant_table RECORD;
BEGIN
    FOR tenant_table IN
        SELECT namespace.nspname AS schema_name, relation.relname AS table_name
        FROM pg_class relation JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'billing' AND relation.relrowsecurity AND relation.relkind IN ('r','p')
    LOOP
        EXECUTE format('ALTER TABLE %I.%I FORCE ROW LEVEL SECURITY', tenant_table.schema_name, tenant_table.table_name);
    END LOOP;
END $$;
