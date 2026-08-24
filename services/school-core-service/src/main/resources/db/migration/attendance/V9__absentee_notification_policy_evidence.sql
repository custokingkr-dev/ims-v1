ALTER TABLE attendance.absentee_notifications
    ADD COLUMN guardian_id VARCHAR(64),
    ADD COLUMN consent_event_id VARCHAR(64),
    ADD COLUMN consent_notice_version VARCHAR(64),
    ADD COLUMN policy_version VARCHAR(64),
    ADD COLUMN policy_decision VARCHAR(32),
    ADD COLUMN destination_sha256 VARCHAR(64),
    ADD COLUMN policy_evaluated_at TIMESTAMPTZ,
    ADD COLUMN policy_expires_at TIMESTAMPTZ;

-- Rows queued before the v2 binding contract cannot be proven safe at dispatch time. Quarantine
-- them instead of silently treating nullable legacy evidence as authorization.
UPDATE attendance.absentee_notifications
SET status = 'SUPPRESSED'
WHERE status = 'QUEUED';

ALTER TABLE attendance.absentee_notifications
    ADD CONSTRAINT chk_absentee_notification_v2_policy
    CHECK (status <> 'QUEUED' OR (
        guardian_id IS NOT NULL
        AND length(trim(guardian_id)) > 0
        AND consent_event_id IS NOT NULL
        AND length(trim(consent_event_id)) > 0
        AND consent_notice_version IS NOT NULL
        AND length(trim(consent_notice_version)) > 0
        AND policy_version = 'guardian-communications.v2'
        AND policy_decision = 'ALLOW'
        AND destination_sha256 IS NOT NULL
        AND destination_sha256 ~ '^[0-9a-f]{64}$'
        AND policy_evaluated_at IS NOT NULL
        AND policy_expires_at IS NOT NULL
        AND policy_expires_at > policy_evaluated_at
        AND policy_expires_at <= policy_evaluated_at + INTERVAL '2 minutes'
    ));

COMMENT ON COLUMN attendance.absentee_notifications.guardian_id IS
    'Primary guardian selected by school-core communication policy at queue time.';
COMMENT ON COLUMN attendance.absentee_notifications.consent_event_id IS
    'Immutable SCHOOL_COMMUNICATIONS consent event authorizing this queued notification.';
COMMENT ON COLUMN attendance.absentee_notifications.consent_notice_version IS
    'Notice/version recorded by the immutable consent event.';
COMMENT ON COLUMN attendance.absentee_notifications.policy_version IS
    'Producer-side communication policy contract version.';
COMMENT ON COLUMN attendance.absentee_notifications.policy_decision IS
    'ALLOW for rows admitted to the queue; denied candidates are not persisted.';
COMMENT ON COLUMN attendance.absentee_notifications.destination_sha256 IS
    'SHA-256 of the channel-normalized destination bound by the v2 policy decision.';
COMMENT ON COLUMN attendance.absentee_notifications.policy_evaluated_at IS
    'Timestamp at which the producer evaluated current consent and preferences.';
COMMENT ON COLUMN attendance.absentee_notifications.policy_expires_at IS
    'Short technical expiry after which dispatch must re-evaluate or suppress.';
