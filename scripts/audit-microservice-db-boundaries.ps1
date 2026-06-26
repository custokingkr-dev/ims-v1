param(
    [string]$PostgresContainer = "custoking-postgres",
    [string]$Database = "postgres",
    [string]$User = "postgres"
)

$ErrorActionPreference = "Stop"

$serviceSchemas = @(
    "tenant_school",
    "identity",
    "student",
    "attendance",
    "fee",
    "catalog",
    "workflow",
    "firefighting",
    "notification",
    "reporting",
    "billing",
    "audit"
)

$schemaList = ($serviceSchemas | ForEach-Object { "'$_'" }) -join ","

$crossSchemaSql = @"
SELECT src_ns.nspname || '.' || src.relname || ' -> ' || tgt_ns.nspname || '.' || tgt.relname || ' (' || con.conname || ')'
FROM pg_constraint con
JOIN pg_class src ON src.oid = con.conrelid
JOIN pg_namespace src_ns ON src_ns.oid = src.relnamespace
JOIN pg_class tgt ON tgt.oid = con.confrelid
JOIN pg_namespace tgt_ns ON tgt_ns.oid = tgt.relnamespace
WHERE con.contype = 'f'
  AND src_ns.nspname IN ($schemaList)
  AND tgt_ns.nspname <> src_ns.nspname
ORDER BY 1;
"@

$publicFkSql = @"
SELECT src_ns.nspname || '.' || src.relname || ' -> public.' || tgt.relname || ' (' || con.conname || ')'
FROM pg_constraint con
JOIN pg_class src ON src.oid = con.conrelid
JOIN pg_namespace src_ns ON src_ns.oid = src.relnamespace
JOIN pg_class tgt ON tgt.oid = con.confrelid
JOIN pg_namespace tgt_ns ON tgt_ns.oid = tgt.relnamespace
WHERE con.contype = 'f'
  AND src_ns.nspname IN ($schemaList)
  AND tgt_ns.nspname = 'public'
ORDER BY 1;
"@

function Invoke-Psql {
    param([string]$Sql)
    $output = & docker exec $PostgresContainer psql -v ON_ERROR_STOP=1 -U $User -d $Database -At -c $Sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to query PostgreSQL container '$PostgresContainer': $($output -join [Environment]::NewLine)"
    }
    $output
}

$container = & docker ps --filter "name=^/$PostgresContainer$" --format "{{.Names}}" 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Docker is not available for DB boundary audit: $($container -join [Environment]::NewLine)"
}
if (($container | Where-Object { $_ -eq $PostgresContainer }).Count -eq 0) {
    throw "PostgreSQL container '$PostgresContainer' is not running. Start the split stack or pass -PostgresContainer."
}

$crossSchema = @(Invoke-Psql $crossSchemaSql | Where-Object { $_ -and $_.Trim() })
$publicFks = @(Invoke-Psql $publicFkSql | Where-Object { $_ -and $_.Trim() })

if ($crossSchema.Count -gt 0) {
    Write-Host "Cross-schema foreign keys found:"
    $crossSchema | ForEach-Object { Write-Host "  $_" }
}

if ($publicFks.Count -gt 0) {
    Write-Host "Public-schema foreign keys found:"
    $publicFks | ForEach-Object { Write-Host "  $_" }
}

if ($crossSchema.Count -gt 0 -or $publicFks.Count -gt 0) {
    exit 1
}

Write-Host "Microservice DB boundary audit passed: no cross-schema or public-schema FKs in extracted service schemas."
