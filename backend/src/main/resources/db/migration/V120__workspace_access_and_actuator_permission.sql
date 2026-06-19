-- V120: Add workspace:access and system:actuator permissions.
-- Add revoked_by / revoked_at to user_role_assignments for full assignment lifecycle.
-- Forward-only migration — additive only.

-- ── 1. New permissions ────────────────────────────────────────────────────────

INSERT INTO permissions (code, description) VALUES
    ('workspace:access', 'Enter the main school-operations workspace'),
    ('system:actuator',  'Access sensitive Spring Boot actuator endpoints')
ON CONFLICT (code) DO NOTHING;

-- ── 2. Assign workspace:access to all non-anonymous roles ─────────────────────

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'workspace:access'
WHERE r.name IN ('SUPERADMIN','ZONE_ADMIN','ADMIN','OPERATIONS','ACCOUNTANT','TEACHER','VIEWER')
ON CONFLICT DO NOTHING;

-- ── 3. system:actuator — SUPERADMIN only ──────────────────────────────────────

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'system:actuator'
WHERE r.name = 'SUPERADMIN'
ON CONFLICT DO NOTHING;

-- ── 4. Add revocation tracking columns to user_role_assignments ───────────────

ALTER TABLE user_role_assignments
    ADD COLUMN IF NOT EXISTS revoked_by BIGINT,
    ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMP WITH TIME ZONE;
