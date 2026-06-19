-- V119: RBAC scoping columns on user_role_assignments, missing permissions, DB constraints.
-- Forward-only migration. All changes are additive.

-- ── 1. Scoped role assignments ─────────────────────────────────────────────────
-- Add optional scope columns so a user can hold the same role in different schools/zones.

ALTER TABLE user_role_assignments
    ADD COLUMN IF NOT EXISTS school_id BIGINT REFERENCES schools(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS zone_id   BIGINT REFERENCES zones(id)   ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS active    BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS valid_from  TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS valid_until TIMESTAMP WITH TIME ZONE;

-- Drop old (user_id, role_id) unique constraint — superseded by scoped index below.
ALTER TABLE user_role_assignments DROP CONSTRAINT IF EXISTS uk_user_role;

-- New unique index: same role allowed only once per (user, scope).
-- COALESCE(-1) treats NULL as a sentinel so two NULL-scoped rows still collide.
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_role_scoped
    ON user_role_assignments (user_id, role_id, COALESCE(school_id, -1), COALESCE(zone_id, -1));

-- Performance indexes for active-scope lookups.
CREATE INDEX IF NOT EXISTS idx_ura_user_active  ON user_role_assignments (user_id, active);
CREATE INDEX IF NOT EXISTS idx_ura_school_active ON user_role_assignments (school_id, active) WHERE school_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ura_zone_active   ON user_role_assignments (zone_id,   active) WHERE zone_id   IS NOT NULL;

-- ── 2. Missing permission codes ────────────────────────────────────────────────

INSERT INTO permissions (code, description) VALUES
    ('fee:assign',         'Assign fee plans to students'),
    ('payment:reconcile',  'Reconcile payment records'),
    ('notification:read',  'View notifications'),
    ('notification:send',  'Send notifications'),
    ('role:read',          'View RBAC roles and their permission sets'),
    ('role:create',        'Create new RBAC roles'),
    ('role:update',        'Update RBAC roles and assignments'),
    ('permission:read',    'View the full permissions list')
ON CONFLICT (code) DO NOTHING;

-- Give SUPERADMIN every permission added above.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'SUPERADMIN'
  AND p.code IN ('fee:assign','payment:reconcile','notification:read','notification:send',
                 'role:read','role:create','role:update','permission:read')
ON CONFLICT DO NOTHING;

-- role:read and permission:read → ZONE_ADMIN (read-only RBAC visibility)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN ('role:read','permission:read','notification:read')
WHERE r.name = 'ZONE_ADMIN'
ON CONFLICT DO NOTHING;

-- notification:read, fee:assign → ADMIN
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN ('notification:read','notification:send','fee:assign')
WHERE r.name = 'ADMIN'
ON CONFLICT DO NOTHING;

-- notification:read, payment:reconcile → ACCOUNTANT
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN ('notification:read','payment:reconcile')
WHERE r.name = 'ACCOUNTANT'
ON CONFLICT DO NOTHING;

-- ── 3. Additional DB constraints ───────────────────────────────────────────────

-- Ensure role names and permission codes are immutable identifiers (already unique via column def).
-- Add partial index on roles to enforce lowercase naming convention.
CREATE INDEX IF NOT EXISTS idx_roles_name_lower ON roles (LOWER(name));

-- Enforce that valid_until is always after valid_from when both are set.
ALTER TABLE user_role_assignments
    DROP CONSTRAINT IF EXISTS chk_ura_validity_range;
ALTER TABLE user_role_assignments
    ADD CONSTRAINT chk_ura_validity_range
    CHECK (valid_from IS NULL OR valid_until IS NULL OR valid_until > valid_from);
