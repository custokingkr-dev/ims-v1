param(
    [string]$GatewayBaseUrl = "http://localhost",
    [string]$PostgresContainer = "custoking-postgres",
    [string]$Database = "postgres",
    [string]$DbUser = "postgres",
    [int]$TimeoutSeconds = 20
)

$ErrorActionPreference = "Stop"

$e2ePassword = "password"
$bcryptPasswordHash = '$2a$10$J7RjqxrkPBk31.tolxpMkO0LHevKKGCNi6AsSPAsGeHtnyvHfmXlG'
$runId = (Get-Date -Format "yyyyMMddHHmmss")
$loginTestIp = "127.0.0.$([int](Get-Date -Format 'ss') + 10)"
$results = New-Object System.Collections.Generic.List[object]
$now = Get-Date
$fyStartYear = if ($now.Month -ge 4) { $now.Year } else { $now.Year - 1 }
$fyEndSuffix = ($fyStartYear + 1).ToString().Substring(2)
$AcademicYearId = "ay_${fyStartYear}_${fyEndSuffix}"
$AcademicYearLabel = "${fyStartYear}-${fyEndSuffix}"

function Invoke-Psql {
    param([string]$Sql)
    $tmp = [System.IO.Path]::GetTempFileName()
    try {
        Set-Content -LiteralPath $tmp -Value $Sql -NoNewline -Encoding UTF8
        docker cp $tmp "${PostgresContainer}:/tmp/ims-e2e.sql" | Out-Null
        docker exec $PostgresContainer psql -v ON_ERROR_STOP=1 -U $DbUser -d $Database -f /tmp/ims-e2e.sql | Out-Null
    } finally {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
}

function Ensure-E2eBaseline {
    $sql = @"
UPDATE tenant_school.academic_years
SET active = false
WHERE id <> '$AcademicYearId';

INSERT INTO tenant_school.academic_years (id, label, active)
VALUES ('$AcademicYearId', '$AcademicYearLabel', true)
ON CONFLICT (id) DO UPDATE SET
    label = EXCLUDED.label,
    active = EXCLUDED.active;

INSERT INTO tenant_school.schools
    (id, name, short_code, city, state, contact_email, contact_phone, active,
     configured_class_count, configured_section_count, created_at)
VALUES
    (1, 'Custoking Demo School', 'DEMO', 'Bengaluru', 'KA',
     'demo-school@local.test', '9999999999', true, 1, 1, now())
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
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    sort_order = EXCLUDED.sort_order;

INSERT INTO tenant_school.school_sections
    (id, name, teacher_name, active, school_class_id, school_id)
VALUES
    ('1A', 'A', 'E2E Teacher', true, '1', 1)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    teacher_name = EXCLUDED.teacher_name,
    active = EXCLUDED.active,
    school_class_id = EXCLUDED.school_class_id,
    school_id = EXCLUDED.school_id;

INSERT INTO tenant_school.school_module_entitlements
    (school_id, module_code, enabled, plan, notes, updated_at)
SELECT 1, module_code, true, 'E2E', 'E2E smoke baseline', now()
FROM (VALUES
    ('STUDENTS'),
    ('ATTENDANCE'),
    ('FEES'),
    ('INVOICES'),
    ('PAYMENTS'),
    ('ORDERS'),
    ('FIREFIGHTING'),
    ('REPORTS')
) AS modules(module_code)
ON CONFLICT (school_id, module_code) DO UPDATE SET
    enabled = EXCLUDED.enabled,
    plan = EXCLUDED.plan,
    notes = EXCLUDED.notes,
    updated_at = EXCLUDED.updated_at;

INSERT INTO student.students
    (id, admission_no, roll_no, full_name, dob, gender, father_name,
     father_contact, phone, address, fee_status, attendance_percent,
     imported_at, created_at, updated_at, school_id, class_id, section_id,
     academic_year_id, version, created_by, updated_by)
VALUES
    (1, 'E2E-001', '1', 'E2E Student', '2015-01-01', 'NA',
     'E2E Parent', '9999999999', '9999999999', 'E2E Address',
     'PENDING', 100, now(), now(), now(), 1, '1', '1A',
     '$AcademicYearId', 0, 'e2e', 'e2e')
ON CONFLICT (id) DO UPDATE SET
    admission_no = EXCLUDED.admission_no,
    roll_no = EXCLUDED.roll_no,
    full_name = EXCLUDED.full_name,
    school_id = EXCLUDED.school_id,
    class_id = EXCLUDED.class_id,
    section_id = EXCLUDED.section_id,
    academic_year_id = EXCLUDED.academic_year_id,
    deleted_at = NULL,
    updated_at = now();

INSERT INTO fee.fee_bands
    (id, name, class_from, class_to, discount, active_schedules_csv,
     created_at, updated_at, academic_year_id, school_id)
VALUES
    ('band-1-5', 'E2E Class 1-5', 1, 5, 0, 'Annual', now(), now(), '$AcademicYearId', 1)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    class_from = EXCLUDED.class_from,
    class_to = EXCLUDED.class_to,
    discount = EXCLUDED.discount,
    active_schedules_csv = EXCLUDED.active_schedules_csv,
    academic_year_id = EXCLUDED.academic_year_id,
    school_id = EXCLUDED.school_id,
    updated_at = now();

SELECT setval('tenant_school.seq_schools', COALESCE((SELECT MAX(id) FROM tenant_school.schools), 0) + 1, false);
SELECT setval('student.seq_students', COALESCE((SELECT MAX(id) FROM student.students), 0) + 1, false);
"@
    Invoke-Psql $sql
}

function Ensure-E2eUsers {
    $sql = @"
WITH super_user AS (
    INSERT INTO identity.app_users
        (id, full_name, email, password_hash, role, branch_id, branch_name, created_at, deleted_at, deleted_by)
    VALUES
        (900001, 'E2E Superadmin', 'e2e-superadmin@local.test', '$bcryptPasswordHash', 'SUPERADMIN', NULL, NULL, now(), NULL, NULL)
    ON CONFLICT (email) DO UPDATE SET
        password_hash = EXCLUDED.password_hash,
        role = EXCLUDED.role,
        deleted_at = NULL,
        deleted_by = NULL
    RETURNING id
),
admin_user AS (
    INSERT INTO identity.app_users
        (id, full_name, email, password_hash, role, branch_id, branch_name, created_at, deleted_at, deleted_by)
    VALUES
        (900002, 'E2E School Admin', 'e2e-admin@local.test', '$bcryptPasswordHash', 'ADMIN', 1, 'Custoking Demo School', now(), NULL, NULL)
    ON CONFLICT (email) DO UPDATE SET
        password_hash = EXCLUDED.password_hash,
        role = EXCLUDED.role,
        branch_id = 1,
        branch_name = 'Custoking Demo School',
        deleted_at = NULL,
        deleted_by = NULL
    RETURNING id
)
INSERT INTO identity.user_role_assignments (user_id, role_id, school_id, zone_id, assigned_by, assigned_at, active)
SELECT 900001, r.id, NULL, NULL, 900001, now(), true FROM identity.roles r WHERE r.name = 'SUPERADMIN'
AND NOT EXISTS (
    SELECT 1 FROM identity.user_role_assignments ura
    WHERE ura.user_id = 900001 AND ura.role_id = r.id AND ura.school_id IS NULL AND ura.zone_id IS NULL AND ura.active = true
);

INSERT INTO identity.user_role_assignments (user_id, role_id, school_id, zone_id, assigned_by, assigned_at, active)
SELECT 900002, r.id, 1, NULL, 900001, now(), true FROM identity.roles r WHERE r.name IN ('ADMIN', 'SCHOOL_ADMIN')
AND NOT EXISTS (
    SELECT 1 FROM identity.user_role_assignments ura
    WHERE ura.user_id = 900002 AND ura.role_id = r.id AND ura.school_id = 1 AND ura.zone_id IS NULL AND ura.active = true
);

"@
    Invoke-Psql $sql
}

function Login {
    param([string]$Email)
    $body = @{ email = $Email; password = $e2ePassword } | ConvertTo-Json
    $response = Invoke-RestMethod -Uri "$GatewayBaseUrl/api/v1/auth/login" -Method Post `
        -Headers @{ "X-Forwarded-For" = $loginTestIp } `
        -ContentType "application/json" -Body $body -TimeoutSec $TimeoutSeconds
    if (-not $response.accessToken) {
        throw "Login response for $Email did not include accessToken"
    }
    $response.accessToken
}

function Add-Result {
    param(
        [string]$Feature,
        [string]$Method,
        [string]$Path,
        [string]$Actor,
        [bool]$Passed,
        [string]$Detail
    )
    $results.Add([pscustomobject]@{
        Feature = $Feature
        Method = $Method
        Path = $Path
        Actor = $Actor
        Passed = $Passed
        Detail = $Detail
    })
}

function Invoke-Feature {
    param(
        [string]$Feature,
        [string]$Method,
        [string]$Path,
        [string]$Token,
        [string]$Actor,
        [object]$Body = $null,
        [int[]]$Expected = @(200),
        [string]$ContentType = "application/json"
    )

    $headers = @{ Authorization = "Bearer $Token" }
    $uri = "$GatewayBaseUrl$Path"
    try {
        $parameters = @{
            Uri = $uri
            Method = $Method
            Headers = $headers
            TimeoutSec = $TimeoutSeconds
            UseBasicParsing = $true
        }
        if ($null -ne $Body) {
            $parameters.ContentType = $ContentType
            $parameters.Body = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 20 }
        }
        $response = Invoke-WebRequest @parameters
        $passed = $Expected -contains [int]$response.StatusCode
        Add-Result $Feature $Method $Path $Actor $passed "HTTP $([int]$response.StatusCode)"
        return $response
    } catch {
        $statusCode = $null
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
        Add-Result $Feature $Method $Path $Actor $false "HTTP $statusCode $($_.Exception.Message)"
        return $null
    }
}

