param(
    [string]$OutputSql = "legacy-public-retirement-active-compact.sql",
    [string]$ArchiveSchema = "legacy_public_archive"
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "legacy-compatibility-map.ps1")

function ConvertTo-SqlLiteral {
    param([string]$Value)
    return "'" + ($Value -replace "'", "''") + "'"
}

$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd_HHmmss")
$values = ((Get-LegacyCompatibilityMappings | Sort-Object Domain, PublicTable | ForEach-Object {
    "(" + (@(
        ConvertTo-SqlLiteral $_.Domain
        ConvertTo-SqlLiteral $_.PublicTable
    ) -join ",") + ")"
}) -join ",")

$archiveSchemaLiteral = ConvertTo-SqlLiteral $ArchiveSchema

$sql = @"
BEGIN;
CREATE SCHEMA IF NOT EXISTS "$ArchiveSchema";
CREATE TEMP TABLE ims_legacy_retire(domain text, public_table text);
INSERT INTO ims_legacy_retire(domain, public_table) VALUES $values;
DO `$`$
DECLARE
    m record;
    archive_name text;
    drop_targets text := '';
BEGIN
    FOR m IN SELECT * FROM ims_legacy_retire LOOP
        IF to_regclass(format('%I.%I', 'public', m.public_table)) IS NOT NULL THEN
            archive_name := regexp_replace(m.domain || '_' || m.public_table || '_$stamp', '[^a-zA-Z0-9_]', '_', 'g');
            EXECUTE format('CREATE TABLE %I.%I AS TABLE %I.%I WITH DATA', $archiveSchemaLiteral, archive_name, 'public', m.public_table);
            drop_targets := drop_targets || CASE WHEN drop_targets = '' THEN '' ELSE ', ' END ||
                format('%I.%I', 'public', m.public_table);
        END IF;
    END LOOP;

    IF drop_targets <> '' THEN
        EXECUTE 'DROP TABLE ' || drop_targets || ' RESTRICT';
    END IF;
END
`$`$;
COMMIT;
"@

Set-Content -Path $OutputSql -Value $sql -Encoding UTF8
Write-Host "Generated compact legacy retirement SQL: $OutputSql"
Write-Host "Length: $($sql.Length)"
