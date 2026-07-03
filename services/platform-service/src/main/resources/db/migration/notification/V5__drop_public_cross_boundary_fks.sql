ALTER TABLE notification.notification_broadcasts
    DROP CONSTRAINT IF EXISTS notification_broadcasts_school_id_fkey,
    DROP CONSTRAINT IF EXISTS notification_broadcasts_created_by_fkey,
    DROP CONSTRAINT IF EXISTS notification_broadcasts_approved_by_fkey,
    DROP CONSTRAINT IF EXISTS notification_broadcasts_sent_by_fkey;

ALTER TABLE notification.notification_logs
    DROP CONSTRAINT IF EXISTS notification_logs_school_id_fkey,
    DROP CONSTRAINT IF EXISTS notification_logs_student_id_fkey,
    DROP CONSTRAINT IF EXISTS notification_logs_sent_by_fkey;
