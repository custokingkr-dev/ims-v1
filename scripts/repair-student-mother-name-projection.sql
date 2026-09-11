-- Audit + repair for students whose legacy mother_name projection was wiped by the
-- LegacyGuardianSynchronizer defect fixed in fix(students): stop the guardian sync wiping
-- mother_name on a combined edit (2026-09-11).
--
-- Shape of the damage: a save that changed the father AND added the mother refreshed
-- student.students.{father_name,father_contact,mother_name} from the guardian ledger before the
-- MOTHER row existed. The ledger holds the mother (an ACTIVE guardian linked with
-- relationship = 'MOTHER'); the students row -- read by the edit modal and the student export --
-- holds NULL/blank. reporting.dim_student does not carry mother_name, so no outbox replay is
-- needed after the repair.
--
-- DO NOT RUN THIS AGAINST PRODUCTION FROM A WORKSTATION. Production database access is a
-- governed, separately-approved operation. Section 1 is read-only evidence and is shaped for
-- the sanctioned Cloud Run psql runners in scripts/invoke-*-cloudsql.ps1 (BEGIN READ ONLY,
-- aggregate SELECTs, \echo/\pset only). Section 2 is a WRITE and is deliberately refused by the
-- read-only evidence runner (it rejects any UPDATE); it must only be executed under an approval
-- reference, with the expected row count pinned from a fresh run of section 1.
--
-- NOTE for operators: the invoke-*-cloudsql.ps1 runners capture native output with `2>&1`,
-- which under Windows PowerShell 5.1 wraps stderr lines in NativeCommandError records and can
-- flip $? even on exit code 0. Drive them from Bash (pwsh/bash), not from powershell.exe.

\pset pager off

-- ============================================================================================
-- SECTION 1 -- READ-ONLY EVIDENCE (safe to run through the read-only evidence runner)
-- ============================================================================================
-- The derivation below is V24's guardian_legacy_parity rule, inlined so the audit and the
-- repair select exactly the same rows regardless of the view's grants.

\echo 'mother_name projection wiped: count'
SELECT count(*) AS students_missing_mother_projection
FROM student.students s
WHERE s.deleted_at IS NULL
  AND NULLIF(btrim(COALESCE(s.mother_name, '')), '') IS NULL
  AND EXISTS (
      SELECT 1
      FROM student.student_guardians link
      JOIN student.guardians guardian ON guardian.id = link.guardian_id
      WHERE link.student_id = s.id
        AND link.relationship = 'MOTHER'
        AND guardian.status = 'ACTIVE'
        AND NULLIF(btrim(COALESCE(guardian.full_name, '')), '') IS NOT NULL
  );

\echo 'mother_name projection wiped: cross-check against student.guardian_legacy_parity (must equal the count above)'
SELECT count(*) AS parity_view_mother_missing_on_legacy_side
FROM student.guardian_legacy_parity parity
JOIN student.students s ON s.id = parity.student_id
WHERE s.deleted_at IS NULL   -- the view itself does not exclude soft-deleted students
  AND NOT parity.mother_name_matches
  AND NULLIF(btrim(COALESCE(parity.legacy_mother_name, '')), '') IS NULL
  AND NULLIF(btrim(COALESCE(parity.normalized_mother_name, '')), '') IS NOT NULL;

\echo 'mother_name projection wiped: rows'
SELECT s.id AS student_id,
       s.school_id,
       s.admission_no,
       s.mother_name AS legacy_mother_name,
       mother.guardian_id,
       mother.full_name AS normalized_mother_name,
       mother.link_updated_at,
       s.updated_at AS student_updated_at,
       s.updated_by AS student_updated_by
FROM student.students s
JOIN LATERAL (
    SELECT guardian.id AS guardian_id, guardian.full_name, link.updated_at AS link_updated_at
    FROM student.student_guardians link
    JOIN student.guardians guardian ON guardian.id = link.guardian_id
    WHERE link.student_id = s.id
      AND link.relationship = 'MOTHER'
      AND guardian.status = 'ACTIVE'
    ORDER BY link.is_primary DESC, link.updated_at DESC, link.id
    LIMIT 1
) mother ON TRUE
WHERE s.deleted_at IS NULL
  AND NULLIF(btrim(COALESCE(s.mother_name, '')), '') IS NULL
  AND NULLIF(btrim(COALESCE(mother.full_name, '')), '') IS NOT NULL
ORDER BY s.school_id, s.id;

-- Mirror shape of the same defect (father changed + mother REMOVED in one save): the early
-- refresh resurrected the old mother name on the students row after her guardian was unlinked.
-- Listed for awareness only; the repair below does NOT touch these -- each needs a human
-- decision (the operator intended removal, so the fix is to blank the projection, but confirm).
\echo 'mother_name resurrected without an ACTIVE MOTHER guardian (mirror shape, not repaired here)'
SELECT s.id AS student_id, s.school_id, s.admission_no, s.mother_name AS legacy_mother_name,
       s.updated_at AS student_updated_at
