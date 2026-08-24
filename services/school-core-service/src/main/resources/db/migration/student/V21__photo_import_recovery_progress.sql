ALTER TABLE student.photo_import_recoveries
    DROP CONSTRAINT IF EXISTS chk_photo_import_recovery_status;

-- A newer/manual student photo is a successful safety decision, not an infrastructure failure.
-- Reclassify the protection decisions recorded by the first recovery release so historical
-- progress remains accurate after this migration.
UPDATE student.photo_import_recoveries
SET status = 'PROTECTED',
    updated_at = now()
WHERE status = 'FAILED'
  AND message LIKE 'Student photo changed%';

ALTER TABLE student.photo_import_recoveries
    ADD CONSTRAINT chk_photo_import_recovery_status
    CHECK (status IN ('EXECUTING', 'COMPLETED', 'PROTECTED', 'FAILED'));
