CREATE TABLE IF NOT EXISTS student_review_campaigns (
    id               VARCHAR(36)   PRIMARY KEY,
    school_id        BIGINT        NOT NULL REFERENCES schools(id),
    academic_year_id VARCHAR(36)   REFERENCES academic_years(id),
    review_type      VARCHAR(30)   NOT NULL,
    title            VARCHAR(200)  NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    verifier         VARCHAR(20),
    initiated_by     BIGINT        REFERENCES app_users(id),
    initiated_at     TIMESTAMPTZ,
    due_date         DATE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS student_review_items (
    id                    VARCHAR(36)   PRIMARY KEY,
    campaign_id           VARCHAR(36)   NOT NULL REFERENCES student_review_campaigns(id),
    student_id            BIGINT        NOT NULL REFERENCES students(id),
    school_id             BIGINT        NOT NULL REFERENCES schools(id),
    assigned_to_user_id   BIGINT        REFERENCES app_users(id),
    status                VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    verified_photo        BOOLEAN       NOT NULL DEFAULT FALSE,
    verified_full_name    BOOLEAN       NOT NULL DEFAULT FALSE,
    verified_admission_no BOOLEAN       NOT NULL DEFAULT FALSE,
    verified_class_section BOOLEAN      NOT NULL DEFAULT FALSE,
    verified_roll_no      BOOLEAN       NOT NULL DEFAULT FALSE,
    verified_father_name  BOOLEAN       NOT NULL DEFAULT FALSE,
    verified_father_contact BOOLEAN     NOT NULL DEFAULT FALSE,
    verified_address      BOOLEAN       NOT NULL DEFAULT FALSE,
    verified_blood_group  BOOLEAN       NOT NULL DEFAULT FALSE,
    current_full_name     VARCHAR(200),
    suggested_full_name   VARCHAR(200),
    parent_confirmed      BOOLEAN       NOT NULL DEFAULT FALSE,
    teacher_confirmed     BOOLEAN       NOT NULL DEFAULT FALSE,
    correction_requested  BOOLEAN       NOT NULL DEFAULT FALSE,
    correction_notes      TEXT,
    completed_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_campaign_student UNIQUE (campaign_id, student_id)
);

CREATE INDEX idx_review_campaigns_school  ON student_review_campaigns (school_id, review_type, status);
CREATE INDEX idx_review_items_campaign    ON student_review_items (campaign_id);
CREATE INDEX idx_review_items_school      ON student_review_items (school_id, status);
