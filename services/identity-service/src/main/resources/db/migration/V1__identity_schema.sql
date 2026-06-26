CREATE SEQUENCE IF NOT EXISTS seq_app_users START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS app_users (
    id BIGINT PRIMARY KEY DEFAULT nextval('seq_app_users'),
    full_name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(255) NOT NULL,
    branch_id BIGINT,
    branch_name VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    deleted_by VARCHAR(255),
    zone_id BIGINT,
    zone_name VARCHAR(255),
    CONSTRAINT uk_identity_app_user_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS auth_sessions (
    id VARCHAR(255) PRIMARY KEY,
    access_token_hash VARCHAR(200) NOT NULL,
    refresh_token_hash VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    user_id BIGINT NOT NULL REFERENCES app_users(id)
);

CREATE TABLE IF NOT EXISTS roles (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS permissions (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(150) NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS role_permissions (
    role_id BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE IF NOT EXISTS user_role_assignments (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_by BIGINT,
    revoked_by BIGINT,
    revoked_at TIMESTAMPTZ,
    school_id BIGINT,
    zone_id BIGINT,
    active BOOLEAN NOT NULL DEFAULT true,
    valid_from TIMESTAMPTZ,
    valid_until TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS rbac_audit_log (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    actor_user_id BIGINT,
    actor_email VARCHAR(255),
    target_user_id BIGINT,
    role_id BIGINT,
    role_name VARCHAR(100),
    permission_codes TEXT,
    school_id BIGINT,
    zone_id BIGINT,
    old_value TEXT,
    new_value TEXT,
    ip_address VARCHAR(100),
    user_agent TEXT,
    correlation_id VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_identity_auth_session_access_hash ON auth_sessions(access_token_hash);
CREATE UNIQUE INDEX IF NOT EXISTS idx_identity_auth_session_refresh_hash ON auth_sessions(refresh_token_hash);
CREATE INDEX IF NOT EXISTS idx_identity_auth_sessions_user ON auth_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_identity_app_users_role ON app_users(role);
CREATE INDEX IF NOT EXISTS idx_identity_app_users_role_branch ON app_users(role, branch_id);
CREATE INDEX IF NOT EXISTS idx_identity_app_users_role_branch_id ON app_users(role, branch_id);
CREATE INDEX IF NOT EXISTS idx_identity_app_users_zone_id ON app_users(zone_id);
CREATE INDEX IF NOT EXISTS idx_identity_user_role_assignments_user_id ON user_role_assignments(user_id);
CREATE INDEX IF NOT EXISTS idx_identity_role_permissions_role_id ON role_permissions(role_id);
CREATE INDEX IF NOT EXISTS idx_identity_ura_user_scope_active ON user_role_assignments (user_id, role_id, COALESCE(school_id, -1), COALESCE(zone_id, -1));
CREATE INDEX IF NOT EXISTS idx_identity_ura_user_active ON user_role_assignments(user_id, active);
CREATE INDEX IF NOT EXISTS idx_identity_ura_school_active ON user_role_assignments(school_id, active) WHERE school_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_identity_ura_zone_active ON user_role_assignments(zone_id, active) WHERE zone_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_identity_roles_name_lower ON roles(LOWER(name));

INSERT INTO app_users
    (id, full_name, email, password_hash, role, branch_id, branch_name,
     created_at, deleted_at, deleted_by, zone_id, zone_name)
SELECT id, full_name, email, password_hash, role, branch_id, branch_name,
       created_at, deleted_at, deleted_by, zone_id, zone_name
FROM public.app_users
ON CONFLICT (id) DO NOTHING;

INSERT INTO auth_sessions
    (id, access_token_hash, refresh_token_hash, created_at, expires_at, user_id)
SELECT id, access_token_hash, refresh_token_hash, created_at, expires_at, user_id
FROM public.auth_sessions
ON CONFLICT (id) DO NOTHING;

INSERT INTO roles (id, name, description, created_at)
SELECT id, name, description, created_at
FROM public.roles
ON CONFLICT (id) DO NOTHING;

INSERT INTO permissions (id, code, description, created_at)
SELECT id, code, description, created_at
FROM public.permissions
ON CONFLICT (id) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT role_id, permission_id
FROM public.role_permissions
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO user_role_assignments
    (id, user_id, role_id, assigned_at, assigned_by, revoked_by, revoked_at,
     school_id, zone_id, active, valid_from, valid_until)
SELECT id, user_id, role_id, assigned_at, assigned_by, revoked_by, revoked_at,
       school_id, zone_id, active, valid_from, valid_until
FROM public.user_role_assignments
ON CONFLICT (id) DO NOTHING;

INSERT INTO rbac_audit_log
    (id, event_type, actor_user_id, actor_email, target_user_id, role_id,
     role_name, permission_codes, school_id, zone_id, old_value, new_value,
     ip_address, user_agent, correlation_id, created_at)
SELECT id, event_type, actor_user_id, actor_email, target_user_id, role_id,
       role_name, permission_codes, school_id, zone_id, old_value, new_value,
       ip_address, user_agent, correlation_id, created_at
FROM public.rbac_audit_log
ON CONFLICT (id) DO NOTHING;

SELECT setval('seq_app_users', COALESCE((SELECT max(id) FROM app_users), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('roles', 'id'), COALESCE((SELECT max(id) FROM roles), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('permissions', 'id'), COALESCE((SELECT max(id) FROM permissions), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('user_role_assignments', 'id'), COALESCE((SELECT max(id) FROM user_role_assignments), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('rbac_audit_log', 'id'), COALESCE((SELECT max(id) FROM rbac_audit_log), 0) + 1, false);
