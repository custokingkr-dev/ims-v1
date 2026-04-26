-- =============================================================
-- V3 – Performance indexes
-- Derived from repository query patterns: lookups by school/year,
-- section, status, student, and batch filters used in service layer.
-- =============================================================

-- app_users
CREATE INDEX IF NOT EXISTS idx_app_users_role
    ON app_users (role);

CREATE INDEX IF NOT EXISTS idx_app_users_role_branch
    ON app_users (role, branch_id);

-- students
CREATE INDEX IF NOT EXISTS idx_students_school_year
    ON students (school_id, academic_year_id);

CREATE INDEX IF NOT EXISTS idx_students_section
    ON students (section_id);

CREATE INDEX IF NOT EXISTS idx_students_class_school
    ON students (class_id, school_id);

-- fee_assignments
CREATE INDEX IF NOT EXISTS idx_fee_assignments_student
    ON fee_assignments (student_id);

CREATE INDEX IF NOT EXISTS idx_fee_assignments_band_year
    ON fee_assignments (band_id, academic_year_id);

-- payment_records
CREATE INDEX IF NOT EXISTS idx_payment_records_student
    ON payment_records (student_id);

CREATE INDEX IF NOT EXISTS idx_payment_records_receipt
    ON payment_records (receipt_number);

CREATE INDEX IF NOT EXISTS idx_payment_records_assignment
    ON payment_records (assignment_id);

-- attendance_daily
CREATE INDEX IF NOT EXISTS idx_attendance_section_year
    ON attendance_daily (section_id, academic_year_id);

CREATE INDEX IF NOT EXISTS idx_attendance_date
    ON attendance_daily (attendance_date);

-- catalog_orders
CREATE INDEX IF NOT EXISTS idx_catalog_orders_school
    ON catalog_orders (school_id);

CREATE INDEX IF NOT EXISTS idx_catalog_orders_status
    ON catalog_orders (status);

-- firefighting_requests
CREATE INDEX IF NOT EXISTS idx_ff_requests_school
    ON firefighting_requests (school_id);

CREATE INDEX IF NOT EXISTS idx_ff_requests_status
    ON firefighting_requests (status);

-- annual_plan_items
CREATE INDEX IF NOT EXISTS idx_annual_plan_school_year
    ON annual_plan_items (school_id, academic_year_id);

-- import_rows
CREATE INDEX IF NOT EXISTS idx_import_rows_batch
    ON import_rows (batch_id);

CREATE INDEX IF NOT EXISTS idx_import_rows_batch_status
    ON import_rows (batch_id, status);

-- fee_bands
CREATE INDEX IF NOT EXISTS idx_fee_bands_year
    ON fee_bands (academic_year_id);

-- school_sections
CREATE INDEX IF NOT EXISTS idx_school_sections_school
    ON school_sections (school_id);

CREATE INDEX IF NOT EXISTS idx_school_sections_class
    ON school_sections (school_class_id);
