param(
  [Parameter(Mandatory = $true)]
  [string]$EvidenceJson,

  [ValidateSet("dev", "prod")]
  [string]$Environment = "dev",

  [switch]$Apply,

  [int]$ExpectedCandidateCount = -1,

  [int]$ChunkSize = 75,

  [string]$ProjectId = "custoking",

  [string]$Region = "asia-south2",

  [string]$OutputPath = "artifacts/dob-repair/reconciliation-result.json"
)

$ErrorActionPreference = "Stop"
$GcloudCommand = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }

if (-not (Test-Path -LiteralPath $EvidenceJson)) {
  throw "DOB evidence file not found: $EvidenceJson"
}
if ($ChunkSize -lt 1 -or $ChunkSize -gt 2000) {
  throw "ChunkSize must be between 1 and 2000."
}
if ($Apply -and $ExpectedCandidateCount -lt 0) {
  throw "-Apply requires -ExpectedCandidateCount from a reviewed dry run."
}

$config = if ($Environment -eq "prod") {
  @{ Job = "ims-gateway-smoke-sql-prod"; Host = "10.92.0.5"; Database = "custoking_prod" }
} else {
  @{ Job = "ims-gateway-smoke-sql-dev"; Host = "10.92.0.4"; Database = "custoking_dev" }
}
$evidence = Get-Content -Raw -LiteralPath $EvidenceJson | ConvertFrom-Json
$records = @($evidence.records)
$workbooks = @($evidence.workbooks)
if ($records.Count -eq 0) {
  throw "DOB evidence contains no shifted-date records."
}
if ($workbooks.Count -eq 0) {
  throw "DOB evidence contains no source workbook metadata."
}
$workbookByBatch = @{}
foreach ($workbook in $workbooks) {
  $batchId = [string]$workbook.batchId
  $sha256 = ([string]$workbook.sha256).ToLowerInvariant()
  if (-not $batchId -or $sha256 -notmatch '^[0-9a-f]{64}$') {
    throw "DOB evidence contains invalid source workbook metadata."
  }
  if ($workbookByBatch.ContainsKey($batchId)) {
    throw "DOB evidence contains duplicate workbook metadata for batch $batchId."
  }
  $workbookByBatch[$batchId] = $sha256
}
foreach ($record in $records) {
  if (-not $workbookByBatch.ContainsKey([string]$record.batchId)) {
    throw "DOB evidence record references batch $($record.batchId) without source workbook metadata."
  }
}

function ConvertTo-SqlLiteral([object]$Value) {
  if ($null -eq $Value) { return "NULL" }
  return "'" + ([string]$Value).Replace("'", "''") + "'"
}