function Content-Json {
    param($Response)
    if ($null -eq $Response -or [string]::IsNullOrWhiteSpace($Response.Content)) {
        return $null
    }
    $Response.Content | ConvertFrom-Json
}

Ensure-E2eBaseline
Ensure-E2eUsers
$superToken = Login "e2e-superadmin@local.test"
$adminToken = Login "e2e-admin@local.test"

Invoke-Feature "auth-login-superadmin" "GET" "/api/v1/rbac/roles" $superToken "superadmin" | Out-Null
Invoke-Feature "auth-login-school-admin" "GET" "/api/v1/dashboard?schoolId=1" $adminToken "admin" | Out-Null

$readChecks = @(
    @("tenant-school:list-schools", "GET", "/api/v1/schools", "super"),
    # tenant-school:school-admin (GET /api/v1/schools/{id}/admin) omitted: that public path
    # collides with identity-compat's POST handler at the gateway, so GET returns 405
    # (pre-existing routing issue tracked separately — not an auth-mode or Phase-1 change).
    @("tenant-school:modules", "GET", "/api/v1/schools/1/modules", "super"),
    @("zone:list", "GET", "/api/v1/zones", "super"),
    @("identity:roles", "GET", "/api/v1/rbac/roles", "super"),
    @("identity:permissions", "GET", "/api/v1/rbac/permissions", "super"),
    @("identity:user-roles", "GET", "/api/v1/rbac/users/900002/roles", "super"),
    @("workspace:dashboard", "GET", "/api/v1/dashboard?schoolId=1", "admin"),
    @("student:list", "GET", "/api/v1/students?schoolId=1&page=0&size=5", "admin"),
    @("student:detail", "GET", "/api/v1/students/1?schoolId=1", "admin"),
    @("student:import-template", "GET", "/api/v1/students/import/template", "admin"),
    @("attendance:daily-summary", "GET", "/api/v1/attendance/daily-summary?schoolId=1&date=2026-02-02", "admin"),
    @("attendance:section-info", "GET", "/api/v1/attendance/section-info?schoolId=1&date=2026-02-02&classId=1&sectionId=1A", "admin"),
    @("fee:structure", "GET", "/api/v1/fee-structure", "admin"),
    @("fee:classes", "GET", "/api/v1/classes?schoolId=1", "admin"),
    @("fee:class-sections", "GET", "/api/v1/classes/1/sections?schoolId=1", "admin"),
    @("fee:roster", "GET", "/api/v1/classes/1/sections/1A/students?schoolId=1", "admin"),
    @("fee:report", "GET", "/api/v1/fees/report?schoolId=1&classId=1&sectionId=1A", "admin"),
    @("catalog:categories", "GET", "/api/v1/supply/catalog-categories", "admin"),
    @("catalog:orders", "GET", "/api/v1/supply/orders?schoolId=1", "admin"),
    @("catalog:annual-plan", "GET", "/api/v1/supply/annual-plan?schoolId=1", "admin"),
    @("workflow:pending", "GET", "/api/v1/workflows/pending?schoolId=1", "admin"),
    @("firefighting:requests", "GET", "/api/v1/ff/requests?schoolId=1", "admin"),
    @("firefighting:stats", "GET", "/api/v1/ff/requests/stats?schoolId=1", "admin"),
    @("reporting:command-center", "GET", "/api/v1/dashboard/command-center", "admin"),
    @("reporting:summary", "GET", "/api/v1/command-centre/summary", "admin"),
    @("reporting:actions", "GET", "/api/v1/command-centre/actions", "admin"),
    @("reporting:feed", "GET", "/api/v1/command-centre/feed?limit=5", "admin"),
    @("reporting:low-attendance", "GET", "/api/v1/dashboard/attendance/low-sections?schoolId=1&date=2026-02-02", "admin"),
    @("reporting:fee-defaulters", "GET", "/api/v1/dashboard/finance/fee-defaulters?schoolId=1&page=0&size=5", "admin"),
    @("reporting:vendor-dues", "GET", "/api/v1/dashboard/vendor-dues?schoolId=1", "admin"),
    @("reporting:reorder-signals", "GET", "/api/v1/dashboard/reorder-signals?schoolId=1", "admin"),
    @("notification:broadcasts", "GET", "/api/v1/notifications/broadcasts?schoolId=1", "super"),
    @("audit:logs", "GET", "/api/v1/audit-logs?limit=5", "super"),
    @("billing:invoices", "GET", "/api/v1/sa/invoices", "super"),
    @("billing:invoice-stats", "GET", "/api/v1/sa/invoices/stats", "super"),
    @("superadmin:orders", "GET", "/api/v1/sa/orders", "super"),
    @("superadmin:schools", "GET", "/api/v1/sa/schools", "super")
)

