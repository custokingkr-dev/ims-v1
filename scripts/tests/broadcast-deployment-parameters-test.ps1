$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$renderer = Join-Path $repoRoot "scripts/render-clouddeploy-targets.ps1"
$resolver = Join-Path $repoRoot "scripts/resolve-affected-ci-targets.ps1"
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("ims-broadcast-config-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$saved = @{}
foreach ($deploymentEnv in @("DEV", "PROD")) {
  foreach ($entry in @{
    GCP_PROJECT_ID = "custoking-$($deploymentEnv.ToLowerInvariant())"
    GCP_PROJECT_NUMBER = "123456789"
    GCP_REGION = "asia-south2"
    DB_HOST = "127.0.0.1:5432"
    DB_NAME = "fixture"
    STUDENT_PHOTO_IMPORT_DRIVE_ROOT_FOLDER_ID = "fixture-folder"
    STUDENT_PHOTO_BUCKET = "fixture-private-bucket"
  }.GetEnumerator()) {
    $name = "${deploymentEnv}_$($entry.Key)"
    $saved[$name] = [Environment]::GetEnvironmentVariable($name)
    [Environment]::SetEnvironmentVariable($name, $entry.Value)
  }
}
function Assert-True([bool]$Value, [string]$Message) {
  if (-not $Value) { throw $Message }
}
function Assert-Rejected([string]$Template, [string]$Environment, [string]$Message) {
  $path = Join-Path $testRoot "invalid.yaml"
  Set-Content -LiteralPath $path -Value $Template
  $rejected = $false
  try { & $renderer -Environment $Environment -TemplatePath $path -OutputPath (Join-Path $testRoot "invalid-rendered.yaml") }
  catch { $rejected = $_.Exception.Message -match 'Broadcast parameters|Platform target must declare' }
  Assert-True $rejected $Message
}
try {
  $dev = Get-Content -Raw (Join-Path $repoRoot "deploy/clouddeploy/targets-dev.yaml")
  $prod = Get-Content -Raw (Join-Path $repoRoot "deploy/clouddeploy/targets-prod.yaml")
  foreach ($deploymentEnv in @("dev", "prod")) {
    $output = Join-Path $testRoot "$deploymentEnv.yaml"
    & $renderer -Environment $deploymentEnv -OutputPath $output
    $rendered = Get-Content -Raw $output
    Assert-True ($rendered -notmatch '__[A-Z0-9_]+__') "Unresolved $deploymentEnv placeholder"
    $mode = if ($deploymentEnv -eq "dev") { "DRY_RUN" } else { "OFF" }
    Assert-True ($rendered.Contains("broadcast_dispatch_mode: `"$mode`"")) "Wrong $deploymentEnv mode"
  }
  Assert-Rejected ($prod.Replace('broadcast_dispatch_mode: "OFF"', 'broadcast_dispatch_mode: "DRY_RUN"').Replace('broadcast_worker_ready: "false"', 'broadcast_worker_ready: "true"')) "prod" "Production dry-run activation must fail"
  Assert-Rejected ($dev.Replace('broadcast_dispatch_mode: "DRY_RUN"', 'broadcast_dispatch_mode: "LIVE"')) "dev" "Live mode must fail"
  Assert-Rejected ($dev.Replace('notification_delivery_provider: logging', 'notification_delivery_provider: msg91')) "dev" "Live provider must fail"
  Assert-Rejected ($dev.Replace('msg91_dry_run: "true"', 'msg91_dry_run: "false"')) "dev" "Live provider fallback must fail"
  Assert-Rejected ($dev.Replace('broadcast_worker_ready: "true"', 'broadcast_worker_ready: "false"')) "dev" "Unready worker must fail"
  Assert-Rejected ($dev.Replace('broadcast_dispatch_mode: "DRY_RUN"', '# missing mode')) "dev" "Missing explicit target mode must fail"
  Assert-Rejected ($dev.Replace('broadcast_dispatch_mode: "DRY_RUN"', "broadcast_dispatch_mode: `"DRY_RUN`"`n  broadcast_dispatch_mode: `"OFF`"")) "dev" "Duplicate target mode must fail"

  $stage = Get-Content -Raw (Join-Path $repoRoot "deploy/clouddeploy/targets-stage.yaml")
  Assert-True ($stage.Contains('broadcast_dispatch_mode: "OFF"') -and $stage.Contains('broadcast_worker_ready: "false"')) "Stage must stay off"
  $stageRejected = $false
  try { & $renderer -Environment stage -OutputPath (Join-Path $testRoot "stage.yaml") }
  catch { $stageRejected = $_.Exception.Message -match 'ValidateSet|validation|does not belong' }
  Assert-True $stageRejected "Stage reconciliation must remain unavailable"

  $configRoute = & $resolver -Environment dev -ChangedFilesOverride @("deploy/clouddeploy/targets-dev.yaml", "scripts/render-clouddeploy-targets.ps1") | ConvertFrom-Json
  Assert-True ($configRoute.deployment_reconciliation_required -and -not $configRoute.has_service_changes) "Config commit must reconcile without releasing services"
  $manifestRoute = & $resolver -Environment dev -ChangedFilesOverride @("deploy/cloudrun/platform-service.yaml") | ConvertFrom-Json
  Assert-True ($manifestRoute.deployment_config_changed -and -not $manifestRoute.deployment_reconciliation_required -and $manifestRoute.service_matrix.include.Count -eq 1 -and $manifestRoute.service_matrix.include[0].name -eq "platform-service") "Manifest commit must release only platform through Cloud Deploy"
  Write-Output "PASS: dev/prod render; seven unsafe parameter rejections; stage off/unavailable; separate config and platform-release routing."
}
finally {
  foreach ($entry in $saved.GetEnumerator()) { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value) }
  $resolvedTestRoot = [System.IO.Path]::GetFullPath($testRoot)
  $tempParent = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd([char[]]'\/')
  if ((Split-Path -Parent $resolvedTestRoot).TrimEnd([char[]]'\/') -ne $tempParent -or (Split-Path -Leaf $resolvedTestRoot) -notlike "ims-broadcast-config-*") {
    throw "Refusing cleanup outside the dedicated temporary test directory."
  }
  Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
}
