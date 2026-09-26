$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$manifest = Get-Content -Raw (Join-Path $repoRoot "deploy/cloudrun/platform-service.yaml")
foreach ($expected in @(
  '- name: BROADCAST_DISPATCH_MODE\s+value: "OFF" # from-param: \$\{broadcast_dispatch_mode\}',
  '- name: BROADCAST_WORKER_READY\s+value: "false" # from-param: \$\{broadcast_worker_ready\}',
  '- name: NOTIFICATION_DELIVERY_PROVIDER\s+value: logging # from-param: \$\{notification_delivery_provider\}',
  '- name: MSG91_DRY_RUN\s+value: "true" # from-param: \$\{msg91_dry_run\}'
)) {
  if ($manifest -notmatch $expected) { throw "Platform manifest is missing its safe broadcast parameter fallback: $expected" }
}
Write-Output "PASS: platform broadcast parameter wiring retains OFF/false, logging, and MSG91 dry-run defaults."
