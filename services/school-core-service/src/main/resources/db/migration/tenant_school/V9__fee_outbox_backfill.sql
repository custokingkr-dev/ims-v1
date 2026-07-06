-- Reporting Decoupling SP2 (fee): backfill tenant_school.outbox_events from existing
-- fee.fee_assignments / fee.payment_records rows so pre-existing fee data is projected
-- into the reporting fact model without waiting for a subsequent mutation. Cross-schema
-- read is fine here (migrations run as the schema owner, not the RLS-scoped app_rt role).
-- fee.fee_assignments.school_id / fee.payment_records.school_id are already denormalized
-- from student.students (see fee/V5__tenant_key_denormalize.sql).
--
-- Guarded with to_regclass() (mirrors fee/V5__tenant_key_denormalize.sql) so this migration
-- is safe to run against a datasource where the fee schema hasn't been migrated yet (e.g.
-- test containers that only bring up tenant_school + student).
DO $$
BEGIN
    IF to_regclass('fee.fee_assignments') IS NOT NULL THEN
        INSERT INTO tenant_school.outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
        SELECT 'FeeAssignmentUpserted:'||fa.id, 'fee-assignment.upserted.v1', 'FeeAssignment', fa.id, fa.school_id,
               jsonb_build_object(
                   'id', fa.id,
                   'studentId', fa.student_id,
                   'schoolId', fa.school_id,
                   'academicYearId', fa.academic_year_id,
                   'netPayable', fa.net_payable,
                   'paidAmount', fa.paid_amount,
                   'dueAmount', GREATEST(fa.net_payable - fa.paid_amount, 0),
                   'status', CASE WHEN fa.paid_amount >= fa.net_payable THEN 'Paid' ELSE 'Overdue' END)
        FROM fee.fee_assignments fa;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('fee.payment_records') IS NOT NULL THEN
        INSERT INTO tenant_school.outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
        SELECT 'PaymentRecorded:'||pr.id, 'payment.recorded.v1', 'Payment', pr.id, pr.school_id,
               jsonb_build_object(
                   'id', pr.id,
                   'assignmentId', pr.assignment_id,
                   'schoolId', pr.school_id,
                   'studentId', pr.student_id,
                   'amount', pr.amount,
                   'paidAt', pr.paid_at)
        FROM fee.payment_records pr;
    END IF;
END $$;
