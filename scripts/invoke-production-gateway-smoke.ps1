param(
    [string]$Project = "custoking",
    [string]$Region = "asia-south2",
    [ValidateSet("dev", "prod")]
    [string]$Environment = "dev",
    [string]$GatewayBaseUrl,
    [string]$HostAddress,
    [int]$Port = 5432,
    [string]$Database,
    [string]$DbUser = "appuser",
    [string]$PasswordSecret = "db-password",
    [string]$CloudBuildId,
    [string]$Network = "default",
    [string]$Subnet = "default",
    [long]$PreferredSchoolId = 4,
    [string]$OutputJson = "artifacts/deployment-smoke.json",
    [string]$PreflightJson = "artifacts/real-environment-readiness-final.json",
    [string]$PreflightMarkdown = "artifacts/real-environment-readiness-final.md",
    [string]$LegacyCompatibilityJson = "artifacts/legacy-compatibility-audit-cloudsql.json",
    [int]$SmokeTimeoutSeconds = 60,
    [string]$Gcloud = "C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Database)) {
    $Database = "custoking_$Environment"
}
if ([string]::IsNullOrWhiteSpace($HostAddress)) {
    $instanceName = "custoking-db-$Environment"
    $instance = ((& $Gcloud sql instances describe $instanceName `
        --project=$Project `
        --format=json) -join "`n") | ConvertFrom-Json
    $privateIp = @($instance.ipAddresses | Where-Object { $_.type -eq "PRIVATE" }) |
        Select-Object -First 1
    $HostAddress = [string]$privateIp.ipAddress
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($HostAddress)) {
        throw "Could not resolve the private address for Cloud SQL instance $instanceName."
    }
}
if ([string]::IsNullOrWhiteSpace($GatewayBaseUrl)) {
    $gatewayServiceName = "custoking-api-gateway-$Environment"
    $GatewayBaseUrl = ((& $Gcloud run services describe $gatewayServiceName `
        --project=$Project `
        --region=$Region `
        --format="value(status.url)") -join "").Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($GatewayBaseUrl)) {
        throw "Could not resolve the Cloud Run URL for $gatewayServiceName."
    }
}

$smokePassword = "password"
$bcryptPasswordHash = '$2a$10$J7RjqxrkPBk31.tolxpMkO0LHevKKGCNi6AsSPAsGeHtnyvHfmXlG'
$superEmail = "prod-smoke-superadmin@custoking.local"
$adminEmail = "prod-smoke-admin@custoking.local"
$smokeAdmissionNo = "IMS-$Environment-SMOKE"
$context = $null
$script:CloudSqlJobName = "ims-gateway-smoke-sql-$Environment"
$script:CloudSqlJobEnsured = $false

function ConvertTo-SqlLiteral {
    param([string]$Value)
    "'" + ($Value -replace "'", "''") + "'"
}

function Ensure-CloudSqlJob {
    if ($script:CloudSqlJobEnsured) {
        return
    }

    $describeExitCode = 1
    $describeOutput = @()
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $describeOutput = @(& $Gcloud run jobs describe $script:CloudSqlJobName `
            --project=$Project `
            --region=$Region `
            --format=json 2> $null)
        $describeExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($describeExitCode -eq 0) {
        $existingJob = ($describeOutput -join "`n") | ConvertFrom-Json
        $container = $existingJob.spec.template.spec.template.spec.containers[0]
        if ($null -eq $container) {
            $container = $existingJob.spec.template.template.containers[0]
        }
        $currentSslMode = [string](@($container.env | Where-Object {
                    $_.name -eq "PGSSLMODE"
                } | Select-Object -First 1)[0].value)
        if ($currentSslMode.ToLowerInvariant() -ne "require") {
            Write-Host "Reconciling PGSSLMODE=require on existing Cloud Run job $script:CloudSqlJobName"
            & $Gcloud run jobs update $script:CloudSqlJobName `
                --project=$Project `
                --region=$Region `
                --update-env-vars=PGSSLMODE=require | Write-Output
            if ($LASTEXITCODE -ne 0) {
                throw "Could not require encrypted PostgreSQL transport on Cloud Run job $script:CloudSqlJobName."
            }
        }
    } else {
        & $Gcloud run jobs create $script:CloudSqlJobName `
            --project=$Project `
            --region=$Region `
            --image=postgres:16-alpine `
            --command=sh `
            --set-env-vars=PGSSLMODE=require `
            --set-secrets=PGPASSWORD="${PasswordSecret}:latest" `
            --network=$Network `
            --subnet=$Subnet `
            --vpc-egress=private-ranges-only `
            --max-retries=0 `
            --tasks=1 | Write-Output
        if ($LASTEXITCODE -ne 0) {
            throw "Could not create Cloud Run job $script:CloudSqlJobName."
        }
    }

    $script:CloudSqlJobEnsured = $true
}

function Invoke-CloudSqlJob {
    param(
        [string]$NamePrefix,
        [string]$Sql,
        [string]$Marker
    )

    $executionMarker = $Marker + "-" + ((New-Guid).ToString("n").Substring(0, 8))
    $encodedSql = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Sql))
    $script = "set -euo pipefail; printf '%s' '$encodedSql' | base64 -d > /tmp/smoke.sql; psql -q -t -A -v ON_ERROR_STOP=1 -h $HostAddress -p $Port -U $DbUser -d $Database -f /tmp/smoke.sql | sed 's/^/$executionMarker|/'"
    Ensure-CloudSqlJob

    Write-Host "Executing $NamePrefix through reusable Cloud Run job $script:CloudSqlJobName"
    # Use the Cloud Run v2 API for the execution override. Passing the encoded SQL through
    # gcloud.cmd exceeds the Windows command-line limit for the provisioning statement.
    $accessToken = ((& $Gcloud auth print-access-token) -join "").Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($accessToken)) {
        throw "Could not obtain a gcloud access token for the Cloud SQL smoke job."
    }
    $runUri = "https://run.googleapis.com/v2/projects/$Project/locations/$Region/jobs/$($script:CloudSqlJobName):run"
    $runBody = @{
        overrides = @{
            containerOverrides = @(
                @{ args = @("-c", $script) }
            )
        }
    } | ConvertTo-Json -Depth 8
    $headers = @{ Authorization = "Bearer $accessToken" }
    $operation = Invoke-RestMethod -Uri $runUri -Method Post -Headers $headers `
        -ContentType "application/json" -Body $runBody -TimeoutSec 60
    if ([string]::IsNullOrWhiteSpace([string]$operation.name)) {
        throw "Cloud SQL smoke job execution did not return an operation name."
    }
    $operationUri = "https://run.googleapis.com/v2/$($operation.name)"
    $operationDeadline = (Get-Date).AddMinutes(5)
    do {
        Start-Sleep -Seconds 5
        $operation = Invoke-RestMethod -Uri $operationUri -Headers $headers -TimeoutSec 60
    } while (-not $operation.done -and (Get-Date) -lt $operationDeadline)
    if (-not $operation.done) {
        throw "Timed out waiting for Cloud SQL smoke job operation $($operation.name)."
    }
    if ($operation.error) {
        throw "Cloud SQL smoke job failed: $($operation.error.message)"
    }
    Start-Sleep -Seconds 1

    # Keep the marker filter free of embedded quotes. On Windows, gcloud.cmd is mediated by
    # cmd.exe and nested filter quotes can be consumed before the Cloud Logging CLI sees them.
    $filter = "resource.type=cloud_run_job AND resource.labels.job_name=$($script:CloudSqlJobName) AND textPayload:$executionMarker"
    $lines = @()
    for ($attempt = 1; $attempt -le 10; $attempt++) {
        $lines = @(& $Gcloud logging read $filter `
            --project=$Project `
            --freshness=30m `
            --order=asc `
            --limit=100 `
            --format="value(textPayload)")
        if (@($lines | Where-Object { $_ -like "$executionMarker|*" }).Count -gt 0) {
            return @($lines | ForEach-Object {
                if ($_ -like "$executionMarker|*") {
                    $Marker + "|" + $_.Substring(("$executionMarker|").Length)
                } else {
                    $_
                }
            })
        }
        Start-Sleep -Seconds 2
    }
    return @($lines | ForEach-Object {
        if ($_ -like "$executionMarker|*") {
            $Marker + "|" + $_.Substring(("$executionMarker|").Length)
        } else {
            $_
        }
    })
}

