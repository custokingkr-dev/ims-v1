INSERT INTO identity.permissions (code, description, created_at)
VALUES ('student:export', 'Export assigned-school student details and photos', now())
ON CONFLICT (code) DO UPDATE SET description = EXCLUDED.description;

INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM identity.roles role
JOIN identity.permissions permission ON permission.code = 'student:export'
WHERE role.name IN ('SUPERADMIN', 'OPERATIONS')
ON CONFLICT (role_id, permission_id) DO NOTHING;
