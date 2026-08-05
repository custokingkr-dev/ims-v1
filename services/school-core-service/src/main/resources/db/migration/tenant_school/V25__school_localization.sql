ALTER TABLE tenant_school.schools
    ADD COLUMN IF NOT EXISTS country_code VARCHAR(2) NOT NULL DEFAULT 'IN',
    ADD COLUMN IF NOT EXISTS locale VARCHAR(35) NOT NULL DEFAULT 'en-IN',
    ADD COLUMN IF NOT EXISTS currency_code VARCHAR(3) NOT NULL DEFAULT 'INR',
    ADD COLUMN IF NOT EXISTS phone_region VARCHAR(2) NOT NULL DEFAULT 'IN';

ALTER TABLE tenant_school.schools
    ADD CONSTRAINT ck_schools_country_code CHECK (country_code ~ '^[A-Z]{2}$'),
    ADD CONSTRAINT ck_schools_currency_code CHECK (currency_code ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT ck_schools_phone_region CHECK (phone_region ~ '^[A-Z]{2}$');

