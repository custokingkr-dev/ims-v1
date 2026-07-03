CREATE SEQUENCE IF NOT EXISTS seq_students START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS import_batches (
    id VARCHAR(255) PRIMARY KEY,
    file_token VARCHAR(255),
    job_id VARCHAR(255),
    total_rows INTEGER NOT NULL,
    valid_count INTEGER NOT NULL,
    error_count INTEGER NOT NULL,
    warning_count INTEGER NOT NULL,
    status VARCHAR(255),
    pct INTEGER NOT NULL,
    inserted INTEGER NOT NULL,
    skipped INTEGER NOT NULL,
    skipped_json TEXT,
    created_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_student_import_batch_file_token UNIQUE (file_token),
    CONSTRAINT uq_student_import_batch_job_id UNIQUE (job_id)
);

CREATE TABLE IF NOT EXISTS students (
    id BIGINT PRIMARY KEY DEFAULT nextval('seq_students'),
    admission_no VARCHAR(255) NOT NULL,
    roll_no VARCHAR(255),
    board_reg_no VARCHAR(255),
    full_name VARCHAR(255) NOT NULL,
    dob DATE,
    gender VARCHAR(255),
    father_name VARCHAR(255),
    father_contact VARCHAR(255),
    mother_name VARCHAR(255),
    phone VARCHAR(255),
    address TEXT,
    house_number VARCHAR(255),
    street VARCHAR(255),
    locality VARCHAR(255),
    city VARCHAR(255),
    state VARCHAR(255),
    pin_code VARCHAR(255),
    photo_url VARCHAR(255),
    fee_status VARCHAR(255),
    attendance_percent DOUBLE PRECISION,
    imported_at TIMESTAMPTZ,
    import_batch_id VARCHAR(255),
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    school_id BIGINT NOT NULL,
    class_id VARCHAR(255) NOT NULL,
    section_id VARCHAR(255) NOT NULL,
    academic_year_id VARCHAR(255) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    deleted_by VARCHAR(255),
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    CONSTRAINT uix_student_students_school_admission UNIQUE (school_id, admission_no)
);

CREATE TABLE IF NOT EXISTS import_rows (
    id VARCHAR(255) PRIMARY KEY,
    row_no INTEGER NOT NULL,
    name VARCHAR(255),
    class_name VARCHAR(255),
    section_name VARCHAR(255),
    admission_no VARCHAR(255),
    phone VARCHAR(255),
    status VARCHAR(255),
    message TEXT,
    raw_json TEXT,
    normalized_json TEXT,
    batch_id VARCHAR(255) NOT NULL REFERENCES import_batches(id)
);

