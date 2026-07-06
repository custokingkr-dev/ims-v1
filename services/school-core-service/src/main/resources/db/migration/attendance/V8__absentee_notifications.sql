CREATE TABLE attendance.absentee_notifications (
    id                VARCHAR(255) PRIMARY KEY,
    school_id         BIGINT       NOT NULL,
    student_id        BIGINT       NOT NULL,
    class_id          VARCHAR(255) NOT NULL,
    section_id        VARCHAR(255) NOT NULL,
    academic_year_id  VARCHAR(255) NOT NULL,
    attendance_date   DATE         NOT NULL,
    parent_contact    VARCHAR(255) NOT NULL,
    channel           VARCHAR(20)  NOT NULL DEFAULT 'WHATSAPP',
    message           TEXT         NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    queued_by         BIGINT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_absentee_notification UNIQUE (student_id, attendance_date)
);
CREATE INDEX idx_absentee_notif_school_date ON attendance.absentee_notifications (school_id, attendance_date);

ALTER TABLE attendance.absentee_notifications ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON attendance.absentee_notifications;
CREATE POLICY tenant_isolation ON attendance.absentee_notifications
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');
