param(
    [string]$Project = "custoking",
    [string]$Region = "asia-south2",
    [string]$HostAddress = "10.116.0.3",
    [int]$Port = 5432,
    [string]$Database = "custoking_ims_v1",
    [string]$ConnectUser = "appuser",
    [string]$Owner = "appuser",
    [string]$ConnectPasswordSecret = "db-password",
    [string]$AppRtPasswordSecret = "app-rt-password",
    [switch]$AuditOnly,
    [string]$Network = "default",
    [string]$Subnet = "default",
    [string]$Gcloud = "C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
)
$ErrorActionPreference = "Stop"

$createSql = Get-Content (Join-Path $PSScriptRoot "create-app-rt-role.sql") -Raw
$auditSql  = Get-Content (Join-Path $PSScriptRoot "audit-app-rt-privileges.sql") -Raw

$createB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($createSql))
$auditB64  = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($auditSql))

# Runs as appuser; create-app-rt-role.sql elevates via SET ROLE cloudsqlsuperuser (set_superuser=1, owner=appuser).
# Live RLS/DDL probes are NOT run in prod (appuser is not a superuser): the audit asserts the structural
# attributes instead (NOSUPERUSER/NOBYPASSRLS/not-a-cloudsqlsuperuser-member), which guarantees RLS will bite.
$createStep = "printf '%s' '$createB64' | base64 -d > /tmp/create.sql && psql -v ON_ERROR_STOP=1 -v app_rt_password=`"`$APP_RT_PASSWORD`" -v owner=$Owner -v set_superuser=1 -h $HostAddress -p $Port -U $ConnectUser -d $Database -f /tmp/create.sql"
$auditStep  = "printf '%s' '$auditB64' | base64 -d > /tmp/audit.sql && psql -v ON_ERROR_STOP=1 -h $HostAddress -p $Port -U $ConnectUser -d $Database -f /tmp/audit.sql"
$script = if ($AuditOnly) { $auditStep } else { "$createStep && $auditStep" }
$jobArgs = "-c,$script"

$job = "ims-app-rt-" + (Get-Date -Format "yyyyMMddHHmmss")
try {
    & $Gcloud run jobs create $job `
        --project=$Project --region=$Region `
        --image=postgres:16-alpine --command=sh "--args=$jobArgs" `
        --set-env-vars=PGSSLMODE=disable `
        --set-secrets="PGPASSWORD=${ConnectPasswordSecret}:latest,APP_RT_PASSWORD=${AppRtPasswordSecret}:latest" `
        --network=$Network --subnet=$Subnet --vpc-egress=private-ranges-only `
        --max-retries=0 --tasks=1 | Write-Output

    & $Gcloud run jobs execute $job --project=$Project --region=$Region --wait | Write-Output
    Write-Host "app_rt provisioning job completed: $job" -ForegroundColor Green
} finally {
    $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    try { & $Gcloud run jobs delete $job --project=$Project --region=$Region --quiet *> $null }
    finally { $ErrorActionPreference = $prev }
}
