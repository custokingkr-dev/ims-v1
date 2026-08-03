INSERT INTO identity.permissions (code, description, created_at)
VALUES ('student:photo-import', 'Import student portraits from an approved Google Drive folder', now())
ON CONFLICT (code) DO UPDATE SET description = EXCLUDED.description;

INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
JOIN identity.permissions p ON p.code = 'student:photo-import'
WHERE r.name IN ('SUPERADMIN', 'OPERATIONS')
ON CONFLICT (role_id, permission_id) DO NOTHING;
