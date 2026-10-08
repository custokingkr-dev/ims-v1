param(
  [Parameter(Mandatory = $true)]
  [string]$CommitSha,

  [Parameter(Mandatory = $true)]
  [string]$Context,

  [string]$SourcePaths = "",

  [string]$BuildArgs = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$rawContext = ($Context -replace "\\", "/").Trim()
$normalizedContext = if ($rawContext -in @(".", "./", "./.")) {
  "."
} else {
  $rawContext.TrimStart(".", "/")
}
if ([string]::IsNullOrWhiteSpace($normalizedContext)) {
  throw "Image build context cannot be empty."
}

if ($CommitSha -notmatch "^[0-9a-fA-F]{40,64}$") {
  throw "Image source must select an exact Git commit SHA."
}
$exactCommitSha = (& git -C $repoRoot rev-parse --verify "${CommitSha}^{commit}").Trim()
if ($LASTEXITCODE -ne 0 -or $exactCommitSha -notmatch "^[0-9a-f]{40,64}$") {
  throw "Could not resolve the selected image source commit."
}

$epochPath = "deploy/runtime-patch-epoch.txt"
$epochObjectId = (& git -C $repoRoot rev-parse "${exactCommitSha}:$epochPath").Trim()
if ($LASTEXITCODE -ne 0 -or $epochObjectId -notmatch "^[0-9a-f]{40,64}$") {
  throw "Selected commit is missing the runtime patch epoch."
}
$epochSize = (& git -C $repoRoot cat-file -s $epochObjectId).Trim()
if ($LASTEXITCODE -ne 0 -or $epochSize -notmatch "^[0-9]+$" -or [long]$epochSize -gt 64 -or [long]$epochSize -lt 10) {
  throw "Runtime patch epoch must be a bounded ASCII date blob."
}
$epochType = (& git -C $repoRoot cat-file -t $epochObjectId).Trim()
if ($LASTEXITCODE -ne 0 -or $epochType -ne "blob") {
  throw "Runtime patch epoch must be a Git blob."
}
# Read bytes from the selected Git object, never the checkout or deployment clock.
$gitStart = New-Object System.Diagnostics.ProcessStartInfo
$gitStart.FileName = "git"
$gitStart.Arguments = "cat-file blob $epochObjectId"
$gitStart.WorkingDirectory = $repoRoot.Path
$gitStart.UseShellExecute = $false
$gitStart.RedirectStandardOutput = $true
$gitStart.RedirectStandardError = $true
$gitProcess = New-Object System.Diagnostics.Process
$gitProcess.StartInfo = $gitStart
$epochBuffer = New-Object System.IO.MemoryStream
try {
  [void]$gitProcess.Start()
  $gitProcess.StandardOutput.BaseStream.CopyTo($epochBuffer)
  $gitProcess.WaitForExit()
  $epochBytes = $epochBuffer.ToArray()
  if ($gitProcess.ExitCode -ne 0 -or $epochBytes.Length -ne [long]$epochSize -or @($epochBytes | Where-Object { $_ -gt 127 }).Count -gt 0) {
    throw "Runtime patch epoch must be a bounded ASCII date blob."
  }
  $epochText = [System.Text.Encoding]::ASCII.GetString($epochBytes)
  if ($epochText -cnotmatch "\A[0-9]{4}-[0-9]{2}-[0-9]{2}(?:-r[1-9][0-9]{0,5})?(?:\r?\n)?\z") {
    throw "Runtime patch epoch must contain one canonical YYYY-MM-DD date with an optional -r1..999999 cycle."
  }
  $securityPatchEpoch = $epochText.TrimEnd([char]13, [char]10)
  $parsedEpoch = [datetime]::MinValue
  if (-not [datetime]::TryParseExact($securityPatchEpoch.Substring(0, 10), "yyyy-MM-dd", [System.Globalization.CultureInfo]::InvariantCulture, [System.Globalization.DateTimeStyles]::None, [ref]$parsedEpoch)) {
    throw "Runtime patch epoch must be a real calendar date."
  }
} finally {
  $epochBuffer.Dispose()
  $gitProcess.Dispose()
}

$normalizedSourcePaths = @(
  if ([string]::IsNullOrWhiteSpace($SourcePaths)) {
    $normalizedContext
  } else {
    @($SourcePaths -split "[|;`r`n]+") | ForEach-Object { ($_ -replace "\\", "/").TrimStart(".", "/") }
  }
  $epochPath
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique

$sourceObjects = foreach ($sourcePath in $normalizedSourcePaths) {
  $objectId = (& git -C $repoRoot rev-parse "${exactCommitSha}:$sourcePath").Trim()
  if ($LASTEXITCODE -ne 0 -or $objectId -notmatch "^[0-9a-f]{40,64}$") {
    throw "Could not resolve Git object for '$sourcePath' at '$CommitSha'."
  }
  "${sourcePath}=${objectId}"
}

$normalizedBuildArgs = (@($BuildArgs -split "`r?`n") |
  ForEach-Object { $_.Trim() } |
  Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join "`n"
$fingerprint = "custoking-image-source-v2`n$normalizedContext`n$($sourceObjects -join "`n")`n$normalizedBuildArgs"
$bytes = [System.Text.Encoding]::UTF8.GetBytes($fingerprint)
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
  $sourceId = ([System.BitConverter]::ToString($sha256.ComputeHash($bytes)) -replace "-", "").ToLowerInvariant()
} finally {
  $sha256.Dispose()
}

[ordered]@{
  securityPatchEpoch = $securityPatchEpoch
  sourceId = $sourceId
  sourceTag = "src-$sourceId"
  context = $normalizedContext
  sourcePaths = $normalizedSourcePaths
  sourceObjects = $sourceObjects
  buildArgs = $normalizedBuildArgs
} | ConvertTo-Json -Depth 5 -Compress
