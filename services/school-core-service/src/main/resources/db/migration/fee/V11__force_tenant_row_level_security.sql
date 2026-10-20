-- Forward-only: existing policies remain authoritative; owners must also obey tenant RLS.
DO $$
DECLARE protected_table record;
BEGIN
    FOR protected_table IN
        SELECT namespace.nspname AS schema_name, relation.relname AS table_name
        FROM pg_class relation JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'fee'
          AND relation.relkind IN ('r', 'p') AND relation.relrowsecurity
    LOOP
        EXECUTE format('ALTER TABLE %I.%I FORCE ROW LEVEL SECURITY',
                       protected_table.schema_name, protected_table.table_name);
    END LOOP;
END;
$$;