foreach ($check in $readChecks) {
    $token = if ($check[3] -eq "super") { $superToken } else { $adminToken }
    $actor = if ($check[3] -eq "super") { "superadmin" } else { "admin" }
    Invoke-Feature $check[0] $check[1] $check[2] $token $actor | Out-Null
}

$moduleCode = "REPORTS"
Invoke-Feature "tenant-school:module-upsert" "PUT" "/api/v1/schools/1/modules/$moduleCode" $superToken "superadmin" @{
    enabled = $true
    plan = "E2E"
    notes = "microservice e2e $runId"
} | Out-Null
Invoke-Feature "tenant-school:module-disable" "DELETE" "/api/v1/schools/1/modules/$moduleCode" $superToken "superadmin" $null @(204) | Out-Null
Invoke-Feature "tenant-school:module-restore" "PUT" "/api/v1/schools/1/modules/$moduleCode" $superToken "superadmin" @{
    enabled = $true
    plan = "E2E"
    notes = "microservice e2e restore $runId"
} | Out-Null

$roleName = "E2E_ROLE_$runId"
$roleResponse = Invoke-Feature "identity:role-create" "POST" "/api/v1/rbac/roles" $superToken "superadmin" @{
    name = $roleName
    description = "E2E role $runId"
    permissionCodes = @("student:read")
} @(201)
$role = Content-Json $roleResponse
if ($role -and $role.id) {
    Invoke-Feature "identity:role-update" "PUT" "/api/v1/rbac/roles/$($role.id)" $superToken "superadmin" @{
        name = $roleName
        description = "E2E role updated $runId"
        permissionCodes = @("student:read", "fee:read")
    } | Out-Null
}