function Provision-SmokeUsers {
    $superEmailSql = ConvertTo-SqlLiteral $superEmail
    $adminEmailSql = ConvertTo-SqlLiteral $adminEmail
    $smokeAdmissionNoSql = ConvertTo-SqlLiteral $smokeAdmissionNo
    $hashSql = ConvertTo-SqlLiteral $bcryptPasswordHash

    $sql = @"
DO `$`$
DECLARE
    selected_school_id bigint;
    selected_school_name text;
    selected_student_id bigint;
    selected_class_id text;
    selected_section_id text;
    selected_academic_year_id text;
    super_user_id bigint;
    admin_user_id bigint;
    super_role_id bigint;
    admin_role_id bigint;
BEGIN
    SELECT s.id, s.name
      INTO selected_school_id, selected_school_name
      FROM tenant_school.schools s
     WHERE s.id = $PreferredSchoolId
     LIMIT 1;

    IF selected_school_id IS NULL THEN
        SELECT s.id, s.name
          INTO selected_school_id, selected_school_name
          FROM tenant_school.schools s
         ORDER BY s.id
         LIMIT 1;
    END IF;

    IF selected_school_id IS NULL THEN
        RAISE EXCEPTION 'No school exists for production smoke.';
    END IF;

    SELECT ss.school_class_id::text, ss.id::text
      INTO selected_class_id, selected_section_id
      FROM tenant_school.school_sections ss
     WHERE ss.school_id = selected_school_id
       AND ss.active = true
     ORDER BY ss.id
     LIMIT 1;

    IF selected_class_id IS NULL OR selected_section_id IS NULL THEN
        RAISE EXCEPTION 'No class/section exists for selected smoke school %.', selected_school_id;
    END IF;

    SELECT id::text
      INTO selected_academic_year_id
      FROM tenant_school.academic_years
     WHERE active = true
     ORDER BY id
     LIMIT 1;

    IF selected_academic_year_id IS NULL THEN
        SELECT id::text
          INTO selected_academic_year_id
          FROM tenant_school.academic_years
         ORDER BY id
         LIMIT 1;
    END IF;

    IF selected_academic_year_id IS NULL THEN
        RAISE EXCEPTION 'No academic year exists for production smoke.';
    END IF;

    SELECT id INTO super_role_id FROM identity.roles WHERE UPPER(name) = 'SUPERADMIN' LIMIT 1;
    SELECT id INTO admin_role_id FROM identity.roles WHERE UPPER(name) IN ('ADMIN', 'SCHOOL_ADMIN') ORDER BY CASE WHEN UPPER(name) = 'ADMIN' THEN 0 ELSE 1 END LIMIT 1;

    IF super_role_id IS NULL THEN
        RAISE EXCEPTION 'SUPERADMIN role is missing.';
    END IF;
    IF admin_role_id IS NULL THEN
        RAISE EXCEPTION 'ADMIN/SCHOOL_ADMIN role is missing.';
    END IF;

    INSERT INTO identity.app_users (full_name, email, password_hash, role, branch_id, branch_name, created_at, deleted_at, deleted_by)
    VALUES ('Production Smoke Superadmin', $superEmailSql, $hashSql, 'SUPERADMIN', NULL, NULL, now(), NULL, NULL)
    ON CONFLICT (email) DO UPDATE SET
        full_name = EXCLUDED.full_name,
        password_hash = EXCLUDED.password_hash,
        role = EXCLUDED.role,
        branch_id = NULL,
        branch_name = NULL,
        deleted_at = NULL,
        deleted_by = NULL
    RETURNING id INTO super_user_id;

    INSERT INTO identity.app_users (full_name, email, password_hash, role, branch_id, branch_name, created_at, deleted_at, deleted_by)
    VALUES ('Production Smoke School Admin', $adminEmailSql, $hashSql, 'ADMIN', selected_school_id, selected_school_name, now(), NULL, NULL)
    ON CONFLICT (email) DO UPDATE SET
        full_name = EXCLUDED.full_name,
        password_hash = EXCLUDED.password_hash,
        role = EXCLUDED.role,
        branch_id = selected_school_id,
        branch_name = selected_school_name,
        deleted_at = NULL,
        deleted_by = NULL
    RETURNING id INTO admin_user_id;

    DELETE FROM identity.auth_sessions WHERE user_id IN (super_user_id, admin_user_id);

    UPDATE identity.user_role_assignments
       SET active = false, revoked_at = now(), revoked_by = super_user_id
     WHERE user_id IN (super_user_id, admin_user_id)
       AND active = true;

    INSERT INTO identity.user_role_assignments (user_id, role_id, school_id, zone_id, assigned_by, assigned_at, active, valid_from)
    VALUES (super_user_id, super_role_id, NULL, NULL, super_user_id, now(), true, now());

    INSERT INTO identity.user_role_assignments (user_id, role_id, school_id, zone_id, assigned_by, assigned_at, active, valid_from)
    VALUES (admin_user_id, admin_role_id, selected_school_id, NULL, super_user_id, now(), true, now());

    INSERT INTO student.students
        (admission_no, roll_no, full_name, dob, gender, father_name, father_contact,
         phone, address, fee_status, attendance_percent, created_at, updated_at,
         school_id, class_id, section_id, academic_year_id, version, deleted_at,
         deleted_by, created_by, updated_by)
    VALUES
        ($smokeAdmissionNoSql, 'SMOKE', 'Production Smoke Student', DATE '2016-01-01', 'NA',
         'Production Smoke Parent', '9999999999', '9999999999', 'Production smoke address',
         'Pending', 0, now(), now(), selected_school_id, selected_class_id,
         selected_section_id, selected_academic_year_id, 0, NULL, NULL,
         'production-gateway-smoke', 'production-gateway-smoke')
    ON CONFLICT (school_id, admission_no) DO UPDATE SET
        roll_no = EXCLUDED.roll_no,
        full_name = EXCLUDED.full_name,
        dob = EXCLUDED.dob,
        gender = EXCLUDED.gender,
        father_name = EXCLUDED.father_name,
        father_contact = EXCLUDED.father_contact,
        phone = EXCLUDED.phone,
        address = EXCLUDED.address,
        fee_status = EXCLUDED.fee_status,
        attendance_percent = EXCLUDED.attendance_percent,
        updated_at = now(),
        class_id = EXCLUDED.class_id,
        section_id = EXCLUDED.section_id,
        academic_year_id = EXCLUDED.academic_year_id,
        deleted_at = NULL,
        deleted_by = NULL,
        updated_by = 'production-gateway-smoke'
    RETURNING id INTO selected_student_id;

    RAISE NOTICE 'provisioned production smoke users super=% admin=% school=%', super_user_id, admin_user_id, selected_school_id;
END
`$`$;

SELECT selected.school_id || '|' ||
       selected.student_id::text || '|' ||
       selected.class_id || '|' ||
       selected.section_id || '|' ||
       au.id || '|' ||
       $superEmailSql || '|' ||
       $adminEmailSql
FROM (
    SELECT s.id AS school_id,
           st.id AS student_id,
           st.class_id::text AS class_id,
           st.section_id::text AS section_id
      FROM tenant_school.schools s
      JOIN LATERAL (
          SELECT id, class_id, section_id
            FROM student.students
           WHERE school_id = s.id
             AND admission_no = $smokeAdmissionNoSql
             AND deleted_at IS NULL
           LIMIT 1
      ) st ON true
     WHERE s.id = COALESCE((SELECT id FROM tenant_school.schools WHERE id = $PreferredSchoolId LIMIT 1), (SELECT id FROM tenant_school.schools ORDER BY id LIMIT 1))
     LIMIT 1
) selected
JOIN identity.app_users au ON au.email = $adminEmailSql;
"@

    $lines = Invoke-CloudSqlJob "ims-prod-smoke-provision" $sql "IMS_SMOKE_CONTEXT"
    foreach ($line in $lines) {
        if ($line -like "IMS_SMOKE_CONTEXT|*") {
            $parts = $line.Substring("IMS_SMOKE_CONTEXT|".Length).Split("|")
            if ($parts.Count -eq 7) {
                return [pscustomobject]@{
                    schoolId = [long]$parts[0]
                    studentId = [long]$parts[1]
                    classId = $parts[2]
                    sectionId = $parts[3]
                    adminUserId = [long]$parts[4]
                    superadminEmail = $parts[5]
                    adminEmail = $parts[6]
                }
            }
        }
    }
    throw "Production smoke provisioning did not return context."
}

