param(
    [string]$PostgresContainer = "custoking-postgres",
    [string]$Database = "postgres",
    [string]$DbUser = "postgres",
    [string]$AppRtPassword = "app_rt",
    [int]$TimeoutSeconds = 180,
    [string[]]$RequiredSchemas = @("identity", "tenant_school")
)
$ErrorActionPreference = "Stop"

$requiredSchemaNames = $RequiredSchemas |
    ForEach-Object { $_ -split "," } |
    ForEach-Object { $_.Trim() } |
    Where-Object { $_ }
if (-not $requiredSchemaNames) { throw "At least one required schema must be specified." }

$sqlPath = Join-Path $PSScriptRoot "create-app-rt-role.sql"
docker cp $sqlPath "${PostgresContainer}:/tmp/create-app-rt-role.sql" | Out-Null

$lastGrantedSchemaSet = $null
$schemaReady = $false
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    $schemaValues = ($requiredSchemaNames | ForEach-Object { "('$_')" }) -join ","
    $existingSchemasSql = @"
SELECT n.nspname
FROM (VALUES $schemaValues) AS s(name)
JOIN pg_namespace n ON n.nspname = s.name
ORDER BY n.nspname;
"@
    $existingSchemas = @(docker exec $PostgresContainer psql -U $DbUser -d $Database -t -A -c $existingSchemasSql 2>$null)
    $schemaSet = ($existingSchemas | Where-Object { $_ }) -join ","
    if ($schemaSet -and $schemaSet -ne $lastGrantedSchemaSet) {
        docker exec $PostgresContainer psql -v ON_ERROR_STOP=1 -v app_rt_password="$AppRtPassword" -v owner=postgres -U $DbUser -d $Database -f /tmp/create-app-rt-role.sql
        if ($LASTEXITCODE -ne 0) { throw "Failed to ensure app_rt role/grants locally (exit $LASTEXITCODE)." }
        $lastGrantedSchemaSet = $schemaSet
    }

    $checkSql = @"
SELECT NOT EXISTS (
  SELECT 1
  FROM (VALUES $schemaValues) AS s(name)
  WHERE NOT EXISTS (
    SELECT 1
    FROM pg_namespace n
    WHERE n.nspname = s.name
  )
);
"@
    $checkResult = docker exec $PostgresContainer psql -U $DbUser -d $Database -t -A -c $checkSql 2>$null
    if (($checkResult | Select-Object -Last 1) -eq "t") { $schemaReady = $true; break }
    Start-Sleep -Seconds 3
}
if (-not $schemaReady) { throw "Timed out after $TimeoutSeconds s waiting for local service schemas in $PostgresContainer." }

docker exec $PostgresContainer psql -v ON_ERROR_STOP=1 -v app_rt_password="$AppRtPassword" -v owner=postgres -U $DbUser -d $Database -f /tmp/create-app-rt-role.sql
if ($LASTEXITCODE -ne 0) { throw "Failed to ensure app_rt role/grants locally (exit $LASTEXITCODE)." }
Write-Host "Local app_rt role and grants are ready." -ForegroundColor Green
