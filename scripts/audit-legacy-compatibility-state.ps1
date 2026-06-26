param(
    [string]$PostgresContainer = "custoking-postgres",
    [string]$Database = "postgres",
    [string]$DbUser = "postgres",
    [string]$OutputJson,
    [switch]$RowsOnlyJson,
    [switch]$FailOnNeedsBackfill,
    [switch]$FailOnPublicRows
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "legacy-compatibility-map.ps1")
$mappings = Get-LegacyCompatibilityMappings

function ConvertTo-SqlLiteral {
    param([string]$Value)
    return "'" + ($Value -replace "'", "''") + "'"
}

function Invoke-External {
    param(
        [string]$Description,
        [scriptblock]$Command
    )

    $output = & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE."
    }
    return $output
}

$values = ($mappings | ForEach-Object {
    "(" + (@(
        ConvertTo-SqlLiteral $_.Domain
        ConvertTo-SqlLiteral $_.PublicTable
        ConvertTo-SqlLiteral $_.TargetSchema
        ConvertTo-SqlLiteral $_.TargetTable
    ) -join ", ") + ")"
}) -join ",`n"

$sql = @"
CREATE TEMP TABLE ims_legacy_mapping (
    domain text NOT NULL,
    public_table text NOT NULL,
    target_schema text NOT NULL,
    target_table text NOT NULL
);

INSERT INTO ims_legacy_mapping(domain, public_table, target_schema, target_table)
VALUES
$values;

CREATE TEMP TABLE ims_legacy_report (
    domain text NOT NULL,
    public_table text NOT NULL,
    target_table text NOT NULL,
    public_rows bigint,
    target_rows bigint,
    status text NOT NULL
);

DO `$`$
DECLARE
    mapping record;
    public_reg regclass;
    target_reg regclass;
    public_count bigint;
    target_count bigint;
    table_status text;
BEGIN
    FOR mapping IN SELECT * FROM ims_legacy_mapping ORDER BY domain, public_table LOOP
        public_reg := to_regclass(format('%I.%I', 'public', mapping.public_table));
        target_reg := to_regclass(format('%I.%I', mapping.target_schema, mapping.target_table));
        public_count := NULL;
        target_count := NULL;

        IF public_reg IS NOT NULL THEN
            EXECUTE format('SELECT count(*) FROM %s', public_reg) INTO public_count;
        END IF;

        IF target_reg IS NOT NULL THEN
            EXECUTE format('SELECT count(*) FROM %s', target_reg) INTO target_count;
        END IF;

        IF public_reg IS NULL THEN
            table_status := 'NO_PUBLIC_TABLE';
        ELSIF target_reg IS NULL THEN
            table_status := 'NO_TARGET_TABLE';
        ELSIF public_count = 0 THEN
            table_status := 'PUBLIC_EMPTY';
        ELSIF target_count >= public_count THEN
            table_status := 'MIRRORED_OR_BACKFILLED';
        ELSE
            table_status := 'NEEDS_BACKFILL_REVIEW';
        END IF;

        INSERT INTO ims_legacy_report(domain, public_table, target_table, public_rows, target_rows, status)
        VALUES (
            mapping.domain,
            'public.' || mapping.public_table,
            mapping.target_schema || '.' || mapping.target_table,
            public_count,
            target_count,
            table_status
        );
    END LOOP;
END
`$`$;

COPY (
    SELECT domain, public_table, target_table, public_rows, target_rows, status
    FROM ims_legacy_report
    ORDER BY domain, public_table
) TO STDOUT WITH CSV HEADER;
"@

$tempFile = Join-Path ([System.IO.Path]::GetTempPath()) ("ims-legacy-compatibility-audit-{0}.sql" -f ([Guid]::NewGuid().ToString("N")))
$containerFile = "/tmp/ims-legacy-compatibility-audit.sql"

try {
    Set-Content -Path $tempFile -Value $sql -Encoding UTF8
    Invoke-External "Copy audit SQL into PostgreSQL container" {
        docker cp $tempFile "${PostgresContainer}:$containerFile"
    } | Out-Null
    $csv = Invoke-External "Run legacy compatibility audit SQL" {
        docker exec $PostgresContainer psql -q -v ON_ERROR_STOP=1 -U $DbUser -d $Database -f $containerFile
    }
    $rows = @($csv | Where-Object { $_ -and $_.Trim() } | ConvertFrom-Csv)

    Write-Host "domain`tpublic_table`ttarget_table`tpublic_rows`ttarget_rows`tstatus"
    foreach ($row in $rows) {
        Write-Host "$($row.domain)`t$($row.public_table)`t$($row.target_table)`t$($row.public_rows)`t$($row.target_rows)`t$($row.status)"
    }

    $needsBackfill = @($rows | Where-Object { $_.status -eq "NEEDS_BACKFILL_REVIEW" -or $_.status -eq "NO_TARGET_TABLE" })
    $publicRows = @($rows | Where-Object {
        $_.public_rows -and ([int64]$_.public_rows) -gt 0 -and $_.status -ne "NO_TARGET_TABLE"
    })

    if ($needsBackfill.Count -gt 0) {
        Write-Host ""
        Write-Host "Legacy compatibility audit warning: $($needsBackfill.Count) table(s) need backfill/schema review."
    }

    if ($publicRows.Count -gt 0) {
        Write-Host "Legacy compatibility audit note: $($publicRows.Count) public table(s) still contain rows."
    }

    $statusCounts = [ordered]@{}
    foreach ($group in ($rows | Group-Object -Property status | Sort-Object -Property Name)) {
        $statusCounts[$group.Name] = $group.Count
    }

    $summary = [ordered]@{
        generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
        postgresContainer = $PostgresContainer
        database = $Database
        dbUser = $DbUser
        totalMappedTables = $rows.Count
        publicTablesWithRows = $publicRows.Count
        needsBackfillReview = $needsBackfill.Count
        statusCounts = $statusCounts
    }

    if ($OutputJson) {
        if ($RowsOnlyJson) {
            $rows | ConvertTo-Json -Depth 3 | Set-Content -Path $OutputJson -Encoding UTF8
        } else {
            [ordered]@{
                summary = $summary
                rows = $rows
            } | ConvertTo-Json -Depth 5 | Set-Content -Path $OutputJson -Encoding UTF8
        }
    }

    if (($FailOnNeedsBackfill -and $needsBackfill.Count -gt 0) -or ($FailOnPublicRows -and $publicRows.Count -gt 0)) {
        exit 1
    }

    Write-Host "Legacy compatibility audit completed: $($rows.Count) mapped table(s), $($needsBackfill.Count) backfill/schema issue(s), $($publicRows.Count) public table(s) with rows."
} finally {
    Remove-Item -Path $tempFile -Force -ErrorAction SilentlyContinue
    docker exec $PostgresContainer rm -f $containerFile 2>$null | Out-Null
}
