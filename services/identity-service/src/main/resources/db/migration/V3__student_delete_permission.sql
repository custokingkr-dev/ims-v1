INSERT INTO identity.permissions (code, description, created_at)
VALUES ('student:delete', 'Delete students', now())
ON CONFLICT (code) DO UPDATE SET description = EXCLUDED.description;

WITH target_roles AS (
    SELECT name
    FROM identity.roles
    WHERE name IN ('SUPERADMIN', 'ADMIN', 'SCHOOL_ADMIN')
)
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM target_roles tr
JOIN identity.roles r ON r.name = tr.name
JOIN identity.permissions p ON p.code = 'student:delete'
ON CONFLICT (role_id, permission_id) DO NOTHING;
