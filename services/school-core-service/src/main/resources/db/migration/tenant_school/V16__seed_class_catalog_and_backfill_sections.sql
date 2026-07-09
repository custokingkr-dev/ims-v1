-- Greenfield databases must have the global class catalogue before school
-- structure generation can create per-school sections. Older environments had
-- these rows from legacy public tables; fresh prod did not.

INSERT INTO tenant_school.school_classes (id, name, sort_order)
SELECT value::text, value::text, value
FROM generate_series(1, 12) AS value
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    sort_order = EXCLUDED.sort_order;

WITH schools_to_backfill AS (
    SELECT
        id AS school_id,
        COALESCE(configured_class_count, 12) AS class_count,
        COALESCE(configured_section_count, 2) AS section_count
    FROM tenant_school.schools
),
target_classes AS (
    SELECT
        s.school_id,
        c.id AS class_id,
        c.name AS class_name,
        c.sort_order,
        s.section_count
    FROM schools_to_backfill s
    JOIN tenant_school.school_classes c
      ON c.sort_order <= LEAST(s.class_count, 12)
),
target_sections AS (
    SELECT
        school_id,
        class_id,
        class_name,
        chr(64 + section_no)::text AS section_name
    FROM target_classes
    CROSS JOIN LATERAL generate_series(1, LEAST(section_count, 26)) AS section_no
)
INSERT INTO tenant_school.school_sections (
    id, school_id, school_class_id, name, teacher_name, active
)
SELECT
    school_id || '-' || class_id || '-' || section_name,
    school_id,
    class_id,
    section_name,
    '',
    true
FROM target_sections
ON CONFLICT (id) DO UPDATE SET
    school_id = EXCLUDED.school_id,
    school_class_id = EXCLUDED.school_class_id,
    name = EXCLUDED.name,
    active = true;

DO $$
BEGIN
    IF to_regclass('public.school_classes') IS NOT NULL THEN
        INSERT INTO public.school_classes (id, name, sort_order)
        SELECT value::text, value::text, value
        FROM generate_series(1, 12) AS value
        ON CONFLICT (id) DO UPDATE SET
            name = EXCLUDED.name,
            sort_order = EXCLUDED.sort_order;
    END IF;
END $$;
