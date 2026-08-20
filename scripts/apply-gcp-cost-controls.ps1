param(
  [string]$ProjectId = $(if ($env:GCP_PROJECT_ID) { $env:GCP_PROJECT_ID } else { throw "ProjectId is required: pass -ProjectId explicitly or set GCP_PROJECT_ID. It used to default to the pre-split project, which is being deleted." }),
  [string]$BillingAccount = "018AC9-E669C1-2FC9B8",
  [string]$BudgetDisplayName = "Custoking Monthly Guardrail",
  [string]$LifecycleFile = "infra/gcp/student-photo-temp-lifecycle.json",
  [int]$MinimumSupersededSecretAgeDays = 30,
  [switch]$Apply,
  [switch]$DestroySupersededSecretVersions
)

$ErrorActionPreference = "Stop"
$GcloudCommand = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }

if (-not (Test-Path -LiteralPath $LifecycleFile)) {
  throw "Lifecycle configuration not found: $LifecycleFile"
}
$lifecycle = Get-Content -Raw -LiteralPath $LifecycleFile | ConvertFrom-Json
$prefix = [string]$lifecycle.rule[0].condition.matchesPrefix[0]
if ($lifecycle.rule.Count -ne 1 -or $lifecycle.rule[0].action.type -ne "Delete" -or $prefix -ne "temporary/photo-imports/") {
  throw "Lifecycle policy must contain only the isolated temporary/photo-imports/ deletion rule."
}

$budgetJson = & $GcloudCommand billing budgets list "--billing-account=$BillingAccount" --format=json
if ($LASTEXITCODE -ne 0) { throw "Could not list billing budgets." }
$budget = @(($budgetJson | ConvertFrom-Json) | Where-Object { $_.displayName -eq $BudgetDisplayName })
if ($budget.Count -ne 1) { throw "Expected one budget named '$BudgetDisplayName', found $($budget.Count)." }
$budgetId = ([string]$budget[0].name -split '/')[-1]

$repositoryJson = & $GcloudCommand artifacts repositories describe custoking `
  --location=asia-south2 "--project=$ProjectId" --format=json
if ($LASTEXITCODE -ne 0) { throw "Could not inspect Artifact Registry." }
$repository = $repositoryJson | ConvertFrom-Json
if (@($repository.cleanupPolicies.PSObject.Properties).Count -lt 2) {
  throw "Artifact Registry cleanup policies are missing."
}

Write-Host "Validated isolated photo lifecycle prefix, billing budget, and Artifact Registry cleanup policies."
if (-not $Apply) {
  Write-Host "Dry run only. Re-run with -Apply to update the buckets and gross-cost budget."
  exit 0
}

foreach ($environment in @("dev", "prod")) {
  $bucket = "gs://custoking-student-photos-$environment"
  $legacyPrefixes = @(& $GcloudCommand storage ls --recursive "$bucket/**" | ForEach-Object {
    if ($_ -match "^$([regex]::Escape($bucket))/((?:schools/[A-Za-z0-9._-]+/student-imports/photo-import-[A-Fa-f0-9-]+/))") {
      $matches[1]
    }
  } | Sort-Object -Unique)
  $effectiveLifecyclePath = "artifacts/cost-controls/student-photo-$environment-lifecycle.json"
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $effectiveLifecyclePath) | Out-Null
  [ordered]@{
    rule = @(
      [ordered]@{
        action = [ordered]@{ type = "Delete" }
        condition = [ordered]@{
          age = 14
          matchesPrefix = @("temporary/photo-imports/") + $legacyPrefixes
        }
      }
    )
  } | ConvertTo-Json -Depth 10 | Set-Content -Path $effectiveLifecyclePath
  Write-Host "$bucket lifecycle includes the isolated current prefix and $($legacyPrefixes.Count) legacy photo-import batch prefixes."
  & $GcloudCommand storage buckets update $bucket "--lifecycle-file=$effectiveLifecyclePath" --quiet
  if ($LASTEXITCODE -ne 0) { throw "Could not update lifecycle policy for $bucket." }
}

& $GcloudCommand billing budgets update $budgetId `
  "--billing-account=$BillingAccount" `
  --credit-types-treatment=exclude-all-credits `
  --quiet
if ($LASTEXITCODE -ne 0) { throw "Could not change budget credit treatment." }

if ($DestroySupersededSecretVersions) {
  $secretCutoff = (Get-Date).ToUniversalTime().AddDays(-$MinimumSupersededSecretAgeDays)
  $secrets = & $GcloudCommand secrets list "--project=$ProjectId" --format="value(name)"
  foreach ($secret in $secrets) {
    $rows = @(& $GcloudCommand secrets versions list $secret "--project=$ProjectId" `
      --filter="state=enabled" --sort-by="~createTime" --format="csv[no-heading](name,createTime)")
    foreach ($row in @($rows | Select-Object -Skip 1)) {
      $fields = $row -split ',', 2
      $version = $fields[0]
      $created = ([datetime]$fields[1]).ToUniversalTime()
      if ($created -gt $secretCutoff) {
        Write-Host "Keeping recent superseded version $version of $secret until it is $MinimumSupersededSecretAgeDays days old."
        continue
      }
      & $GcloudCommand secrets versions destroy $version --secret=$secret "--project=$ProjectId" --quiet
      if ($LASTEXITCODE -ne 0) { throw "Could not destroy superseded version $version of $secret." }
    }
  }
}

Write-Host "Applied GCP cost controls. Permanent student photo keys are outside the lifecycle prefix."
