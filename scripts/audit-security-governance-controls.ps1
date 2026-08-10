param(
  [string]$ReadinessAudit = "scripts/audit-security-governance-readiness.ps1",
  [string]$GovernanceConfigurator = "scripts/configure-security-governance-controls.ps1",
  [string]$RuntimeConfigurator = "scripts/configure-runtime-service-accounts.ps1",
  [string]$ReportingConfigurator = "scripts/configure-reporting-pubsub-push-oidc.ps1",
  [string]$DependabotConfig = ".github/dependabot.yml",
  [string]$CodeQlWorkflow = ".github/workflows/codeql-analysis.yml",
  [string]$ContainerWorkflow = ".github/workflows/security-scan.yml"
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$paths = @($ReadinessAudit, $GovernanceConfigurator, $RuntimeConfigurator,
  $ReportingConfigurator, $DependabotConfig, $CodeQlWorkflow, $ContainerWorkflow)
$contents = @{}
foreach ($relative in $paths) {
  $path = Join-Path $repoRoot $relative
  if (-not (Test-Path -LiteralPath $path)) {
    throw "Missing security governance control: $relative"
  }
  $contents[$relative] = Get-Content -Raw -LiteralPath $path
}

$violations = New-Object System.Collections.Generic.List[string]
function Require-Text {
  param([string]$File, [string[]]$Required)
  foreach ($needle in $Required) {
    if (-not $contents[$File].Contains($needle)) {
      $violations.Add("$File missing required control: $needle") | Out-Null
    }
  }
}

Require-Text $ReadinessAudit @(
  "get-iam-policy",
  "workload-identity-pools providers describe",
  "ims-reporting-service-push-prod",
  "hasQueryString",
  "code-scanning/alerts",
  "withoutRotationSchedule",
  "cloudArmorPolicyCount",
  "Cloud Deploy targets still use default compute"
)
if ($contents[$ReadinessAudit].Contains("secrets versions access")) {
  $violations.Add("Readiness audit must not access secret payloads.") | Out-Null
}

Require-Text $GovernanceConfigurator @(
  "AllowExternalMutation",
  "ApplyGitHub",
  "ApplyWorkloadIdentity",
  "repository_id",
  "repository_owner_id",
  "workflow_ref",
  "refs/heads/main",
  "refs/heads/dev",
  "required_approving_review_count",
  "analyze (java-kotlin)",
  "analyze (javascript-typescript)",
  "required_conversation_resolution"
)

foreach ($file in @($RuntimeConfigurator, $ReportingConfigurator)) {
  Require-Text $file @(
    'if ($Apply -and $Environment -eq "prod" -and -not $AllowProduction)',
    "Dry run only"
  )
}

Require-Text $DependabotConfig @("package-ecosystem: npm", "package-ecosystem: maven", "package-ecosystem: docker")
Require-Text $CodeQlWorkflow @("java-kotlin", "javascript-typescript", "security-extended", "github/codeql-action/analyze@v4")
Require-Text $ContainerWorkflow @("Trivy HIGH/CRITICAL gate", "ignore-unfixed: false", 'exit-code: "1"')

foreach ($script in @($ReadinessAudit, $GovernanceConfigurator, $RuntimeConfigurator, $ReportingConfigurator)) {
  $tokens = $null
  $parseErrors = $null
  [void][System.Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $repoRoot $script), [ref]$tokens, [ref]$parseErrors)
  if (@($parseErrors).Count -gt 0) {
    foreach ($parseError in @($parseErrors)) {
      $violations.Add("$script parse error: $($parseError.Message)") | Out-Null
    }
  }
}

if ($violations.Count -gt 0) {
  Write-Host "Security governance control violations:"
  $violations | ForEach-Object { Write-Host "  $_" }
  exit 1
}

Write-Host "Security governance controls passed: guarded mutations, read-only evidence, WIF constraints, dependency coverage, CodeQL, and HIGH/CRITICAL container gates are present."
