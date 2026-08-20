param(
  [string]$ProjectId = $(if ($env:GCP_PROJECT_ID) { $env:GCP_PROJECT_ID } else { throw "ProjectId is required: pass -ProjectId explicitly or set GCP_PROJECT_ID. It used to default to the pre-split project, which is being deleted." }),
  [string]$Repository = "custokingkr-dev/ims-v1",
  [string]$WorkloadIdentityPool = "github-pool",
  [string]$WorkloadIdentityProvider = "github-provider",
  [string[]]$RequiredStatusChecks = @(
    "summary",
    "analyze (java-kotlin)",
    "analyze (javascript-typescript)"
  ),
  [switch]$ApplyGitHub,
  [switch]$ApplyBranchProtection,
  [switch]$ApplyEnvironmentPolicy,
  [switch]$ApplyDependencyAlerts,
  [switch]$ApplyWorkloadIdentity,
  [switch]$AllowExternalMutation
)

$ErrorActionPreference = "Stop"
$GcloudCommand = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }

$applyBranchProtectionRequested = $ApplyGitHub -or $ApplyBranchProtection
$applyEnvironmentPolicyRequested = $ApplyGitHub -or $ApplyEnvironmentPolicy
$externalMutationRequested = $applyBranchProtectionRequested -or $applyEnvironmentPolicyRequested -or
  $ApplyDependencyAlerts -or $ApplyWorkloadIdentity

