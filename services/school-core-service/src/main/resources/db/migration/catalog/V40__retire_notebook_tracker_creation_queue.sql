-- Retire the rollout bridge V38 kept for the previous school-core revision (item order workflows design v1, D6): the old
-- creation queue catalog.notebook_tracker_creation_queue, its forwarding trigger and catalog.forward_to_order_workflow_creation_queue().
-- Only a school-core older than ed8aeba5 (PR #382) ever wrote that table, and no such revision can serve once this runs:
--   * dev serves ed8aeba5 or later, and rolling school-core back below ed8aeba5 is unsupported from this release on
--     (that revision enqueues into the old table inside the placing transaction, so its notebook placements would fail);
--   * prod's previous revision (origin/main) never had a creation queue, so prod's first release with the item order
--     workflows runs V28 (which creates the table), V38 and this in one migration job, and nothing writes it in between.
-- V38's placement trigger, trg_catalog_orders_queue_unnumbered_placement with catalog.queue_unnumbered_placement(), is NOT
-- dropped: prod's first release with the item order workflows still needs it for the orders origin/main's school-core
-- places, without a number, during the canary. Its comments below say exactly when it may go.
--
-- Locks. Dropping the old table drops its foreign key to catalog_orders, and with it the referential-integrity triggers on
-- catalog_orders, which needs ACCESS EXCLUSIVE on both tables. A plain LOCK would queue every read of catalog_orders behind
-- the wait, so both are taken together with NOWAIT, released when either is busy, and tried again for up to 15 seconds.
-- Then the migration stops, changing nothing, and the migration job can simply be re-run. lock_timeout bounds every other
-- wait the same way. Once taken, the locks are held for the milliseconds the rest of this needs.
SET LOCAL app.bypass_rls = 'on';
SET LOCAL lock_timeout = '15s';
DO $$
BEGIN
    FOR attempt IN 1..150 LOOP
        BEGIN
            LOCK TABLE catalog.notebook_tracker_creation_queue, catalog.catalog_orders IN ACCESS EXCLUSIVE MODE NOWAIT;
            RETURN;
        EXCEPTION WHEN lock_not_available THEN
            PERFORM pg_sleep(0.1);
        END;
    END LOOP;
    RAISE EXCEPTION 'V40: catalog_orders and notebook_tracker_creation_queue stayed busy for 15 seconds; nothing was changed, re-run the migration job';
END $$;

-- 1. The old table holds no row unless a writer bypassed its forwarding trigger (session_replication_role = replica, a
--    disabled trigger, a data load). Any such row is forwarded now exactly as the trigger would have forwarded it
--    (V38's catalog.forward_to_order_workflow_creation_queue(): fresh retries; a row already queued is due again now).
--    Replica mode skips the old table's foreign key too, so a leftover row may name an order that does not exist (or not
--    at that school). The trigger could never have forwarded such a row (the new queue's foreign key refuses it), and its
--    order would have taken it with it (ON DELETE CASCADE): it is dropped with the table, and named in a warning.
DO $$
DECLARE moved TEXT; dropped TEXT;
BEGIN
    WITH leftover AS (
            DELETE FROM catalog.notebook_tracker_creation_queue q
            RETURNING q.order_id, q.school_id,
                EXISTS (SELECT 1 FROM catalog.catalog_orders co WHERE co.id = q.order_id AND co.school_id = q.school_id) AS order_exists),
         forwarded AS (
            INSERT INTO catalog.order_workflow_creation_queue (order_id, school_id)
            SELECT order_id, school_id FROM leftover WHERE order_exists
            ON CONFLICT (order_id) DO UPDATE SET next_attempt_at = now(), attempts = 0, dead_lettered_at = NULL
            RETURNING order_id)
    SELECT (SELECT string_agg(order_id, ', ' ORDER BY order_id) FROM forwarded),
           (SELECT string_agg(format('%s (school %s)', order_id, school_id), ', ' ORDER BY order_id) FROM leftover WHERE NOT order_exists)
    INTO moved, dropped;
    IF moved IS NOT NULL THEN
        RAISE NOTICE 'V40: forwarded rows left in catalog.notebook_tracker_creation_queue into order_workflow_creation_queue: %', moved;
    END IF;
    IF dropped IS NOT NULL THEN
        RAISE WARNING 'V40: dropped rows left in catalog.notebook_tracker_creation_queue whose order does not exist at that school: %', dropped;
    END IF;
END $$;

-- 2. The old queue goes, with its forwarding trigger, policy, index and foreign key; then the trigger's function.
DROP TABLE catalog.notebook_tracker_creation_queue;
DROP FUNCTION catalog.forward_to_order_workflow_creation_queue();

-- 3. The placement trigger stays. What it is for, and when it may be dropped, is recorded on it.
COMMENT ON FUNCTION catalog.queue_unnumbered_placement() IS
    'Since catalog V38, kept by V40. Queues for repair (order_workflow_creation_queue.repair) an order of a category with an '
    'order code that leaves DRAFT with no number at commit: a placement by a school-core that does not mint, which is '
    'origin/main''s (pre item order workflows) during prod''s first release with them. Drop it with its trigger, in a '
    'migration, only when both hold: (1) every school-core revision that can serve in every environment mints on the legacy '
    'status update (CatalogReadRepository.updateOrderStatus, the release that added catalog V40); (2) prod''s first release '
    'with the item order workflows has completed its school-core canary, and the rollback target is that release or later. '
    'Keep the repair column and the repair code while repair rows can exist.';
COMMENT ON TRIGGER trg_catalog_orders_queue_unnumbered_placement ON catalog.catalog_orders IS
    'Runs catalog.queue_unnumbered_placement() at commit for an order that leaves DRAFT; see that function''s comment for '
    'why it is kept and the two conditions under which it may be dropped.';
