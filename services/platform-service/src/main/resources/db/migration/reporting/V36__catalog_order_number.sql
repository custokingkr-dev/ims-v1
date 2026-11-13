-- Order numbers (item order workflows design v1, D10, §3.6): catalog-order.upserted.v1 now carries orderNumber, the
-- human-facing <ItemCode>-<SchoolOrderCode>-<YY-MM-DD>-<NNNN> number. The CK id stays the key. Null for drafts and for
-- categories without an order code; school-core's catalog V37 re-emits the event for every order it numbered.
ALTER TABLE reporting.fact_catalog_order ADD COLUMN IF NOT EXISTS order_number varchar(40);

-- The rollout window. Platform deploys last (dev: after school-core, operations and billing; prod: in the last Cloud Deploy
-- wave, through a canary), so the previous platform revision serves for minutes after school-core's catalog V37 has
-- re-emitted every number, and while this release's school-core emits numbers with every change. That revision projects
-- those events with a projector that ignores orderNumber and marks them PROCESSED, and nothing projects a processed event
-- again. Two things recover what it drops:
--   1. the numbers already in the inbox when this migration runs are copied onto the facts that lack one;
--   2. whenever any revision marks a catalog-order.upserted.v1 event processed, a trigger copies the event's number onto a
--      fact that still lacks one. It runs in the projector's transaction, after its upsert, under the same row-level
--      security (the projector bypasses it transaction-locally). A number never changes once minted (catalog V37), so
--      copying it from an older event is as right as from a newer one, and a stored number is never overwritten.
-- The trigger costs one primary-key lookup per catalog event and does nothing once the number is stored; it can be dropped
-- once no revision older than this one can serve. A payload that is not a JSON object is skipped, never an error: a
-- projection must not fail on account of this copy.
DO $$
BEGIN
    PERFORM set_config('app.bypass_rls', 'on', true);
    WITH events AS (
        SELECT CASE WHEN pg_input_is_valid(payload, 'jsonb') THEN payload::jsonb END AS body, received_at
        FROM reporting.reporting_event_inbox
        WHERE event_type = 'catalog-order.upserted.v1'
    ), numbered AS (
        SELECT DISTINCT ON (body ->> 'id') body ->> 'id' AS order_id, body ->> 'orderNumber' AS order_number
        FROM events
        WHERE jsonb_typeof(body) = 'object' AND body ->> 'id' IS NOT NULL
          AND length(body ->> 'orderNumber') BETWEEN 1 AND 40
        ORDER BY body ->> 'id', received_at DESC
    )
    UPDATE reporting.fact_catalog_order fact
    SET order_number = numbered.order_number
    FROM numbered
    WHERE fact.id = numbered.order_id AND fact.order_number IS NULL;
END $$;

CREATE FUNCTION reporting.copy_catalog_order_number() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    body jsonb;
BEGIN
    IF NOT pg_input_is_valid(NEW.payload, 'jsonb') THEN
        RETURN NULL;
    END IF;
    body := NEW.payload::jsonb;
    IF jsonb_typeof(body) <> 'object' OR body ->> 'id' IS NULL OR COALESCE(length(body ->> 'orderNumber'), 0) NOT BETWEEN 1 AND 40 THEN
        RETURN NULL;
    END IF;
    UPDATE reporting.fact_catalog_order SET order_number = body ->> 'orderNumber'
    WHERE id = body ->> 'id' AND order_number IS NULL;
    RETURN NULL;
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'The order number of catalog event % could not be copied to reporting: %', NEW.event_id, SQLERRM;
    RETURN NULL;
END $$;

CREATE TRIGGER trg_reporting_event_inbox_catalog_order_number
    AFTER UPDATE OF status ON reporting.reporting_event_inbox
    FOR EACH ROW
    WHEN (NEW.status = 'PROCESSED' AND NEW.event_type = 'catalog-order.upserted.v1' AND OLD.status IS DISTINCT FROM 'PROCESSED')
    EXECUTE FUNCTION reporting.copy_catalog_order_number();
