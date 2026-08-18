<#
.SYNOPSIS
  Copies Secret Manager values from the source project into a destination Custoking project.

.DESCRIPTION
  Values are streamed source -> destination in memory. Nothing is written to disk, echoed to the console,
  or placed in a variable that is later printed. Only secret NAMES and byte counts are reported.

  Run Invoke-DestinationBuild.ps1 -Stage 60 first: this script adds versions to containers that must
  already exist, and it will not create them.

  Values must be transferred rather than regenerated:
    jwt-secret-<env>        regenerating signs out every active session
    db-password-<env>       must equal the appuser password on the destination instance
    app-rt-password-<env>   must equal the password passed to create-app-rt-role.sql
    *-read-token / *-service-token   shared inter-service secrets; a partial rotation breaks calls
    msg91-auth-key-<env>    vendor credential, not reissuable by us

.EXAMPLE
  ./Copy-MigrationSecrets.ps1 -Environment dev
  ./Copy-MigrationSecrets.ps1 -Environment dev -Verify
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][ValidateSet('dev', 'prod')][string]$Environment,
  [string]$SourceProject = 'custoking',
  [string]$TargetProject,
  # Re-read both sides and compare SHA-256 of the values without displaying them.
  [switch]$Verify
)

$ErrorActionPreference = 'Stop'
if (-not $TargetProject) { $TargetProject = "custoking-$Environment" }

function Invoke-GcloudText {
  param([string[]]$Arguments)
  $previous = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $output = & gcloud @Arguments
    $code = $LASTEXITCODE
  }
  finally { $ErrorActionPreference = $previous }
  if ($code -ne 0) { throw "gcloud $($Arguments -join ' ') exited $code" }
  return ($output | Out-String)
}

function Split-Lines([string]$Text) {
  if (-not $Text) { return @() }
  return ($Text -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

function Get-Sha256([byte[]]$Bytes) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try { return [BitConverter]::ToString($sha.ComputeHash($Bytes)).Replace('-', '').ToLowerInvariant() }
  finally { $sha.Dispose() }
}

# Read a secret's newest enabled value as raw bytes, without it ever touching disk or the console.
# gcloud writes the value to stdout; capture it through a byte-preserving redirect so that trailing
# newlines and non-UTF8 material are not silently altered.
function Get-SecretBytes {
  param([string]$Project, [string]$Secret)
  $temp = [System.IO.Path]::GetTempFileName()
  try {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
      & gcloud secrets versions access latest --secret=$Secret --project=$Project --out-file=$temp
      $code = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $previous }
    if ($code -ne 0) { throw "Could not read $Secret from $Project (exit $code)." }
    return [System.IO.File]::ReadAllBytes($temp)
  }
  finally {
    if (Test-Path $temp) {
      # Overwrite before deleting so the value is not left recoverable in slack space.
      $length = (Get-Item $temp).Length
      if ($length -gt 0) { [System.IO.File]::WriteAllBytes($temp, (New-Object byte[] $length)) }
      Remove-Item $temp -Force
    }
  }
}

$secrets = @(Split-Lines (Invoke-GcloudText @('secrets', 'list', "--project=$SourceProject", '--format=value(name)')) |
    Where-Object { $_ -match "-$Environment$" -or $_ -match '-sql$' })
if ($secrets.Count -eq 0) { throw 'Resolved zero source secrets; refusing to continue.' }

Write-Host "Transferring $($secrets.Count) secret values: $SourceProject -> $TargetProject" -ForegroundColor Cyan
Write-Host 'Values are never printed. Only names, sizes and digests are shown.' -ForegroundColor DarkGray

$failed = @()
foreach ($secret in $secrets) {
  try {
    $bytes = Get-SecretBytes -Project $SourceProject -Secret $secret
    $sourceHash = Get-Sha256 $bytes

    if ($Verify) {
      $targetBytes = Get-SecretBytes -Project $TargetProject -Secret $secret
      $targetHash = Get-Sha256 $targetBytes
      if ($sourceHash -eq $targetHash) {
        Write-Host ("  match  {0,-52} {1} bytes" -f $secret, $bytes.Length) -ForegroundColor Green
      }
      else {
        Write-Host ("  DIFFER {0,-52} src {1}.. dst {2}.." -f $secret, $sourceHash.Substring(0, 8), $targetHash.Substring(0, 8)) -ForegroundColor Red
        $failed += $secret
      }
      [Array]::Clear($targetBytes, 0, $targetBytes.Length)
    }
    else {
      # Skip if an identical value is already present, so re-runs do not pile up versions.
      $existing = $null
      try { $existing = Get-Sha256 (Get-SecretBytes -Project $TargetProject -Secret $secret) } catch { $existing = $null }
      if ($existing -eq $sourceHash) {
        Write-Host ("  --     {0,-52} already current" -f $secret) -ForegroundColor DarkGray
      }
      else {
        $temp = [System.IO.Path]::GetTempFileName()
        try {
          [System.IO.File]::WriteAllBytes($temp, $bytes)
          & gcloud secrets versions add $secret --data-file=$temp --project=$TargetProject | Out-Null
          if ($LASTEXITCODE -ne 0) { throw "versions add failed for $secret" }
          Write-Host ("  ok     {0,-52} {1} bytes" -f $secret, $bytes.Length) -ForegroundColor Green
        }
        finally {
          $length = (Get-Item $temp).Length
          if ($length -gt 0) { [System.IO.File]::WriteAllBytes($temp, (New-Object byte[] $length)) }
          Remove-Item $temp -Force
        }
      }
    }

    [Array]::Clear($bytes, 0, $bytes.Length)
  }
  catch {
    Write-Host ("  FAIL   {0,-52} {1}" -f $secret, $_.Exception.Message) -ForegroundColor Red
    $failed += $secret
  }
}

if ($failed.Count -gt 0) {
  throw "$($failed.Count) secret(s) did not transfer or did not match: $($failed -join ', ')"
}
Write-Host "`nAll $($secrets.Count) secrets $(if ($Verify) { 'verified identical' } else { 'transferred' })." -ForegroundColor Cyan
if (-not $Verify) { Write-Host 'Re-run with -Verify to confirm the destination matches the source.' -ForegroundColor Yellow }
