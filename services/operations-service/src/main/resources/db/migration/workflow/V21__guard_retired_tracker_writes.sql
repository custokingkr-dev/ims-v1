-- Item order workflows (design v1 §3.1): the rollout window after V19 and V20. A release runs this service's migrations
-- before its new revision takes traffic, so for a few minutes the previous revision (the notebook tracker) still serves on
-- the new schema, and browser tabs still running the old screens call it. Two of its writes land in shapes the new
-- engine does not use:
--   * vendor selections: it rewrites the whole column as {"board": ["board_shree"]} after V19 remapped every row to
--     {"BOARD_SUPPLIERS": [<vendor id>]};
--   * stage notes: it overwrites workflow_instance_steps.stage_note after V20 copied every note once into
--     workflow_internal_notes, and nothing reads that column any more, so the note would silently disappear.
-- This migration
--   1. repeats V19's remap and V20's copy for whatever the old revision wrote since they ran;
--   2. refuses any later selection that is not {CATEGORY_CODE: [vendor ids]}, so an old write fails visibly (the old
--      screen shows an error and a reload brings the new one) instead of storing ids the engine cannot use;
--   3. copies each stage note the old revision still saves into the stage's entries, so it shows on the new screens.
-- The engine also reads selections tolerantly (OrderWorkflowRepository.vendorSelections), so a value that got past this
-- selects nothing rather than failing the order. The trigger can be dropped with the stage_note columns once no revision
-- that writes them can serve.
-- Owner backfills on forced-RLS tables need the deliberate bypass (V7).
SET LOCAL app.bypass_rls = 'on';
-- A release that cannot take its locks fails and is retried, rather than queueing every request behind this migration.
SET LOCAL lock_timeout = '15s';

-- Both tables are locked first, so no old write lands between the repeat and the guard that follows it. The trackers lock
-- is the one ADD CONSTRAINT needs anyway, taken up front so it is never upgraded while a writer waits.
LOCK TABLE workflow.workflow_notebook_trackers IN ACCESS EXCLUSIVE MODE;
LOCK TABLE workflow.workflow_instance_steps IN SHARE ROW EXCLUSIVE MODE;

-- 1a. Selections the old revision saved since V19, as V19 maps them. A selection that matches no directory vendor stops the
--     migration and is named, as in V19: silently losing an operator's selection is worse than a failed deploy.
DO $$
DECLARE unmapped TEXT;
BEGIN
    SELECT string_agg(format('%s %s=%s', t.order_id, sel.key, sel.value::text), '; ' ORDER BY t.order_id, sel.key)
    INTO unmapped
    FROM workflow.workflow_notebook_trackers t
    CROSS JOIN LATERAL jsonb_each(t.vendor_selections) AS sel(key, value)
    WHERE t.vendor_selections ?| ARRAY['board', 'lamination', 'binder', 'offset']
      AND (sel.key NOT IN ('board', 'lamination', 'binder', 'offset')
       OR jsonb_typeof(sel.value) <> 'array'
       OR EXISTS (
            SELECT 1
            FROM jsonb_array_elements(sel.value) AS item(value)
            WHERE jsonb_typeof(item.value) <> 'string'
               OR NOT EXISTS (
                    SELECT 1
                    FROM workflow.workflow_vendors v
                    JOIN workflow.workflow_vendor_category_members m ON m.vendor_id = v.id
                    WHERE v.legacy_key = item.value #>> '{}'
                      AND m.category_code = CASE sel.key
                                                WHEN 'board' THEN 'BOARD_SUPPLIERS'
                                                WHEN 'lamination' THEN 'LAMINATION_VENDORS'
                                                WHEN 'binder' THEN 'BINDERS'
                                                WHEN 'offset' THEN 'OFFSET_PRINTERS'
                                            END)));
    IF unmapped IS NOT NULL THEN
        RAISE EXCEPTION 'V21: vendor selections saved since V19 that match no directory vendor: %', unmapped;
    END IF;
