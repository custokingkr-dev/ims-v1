-- Best-effort cleanup for legacy MySQL databases before Hibernate ddl-auto=update runs.
-- This file is intentionally idempotent and allowed to continue on error so fresh databases still boot.

INSERT INTO schools (name, short_code, city, state, contact_email, contact_phone, active, created_at)
SELECT 'Custoking Demo School', 'DEMO', 'Hyderabad', 'Telangana', NULL, NULL, 1, NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM schools WHERE LOWER(short_code) = 'demo'
);

SET @demo_school_id := (
    SELECT id FROM schools WHERE LOWER(short_code) = 'demo' ORDER BY id LIMIT 1
);

-- Legacy rows may have 0000-00-00 timestamps that block ALTER TABLE ... NOT NULL.
UPDATE firefighting_requests
SET created_at = NOW()
WHERE created_at IS NULL
   OR CAST(created_at AS CHAR(19)) = '0000-00-00 00:00:00';

-- Backfill orphaned or missing school links before Hibernate adds/updates the FK.
UPDATE firefighting_requests
SET school_id = @demo_school_id
WHERE school_id IS NULL
   OR school_id NOT IN (SELECT id FROM schools);


-- Remove deprecated Aadhaar and split-name columns from students if they exist.
ALTER TABLE students DROP INDEX uk_student_aadhar_hash;
ALTER TABLE students DROP COLUMN aadhar_hash;
ALTER TABLE students DROP COLUMN aadhar_encrypted;
ALTER TABLE students DROP COLUMN aadhar_masked;
ALTER TABLE students DROP COLUMN first_name;
ALTER TABLE students DROP COLUMN last_name;

-- Persist school ownership directly on each student for cleaner multi-school storage.
ALTER TABLE students ADD COLUMN school_id BIGINT NULL;
UPDATE students s
LEFT JOIN school_sections sec ON sec.id = s.section_id
SET s.school_id = sec.school_id
WHERE s.school_id IS NULL;
