param(
  [Parameter(Mandatory = $true)]
  [string]$CommitSha,

  [Parameter(Mandatory = $true)]
  [string]$Context,

  [string]$BuildArgs = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$normalizedContext = ($Context -replace "\\", "/").TrimStart(".", "/")
if ([string]::IsNullOrWhiteSpace($normalizedContext)) {
  throw "Image build context cannot be empty."
}

$treeId = (& git -C $repoRoot rev-parse "${CommitSha}:$normalizedContext").Trim()
if ($LASTEXITCODE -ne 0 -or $treeId -notmatch "^[0-9a-f]{40,64}$") {
  throw "Could not resolve Git tree for '$normalizedContext' at '$CommitSha'."
}

$normalizedBuildArgs = (@($BuildArgs -split "`r?`n") |
  ForEach-Object { $_.Trim() } |
  Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join "`n"
$fingerprint = "custoking-image-source-v1`n$normalizedContext`n$treeId`n$normalizedBuildArgs"
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
  treeId = $treeId
  buildArgs = $normalizedBuildArgs
} | ConvertTo-Json -Depth 5 -Compress
