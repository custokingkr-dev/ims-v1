\set ON_ERROR_STOP on

BEGIN READ ONLY;
SET LOCAL app.bypass_rls = 'on';

SELECT 'IMS_ASYNC_STATUS|' || json_build_object(
    'certificationId', :'certification_id',
    'school', (SELECT json_build_object(
        'rows', count(*),
        'published', count(*) FILTER (WHERE published_at IS NOT NULL),
        'deadLettered', count(*) FILTER (WHERE dead_lettered_at IS NOT NULL),
        'attempts', COALESCE(sum(attempts), 0))
      FROM tenant_school.outbox_events
      WHERE event_key = 'certification:' || :'certification_id' || ':school'),
    'operations', (SELECT json_build_object(
        'rows', count(*),
        'published', count(*) FILTER (WHERE published_at IS NOT NULL),
        'deadLettered', count(*) FILTER (WHERE dead_lettered_at IS NOT NULL),
        'attempts', COALESCE(sum(attempts), 0))
      FROM firefighting.outbox_events
      WHERE event_key = 'certification:' || :'certification_id' || ':operations'),
    'billing', (SELECT json_build_object(
        'rows', count(*),
        'published', count(*) FILTER (WHERE published_at IS NOT NULL),
        'deadLettered', count(*) FILTER (WHERE dead_lettered_at IS NOT NULL),
        'attempts', COALESCE(sum(attempts), 0))
      FROM billing.outbox_events
      WHERE event_key = 'certification:' || :'certification_id' || ':billing'),
    'reportingInbox', (SELECT json_build_object(
        'rows', count(*),
        'processed', count(*) FILTER (WHERE status = 'PROCESSED'),
        'failed', count(*) FILTER (WHERE status IN ('FAILED', 'DEAD_LETTER')))
      FROM reporting.reporting_event_inbox
      WHERE aggregate_type = 'ReliabilityCertification'
        AND aggregate_id = :'certification_id')
)::text;

ROLLBACK;
