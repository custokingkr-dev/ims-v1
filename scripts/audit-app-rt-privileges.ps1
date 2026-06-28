param(
    [string]$PostgresContainer = "custoking-postgres",
    [string]$Database = "postgres",
    [string]$DbUser = "postgres"
)
$ErrorActionPreference = "Stop"

$sqlPath = Join-Path $PSScriptRoot "audit-app-rt-privileges.sql"
docker cp $sqlPath "${PostgresContainer}:/tmp/audit-app-rt-privileges.sql" | Out-Null
docker exec $PostgresContainer psql -v ON_ERROR_STOP=1 -v run_live_probes=1 -U $DbUser -d $Database -f /tmp/audit-app-rt-privileges.sql
if ($LASTEXITCODE -ne 0) { throw "app_rt privilege audit failed (exit $LASTEXITCODE)." }
Write-Host "app_rt privilege audit passed." -ForegroundColor Green
