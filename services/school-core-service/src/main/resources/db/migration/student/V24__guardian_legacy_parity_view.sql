-- The V14 migration already normalized and linked legacy father/mother fields.
-- Keep the compatibility columns while old exports/readers exist, but expose exact
-- parity evidence so their eventual removal is gated by data rather than assumption.

CREATE OR REPLACE VIEW student.guardian_legacy_parity
WITH (security_invoker = true)
AS
SELECT
    student_row.id AS student_id,
    student_row.school_id,
    student_row.father_name AS legacy_father_name,
    student_row.father_contact AS legacy_father_contact,
    student_row.mother_name AS legacy_mother_name,
    normalized.father_name AS normalized_father_name,
    normalized.father_contact AS normalized_father_contact,
    normalized.mother_name AS normalized_mother_name,
    NULLIF(btrim(COALESCE(student_row.father_name, '')), '')
        IS NOT DISTINCT FROM NULLIF(btrim(COALESCE(normalized.father_name, '')), '')
        AS father_name_matches,
    regexp_replace(COALESCE(student_row.father_contact, ''), '[^0-9]', '', 'g')
        IS NOT DISTINCT FROM regexp_replace(COALESCE(normalized.father_contact, ''), '[^0-9]', '', 'g')
        AS father_contact_matches,
    NULLIF(btrim(COALESCE(student_row.mother_name, '')), '')
        IS NOT DISTINCT FROM NULLIF(btrim(COALESCE(normalized.mother_name, '')), '')
        AS mother_name_matches
FROM student.students student_row
LEFT JOIN LATERAL (
    SELECT
        (SELECT guardian.full_name
         FROM student.student_guardians link
         JOIN student.guardians guardian ON guardian.id = link.guardian_id
         WHERE link.student_id = student_row.id
           AND link.relationship = 'FATHER'
           AND guardian.status = 'ACTIVE'
         ORDER BY link.is_primary DESC, link.updated_at DESC, link.id
         LIMIT 1) AS father_name,
        (SELECT guardian.phone
         FROM student.student_guardians link
         JOIN student.guardians guardian ON guardian.id = link.guardian_id
         WHERE link.student_id = student_row.id
           AND link.relationship = 'FATHER'
           AND guardian.status = 'ACTIVE'
         ORDER BY link.is_primary DESC, link.updated_at DESC, link.id
         LIMIT 1) AS father_contact,
        (SELECT guardian.full_name
         FROM student.student_guardians link
         JOIN student.guardians guardian ON guardian.id = link.guardian_id
         WHERE link.student_id = student_row.id
           AND link.relationship = 'MOTHER'
           AND guardian.status = 'ACTIVE'
         ORDER BY link.is_primary DESC, link.updated_at DESC, link.id
         LIMIT 1) AS mother_name
) normalized ON TRUE;

REVOKE ALL ON TABLE student.guardian_legacy_parity FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        REVOKE ALL ON TABLE student.guardian_legacy_parity FROM app_rt;
    END IF;
END
$$;
