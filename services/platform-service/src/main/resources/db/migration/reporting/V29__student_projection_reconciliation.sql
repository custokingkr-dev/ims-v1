-- Reconcile the student read model against its durable event source without
-- crossing the service-owned schema boundary. Only successfully processed events
-- are expectations; RECEIVED/FAILED/DEAD_LETTER rows are operational backlog.

CREATE OR REPLACE VIEW reporting.student_projection_reconciliation
AS
WITH latest_event AS (
    SELECT DISTINCT ON (aggregate_id::BIGINT)
        aggregate_id::BIGINT AS student_id,
        event_id,
        event_type,
        status,
        school_id,
        COALESCE(occurred_at, received_at) AS event_time,
        payload::jsonb AS payload
    FROM reporting.reporting_event_inbox
    WHERE event_type IN ('student.upserted.v1', 'student.deleted.v1')
      AND aggregate_id ~ '^[0-9]+$'
    ORDER BY aggregate_id::BIGINT,
             COALESCE(occurred_at, received_at) DESC,
             received_at DESC,
             event_id DESC
)
SELECT
    event.student_id,
    event.school_id,
    event.event_id,
    event.event_type,
    event.event_time,
    CASE
        WHEN event.event_type = 'student.deleted.v1' AND dimension.id IS NOT NULL
            THEN 'DELETED_STUDENT_STILL_PROJECTED'
        WHEN event.event_type = 'student.deleted.v1' AND tombstone.student_id IS NULL
            THEN 'DELETION_TOMBSTONE_MISSING'
        WHEN event.event_type = 'student.upserted.v1' AND dimension.id IS NULL
            THEN 'STUDENT_PROJECTION_MISSING'
        WHEN event.event_type = 'student.upserted.v1' AND (
            dimension.school_id IS DISTINCT FROM (event.payload ->> 'schoolId')::BIGINT
            OR dimension.admission_no IS DISTINCT FROM event.payload ->> 'admissionNo'
            OR dimension.full_name IS DISTINCT FROM event.payload ->> 'fullName'
            OR dimension.roll_no IS DISTINCT FROM event.payload ->> 'rollNo'
            OR dimension.class_id IS DISTINCT FROM event.payload ->> 'classId'
            OR dimension.section_id IS DISTINCT FROM event.payload ->> 'sectionId'
            OR dimension.parent_contact IS DISTINCT FROM event.payload ->> 'parentContact'
            OR dimension.phone IS DISTINCT FROM event.payload ->> 'phone'
            OR dimension.active IS DISTINCT FROM COALESCE((event.payload ->> 'active')::BOOLEAN, FALSE)
            OR dimension.attendance_percent IS DISTINCT FROM (event.payload ->> 'attendancePercent')::NUMERIC
            OR dimension.father_name IS DISTINCT FROM event.payload ->> 'fatherName'
        ) THEN 'STUDENT_PROJECTION_STALE'
        ELSE NULL
    END AS issue
FROM latest_event event
LEFT JOIN reporting.dim_student dimension ON dimension.id = event.student_id
LEFT JOIN reporting.student_projection_tombstones tombstone ON tombstone.student_id = event.student_id
WHERE event.status = 'PROCESSED';

CREATE OR REPLACE VIEW reporting.student_projection_reconciliation_summary
AS
SELECT
    count(*) AS expected_students,
    count(*) FILTER (WHERE issue IS NULL) AS reconciled_students,
    count(*) FILTER (WHERE issue IS NOT NULL) AS issue_rows,
    max(event_time) AS newest_processed_student_event
FROM reporting.student_projection_reconciliation;

-- Owner-operated repair: requeue only the newest successfully processed event for
-- one student. The normal leased projector performs the actual repair, preserving
-- idempotency, tombstone ordering, retry, and dead-letter behavior.
CREATE OR REPLACE FUNCTION reporting.requeue_student_projection(p_student_id BIGINT)
RETURNS BOOLEAN
LANGUAGE plpgsql
AS $$
DECLARE
    v_event_id VARCHAR(120);
    v_status VARCHAR(40);
BEGIN
    SELECT event_id, status INTO v_event_id, v_status
    FROM reporting.reporting_event_inbox
    WHERE event_type IN ('student.upserted.v1', 'student.deleted.v1')
      AND aggregate_id = p_student_id::text
    ORDER BY COALESCE(occurred_at, received_at) DESC, received_at DESC, event_id DESC
    LIMIT 1;

    -- Never replay an older event ahead of a newer pending/failed/dead-letter event.
    IF v_event_id IS NULL OR v_status <> 'PROCESSED' THEN
        RETURN FALSE;
    END IF;

    UPDATE reporting.reporting_event_inbox
    SET status = 'RECEIVED',
        processed_at = NULL,
        last_error = NULL,
        attempt_count = 0,
        next_attempt_at = now(),
        claimed_at = NULL
    WHERE event_id = v_event_id;

    RETURN TRUE;
END
$$;

REVOKE ALL ON FUNCTION reporting.requeue_student_projection(BIGINT) FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        REVOKE ALL ON FUNCTION reporting.requeue_student_projection(BIGINT) FROM app_rt;
    END IF;
END
$$;
