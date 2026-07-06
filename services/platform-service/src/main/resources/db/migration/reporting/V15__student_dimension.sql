-- reporting.dim_student projected from school-core (student.upserted.v1) outbox events, per
-- Reporting Decoupling SP5. Mirrors reporting.dim_school / dim_section (V11) posture exactly:
-- a contextless event-consumer projection, so it intentionally stays WITHOUT RLS, matching the
-- sibling posture documented in V8__enable_rls.sql.
CREATE TABLE IF NOT EXISTS dim_student (
    id             BIGINT PRIMARY KEY,
    school_id      BIGINT,
    admission_no   VARCHAR(255),
    full_name      VARCHAR(255),
    roll_no        VARCHAR(255),
    class_id       VARCHAR(255),
    section_id     VARCHAR(255),
    parent_contact VARCHAR(255),
    phone          VARCHAR(255),
    active         BOOLEAN,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_dim_student_school_id ON dim_student (school_id);
CREATE INDEX IF NOT EXISTS idx_dim_student_section_id ON dim_student (section_id);
