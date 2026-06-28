param(
    [string]$Project = "custoking-ims",
    [string]$Region = "asia-south2",
    [string]$GatewayBaseUrl = "https://custoking-api-gateway-xkv7oenbna-em.a.run.app",
    [string]$HostAddress = "10.116.0.3",
    [int]$Port = 5432,
    [string]$Database = "custoking_ims_v1",
    [string]$DbUser = "appuser",
    [string]$PasswordSecret = "db-password",
    [string]$Network = "default",
    [string]$Subnet = "default",
    [long]$PreferredSchoolId = 4,
    [string]$OutputJson = "artifacts/deployment-smoke.json",
    [string]$PreflightJson = "artifacts/real-environment-readiness-final.json",
    [string]$PreflightMarkdown = "artifacts/real-environment-readiness-final.md",
    [string]$LegacyCompatibilityJson = "artifacts/legacy-compatibility-audit-cloudsql.json",
    [string]$Gcloud = "C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
)

$ErrorActionPreference = "Stop"

$smokePassword = "password"
$bcryptPasswordHash = '$2a$10$J7RjqxrkPBk31.tolxpMkO0LHevKKGCNi6AsSPAsGeHtnyvHfmXlG'
$superEmail = "prod-smoke-superadmin@custoking.local"
$adminEmail = "prod-smoke-admin@custoking.local"
$context = $null

function ConvertTo-SqlLiteral {
    param([string]$Value)
    "'" + ($Value -replace "'", "''") + "'"
}

function Invoke-CloudSqlJob {
    param(
        [string]$NamePrefix,
        [string]$Sql,
        [string]$Marker
    )

    $job = $NamePrefix + "-" + (Get-Date -Format "yyyyMMddHHmmss") + "-" + ((New-Guid).ToString("n").Substring(0, 5))
    $encodedSql = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Sql))
    $script = "printf '%s' '$encodedSql' | base64 -d > /tmp/smoke.sql && psql -q -t -A -v ON_ERROR_STOP=1 -h $HostAddress -p $Port -U $DbUser -d $Database -f /tmp/smoke.sql | sed 's/^/$Marker|/'"

    try {
        & $Gcloud run jobs create $job `
            --project=$Project `
            --region=$Region `
            --image=postgres:16-alpine `
            --command=sh `
            --args=-c,$script `
            --set-env-vars=PGSSLMODE=disable `
            --set-secrets=PGPASSWORD="${PasswordSecret}:latest" `
            --network=$Network `
            --subnet=$Subnet `
            --vpc-egress=private-ranges-only `
            --max-retries=0 `
            --tasks=1 | Write-Output

        & $Gcloud run jobs execute $job --project=$Project --region=$Region --wait | Write-Output
        Start-Sleep -Seconds 3

        $filter = "resource.type=`"cloud_run_job`" AND resource.labels.job_name=`"$job`""
        return & $Gcloud logging read $filter `
            --project=$Project `
            --freshness=30m `
            --order=asc `
            --limit=300 `
            --format="value(textPayload)"
    } finally {
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & $Gcloud run jobs delete $job --project=$Project --region=$Region --quiet *> $null
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
    }
}

function Provision-SmokeUsers {
    $superEmailSql = ConvertTo-SqlLiteral $superEmail
    $adminEmailSql = ConvertTo-SqlLiteral $adminEmail
    $hashSql = ConvertTo-SqlLiteral $bcryptPasswordHash

    $sql = @"
DO `$`$
DECLARE
    selected_school_id bigint;
    selected_school_name text;
    selected_student_id bigint;
    selected_class_id text;
    selected_section_id text;
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

    SELECT st.id, st.class_id::text, st.section_id::text
      INTO selected_student_id, selected_class_id, selected_section_id
      FROM student.students st
     WHERE st.school_id = selected_school_id
       AND st.deleted_at IS NULL
     ORDER BY st.id
     LIMIT 1;

    IF selected_student_id IS NULL THEN
        SELECT ss.school_class_id::text, ss.id::text
          INTO selected_class_id, selected_section_id
          FROM tenant_school.school_sections ss
         WHERE ss.school_id = selected_school_id
           AND ss.active = true
         ORDER BY ss.id
         LIMIT 1;
    END IF;

    IF selected_class_id IS NULL OR selected_section_id IS NULL THEN
        RAISE EXCEPTION 'No class/section exists for selected smoke school %.', selected_school_id;
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

    RAISE NOTICE 'provisioned production smoke users super=% admin=% school=%', super_user_id, admin_user_id, selected_school_id;
END
`$`$;

SELECT selected.school_id || '|' ||
       COALESCE(selected.student_id::text, '1') || '|' ||
       selected.class_id || '|' ||
       selected.section_id || '|' ||
       au.id || '|' ||
       $superEmailSql || '|' ||
       $adminEmailSql
FROM (
    SELECT s.id AS school_id,
           st.id AS student_id,
           COALESCE(st.class_id::text, ss.school_class_id::text) AS class_id,
           COALESCE(st.section_id::text, ss.id::text) AS section_id
      FROM tenant_school.schools s
      LEFT JOIN LATERAL (
          SELECT id, class_id, section_id
            FROM student.students
           WHERE school_id = s.id AND deleted_at IS NULL
           ORDER BY id
           LIMIT 1
      ) st ON true
      LEFT JOIN LATERAL (
          SELECT id, school_class_id
            FROM tenant_school.school_sections
           WHERE school_id = s.id AND active = true
           ORDER BY id
           LIMIT 1
      ) ss ON true
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
    $sql = @"
WITH smoke_users AS (
    SELECT id
      FROM identity.app_users
     WHERE email IN ($superEmailSql, $adminEmailSql)
)
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
        -OutputJson $OutputJson
    if ($LASTEXITCODE -ne 0) {
        throw "Production gateway deployment smoke failed."
    }

    & (Join-Path $PSScriptRoot "invoke-real-environment-readiness-preflight.ps1") `
        -ProjectId $Project `
        -Region $Region `
        -DeploymentSmokeJson $OutputJson `
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
