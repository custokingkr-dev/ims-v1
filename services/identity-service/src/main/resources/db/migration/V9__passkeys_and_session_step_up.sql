CREATE TABLE identity.passkey_credentials (
    credential_id text PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES identity.app_users(id) ON DELETE CASCADE,
    public_key_cose text NOT NULL,
    signature_count bigint NOT NULL CHECK (signature_count >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz
);
CREATE INDEX passkey_credentials_user_idx ON identity.passkey_credentials(user_id);
CREATE TABLE identity.passkey_challenges (
    id uuid PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES identity.app_users(id) ON DELETE CASCADE,
    session_id varchar(255) NOT NULL REFERENCES identity.auth_sessions(id) ON DELETE CASCADE,
    purpose varchar(16) NOT NULL CHECK (purpose IN ('REGISTER','ASSERT')),
    request_json text NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz
);
CREATE INDEX passkey_challenges_expiry_idx ON identity.passkey_challenges(expires_at);
CREATE TABLE identity.session_step_up (
    family_id varchar(64) PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES identity.app_users(id) ON DELETE CASCADE,
    credential_version bigint NOT NULL,
    verified_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL
);
DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='app_rt') THEN
    REVOKE ALL ON identity.passkey_credentials,identity.passkey_challenges,identity.session_step_up FROM app_rt;
    GRANT SELECT,INSERT,UPDATE,DELETE ON identity.passkey_credentials,identity.passkey_challenges,identity.session_step_up TO app_rt;
END IF; END $$;
