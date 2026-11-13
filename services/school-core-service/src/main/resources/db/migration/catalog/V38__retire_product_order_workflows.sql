-- Retire the PR #380 product production workflow (item order workflows design v1, D4, D13). Every item now runs on the one
-- order workflow engine in operations-service, which a placement starts through the creation queue. Here:
--   0. the creation queue moves to catalog.order_workflow_creation_queue, a table only this release's school-core reads
--      (why: see "The rollout window" below);
--   1. open, placed orders of every non-notebook workflow category move onto the engine: they become tracker-managed and
--      are queued. Their V36 progress, if any, travels in the creation call (NotebookTrackerCreationQueue reads it), and
--      operations marks those stages completed without re-applying gates;
--   2. every open tracker-managed order is queued, so operations' idempotent create fills in the order number (V37) on a
--      workflow that already exists;
--   3. the V36 tables become read-only for the runtime role. They are kept for one release, then dropped (out of scope).
-- "Open" means placed and not finished: not DRAFT, CANCELLED or a terminal status. Closed orders get no workflow (the
-- queue drops a closed order's row anyway), so they are not queued.
--
-- The rollout window. School-core's migration job runs this while the previous school-core revision still serves, and in
-- prod the Cloud Deploy canary keeps that revision serving beside the new one until the rollout completes. Operations is
-- deployed after school-core, so the previous engine serves meanwhile too. The previous school-core:
--   * drains notebook_tracker_creation_queue every minute (Cloud Scheduler's relay trigger) with no notion of templates:
--     any order it finds there gets a NOTEBOOK workflow, through the previous engine's notebook-tracker route, on the
--     wholesale path, and its row is deleted, so the order would stay on the wrong template for good;
--   * deletes an order's row whenever that order's workflow pushes a status, so a row that carries an order number to an
--     existing workflow (step 2) would be lost;
--   * starts a workflow by hand ("ensure") for any tracker-managed order, which after step 1 includes fliers and the rest;
--   * places orders without minting a number, and places non-notebook orders on approve, quote and deliver.
-- So this release's school-core keeps its queue in a new table the previous revision never reads, everything the previous
-- revision queues is forwarded into it, and an order the previous revision places is queued for repair (a number, and the
-- move of step 1) by a commit-time trigger. Nothing the previous revision does can then create a workflow, or lose a row.
-- The old table and both triggers stay until the release after this one (they cost nothing once that revision is gone).
--
-- Locks. The DDL below takes SHARE ROW EXCLUSIVE on catalog_orders (the queue's foreign key, the constraint trigger) and
-- on notebook_tracker_creation_queue (the forwarding trigger). The previous revision writes those two tables in both
-- orders (a placement: the order, then the queue; a status push or a created workflow: the queue, then the order), so
-- taking one and waiting for the other could deadlock with it. Both are taken first, together, without ever waiting while
-- one is held: a busy table releases both, and the attempt is repeated. After 15 seconds the migration stops, changing
-- nothing, and the migration job can simply be re-run. lock_timeout bounds every other wait the same way.
SET LOCAL lock_timeout = '15s';
DO $$
BEGIN
    FOR attempt IN 1..150 LOOP
        BEGIN
            LOCK TABLE catalog.notebook_tracker_creation_queue, catalog.catalog_orders IN SHARE ROW EXCLUSIVE MODE NOWAIT;
            RETURN;
        EXCEPTION WHEN lock_not_available THEN
            PERFORM pg_sleep(0.1);
        END;
    END LOOP;
    RAISE EXCEPTION 'V38: catalog_orders and notebook_tracker_creation_queue stayed busy for 15 seconds; nothing was changed, re-run the migration job';
END $$;

-- 0. The new queue: the shape of notebook_tracker_creation_queue (V28, V30) plus repair. repair: the order was placed by
--    a school-core that did not number orders; the next claim mints its number and applies step 1 before creating.
CREATE TABLE catalog.order_workflow_creation_queue (
    order_id         VARCHAR(255) PRIMARY KEY,
    school_id        BIGINT       NOT NULL,
    attempts         INT          NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error       VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    dead_lettered_at TIMESTAMPTZ  NULL,
    repair           BOOLEAN      NOT NULL DEFAULT false,
    FOREIGN KEY (order_id, school_id) REFERENCES catalog.catalog_orders (id, school_id) ON DELETE CASCADE
);
CREATE INDEX idx_order_workflow_creation_queue_pending ON catalog.order_workflow_creation_queue (next_attempt_at)
    WHERE dead_lettered_at IS NULL;

ALTER TABLE catalog.order_workflow_creation_queue ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.order_workflow_creation_queue FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.order_workflow_creation_queue
    USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on'
        OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]))
    WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on'
        OR school_id = ANY(string_to_array(nullif(current_setting('app.operator_schools', true), ''), ',')::bigint[]));

-- The triggers below write this table as the caller, and the previous revision's writes resume the moment this migration
-- commits, before afterMigrate gives the dedicated runtime role its privileges on new tables. So the role a deployed
-- school-core connects as (ims_school_core_rt; app_rt where the dedicated role is not in use yet) gets them here, in the
-- same commit. afterMigrate re-syncs ims_school_core_rt to app_rt's set afterwards, which is the same set.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.order_workflow_creation_queue TO app_rt;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ims_school_core_rt') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.order_workflow_creation_queue TO ims_school_core_rt;
    END IF;
END $$;

COMMENT ON TABLE catalog.order_workflow_creation_queue IS
    'Order workflow creations still to be made (NotebookTrackerCreationQueue). Replaces notebook_tracker_creation_queue since V38.';

