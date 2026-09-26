-- Counters are shared by all identity/gateway replicas. No email, token or password is stored here.
CREATE TABLE identity.request_quotas (
    quota_key VARCHAR(180) PRIMARY KEY,
    used INTEGER NOT NULL CHECK (used > 0),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX request_quotas_expiry ON identity.request_quotas (expires_at);

ALTER TABLE identity.app_users ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE identity.auth_sessions ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE identity.password_reset_tokens (
    token_hash CHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES identity.app_users(id) ON DELETE CASCADE,
    credential_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);
CREATE INDEX password_reset_tokens_expiry ON identity.password_reset_tokens (expires_at);
CREATE INDEX password_reset_tokens_user ON identity.password_reset_tokens (user_id);

-- Durable delivery intent contains no reset secret. Workers create a fresh, hashed token per
-- attempt; any successfully used link invalidates all other links via credential_version.
CREATE TABLE identity.password_reset_deliveries (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES identity.app_users(id) ON DELETE CASCADE,
    credential_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);
CREATE INDEX password_reset_deliveries_due ON identity.password_reset_deliveries (next_attempt_at) WHERE status = 'PENDING';

-- Runtime imports may not preserve owner default privileges. Make the required
-- DML explicit; DELETE is needed by bounded expiry cleanup, not TRUNCATE.
REVOKE ALL ON identity.request_quotas, identity.password_reset_tokens, identity.password_reset_deliveries FROM PUBLIC;
DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
    REVOKE ALL ON identity.request_quotas, identity.password_reset_tokens, identity.password_reset_deliveries FROM app_rt;
    GRANT SELECT, INSERT, UPDATE, DELETE ON identity.request_quotas, identity.password_reset_tokens, identity.password_reset_deliveries TO app_rt;
END IF; END $$;
