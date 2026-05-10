-- Add optional zone assignment fields to app_users for future zone-wise admin scoping.
-- zoneId and zoneName are nullable; initially unused except for OPERATIONS users.
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS zone_id   BIGINT;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS zone_name VARCHAR(255);
