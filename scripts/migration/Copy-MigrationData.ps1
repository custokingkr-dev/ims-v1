<#
.SYNOPSIS
  Moves database and student-photo data from the source project into a destination Custoking project.

.DESCRIPTION
  Ordering matters and is not obvious:

    1. app_rt must EXIST on the destination BEFORE the import. The dump carries GRANT statements and RLS
       policies that name app_rt; importing without the role present fails partway through, leaving a
       half-loaded database.
    2. Import the dump.
    3. Re-run create-app-rt-role.sql to apply schema-level grants, which can only be granted once the
       schemas the dump creates actually exist.

  The export lands in a bucket in the DESTINATION project, never the source, so the last copy of the data
  survives the source project's deletion.

  Both Cloud SQL instances have their own generated service accounts. Each needs explicit access to the
  transfer bucket: the source instance to write the export, the destination instance to read it.

.EXAMPLE
  ./Copy-MigrationData.ps1 -Environment dev -Stage export,import,photos
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][ValidateSet('dev', 'prod')][string]$Environment,
  [string]$SourceProject = 'custoking',
  [string]$TargetProject,
  [string]$Region = 'asia-south2',
  [string]$TransferBucket,
  [string]$SourcePhotoBucket,
  [string]$TargetPhotoBucket,
  [ValidateSet('bucket', 'export', 'import', 'photos', 'all')][string[]]$Stage = @('all'),
  [string]$ExportObject
)

$ErrorActionPreference = 'Stop'
if (-not $TargetProject) { $TargetProject = "custoking-$Environment" }
if (-not $TransferBucket) { $TransferBucket = "custoking-$Environment-migration" }
if (-not $SourcePhotoBucket) { $SourcePhotoBucket = "custoking-student-photos-$Environment" }
if (-not $TargetPhotoBucket) { $TargetPhotoBucket = "custoking-$Environment-student-photos" }

$SourceInstance = "custoking-db-$Environment"
$TargetInstance = "custoking-db-$Environment"
$Database = "custoking_$Environment"

function Invoke-Native {
  param([string[]]$Arguments, [switch]$AllowFailure, [string]$Executable = 'gcloud')
  $previous = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $output = & $Executable @Arguments
    $code = $LASTEXITCODE
  }
  finally { $ErrorActionPreference = $previous }
  if ($code -ne 0 -and -not $AllowFailure) { throw "$Executable $($Arguments -join ' ') exited $code" }
  return ($output | Out-String).Trim()
}

function Test-Stage([string]$Name) { return ($Stage -contains 'all') -or ($Stage -contains $Name) }

# Full object manifest with checksums. 'gcloud storage ls' output is not reliably parseable for this, so
# read the JSON API directly and page through it.
function Get-BucketManifest {
  param([string]$Bucket, [string]$Project)
  $token = (Invoke-Native @('auth', 'print-access-token')).Trim()
  $results = New-Object System.Collections.Generic.List[object]
  $pageToken = $null
  do {
    $uri = "https://storage.googleapis.com/storage/v1/b/$Bucket/o?maxResults=1000&fields=items(name,size,crc32c),nextPageToken"
    if ($pageToken) { $uri += "&pageToken=$pageToken" }
    $response = Invoke-RestMethod -Uri $uri -Headers @{
      Authorization        = "Bearer $token"
      'x-goog-user-project' = $Project
    }
    foreach ($item in $response.items) {
      $results.Add([pscustomobject]@{ Name = $item.name; Crc = $item.crc32c; Size = $item.size })
    }
    $pageToken = $response.nextPageToken
  } while ($pageToken)
  return $results
}
function Write-Step([string]$Text) { Write-Host "`n=== $Text ===" -ForegroundColor Cyan }

# A Cloud SQL instance reports RUNNABLE while an UPDATE operation is still finishing internally, and any
# export or import issued in that window fails with HTTP 409 "another operation was already in progress".
# Starting a stopped instance leaves exactly such an operation behind, so always drain before acting.
function Wait-SqlOperations {
  param([string]$Project, [string]$Instance, [int]$TimeoutSeconds = 900)
  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    $pending = Invoke-Native @('sql', 'operations', 'list', "--instance=$Instance", "--project=$Project",
      '--filter=status!=DONE', '--format=value(name)') -AllowFailure
    if (-not $pending) { return }
    Write-Host '  waiting for an in-flight Cloud SQL operation to finish...' -ForegroundColor DarkYellow
    Start-Sleep -Seconds 15
  }
  throw "Cloud SQL operations on $Project/$Instance did not drain within $TimeoutSeconds seconds."
}

$sourceSa = Invoke-Native @('sql', 'instances', 'describe', $SourceInstance, "--project=$SourceProject", '--format=value(serviceAccountEmailAddress)')
$targetSa = Invoke-Native @('sql', 'instances', 'describe', $TargetInstance, "--project=$TargetProject", '--format=value(serviceAccountEmailAddress)')
Write-Host "source instance SA : $sourceSa"
Write-Host "target instance SA : $targetSa"

