-- Tombstone projection deletes are keyed by student rather than by school.
CREATE INDEX IF NOT EXISTS idx_fact_fee_assignment_student_id
    ON reporting.fact_fee_assignment (student_id)
    WHERE student_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_fact_payment_student_id
    ON reporting.fact_payment (student_id)
    WHERE student_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_event_student_contributions_student_id
    ON reporting.event_student_contributions (student_id);
