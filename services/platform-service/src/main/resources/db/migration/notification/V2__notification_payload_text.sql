ALTER TABLE notification_inbox_events
    ALTER COLUMN payload TYPE TEXT USING payload::TEXT;
