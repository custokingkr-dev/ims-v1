param(
    [string]$PostgresContainer = "custoking-postgres",
    [string]$Database = "postgres",
    [string]$DbUser = "postgres",
    [string]$AppRtPassword = "app_rt",
    [int]$TimeoutSeconds = 180
)
$ErrorActionPreference = "Stop"

$schemaReady = $false
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    $checkResult = docker exec $PostgresContainer psql -U $DbUser -d $Database -t -A -c "SELECT to_regclass('identity.app_users') IS NOT NULL AND to_regclass('tenant_school.schools') IS NOT NULL;" 2>$null
    if (($checkResult | Select-Object -Last 1) -eq "t") { $schemaReady = $true; break }
    Start-Sleep -Seconds 3
}
if (-not $schemaReady) { throw "Timed out after $TimeoutSeconds s waiting for identity.app_users and tenant_school.schools in $PostgresContainer." }

$sqlPath = Join-Path $PSScriptRoot "create-app-rt-role.sql"
docker cp $sqlPath "${PostgresContainer}:/tmp/create-app-rt-role.sql" | Out-Null
docker exec $PostgresContainer psql -v ON_ERROR_STOP=1 -v app_rt_password="$AppRtPassword" -v owner=postgres -U $DbUser -d $Database -f /tmp/create-app-rt-role.sql
if ($LASTEXITCODE -ne 0) { throw "Failed to ensure app_rt role/grants locally (exit $LASTEXITCODE)." }
Write-Host "Local app_rt role and grants are ready." -ForegroundColor Green
