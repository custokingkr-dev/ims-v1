-- Student deletion is terminal. Pub/Sub provides at-least-once delivery and can deliver
-- an older upsert after a newer deletion, so the read model must remember deletions.
CREATE TABLE IF NOT EXISTS reporting.student_projection_tombstones (
    student_id BIGINT PRIMARY KEY,
    deleted_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE reporting.student_projection_tombstones IS
    'Terminal student deletions used to reject stale, out-of-order projection upserts.';

REVOKE ALL ON reporting.student_projection_tombstones FROM PUBLIC;

-- Recover deletion knowledge already present in the durable inbox. Student identifiers are
-- generated numeric IDs and are never reused, so the tombstone remains terminal.
INSERT INTO reporting.student_projection_tombstones (student_id, deleted_at, recorded_at)
SELECT aggregate_id::BIGINT,
       MAX(COALESCE(occurred_at, received_at)),
       now()
FROM reporting.reporting_event_inbox
WHERE event_type = 'student.deleted.v1'
  AND aggregate_id ~ '^[0-9]+$'
GROUP BY aggregate_id::BIGINT
ON CONFLICT (student_id) DO UPDATE SET
    deleted_at = GREATEST(
        reporting.student_projection_tombstones.deleted_at,
        EXCLUDED.deleted_at
    ),
    recorded_at = now();

-- Clean any projections recreated by stale upserts before this ordering guard existed.
DELETE FROM reporting.event_student_contributions target
USING reporting.student_projection_tombstones tombstone
WHERE target.student_id = tombstone.student_id;

DELETE FROM reporting.fact_payment target
USING reporting.student_projection_tombstones tombstone
WHERE target.student_id = tombstone.student_id;

DELETE FROM reporting.fact_fee_assignment target
USING reporting.student_projection_tombstones tombstone
WHERE target.student_id = tombstone.student_id;

DELETE FROM reporting.dim_student target
USING reporting.student_projection_tombstones tombstone
WHERE target.id = tombstone.student_id;

-- On a fresh database, reporting migrations run before notification migrations. On an
-- existing database the notification table is present and can contain student references.
DO $$
BEGIN
    IF to_regclass('notification.notification_logs') IS NOT NULL THEN
        EXECUTE '
            DELETE FROM notification.notification_logs target
            USING reporting.student_projection_tombstones tombstone
            WHERE target.student_id = tombstone.student_id';
    END IF;
END
$$;
