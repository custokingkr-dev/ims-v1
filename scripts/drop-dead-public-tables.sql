-- One-off prod cleanup: drop two verified-dead monolith-era orphan tables in the
-- unowned `public` schema. Both confirmed unreferenced by any service code/config;
-- no service uses the public schema. Idempotent (IF EXISTS) — a no-op on any DB that
-- never had them. Run via the Cloud Run psql-job pattern (see the plan / spec).
\pset pager off

\echo '== PRE-DROP SAFETY RECORD: public.flyway_schema_history (old monolith Flyway history) =='
SELECT count(*) AS row_count FROM public.flyway_schema_history;
SELECT * FROM public.flyway_schema_history ORDER BY 1;

\echo '== PRE-DROP SAFETY RECORD: public.outbox_events (monolith leftover; Task 3.1 outbox will be per-service, NOT this) =='
SELECT count(*) AS row_count FROM public.outbox_events;
SELECT * FROM public.outbox_events;

\echo '== DROP =='
-- Plain DROP (no CASCADE) on purpose: both tables were verified to have no dependents,
-- so a plain drop succeeds — and if that assumption is ever wrong, it FAILS LOUDLY on the
-- offending dependent rather than silently cascade-dropping unexpected objects in prod.
DROP TABLE IF EXISTS public.outbox_events;
DROP TABLE IF EXISTS public.flyway_schema_history;

\echo '== POST-DROP: remaining public base tables (the two above must be gone) =='
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY 1;