CREATE TABLE IF NOT EXISTS student_review_campaigns (
    id VARCHAR(36) PRIMARY KEY,
    school_id BIGINT NOT NULL,
    academic_year_id VARCHAR(36),
    review_type VARCHAR(30) NOT NULL,
    title VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    verifier VARCHAR(20),
    initiated_by BIGINT,
    initiated_at TIMESTAMPTZ,
    due_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS student_review_items (
    id VARCHAR(36) PRIMARY KEY,
    campaign_id VARCHAR(36) NOT NULL REFERENCES student_review_campaigns(id),
    student_id BIGINT NOT NULL REFERENCES students(id),
    school_id BIGINT NOT NULL,
    assigned_to_user_id BIGINT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    verified_photo BOOLEAN NOT NULL DEFAULT FALSE,
    verified_full_name BOOLEAN NOT NULL DEFAULT FALSE,
    verified_admission_no BOOLEAN NOT NULL DEFAULT FALSE,
    verified_class_section BOOLEAN NOT NULL DEFAULT FALSE,
    verified_roll_no BOOLEAN NOT NULL DEFAULT FALSE,
    verified_father_name BOOLEAN NOT NULL DEFAULT FALSE,
    verified_father_contact BOOLEAN NOT NULL DEFAULT FALSE,
    verified_address BOOLEAN NOT NULL DEFAULT FALSE,
    verified_blood_group BOOLEAN NOT NULL DEFAULT FALSE,
    current_full_name VARCHAR(200),
    suggested_full_name VARCHAR(200),
    parent_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    teacher_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    correction_requested BOOLEAN NOT NULL DEFAULT FALSE,
    correction_notes TEXT,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_student_campaign_student UNIQUE (campaign_id, student_id)
);

CREATE INDEX IF NOT EXISTS idx_student_students_school_year ON students(school_id, academic_year_id);
CREATE INDEX IF NOT EXISTS idx_student_students_section ON students(section_id);
CREATE INDEX IF NOT EXISTS idx_student_students_class_school ON students(class_id, school_id);
CREATE INDEX IF NOT EXISTS idx_student_students_school_id ON students(school_id);
CREATE INDEX IF NOT EXISTS idx_student_students_school_class_section ON students(school_id, class_id, section_id);
CREATE INDEX IF NOT EXISTS idx_student_import_rows_batch ON import_rows(batch_id);
CREATE INDEX IF NOT EXISTS idx_student_import_rows_batch_status ON import_rows(batch_id, status);
CREATE INDEX IF NOT EXISTS idx_student_review_campaigns_school ON student_review_campaigns(school_id, review_type, status);
CREATE INDEX IF NOT EXISTS idx_student_review_items_campaign ON student_review_items(campaign_id);
CREATE INDEX IF NOT EXISTS idx_student_review_items_school ON student_review_items(school_id, status);

DO $$
BEGIN
    IF to_regclass('public.import_batches') IS NOT NULL THEN
    INSERT INTO import_batches
        (id, file_token, job_id, total_rows, valid_count, error_count, warning_count,
         status, pct, inserted, skipped, skipped_json, created_at, completed_at)
    SELECT id, file_token, job_id, total_rows, valid_count, error_count, warning_count,
           status, pct, inserted, skipped, skipped_json, created_at, completed_at
    FROM public.import_batches
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.students') IS NOT NULL THEN
    INSERT INTO students
        (id, admission_no, roll_no, board_reg_no, full_name, dob, gender,
         father_name, father_contact, mother_name, phone, address, house_number,
         street, locality, city, state, pin_code, photo_url, fee_status,
         attendance_percent, imported_at, import_batch_id, created_at, updated_at,
         school_id, class_id, section_id, academic_year_id, version, deleted_at,
         deleted_by, created_by, updated_by)
    SELECT id, admission_no, roll_no, board_reg_no, full_name, dob, gender,
           father_name, father_contact, mother_name, phone, address, house_number,
           street, locality, city, state, pin_code, photo_url, fee_status,
           attendance_percent, imported_at, import_batch_id, created_at, updated_at,
           school_id, class_id, section_id, academic_year_id, version, deleted_at,
           deleted_by, created_by, updated_by
    FROM public.students
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.import_rows') IS NOT NULL THEN
    INSERT INTO import_rows
        (id, row_no, name, class_name, section_name, admission_no, phone,
         status, message, raw_json, normalized_json, batch_id)
    SELECT id, row_no, name, class_name, section_name, admission_no, phone,
           status, message, raw_json, normalized_json, batch_id
    FROM public.import_rows
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.student_review_campaigns') IS NOT NULL THEN
    INSERT INTO student_review_campaigns
        (id, school_id, academic_year_id, review_type, title, status, verifier,
         initiated_by, initiated_at, due_date, created_at, updated_at)
    SELECT id, school_id, academic_year_id, review_type, title, status, verifier,
           initiated_by, initiated_at, due_date, created_at, updated_at
    FROM public.student_review_campaigns
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.student_review_items') IS NOT NULL THEN
    INSERT INTO student_review_items
        (id, campaign_id, student_id, school_id, assigned_to_user_id, status,
         verified_photo, verified_full_name, verified_admission_no,
         verified_class_section, verified_roll_no, verified_father_name,
         verified_father_contact, verified_address, verified_blood_group,
         current_full_name, suggested_full_name, parent_confirmed,
         teacher_confirmed, correction_requested, correction_notes, completed_at,
         created_at, updated_at)
    SELECT id, campaign_id, student_id, school_id, assigned_to_user_id, status,
           verified_photo, verified_full_name, verified_admission_no,
           verified_class_section, verified_roll_no, verified_father_name,
           verified_father_contact, verified_address, verified_blood_group,
           current_full_name, suggested_full_name, parent_confirmed,
           teacher_confirmed, correction_requested, correction_notes, completed_at,
           created_at, updated_at
    FROM public.student_review_items
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

SELECT setval('seq_students', COALESCE((SELECT max(id) FROM students), 0) + 1, false);
