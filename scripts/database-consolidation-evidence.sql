-- Read-only evidence bundle for the staged database-consolidation gates.
-- Run as the migration/owner identity with psql -v ON_ERROR_STOP=1.
-- This script intentionally performs no UPDATE, DELETE, ALTER, or DROP.

\pset pager off
\echo 'billing legacy-to-canonical summary'
SELECT * FROM billing.legacy_invoice_migration_summary;

\echo 'billing issues by reason'
SELECT issue, count(*) AS rows
FROM billing.legacy_invoice_migration_issues
GROUP BY issue
ORDER BY issue;

\echo 'catalog legacy mapping readiness'
SELECT *
FROM catalog.legacy_catalog_migration_readiness
ORDER BY source_table;

\echo 'guardian legacy-column parity'
SELECT
    count(*) AS student_rows,
    count(*) FILTER (WHERE father_name_matches AND father_contact_matches AND mother_name_matches)
        AS matching_rows,
    count(*) FILTER (WHERE NOT father_name_matches) AS father_name_mismatches,
    count(*) FILTER (WHERE NOT father_contact_matches) AS father_contact_mismatches,
    count(*) FILTER (WHERE NOT mother_name_matches) AS mother_name_mismatches
FROM student.guardian_legacy_parity;

\echo 'reporting student projection parity'
SELECT * FROM reporting.student_projection_reconciliation_summary;
SELECT issue, count(*) AS rows
FROM reporting.student_projection_reconciliation
WHERE issue IS NOT NULL
GROUP BY issue
ORDER BY issue;

\echo 'processed outbox retention candidates (30-day observation only)'
SELECT 'billing' AS service, count(*) AS candidate_rows
FROM billing.outbox_events WHERE published_at < now() - interval '30 days'
UNION ALL
SELECT 'operations', count(*) FROM firefighting.outbox_events WHERE published_at < now() - interval '30 days'
UNION ALL
SELECT 'school-core', count(*) FROM tenant_school.outbox_events WHERE published_at < now() - interval '30 days'
ORDER BY service;

\echo 'largest user tables and indexes'
SELECT
    schemaname,
    relname,
    pg_size_pretty(pg_total_relation_size(relid)) AS total_size,
    pg_size_pretty(pg_relation_size(relid)) AS table_size,
    pg_size_pretty(pg_indexes_size(relid)) AS index_size,
    n_live_tup,
    n_dead_tup
FROM pg_stat_user_tables
ORDER BY pg_total_relation_size(relid) DESC
LIMIT 50;

\echo 'unused-index observations (not a drop list)'
SELECT
    schemaname,
    relname,
    indexrelname,
    idx_scan,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size
FROM pg_stat_user_indexes
WHERE idx_scan = 0
ORDER BY pg_relation_size(indexrelid) DESC
LIMIT 100;

\echo 'duplicate index definitions (review constraints before any action)'
SELECT
    namespace.nspname AS schemaname,
    table_class.relname AS tablename,
    indexes.indisunique,
    indexes.indkey::text AS key_columns,
    COALESCE(pg_get_expr(indexes.indexprs, indexes.indrelid), '') AS expressions,
    COALESCE(pg_get_expr(indexes.indpred, indexes.indrelid), '') AS predicate,
    array_agg(index_class.relname ORDER BY index_class.relname) AS index_names
FROM pg_index indexes
JOIN pg_class table_class ON table_class.oid = indexes.indrelid
JOIN pg_class index_class ON index_class.oid = indexes.indexrelid
JOIN pg_namespace namespace ON namespace.oid = table_class.relnamespace
WHERE namespace.nspname NOT IN ('pg_catalog', 'information_schema')
GROUP BY namespace.nspname, table_class.relname, indexes.indisunique,
         indexes.indkey::text, indexes.indclass::text, indexes.indcollation::text,
         pg_get_expr(indexes.indexprs, indexes.indrelid),
         pg_get_expr(indexes.indpred, indexes.indrelid)
HAVING count(*) > 1
ORDER BY schemaname, tablename, key_columns;

\echo 'pg_stat_statements availability'
SELECT to_regclass('public.pg_stat_statements') IS NOT NULL AS available;
