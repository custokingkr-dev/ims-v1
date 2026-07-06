-- Backfill catalog-order.upserted.v1 outbox events for pre-existing catalog.catalog_orders
-- rows, per Reporting Decoupling SP4. school-core-service owns both the catalog schema and
-- the tenant_school.outbox_events table, so this stays a same-service, same-database
-- migration (no cross-service foreign keys). Mirrors the V8 reference-outbox backfill shape.
--
-- Guarded with to_regclass: SchoolCoreFlywayConfig runs the tenant_school Flyway instance
-- BEFORE the catalog Flyway instance (@DependsOn chain: tenant_school -> student -> attendance
-- -> fee -> catalog), so on a fresh database catalog.catalog_orders does not exist yet when
-- this migration executes. to_regclass returns NULL (no error) for a not-yet-existing
-- schema/table, so this is a no-op there; environments where catalog already has data (i.e.
-- this migration is added after catalog already exists) get the real backfill.
DO $$
BEGIN
    IF to_regclass('catalog.catalog_orders') IS NOT NULL THEN
        INSERT INTO tenant_school.outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
        SELECT 'CatalogOrderUpserted:'||co.id, 'catalog-order.upserted.v1', 'CatalogOrder', co.id, co.school_id,
               jsonb_build_object(
                   'id', co.id,
                   'schoolId', co.school_id,
                   'category', co.category,
                   'status', co.status,
                   'totalAmount', co.total_amount,
                   'superadminApprovalStatus', co.superadmin_approval_status,
                   'vendorPaidAt', co.vendor_paid_at,
                   'createdAt', co.created_at,
                   'requiredByDate', co.required_by_date,
                   'designStatus', co.design_status)
        FROM catalog.catalog_orders co;
    END IF;
END $$;