-- Whatever the previous revision queues (a notebook it places, a workflow started by hand) lands in the new queue instead,
-- with fresh retries, as that revision's own ON CONFLICT asked. Its insert then writes nothing, so its own attempt and drain
-- find nothing to create: this release's school-core creates the workflow, under the right template. The trigger runs as
-- the caller, whose row-level security admits the new queue's row exactly as it admitted the old one (the same policy).
CREATE FUNCTION catalog.forward_to_order_workflow_creation_queue() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO catalog.order_workflow_creation_queue (order_id, school_id) VALUES (NEW.order_id, NEW.school_id)
    ON CONFLICT (order_id) DO UPDATE SET next_attempt_at = now(), attempts = 0, dead_lettered_at = NULL;
    RETURN NULL;
END $$;

CREATE TRIGGER trg_notebook_tracker_creation_queue_forward
    BEFORE INSERT ON catalog.notebook_tracker_creation_queue
    FOR EACH ROW EXECUTE FUNCTION catalog.forward_to_order_workflow_creation_queue();

COMMENT ON TABLE catalog.notebook_tracker_creation_queue IS
    'Retired since V38: rows inserted here are forwarded to order_workflow_creation_queue. Drop with its trigger next release.';

-- An order that leaves DRAFT and still has no number when its transaction commits was placed by a school-core that does
-- not number orders (this release's mints in the placing transaction). It is queued for repair. Deferred to commit so the
-- placing transaction's own mint is seen. Never fails the placement: a placement matters more than its repair, and a
-- refusal is logged by PostgreSQL as a warning naming the order.
CREATE FUNCTION catalog.queue_unnumbered_placement() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO catalog.order_workflow_creation_queue (order_id, school_id, repair)
    SELECT co.id, co.school_id, true
    FROM catalog.catalog_orders co
    JOIN catalog.product_categories pc ON pc.code = co.category AND pc.order_code IS NOT NULL
    WHERE co.id = NEW.id AND co.status <> 'DRAFT' AND co.order_number IS NULL
    ON CONFLICT (order_id) DO UPDATE SET repair = true;
    RETURN NULL;
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'Catalog order % was placed without an order number and could not be queued for repair: %', NEW.id, SQLERRM;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER trg_catalog_orders_queue_unnumbered_placement
    AFTER UPDATE OF status ON catalog.catalog_orders
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (OLD.status = 'DRAFT' AND NEW.status <> 'DRAFT')
    EXECUTE FUNCTION catalog.queue_unnumbered_placement();

DO $$
BEGIN
    PERFORM set_config('app.bypass_rls', 'on', true);

    -- Rows the previous revision had queued (notebooks only: it queued nothing else) move with their retry state. One
    -- statement, so a row committed meanwhile is either moved or, after this commits, forwarded by the trigger above.
    WITH moved AS (DELETE FROM catalog.notebook_tracker_creation_queue RETURNING *)
    INSERT INTO catalog.order_workflow_creation_queue (order_id, school_id, attempts, next_attempt_at, last_error, created_at, dead_lettered_at)
    SELECT order_id, school_id, attempts, next_attempt_at, last_error, created_at, dead_lettered_at FROM moved
    ON CONFLICT (order_id) DO NOTHING;

    -- 1. Open product orders move onto the engine.
    UPDATE catalog.catalog_orders co
    SET attributes = co.attributes || jsonb_build_object(
            'trackerManaged', true,
            'trackerPending', true,
            'workflowDefinition', pc.workflow_definition)
    FROM catalog.product_categories pc
    WHERE pc.code = co.category
      AND pc.workflow_definition IS NOT NULL
      AND co.category <> 'NOTEBOOKS'
      AND co.form_version = 2
      AND co.status NOT IN ('DRAFT', 'CANCELLED', 'DELIVERED', 'FULFILLED', 'REJECTED', 'RETURNED', 'COMPLETED')
      AND NOT co.attributes @> '{"trackerManaged": true}'::jsonb;

    -- 2. Every open tracker-managed order is queued.
    INSERT INTO catalog.order_workflow_creation_queue (order_id, school_id)
    SELECT co.id, co.school_id
    FROM catalog.catalog_orders co
    WHERE co.attributes @> '{"trackerManaged": true}'::jsonb
      AND co.status NOT IN ('DRAFT', 'CANCELLED', 'DELIVERED', 'FULFILLED', 'REJECTED', 'RETURNED', 'COMPLETED')
    ON CONFLICT (order_id) DO NOTHING;

    -- 2b. Orders the previous revision placed after V37 numbered the existing ones (V37 committed on its own, and that
    --     revision does not mint), up to this migration's lock, are queued for repair, like the placements the trigger
    --     above catches from now on: the claim mints the number, moves an open one as step 1 does, and drops a closed one
    --     once numbered. Open or closed, every placed order of a coded category ends with a number (D13), as V37 promises.
    INSERT INTO catalog.order_workflow_creation_queue (order_id, school_id, repair)
    SELECT co.id, co.school_id, true
    FROM catalog.catalog_orders co
    JOIN catalog.product_categories pc ON pc.code = co.category AND pc.order_code IS NOT NULL
    WHERE co.status <> 'DRAFT' AND co.order_number IS NULL
    ON CONFLICT (order_id) DO UPDATE SET repair = true;
END $$;

-- 3. The retired tables become read-only.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        REVOKE INSERT, UPDATE, DELETE ON catalog.product_order_workflows, catalog.product_order_workflow_steps FROM app_rt;
    END IF;
END $$;

COMMENT ON TABLE catalog.product_order_workflows IS
    'Read-only since V38: the PR #380 product workflow is retired; its orders run on the operations order workflow engine.';
COMMENT ON TABLE catalog.product_order_workflow_steps IS
    'Read-only since V38: imported into the operations order workflow engine by the creation queue.';
