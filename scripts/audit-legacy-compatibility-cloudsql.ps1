param(
    [string]$Project = "custoking-ims",
    [string]$Region = "asia-south2",
    [string]$HostAddress = "10.116.0.3",
    [int]$Port = 5432,
    [string]$Database = "custoking_ims_v1",
    [string]$DbUser = "ims_app",
    [string]$PasswordSecret = "ims-app-password",
    [string]$Network = "default",
    [string]$Subnet = "default",
    [string]$OutputJson = "legacy-compatibility-audit.json",
    [string]$Gcloud = "C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "legacy-compatibility-map.ps1")

function ConvertTo-SqlLiteral {
    param([string]$Value)
    "'" + ($Value -replace "'", "''") + "'"
}

$mappings = Get-LegacyCompatibilityMappings
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

SELECT domain || '|' || public_table || '|' || target_table || '|' ||
       coalesce(public_rows::text, '') || '|' || coalesce(target_rows::text, '') || '|' || status
FROM ims_legacy_report
ORDER BY domain, public_table;
"@

$job = "ims-legacy-audit-" + (Get-Date -Format "yyyyMMddHHmmss")
$encodedSql = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($sql))
$script = "printf '%s' '$encodedSql' | base64 -d > /tmp/audit.sql && psql -q -t -A -v ON_ERROR_STOP=1 -h $HostAddress -p $Port -U $DbUser -d $Database -f /tmp/audit.sql | sed 's/^/IMS_AUDIT_ROW|/'"

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
    $logLines = & $Gcloud logging read $filter `
        --project=$Project `
        --freshness=30m `
        --order=asc `
        --limit=300 `
        --format="value(textPayload)"

    $rows = @()
    foreach ($line in $logLines) {
        if ($line -like "IMS_AUDIT_ROW|*") {
            $parts = $line.Substring("IMS_AUDIT_ROW|".Length).Split("|")
            if ($parts.Count -eq 6) {
                $rows += [pscustomobject]@{
                    domain = $parts[0]
                    public_table = $parts[1]
                    target_table = $parts[2]
                    public_rows = if ($parts[3] -eq "") { $null } else { [int64]$parts[3] }
                    target_rows = if ($parts[4] -eq "") { $null } else { [int64]$parts[4] }
                    status = $parts[5]
                }
            }
        }
    }

    if ($rows.Count -eq 0) {
        throw "Cloud SQL legacy audit returned no rows."
    }

    $needsBackfill = @($rows | Where-Object { $_.status -eq "NEEDS_BACKFILL_REVIEW" -or $_.status -eq "NO_TARGET_TABLE" })
    $publicRows = @($rows | Where-Object {
        $null -ne $_.public_rows -and $_.public_rows -gt 0 -and $_.status -ne "NO_TARGET_TABLE"
    })

    $statusCounts = [ordered]@{}
    foreach ($group in ($rows | Group-Object -Property status | Sort-Object -Property Name)) {
        $statusCounts[$group.Name] = $group.Count
    }

    [ordered]@{
        summary = [ordered]@{
            generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
            source = "cloud-run-job"
            project = $Project
            region = $Region
            cloudSqlHost = $HostAddress
            database = $Database
            dbUser = $DbUser
            totalMappedTables = $rows.Count
            publicTablesWithRows = $publicRows.Count
            needsBackfillReview = $needsBackfill.Count
            statusCounts = $statusCounts
        }
        rows = $rows
    } | ConvertTo-Json -Depth 6 | Set-Content -Path $OutputJson -Encoding UTF8

    Write-Host "Legacy Cloud SQL compatibility audit completed: $($rows.Count) mapped table(s), $($needsBackfill.Count) backfill/schema issue(s), $($publicRows.Count) public table(s) with rows."
} finally {
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Gcloud run jobs delete $job --project=$Project --region=$Region --quiet *> $null
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}
