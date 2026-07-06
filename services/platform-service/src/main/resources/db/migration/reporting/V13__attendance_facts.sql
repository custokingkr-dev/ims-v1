-- Fact table projected from attendance.attendance_daily events (attendance-daily.upserted.v1),
-- per Reporting Decoupling SP3. Mirrors reporting.billing_invoice_read (V9) / dim_* (V11)
-- posture exactly: this is a contextless event-consumer projection, so it intentionally stays
-- WITHOUT RLS, matching the sibling posture documented in V8__enable_rls.sql. Rows are upserted
-- idempotently by id so replaying the same or a later event never duplicates state.
CREATE TABLE IF NOT EXISTS fact_attendance_daily (
    id                VARCHAR(255) PRIMARY KEY,
    school_id         BIGINT,
    attendance_date    DATE,
    class_id          VARCHAR(255),
    section_id        VARCHAR(255),
    academic_year_id  VARCHAR(255),
    present_count     INTEGER,
    absent_count      INTEGER,
    late_count        INTEGER,
    leave_count       INTEGER,
    total_enrolled    INTEGER,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_fact_attendance_daily_school_date
    ON fact_attendance_daily (school_id, attendance_date);

CREATE INDEX IF NOT EXISTS idx_fact_attendance_daily_section_date
    ON fact_attendance_daily (section_id, attendance_date);