function Invoke-RepairSql([string]$Sql, [string]$MarkerPrefix) {
  $marker = "$MarkerPrefix-$(([guid]::NewGuid()).ToString('N').Substring(0, 10))"
  $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Sql))
  $shellScript = "printf '%s' '$encoded' | base64 -d > /tmp/dob-repair.sql && psql -q -t -A -v ON_ERROR_STOP=1 -h $($config.Host) -p 5432 -U appuser -d $($config.Database) -f /tmp/dob-repair.sql | sed 's/^/$marker|/'"
  $flagsPath = [IO.Path]::GetTempFileName()
  try {
    [ordered]@{
      "--project" = $ProjectId
      "--region" = $Region
      "--args" = @("-c", $shellScript)
      "--wait" = $null
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $flagsPath
    & $GcloudCommand run jobs execute $config.Job "--flags-file=$flagsPath" | Out-Null
    if ($LASTEXITCODE -ne 0) {
      throw "Cloud SQL repair job execution failed."
    }
  } finally {
    Remove-Item -LiteralPath $flagsPath -Force -ErrorAction SilentlyContinue
  }
  Start-Sleep -Seconds 2
  $filter = "resource.type=`"cloud_run_job`" AND resource.labels.job_name=`"$($config.Job)`" AND textPayload:`"$marker`""
  for ($attempt = 1; $attempt -le 12; $attempt++) {
    $lines = @(& $GcloudCommand logging read $filter `
      "--project=$ProjectId" `
      --freshness=30m `
      --order=asc `
      --limit=500 `
      --format="value(textPayload)")
    $payloads = @($lines | Where-Object { $_ -like "$marker|*" } | ForEach-Object { $_.Substring("$marker|".Length) })
    if ($payloads.Count -gt 0) { return $payloads }
    Start-Sleep -Seconds 2
  }
  throw "Cloud SQL repair job completed without marked output."
}

function New-EvidenceValues([object[]]$Chunk) {
  return ($Chunk | ForEach-Object {
    $sourceSha256 = $workbookByBatch[[string]$_.batchId]
    "($(ConvertTo-SqlLiteral $_.batchId), $([int]$_.rowNumber), $(ConvertTo-SqlLiteral $_.admissionNo), $(ConvertTo-SqlLiteral $_.intendedDob)::date, $(ConvertTo-SqlLiteral $_.observedBuggyDob)::date, $(ConvertTo-SqlLiteral $sourceSha256))"
  }) -join ",`n"
}

function New-MatchesCte([object[]]$Chunk) {
  $values = New-EvidenceValues $Chunk
  return @"
WITH evidence(batch_id, workbook_row_no, admission_no, intended_dob, observed_dob, source_sha256) AS (
  VALUES
$values
), matches AS (
  SELECT e.batch_id, e.workbook_row_no, e.admission_no, e.intended_dob, e.observed_dob,
         ir.id AS import_row_id, ir.school_id, s.id AS student_id, s.dob AS current_dob,
         NULLIF(ir.normalized_json, '')::jsonb ->> 'dateOfBirth' AS normalized_dob,
         sch.name AS school_name,
         md5((to_jsonb(s) - 'dob')::text) AS other_columns_hash
  FROM evidence e
  JOIN student.import_batches b
    ON b.id = e.batch_id
   AND b.status = 'DONE'
   AND lower(b.original_file_sha256) = e.source_sha256
  JOIN student.import_rows ir
    ON ir.batch_id = e.batch_id
   -- Old batches numbered the first data row as 1; newer batches retain workbook row numbers.
   AND ir.row_no = e.workbook_row_no - CASE
     WHEN EXISTS (
       SELECT 1 FROM student.import_rows convention
       WHERE convention.batch_id = e.batch_id AND convention.row_no = 1
     ) THEN 1 ELSE 0 END
   AND ir.status = 'Imported'
   AND ir.applied_student_id IS NOT NULL
  JOIN student.students s
    ON s.id = ir.applied_student_id
   AND s.school_id = ir.school_id
   AND s.school_id = b.school_id
  JOIN tenant_school.schools sch ON sch.id = s.school_id
)
"@
}

$chunks = @()
for ($offset = 0; $offset -lt $records.Count; $offset += $ChunkSize) {
  $last = [Math]::Min($offset + $ChunkSize - 1, $records.Count - 1)
  $chunks += ,@($records[$offset..$last])
}

$batchResults = @{}
$totalMatched = 0
$totalEligible = 0
$totalAlreadyIntended = 0
$totalOther = 0
$chunkEligibleCounts = @()
foreach ($chunk in $chunks) {
  $chunkEligible = 0
  $cte = New-MatchesCte $chunk
  $sql = $cte + @"
SELECT json_build_object(
  'batchId', batch_id,
  'schoolId', school_id,
  'schoolName', school_name,
  'matched', count(*),
  'eligible', count(*) FILTER (WHERE normalized_dob = observed_dob::text AND current_dob = observed_dob AND intended_dob = observed_dob + 1),
  'alreadyIntended', count(*) FILTER (WHERE current_dob = intended_dob),
  'other', count(*) FILTER (WHERE NOT (normalized_dob = observed_dob::text AND current_dob = observed_dob AND intended_dob = observed_dob + 1) AND current_dob IS DISTINCT FROM intended_dob)
)::text
FROM matches
GROUP BY batch_id, school_id, school_name
UNION ALL
SELECT json_build_object(
  'batchId', NULL,
  'schoolId', NULL,
  'schoolName', NULL,
  'matched', 0,
  'eligible', 0,
  'alreadyIntended', 0,
  'other', 0
)::text
WHERE NOT EXISTS (SELECT 1 FROM matches);
"@
  foreach ($payload in @(Invoke-RepairSql $sql "DOB-DRYRUN")) {
    $item = $payload | ConvertFrom-Json
    $key = [string]$item.batchId
    if ($key) {
      if (-not $batchResults.ContainsKey($key)) {
        $batchResults[$key] = [ordered]@{ batchId = $key; schoolId = [long]$item.schoolId; schoolName = [string]$item.schoolName; matched = 0; eligible = 0; alreadyIntended = 0; other = 0 }
      }
      foreach ($field in @("matched", "eligible", "alreadyIntended", "other")) {
        $batchResults[$key][$field] += [int]$item.$field
      }
    }
    $totalMatched += [int]$item.matched
    $totalEligible += [int]$item.eligible
    $chunkEligible += [int]$item.eligible
    $totalAlreadyIntended += [int]$item.alreadyIntended
    $totalOther += [int]$item.other
  }
  $chunkEligibleCounts += $chunkEligible
}

$unmatched = $records.Count - $totalMatched

$totalUpdated = 0
if ($Apply) {
  if ($totalEligible -ne $ExpectedCandidateCount) {
    throw "Candidate count changed: expected $ExpectedCandidateCount but dry run found $totalEligible. Nothing was updated."
  }
  for ($chunkIndex = 0; $chunkIndex -lt $chunks.Count; $chunkIndex++) {
    $chunk = $chunks[$chunkIndex]
    $cte = New-MatchesCte $chunk
    $sql = "BEGIN;`n" + $cte + @"
, eligible AS (
  SELECT * FROM matches
  WHERE normalized_dob = observed_dob::text
    AND current_dob = observed_dob
    AND intended_dob = observed_dob + 1
), updated AS (
  UPDATE student.students s
  SET dob = e.intended_dob
  FROM eligible e
  WHERE s.id = e.student_id
    AND s.dob = e.current_dob
  RETURNING s.id, s.import_batch_id, md5((to_jsonb(s) - 'dob')::text) AS other_columns_hash
)
SELECT json_build_object(
  'updated', count(*),
  'otherColumnsPreserved', COALESCE(bool_and(u.other_columns_hash = e.other_columns_hash), true)
)::text
FROM updated u
JOIN eligible e ON e.student_id = u.id;
COMMIT;
"@
    $payload = @(Invoke-RepairSql $sql "DOB-APPLY") | Select-Object -First 1
    $applied = $payload | ConvertFrom-Json
    if (-not [bool]$applied.otherColumnsPreserved) {
      throw "A non-DOB student column changed during repair."
    }
    if ([int]$applied.updated -ne $chunkEligibleCounts[$chunkIndex]) {
      throw "Candidate count changed in chunk $($chunkIndex + 1): expected $($chunkEligibleCounts[$chunkIndex]) but updated $($applied.updated)."
    }
    $totalUpdated += [int]$applied.updated
  }
}

$result = [ordered]@{
  schemaVersion = 1
  environment = $Environment
  mode = if ($Apply) { "apply" } else { "dry-run" }
  generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
  evidenceRows = $records.Count
  matched = $totalMatched
  unmatched = $unmatched
  eligible = $totalEligible
  alreadyIntended = $totalAlreadyIntended
  other = $totalOther
  updated = $totalUpdated
  batches = @($batchResults.Values | Sort-Object schoolId, batchId)
}
$directory = Split-Path -Parent $OutputPath
if ($directory) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $OutputPath
$result | ConvertTo-Json -Depth 8
