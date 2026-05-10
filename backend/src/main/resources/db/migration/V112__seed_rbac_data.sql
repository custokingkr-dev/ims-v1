-- Seed default roles
INSERT INTO roles (name, description) VALUES
    ('SUPERADMIN', 'Platform-level administrator — full access'),
    ('ZONE_ADMIN',  'Zone-level administrator across multiple schools'),
    ('ADMIN',       'School-level full administrator'),
    ('OPERATIONS',  'School daily operations — students, attendance, orders, firefighting'),
    ('ACCOUNTANT',  'School finance and fee management'),
    ('TEACHER',     'Teaching staff — attendance and student access'),
    ('VIEWER',      'Read-only access within a school')
ON CONFLICT (name) DO NOTHING;

-- Seed permissions
INSERT INTO permissions (code, description) VALUES
    ('student:read',           'View student records'),
    ('student:create',         'Create student records'),
    ('student:update',         'Update student records'),
    ('student:delete',         'Delete student records'),
    ('student:import',         'Bulk import students'),
    ('fee_structure:read',     'View fee structure/bands'),
    ('fee_structure:manage',   'Create and update fee structure'),
    ('fee:collect',            'Record fee payments'),
    ('fee:read',               'View fee collections and reports'),
    ('attendance:read',        'View attendance records'),
    ('attendance:manage',      'Record and update attendance'),
    ('order:read',             'View supply orders'),
    ('order:create',           'Create supply orders'),
    ('order:update',           'Update supply orders'),
    ('order:approve',          'Approve supply orders'),
    ('order:fulfill',          'Mark supply orders as fulfilled'),
    ('firefighting:read',      'View firefighting requests'),
    ('firefighting:create',    'Create firefighting requests'),
    ('firefighting:update',    'Update firefighting requests'),
    ('firefighting:approve',   'Approve firefighting requests'),
    ('firefighting:fulfill',   'Fulfill firefighting requests'),
    ('payment:create',         'Record payments'),
    ('payment:read',           'View payments'),
    ('invoice:create',         'Create invoices'),
    ('invoice:read',           'View invoices'),
    ('staff:read',             'View staff records'),
    ('staff:manage',           'Manage staff records'),
    ('audit:read',             'View audit log'),
    ('user:manage',            'Create and manage users'),
    ('school:read',            'View school records'),
    ('school:create',          'Create schools'),
    ('school:update',          'Update school records'),
    ('school:admin_manage',    'Manage school admins and ops users'),
    ('zone:read',              'View zone information'),
    ('zone:manage',            'Create and update zones'),
    ('zone:assign_school',     'Assign schools to zones'),
    ('plan:read',              'View annual procurement plan'),
    ('plan:manage',            'Manage annual procurement plan'),
    ('timetable:read',         'View timetable'),
    ('timetable:manage',       'Manage timetable'),
    ('workflow:read',          'View workflow instances and history'),
    ('workflow:act',           'Submit, approve, or reject workflow steps')
ON CONFLICT (code) DO NOTHING;

-- SUPERADMIN: all permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p WHERE r.name = 'SUPERADMIN'
ON CONFLICT DO NOTHING;

-- ZONE_ADMIN permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'student:read', 'fee:read', 'fee_structure:read',
    'attendance:read', 'order:read', 'order:approve',
    'firefighting:read', 'firefighting:approve',
    'payment:read', 'invoice:read', 'staff:read', 'audit:read',
    'school:read', 'zone:read', 'zone:manage', 'zone:assign_school',
    'plan:read', 'timetable:read', 'workflow:read', 'workflow:act'
) WHERE r.name = 'ZONE_ADMIN'
ON CONFLICT DO NOTHING;

-- ADMIN permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'student:read', 'student:create', 'student:update', 'student:import',
    'fee:read', 'fee:collect', 'fee_structure:read', 'fee_structure:manage',
    'attendance:read', 'attendance:manage',
    'order:read', 'order:create', 'order:update',
    'firefighting:read', 'firefighting:create', 'firefighting:update', 'firefighting:approve',
    'payment:read', 'payment:create', 'invoice:read',
    'staff:read', 'staff:manage', 'audit:read',
    'plan:read', 'plan:manage', 'timetable:read', 'timetable:manage',
    'workflow:read', 'workflow:act'
) WHERE r.name = 'ADMIN'
ON CONFLICT DO NOTHING;

-- OPERATIONS permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'student:read', 'student:create', 'student:update', 'student:import',
    'attendance:read', 'attendance:manage',
    'order:read', 'order:create',
    'firefighting:read', 'firefighting:create',
    'staff:read', 'plan:read', 'timetable:read',
    'workflow:read'
) WHERE r.name = 'OPERATIONS'
ON CONFLICT DO NOTHING;

-- ACCOUNTANT permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'student:read',
    'fee:read', 'fee:collect', 'fee_structure:read', 'fee_structure:manage',
    'order:read', 'firefighting:read',
    'payment:read', 'payment:create', 'invoice:read', 'invoice:create',
    'audit:read', 'workflow:read', 'workflow:act'
) WHERE r.name = 'ACCOUNTANT'
ON CONFLICT DO NOTHING;

-- TEACHER permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'student:read', 'student:update',
    'attendance:read', 'attendance:manage',
    'timetable:read', 'timetable:manage',
    'staff:read'
) WHERE r.name = 'TEACHER'
ON CONFLICT DO NOTHING;

-- VIEWER permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.code IN (
    'student:read', 'fee:read', 'fee_structure:read',
    'attendance:read', 'order:read', 'firefighting:read',
    'payment:read', 'invoice:read', 'staff:read',
    'plan:read', 'timetable:read', 'workflow:read'
) WHERE r.name = 'VIEWER'
ON CONFLICT DO NOTHING;

-- Backfill user_role_assignments from existing app_users.role
-- Maps SUPERADMIN -> SUPERADMIN, ADMIN -> ADMIN, OPERATIONS -> OPERATIONS, etc.
INSERT INTO user_role_assignments (user_id, role_id)
SELECT u.id, r.id
FROM app_users u
JOIN roles r ON r.name = UPPER(u.role)
WHERE u.role IS NOT NULL
ON CONFLICT (user_id, role_id) DO NOTHING;
