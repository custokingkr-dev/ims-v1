-- Reference dimension tables projected from tenant_school (school-core) outbox events
-- (school.upserted.v1, school-section.upserted.v1, academic-year.upserted.v1), per
-- Reporting Decoupling SP1. Mirrors reporting.billing_invoice_read (V9) posture exactly:
-- these are contextless event-consumer projections, so they intentionally stay WITHOUT
-- RLS, matching the sibling posture documented in V8__enable_rls.sql.
CREATE TABLE IF NOT EXISTS dim_school (
    id         BIGINT PRIMARY KEY,
    name       VARCHAR(255),
    short_code VARCHAR(255),
    city       VARCHAR(255),
    state      VARCHAR(255),
    active     BOOLEAN,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS dim_section (
    id           VARCHAR(255) PRIMARY KEY,
    name         VARCHAR(255),
    school_id    BIGINT,
    class_id     VARCHAR(255),
    class_name   VARCHAR(255),
    active       BOOLEAN,
    teacher_name VARCHAR(255),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS dim_academic_year (
    id         VARCHAR(255) PRIMARY KEY,
    label      VARCHAR(255),
    active     BOOLEAN,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_dim_section_school_id ON dim_section (school_id);
