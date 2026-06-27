CREATE TABLE IF NOT EXISTS notification_broadcasts (
    id UUID PRIMARY KEY,
    school_id BIGINT,
    module VARCHAR(50),
    title TEXT NOT NULL,
    message TEXT NOT NULL,
    audience_type VARCHAR(50) NOT NULL,
    channels TEXT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    scheduled_at TIMESTAMPTZ,
    approved_by BIGINT,
    approved_at TIMESTAMPTZ,
    sent_by BIGINT,
    sent_at TIMESTAMPTZ,
    created_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_notification_broadcast_school_status ON notification_broadcasts(school_id, status);
CREATE INDEX IF NOT EXISTS idx_notification_broadcast_created ON notification_broadcasts(created_at DESC);

CREATE TABLE IF NOT EXISTS notification_delivery_logs (
    id UUID PRIMARY KEY,
    broadcast_id UUID REFERENCES notification_broadcasts(id),
    recipient_type VARCHAR(30),
    recipient_ref VARCHAR(100),
    channel VARCHAR(30),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    provider_message_id VARCHAR(150),
    failure_reason TEXT,
    delivered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_notification_delivery_broadcast_status ON notification_delivery_logs(broadcast_id, status);

CREATE TABLE IF NOT EXISTS notification_logs (
    id VARCHAR(36) PRIMARY KEY,
    school_id BIGINT,
    student_id BIGINT,
    parent_contact VARCHAR(200),
    channel VARCHAR(30) NOT NULL,
    notification_type VARCHAR(80) NOT NULL,
    message TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    sent_by BIGINT,
    sent_at TIMESTAMPTZ,
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_notification_logs_school_type ON notification_logs(school_id, notification_type);
CREATE INDEX IF NOT EXISTS idx_notification_logs_student ON notification_logs(student_id);
CREATE INDEX IF NOT EXISTS idx_notification_logs_status ON notification_logs(status);
CREATE INDEX IF NOT EXISTS idx_notification_logs_created ON notification_logs(created_at DESC);

DO $$
BEGIN
    IF to_regclass('public.notification_broadcasts') IS NOT NULL THEN
    INSERT INTO notification_broadcasts
        (id, school_id, module, title, message, audience_type, channels, status,
         scheduled_at, approved_by, approved_at, sent_by, sent_at, created_by,
         created_at, updated_at)
    SELECT id, school_id, module, title, message, audience_type, channels, status,
           scheduled_at, approved_by, approved_at, sent_by, sent_at, created_by,
           created_at, updated_at
    FROM public.notification_broadcasts
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.notification_delivery_logs') IS NOT NULL THEN
    INSERT INTO notification_delivery_logs
        (id, broadcast_id, recipient_type, recipient_ref, channel, status,
         provider_message_id, failure_reason, delivered_at, created_at)
    SELECT id, broadcast_id, recipient_type, recipient_ref, channel, status,
           provider_message_id, failure_reason, delivered_at, created_at
    FROM public.notification_delivery_logs
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.notification_logs') IS NOT NULL THEN
    INSERT INTO notification_logs
        (id, school_id, student_id, parent_contact, channel, notification_type,
         message, status, sent_by, sent_at, failure_reason, created_at, updated_at)
    SELECT id, school_id, student_id, parent_contact, channel, notification_type,
           message, status, sent_by, sent_at, failure_reason, created_at, updated_at
    FROM public.notification_logs
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;
