-- Owners must be subject to policies as well; runtime guard rejects owner credentials.
DO $$ DECLARE relation record; BEGIN
    FOR relation IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='reporting' AND c.relrowsecurity AND c.relkind IN ('r','p')
    LOOP EXECUTE format('ALTER TABLE %I.%I FORCE ROW LEVEL SECURITY','reporting',relation.relname); END LOOP;
END $$;
