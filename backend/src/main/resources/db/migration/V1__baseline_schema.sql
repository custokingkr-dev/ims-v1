-- =============================================================
-- V1 – Baseline schema
-- Generated from entity classes; matches Hibernate 6
-- CamelCaseToUnderscoresNamingStrategy exactly.
-- Implicit @ManyToOne FK column = {field_snake_case}_id
-- @Lob String  → TEXT
-- primitive long/int/double/boolean → NOT NULL
-- IF NOT EXISTS on every statement so this migration is
-- idempotent on databases already created by ddl-auto: update.
-- =============================================================

-- ── Independent tables (no FKs) ─────────────────────────────

CREATE TABLE IF NOT EXISTS academic_years (
    id     VARCHAR(255) NOT NULL,
    label  VARCHAR(255),
    active BOOLEAN      NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS schools (
    id                       BIGSERIAL    NOT NULL,
    name                     VARCHAR(255) NOT NULL,
    short_code               VARCHAR(255) NOT NULL,
    city                     VARCHAR(255),
    state                    VARCHAR(255),
    contact_email            VARCHAR(255),
    contact_phone            VARCHAR(255),
    active                   BOOLEAN      NOT NULL,
    configured_class_count   INTEGER,
    configured_section_count INTEGER,
    created_at               TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_school_short_code UNIQUE (short_code)
);

CREATE TABLE IF NOT EXISTS school_classes (
    id         VARCHAR(255) NOT NULL,
    name       VARCHAR(255),
    sort_order INTEGER      NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS app_users (
    id            BIGSERIAL    NOT NULL,
    full_name     VARCHAR(255) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(255) NOT NULL,
    branch_id     BIGINT,
    branch_name   VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_app_user_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS catalog_items (
    id            BIGSERIAL NOT NULL,
    title         VARCHAR(255),
    subtitle      VARCHAR(255),
    icon          VARCHAR(255),
    order_type    VARCHAR(255),
    sample_amount BIGINT    NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS supply_orders (
    code         VARCHAR(255) NOT NULL,
    title        VARCHAR(255),
    category     VARCHAR(255),
    items        VARCHAR(255),
    amount       BIGINT       NOT NULL,
    status       VARCHAR(255),
    order_date   DATE,
    action_label VARCHAR(255),
    PRIMARY KEY (code)
);

CREATE TABLE IF NOT EXISTS annual_plan_entries (
    id        BIGSERIAL    NOT NULL,
    term_name VARCHAR(255),
    category  VARCHAR(255),
    status    VARCHAR(255),
    quantity  VARCHAR(255),
    amount    BIGINT       NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS import_batches (
    id            VARCHAR(255) NOT NULL,
    file_token    VARCHAR(255),
    job_id        VARCHAR(255),
    total_rows    INTEGER      NOT NULL,
    valid_count   INTEGER      NOT NULL,
    error_count   INTEGER      NOT NULL,
    warning_count INTEGER      NOT NULL,
    status        VARCHAR(255),
    pct           INTEGER      NOT NULL,
    inserted      INTEGER      NOT NULL,
    skipped       INTEGER      NOT NULL,
    skipped_json  TEXT,
    created_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    PRIMARY KEY (id),
    CONSTRAINT uq_import_batch_file_token UNIQUE (file_token),
    CONSTRAINT uq_import_batch_job_id     UNIQUE (job_id)
);

CREATE TABLE IF NOT EXISTS superadmin_invoices (
    id          VARCHAR(255) NOT NULL,
    order_ref   VARCHAR(255),
    school      VARCHAR(255),
    school_id   BIGINT,
    description VARCHAR(255),
    qty         INTEGER      NOT NULL,
    rate        BIGINT       NOT NULL,
    amount      BIGINT       NOT NULL,
    gst_amount  BIGINT       NOT NULL,
    total       BIGINT       NOT NULL,
    status      VARCHAR(255),
    issued_at   VARCHAR(255),
    due_at      VARCHAR(255),
    notes       VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS superadmin_order_seq (
    id          VARCHAR(255) NOT NULL,
    order_seq   BIGINT       NOT NULL,
    invoice_seq BIGINT       NOT NULL,
    PRIMARY KEY (id)
);

-- ── auth_sessions (→ app_users) ──────────────────────────────

CREATE TABLE IF NOT EXISTS auth_sessions (
    id            VARCHAR(255) NOT NULL,
    access_token  VARCHAR(200) NOT NULL,
    refresh_token VARCHAR(200) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    user_id       BIGINT       NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT idx_auth_session_access  UNIQUE (access_token),
    CONSTRAINT idx_auth_session_refresh UNIQUE (refresh_token),
    CONSTRAINT fk_auth_session_user     FOREIGN KEY (user_id) REFERENCES app_users (id)
);

-- ── school_sections (→ school_classes, schools) ──────────────

CREATE TABLE IF NOT EXISTS school_sections (
    id              VARCHAR(255) NOT NULL,
    name            VARCHAR(255),
    teacher_name    VARCHAR(255),
    active          BOOLEAN      NOT NULL,
    school_class_id VARCHAR(255) NOT NULL,
    school_id       BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_section_class  FOREIGN KEY (school_class_id) REFERENCES school_classes (id),
    CONSTRAINT fk_school_section_school FOREIGN KEY (school_id)        REFERENCES schools (id)
);

-- ── students (→ schools, school_classes, school_sections, academic_years) ──

CREATE TABLE IF NOT EXISTS students (
    id                 BIGSERIAL    NOT NULL,
    admission_no       VARCHAR(255) NOT NULL,
    roll_no            VARCHAR(255),
    board_reg_no       VARCHAR(255),
    full_name          VARCHAR(255) NOT NULL,
    dob                DATE,
    gender             VARCHAR(255),
    father_name        VARCHAR(255),
    father_contact     VARCHAR(255),
    mother_name        VARCHAR(255),
    phone              VARCHAR(255),
    address            TEXT,
    house_number       VARCHAR(255),
    street             VARCHAR(255),
    locality           VARCHAR(255),
    city               VARCHAR(255),
    state              VARCHAR(255),
    pin_code           VARCHAR(255),
    photo_url          VARCHAR(255),
    fee_status         VARCHAR(255),
    attendance_percent FLOAT8,
    imported_at        TIMESTAMPTZ,
    import_batch_id    VARCHAR(255),
    created_at         TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ,
    school_id          BIGINT       NOT NULL,
    class_id           VARCHAR(255) NOT NULL,
    section_id         VARCHAR(255) NOT NULL,
    academic_year_id   VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_student_admission_no     UNIQUE (admission_no),
    CONSTRAINT fk_student_school           FOREIGN KEY (school_id)        REFERENCES schools (id),
    CONSTRAINT fk_student_class            FOREIGN KEY (class_id)          REFERENCES school_classes (id),
    CONSTRAINT fk_student_section          FOREIGN KEY (section_id)        REFERENCES school_sections (id),
    CONSTRAINT fk_student_academic_year    FOREIGN KEY (academic_year_id)  REFERENCES academic_years (id)
);

-- ── fee_bands (→ academic_years) ─────────────────────────────

CREATE TABLE IF NOT EXISTS fee_bands (
    id                   VARCHAR(255) NOT NULL,
    name                 VARCHAR(255),
    class_from           INTEGER      NOT NULL,
    class_to             INTEGER      NOT NULL,
    discount             FLOAT8       NOT NULL,
    active_schedules_csv TEXT,
    created_at           TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ,
    academic_year_id     VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_fee_band_academic_year FOREIGN KEY (academic_year_id) REFERENCES academic_years (id)
);

-- ── fee_items (→ fee_bands) ───────────────────────────────────

CREATE TABLE IF NOT EXISTS fee_items (
    id         VARCHAR(255) NOT NULL,
    name       VARCHAR(255),
    frequency  VARCHAR(255),
    amount     BIGINT       NOT NULL,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    band_id    VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_fee_item_band FOREIGN KEY (band_id) REFERENCES fee_bands (id)
);

-- ── fee_assignments (→ students, fee_bands, academic_years) ──
-- UK column names from @UniqueConstraint: "student_id" and "academicYear_id"
-- After CamelCaseToUnderscoresNamingStrategy: academic_year_id

CREATE TABLE IF NOT EXISTS fee_assignments (
    id               VARCHAR(255) NOT NULL,
    schedule         VARCHAR(255),
    band_discount    FLOAT8       NOT NULL,
    manual_discount  FLOAT8       NOT NULL,
    surcharge        FLOAT8       NOT NULL,
    net_payable      BIGINT       NOT NULL,
    paid_amount      BIGINT       NOT NULL,
    assigned_by      BIGINT,
    assigned_at      TIMESTAMPTZ,
    updated_by       BIGINT,
    updated_at       TIMESTAMPTZ,
    student_id       BIGINT       NOT NULL,
    band_id          VARCHAR(255) NOT NULL,
    academic_year_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_fee_assignment_student_year UNIQUE (student_id, academic_year_id),
    CONSTRAINT fk_fee_assignment_student      FOREIGN KEY (student_id)       REFERENCES students (id),
    CONSTRAINT fk_fee_assignment_band         FOREIGN KEY (band_id)           REFERENCES fee_bands (id),
    CONSTRAINT fk_fee_assignment_year         FOREIGN KEY (academic_year_id)  REFERENCES academic_years (id)
);

-- ── payment_records (→ students, fee_assignments) ─────────────

CREATE TABLE IF NOT EXISTS payment_records (
    id             VARCHAR(255) NOT NULL,
    amount         BIGINT       NOT NULL,
    mode           VARCHAR(255),
    notes          TEXT,
    paid_at        TIMESTAMPTZ,
    recorded_by    BIGINT,
    receipt_number VARCHAR(255),
    created_at     TIMESTAMPTZ,
    student_id     BIGINT       NOT NULL,
    assignment_id  VARCHAR(255),
    PRIMARY KEY (id),
    CONSTRAINT fk_payment_student    FOREIGN KEY (student_id)   REFERENCES students (id),
    CONSTRAINT fk_payment_assignment FOREIGN KEY (assignment_id) REFERENCES fee_assignments (id)
);

-- ── attendance_daily (→ school_classes, school_sections, academic_years) ──
-- UK column names from @UniqueConstraint: "attendanceDate", "section_id", "academicYear_id"
-- After naming strategy: attendance_date, section_id, academic_year_id

CREATE TABLE IF NOT EXISTS attendance_daily (
    id               VARCHAR(255) NOT NULL,
    attendance_date  DATE,
    total_enrolled   INTEGER      NOT NULL,
    present_count    INTEGER      NOT NULL,
    absent_count     INTEGER      NOT NULL,
    recorded_by      BIGINT,
    recorded_at      TIMESTAMPTZ,
    updated_by       BIGINT,
    updated_at       TIMESTAMPTZ,
    locked           BOOLEAN      NOT NULL,
    school_class_id  VARCHAR(255) NOT NULL,
    section_id       VARCHAR(255) NOT NULL,
    academic_year_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_attendance_day_section_year UNIQUE (attendance_date, section_id, academic_year_id),
    CONSTRAINT fk_attendance_class            FOREIGN KEY (school_class_id)  REFERENCES school_classes (id),
    CONSTRAINT fk_attendance_section          FOREIGN KEY (section_id)        REFERENCES school_sections (id),
    CONSTRAINT fk_attendance_year             FOREIGN KEY (academic_year_id)  REFERENCES academic_years (id)
);

-- ── staff_members (→ schools) ─────────────────────────────────

CREATE TABLE IF NOT EXISTS staff_members (
    id             BIGSERIAL    NOT NULL,
    name           VARCHAR(255),
    designation    VARCHAR(255),
    department     VARCHAR(255),
    monthly_salary BIGINT       NOT NULL,
    payroll_status VARCHAR(255),
    school_id      BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT fk_staff_school FOREIGN KEY (school_id) REFERENCES schools (id)
);

-- ── catalog_orders (→ schools) ────────────────────────────────

CREATE TABLE IF NOT EXISTS catalog_orders (
    id                         VARCHAR(255) NOT NULL,
    category                   VARCHAR(255) NOT NULL,
    order_data                 TEXT,
    subtotal                   BIGINT       NOT NULL,
    gst                        BIGINT       NOT NULL,
    total_amount               BIGINT       NOT NULL,
    status                     VARCHAR(255),
    class_group                VARCHAR(255),
    logo_on_uniform            VARCHAR(255),
    notebook_cover_logo        VARCHAR(255),
    notebook_delivery_mode     VARCHAR(255),
    notebook_spine_name        VARCHAR(255),
    stationery_pack_type       VARCHAR(255),
    event_name                 VARCHAR(255),
    event_date                 DATE,
    design_status              VARCHAR(255),
    superadmin_approval_status VARCHAR(255),
    required_by_date           DATE,
    estimated_delivery         VARCHAR(255),
    placed_by                  BIGINT,
    placed_at                  TIMESTAMPTZ,
    notes                      VARCHAR(255),
    created_at                 TIMESTAMPTZ,
    school_id                  BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT fk_catalog_order_school FOREIGN KEY (school_id) REFERENCES schools (id)
);

-- ── annual_plan_items (→ schools, academic_years) ─────────────

CREATE TABLE IF NOT EXISTS annual_plan_items (
    id               VARCHAR(255) NOT NULL,
    term_name        VARCHAR(255),
    category         VARCHAR(255),
    description      VARCHAR(255),
    quantity         VARCHAR(255),
    estimated_amount BIGINT       NOT NULL,
    status           VARCHAR(255),
    linked_order_id  VARCHAR(255),
    created_at       TIMESTAMPTZ,
    school_id        BIGINT,
    academic_year_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_annual_plan_school FOREIGN KEY (school_id)        REFERENCES schools (id),
    CONSTRAINT fk_annual_plan_year   FOREIGN KEY (academic_year_id)  REFERENCES academic_years (id)
);

-- ── firefighting_requests (→ schools) ────────────────────────

CREATE TABLE IF NOT EXISTS firefighting_requests (
    code                    VARCHAR(255) NOT NULL,
    title                   VARCHAR(255),
    category                VARCHAR(255),
    urgency                 VARCHAR(255),
    required_by_date        DATE,
    estimated_budget        BIGINT       NOT NULL,
    description             TEXT,
    reference_file_url      VARCHAR(255),
    raised_by               BIGINT,
    status                  VARCHAR(255),
    bursar_note             VARCHAR(255),
    principal_note          VARCHAR(255),
    bursar_approved_at      TIMESTAMPTZ,
    principal_approved_at   TIMESTAMPTZ,
    rejected_by             VARCHAR(255),
    rejected_reason         VARCHAR(255),
    custoking_criteria_json TEXT,
    winner_vendor           VARCHAR(255),
    winner_amount           BIGINT,
    created_at              TIMESTAMPTZ,
    school_id               BIGINT,
    PRIMARY KEY (code),
    CONSTRAINT fk_ff_request_school FOREIGN KEY (school_id) REFERENCES schools (id)
);

-- ── ff_quotations (→ firefighting_requests) ──────────────────
-- isCustoking → is_custoking, isRecommended → is_recommended

CREATE TABLE IF NOT EXISTS ff_quotations (
    id                VARCHAR(255) NOT NULL,
    vendor_name       VARCHAR(255),
    amount            BIGINT       NOT NULL,
    delivery_timeline VARCHAR(255),
    notes             VARCHAR(255),
    document_url      VARCHAR(255),
    is_custoking      BOOLEAN      NOT NULL,
    is_recommended    BOOLEAN      NOT NULL,
    created_at        TIMESTAMPTZ,
    request_id        VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ff_quotation_request FOREIGN KEY (request_id) REFERENCES firefighting_requests (code)
);

-- ── import_rows (→ import_batches) ────────────────────────────

CREATE TABLE IF NOT EXISTS import_rows (
    id              VARCHAR(255) NOT NULL,
    row_no          INTEGER      NOT NULL,
    name            VARCHAR(255),
    class_name      VARCHAR(255),
    section_name    VARCHAR(255),
    admission_no    VARCHAR(255),
    phone           VARCHAR(255),
    status          VARCHAR(255),
    message         TEXT,
    raw_json        TEXT,
    normalized_json TEXT,
    batch_id        VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_import_row_batch FOREIGN KEY (batch_id) REFERENCES import_batches (id)
);
