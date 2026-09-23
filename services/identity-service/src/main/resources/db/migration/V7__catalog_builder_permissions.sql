INSERT INTO identity.permissions (code, description, created_at) VALUES
('catalog:manage', 'Configure catalog products, options, dimensions and order rules', now()),
('catalog:quote', 'Quote submitted catalog orders', now())
ON CONFLICT (code) DO UPDATE SET description = EXCLUDED.description;

INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT role.id, permission.id FROM identity.roles role
JOIN identity.permissions permission ON permission.code IN ('catalog:manage', 'catalog:quote')
WHERE role.name = 'SUPERADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;
