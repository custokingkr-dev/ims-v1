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

$normalizedSourcePaths = @(
  if ([string]::IsNullOrWhiteSpace($SourcePaths)) {
    $normalizedContext
  } else {
    @($SourcePaths -split "[|;`r`n]+") | ForEach-Object { ($_ -replace "\\", "/").TrimStart(".", "/") }
  }
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique

$sourceObjects = foreach ($sourcePath in $normalizedSourcePaths) {
  $objectId = (& git -C $repoRoot rev-parse "${CommitSha}:$sourcePath").Trim()
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
  sourceId = $sourceId
  sourceTag = "src-$sourceId"
  context = $normalizedContext
  sourcePaths = $normalizedSourcePaths
  sourceObjects = $sourceObjects
  buildArgs = $normalizedBuildArgs
} | ConvertTo-Json -Depth 5 -Compress
