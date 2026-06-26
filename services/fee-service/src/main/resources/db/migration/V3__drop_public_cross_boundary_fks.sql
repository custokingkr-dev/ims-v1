ALTER TABLE fee.fee_bands
    DROP CONSTRAINT IF EXISTS fee_bands_academic_year_id_fkey;

ALTER TABLE fee.fee_assignments
    DROP CONSTRAINT IF EXISTS fee_assignments_academic_year_id_fkey;