function Retire-SmokeUsers {
    $superEmailSql = ConvertTo-SqlLiteral $superEmail
    $adminEmailSql = ConvertTo-SqlLiteral $adminEmail
    $smokeAdmissionNoSql = ConvertTo-SqlLiteral $smokeAdmissionNo
    $sql = @"
CREATE TEMP TABLE smoke_users AS
SELECT id
  FROM identity.app_users
 WHERE email IN ($superEmailSql, $adminEmailSql);

DELETE FROM identity.auth_sessions
 WHERE user_id IN (SELECT id FROM smoke_users);

UPDATE identity.user_role_assignments
   SET active = false,
       revoked_at = now(),
       revoked_by = NULL
 WHERE user_id IN (SELECT id FROM smoke_users)
   AND active = true;

UPDATE identity.app_users
   SET deleted_at = now(),
       deleted_by = 'production-gateway-smoke',
       email = 'deleted+' || id || '+' || email
 WHERE email IN ($superEmailSql, $adminEmailSql);

UPDATE student.students
   SET deleted_at = now(),
       deleted_by = 'production-gateway-smoke',
       updated_at = now()
 WHERE admission_no = $smokeAdmissionNoSql;

SELECT 'retired';
"@
    Invoke-CloudSqlJob "ims-prod-smoke-retire" $sql "IMS_SMOKE_RETIRE" | Out-Null
}

