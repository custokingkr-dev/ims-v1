-- Phase 1: nullable school_id on bands + items.
ALTER TABLE fee.fee_bands ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE fee.fee_items ADD COLUMN IF NOT EXISTS school_id BIGINT;

-- Phase 2: copy-per-school. For each (band, school) that has assignments, create a
-- school-owned copy of the band + its items and repoint that school's assignments.
DO $$
DECLARE r RECORD; new_band TEXT;
BEGIN
    FOR r IN
        SELECT DISTINCT band_id, school_id
        FROM fee.fee_assignments
        WHERE school_id IS NOT NULL
    LOOP
        new_band := gen_random_uuid()::text;
        INSERT INTO fee.fee_bands
            (id, name, class_from, class_to, discount, active_schedules_csv,
             created_at, updated_at, academic_year_id, school_id)
        SELECT new_band, name, class_from, class_to, discount, active_schedules_csv,
               created_at, now(), academic_year_id, r.school_id
        FROM fee.fee_bands WHERE id = r.band_id;

        INSERT INTO fee.fee_items
            (id, name, frequency, amount, created_at, updated_at, band_id, school_id)
        SELECT gen_random_uuid()::text, name, frequency, amount, created_at, now(), new_band, r.school_id
        FROM fee.fee_items WHERE band_id = r.band_id;

        UPDATE fee.fee_assignments
           SET band_id = new_band
         WHERE band_id = r.band_id AND school_id = r.school_id;
    END LOOP;

    -- Originals (now unreferenced) and truly-unassigned bands still have school_id IS NULL → drop.
    DELETE FROM fee.fee_items WHERE band_id IN (SELECT id FROM fee.fee_bands WHERE school_id IS NULL);
    DELETE FROM fee.fee_bands WHERE school_id IS NULL;
END $$;

-- Phase 3: enforce NOT NULL + RLS (mirrors fee/V7 policy).
ALTER TABLE fee.fee_bands ALTER COLUMN school_id SET NOT NULL;
ALTER TABLE fee.fee_items ALTER COLUMN school_id SET NOT NULL;

ALTER TABLE fee.fee_bands ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON fee.fee_bands;
CREATE POLICY tenant_isolation ON fee.fee_bands
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE fee.fee_items ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON fee.fee_items;
CREATE POLICY tenant_isolation ON fee.fee_items
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

CREATE INDEX IF NOT EXISTS idx_fee_bands_school_year ON fee.fee_bands (school_id, academic_year_id);
CREATE INDEX IF NOT EXISTS idx_fee_items_school_band ON fee.fee_items (school_id, band_id);
