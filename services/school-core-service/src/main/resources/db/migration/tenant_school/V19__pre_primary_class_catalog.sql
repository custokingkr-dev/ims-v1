-- Add pre-primary classes ahead of numeric classes while preserving existing
-- schools' active numeric class coverage.

UPDATE tenant_school.school_classes
SET sort_order = sort_order + 3
WHERE sort_order BETWEEN 1 AND 12
  AND lower(id) NOT IN ('pre-primary', 'lkg', 'ukg')
  AND (
      id ~ '^[0-9]+$'
      OR name ~* '^(class[[:space:]]*)?[0-9]+$'
  );

INSERT INTO tenant_school.school_classes (id, name, sort_order)
VALUES
    ('pre-primary', 'Nursery / Pre-Nursery / Playgroup', 1),
    ('lkg', 'LKG (Lower Kindergarten)', 2),
    ('ukg', 'UKG (Upper Kindergarten)', 3)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    sort_order = EXCLUDED.sort_order;

UPDATE tenant_school.schools
SET configured_class_count = LEAST(COALESCE(configured_class_count, 12) + 3, 15)
WHERE configured_class_count IS NULL OR configured_class_count <= 12;

WITH ranked_classes AS (
    SELECT
        id,
        name,
        sort_order,
        row_number() OVER (ORDER BY sort_order, name) AS class_rank
    FROM tenant_school.school_classes
),
schools_to_backfill AS (
    SELECT
        id AS school_id,
        COALESCE(configured_class_count, 15) AS class_count,
        COALESCE(configured_section_count, 2) AS section_count
    FROM tenant_school.schools
),
target_sections AS (
    SELECT
        s.school_id,
        c.id AS class_id,
        c.name AS class_name,
        chr(64 + section_no)::text AS section_name
    FROM schools_to_backfill s
    JOIN ranked_classes c
      ON c.class_rank <= LEAST(s.class_count, 15)
    CROSS JOIN LATERAL generate_series(1, LEAST(s.section_count, 26)) AS section_no
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

INSERT INTO tenant_school.outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
SELECT 'SchoolSectionUpserted:' || ss.id,
       'school-section.upserted.v1',
       'SchoolSection',
       ss.id,
       ss.school_id,
       jsonb_build_object(
           'id', ss.id,
           'name', ss.name,
           'schoolId', ss.school_id,
           'classId', ss.school_class_id,
           'className', sc.name,
           'active', ss.active,
           'teacherName', ss.teacher_name
       )
FROM tenant_school.school_sections ss
JOIN tenant_school.school_classes sc ON sc.id = ss.school_class_id
WHERE ss.active = true
  AND ss.school_class_id IN ('pre-primary', 'lkg', 'ukg');

DO $$
BEGIN
    IF to_regclass('public.school_classes') IS NOT NULL THEN
        UPDATE public.school_classes
        SET sort_order = sort_order + 3
        WHERE sort_order BETWEEN 1 AND 12
          AND lower(id) NOT IN ('pre-primary', 'lkg', 'ukg')
          AND (
              id ~ '^[0-9]+$'
              OR name ~* '^(class[[:space:]]*)?[0-9]+$'
          );

        INSERT INTO public.school_classes (id, name, sort_order)
        VALUES
            ('pre-primary', 'Nursery / Pre-Nursery / Playgroup', 1),
            ('lkg', 'LKG (Lower Kindergarten)', 2),
            ('ukg', 'UKG (Upper Kindergarten)', 3)
        ON CONFLICT (id) DO UPDATE SET
            name = EXCLUDED.name,
            sort_order = EXCLUDED.sort_order;
    END IF;
END $$;
