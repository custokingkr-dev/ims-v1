$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$containerName = 'ims-receipt-preflight-' + [guid]::NewGuid().ToString('N')
$preflightSql = Get-Content -LiteralPath (Join-Path $repoRoot 'scripts/sql/fee-payment-integrity-preflight.sql') -Raw
$migrationSql = Get-Content -LiteralPath (Join-Path $repoRoot 'services/school-core-service/src/main/resources/db/migration/fee/V10__payment_integrity.sql') -Raw

function Invoke-TestSql([string]$Sql) {
    $result = $Sql | docker exec -i $containerName psql -X -q -U postgres -v ON_ERROR_STOP=1 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Local PostgreSQL check failed: $result" }
    return $result
}
function Read-Evidence([string]$Prefix = '') {
    $lines = Invoke-TestSql ($Prefix + "`n" + $preflightSql)
    $json = @($lines | Where-Object { [string]$_ -match '^\{' })
    if ($json.Count -ne 1) { throw 'Expected one aggregate JSON evidence record' }
    return $json[0] | ConvertFrom-Json
}
function Assert-True([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }

try {
    docker run --detach --name $containerName --network none -e POSTGRES_HOST_AUTH_METHOD=trust postgres:16 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start isolated local PostgreSQL test container' }
    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        docker exec $containerName pg_isready -U postgres *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) { throw 'Local PostgreSQL did not become ready' }
    Invoke-TestSql @'
CREATE SCHEMA fee;
CREATE ROLE app_rt;
CREATE TABLE fee.payment_records (id text PRIMARY KEY, school_id bigint, receipt_number text);
INSERT INTO fee.payment_records VALUES ('a', 7, 'RCPT-legacy'), ('b', 8, 'RCPT-legacy'), ('c', 7, NULL), ('d', 8, 'RCPT-V2-12');
'@ | Out-Null
    $before = Read-Evidence
    Assert-True ($before.readOnly -and $before.completeDatabaseVisibility) 'Owner evidence must be complete and read-only'
    Assert-True ($before.visiblePaymentCount -eq 4 -and $before.duplicateReceiptGroups -eq 1 -and $before.paymentsInDuplicateReceiptGroups -eq 2) 'Pre-migration collision counts differ'
    Assert-True ($before.duplicateGroupsAcrossSchools -eq 1 -and -not $before.v10Schema.columns_present) 'Pre-migration schema or school-collision evidence differs'
    Invoke-TestSql $migrationSql | Out-Null
    $after = Read-Evidence
    Assert-True ($after.visiblePaymentCount -eq 4 -and $after.duplicateGroupsFullyMarkedLegacy -eq 1) 'Migration must preserve and mark both colliding records'
    Assert-True ($after.v10Schema.columns_present -and $after.v10Schema.sequence_present -and $after.v10Schema.receipt_index_valid -and $after.v10Schema.idempotency_index_valid) 'V10 schema should be ready'
    Assert-True ($after.appRtCanUseReceiptSequence -and $after.duplicateSchoolIdempotencyKeys -eq 0) 'Sequence permission and idempotency evidence differs'
    Invoke-TestSql @'
GRANT USAGE ON SCHEMA fee TO app_rt;
GRANT SELECT ON fee.payment_records TO app_rt;
ALTER TABLE fee.payment_records ENABLE ROW LEVEL SECURITY;
CREATE POLICY hidden_in_this_session ON fee.payment_records USING (false);
'@ | Out-Null
    $scoped = Read-Evidence 'SET ROLE app_rt;'
    Assert-True (-not $scoped.completeDatabaseVisibility -and $scoped.visiblePaymentCount -eq 0 -and $scoped.interpretation.StartsWith('INCOMPLETE:')) 'RLS-hidden rows must never be reported as database-wide zero collisions'
    $unchanged = Read-Evidence
    Assert-True ($unchanged.visiblePaymentCount -eq 4 -and $unchanged.duplicateReceiptGroups -eq 1) 'Read-only preflight changed the fixture'
    Invoke-TestSql @'
CREATE ROLE receipt_owner;
CREATE ROLE inherited_reader INHERIT;
CREATE ROLE noninherited_reader NOINHERIT;
GRANT receipt_owner TO inherited_reader, noninherited_reader;
GRANT USAGE ON SCHEMA fee TO receipt_owner, inherited_reader, noninherited_reader;
GRANT SELECT ON fee.payment_records TO noninherited_reader;
ALTER TABLE fee.payment_records OWNER TO receipt_owner;
'@ | Out-Null
    $inherited = Read-Evidence 'SET ROLE inherited_reader;'
    Assert-True ($inherited.completeDatabaseVisibility -and $inherited.visiblePaymentCount -eq 4) 'Inherited owner privileges must count as complete scope without forced RLS'
    $noninherited = Read-Evidence 'SET ROLE noninherited_reader;'
    Assert-True (-not $noninherited.completeDatabaseVisibility -and $noninherited.visiblePaymentCount -eq 0) 'Membership without inherited owner privileges must not bypass visibility guards'
    Invoke-TestSql 'ALTER TABLE fee.payment_records FORCE ROW LEVEL SECURITY;' | Out-Null
    $forcedOwner = Read-Evidence 'SET ROLE inherited_reader;'
    Assert-True (-not $forcedOwner.completeDatabaseVisibility -and $forcedOwner.visiblePaymentCount -eq 0) 'Forced RLS must still restrict an inherited owner'
    Write-Output 'PASS: receipt preflight before/after V10, preserved collisions, sequence grants, RLS-incomplete visibility, and no data mutation.'
} finally {
    # This exact name was generated above for this test; never target other containers.
    docker rm --force $containerName *> $null
}