try {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputJson) | Out-Null
    $context = Provision-SmokeUsers

    $env:IMS_SMOKE_SUPERADMIN_EMAIL = $context.superadminEmail
    $env:IMS_SMOKE_SUPERADMIN_PASSWORD = $smokePassword
    $env:IMS_SMOKE_ADMIN_EMAIL = $context.adminEmail
    $env:IMS_SMOKE_ADMIN_PASSWORD = $smokePassword

    & (Join-Path $PSScriptRoot "smoke-deployment-readiness.ps1") `
        -GatewayBaseUrl $GatewayBaseUrl `
        -SuperadminEmail $context.superadminEmail `
        -SuperadminPassword $smokePassword `
        -AdminEmail $context.adminEmail `
        -AdminPassword $smokePassword `
        -SchoolId $context.schoolId `
        -StudentId $context.studentId `
        -AdminUserId $context.adminUserId `
        -ClassId $context.classId `
        -SectionId $context.sectionId `
        -TimeoutSeconds $SmokeTimeoutSeconds `
        -RunPhotoUploadSmoke `
        -OutputJson $OutputJson
    if ($LASTEXITCODE -ne 0) {
        throw "Production gateway deployment smoke failed."
    }

    & (Join-Path $PSScriptRoot "invoke-real-environment-readiness-preflight.ps1") `
        -ProjectId $Project `
        -Region $Region `
        -Environment $Environment `
        -DeploymentSmokeJson $OutputJson `
        -CloudBuildId $CloudBuildId `
        -LegacyCompatibilityJson $LegacyCompatibilityJson `
        -GatewayBaseUrl $GatewayBaseUrl `
        -GcloudPath $Gcloud `
        -OutputJson $PreflightJson `
        -OutputMarkdown $PreflightMarkdown
    if ($LASTEXITCODE -ne 0) {
        throw "Final real environment preflight failed."
    }
} finally {
    if ($context) {
        Retire-SmokeUsers
    }
    Remove-Item Env:IMS_SMOKE_SUPERADMIN_EMAIL -ErrorAction SilentlyContinue
    Remove-Item Env:IMS_SMOKE_SUPERADMIN_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:IMS_SMOKE_ADMIN_EMAIL -ErrorAction SilentlyContinue
    Remove-Item Env:IMS_SMOKE_ADMIN_PASSWORD -ErrorAction SilentlyContinue
}
