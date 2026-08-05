ALTER TABLE tenant_school.schools
    ADD COLUMN IF NOT EXISTS time_zone VARCHAR(63);

UPDATE tenant_school.schools
SET time_zone = 'Asia/Kolkata'
WHERE time_zone IS NULL OR btrim(time_zone) = '';

ALTER TABLE tenant_school.schools
    ALTER COLUMN time_zone SET DEFAULT 'Asia/Kolkata',
    ALTER COLUMN time_zone SET NOT NULL;
