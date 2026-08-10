param(
  [string]$ProjectId = "custoking",
  [string]$Repository = "custokingkr-dev/ims-v1",
  [string]$WorkloadIdentityPool = "github-pool",
  [string]$WorkloadIdentityProvider = "github-provider",
  [string[]]$RequiredStatusChecks = @(
    "summary",
    "analyze (java-kotlin)",
    "analyze (javascript-typescript)"
  ),
  [switch]$ApplyGitHub,
  [switch]$ApplyWorkloadIdentity,
  [switch]$AllowExternalMutation
)

$ErrorActionPreference = "Stop"
$GcloudCommand = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }

if (($ApplyGitHub -or $ApplyWorkloadIdentity) -and -not $AllowExternalMutation) {
  throw "External governance changes require -AllowExternalMutation in addition to an explicit apply switch."
}

function Invoke-Checked {
  param([string]$Command, [string[]]$Arguments, [string]$InputJson = "")
  if ([string]::IsNullOrWhiteSpace($InputJson)) {
    & $Command @Arguments
  } else {
    $InputJson | & $Command @Arguments
  }
  if ($LASTEXITCODE -ne 0) {
    throw "$Command failed: $($Arguments -join ' ')"
  }
}

$repoJson = (& gh api "repos/$Repository") -join "`n"
if ($LASTEXITCODE -ne 0) { throw "Could not read repository metadata for $Repository." }
$repo = $repoJson | ConvertFrom-Json
$repositoryId = [string]$repo.id
$ownerId = [string]$repo.owner.id

$allowedWorkflowRefs = @(
  "$Repository/.github/workflows/build-release.yml@refs/heads/dev",
  "$Repository/.github/workflows/build-release.yml@refs/heads/main",
  "$Repository/.github/workflows/rollback.yml@refs/heads/dev",
  "$Repository/.github/workflows/rollback.yml@refs/heads/main",
  "$Repository/.github/workflows/gcp-cost-controls.yml@refs/heads/main",
  "$Repository/.github/workflows/recovery-drill.yml@refs/heads/main"
)
$workflowExpression = ($allowedWorkflowRefs | ForEach-Object { "assertion.workflow_ref=='$_'" }) -join " || "
$condition = "assertion.repository_id=='$repositoryId' && assertion.repository_owner_id=='$ownerId' && (assertion.ref=='refs/heads/dev' || assertion.ref=='refs/heads/main') && ($workflowExpression)"
$attributeMapping = @(
  "google.subject=assertion.sub",
  "attribute.repository=assertion.repository",
  "attribute.repository_id=assertion.repository_id",
  "attribute.repository_owner_id=assertion.repository_owner_id",
  "attribute.ref=assertion.ref",
  "attribute.workflow_ref=assertion.workflow_ref"
) -join ","

$protection = [ordered]@{
  required_status_checks = [ordered]@{ strict = $true; contexts = @($RequiredStatusChecks) }
  enforce_admins = $true
  required_pull_request_reviews = [ordered]@{
    dismiss_stale_reviews = $true
    require_code_owner_reviews = $false
    required_approving_review_count = 1
    require_last_push_approval = $true
  }
  restrictions = $null
  required_linear_history = $false
  allow_force_pushes = $false
  allow_deletions = $false
  block_creations = $false
  required_conversation_resolution = $true
  lock_branch = $false
  allow_fork_syncing = $false
}

$plan = [ordered]@{
  repository = $Repository
  repositoryId = $repositoryId
  ownerId = $ownerId
  branchProtection = [ordered]@{ branches = @("main", "dev"); requiredStatusChecks = @($RequiredStatusChecks) }
  devEnvironmentBranchPolicy = "dev only"
  workloadIdentity = [ordered]@{
    pool = $WorkloadIdentityPool
    provider = $WorkloadIdentityProvider
    condition = $condition
    allowedWorkflowRefs = $allowedWorkflowRefs
  }
  applyGitHub = [bool]$ApplyGitHub
  applyWorkloadIdentity = [bool]$ApplyWorkloadIdentity
}

if (-not $ApplyGitHub -and -not $ApplyWorkloadIdentity) {
  $plan | ConvertTo-Json -Depth 8
  Write-Host "Dry run only. No GitHub or Google Cloud settings were changed."
  exit 0
}

if ($ApplyWorkloadIdentity) {
  Invoke-Checked -Command $GcloudCommand -Arguments @(
    "iam", "workload-identity-pools", "providers", "update-oidc", $WorkloadIdentityProvider,
    "--project=$ProjectId", "--location=global", "--workload-identity-pool=$WorkloadIdentityPool",
    "--attribute-mapping=$attributeMapping", "--attribute-condition=$condition", "--quiet"
  )
}

if ($ApplyGitHub) {
  $protectionJson = $protection | ConvertTo-Json -Depth 8 -Compress
  foreach ($branch in @("main", "dev")) {
    Invoke-Checked -Command "gh" -Arguments @(
      "api", "--method", "PUT", "repos/$Repository/branches/$branch/protection", "--input", "-"
    ) -InputJson $protectionJson
  }

  Invoke-Checked -Command "gh" -Arguments @(
    "api", "--method", "PUT", "repos/$Repository/environments/dev",
    "-F", "deployment_branch_policy[protected_branches]=false",
    "-F", "deployment_branch_policy[custom_branch_policies]=true"
  )

  $existingPolicy = & gh api "repos/$Repository/environments/dev/deployment-branch-policies" 2>$null
  $policyExitCode = $LASTEXITCODE
  $global:LASTEXITCODE = 0
  $hasDevPolicy = $false
  if ($policyExitCode -eq 0) {
    $policyData = ($existingPolicy -join "`n") | ConvertFrom-Json
    $hasDevPolicy = @($policyData.branch_policies | Where-Object { $_.name -eq "dev" -and $_.type -eq "branch" }).Count -gt 0
  }
  if (-not $hasDevPolicy) {
    Invoke-Checked -Command "gh" -Arguments @(
      "api", "--method", "POST", "repos/$Repository/environments/dev/deployment-branch-policies",
      "-f", "name=dev", "-f", "type=branch"
    )
  }
}

$plan["applied"] = $true
$plan | ConvertTo-Json -Depth 8