if ($externalMutationRequested -and -not $AllowExternalMutation) {
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

if (-not [bool]$repo.permissions.admin -and
    ($applyBranchProtectionRequested -or $applyEnvironmentPolicyRequested -or $ApplyDependencyAlerts)) {
  throw "The authenticated GitHub principal is not a repository administrator; requested GitHub settings cannot be applied."
}

$allowedWorkflowClaims = @(
  @{ ref = "refs/heads/dev"; workflow_ref = "$Repository/.github/workflows/build-release.yml@refs/heads/dev" },
  @{ ref = "refs/heads/main"; workflow_ref = "$Repository/.github/workflows/build-release.yml@refs/heads/main" },
  @{ ref = "refs/heads/dev"; workflow_ref = "$Repository/.github/workflows/rollback.yml@refs/heads/dev" },
  @{ ref = "refs/heads/main"; workflow_ref = "$Repository/.github/workflows/rollback.yml@refs/heads/main" },
  @{ ref = "refs/heads/dev"; workflow_ref = "$Repository/.github/workflows/reconcile-deployment-config.yml@refs/heads/dev" },
  @{ ref = "refs/heads/main"; workflow_ref = "$Repository/.github/workflows/reconcile-deployment-config.yml@refs/heads/main" },
  @{ ref = "refs/heads/main"; workflow_ref = "$Repository/.github/workflows/gcp-cost-controls.yml@refs/heads/main" },
  @{ ref = "refs/heads/main"; workflow_ref = "$Repository/.github/workflows/recovery-drill.yml@refs/heads/main" }
)
$allowedWorkflowRefs = @($allowedWorkflowClaims | ForEach-Object { $_.workflow_ref })
$serviceAccountWorkflowRefs = [ordered]@{
  release_dev = @("$Repository/.github/workflows/build-release.yml@refs/heads/dev")
  release_prod = @("$Repository/.github/workflows/build-release.yml@refs/heads/main")
  rollback_dev = @("$Repository/.github/workflows/rollback.yml@refs/heads/dev")
  rollback_prod = @("$Repository/.github/workflows/rollback.yml@refs/heads/main")
  config_dev = @("$Repository/.github/workflows/reconcile-deployment-config.yml@refs/heads/dev")
  config_prod = @("$Repository/.github/workflows/reconcile-deployment-config.yml@refs/heads/main")
  cost_controller = @("$Repository/.github/workflows/gcp-cost-controls.yml@refs/heads/main")
  recovery = @("$Repository/.github/workflows/recovery-drill.yml@refs/heads/main")
}
$allowedClaimPairs = @($allowedWorkflowClaims | ForEach-Object {
  "(assertion.ref=='$($_.ref)' && assertion.workflow_ref=='$($_.workflow_ref)')"
}) -join " || "
$condition = "assertion.repository_id=='$repositoryId' && assertion.repository_owner_id=='$ownerId' && ($allowedClaimPairs)"
$attributeMapping = @(
  "google.subject=assertion.sub",
  "attribute.repository=assertion.repository",
  "attribute.repository_id=assertion.repository_id",
  "attribute.repository_owner_id=assertion.repository_owner_id",
  "attribute.ref=assertion.ref",
  "attribute.workflow_file=assertion.workflow_ref.extract('/.github/workflows/{workflow_file}@')",
  "attribute.workflow_ref=assertion.workflow_ref"
) -join ","

function Test-ExpectedWorkloadIdentityClaim {
  param(
    [hashtable]$Claim,
    [bool]$Expected,
    [string]$Case
  )

  $claimPairAccepted = @($allowedWorkflowClaims | Where-Object {
    [string]$_.ref -eq [string]$Claim.ref -and
    [string]$_.workflow_ref -eq [string]$Claim.workflow_ref
  }).Count -gt 0
  $accepted = [string]$Claim.repository_id -eq $repositoryId -and
    [string]$Claim.repository_owner_id -eq $ownerId -and
    $claimPairAccepted
  if ($accepted -ne $Expected) {
    throw "WIF claim negative test '$Case' expected $Expected but evaluated $accepted."
  }
  return [ordered]@{ case = $Case; expected = $Expected; accepted = $accepted }
}

function Test-ExpectedServiceAccountScope {
  param(
    [string]$ServiceAccountScope,
    [string]$WorkflowRef,
    [bool]$Expected,
    [string]$Case
  )

  if (-not $serviceAccountWorkflowRefs.Contains($ServiceAccountScope)) {
    throw "Unknown service-account scope '$ServiceAccountScope'."
  }
  $accepted = @($serviceAccountWorkflowRefs[$ServiceAccountScope]) -contains $WorkflowRef
  if ($accepted -ne $Expected) {
    throw "WIF service-account scope test '$Case' expected $Expected but evaluated $accepted."
  }
  return [ordered]@{
    case = $Case
    serviceAccountScope = $ServiceAccountScope
    workflowRef = $WorkflowRef
    expected = $Expected
    accepted = $accepted
  }
}

$validBuildDev = @{
  repository_id = $repositoryId
  repository_owner_id = $ownerId
  ref = "refs/heads/dev"
  workflow_ref = "$Repository/.github/workflows/build-release.yml@refs/heads/dev"
}
$claimTests = @(
  Test-ExpectedWorkloadIdentityClaim -Claim $validBuildDev -Expected $true -Case "allow-build-release-dev"
  Test-ExpectedWorkloadIdentityClaim -Claim @{
    repository_id = $repositoryId; repository_owner_id = $ownerId; ref = "refs/heads/main"
    workflow_ref = "$Repository/.github/workflows/build-release.yml@refs/heads/main"
  } -Expected $true -Case "allow-build-release-main"
  Test-ExpectedWorkloadIdentityClaim -Claim @{
    repository_id = $repositoryId; repository_owner_id = $ownerId; ref = "refs/heads/main"
    workflow_ref = "$Repository/.github/workflows/gcp-cost-controls.yml@refs/heads/main"
  } -Expected $true -Case "allow-cost-control-main"
  Test-ExpectedWorkloadIdentityClaim -Claim @{
    repository_id = "999999999"; repository_owner_id = $ownerId; ref = $validBuildDev.ref
    workflow_ref = $validBuildDev.workflow_ref
  } -Expected $false -Case "deny-wrong-repository-id"
  Test-ExpectedWorkloadIdentityClaim -Claim @{
    repository_id = $repositoryId; repository_owner_id = "999999999"; ref = $validBuildDev.ref
    workflow_ref = $validBuildDev.workflow_ref
  } -Expected $false -Case "deny-wrong-owner-id"
  Test-ExpectedWorkloadIdentityClaim -Claim @{
    repository_id = $repositoryId; repository_owner_id = $ownerId; ref = "refs/heads/feature/untrusted"
    workflow_ref = $validBuildDev.workflow_ref
  } -Expected $false -Case "deny-feature-ref"
  Test-ExpectedWorkloadIdentityClaim -Claim @{
    repository_id = $repositoryId; repository_owner_id = $ownerId; ref = "refs/heads/dev"
    workflow_ref = "$Repository/.github/workflows/untrusted.yml@refs/heads/dev"
  } -Expected $false -Case "deny-unlisted-workflow"
  Test-ExpectedWorkloadIdentityClaim -Claim @{
    repository_id = $repositoryId; repository_owner_id = $ownerId; ref = "refs/heads/dev"
    workflow_ref = "$Repository/.github/workflows/gcp-cost-controls.yml@refs/heads/dev"
  } -Expected $false -Case "deny-cost-control-dev"
  Test-ExpectedWorkloadIdentityClaim -Claim @{
    repository_id = $repositoryId; repository_owner_id = $ownerId; ref = "refs/heads/dev"
    workflow_ref = "$Repository/.github/workflows/build-release.yml@refs/heads/main"
  } -Expected $false -Case "deny-mismatched-ref-and-workflow-ref"
)
$serviceAccountScopeTests = @(
  Test-ExpectedServiceAccountScope -ServiceAccountScope "release_dev" `
    -WorkflowRef "$Repository/.github/workflows/build-release.yml@refs/heads/dev" `
    -Expected $true -Case "allow-dev-release-identity-from-dev"
  Test-ExpectedServiceAccountScope -ServiceAccountScope "release_prod" `
    -WorkflowRef "$Repository/.github/workflows/build-release.yml@refs/heads/main" `
    -Expected $true -Case "allow-prod-release-identity-from-main"
  Test-ExpectedServiceAccountScope -ServiceAccountScope "release_prod" `
    -WorkflowRef "$Repository/.github/workflows/build-release.yml@refs/heads/dev" `
    -Expected $false -Case "deny-prod-release-identity-from-dev"
  Test-ExpectedServiceAccountScope -ServiceAccountScope "release_dev" `
    -WorkflowRef "$Repository/.github/workflows/build-release.yml@refs/heads/main" `
    -Expected $false -Case "deny-dev-release-identity-from-main"
  Test-ExpectedServiceAccountScope -ServiceAccountScope "rollback_prod" `
    -WorkflowRef "$Repository/.github/workflows/rollback.yml@refs/heads/dev" `
    -Expected $false -Case "deny-prod-rollback-identity-from-dev"
  Test-ExpectedServiceAccountScope -ServiceAccountScope "rollback_dev" `
    -WorkflowRef "$Repository/.github/workflows/rollback.yml@refs/heads/main" `
    -Expected $false -Case "deny-dev-rollback-identity-from-main"
  Test-ExpectedServiceAccountScope -ServiceAccountScope "config_dev" `
    -WorkflowRef "$Repository/.github/workflows/reconcile-deployment-config.yml@refs/heads/dev" `
    -Expected $true -Case "allow-dev-config-identity-from-dev"
  Test-ExpectedServiceAccountScope -ServiceAccountScope "config_prod" `
    -WorkflowRef "$Repository/.github/workflows/reconcile-deployment-config.yml@refs/heads/main" `
    -Expected $true -Case "allow-prod-config-identity-from-main"
  Test-ExpectedServiceAccountScope -ServiceAccountScope "config_prod" `
    -WorkflowRef "$Repository/.github/workflows/reconcile-deployment-config.yml@refs/heads/dev" `
    -Expected $false -Case "deny-prod-config-identity-from-dev"
  Test-ExpectedServiceAccountScope -ServiceAccountScope "config_dev" `
    -WorkflowRef "$Repository/.github/workflows/reconcile-deployment-config.yml@refs/heads/main" `
    -Expected $false -Case "deny-dev-config-identity-from-main"
  Test-ExpectedServiceAccountScope -ServiceAccountScope "cost_controller" `
    -WorkflowRef "$Repository/.github/workflows/gcp-cost-controls.yml@refs/heads/dev" `
    -Expected $false -Case "deny-cost-control-identity-from-dev"
  Test-ExpectedServiceAccountScope -ServiceAccountScope "recovery" `
    -WorkflowRef "$Repository/.github/workflows/recovery-drill.yml@refs/heads/dev" `
    -Expected $false -Case "deny-recovery-identity-from-dev"
)

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
  devEnvironmentBranchPolicy = @("dev")
  workloadIdentity = [ordered]@{
    pool = $WorkloadIdentityPool
    provider = $WorkloadIdentityProvider
    condition = $condition
    allowedWorkflowRefs = $allowedWorkflowRefs
    allowedWorkflowClaims = $allowedWorkflowClaims
    claimTests = $claimTests
    serviceAccountWorkflowRefs = $serviceAccountWorkflowRefs
    serviceAccountScopeTests = $serviceAccountScopeTests
  }
  applyBranchProtection = [bool]$applyBranchProtectionRequested
  applyEnvironmentPolicy = [bool]$applyEnvironmentPolicyRequested
  applyDependencyAlerts = [bool]$ApplyDependencyAlerts
  applyWorkloadIdentity = [bool]$ApplyWorkloadIdentity
}

