param(
  [Parameter(Mandatory = $true)]
  [ValidateSet("dev", "prod")]
  [string]$Environment,

  [string]$SourcePath = "deploy/clouddeploy/delivery-pipelines.yaml",

  [Parameter(Mandatory = $true)]
  [string]$OutputPath
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$source = if ([System.IO.Path]::IsPathRooted($SourcePath)) {
  $SourcePath
} else {
  Join-Path $repoRoot $SourcePath
}
$output = if ([System.IO.Path]::IsPathRooted($OutputPath)) {
  $OutputPath
} else {
  Join-Path $repoRoot $OutputPath
}

if (-not (Test-Path -LiteralPath $source)) {
  throw "Cloud Deploy pipeline source does not exist: $source"
}

$documents = @([regex]::Split((Get-Content -Raw -LiteralPath $source), '(?m)^\s*---\s*$') |
  ForEach-Object { $_.Trim() } |
  Where-Object { -not [string]::IsNullOrWhiteSpace($_) })

$selected = @()
foreach ($document in $documents) {
  if ($document -notmatch '(?m)^kind:\s*DeliveryPipeline\s*$') {
    throw "Every document in $SourcePath must be a Cloud Deploy DeliveryPipeline."
  }

  $nameMatches = [regex]::Matches($document, '(?m)^\s*name:\s*([^\s#]+)\s*$')
  $targetMatches = [regex]::Matches($document, '(?m)^\s*-\s*targetId:\s*([^\s#]+)\s*$')
  $labelMatches = [regex]::Matches($document, '(?m)^\s*labels:\s*\{[^\r\n]*\benv:\s*(dev|prod)\b[^\r\n]*\}\s*$')
  if ($nameMatches.Count -ne 1 -or $targetMatches.Count -ne 1 -or $labelMatches.Count -ne 1) {
    throw "Every pipeline must have a name, exactly one targetId, and an inline dev/prod env label."
  }

  $nameMatch = $nameMatches[0]
  $targetMatch = $targetMatches[0]
  $labelMatch = $labelMatches[0]
  $documentEnvironment = $labelMatch.Groups[1].Value
  if ($nameMatch.Groups[1].Value -notmatch "-$documentEnvironment$" -or
      $targetMatch.Groups[1].Value -notmatch "-$documentEnvironment$") {
    throw "Pipeline '$($nameMatch.Groups[1].Value)' does not match its '$documentEnvironment' label."
  }

  if ($documentEnvironment -eq $Environment) {
    $selected += $document
  }
}

if ($selected.Count -ne 7) {
  throw "Expected exactly seven '$Environment' delivery pipelines, found $($selected.Count)."
}

$outputDirectory = Split-Path -Parent $output
if (-not [string]::IsNullOrWhiteSpace($outputDirectory)) {
  New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}

($selected -join "`n---`n") + "`n" | Set-Content -LiteralPath $output -Encoding utf8
Write-Output $output
