-- Item order workflows (design v1, "Contract release: retiring the rollout bridges"): retire the overwritten stage note of
-- the notebook tracker. Since V20 a stage note is an entry in workflow_internal_notes; V20 copied every saved note there
-- once, and V21's trigger copied each one the tracker's revision still saved during the rollout. No revision that can
-- serve writes or reads workflow_instance_steps.stage_note any more: ed8aeba5 and later never name it, and rolling
-- operations back below ed8aeba5 (which selects it in every step read) is unsupported from this release on. So:
--   1. the copy of V20 and V21 runs once more, for anything written since with V21's trigger bypassed;
--   2. a note that is still in no entry stops the migration and is named, as V19 and V21 stop on what they cannot map:
--      dropping it silently would lose it. V20 left out notes with no recorded author, so such a note lands here;
--   3. V21's trigger and its function go, then the three columns.
-- On a database where the table was created empty in this same job (prod's first release with the order workflows),
-- 1 and 2 find nothing.
-- Owner backfills and checks on forced-RLS tables need the deliberate bypass (V7): without it the owner sees no row and
-- the check below would pass vacuously.
SET LOCAL app.bypass_rls = 'on';
SET LOCAL lock_timeout = '15s';

-- Locks. Dropping the trigger and the columns needs ACCESS EXCLUSIVE on workflow_instance_steps, which every list and
-- detail read of the serving revision touches. A plain LOCK would queue those reads behind the wait, so the lock is taken
-- with NOWAIT and tried again for up to 15 seconds; then the migration stops, changing nothing, and the migration job can
-- simply be re-run. Taken first, so nothing writes a note between the copy and the check, and held for the milliseconds
-- the rest needs.
DO $$
BEGIN
    FOR attempt IN 1..150 LOOP
        BEGIN
            LOCK TABLE workflow.workflow_instance_steps IN ACCESS EXCLUSIVE MODE NOWAIT;
            RETURN;
        EXCEPTION WHEN lock_not_available THEN
            PERFORM pg_sleep(0.1);
        END;
    END LOOP;
    RAISE EXCEPTION 'V22: workflow_instance_steps stayed busy for 15 seconds; nothing was changed, re-run the migration job';
END $$;

-- 1. Stage notes saved since V21 copied them, each once, as V20 and V21 copy them (V21 step 3a, verbatim).
INSERT INTO workflow.workflow_internal_notes (instance_id, school_id, author_user_id, body, created_at, step_order)
SELECT s.instance_id, s.school_id, s.stage_note_saved_by, s.stage_note, COALESCE(s.stage_note_saved_at, now()), s.step_order
FROM workflow.workflow_instance_steps s
WHERE s.stage_note IS NOT NULL
  AND btrim(s.stage_note) <> ''
  AND s.stage_note_saved_by IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM workflow.workflow_internal_notes n
      WHERE n.instance_id = s.instance_id AND n.step_order = s.step_order AND n.body = s.stage_note)
ORDER BY s.instance_id, s.step_order;

-- 2. Every non-blank note is now an entry of its stage, or the migration stops here and names what would be lost.
DO $$
DECLARE uncopied TEXT;
BEGIN
    SELECT string_agg(format('instance %s (order %s) step %s', s.instance_id, i.entity_id, s.step_order), '; '
                      ORDER BY s.instance_id, s.step_order)
    INTO uncopied
    FROM workflow.workflow_instance_steps s
    LEFT JOIN workflow.workflow_instances i ON i.id = s.instance_id
    WHERE s.stage_note IS NOT NULL
      AND btrim(s.stage_note) <> ''
      AND NOT EXISTS (
          SELECT 1 FROM workflow.workflow_internal_notes n
          WHERE n.instance_id = s.instance_id AND n.step_order = s.step_order AND n.body = s.stage_note);
    IF uncopied IS NOT NULL THEN
        RAISE EXCEPTION 'V22: stage notes that are in no stage entry (no recorded author, so V20 and V21 did not copy them): %. '
            'Nothing was changed. Copy each into workflow.workflow_internal_notes (same instance_id, school_id, step_order and body, '
            'with the author it should carry) or clear its stage_note, then re-run the migration job', uncopied;
    END IF;
END $$;

-- 3. The bridge goes: the trigger first (it depends on the columns), then its function, then the columns.
DROP TRIGGER copy_retired_stage_note ON workflow.workflow_instance_steps;
DROP FUNCTION workflow.copy_retired_stage_note();
ALTER TABLE workflow.workflow_instance_steps
    DROP COLUMN stage_note,
    DROP COLUMN stage_note_saved_at,
    DROP COLUMN stage_note_saved_by;
