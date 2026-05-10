-- Zone hierarchy: zones, zone_school_mappings, zone_admin_assignments

CREATE TABLE IF NOT EXISTS zones (
    id          BIGSERIAL    NOT NULL,
    name        VARCHAR(255) NOT NULL,
    code        VARCHAR(50)  NOT NULL,
    city        VARCHAR(255),
    state       VARCHAR(255),
    description VARCHAR(500),
    active      BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_zone_name UNIQUE (name),
    CONSTRAINT uk_zone_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS zone_school_mappings (
    id        BIGSERIAL   NOT NULL,
    zone_id   BIGINT      NOT NULL,
    school_id BIGINT      NOT NULL,
    active    BOOLEAN     NOT NULL DEFAULT true,
    added_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    added_by  BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_zone_school UNIQUE (zone_id, school_id),
    CONSTRAINT fk_zsm_zone   FOREIGN KEY (zone_id)   REFERENCES zones   (id) ON DELETE CASCADE,
    CONSTRAINT fk_zsm_school FOREIGN KEY (school_id) REFERENCES schools  (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS zone_admin_assignments (
    id          BIGSERIAL   NOT NULL,
    zone_id     BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    active      BOOLEAN     NOT NULL DEFAULT true,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_by BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_zone_admin UNIQUE (zone_id, user_id),
    CONSTRAINT fk_zaa_zone FOREIGN KEY (zone_id) REFERENCES zones     (id) ON DELETE CASCADE,
    CONSTRAINT fk_zaa_user FOREIGN KEY (user_id) REFERENCES app_users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_zone_school_mappings_zone_id   ON zone_school_mappings (zone_id);
CREATE INDEX IF NOT EXISTS idx_zone_school_mappings_school_id ON zone_school_mappings (school_id);
CREATE INDEX IF NOT EXISTS idx_zone_admin_assignments_user_id ON zone_admin_assignments (user_id);
CREATE INDEX IF NOT EXISTS idx_zone_admin_assignments_zone_id ON zone_admin_assignments (zone_id);
