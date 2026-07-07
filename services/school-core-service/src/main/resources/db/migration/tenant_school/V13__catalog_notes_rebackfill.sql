-- Re-seeds the catalog-order.upserted.v1 outbox for pre-existing catalog.catalog_orders rows
-- with the notes field added to the payload, so the reporting.fact_catalog_order.notes column
-- (added alongside this migration) gets backfilled for orders that existed before notes was
-- added to the emit/backfill payload. Mirrors the V11 catalog outbox backfill shape, guarded
-- the same way for fresh databases where catalog.catalog_orders does not exist yet.
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
                   'designStatus', co.design_status,
                   'notes', co.notes)
        FROM catalog.catalog_orders co;
    END IF;
END $$;
