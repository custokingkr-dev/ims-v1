param(
  [Parameter(Mandatory=$true)][ValidateSet('dev','prod')][string]$Environment,
  [Parameter(Mandatory=$true)][string]$ProjectId,
  [string]$Region='asia-south2',
  [switch]$Apply
)
$ErrorActionPreference='Stop'
if ($ProjectId -cne "custoking-$Environment") { throw 'Project must explicitly match the selected Custoking environment.' }
$gcloud=(Get-Command gcloud.cmd -ErrorAction Stop).Source
$secret="broadcast-live-webhook-token-$Environment"
$identity="ims-platform-$Environment@$ProjectId.iam.gserviceaccount.com"
function Read-Metadata([string[]]$Arguments) {
  $output=& $gcloud @Arguments "--project=$ProjectId" --format=json
  if ($LASTEXITCODE -ne 0) { throw 'GCP metadata read failed; no changes attempted.' }
  return ($output | ConvertFrom-Json)
}
$account=Read-Metadata @('iam','service-accounts','describe',$identity)
if ($account.email -cne $identity -or $account.disabled) { throw 'Expected active platform runtime identity is required.' }
$secrets=@(Read-Metadata @('secrets','list'))
$exists=@($secrets | Where-Object { $_.name -match "/secrets/$secret$" }).Count -eq 1
$versions=if ($exists) {@(Read-Metadata @('secrets','versions','list',$secret))} else {@()}
if ($versions.Count -gt 0) {
  $latest=@($versions | Sort-Object { [long](($_.name -split '/')[-1]) } -Descending)[0]
  if ($latest.state -ne 'ENABLED') { throw 'Latest webhook secret version is not enabled; this script never rotates or reenables existing credentials.' }
}
[ordered]@{project=$ProjectId;environment=$Environment;secret=$secret;secretCreateRequired=(!$exists);
  versionCreateRequired=($versions.Count -eq 0);secretAccessor=$identity;liveSendingEnabled=$false;applyRequested=[bool]$Apply} | ConvertTo-Json
if (-not $Apply) { Write-Output 'Read-only plan. No cloud configuration or messages changed.'; return }
if (!$exists) {
  & $gcloud secrets create $secret --replication-policy=user-managed "--locations=$Region" "--project=$ProjectId" --quiet | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Webhook secret creation failed.' }
}
if ($versions.Count -eq 0) {
  $random=[Security.Cryptography.RandomNumberGenerator]::Create(); $bytes=New-Object byte[] 32
  try {
    $random.GetBytes($bytes)
    # Credential stays in memory and gcloud stdin, never an argument, file, plan or log.
    [Convert]::ToBase64String($bytes) | & $gcloud secrets versions add $secret --data-file=- "--project=$ProjectId" --quiet | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Webhook secret version creation failed; no credential was printed.' }
  } finally { [Array]::Clear($bytes,0,$bytes.Length); $random.Dispose() }
}
& $gcloud secrets add-iam-policy-binding $secret "--member=serviceAccount:$identity" --role=roles/secretmanager.secretAccessor "--project=$ProjectId" --quiet | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Webhook secret access binding failed.' }
Write-Output 'Webhook prerequisite provisioned. No rollout, provider setup or live messaging was performed.'
