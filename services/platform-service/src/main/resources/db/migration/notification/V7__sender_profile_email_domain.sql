ALTER TABLE notification.notification_sender_profiles
    ADD COLUMN IF NOT EXISTS email_domain VARCHAR(255);

UPDATE notification.notification_sender_profiles
SET email_domain = 'custoking.com'
WHERE school_id IS NULL
  AND active = TRUE
  AND email_domain IS NULL
  AND email_from_address = 'support@custoking.com';