if (-not $externalMutationRequested) {
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

  $providerJson = (& $GcloudCommand iam workload-identity-pools providers describe $WorkloadIdentityProvider `
    "--project=$ProjectId" --location=global "--workload-identity-pool=$WorkloadIdentityPool" --format=json) -join "`n"
  if ($LASTEXITCODE -ne 0) { throw "Could not verify the updated WIF provider." }
  $provider = $providerJson | ConvertFrom-Json
  if ([string]$provider.attributeCondition -ne $condition) {
    throw "Updated WIF provider condition does not match the reviewed condition."
  }
  foreach ($mapping in @("repository_id", "repository_owner_id", "ref", "workflow_file", "workflow_ref")) {
    if (-not $provider.attributeMapping.PSObject.Properties["attribute.$mapping"]) {
      throw "Updated WIF provider is missing attribute.$mapping."
    }
  }
}

if ($applyBranchProtectionRequested) {
  $protectionJson = $protection | ConvertTo-Json -Depth 8 -Compress
  foreach ($branch in @("main", "dev")) {
    Invoke-Checked -Command "gh" -Arguments @(
      "api", "--method", "PUT", "repos/$Repository/branches/$branch/protection", "--input", "-"
    ) -InputJson $protectionJson
  }

}

if ($applyEnvironmentPolicyRequested) {
  $environmentPolicy = [ordered]@{
    deployment_branch_policy = [ordered]@{
      protected_branches = $false
      custom_branch_policies = $true
    }
  } | ConvertTo-Json -Depth 4 -Compress
  Invoke-Checked -Command "gh" -Arguments @(
    "api", "--method", "PUT", "repos/$Repository/environments/dev", "--input", "-"
  ) -InputJson $environmentPolicy

  $existingPolicy = & gh api "repos/$Repository/environments/dev/deployment-branch-policies" 2>$null
  $policyExitCode = $LASTEXITCODE
  $global:LASTEXITCODE = 0
  $existingBranchPolicies = @()
  if ($policyExitCode -eq 0) {
    $policyData = ($existingPolicy -join "`n") | ConvertFrom-Json
    $existingBranchPolicies = @($policyData.branch_policies | Where-Object { $_.type -eq "branch" })
  }

  $devBranchPolicy = $existingBranchPolicies | Where-Object { $_.name -eq "dev" } | Select-Object -First 1
  if (-not $devBranchPolicy) {
    Invoke-Checked -Command "gh" -Arguments @(
      "api", "--method", "POST", "repos/$Repository/environments/dev/deployment-branch-policies",
      "-f", "name=dev", "-f", "type=branch"
    )
  }

  foreach ($unexpectedPolicy in @($existingBranchPolicies | Where-Object { $_.name -ne "dev" })) {
    Invoke-Checked -Command "gh" -Arguments @(
      "api", "--method", "DELETE",
      "repos/$Repository/environments/dev/deployment-branch-policies/$($unexpectedPolicy.id)"
    )
  }
}

if ($ApplyDependencyAlerts) {
  Invoke-Checked -Command "gh" -Arguments @("api", "--method", "PUT", "repos/$Repository/vulnerability-alerts")
  Invoke-Checked -Command "gh" -Arguments @("api", "--method", "PUT", "repos/$Repository/automated-security-fixes")
  Invoke-Checked -Command "gh" -Arguments @("api", "repos/$Repository/vulnerability-alerts")
  Invoke-Checked -Command "gh" -Arguments @("api", "repos/$Repository/automated-security-fixes")
}

$plan["applied"] = $true
$plan | ConvertTo-Json -Depth 8
