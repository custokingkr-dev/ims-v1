-- Notebook order tracker permissions (spec v4; review fixes 2026-10-20, findings 4 and 5).
--
-- The tracker checks its own codes rather than workflow:*: the OPERATIONS role holds workflow:read but never
-- workflow:act, so operators could not work a tracker step once deployed. Item-scoped operators (owner decision D2)
-- receive these codes without the rest of the OPERATIONS set; see IdentityAuthService.loginPermissions.
INSERT INTO identity.permissions (code, description, created_at)
VALUES
    ('notebook-tracker:read', 'Read notebook order trackers', now()),
    ('notebook-tracker:act', 'Work notebook order tracker steps', now())
ON CONFLICT (code) DO UPDATE SET description = EXCLUDED.description;

INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
JOIN identity.permissions p ON p.code IN ('notebook-tracker:read', 'notebook-tracker:act')
WHERE r.name IN ('SUPERADMIN', 'OPERATIONS')
ON CONFLICT (role_id, permission_id) DO NOTHING;
