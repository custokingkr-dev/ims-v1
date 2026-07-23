ALTER TABLE tenant_school.staff_members
    ADD COLUMN IF NOT EXISTS employee_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS phone VARCHAR(50),
    ADD COLUMN IF NOT EXISTS staff_type VARCHAR(64),
    ADD COLUMN IF NOT EXISTS employment_status VARCHAR(64) NOT NULL DEFAULT 'Active',
    ADD COLUMN IF NOT EXISTS join_date DATE,
    ADD COLUMN IF NOT EXISTS notes TEXT;

UPDATE tenant_school.staff_members
SET staff_type = CASE
        WHEN lower(coalesce(designation, '')) LIKE '%teacher%' THEN 'Teaching'
        ELSE 'Non-teaching'
    END
WHERE staff_type IS NULL OR trim(staff_type) = '';

UPDATE tenant_school.staff_members
SET employment_status = 'Active'
WHERE employment_status IS NULL OR trim(employment_status) = '';

UPDATE tenant_school.staff_members
SET payroll_status = 'Pending'
WHERE payroll_status IS NULL OR trim(payroll_status) = '';

CREATE UNIQUE INDEX IF NOT EXISTS uq_staff_members_school_employee_code
    ON tenant_school.staff_members (school_id, lower(employee_code))
    WHERE employee_code IS NOT NULL AND trim(employee_code) <> '';

CREATE INDEX IF NOT EXISTS idx_staff_members_school_status
    ON tenant_school.staff_members (school_id, employment_status, staff_type);
