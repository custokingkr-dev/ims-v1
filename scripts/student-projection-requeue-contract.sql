\set ON_ERROR_STOP on

SELECT :'approval_reference' =
       'projection-requeue-v1:3fb7f7a0c94560561684f02754e9dec824feb6ae1c2e032664d88949de28fb17'
       AS approval_reference_matches \gset
\if :approval_reference_matches
\else
    \echo 'Projection approval reference mismatch.'
    \quit 4
\endif

WITH approved_candidate AS (
    SELECT student_id
    FROM reporting.student_projection_reconciliation
    WHERE issue = 'STUDENT_PROJECTION_MISSING'
      AND event_type = 'student.upserted.v1'
      AND event_time = TIMESTAMPTZ '2026-07-09 14:48:15.857339+00'
      AND encode(sha256(convert_to(event_id, 'UTF8')), 'hex') =
          '24f8451ab051ca6af81dba50022dc20cebf2572500ffcd69a7a5cf72b5ca8479'
)
SELECT
    (SELECT count(*)
     FROM reporting.student_projection_reconciliation
     WHERE issue IS NOT NULL) = 1 AS exactly_one_current_issue,
    (SELECT count(*) FROM approved_candidate) = 1 AS exactly_one_approved_candidate
\gset

\if :exactly_one_current_issue
\else
    \echo 'Projection issue count changed; refusing requeue.'
    \quit 5
\endif
\if :exactly_one_approved_candidate
\else
    \echo 'Approved projection candidate changed; refusing requeue.'
    \quit 6
\endif

WITH approved_candidate AS (
    SELECT student_id
    FROM reporting.student_projection_reconciliation
    WHERE issue = 'STUDENT_PROJECTION_MISSING'
      AND event_type = 'student.upserted.v1'
      AND event_time = TIMESTAMPTZ '2026-07-09 14:48:15.857339+00'
      AND encode(sha256(convert_to(event_id, 'UTF8')), 'hex') =
          '24f8451ab051ca6af81dba50022dc20cebf2572500ffcd69a7a5cf72b5ca8479'
)
SELECT reporting.requeue_student_projection(student_id) AS approved_event_requeued
FROM approved_candidate
\gset

\if :approved_event_requeued
\else
    \echo 'Owner requeue function rejected the approved latest event.'
    \quit 7
\endif

SELECT count(*) = 1 AS approved_event_is_received
FROM reporting.reporting_event_inbox
WHERE encode(sha256(convert_to(event_id, 'UTF8')), 'hex') =
      '24f8451ab051ca6af81dba50022dc20cebf2572500ffcd69a7a5cf72b5ca8479'
  AND status = 'RECEIVED'
  AND processed_at IS NULL
  AND last_error IS NULL
  AND attempt_count = 0
  AND claimed_at IS NULL
\gset

\if :approved_event_is_received
\else
    \echo 'Approved event did not reach the exact clean RECEIVED state.'
    \quit 8
\endif

\echo 'Approved single projection event requeued successfully.'