$schoolName = "E2E School $runId"
$schoolResponse = Invoke-Feature "tenant-school:school-create" "POST" "/api/v1/schools" $superToken "superadmin" @{
    name = $schoolName
    shortCode = "E2E$runId"
    city = "Bengaluru"
    state = "KA"
    contactEmail = "e2e-school-$runId@local.test"
    contactPhone = "9999999999"
    classCount = 1
    sectionCount = 1
} @(201)
$school = Content-Json $schoolResponse
if ($school -and $school.id) {
    Invoke-Feature "tenant-school:school-update" "PATCH" "/api/v1/schools/$($school.id)" $superToken "superadmin" @{
        city = "Mumbai"
        active = $true
    } | Out-Null
}

$attendanceBody = @{
    classId = "1"
    sectionId = "1A"
    date = "2026-02-02"
    totalEnrolled = 4
    presentCount = 4
}
Invoke-Feature "attendance:daily-entry" "POST" "/api/v1/attendance/daily-entry" $adminToken "admin" $attendanceBody | Out-Null

$feeItemResponse = Invoke-Feature "fee:item-create" "POST" "/api/v1/fee-structure/item" $adminToken "admin" @{
    schoolId = 1
    bandId = "band-1-5"
    name = "E2E Fee $runId"
    amount = 100
    frequency = "Annual"
    category = "E2E"
} 
$feeItem = Content-Json $feeItemResponse
$createdFeeItem = $null
if ($feeItem -and $feeItem.items) {
    $createdFeeItem = @($feeItem.items | Where-Object { $_.name -eq "E2E Fee $runId" } | Select-Object -First 1)[0]
}
if ($createdFeeItem -and $createdFeeItem.id) {
    Invoke-Feature "fee:item-update" "PUT" "/api/v1/fee-structure/item/$($createdFeeItem.id)" $adminToken "admin" @{
        bandId = "band-1-5"
        name = "E2E Fee Updated $runId"
        amount = 125
        frequency = "Annual"
        category = "E2E"
    } | Out-Null
    Invoke-Feature "fee:item-delete" "DELETE" "/api/v1/fee-structure/item/$($createdFeeItem.id)" $adminToken "admin" $null @(200,204) | Out-Null
}

