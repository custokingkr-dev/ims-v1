-- V125: Add system:swagger permission and grant to SUPERADMIN only.
-- Swagger access is controlled via RBAC rather than relying solely on springdoc's prod-disable flag.

INSERT INTO permissions (code, description) VALUES
    ('system:swagger', 'Access Swagger UI and OpenAPI docs endpoint')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'system:swagger'
WHERE r.name = 'SUPERADMIN'
ON CONFLICT DO NOTHING;
