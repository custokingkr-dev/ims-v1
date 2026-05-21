-- V118: Add permissions that were missing from V112 seed data.
-- Only adds new rows; never edits existing ones.

INSERT INTO permissions (code, description) VALUES
    ('fee:reverse',     'Reverse a fee payment'),
    ('invoice:cancel',  'Cancel an invoice'),
    ('customer:read',   'View customer/parent records'),
    ('customer:create', 'Create or update customer/parent records'),
    ('order:reject',    'Reject supply orders'),
    ('report:read',     'View reports'),
    ('school:suspend',  'Suspend or reactivate a school')
ON CONFLICT (code) DO NOTHING;

-- Assign new permissions to roles (SUPERADMIN gets all via the wildcard insert below)

-- fee:reverse → ADMIN
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'fee:reverse'
WHERE r.name = 'ADMIN'
ON CONFLICT DO NOTHING;

-- invoice:cancel → ADMIN, ACCOUNTANT
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'invoice:cancel'
WHERE r.name IN ('ADMIN', 'ACCOUNTANT')
ON CONFLICT DO NOTHING;

-- customer:read → ADMIN, OPERATIONS, ACCOUNTANT
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'customer:read'
WHERE r.name IN ('ADMIN', 'OPERATIONS', 'ACCOUNTANT')
ON CONFLICT DO NOTHING;

-- customer:create → ADMIN, ACCOUNTANT
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'customer:create'
WHERE r.name IN ('ADMIN', 'ACCOUNTANT')
ON CONFLICT DO NOTHING;

-- order:reject → ZONE_ADMIN, ADMIN (SUPERADMIN already has all)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'order:reject'
WHERE r.name IN ('ZONE_ADMIN', 'ADMIN')
ON CONFLICT DO NOTHING;

-- report:read → ZONE_ADMIN, ADMIN, ACCOUNTANT, TEACHER, VIEWER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'report:read'
WHERE r.name IN ('ZONE_ADMIN', 'ADMIN', 'ACCOUNTANT', 'TEACHER', 'VIEWER')
ON CONFLICT DO NOTHING;

-- school:suspend → SUPERADMIN only (handled by wildcard insert below)

-- Give SUPERADMIN every permission that has been added since V112
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p WHERE r.name = 'SUPERADMIN'
ON CONFLICT DO NOTHING;
