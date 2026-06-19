-- V130: Notification logs for parent/student communications from the dashboard.
-- Tracks every SMS/WhatsApp/email/push notification sent from command-center actions.

CREATE TABLE IF NOT EXISTS notification_logs (
    id                VARCHAR(36)  PRIMARY KEY,
    school_id         BIGINT       REFERENCES schools(id),
    student_id        BIGINT       REFERENCES students(id),
    parent_contact    VARCHAR(200),
    channel           VARCHAR(30)  NOT NULL,
    notification_type VARCHAR(80)  NOT NULL,
    message           TEXT,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    sent_by           BIGINT       REFERENCES app_users(id),
    sent_at           TIMESTAMPTZ,
    failure_reason    TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_notif_logs_school_type   ON notification_logs(school_id, notification_type);
CREATE INDEX IF NOT EXISTS idx_notif_logs_student       ON notification_logs(student_id);
CREATE INDEX IF NOT EXISTS idx_notif_logs_status        ON notification_logs(status);
CREATE INDEX IF NOT EXISTS idx_notif_logs_created       ON notification_logs(created_at DESC);
