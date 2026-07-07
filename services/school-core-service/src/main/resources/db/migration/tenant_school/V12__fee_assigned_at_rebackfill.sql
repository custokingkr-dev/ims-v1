-- Reporting Decoupling: re-seed tenant_school.outbox_events for existing fee.fee_assignments
-- rows with the augmented fee-assignment.upserted.v1 payload (now including assignedAt) so the
-- reporting.fact_fee_assignment.assigned_at column (reporting/V17) gets backfilled for
-- pre-existing assignments without waiting for a subsequent mutation. Idempotent projector
-- upsert (ON CONFLICT (id) DO UPDATE) re-projects the existing fact rows, now filling
-- assigned_at. Mirrors V9__fee_outbox_backfill.sql's guarded to_regclass() pattern.
DO $$
BEGIN
    IF to_regclass('fee.fee_assignments') IS NOT NULL THEN
        INSERT INTO tenant_school.outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
        SELECT 'FeeAssignmentUpserted:'||fa.id||':assigned-at-rebackfill', 'fee-assignment.upserted.v1', 'FeeAssignment', fa.id, fa.school_id,
               jsonb_build_object(
                   'id', fa.id,
                   'studentId', fa.student_id,
                   'schoolId', fa.school_id,
                   'academicYearId', fa.academic_year_id,
                   'netPayable', fa.net_payable,
                   'paidAmount', fa.paid_amount,
                   'dueAmount', GREATEST(fa.net_payable - fa.paid_amount, 0),
                   'status', CASE WHEN fa.paid_amount >= fa.net_payable THEN 'Paid' ELSE 'Overdue' END,
                   'assignedAt', fa.assigned_at)
        FROM fee.fee_assignments fa;
    END IF;
END $$;
