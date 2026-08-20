param(
  [string]$ProjectId = $(if ($env:GCP_PROJECT_ID) { $env:GCP_PROJECT_ID } else { throw "ProjectId is required: pass -ProjectId explicitly or set GCP_PROJECT_ID. It used to default to the pre-split project, which is being deleted." }),
  [ValidateSet("dev", "prod", "all")]
  [string]$Environment = "all",
  [int]$MinimumAgeDays = 14,
  [string]$ReportPath = "artifacts/cost-controls/expired-photo-import-sources.csv",
  [switch]$Apply
)

$ErrorActionPreference = "Stop"
$GcloudCommand = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }
$environments = if ($Environment -eq "all") { @("dev", "prod") } else { @($Environment) }
$cutoff = (Get-Date).ToUniversalTime().AddDays(-$MinimumAgeDays)
$candidates = @()

foreach ($envName in $environments) {
  $bucket = "custoking-student-photos-$envName"
  $rows = & $GcloudCommand storage ls --long --recursive "gs://$bucket/**"
  if ($LASTEXITCODE -ne 0) { throw "Could not list gs://$bucket." }
  foreach ($row in $rows) {
    if ($row -notmatch '^\s*(\d+)\s+(\S+)\s+(gs://\S+)$') { continue }
    $bytes = [int64]$matches[1]
    $created = [datetime]$matches[2]
    $url = $matches[3]
    if ($url -notmatch '^gs://custoking-student-photos-(dev|prod)/schools/[A-Za-z0-9._-]+/student-imports/photo-import-[A-Fa-f0-9-]+/') {
      continue
    }
    if ($created.ToUniversalTime() -gt $cutoff) { continue }
    $candidates += [pscustomobject]@{ Environment=$envName; Created=$created; Bytes=$bytes; Url=$url }
  }
}

$totalBytes = ($candidates | Measure-Object Bytes -Sum).Sum
Write-Host "Legacy temporary photo-import candidates: $($candidates.Count) objects, $totalBytes bytes, cutoff $($cutoff.ToString('o'))."
$reportDirectory = Split-Path -Parent $ReportPath
if ($reportDirectory) { New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null }
$candidates | Export-Csv -NoTypeInformation -Path $ReportPath
Write-Host "Candidate report: $ReportPath"
if (-not $Apply) {
  $candidates | Select-Object -First 20 Environment, Created, Bytes, Url | Format-Table -AutoSize
  Write-Host "Dry run only. Re-run with -Apply after reviewing the exact object paths."
  exit 0
}

foreach ($candidate in $candidates) {
  & $GcloudCommand storage rm $candidate.Url --quiet
  if ($LASTEXITCODE -ne 0) { throw "Could not delete $($candidate.Url)." }
}
Write-Host "Deleted $($candidates.Count) legacy temporary source objects. Bucket soft-delete retention remains active."
