-- Augments reporting.dim_student with attendance_percent + father_name so the low-attendance
-- and fee-defaulter reads can later be decoupled from student.students, per Reporting
-- Decoupling follow-up to SP5.
ALTER TABLE dim_student ADD COLUMN attendance_percent NUMERIC;
ALTER TABLE dim_student ADD COLUMN father_name VARCHAR(255);
