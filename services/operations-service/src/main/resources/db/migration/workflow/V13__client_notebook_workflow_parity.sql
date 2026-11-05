-- Align the tracker stage sequence with the client workflow while retaining each step's saved data and audit history.
-- Head Approval moves 3 -> 4, CDR Conversion 4 -> 3, Printing Press 7 -> 8, and Pre-Print QC 8 -> 7.
DROP INDEX IF EXISTS workflow.uk_wis_one_active;
ALTER TABLE workflow.workflow_instance_steps DROP CONSTRAINT fk_wis_step;
ALTER TABLE workflow.workflow_school_step_sla DROP CONSTRAINT fk_wsss_step;

UPDATE workflow.workflow_steps
SET step_order = step_order + 100
WHERE definition_id = 'NOTEBOOK_ORDER_TRACKER' AND step_order IN (3, 4, 7, 8);

UPDATE workflow.workflow_instance_steps
SET step_order = step_order + 100
WHERE definition_id = 'NOTEBOOK_ORDER_TRACKER' AND step_order IN (3, 4, 7, 8);

UPDATE workflow.workflow_school_step_sla
SET step_order = step_order + 100
WHERE definition_id = 'NOTEBOOK_ORDER_TRACKER' AND step_order IN (3, 4, 7, 8);

UPDATE workflow.workflow_actions a
SET step_order = step_order + 100
FROM workflow.workflow_notebook_trackers t
WHERE a.instance_id = t.instance_id AND a.step_order IN (3, 4, 7, 8);

UPDATE workflow.workflow_steps
SET step_order = CASE step_order WHEN 103 THEN 4 WHEN 104 THEN 3 WHEN 107 THEN 8 WHEN 108 THEN 7 END
WHERE definition_id = 'NOTEBOOK_ORDER_TRACKER' AND step_order IN (103, 104, 107, 108);

UPDATE workflow.workflow_instance_steps
SET step_order = CASE step_order WHEN 103 THEN 4 WHEN 104 THEN 3 WHEN 107 THEN 8 WHEN 108 THEN 7 END
WHERE definition_id = 'NOTEBOOK_ORDER_TRACKER' AND step_order IN (103, 104, 107, 108);

UPDATE workflow.workflow_school_step_sla
SET step_order = CASE step_order WHEN 103 THEN 4 WHEN 104 THEN 3 WHEN 107 THEN 8 WHEN 108 THEN 7 END
WHERE definition_id = 'NOTEBOOK_ORDER_TRACKER' AND step_order IN (103, 104, 107, 108);

UPDATE workflow.workflow_actions a
SET step_order = CASE a.step_order WHEN 103 THEN 4 WHEN 104 THEN 3 WHEN 107 THEN 8 WHEN 108 THEN 7 END
FROM workflow.workflow_notebook_trackers t
WHERE a.instance_id = t.instance_id AND a.step_order IN (103, 104, 107, 108);

UPDATE workflow.workflow_steps
SET step_name = CASE step_order
        WHEN 3 THEN 'CDR Conversion'
        WHEN 4 THEN 'Head Approval'
        WHEN 7 THEN 'Pre-Print Quality Check'
        WHEN 8 THEN 'Printing Press'
        ELSE step_name
    END,
    default_sla_value = CASE step_order
        WHEN 1 THEN 2 WHEN 2 THEN 72 WHEN 3 THEN 2 WHEN 4 THEN 2
        WHEN 5 THEN 24 WHEN 6 THEN 6 WHEN 7 THEN 24 WHEN 8 THEN 50
        WHEN 9 THEN 4 WHEN 10 THEN 72 WHEN 11 THEN 2 WHEN 12 THEN 4
    END,
    default_sla_unit = 'HOURS'
WHERE definition_id = 'NOTEBOOK_ORDER_TRACKER';

-- A school override follows its semantic stage through the reordered definitions.
-- Any active order whose new sequence has a gap is moved back to the earliest unfinished step.
WITH first_pending AS (
    SELECT instance_id, COALESCE(MIN(step_order) FILTER (WHERE status <> 'COMPLETED'), 13) AS first_step
    FROM workflow.workflow_instance_steps
    WHERE definition_id = 'NOTEBOOK_ORDER_TRACKER'
    GROUP BY instance_id
), resolved_sla AS (
    SELECT instance.instance_id, instance.step_order,
           CASE WHEN school_sla.school_id IS NOT NULL THEN school_sla.sla_value ELSE definition.default_sla_value END AS sla_value,
           CASE WHEN school_sla.school_id IS NOT NULL THEN school_sla.sla_unit ELSE definition.default_sla_unit END AS sla_unit
    FROM workflow.workflow_instance_steps instance
    JOIN workflow.workflow_steps definition
      ON definition.definition_id = instance.definition_id AND definition.step_order = instance.step_order
    LEFT JOIN workflow.workflow_school_step_sla school_sla
      ON school_sla.definition_id = definition.definition_id
     AND school_sla.step_order = definition.step_order
     AND school_sla.school_id = instance.school_id
    WHERE instance.definition_id = 'NOTEBOOK_ORDER_TRACKER'
)
UPDATE workflow.workflow_instance_steps s
SET status = CASE
        WHEN s.step_order < p.first_step THEN 'COMPLETED'
        WHEN s.step_order = p.first_step THEN 'ACTIVE'
        ELSE 'LOCKED'
    END,
    started_at = CASE
        WHEN s.step_order < p.first_step THEN COALESCE(s.started_at, s.completed_at, now())
        WHEN s.step_order = p.first_step THEN COALESCE(s.started_at, now())
        ELSE NULL
    END,
    completed_at = CASE WHEN s.step_order < p.first_step THEN COALESCE(s.completed_at, now()) ELSE NULL END,
    completed_by = CASE WHEN s.step_order < p.first_step THEN s.completed_by ELSE NULL END,
    due_at = CASE
        WHEN s.step_order < p.first_step THEN NULL
        WHEN s.step_order > p.first_step THEN NULL
        WHEN resolved_sla.sla_value IS NULL THEN NULL
        WHEN resolved_sla.sla_unit = 'HOURS'
            THEN COALESCE(s.started_at, now()) + make_interval(hours => resolved_sla.sla_value)
        ELSE COALESCE(s.started_at, now()) + make_interval(days => resolved_sla.sla_value)
    END,
    version = s.version + 1
FROM first_pending p, resolved_sla
WHERE s.instance_id = p.instance_id
  AND s.instance_id = resolved_sla.instance_id
  AND s.step_order = resolved_sla.step_order
  AND s.definition_id = 'NOTEBOOK_ORDER_TRACKER';

UPDATE workflow.workflow_instances i
SET current_step = COALESCE((
    SELECT MIN(s.step_order)
    FROM workflow.workflow_instance_steps s
    WHERE s.instance_id = i.id AND s.status = 'ACTIVE'
), 0),
version = version + 1
WHERE i.id IN (SELECT instance_id FROM workflow.workflow_notebook_trackers);

ALTER TABLE workflow.workflow_instance_steps
    ADD CONSTRAINT fk_wis_step FOREIGN KEY (definition_id, step_order)
    REFERENCES workflow.workflow_steps (definition_id, step_order);
ALTER TABLE workflow.workflow_school_step_sla
    ADD CONSTRAINT fk_wsss_step FOREIGN KEY (definition_id, step_order)
    REFERENCES workflow.workflow_steps (definition_id, step_order);
CREATE UNIQUE INDEX uk_wis_one_active ON workflow.workflow_instance_steps (instance_id) WHERE status = 'ACTIVE';
