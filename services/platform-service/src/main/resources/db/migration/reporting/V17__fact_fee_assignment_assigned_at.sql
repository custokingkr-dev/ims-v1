-- Reporting Decoupling: augment reporting.fact_fee_assignment with assigned_at so the
-- fee-defaulters read can compute due/overdue days downstream. Sourced from
-- fee.fee_assignments.assigned_at via the existing fee-assignment.upserted.v1 outbox event.
ALTER TABLE fact_fee_assignment ADD COLUMN assigned_at TIMESTAMPTZ;
