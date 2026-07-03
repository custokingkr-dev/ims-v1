CREATE TABLE IF NOT EXISTS command_center_actions (
    id UUID PRIMARY KEY,
    school_id BIGINT,
    module VARCHAR(50) NOT NULL,
    urgency VARCHAR(20) NOT NULL,
    confidence INT NOT NULL DEFAULT 80,
    title TEXT NOT NULL,
    reason TEXT,
    impact TEXT,
    current_state VARCHAR(100),
    target_state VARCHAR(100),
    cta_label VARCHAR(100),
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    source_type VARCHAR(50),
    source_id VARCHAR(100),
    accepted_by BIGINT,
    accepted_at TIMESTAMPTZ,
    dismissed_by BIGINT,
    dismissed_reason TEXT,
    dismissed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_cca_school_status_urgency ON command_center_actions(school_id, status, urgency);
CREATE INDEX IF NOT EXISTS idx_cca_status ON command_center_actions(status);
CREATE INDEX IF NOT EXISTS idx_cca_created ON command_center_actions(created_at DESC);

CREATE TABLE IF NOT EXISTS command_center_feed (
    id UUID PRIMARY KEY,
    school_id BIGINT,
    module VARCHAR(50) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    title TEXT NOT NULL,
    message TEXT,
    severity VARCHAR(20) DEFAULT 'info',
    entity_type VARCHAR(80),
    entity_id VARCHAR(100),
    actor_user_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_cc_feed_school_created ON command_center_feed(school_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_cc_feed_created ON command_center_feed(created_at DESC);

DO $$
BEGIN
    IF to_regclass('public.command_center_actions') IS NOT NULL THEN
    INSERT INTO command_center_actions
        (id, school_id, module, urgency, confidence, title, reason, impact,
         current_state, target_state, cta_label, status, source_type, source_id,
         accepted_by, accepted_at, dismissed_by, dismissed_reason, dismissed_at,
         created_at, updated_at)
    SELECT id, school_id, module, urgency, confidence, title, reason, impact,
           current_state, target_state, cta_label, status, source_type, source_id,
           accepted_by, accepted_at, dismissed_by, dismissed_reason, dismissed_at,
           created_at, updated_at
    FROM public.command_center_actions
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.command_center_feed') IS NOT NULL THEN
    INSERT INTO command_center_feed
        (id, school_id, module, event_type, title, message, severity,
         entity_type, entity_id, actor_user_id, created_at)
    SELECT id, school_id, module, event_type, title, message, severity,
           entity_type, entity_id, actor_user_id, created_at
    FROM public.command_center_feed
    ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;
