ALTER TABLE fee_assignments DROP CONSTRAINT IF EXISTS fee_assignments_student_id_fkey;
ALTER TABLE fee_assignments DROP CONSTRAINT IF EXISTS fk_fee_assignment_student;
ALTER TABLE fee_assignments
    ADD CONSTRAINT fk_fee_assignment_student
    FOREIGN KEY (student_id) REFERENCES student.students(id);

ALTER TABLE payment_records DROP CONSTRAINT IF EXISTS payment_records_student_id_fkey;
ALTER TABLE payment_records DROP CONSTRAINT IF EXISTS fk_payment_student;
ALTER TABLE payment_records
    ADD CONSTRAINT fk_payment_student
    FOREIGN KEY (student_id) REFERENCES student.students(id);