# ---------------------------------------------------------------- transfer bucket
if (Test-Stage 'bucket') {
  Write-Step "Transfer bucket gs://$TransferBucket (in $TargetProject)"
  $exists = $true
  try { Invoke-Native @('storage', 'buckets', 'describe', "gs://$TransferBucket", "--project=$TargetProject") | Out-Null }
  catch { $exists = $false }
  if (-not $exists) {
    Invoke-Native @('storage', 'buckets', 'create', "gs://$TransferBucket", "--location=$Region",
      '--uniform-bucket-level-access', '--public-access-prevention', "--project=$TargetProject") | Out-Null
    Write-Host '  created'
  }
  else { Write-Host '  already present' }

  # Cross-project (and cross-organization) grants. The destination organization does not enforce
  # constraints/iam.allowedPolicyMemberDomains, so granting the source project's instance identity here
  # is permitted.
  Invoke-Native @('storage', 'buckets', 'add-iam-policy-binding', "gs://$TransferBucket",
    "--member=serviceAccount:$sourceSa", '--role=roles/storage.objectAdmin', "--project=$TargetProject") | Out-Null
  Invoke-Native @('storage', 'buckets', 'add-iam-policy-binding', "gs://$TransferBucket",
    "--member=serviceAccount:$targetSa", '--role=roles/storage.objectAdmin', "--project=$TargetProject") | Out-Null
  Write-Host '  both instance service accounts granted objectAdmin'
}

$stamp = Get-Date -Format 'yyyyMMddTHHmmssZ'
if (-not $ExportObject) { $ExportObject = "gs://$TransferBucket/$Environment/$Database-$stamp.sql.gz" }

# ---------------------------------------------------------------- export
if (Test-Stage 'export') {
  Write-Step "Export $SourceProject/$SourceInstance/$Database"
  $state = Invoke-Native @('sql', 'instances', 'describe', $SourceInstance, "--project=$SourceProject", '--format=value(state)')
  if ($state -ne 'RUNNABLE') { throw "Source instance is '$state'. It must be RUNNABLE to export; dev is stopped by default." }
  Wait-SqlOperations -Project $SourceProject -Instance $SourceInstance
  # No --offload: it spins up a temporary instance to take the dump, which is worth it for a large or
  # busy database but is pure added cost and latency for one this size.
  Invoke-Native @('sql', 'export', 'sql', $SourceInstance, $ExportObject,
    "--database=$Database", "--project=$SourceProject", '--quiet') | Out-Null
  Write-Host "  exported to $ExportObject"
  $size = Invoke-Native @('storage', 'ls', '-l', $ExportObject, "--project=$TargetProject")
  Write-Host "  $size"

  # Cloud SQL import runs as a role that is neither appuser nor superuser, so it cannot execute
  #   ALTER DEFAULT PRIVILEGES FOR ROLE appuser IN SCHEMA <s> GRANT ... TO app_rt;
  # It fails with "permission denied to change default privileges", and because the import is wrapped in
  # a single transaction the ENTIRE load rolls back — the database is left empty, not half-loaded.
  # Stripping these is lossless: scripts/create-app-rt-role.sql regenerates exactly the same statements
  # when it is re-run after the import.
  Write-Step 'Sanitize the dump for Cloud SQL import'
  $work = Join-Path ([System.IO.Path]::GetTempPath()) "ck-dump-$Environment-$stamp"
  New-Item -ItemType Directory -Force -Path $work | Out-Null
  try {
    $localGz = Join-Path $work 'dump.sql.gz'
    Invoke-Native @('storage', 'cp', $ExportObject, $localGz, "--project=$TargetProject") | Out-Null

    $plain = Join-Path $work 'dump.sql'
    $input = New-Object System.IO.FileStream($localGz, [System.IO.FileMode]::Open)
    $gzip = New-Object System.IO.Compression.GZipStream($input, [System.IO.Compression.CompressionMode]::Decompress)
    $out = New-Object System.IO.FileStream($plain, [System.IO.FileMode]::Create)
    try { $gzip.CopyTo($out) } finally { $out.Dispose(); $gzip.Dispose(); $input.Dispose() }

    $removed = 0
    $sanitized = Join-Path $work 'dump-sanitized.sql'
    $writer = New-Object System.IO.StreamWriter($sanitized, $false, (New-Object System.Text.UTF8Encoding($false)))
    try {
      foreach ($line in [System.IO.File]::ReadLines($plain)) {
        if ($line -match '^ALTER DEFAULT PRIVILEGES .*;$') { $removed++; continue }
        $writer.WriteLine($line)
      }
    }
    finally { $writer.Dispose() }
    Write-Host "  removed $removed ALTER DEFAULT PRIVILEGES statements"
    if ($removed -eq 0) { Write-Host '  (none present; import would have succeeded unmodified)' -ForegroundColor DarkGray }

    $sanitizedGz = Join-Path $work 'dump-sanitized.sql.gz'
    $srcStream = New-Object System.IO.FileStream($sanitized, [System.IO.FileMode]::Open)
    $dstStream = New-Object System.IO.FileStream($sanitizedGz, [System.IO.FileMode]::Create)
    $gzOut = New-Object System.IO.Compression.GZipStream($dstStream, [System.IO.Compression.CompressionMode]::Compress)
    try { $srcStream.CopyTo($gzOut) } finally { $gzOut.Dispose(); $dstStream.Dispose(); $srcStream.Dispose() }

    $script:SanitizedObject = $ExportObject -replace '\.sql\.gz$', '-sanitized.sql.gz'
    Invoke-Native @('storage', 'cp', $sanitizedGz, $script:SanitizedObject, "--project=$TargetProject") | Out-Null
    Write-Host "  sanitized dump: $script:SanitizedObject"
    Write-Host '  IMPORT THIS OBJECT, not the raw export.' -ForegroundColor Yellow
  }
  finally { Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue }
}

