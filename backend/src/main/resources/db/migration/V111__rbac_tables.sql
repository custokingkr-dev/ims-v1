-- RBAC tables: roles, permissions, role_permissions (join), user_role_assignments

CREATE TABLE IF NOT EXISTS roles (
    id          BIGSERIAL    NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT uk_role_name UNIQUE (name)
);

CREATE TABLE IF NOT EXISTS permissions (
    id          BIGSERIAL    NOT NULL,
    code        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT uk_permission_code UNIQUE (code)
);

-- Join table — no surrogate PK needed; composite PK prevents duplicates
CREATE TABLE IF NOT EXISTS role_permissions (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_rp_role       FOREIGN KEY (role_id)       REFERENCES roles (id) ON DELETE CASCADE,
    CONSTRAINT fk_rp_permission FOREIGN KEY (permission_id) REFERENCES permissions (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS user_role_assignments (
    id          BIGSERIAL   NOT NULL,
    user_id     BIGINT      NOT NULL,
    role_id     BIGINT      NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_by BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_role UNIQUE (user_id, role_id),
    CONSTRAINT fk_ura_user  FOREIGN KEY (user_id) REFERENCES app_users (id) ON DELETE CASCADE,
    CONSTRAINT fk_ura_role  FOREIGN KEY (role_id) REFERENCES roles    (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_user_role_assignments_user_id ON user_role_assignments (user_id);
CREATE INDEX IF NOT EXISTS idx_role_permissions_role_id      ON role_permissions (role_id);