FROM student.students s
WHERE s.deleted_at IS NULL
  AND NULLIF(btrim(COALESCE(s.mother_name, '')), '') IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM student.student_guardians link
      JOIN student.guardians guardian ON guardian.id = link.guardian_id
      WHERE link.student_id = s.id
        AND link.relationship = 'MOTHER'
        AND guardian.status = 'ACTIVE'
  )
ORDER BY s.school_id, s.id;

-- ============================================================================================
-- SECTION 2 -- WRITE: REPAIR. DO NOT RUN WITHOUT APPROVAL.
-- ============================================================================================
-- Re-derives students.mother_name from the ACTIVE MOTHER guardian for exactly the rows section 1
-- counts. Idempotent: a second run matches zero rows. Mirrors the synchronizer's own projection
-- refresh (V24 ordering; bumps updated_at and version; leaves updated_by alone so the audit trail
-- still shows who last edited the profile). Guardian rows, links and consent events are untouched.
--
-- Invocation (only under an approved change, from the sanctioned job runner shape):
--   psql -v ON_ERROR_STOP=1 \
--        -v approval_reference='<approval ticket / change reference>' \
--        -v expected_rows=<count printed by section 1, from a run made minutes earlier> \
--        -f scripts/repair-student-mother-name-projection.sql
-- Without approval_reference the file is still safe to run: section 1 executes and section 2
-- quits (exit 0) before BEGIN. With approval_reference but a missing/blank/stale expected_rows
-- the write is refused with a raised error, so psql exits non-zero under ON_ERROR_STOP.
-- (psql's \quit takes no exit-code argument; a raised exception is the only reliable way to
-- make a refusal non-zero.)

\set ON_ERROR_STOP on

\if :{?approval_reference}
\else
    \echo 'Repair section skipped: no approval_reference supplied (read-only evidence above is complete).'
    \quit
\endif
\if :{?expected_rows}
\else
    DO $$ BEGIN RAISE EXCEPTION 'Repair refused: expected_rows was not pinned from a fresh section-1 run.'; END $$;
\endif

SELECT length(btrim(:'approval_reference')) > 0 AS approval_reference_present \gset
\if :approval_reference_present
\else
    DO $$ BEGIN RAISE EXCEPTION 'Repair refused: approval_reference is blank.'; END $$;
\endif

SELECT count(*) = :expected_rows::integer AS expected_rows_match
FROM student.students s
WHERE s.deleted_at IS NULL
  AND NULLIF(btrim(COALESCE(s.mother_name, '')), '') IS NULL
  AND EXISTS (
      SELECT 1
      FROM student.student_guardians link
      JOIN student.guardians guardian ON guardian.id = link.guardian_id
      WHERE link.student_id = s.id
        AND link.relationship = 'MOTHER'
        AND guardian.status = 'ACTIVE'
        AND NULLIF(btrim(COALESCE(guardian.full_name, '')), '') IS NOT NULL
  ) \gset
\if :expected_rows_match
\else
    DO $$ BEGIN RAISE EXCEPTION 'Repair refused: the affected-row count changed since expected_rows was pinned. Re-run section 1.'; END $$;
\endif

BEGIN;

\echo 'repairing students.mother_name from the ACTIVE MOTHER guardian'
WITH mother_values AS (
    SELECT s.id,
           (SELECT guardian.full_name
            FROM student.student_guardians link
            JOIN student.guardians guardian ON guardian.id = link.guardian_id
            WHERE link.student_id = s.id
              AND link.relationship = 'MOTHER'
              AND guardian.status = 'ACTIVE'
            ORDER BY link.is_primary DESC, link.updated_at DESC, link.id
            LIMIT 1) AS mother_name
    FROM student.students s
    WHERE s.deleted_at IS NULL
      AND NULLIF(btrim(COALESCE(s.mother_name, '')), '') IS NULL
)
UPDATE student.students s
SET mother_name = m.mother_name,
    updated_at = now(),
    version = version + 1
FROM mother_values m
WHERE s.id = m.id
  AND NULLIF(btrim(COALESCE(m.mother_name, '')), '') IS NOT NULL
  AND NULLIF(btrim(COALESCE(s.mother_name, '')), '') IS NULL
RETURNING s.id AS student_id, s.school_id, s.admission_no, s.mother_name AS repaired_mother_name;

\echo 'post-repair: remaining rows with a wiped mother_name projection (must be 0)'
SELECT count(*) AS remaining
FROM student.students s
WHERE s.deleted_at IS NULL
  AND NULLIF(btrim(COALESCE(s.mother_name, '')), '') IS NULL
  AND EXISTS (
      SELECT 1
      FROM student.student_guardians link
      JOIN student.guardians guardian ON guardian.id = link.guardian_id
      WHERE link.student_id = s.id
        AND link.relationship = 'MOTHER'
        AND guardian.status = 'ACTIVE'
        AND NULLIF(btrim(COALESCE(guardian.full_name, '')), '') IS NOT NULL
  );

COMMIT;

\echo 'repair committed under approval reference:' :'approval_reference'
