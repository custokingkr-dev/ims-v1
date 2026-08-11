\set ON_ERROR_STOP on

BEGIN;
SET LOCAL app.bypass_rls = 'on';
SELECT pg_advisory_xact_lock(hashtext('ims-async-drain-certification'));

WITH school_event AS (
    INSERT INTO tenant_school.outbox_events(
        event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
    VALUES (
        'certification:' || :'certification_id' || ':school',
        'certification.async-drain.v1', 'ReliabilityCertification', :'certification_id',
        :base_school_id::bigint,
        jsonb_build_object('certificationId', :'certification_id', 'source', 'school-core'))
    RETURNING id
), operations_event AS (
    INSERT INTO firefighting.outbox_events(
        event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
    VALUES (
        'certification:' || :'certification_id' || ':operations',
        'certification.async-drain.v1', 'ReliabilityCertification', :'certification_id',
        :base_school_id::bigint,
        jsonb_build_object('certificationId', :'certification_id', 'source', 'operations'))
    RETURNING id
), billing_event AS (
    INSERT INTO billing.outbox_events(
        event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
    VALUES (
        'certification:' || :'certification_id' || ':billing',
        'certification.async-drain.v1', 'ReliabilityCertification', :'certification_id',
        :base_school_id::bigint,
        jsonb_build_object('certificationId', :'certification_id', 'source', 'billing'))
    RETURNING id
)
SELECT 'IMS_ASYNC_SEED|' || json_build_object(
    'certificationId', :'certification_id',
    'schoolEventId', (SELECT id FROM school_event),
    'operationsEventId', (SELECT id FROM operations_event),
    'billingEventId', (SELECT id FROM billing_event)
)::text;

COMMIT;
