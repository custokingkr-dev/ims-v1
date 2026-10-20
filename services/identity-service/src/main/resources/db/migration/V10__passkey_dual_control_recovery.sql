CREATE TABLE identity.passkey_recovery_requests (
    id uuid PRIMARY KEY,
    target_user_id bigint NOT NULL REFERENCES identity.app_users(id),
    requested_by bigint NOT NULL REFERENCES identity.app_users(id),
    reason varchar(1000) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    approved_by bigint REFERENCES identity.app_users(id),
    completed_at timestamptz,
    CHECK (requested_by <> target_user_id),
    CHECK (approved_by IS NULL OR (approved_by <> requested_by AND approved_by <> target_user_id))
);
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='app_rt') THEN
    GRANT SELECT,INSERT,UPDATE ON identity.passkey_recovery_requests TO app_rt;
END IF; END $$;