# ---------------------------------------------------------------- import
if (Test-Stage 'import') {
  Write-Step "Import into $TargetProject/$TargetInstance/$Database"
  Write-Host '  PRECONDITION: app_rt must already exist on the destination instance.' -ForegroundColor Yellow
  Write-Host '  The dump contains GRANT and RLS statements naming app_rt; without the role the import' -ForegroundColor Yellow
  Write-Host '  fails partway and leaves a half-loaded database.' -ForegroundColor Yellow
  Wait-SqlOperations -Project $TargetProject -Instance $TargetInstance
  Invoke-Native @('sql', 'import', 'sql', $TargetInstance, $ExportObject,
    "--database=$Database", "--project=$TargetProject", '--quiet') | Out-Null
  Write-Host '  imported'
}

# ---------------------------------------------------------------- photos
if (Test-Stage 'photos') {
  Write-Step "Photos gs://$SourcePhotoBucket -> gs://$TargetPhotoBucket"
  Invoke-Native @('storage', 'buckets', 'add-iam-policy-binding', "gs://$TargetPhotoBucket",
    "--member=user:$((Invoke-Native @('config','get-value','account')))", '--role=roles/storage.objectAdmin',
    "--project=$TargetProject") -AllowFailure | Out-Null

  # Copy the durable set only. temporary/ is transient and lifecycle-managed at both ends, so copying it
  # would import objects that are already scheduled for deletion and inflate the reconciliation.
  # Excluded by design, per the measured data ledger:
  #   temporary/          transient photo-import staging, lifecycle-deleted at 14 days
  #   students/<numeric>  legacy pre-UUID layout, proven unreferenced by any students.photo_url
  #   student-imports/<numeric>  same legacy generation
  # Keeping the legacy prefixes out matters more when the source is being deleted: copying them carries
  # dead data into a project that is meant to be clean, and they cannot be cleaned up by the application
  # (StudentPhotoStorage.deleteStoredPhoto ignores any key not under schools/.../students/).
  $exclusions = '^temporary/|^students/|^student-imports/'
  Invoke-Native @('storage', 'rsync', '--recursive', "--exclude=$exclusions",
    "gs://$SourcePhotoBucket", "gs://$TargetPhotoBucket") | Out-Null

  # Compare by name AND CRC32C, not by count. Equal counts with different content is exactly the failure
  # a count-only check cannot see.
  $sourceNames = Get-BucketManifest -Bucket $SourcePhotoBucket -Project $SourceProject |
    Where-Object { $_.Name -notmatch $exclusions }
  $targetNames = Get-BucketManifest -Bucket $TargetPhotoBucket -Project $TargetProject
  $sourceMap = @{}; foreach ($o in $sourceNames) { $sourceMap[$o.Name] = $o.Crc }
  $targetMap = @{}; foreach ($o in $targetNames) { $targetMap[$o.Name] = $o.Crc }

  $missing = @($sourceMap.Keys | Where-Object { -not $targetMap.ContainsKey($_) })
  $differing = @($sourceMap.Keys | Where-Object { $targetMap.ContainsKey($_) -and $targetMap[$_] -ne $sourceMap[$_] })
  Write-Host "  source objects in scope : $($sourceMap.Count)"
  Write-Host "  destination objects     : $($targetMap.Count)"
  Write-Host "  missing at destination  : $($missing.Count)"
  Write-Host "  CRC32C mismatches       : $($differing.Count)"
  if ($missing.Count -gt 0 -or $differing.Count -gt 0) {
    throw "Photo reconciliation failed: $($missing.Count) missing, $($differing.Count) mismatched."
  }
  Write-Host '  every in-scope object present with matching CRC32C' -ForegroundColor Green
}

Write-Host "`nExport object: $ExportObject" -ForegroundColor Cyan
Write-Host 'Next: run create-app-rt-role.sql to apply schema grants, then verify with the ledger.' -ForegroundColor Cyan
