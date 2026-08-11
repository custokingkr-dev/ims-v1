\set ON_ERROR_STOP on

BEGIN;
SET LOCAL app.bypass_rls = 'on';
SELECT pg_advisory_xact_lock(hashtext('ims-scale-fixture'));

-- The application relays are @Scheduled inside the Cloud Run services; pausing
-- Cloud Scheduler does not quiesce them. Serialize this guarded dev cleanup
-- against outbox updates and reporting-inbox inserts so the before/delete/after
-- assertions share an exclusive writer envelope. Read-only queries remain
-- available while this short transaction runs.
LOCK TABLE tenant_school.outbox_events IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE reporting.reporting_event_inbox IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE scale_config AS
SELECT :base_school_id::bigint AS base_school_id,
       :school_count::integer AS expected_school_count;

DO $$
DECLARE
    actual_school_count bigint;
BEGIN
    IF EXISTS (
        SELECT 1
        FROM tenant_school.schools s, scale_config c
        WHERE s.id >= c.base_school_id
          AND s.id < c.base_school_id + 10000
          AND s.short_code NOT LIKE 'SCALE-%'
    ) THEN
        RAISE EXCEPTION 'reserved scale school id range contains non-scale data';
    END IF;

    SELECT count(*) INTO actual_school_count
    FROM tenant_school.schools s, scale_config c
    WHERE s.id >= c.base_school_id
      AND s.id < c.base_school_id + 10000
      AND s.short_code LIKE 'SCALE-%';

    IF actual_school_count <> (SELECT expected_school_count FROM scale_config) THEN
        RAISE EXCEPTION 'expected % verified scale schools, found %',
            (SELECT expected_school_count FROM scale_config), actual_school_count;
    END IF;
END $$;

CREATE TEMP TABLE scale_school_ids AS
SELECT s.id
FROM tenant_school.schools s, scale_config c
WHERE s.id >= c.base_school_id
  AND s.id < c.base_school_id + 10000
  AND s.short_code LIKE 'SCALE-%';

CREATE TEMP TABLE backlog_before AS
SELECT
    (SELECT count(*) FROM scale_school_ids) AS schools,
    (SELECT count(*) FROM tenant_school.outbox_events
        WHERE school_id IN (SELECT id FROM scale_school_ids)) AS outbox_scope,
    (SELECT count(*) FROM reporting.reporting_event_inbox
        WHERE school_id IN (SELECT id FROM scale_school_ids)) AS inbox_scope,
    (SELECT count(*) FROM tenant_school.outbox_events
        WHERE school_id IS NULL OR school_id NOT IN (SELECT id FROM scale_school_ids)) AS outbox_outside_scope,
    (SELECT count(*) FROM reporting.reporting_event_inbox
        WHERE school_id IS NULL OR school_id NOT IN (SELECT id FROM scale_school_ids)) AS inbox_outside_scope;

CREATE TEMP TABLE deleted_counts AS
WITH deleted_inbox AS (
    DELETE FROM reporting.reporting_event_inbox
    WHERE school_id IN (SELECT id FROM scale_school_ids)
    RETURNING 1
), deleted_outbox AS (
    DELETE FROM tenant_school.outbox_events
    WHERE school_id IN (SELECT id FROM scale_school_ids)
    RETURNING 1
)
SELECT (SELECT count(*) FROM deleted_outbox) AS outbox_deleted,
       (SELECT count(*) FROM deleted_inbox) AS inbox_deleted;

CREATE TEMP TABLE backlog_after AS
SELECT
    (SELECT count(*) FROM tenant_school.outbox_events
        WHERE school_id IN (SELECT id FROM scale_school_ids)) AS outbox_scope,
    (SELECT count(*) FROM reporting.reporting_event_inbox
        WHERE school_id IN (SELECT id FROM scale_school_ids)) AS inbox_scope,
    (SELECT count(*) FROM tenant_school.outbox_events
        WHERE school_id IS NULL OR school_id NOT IN (SELECT id FROM scale_school_ids)) AS outbox_outside_scope,
    (SELECT count(*) FROM reporting.reporting_event_inbox
        WHERE school_id IS NULL OR school_id NOT IN (SELECT id FROM scale_school_ids)) AS inbox_outside_scope;

DO $$
DECLARE
    before_row backlog_before%ROWTYPE;
    deleted_row deleted_counts%ROWTYPE;
    after_row backlog_after%ROWTYPE;
BEGIN
    SELECT * INTO before_row FROM backlog_before;
    SELECT * INTO deleted_row FROM deleted_counts;
    SELECT * INTO after_row FROM backlog_after;

    IF deleted_row.outbox_deleted <> before_row.outbox_scope
       OR deleted_row.inbox_deleted <> before_row.inbox_scope THEN
        RAISE EXCEPTION 'scope delete counts did not match before counts';
    END IF;
    IF after_row.outbox_scope <> 0 OR after_row.inbox_scope <> 0 THEN
        RAISE EXCEPTION 'scale backlog remained after cleanup';
    END IF;
    IF after_row.outbox_outside_scope <> before_row.outbox_outside_scope
       OR after_row.inbox_outside_scope <> before_row.inbox_outside_scope THEN
        RAISE EXCEPTION 'outside-scope row count changed';
    END IF;
END $$;

SELECT 'IMS_SCALE_BACKLOG_CLEANUP|' || json_build_object(
    'schools', before_row.schools,
    'before', json_build_object(
        'outboxScope', before_row.outbox_scope,
        'inboxScope', before_row.inbox_scope,
        'outboxOutsideScope', before_row.outbox_outside_scope,
        'inboxOutsideScope', before_row.inbox_outside_scope
    ),
    'deleted', json_build_object(
        'outbox', deleted_row.outbox_deleted,
        'inbox', deleted_row.inbox_deleted
    ),
    'after', json_build_object(
        'outboxScope', after_row.outbox_scope,
        'inboxScope', after_row.inbox_scope,
        'outboxOutsideScope', after_row.outbox_outside_scope,
        'inboxOutsideScope', after_row.inbox_outside_scope
    ),
    'schoolsStudentsAttendanceFactsDeleted', false
)::text
FROM backlog_before before_row, deleted_counts deleted_row, backlog_after after_row;

COMMIT;
