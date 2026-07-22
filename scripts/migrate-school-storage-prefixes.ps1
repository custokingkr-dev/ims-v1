[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Bucket,

    [string]$PostgresContainer = "",
    [string]$HostAddress = "localhost",
    [int]$Port = 5432,
    [string]$Database = "postgres",
    [string]$DbUser = "postgres",
    [string]$DbPassword = "",

    [switch]$Apply,
    [switch]$DeleteLegacyObjects
)

$ErrorActionPreference = "Stop"

function Sql-Literal {
    param([string]$Value)
    if ($null -eq $Value) { return "NULL" }
    return "'" + $Value.Replace("'", "''") + "'"
}

function Invoke-Psql {
    param([string]$Sql)

    if (-not [string]::IsNullOrWhiteSpace($PostgresContainer)) {
        $args = @(
            "exec", $PostgresContainer,
            "psql", "-v", "ON_ERROR_STOP=1",
            "-U", $DbUser,
            "-d", $Database,
            "-t", "-A", "-F", "`t",
            "-c", $Sql
        )
        return & docker @args
    }

    $previousPassword = $env:PGPASSWORD
    if (-not [string]::IsNullOrWhiteSpace($DbPassword)) {
        $env:PGPASSWORD = $DbPassword
    }
    try {
        $args = @(
            "-v", "ON_ERROR_STOP=1",
            "-h", $HostAddress,
            "-p", [string]$Port,
            "-U", $DbUser,
            "-d", $Database,
            "-t", "-A", "-F", "`t",
            "-c", $Sql
        )
        return & psql @args
    } finally {
        $env:PGPASSWORD = $previousPassword
    }
}

function Resolve-Gcloud {
    $cmd = Get-Command "gcloud.cmd" -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $cmd = Get-Command "gcloud" -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "gcloud CLI was not found. Install/authenticate gcloud or run without -Apply for DB-only dry-run output."
}

function Invoke-GcloudStorage {
    param([string[]]$Args)
    $gcloud = Resolve-Gcloud
    & $gcloud @Args
    if ($LASTEXITCODE -ne 0) {
        throw "gcloud storage command failed with exit code $LASTEXITCODE"
    }
}

$candidateSql = @"
SELECT 'student' AS kind,
       st.id::text AS row_id,
       st.photo_url AS old_key,
       'schools/' || sch.school_uid::text || '/students/' || st.id::text || '/photos/' ||
           regexp_replace(st.photo_url, '^.*/', '') AS new_key
FROM student.students st
JOIN tenant_school.schools sch ON sch.id = st.school_id
WHERE st.photo_url LIKE 'students/%'
  AND st.photo_url NOT LIKE 'schools/%'
UNION ALL
SELECT 'import' AS kind,
       ib.id::text AS row_id,
       ib.original_file_object_path AS old_key,
       'schools/' || sch.school_uid::text || '/student-imports/' || ib.id::text || '/' ||
           regexp_replace(ib.original_file_object_path, '^.*/', '') AS new_key
FROM student.import_batches ib
JOIN tenant_school.schools sch ON sch.id = ib.school_id
WHERE ib.original_file_object_path LIKE 'student-imports/%'
  AND ib.original_file_object_path NOT LIKE 'schools/%'
ORDER BY kind, row_id
"@

$rows = @(Invoke-Psql $candidateSql | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
Write-Host "Found $($rows.Count) legacy storage object reference(s)."
if (-not $Apply) {
    Write-Host "Dry-run only. Re-run with -Apply to copy objects and update DB paths."
}

$updated = 0
foreach ($line in $rows) {
    $parts = $line -split "`t", 4
    if ($parts.Count -ne 4) {
        throw "Unexpected psql row format: $line"
    }
    $kind = $parts[0]
    $rowId = $parts[1]
    $oldKey = $parts[2]
    $newKey = $parts[3]

    Write-Host "$kind $rowId"
    Write-Host "  $oldKey"
    Write-Host "  -> $newKey"

    if (-not $Apply) {
        continue
    }

    Invoke-GcloudStorage @("storage", "cp", "gs://$Bucket/$oldKey", "gs://$Bucket/$newKey")

    if ($kind -eq "student") {
        $updateSql = "UPDATE student.students SET photo_url = $(Sql-Literal $newKey), updated_at = now() WHERE id = $rowId AND photo_url = $(Sql-Literal $oldKey);"
    } elseif ($kind -eq "import") {
        $updateSql = "UPDATE student.import_batches SET original_file_object_path = $(Sql-Literal $newKey) WHERE id = $(Sql-Literal $rowId) AND original_file_object_path = $(Sql-Literal $oldKey);"
    } else {
        throw "Unknown legacy storage reference kind: $kind"
    }
    Invoke-Psql $updateSql | Out-Null
    $updated++

    if ($DeleteLegacyObjects) {
        Invoke-GcloudStorage @("storage", "rm", "gs://$Bucket/$oldKey")
    }
}

Write-Host "Updated $updated legacy storage object reference(s)."
if ($Apply -and -not $DeleteLegacyObjects) {
    Write-Host "Legacy objects were copied, not deleted. Re-run with -DeleteLegacyObjects only after verification if cleanup is required."
}
