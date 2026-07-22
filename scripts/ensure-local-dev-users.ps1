param(
    [string]$PostgresContainer = "custoking-postgres",
    [string]$Database = "postgres",
    [string]$DbUser = "postgres",
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"

$password = "password"
$passwordHash = '$2a$10$J7RjqxrkPBk31.tolxpMkO0LHevKKGCNi6AsSPAsGeHtnyvHfmXlG'

function Invoke-Psql {
    param([string]$Sql)

    $tmp = [System.IO.Path]::GetTempFileName()
    try {
        Set-Content -LiteralPath $tmp -Value $Sql -NoNewline -Encoding UTF8
        docker cp $tmp "${PostgresContainer}:/tmp/ims-local-dev-users.sql" | Out-Null
        docker exec $PostgresContainer psql -v ON_ERROR_STOP=1 -U $DbUser -d $Database -f /tmp/ims-local-dev-users.sql
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to seed local development users (exit $LASTEXITCODE)."
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
}

function Wait-ForSchema {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $ready = docker exec $PostgresContainer psql -U $DbUser -d $Database -t -A -c "SELECT to_regclass('identity.app_users') IS NOT NULL AND to_regclass('tenant_school.schools') IS NOT NULL;" 2>$null
        if (($ready | Select-Object -Last 1) -eq "t") {
            return
        }
        Start-Sleep -Seconds 3
    }
    throw "Timed out waiting for identity and tenant_school schemas in $PostgresContainer."
}

Wait-ForSchema

$sql = @"
SET search_path TO identity, tenant_school, public;

INSERT INTO tenant_school.academic_years (id, label, active)
VALUES ('local_2026', '2026-27', true)
ON CONFLICT (id) DO UPDATE SET label = EXCLUDED.label, active = EXCLUDED.active;

INSERT INTO tenant_school.schools
    (id, name, short_code, city, state, contact_email, contact_phone, active,
     configured_class_count, configured_section_count, created_at)
VALUES
    (1, 'Local Demo School', 'LOCAL', 'Bengaluru', 'KA',
     'local-demo-school@custoking.local', '9999999999', true, 1, 1, now())
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    short_code = EXCLUDED.short_code,
    city = EXCLUDED.city,
    state = EXCLUDED.state,
    contact_email = EXCLUDED.contact_email,
    contact_phone = EXCLUDED.contact_phone,
    active = EXCLUDED.active,
    configured_class_count = EXCLUDED.configured_class_count,
    configured_section_count = EXCLUDED.configured_section_count;

INSERT INTO tenant_school.schools
    (id, name, short_code, city, state, contact_email, contact_phone, active,
     configured_class_count, configured_section_count, created_at)
VALUES
    (2, 'Local Demo School Two', 'LOCAL2', 'Bengaluru', 'KA',
     'local-demo-school-two@custoking.local', '9999999998', true, 1, 1, now())
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    short_code = EXCLUDED.short_code,
    city = EXCLUDED.city,
    state = EXCLUDED.state,
    contact_email = EXCLUDED.contact_email,
    contact_phone = EXCLUDED.contact_phone,
    active = EXCLUDED.active,
    configured_class_count = EXCLUDED.configured_class_count,
    configured_section_count = EXCLUDED.configured_section_count;

INSERT INTO tenant_school.school_classes (id, name, sort_order)
VALUES ('1', '1', 4)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, sort_order = EXCLUDED.sort_order;

INSERT INTO tenant_school.school_sections (id, name, teacher_name, active, school_class_id, school_id)
VALUES ('1A', 'A', 'Local Teacher', true, '1', 1)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    teacher_name = EXCLUDED.teacher_name,
    active = EXCLUDED.active,
    school_class_id = EXCLUDED.school_class_id,
    school_id = EXCLUDED.school_id;

INSERT INTO tenant_school.school_sections (id, name, teacher_name, active, school_class_id, school_id)
VALUES ('2A', 'A', 'Local Teacher Two', true, '1', 2)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    teacher_name = EXCLUDED.teacher_name,
    active = EXCLUDED.active,
    school_class_id = EXCLUDED.school_class_id,
    school_id = EXCLUDED.school_id;

INSERT INTO tenant_school.zones (id, name, code, city, state, description, active, created_at, updated_at)
VALUES (1, 'Local Zone', 'LOCAL-ZONE', 'Bengaluru', 'KA', 'Local development zone', true, now(), now())
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    code = EXCLUDED.code,
    city = EXCLUDED.city,
    state = EXCLUDED.state,
    description = EXCLUDED.description,
    active = EXCLUDED.active,
    updated_at = now();

INSERT INTO tenant_school.zone_school_mappings (zone_id, school_id, active, added_at)
VALUES (1, 1, true, now())
ON CONFLICT (zone_id, school_id) DO UPDATE SET active = true;

INSERT INTO identity.roles (name, description, created_at)
VALUES
    ('SUPERADMIN', 'Platform-level administrator - full access', now()),
    ('ZONE_ADMIN', 'Zone-level administrator across mapped schools', now()),
    ('ADMIN', 'School-level full administrator', now()),
    ('SCHOOL_ADMIN', 'School-level administrator', now()),
    ('OPERATIONS', 'School daily operations - students, attendance, orders, firefighting', now()),
    ('ACCOUNTANT', 'School finance and fee management', now()),
    ('TEACHER', 'Teaching staff - attendance and student access', now()),
    ('VIEWER', 'Read-only access within a school', now())
ON CONFLICT (name) DO UPDATE SET description = EXCLUDED.description;

INSERT INTO identity.permissions (code, description, created_at)
VALUES
    ('platform:admin', 'Access platform administration workspace', now()),
    ('school:read', 'Read school accounts', now()),
    ('school:create', 'Create school accounts', now()),
    ('school:update', 'Update school accounts', now()),
    ('school:suspend', 'Suspend school accounts', now()),
    ('zone:read', 'Read zones', now()),
    ('zone:manage', 'Manage zones', now()),
    ('zone:assign_school', 'Assign schools to zones', now()),
    ('attendance:manage', 'Manage attendance', now()),
    ('attendance:read', 'Read attendance', now()),
    ('audit:export', 'Export audit logs', now()),
    ('audit:read', 'Read audit logs', now()),
    ('customer:create', 'Create customers', now()),
    ('customer:read', 'Read customers', now()),
    ('fee:assign', 'Assign fees', now()),
    ('fee:collect', 'Collect fees', now()),
    ('fee:read', 'Read fees', now()),
    ('fee:reverse', 'Reverse fee payments', now()),
    ('fee_structure:manage', 'Manage fee structures', now()),
    ('fee_structure:read', 'Read fee structures', now()),
    ('firefighting:approve', 'Approve firefighting requests', now()),
    ('firefighting:create', 'Create firefighting requests', now()),
    ('firefighting:fulfill', 'Fulfill firefighting requests', now()),
    ('firefighting:read', 'Read firefighting requests', now()),
    ('firefighting:update', 'Update firefighting requests', now()),
    ('invoice:cancel', 'Cancel invoices', now()),
    ('invoice:create', 'Create invoices', now()),
    ('invoice:read', 'Read invoices', now()),
    ('notification:read', 'Read notifications', now()),
    ('notification:send', 'Send notifications', now()),
    ('order:approve', 'Approve orders', now()),
    ('order:create', 'Create orders', now()),
    ('order:fulfill', 'Fulfill orders', now()),
    ('order:read', 'Read orders', now()),
    ('order:reject', 'Reject orders', now()),
    ('order:update', 'Update orders', now()),
    ('payment:create', 'Create payments', now()),
    ('payment:read', 'Read payments', now()),
    ('payment:reconcile', 'Reconcile payments', now()),
    ('permission:read', 'Read permissions', now()),
    ('plan:manage', 'Manage plans', now()),
    ('plan:read', 'Read plans', now()),
    ('report:export', 'Export reports', now()),
    ('report:read', 'Read reports', now()),
    ('role:assign', 'Assign roles', now()),
    ('role:create', 'Create roles', now()),
    ('role:disable', 'Disable roles', now()),
    ('role:read', 'Read roles', now()),
    ('role:revoke', 'Revoke roles', now()),
    ('role:update', 'Update roles', now()),
    ('school:settings:update', 'Update school settings', now()),
    ('staff:manage', 'Manage staff', now()),
    ('staff:read', 'Read staff', now()),
    ('student:create', 'Create students', now()),
    ('student:delete', 'Delete students', now()),
    ('student:import', 'Import students', now()),
    ('student:read', 'Read students', now()),
    ('student:update', 'Update students', now()),
    ('timetable:manage', 'Manage timetable', now()),
    ('timetable:read', 'Read timetable', now()),
    ('user:create', 'Create users', now()),
    ('user:disable', 'Disable users', now()),
    ('user:manage', 'Manage users', now()),
    ('user:read', 'Read users', now()),
    ('user:reset_password', 'Reset user passwords', now()),
    ('user:update', 'Update users', now()),
    ('workflow:act', 'Act on workflows', now()),
    ('workflow:read', 'Read workflows', now()),
    ('workspace:access', 'Access workspace', now())
ON CONFLICT (code) DO UPDATE SET description = EXCLUDED.description;

WITH role_permissions AS (
    SELECT 'SUPERADMIN' AS role_name, code FROM identity.permissions
    UNION ALL
    SELECT 'ZONE_ADMIN', code FROM identity.permissions
      WHERE code LIKE '%:read' OR code IN (
        'workspace:access','zone:manage','zone:assign_school','report:export',
        'role:read','user:read','school:read','school:settings:update','staff:read','notification:read'
      )
    UNION ALL
    SELECT 'ADMIN', code FROM identity.permissions
      WHERE code NOT IN ('audit:export','platform:admin','zone:manage','zone:assign_school','school:create','school:suspend')
    UNION ALL
    SELECT 'SCHOOL_ADMIN', code FROM identity.permissions
      WHERE code NOT IN ('audit:export','platform:admin','zone:manage','zone:assign_school','school:create','school:suspend')
    UNION ALL
    SELECT 'OPERATIONS', code FROM identity.permissions
      WHERE code IN (
        'workspace:access','student:read','student:create','student:update','student:import',
        'attendance:read','attendance:manage','staff:read','timetable:read','timetable:manage',
        'order:read','order:create','order:update','firefighting:read','firefighting:create','firefighting:update',
        'workflow:read','workflow:act','notification:read','notification:send','report:read'
      )
    UNION ALL
    SELECT 'ACCOUNTANT', code FROM identity.permissions
      WHERE code IN (
        'workspace:access','fee:read','fee:assign','fee:collect','fee:reverse',
        'fee_structure:read','fee_structure:manage','payment:read','payment:create',
        'payment:reconcile','invoice:read','invoice:create','invoice:cancel',
        'report:read','report:export','notification:read'
      )
    UNION ALL
    SELECT 'TEACHER', code FROM identity.permissions
      WHERE code IN (
        'workspace:access','student:read','attendance:read','attendance:manage',
        'timetable:read','notification:read','report:read'
      )
    UNION ALL
    SELECT 'VIEWER', code FROM identity.permissions
      WHERE code LIKE '%:read' OR code IN ('workspace:access','plan:read','payment:read','report:read')
)
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM role_permissions rp
JOIN identity.roles r ON r.name = rp.role_name
JOIN identity.permissions p ON p.code = rp.code
ON CONFLICT (role_id, permission_id) DO NOTHING;

WITH dev_users(full_name, email, role_name, school_id, school_name, zone_id, zone_name) AS (
    VALUES
        ('Local Superadmin', 'local-superadmin@custoking.local', 'SUPERADMIN', NULL::bigint, NULL::varchar, NULL::bigint, NULL::varchar),
        ('Local Zone Admin', 'local-zone-admin@custoking.local', 'ZONE_ADMIN', NULL::bigint, NULL::varchar, 1::bigint, 'Local Zone'::varchar),
        ('Local Admin', 'local-admin@custoking.local', 'ADMIN', 1::bigint, 'Local Demo School'::varchar, NULL::bigint, NULL::varchar),
        ('Local Admin Two', 'local-admin2@custoking.local', 'ADMIN', 2::bigint, 'Local Demo School Two'::varchar, NULL::bigint, NULL::varchar),
        ('Local School Admin', 'local-school-admin@custoking.local', 'SCHOOL_ADMIN', 1::bigint, 'Local Demo School'::varchar, NULL::bigint, NULL::varchar),
        ('Local Operations', 'local-operations@custoking.local', 'OPERATIONS', 1::bigint, 'Local Demo School'::varchar, NULL::bigint, NULL::varchar),
        ('Local Accountant', 'local-accountant@custoking.local', 'ACCOUNTANT', 1::bigint, 'Local Demo School'::varchar, NULL::bigint, NULL::varchar),
        ('Local Teacher', 'local-teacher@custoking.local', 'TEACHER', 1::bigint, 'Local Demo School'::varchar, NULL::bigint, NULL::varchar),
        ('Local Viewer', 'local-viewer@custoking.local', 'VIEWER', 1::bigint, 'Local Demo School'::varchar, NULL::bigint, NULL::varchar)
),
upserted AS (
    INSERT INTO identity.app_users
        (full_name, email, password_hash, role, branch_id, branch_name, created_at, deleted_at, deleted_by, zone_id, zone_name)
    SELECT full_name, email, '$passwordHash', role_name, school_id, school_name, now(), NULL, NULL, zone_id, zone_name
    FROM dev_users
    ON CONFLICT (email) DO UPDATE SET
        full_name = EXCLUDED.full_name,
        password_hash = EXCLUDED.password_hash,
        role = EXCLUDED.role,
        branch_id = EXCLUDED.branch_id,
        branch_name = EXCLUDED.branch_name,
        zone_id = EXCLUDED.zone_id,
        zone_name = EXCLUDED.zone_name,
        deleted_at = NULL,
        deleted_by = NULL
    RETURNING id, email, role, branch_id, zone_id
)
INSERT INTO identity.user_role_assignments (user_id, role_id, school_id, zone_id, assigned_by, assigned_at, active, valid_from)
SELECT u.id, r.id, u.branch_id, u.zone_id, 1, now(), true, now()
FROM upserted u
JOIN identity.roles r ON r.name = u.role
WHERE NOT EXISTS (
    SELECT 1
    FROM identity.user_role_assignments existing
    WHERE existing.user_id = u.id
      AND existing.role_id = r.id
      AND COALESCE(existing.school_id, -1) = COALESCE(u.branch_id, -1)
      AND COALESCE(existing.zone_id, -1) = COALESCE(u.zone_id, -1)
      AND existing.active = true
);

INSERT INTO tenant_school.zone_admin_assignments (zone_id, user_id, active, assigned_at, assigned_by)
SELECT 1, u.id, true, now(), 1
FROM identity.app_users u
WHERE u.email = 'local-zone-admin@custoking.local'
ON CONFLICT (zone_id, user_id) DO UPDATE SET active = true, assigned_at = now();

-- BOLA probe targets: one known student per school (mandatory for cross-tenant gate).
INSERT INTO student.students (id, admission_no, full_name, school_id, class_id, section_id, academic_year_id)
VALUES
    (9000001, 'BOLA-A-001', 'BOLA Student A', 1, '1', '1A', 'local_2026'),
    (9000002, 'BOLA-B-001', 'BOLA Student B', 2, '1', '2A', 'local_2026')
ON CONFLICT (id) DO UPDATE SET
    school_id    = EXCLUDED.school_id,
    section_id   = EXCLUDED.section_id,
    admission_no = EXCLUDED.admission_no,
    full_name    = EXCLUDED.full_name;
SELECT setval('student.seq_students', COALESCE((SELECT max(id) FROM student.students), 0) + 1, false);

-- BOLA probe targets: one known catalog order per school.
INSERT INTO catalog.catalog_orders (id, category, subtotal, gst, total_amount, school_id, created_at)
VALUES
    ('ord-bola-a', 'NOTEBOOK', 0, 0, 0, 1, now()),
    ('ord-bola-b', 'NOTEBOOK', 0, 0, 0, 2, now())
ON CONFLICT (id) DO UPDATE SET school_id = EXCLUDED.school_id;

-- BOLA probe targets: one known firefighting request per school.
DO `$`$
BEGIN
    IF to_regclass('firefighting.firefighting_requests') IS NOT NULL THEN
        INSERT INTO firefighting.firefighting_requests (code, estimated_budget, school_id, created_at)
        VALUES
            ('ff-bola-a', 0, 1, now()),
            ('ff-bola-b', 0, 2, now())
        ON CONFLICT (code) DO UPDATE SET school_id = EXCLUDED.school_id;
    END IF;
END `$`$;

-- BOLA probe targets: one known PENDING workflow instance per school. school_id is NOT NULL.
-- workflow_instances.id is BIGSERIAL, so the marker (wf-bola-a/wf-bola-b) is carried in entity_id,
-- which surfaces in the /api/v1/workflows/pending response body for the marker-backed gate.
DO `$`$
BEGIN
    IF to_regclass('workflow.workflow_instances') IS NOT NULL THEN
        INSERT INTO workflow.workflow_instances (id, definition_id, entity_type, entity_id, school_id, current_step, status, initiated_at, version)
        VALUES
            (9100001, 'SUPPLY_ORDER_DEFAULT', 'SUPPLY_ORDER', 'wf-bola-a', 1, 0, 'PENDING', now(), 0),
            (9100002, 'SUPPLY_ORDER_DEFAULT', 'SUPPLY_ORDER', 'wf-bola-b', 2, 0, 'PENDING', now(), 0)
        ON CONFLICT (id) DO UPDATE SET school_id = EXCLUDED.school_id, status = 'PENDING', entity_id = EXCLUDED.entity_id;
        PERFORM setval(pg_get_serial_sequence('workflow.workflow_instances', 'id'), COALESCE((SELECT max(id) FROM workflow.workflow_instances), 0) + 1, false);
    END IF;
END `$`$;

SELECT setval('seq_app_users', COALESCE((SELECT max(id) FROM identity.app_users), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('identity.roles', 'id'), COALESCE((SELECT max(id) FROM identity.roles), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('identity.permissions', 'id'), COALESCE((SELECT max(id) FROM identity.permissions), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('identity.user_role_assignments', 'id'), COALESCE((SELECT max(id) FROM identity.user_role_assignments), 0) + 1, false);
SELECT setval('tenant_school.seq_schools', COALESCE((SELECT max(id) FROM tenant_school.schools), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('tenant_school.zones', 'id'), COALESCE((SELECT max(id) FROM tenant_school.zones), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('tenant_school.zone_school_mappings', 'id'), COALESCE((SELECT max(id) FROM tenant_school.zone_school_mappings), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('tenant_school.zone_admin_assignments', 'id'), COALESCE((SELECT max(id) FROM tenant_school.zone_admin_assignments), 0) + 1, false);
"@

Invoke-Psql $sql | Out-Null

Write-Host ""
Write-Host "Local development users are ready." -ForegroundColor Green
Write-Host "URL: http://localhost"
Write-Host ("{0,-16} {1,-42} {2}" -f "Role", "Email", "Password")
Write-Host ("{0,-16} {1,-42} {2}" -f "----", "-----", "--------")
@(
    @("SUPERADMIN", "local-superadmin@custoking.local"),
    @("ZONE_ADMIN", "local-zone-admin@custoking.local"),
    @("ADMIN", "local-admin@custoking.local"),
    @("ADMIN", "local-admin2@custoking.local"),
    @("SCHOOL_ADMIN", "local-school-admin@custoking.local"),
    @("OPERATIONS", "local-operations@custoking.local"),
    @("ACCOUNTANT", "local-accountant@custoking.local"),
    @("TEACHER", "local-teacher@custoking.local"),
    @("VIEWER", "local-viewer@custoking.local")
) | ForEach-Object {
    Write-Host ("{0,-16} {1,-42} {2}" -f $_[0], $_[1], $password)
}
Write-Host ""