END $$;

-- Only rows still in the old format: a row V19 (or the new engine) already wrote is left exactly as it is.
WITH category_map(legacy, code) AS (
    VALUES ('board', 'BOARD_SUPPLIERS'), ('lamination', 'LAMINATION_VENDORS'), ('binder', 'BINDERS'), ('offset', 'OFFSET_PRINTERS')
), picked AS (
    SELECT DISTINCT t.instance_id, cm.code, v.id
    FROM workflow.workflow_notebook_trackers t
    CROSS JOIN LATERAL jsonb_each(t.vendor_selections) AS sel(key, value)
    JOIN category_map cm ON cm.legacy = sel.key
    CROSS JOIN LATERAL jsonb_array_elements_text(sel.value) AS item(legacy_id)
    JOIN workflow.workflow_vendors v ON v.legacy_key = item.legacy_id
    WHERE t.vendor_selections ?| ARRAY['board', 'lamination', 'binder', 'offset']
), per_category AS (
    SELECT instance_id, code, jsonb_agg(id ORDER BY id) AS ids
    FROM picked
    GROUP BY instance_id, code
), per_tracker AS (
    SELECT instance_id, jsonb_object_agg(code, ids) AS selections
    FROM per_category
    GROUP BY instance_id
)
-- A tracker whose lists were all empty ends with {}; the version moves so a stale browser cannot save over it.
UPDATE workflow.workflow_notebook_trackers t
SET vendor_selections = COALESCE((SELECT p.selections FROM per_tracker p WHERE p.instance_id = t.instance_id), '{}'::jsonb),
    version = t.version + 1
WHERE t.vendor_selections ?| ARRAY['board', 'lamination', 'binder', 'offset'];

-- 2. From now on a selection is {CATEGORY_CODE: [vendor ids]}: an object whose keys are category codes and whose values are
--    lists of numbers. jsonb_path_exists is immutable; its silent errors (strict paths on a value of another shape) only
--    arise where an earlier conjunct is already false.
ALTER TABLE workflow.workflow_notebook_trackers
    ADD CONSTRAINT ck_wnt_vendor_selections CHECK (
        jsonb_typeof(vendor_selections) = 'object'
        AND NOT jsonb_path_exists(vendor_selections,
            'strict $.keyvalue() ? (!(@.key like_regex "^[A-Z][A-Z0-9_]{1,39}$") || @.value.type() != "array")', '{}', true)
        AND NOT jsonb_path_exists(vendor_selections, 'strict $.*[*] ? (@.type() != "number")', '{}', true));

-- 3a. Stage notes the old revision saved since V20, each once, as V20 copies them.
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

-- 3b. And every one it saves from now on, in the saving transaction. The function runs with the saver's rights and
--     row-level-security scope: the note belongs to the school the step row does, which that scope already admitted.
CREATE FUNCTION workflow.copy_retired_stage_note() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    INSERT INTO workflow.workflow_internal_notes (instance_id, school_id, author_user_id, body, created_at, step_order)
    SELECT NEW.instance_id, NEW.school_id, NEW.stage_note_saved_by, NEW.stage_note, COALESCE(NEW.stage_note_saved_at, now()),
           NEW.step_order
    WHERE NOT EXISTS (
        SELECT 1 FROM workflow.workflow_internal_notes n
        WHERE n.instance_id = NEW.instance_id AND n.step_order = NEW.step_order AND n.body = NEW.stage_note);
    RETURN NULL;
END
$$;

CREATE TRIGGER copy_retired_stage_note
    AFTER UPDATE OF stage_note ON workflow.workflow_instance_steps
    FOR EACH ROW
    WHEN (NEW.stage_note IS DISTINCT FROM OLD.stage_note AND NEW.stage_note_saved_by IS NOT NULL AND btrim(NEW.stage_note) <> '')
    EXECUTE FUNCTION workflow.copy_retired_stage_note();
