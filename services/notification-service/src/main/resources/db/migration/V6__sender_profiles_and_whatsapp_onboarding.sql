CREATE TABLE IF NOT EXISTS notification_sender_profiles (
    id UUID PRIMARY KEY,
    school_id BIGINT,
    profile_name VARCHAR(160) NOT NULL,
    email_from_name VARCHAR(160),
    email_from_address VARCHAR(320),
    email_reply_to VARCHAR(320),
    whatsapp_integrated_number VARCHAR(40),
    whatsapp_display_name VARCHAR(160),
    whatsapp_template_namespace VARCHAR(160),
    whatsapp_default_template_name VARCHAR(160),
    whatsapp_language_code VARCHAR(20) NOT NULL DEFAULT 'en',
    msg91_sms_flow_id VARCHAR(160),
    msg91_otp_template_id VARCHAR(160),
    msg91_email_template_id VARCHAR(160),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_sender_profiles_default
    ON notification_sender_profiles ((school_id IS NULL))
    WHERE school_id IS NULL AND active = TRUE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_sender_profiles_school
    ON notification_sender_profiles (school_id)
    WHERE school_id IS NOT NULL AND active = TRUE;

CREATE INDEX IF NOT EXISTS idx_notification_sender_profiles_school_active
    ON notification_sender_profiles (school_id, active);

CREATE TABLE IF NOT EXISTS whatsapp_onboarding_sessions (
    id UUID PRIMARY KEY,
    school_id BIGINT NOT NULL,
    provider VARCHAR(40) NOT NULL DEFAULT 'MSG91',
    status VARCHAR(40) NOT NULL DEFAULT 'REQUESTED',
    requested_by BIGINT,
    school_name VARCHAR(200),
    contact_name VARCHAR(160),
    contact_email VARCHAR(320),
    contact_mobile VARCHAR(40),
    desired_display_name VARCHAR(160),
    desired_phone_number VARCHAR(40),
    notes TEXT,
    provider_reference VARCHAR(220),
    integrated_number VARCHAR(40),
    failure_reason TEXT,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_whatsapp_onboarding_school_status
    ON whatsapp_onboarding_sessions (school_id, status, requested_at DESC);

INSERT INTO notification_sender_profiles (
    id,
    school_id,
    profile_name,
    email_from_name,
    email_from_address,
    whatsapp_display_name,
    whatsapp_language_code,
    active
)
SELECT
    '00000000-0000-0000-0000-000000000001',
    NULL,
    'Custoking Platform Default',
    'Custoking Support',
    'support@custoking.com',
    'Custoking',
    'en',
    TRUE
WHERE NOT EXISTS (
    SELECT 1
    FROM notification_sender_profiles
    WHERE school_id IS NULL AND active = TRUE
);
