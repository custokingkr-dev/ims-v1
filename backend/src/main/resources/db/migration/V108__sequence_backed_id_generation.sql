-- Add explicit sequence generators for existing id columns.
-- Handles both IDENTITY columns (DROP IDENTITY first) and plain BIGSERIAL columns.
-- IF EXISTS guards make every step idempotent.

CREATE SEQUENCE IF NOT EXISTS seq_schools           START WITH 1 INCREMENT BY 1;
SELECT setval('seq_schools',           COALESCE((SELECT MAX(id) FROM schools),             0) + 1, false);
ALTER TABLE schools           ALTER COLUMN id DROP IDENTITY IF EXISTS;
ALTER TABLE schools           ALTER COLUMN id SET DEFAULT nextval('seq_schools');
ALTER SEQUENCE seq_schools           OWNED BY schools.id;

CREATE SEQUENCE IF NOT EXISTS seq_app_users         START WITH 1 INCREMENT BY 1;
SELECT setval('seq_app_users',         COALESCE((SELECT MAX(id) FROM app_users),           0) + 1, false);
ALTER TABLE app_users         ALTER COLUMN id DROP IDENTITY IF EXISTS;
ALTER TABLE app_users         ALTER COLUMN id SET DEFAULT nextval('seq_app_users');
ALTER SEQUENCE seq_app_users         OWNED BY app_users.id;

CREATE SEQUENCE IF NOT EXISTS seq_catalog_items     START WITH 1 INCREMENT BY 1;
SELECT setval('seq_catalog_items',     COALESCE((SELECT MAX(id) FROM catalog_items),       0) + 1, false);
ALTER TABLE catalog_items     ALTER COLUMN id DROP IDENTITY IF EXISTS;
ALTER TABLE catalog_items     ALTER COLUMN id SET DEFAULT nextval('seq_catalog_items');
ALTER SEQUENCE seq_catalog_items     OWNED BY catalog_items.id;

CREATE SEQUENCE IF NOT EXISTS seq_annual_plan_entries START WITH 1 INCREMENT BY 1;
SELECT setval('seq_annual_plan_entries', COALESCE((SELECT MAX(id) FROM annual_plan_entries), 0) + 1, false);
ALTER TABLE annual_plan_entries ALTER COLUMN id DROP IDENTITY IF EXISTS;
ALTER TABLE annual_plan_entries ALTER COLUMN id SET DEFAULT nextval('seq_annual_plan_entries');
ALTER SEQUENCE seq_annual_plan_entries OWNED BY annual_plan_entries.id;

CREATE SEQUENCE IF NOT EXISTS seq_students          START WITH 1 INCREMENT BY 1;
SELECT setval('seq_students',          COALESCE((SELECT MAX(id) FROM students),            0) + 1, false);
ALTER TABLE students          ALTER COLUMN id DROP IDENTITY IF EXISTS;
ALTER TABLE students          ALTER COLUMN id SET DEFAULT nextval('seq_students');
ALTER SEQUENCE seq_students          OWNED BY students.id;

CREATE SEQUENCE IF NOT EXISTS seq_staff_members     START WITH 1 INCREMENT BY 1;
SELECT setval('seq_staff_members',     COALESCE((SELECT MAX(id) FROM staff_members),       0) + 1, false);
ALTER TABLE staff_members     ALTER COLUMN id DROP IDENTITY IF EXISTS;
ALTER TABLE staff_members     ALTER COLUMN id SET DEFAULT nextval('seq_staff_members');
ALTER SEQUENCE seq_staff_members     OWNED BY staff_members.id;
