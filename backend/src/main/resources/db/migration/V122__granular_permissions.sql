-- V122: Granular permissions for Phase 2 enterprise RBAC.
-- Replaces broad permissions (user:manage, school:admin_manage) with
-- fine-grained codes so role-to-permission mapping can be more precisely tuned.
--
-- Existing broad permissions are NOT removed — they stay active for backward compat
-- until all controllers are updated to use the granular codes.
-- This migration is forward-only and additive.

-- ── 1. Platform-level permissions ─────────────────────────────────────────────

INSERT INTO permissions (code, description) VALUES
    ('platform:admin',       'Access the platform-level administration panel'),
    ('platform:dashboard',   'View the cross-school platform dashboard')
ON CONFLICT (code) DO NOTHING;

-- ── 2. School lifecycle permissions ───────────────────────────────────────────

INSERT INTO permissions (code, description) VALUES
    ('school:activate',         'Activate a suspended or inactive school'),
    ('school:suspend',          'Suspend an active school'),
    ('school:settings:update',  'Update school configuration and settings')
ON CONFLICT (code) DO NOTHING;

-- ── 3. Granular user management permissions ───────────────────────────────────
-- Replaces the broad user:manage with individual codes.

INSERT INTO permissions (code, description) VALUES
    ('user:read',           'View user profiles and lists'),
    ('user:create',         'Create new user accounts'),
    ('user:update',         'Update user profile information'),
    ('user:disable',        'Disable / deactivate user accounts'),
    ('user:reset_password', 'Trigger password resets for other users')
ON CONFLICT (code) DO NOTHING;

-- ── 4. Granular RBAC management permissions ───────────────────────────────────

INSERT INTO permissions (code, description) VALUES
    ('role:disable',        'Deactivate an existing RBAC role'),
    ('role:assign',         'Assign a role to a user (any scope)'),
    ('role:revoke',         'Revoke a role assignment from a user'),
    ('permission:assign',   'Add a permission to a role'),
    ('permission:revoke',   'Remove a permission from a role')
ON CONFLICT (code) DO NOTHING;

-- ── 5. Financial permissions ──────────────────────────────────────────────────

INSERT INTO permissions (code, description) VALUES
    ('fee:reverse',         'Reverse / refund a fee payment'),
    ('invoice:cancel',      'Cancel an issued invoice'),
    ('payment:reconcile',   'Reconcile payment records against bank statements')
ON CONFLICT (code) DO NOTHING;

-- ── 6. Order / firefighting permissions ───────────────────────────────────────

INSERT INTO permissions (code, description) VALUES
    ('order:reject',        'Reject a supply order')
ON CONFLICT (code) DO NOTHING;

-- ── 7. Audit / report permissions ─────────────────────────────────────────────

INSERT INTO permissions (code, description) VALUES
    ('audit:export',        'Export audit log entries to CSV/Excel'),
    ('report:export',       'Export reports to CSV/Excel/PDF')
ON CONFLICT (code) DO NOTHING;

-- ── 8. Assign granular permissions to SUPERADMIN ──────────────────────────────

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.name = 'SUPERADMIN'
  AND p.code IN (
    'platform:admin', 'platform:dashboard',
    'school:activate', 'school:suspend', 'school:settings:update',
    'user:read', 'user:create', 'user:update', 'user:disable', 'user:reset_password',
    'role:disable', 'role:assign', 'role:revoke', 'permission:assign', 'permission:revoke',
    'fee:reverse', 'invoice:cancel', 'payment:reconcile',
    'order:reject',
    'audit:export', 'report:export'
  )
ON CONFLICT DO NOTHING;

-- ── 9. ADMIN / SCHOOL_ADMIN: school-scoped subset ─────────────────────────────

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'school:settings:update',
    'user:read', 'user:create', 'user:update', 'user:disable', 'user:reset_password',
    'role:assign', 'role:revoke', 'role:read',
    'fee:reverse', 'invoice:cancel',
    'order:reject',
    'audit:export', 'report:export'
)
WHERE r.name IN ('ADMIN', 'SCHOOL_ADMIN')
ON CONFLICT DO NOTHING;

-- ── 10. ACCOUNTANT: financial subset ──────────────────────────────────────────

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'payment:reconcile', 'report:export'
)
WHERE r.name = 'ACCOUNTANT'
ON CONFLICT DO NOTHING;

-- ── 11. ZONE_ADMIN: read + limited assignment ──────────────────────────────────

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'platform:dashboard',
    'user:read',
    'role:assign', 'role:revoke'
)
WHERE r.name = 'ZONE_ADMIN'
ON CONFLICT DO NOTHING;

-- ── 12. VIEWER: export permissions ────────────────────────────────────────────

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('report:export')
WHERE r.name = 'VIEWER'
ON CONFLICT DO NOTHING;
