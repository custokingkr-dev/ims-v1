-- RLS backstop for notification schema tenant tables.
--
-- notification_broadcasts, notification_logs, and whatsapp_onboarding_sessions carry a school_id
-- and are now RLS-protected.
--
-- notification_delivery_logs, notification_inbox_events, and notification_delivery_attempts
-- intentionally remain WITHOUT RLS here (no school_id column) — do not add policies to those.
--
-- notification_sender_profiles is DELIBERATELY EXCLUDED from RLS: the MSG91 delivery pipeline
-- resolves the sender profile (SenderProfileRepository.resolve) from CONTEXT-LESS background
-- threads — the @Scheduled NotificationInboxRetryService and the internal Pub/Sub push
-- (PubSubPushController), neither of which has a TenantContext. Under RLS those reads would see
-- zero rows and silently fall back to the hardcoded default, losing per-school sender identity
-- (esp. the WhatsApp integrated number, which has no property fallback). Its write path is already
-- app-scoped via TenantScope.resolveSchoolId in SenderProfileController.
--
-- LOGS BYPASS REQUIREMENT: notification_logs is written by
-- NotificationLogCommandRepository.createRequestLog, invoked via the internal-token-gated
-- POST /api/v1/notifications/logs system ingestion endpoint. Callers of that endpoint may have
-- NO matching TenantContext (system-to-system calls with no authenticated school), so a plain
-- WITH CHECK would reject those inserts. createRequestLog MUST call
-- ProjectorRls.allow(jdbcClient) as the first statement of its @Transactional method, mirroring
-- reporting/V22__enable_rls_facts_dims.sql's projector bypass pattern.

ALTER TABLE notification_broadcasts ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON notification_broadcasts;
CREATE POLICY tenant_isolation ON notification_broadcasts
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE notification_logs ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON notification_logs;
CREATE POLICY tenant_isolation ON notification_logs
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE whatsapp_onboarding_sessions ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON whatsapp_onboarding_sessions;
CREATE POLICY tenant_isolation ON whatsapp_onboarding_sessions
  USING      (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');
