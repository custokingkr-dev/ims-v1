-- Keep the global class catalog order deterministic after the pre-primary rollout.
-- Some seed paths can re-upsert numeric class 1 with sort_order=1; normalize it
-- back behind pre-primary, LKG, and UKG without changing class IDs.

UPDATE tenant_school.school_classes
SET sort_order = CASE
    WHEN lower(id) = 'pre-primary' THEN 1
    WHEN lower(id) = 'lkg' THEN 2
    WHEN lower(id) = 'ukg' THEN 3
    WHEN id ~ '^[0-9]+$' AND id::integer BETWEEN 1 AND 12 THEN id::integer + 3
    WHEN lower(id) ~ '^c[0-9]+$'
        AND regexp_replace(lower(id), '^c', '')::integer BETWEEN 1 AND 12
        THEN regexp_replace(lower(id), '^c', '')::integer + 3
    WHEN name ~* '^(class[[:space:]]*)?[0-9]+$'
        AND regexp_replace(lower(name), '[^0-9]', '', 'g')::integer BETWEEN 1 AND 12
        THEN regexp_replace(lower(name), '[^0-9]', '', 'g')::integer + 3
    ELSE sort_order
END
WHERE lower(id) IN ('pre-primary', 'lkg', 'ukg')
   OR (id ~ '^[0-9]+$' AND id::integer BETWEEN 1 AND 12)
   OR (lower(id) ~ '^c[0-9]+$' AND regexp_replace(lower(id), '^c', '')::integer BETWEEN 1 AND 12)
   OR (name ~* '^(class[[:space:]]*)?[0-9]+$'
       AND regexp_replace(lower(name), '[^0-9]', '', 'g')::integer BETWEEN 1 AND 12);

WITH ranked_classes AS (
    SELECT
        id,
        name,
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
    active = EXCLUDED.active;

DO $$
BEGIN
    IF to_regclass('public.school_classes') IS NOT NULL THEN
        UPDATE public.school_classes
        SET sort_order = CASE
            WHEN lower(id) = 'pre-primary' THEN 1
            WHEN lower(id) = 'lkg' THEN 2
            WHEN lower(id) = 'ukg' THEN 3
            WHEN id ~ '^[0-9]+$' AND id::integer BETWEEN 1 AND 12 THEN id::integer + 3
            WHEN lower(id) ~ '^c[0-9]+$'
                AND regexp_replace(lower(id), '^c', '')::integer BETWEEN 1 AND 12
                THEN regexp_replace(lower(id), '^c', '')::integer + 3
            WHEN name ~* '^(class[[:space:]]*)?[0-9]+$'
                AND regexp_replace(lower(name), '[^0-9]', '', 'g')::integer BETWEEN 1 AND 12
                THEN regexp_replace(lower(name), '[^0-9]', '', 'g')::integer + 3
            ELSE sort_order
        END
        WHERE lower(id) IN ('pre-primary', 'lkg', 'ukg')
           OR (id ~ '^[0-9]+$' AND id::integer BETWEEN 1 AND 12)
           OR (lower(id) ~ '^c[0-9]+$' AND regexp_replace(lower(id), '^c', '')::integer BETWEEN 1 AND 12)
           OR (name ~* '^(class[[:space:]]*)?[0-9]+$'
               AND regexp_replace(lower(name), '[^0-9]', '', 'g')::integer BETWEEN 1 AND 12);
    END IF;
END $$;
