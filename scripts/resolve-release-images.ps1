#requires -Version 7.0
[CmdletBinding()]
param(
  [Parameter(Mandatory)][ValidateSet('dev','prod')][string]$Environment,
  [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40,64}$')][string]$CommitSha,
  [Parameter(Mandatory)][ValidatePattern('^[a-z][a-z0-9-]{4,61}[a-z0-9]$')][string]$ProjectId,
  [Parameter(Mandatory)][ValidatePattern('^[a-z][a-z0-9-]{4,61}[a-z0-9]$')][string]$SourceProjectId,
  [Parameter(Mandatory)][ValidatePattern('^[a-z]+-[a-z]+[0-9]+$')][string]$Region,
  [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9_-]*$')][string]$Repository,
  [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')][string]$GitHubRepository,
  [Parameter(Mandatory)][string]$MatrixJson,
  [ValidateRange(1,3)][int]$MaxConcurrency=3,
  [string]$OutputPath='release-evidence/images.json',
  [string]$GitHubOutput=$env:GITHUB_OUTPUT
)
$ErrorActionPreference='Stop'
$scanKeys=[ordered]@{ 'identity-service'='identity'; 'school-core-service'='school_core';
  'operations-service'='operations'; 'platform-service'='platform'; 'billing-service'='billing';
  'frontend'='frontend'; 'api-gateway'='api_gateway' }
$matrix=$MatrixJson | ConvertFrom-Json -AsHashtable
$entries=@($matrix.include)
if ($entries.Count -eq 0) { throw 'No affected release images were supplied.' }
$seenNames=@{}; $seenImages=@{}
foreach ($entry in $entries) {
  if (!$scanKeys.Contains([string]$entry.name)) { throw "Unsupported release image '$($entry.name)' cannot be mapped to a scan gate." }
  if ($seenNames.ContainsKey([string]$entry.name) -or $seenImages.ContainsKey([string]$entry.image)) { throw 'Duplicate release service or image in affected matrix.' }
  if ([string]$entry.image -cnotmatch '^[a-z0-9]+(?:[._-][a-z0-9]+)*$' -or [string]::IsNullOrWhiteSpace([string]$entry.context)) { throw 'Invalid release image or context.' }
  $seenNames[[string]$entry.name]=$true; $seenImages[[string]$entry.image]=$true
}
if (Test-Path -LiteralPath $OutputPath) { throw 'Image resolution output already exists; use a fresh evidence directory.' }
$options=@{ Environment=$Environment; CommitSha=$CommitSha; ProjectId=$ProjectId; SourceProjectId=$SourceProjectId;
  Region=$Region; Repository=$Repository; GitHubRepository=$GitHubRepository;
  SourceRegistry="$Region-docker.pkg.dev/$SourceProjectId/$Repository";
  RuntimeRegistry="$Region-docker.pkg.dev/$ProjectId/$Repository" }
$work=@(for ($i=0; $i -lt $entries.Count; $i++) { @{ Index=$i; Entry=$entries[$i]; Options=$options } })
$worker=Join-Path $PSScriptRoot 'resolve-release-image-worker.ps1'
$results=@($work | ForEach-Object -Parallel { & $using:worker -Work $_ } -ThrottleLimit $MaxConcurrency)
$failed=@($results | Where-Object { !$_.Success })
if ($results.Count -ne $entries.Count -or $failed.Count -gt 0) {
  throw "Immutable image resolution failed; deployment is blocked. $($failed.Error -join '; ')"
}
# No partial scan outputs or deployable manifest escape when any worker fails.
$services=@($results | Sort-Object Index | ForEach-Object { $_.Service })
$output=[ordered]@{ commit=$CommitSha; environment=$Environment; registry=$options.SourceRegistry;
  resolvedAtUtc=[DateTime]::UtcNow.ToString('o'); services=$services }
$parent=Split-Path -Parent ([IO.Path]::GetFullPath($OutputPath))
New-Item -ItemType Directory -Force -Path (Join-Path $parent 'trivy') | Out-Null
$json=$output | ConvertTo-Json -Depth 10
# CreateNew prevents accidentally replacing an earlier release's evidence.
$stream=[IO.File]::Open($OutputPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write)
try { $bytes=[Text.Encoding]::UTF8.GetBytes($json); $stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
if (![string]::IsNullOrWhiteSpace($GitHubOutput)) {
  $lines=@(foreach ($service in $services) {
    $key=$scanKeys[$service.service]
    "${key}_ref=$($service.immutableRef)"
    "${key}_digest_key=$($service.digest.Replace(':','-'))"
  })
  [IO.File]::AppendAllText($GitHubOutput,($lines -join "`n")+"`n",[Text.Encoding]::UTF8)
}
Write-Host "Resolved $($services.Count) immutable image(s), with at most $MaxConcurrency concurrent workers."
