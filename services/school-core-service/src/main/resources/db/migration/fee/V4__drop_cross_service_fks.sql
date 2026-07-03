ALTER TABLE fee.fee_assignments
    DROP CONSTRAINT IF EXISTS fk_fee_assignment_student;

ALTER TABLE fee.payment_records
    DROP CONSTRAINT IF EXISTS fk_payment_student;
