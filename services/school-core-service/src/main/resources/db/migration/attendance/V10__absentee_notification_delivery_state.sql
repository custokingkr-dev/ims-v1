-- Delivery state for the absentee-notification drainer. Until now rows were inserted as QUEUED and
-- nothing ever drained them; the worker needs a retry budget, a backoff clock, and a place to record
-- the provider's answer so staff can see SENT / FAILED per row.
--
-- Status vocabulary (see AbsenteeNotificationStatus): QUEUED, SENT, SENT_DRY_RUN, SUPPRESSED, FAILED,
-- DEAD_LETTER. SENT is reserved for a real, non-dry-run provider send.
ALTER TABLE attendance.absentee_notifications
    ADD COLUMN IF NOT EXISTS attempts            INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_attempt_at     TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_attempt_at     TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_error          VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS provider            VARCHAR(40),
    ADD COLUMN IF NOT EXISTS provider_message_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS delivered_at        TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS dead_lettered_at    TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now();

-- The drainer claims oldest-first among rows that are QUEUED or FAILED-and-due.
CREATE INDEX IF NOT EXISTS idx_absentee_notif_claimable
    ON attendance.absentee_notifications (created_at)
    WHERE status IN ('QUEUED', 'FAILED');

CREATE INDEX IF NOT EXISTS idx_absentee_notif_dead_lettered
    ON attendance.absentee_notifications (dead_lettered_at)
    WHERE dead_lettered_at IS NOT NULL;

COMMENT ON COLUMN attendance.absentee_notifications.attempts IS
    'Delivery attempts consumed so far (claim + gateway call). Bounded by the worker retry budget.';
COMMENT ON COLUMN attendance.absentee_notifications.next_attempt_at IS
    'Earliest time a FAILED row may be claimed again; NULL when not scheduled.';
COMMENT ON COLUMN attendance.absentee_notifications.provider IS
    'Provider that answered the last attempt (dry-run, logging, msg91).';
COMMENT ON COLUMN attendance.absentee_notifications.provider_message_id IS
    'Provider-side correlation id for a real send, when the provider returns one.';
COMMENT ON COLUMN attendance.absentee_notifications.delivered_at IS
    'Set only when a real, non-dry-run send was accepted by the provider.';
COMMENT ON COLUMN attendance.absentee_notifications.dead_lettered_at IS
    'Set when the retry budget is exhausted or the peer reported a permanent failure.';
