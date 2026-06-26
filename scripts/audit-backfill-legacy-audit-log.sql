-- One-time historical migration from the monolith audit table into audit-service storage.
-- Run manually from an operator psql session before dropping public.audit_log.
-- This is intentionally not exposed by audit-service runtime APIs.

INSERT INTO audit.audit_events
    (action, user_id, school_id, entity_type, entity_id, ip_address, user_agent,
     request_id, actor_email, old_value, new_value, outcome, event_timestamp, received_at)
SELECT l.action,
       COALESCE(l.actor_user_id, l.user_id),
       l.school_id,
       l.entity_type,
       l.entity_id,
       LEFT(l.ip_address, 64),
       LEFT(l.user_agent, 512),
       LEFT(l.request_id, 64),
       LEFT(l.actor_email, 255),
       l.old_value,
       l.new_value,
       COALESCE(NULLIF(l.outcome, ''), 'SUCCESS'),
       COALESCE(l.event_time, l.timestamp, now()),
       now()
FROM public.audit_log l
WHERE NOT EXISTS (
    SELECT 1
    FROM audit.audit_events e
    WHERE e.action = l.action
      AND COALESCE(e.user_id, -1) = COALESCE(COALESCE(l.actor_user_id, l.user_id), -1)
      AND COALESCE(e.school_id, -1) = COALESCE(l.school_id, -1)
      AND COALESCE(e.entity_type, '') = COALESCE(l.entity_type, '')
      AND COALESCE(e.entity_id, '') = COALESCE(l.entity_id, '')
      AND e.event_timestamp = COALESCE(l.event_time, l.timestamp, now())
)
ORDER BY l.id ASC;
