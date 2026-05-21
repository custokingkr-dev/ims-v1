-- V124: RBAC audit log table for tracking role/permission lifecycle events.
-- Records every assign, revoke, create, update, disable event on RBAC objects.
-- Forward-only, additive migration.

CREATE TABLE IF NOT EXISTS rbac_audit_log (
    id              BIGSERIAL PRIMARY KEY,
    event_type      VARCHAR(50) NOT NULL,   -- ROLE_CREATED, ROLE_UPDATED, ROLE_DISABLED,
                                             -- PERMISSION_ASSIGNED, PERMISSION_REMOVED,
                                             -- ROLE_ASSIGNED, ROLE_REVOKED,
                                             -- ROLE_SCOPE_CHANGED, ROLE_EXPIRY_CHANGED,
                                             -- USER_DISABLED, PASSWORD_RESET
    actor_user_id   BIGINT,
    actor_email     VARCHAR(255),
    target_user_id  BIGINT,
    role_id         BIGINT,
    role_name       VARCHAR(100),
    permission_codes TEXT,                  -- comma-separated list of affected permission codes
    school_id       BIGINT,
    zone_id         BIGINT,
    old_value       TEXT,
    new_value       TEXT,
    ip_address      VARCHAR(45),
    user_agent      TEXT,
    correlation_id  VARCHAR(64),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rbac_audit_actor    ON rbac_audit_log (actor_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_rbac_audit_target   ON rbac_audit_log (target_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_rbac_audit_event    ON rbac_audit_log (event_type, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_rbac_audit_school   ON rbac_audit_log (school_id, created_at DESC) WHERE school_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_rbac_audit_created  ON rbac_audit_log (created_at DESC);

COMMENT ON TABLE rbac_audit_log IS
    'Immutable audit trail for all RBAC lifecycle events (assignments, revocations, role/permission changes).';
