CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE tenant_school.schools
    ADD COLUMN IF NOT EXISTS school_uid UUID;

UPDATE tenant_school.schools
SET school_uid = gen_random_uuid()
WHERE school_uid IS NULL;

ALTER TABLE tenant_school.schools
    ALTER COLUMN school_uid SET DEFAULT gen_random_uuid();

ALTER TABLE tenant_school.schools
    ALTER COLUMN school_uid SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_school_school_uid
    ON tenant_school.schools (school_uid);