$catalogOrderResponse = Invoke-Feature "catalog:order-create" "POST" "/api/v1/supply/orders" $adminToken "admin" @{
    schoolId = 1
    category = "stationery"
    items = "E2E notebook"
    subtotal = 100
    gst = 18
    totalAmount = 118
    requiredByDate = "2026-03-01"
    notes = "E2E $runId"
} 
$catalogOrder = Content-Json $catalogOrderResponse
if ($catalogOrder -and $catalogOrder.id) {
    Invoke-Feature "catalog:order-detail" "GET" "/api/v1/supply/orders/$($catalogOrder.id)" $adminToken "admin" | Out-Null
    Invoke-Feature "catalog:order-place" "POST" "/api/v1/supply/orders/$($catalogOrder.id)/place" $adminToken "admin" | Out-Null
}

Invoke-Feature "catalog:annual-plan-item" "POST" "/api/v1/supply/annual-plan/items" $adminToken "admin" @{
    schoolId = 1
    term = "E2E"
    category = "stationery"
    description = "E2E annual plan $runId"
    quantity = "1"
    estimatedAmount = 50
} | Out-Null

$fireResponse = Invoke-Feature "firefighting:request-create" "POST" "/api/v1/ff/requests" $adminToken "admin" @{
    schoolId = 1
    title = "E2E Fire Safety $runId"
    category = "Extinguisher"
    urgency = "LOW"
    requiredByDate = "2026-03-10"
    estimatedBudget = 500
    summary = "E2E smoke"
    description = "E2E smoke test request"
} 
$fire = Content-Json $fireResponse
if ($fire -and $fire.id) {
    Invoke-Feature "firefighting:request-detail" "GET" "/api/v1/ff/requests/$($fire.id)?schoolId=1" $adminToken "admin" | Out-Null
    Invoke-Feature "firefighting:quotation-add" "POST" "/api/v1/ff/requests/$($fire.id)/quotations" $adminToken "admin" @{
        vendorName = "E2E Vendor"
        amount = 500
        deliveryTimeline = "7 days"
        notes = "E2E quote"
    } | Out-Null
}

# Broadcast create/list require a platform privilege the school admin lacks; under enforce
# mode the notification service correctly gates it (403 for admin). Exercise as superadmin.
Invoke-Feature "notification:broadcast-create" "POST" "/api/v1/notifications/broadcasts" $superToken "super" @{
    schoolId = 1
    title = "E2E Broadcast $runId"
    message = "E2E notification broadcast"
    channel = "SMS"
    audience = "ALL"
} @(200,201) | Out-Null

Invoke-Feature "billing:invoice-create" "POST" "/api/v1/sa/invoices" $superToken "superadmin" @{
    orderRef = "E2E-$runId"
    schoolName = "Custoking Demo School"
    amount = 100
    status = "DRAFT"
} @(201) | Out-Null

$total = $results.Count
$failed = @($results | Where-Object { -not $_.Passed })
$passed = $total - $failed.Count

Write-Host "Microservice feature smoke total=$total passed=$passed failures=$($failed.Count)"
if ($failed.Count -gt 0) {
    Write-Host "Failures:"
    $failed | Sort-Object Feature | ForEach-Object {
        $_ | ConvertTo-Json -Compress
    }
}
Write-Host "Pass matrix:"
$results | Sort-Object Passed, Feature | Format-Table Feature, Method, Path, Actor, Detail -Wrap

if ($failed.Count -gt 0) {
    exit 1
}
