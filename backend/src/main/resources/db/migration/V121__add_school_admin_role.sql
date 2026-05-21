-- V121: Add SCHOOL_ADMIN as a named alias for the ADMIN role.
-- Phase 2 prep: ADMIN is kept as the operative role for school-level access.
-- SCHOOL_ADMIN is added to the roles table pointing at the same permission set,
-- allowing a gradual migration of UI labels and assignments without a cutover.
--
-- Phase 2 completion note: once all user_role_assignments.role_id values are
-- migrated from ADMIN to SCHOOL_ADMIN and UI labels are updated, the ADMIN row
-- can be removed via a future migration.
--
-- This migration is forward-only and additive.

-- ── 1. Create SCHOOL_ADMIN role if it does not already exist ──────────────────

INSERT INTO roles (name, description)
VALUES ('SCHOOL_ADMIN', 'School-level administrator. Replaces ADMIN in Phase 2.')
ON CONFLICT (name) DO NOTHING;

-- ── 2. Copy all permissions from ADMIN → SCHOOL_ADMIN ─────────────────────────

INSERT INTO role_permissions (role_id, permission_id)
SELECT sa.id, rp.permission_id
FROM roles sa
JOIN roles admin ON admin.name = 'ADMIN'
JOIN role_permissions rp ON rp.role_id = admin.id
WHERE sa.name = 'SCHOOL_ADMIN'
ON CONFLICT DO NOTHING;

-- ── 3. Add workspace:access to SCHOOL_ADMIN (should already be there via step 2,
--       but re-asserted explicitly for clarity)

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'workspace:access'
WHERE r.name = 'SCHOOL_ADMIN'
ON CONFLICT DO NOTHING;

-- ── 4. Future migration note ───────────────────────────────────────────────────
-- Run this only after all UI references to 'ADMIN' are updated to 'SCHOOL_ADMIN'
-- and all user_role_assignments are migrated:
--
--   UPDATE user_role_assignments SET role_id = (SELECT id FROM roles WHERE name = 'SCHOOL_ADMIN')
--   WHERE role_id = (SELECT id FROM roles WHERE name = 'ADMIN');
--   DELETE FROM roles WHERE name = 'ADMIN';
