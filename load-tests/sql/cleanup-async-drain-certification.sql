\set ON_ERROR_STOP on

BEGIN;
SET LOCAL app.bypass_rls = 'on';
SELECT pg_advisory_xact_lock(hashtext('ims-async-drain-certification'));

WITH deleted_inbox AS (
    DELETE FROM reporting.reporting_event_inbox
    WHERE aggregate_type = 'ReliabilityCertification'
      AND aggregate_id = :'certification_id'
    RETURNING 1
), deleted_school AS (
    DELETE FROM tenant_school.outbox_events
    WHERE aggregate_type = 'ReliabilityCertification'
      AND aggregate_id = :'certification_id'
      AND event_key = 'certification:' || :'certification_id' || ':school'
    RETURNING 1
), deleted_operations AS (
    DELETE FROM firefighting.outbox_events
    WHERE aggregate_type = 'ReliabilityCertification'
      AND aggregate_id = :'certification_id'
      AND event_key = 'certification:' || :'certification_id' || ':operations'
    RETURNING 1
), deleted_billing AS (
    DELETE FROM billing.outbox_events
    WHERE aggregate_type = 'ReliabilityCertification'
      AND aggregate_id = :'certification_id'
      AND event_key = 'certification:' || :'certification_id' || ':billing'
    RETURNING 1
)
SELECT 'IMS_ASYNC_CLEANUP|' || json_build_object(
    'certificationId', :'certification_id',
    'reportingInboxDeleted', (SELECT count(*) FROM deleted_inbox),
    'schoolOutboxDeleted', (SELECT count(*) FROM deleted_school),
    'operationsOutboxDeleted', (SELECT count(*) FROM deleted_operations),
    'billingOutboxDeleted', (SELECT count(*) FROM deleted_billing)
)::text;

COMMIT;
