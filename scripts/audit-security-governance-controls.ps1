param(
  [string]$ReadinessAudit = "scripts/audit-security-governance-readiness.ps1",
  [string]$GovernanceConfigurator = "scripts/configure-security-governance-controls.ps1",
  [string]$RuntimeConfigurator = "scripts/configure-runtime-service-accounts.ps1",
  [string]$ReportingConfigurator = "scripts/configure-reporting-pubsub-push-oidc.ps1",
  [string]$SecretRotationAudit = "scripts/audit-secret-rotation-policy.ps1",
  [string]$SecretRotationPolicy = "deploy/gcp/secret-rotation-policy.json",
  [string]$RecoveryBucketRole = "deploy/gcp/recovery-bucket-iam-operator-role.yaml",
  [string]$CloudDeployDevTargets = "deploy/clouddeploy/targets-dev.yaml",
  [string]$CloudDeployProdTargets = "deploy/clouddeploy/targets-prod.yaml",
  [string]$CloudDeployRenderer = "scripts/render-clouddeploy-targets.ps1",
  [string]$CloudDeployPipelineRenderer = "scripts/render-clouddeploy-pipelines.ps1",
  [string]$AffectedResolver = "scripts/resolve-affected-ci-targets.ps1",
  [string]$BuildReleaseWorkflow = ".github/workflows/build-release.yml",
  [string]$DetectWorkflow = ".github/workflows/_detect-changes.yml",
  [string]$ConfigWorkflow = ".github/workflows/reconcile-deployment-config.yml",
  [string]$RollbackWorkflow = ".github/workflows/rollback.yml",
  [string]$RecoveryWorkflow = ".github/workflows/recovery-drill.yml",
  [string]$RestoreDrillScript = "scripts/invoke-cloudsql-restore-drill.ps1",
  [string]$CloudSqlTransportAudit = "scripts/audit-cloudsql-transport-security.ps1",
  [string]$CloudSqlTransportSql = "scripts/audit-cloudsql-transport-security.sql",
  [string]$CloudSqlTransportCapture = "scripts/capture-cloudsql-transport-evidence.ps1",
  [string]$CicdTerraform = "infra/terraform/cicd/main.tf",
  [string]$DependabotConfig = ".github/dependabot.yml",
  [string]$CodeQlWorkflow = ".github/workflows/codeql-analysis.yml",
  [string]$ContainerWorkflow = ".github/workflows/security-scan.yml"
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$paths = @($ReadinessAudit, $GovernanceConfigurator, $RuntimeConfigurator,
  $ReportingConfigurator, $SecretRotationAudit, $SecretRotationPolicy, $RecoveryBucketRole,
  $CloudDeployDevTargets, $CloudDeployProdTargets, $CloudDeployRenderer,
  $CloudDeployPipelineRenderer, $AffectedResolver,
  $BuildReleaseWorkflow, $DetectWorkflow, $ConfigWorkflow, $RollbackWorkflow,
  $RecoveryWorkflow, $RestoreDrillScript, $CloudSqlTransportAudit, $CloudSqlTransportSql,
  $CloudSqlTransportCapture,
  $CicdTerraform, $DependabotConfig, $CodeQlWorkflow,
  $ContainerWorkflow)
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
  "deny-wrong-repository-id",
  "deny-wrong-owner-id",
  "deny-feature-ref",
  "deny-unlisted-workflow",
  "deny-mismatched-ref-and-workflow-ref",
  "deny-prod-release-identity-from-dev",
  "deny-dev-release-identity-from-main",
  "deny-prod-rollback-identity-from-dev",
  "deny-dev-rollback-identity-from-main",
  "deny-prod-config-identity-from-dev",
  "deny-dev-config-identity-from-main",
  "deny-recovery-identity-from-dev",
  "refs/heads/main",
  "refs/heads/dev",
  'devEnvironmentBranchPolicy = @("main", "dev")',
  "required_approving_review_count",
  "analyze (java-kotlin)",
  "analyze (javascript-typescript)",
  "required_conversation_resolution"
)

Require-Text $SecretRotationAudit @(
  "payloadsAccessed = `$false",
  "unownedLiveSecrets",
  "policySecretsMissingLive",
  "FailOnInventoryDrift"
)
if ($contents[$SecretRotationAudit].Contains("secrets versions access")) {
  $violations.Add("Secret rotation audit must not access secret payloads.") | Out-Null
}
Require-Text $SecretRotationPolicy @(
  '"approvalState": "pending-security-owner"',
  '"intervalApproval": "proposed-defaults-pending-named-owner-and-security-approval"',
  '"notificationScheduleState": "blocked-until-reentrant-rotator-and-pubsub-subscriber-exist"',
  '"proposedRotationDays":',
  '"owner": "application-platform"',
  '"owner": "database-platform"',
  '"owner": "identity-security"',
  '"owner": "integration-platform"',
  '"owner": "security-operations"'
)
Require-Text $CicdTerraform @(
  "attribute.repository_id",
  "attribute.repository_owner_id",
  "attribute.workflow_ref",
  "attribute.workflow_file",
  'release_dev     = "github-release-dev"',
  'release_prod    = "github-release-prod"',
  'rollback_dev    = "github-rollback-dev"',
  'rollback_prod   = "github-rollback-prod"',
  'config_dev      = "github-config-dev"',
  'config_prod     = "github-config-prod"',
  'cost_controller = "github-cost-controller"',
  'recovery        = "custoking-recovery-operator"',
  'attribute.workflow_ref/${each.value.workflow_ref}',
  'roles/clouddeploy.operator',
  'google_project_iam_custom_role" "clouddeploy_config_reconciler',
  'custokingCloudDeployConfigReconciler',
  'clouddeploy.deliveryPipelines.create',
  'clouddeploy.deliveryPipelines.update',
  'clouddeploy.targets.create',
  'clouddeploy.targets.update',
  'release_builder_act_as_clouddeploy',
  'release_builder_act_as_dev_runtime',
  'rollback_prod_act_as_clouddeploy',
  'config_reconciler_roles',
  'config_reconciler_act_as_clouddeploy',
  'clouddeploy_image_reader',
  'release_prod_image_reader',
  'release_dev_image_writer',
  'recovery_bucket_policy_operator',
  'recovery_bucket_and_validation_prefix_only',
  'objects/recovery-drills/',
  "roles/clouddeploy.jobRunner",
  "runtime_service_account_bindings"
)
$configRoleMatch = [regex]::Match(
  $contents[$CicdTerraform],
  '(?s)resource\s+"google_project_iam_custom_role"\s+"clouddeploy_config_reconciler"\s*\{.*?permissions\s*=\s*\[(?<permissions>.*?)\]\s*\r?\n\}'
)
if (-not $configRoleMatch.Success) {
  $violations.Add("CI/CD Terraform must define the configuration-only Cloud Deploy custom role.") | Out-Null
} else {
  foreach ($forbiddenPrefix in @(
      "clouddeploy.releases.",
      "clouddeploy.rollouts.",
      "clouddeploy.deliveryPipelines.delete",
      "clouddeploy.targets.delete",
      "clouddeploy.deliveryPipelines.setIamPolicy",
      "clouddeploy.targets.setIamPolicy"
    )) {
    if ($configRoleMatch.Groups["permissions"].Value.Contains($forbiddenPrefix)) {
      $violations.Add("Configuration reconciler custom role must not include $forbiddenPrefix permissions.") | Out-Null
    }
  }
}
if ($contents[$CicdTerraform].Contains('github_principal_set = "principalSet://iam.googleapis.com/projects/${var.project_number}/locations/global/workloadIdentityPools/${google_iam_workload_identity_pool.github.workload_identity_pool_id}/attribute.repository/${var.github_repository}"')) {
  $violations.Add("CI/CD Terraform must not authorize the mutable repository-name principal set.") | Out-Null
}
Require-Text $RecoveryBucketRole @(
  "storage.buckets.getIamPolicy",
  "storage.buckets.setIamPolicy",
  "storage.objects.get",
  "storage.objects.delete"
)
if ($contents[$RecoveryBucketRole].Contains("storage.objects.restore") -or
    $contents[$RecoveryBucketRole].Contains("storage.objects.list") -or
    $contents[$RecoveryBucketRole].Contains("storage.objects.create")) {
  $violations.Add("Recovery bucket custom role must not list, create, or restore validation objects.") | Out-Null
}
Require-Text $RestoreDrillScript @(
  '[ValidateSet("dev", "prod")]',
  "AllowProductionRecoveryDrill",
  'SourceInstance -ne "custoking-db-prod"',
  "Production recovery cannot retain a failed clone",
  "Invoke-CloudSqlSchemaOnlyExport",
  "schemaOnly = `$true",
  '"--role=roles/storage.objectCreator"',
  "dataRowsValidated = `$false",
  "Test-GcloudResourceAbsent",
  '$evidence["status"] = "cleanup-failed"',
  '$evidence["cleanupConfirmed"] = $false',
  '$evidence["status"] = "PASSED"',
  '$evidence["cleanupConfirmed"] = $true',
  '$evidence["validationObjectRemoved"]',
  '$evidence["temporaryBucketIamRemoved"]',
  '$evidence["temporaryInstanceRemoved"]',
  '$allCleanupConfirmed = (',
  'if ($drillSucceeded -and -not $allCleanupConfirmed)'
)
if ($contents[$RestoreDrillScript].Contains('roles/storage.objectAdmin')) {
  $violations.Add("Recovery helper must grant the temporary clone only Storage Object Creator.") | Out-Null
}
$restoreFinallyIndex = $contents[$RestoreDrillScript].IndexOf("finally {")
$restorePassedIndex = $contents[$RestoreDrillScript].LastIndexOf('$evidence["status"] = "PASSED"')
if ($restoreFinallyIndex -lt 0 -or $restorePassedIndex -lt $restoreFinallyIndex) {
  $violations.Add("Recovery helper must not finalize PASSED evidence until after cleanup completes.") | Out-Null
}
Require-Text $RecoveryWorkflow @(
  "environment: prod",
  "-Environment prod",
  "-SourceInstance custoking-db-prod",
  "-AllowProductionRecoveryDrill"
)
Require-Text $CloudSqlTransportAudit @(
  '[ValidateSet("source", "dev", "prod")]',
  'sslMode',
  'ENCRYPTED_ONLY',
  'TRUSTED_CLIENT_CERTIFICATE_REQUIRED',
  'Get-JdbcSslMode',
  'PGSSLMODE=require',
  'existingJobReconciliationFiles',
  '--update-env-vars=PGSSLMODE=require',
  'unexpectedUpdateFlags',
  'reconcilesExistingJob',
  'PgStatSslEvidencePath',
  'EvidenceMaxAgeMinutes',
  'capturedAtUtc',
  'projectId',
  'unencryptedBackends',
  'ReportOnly'
)
Require-Text $CloudSqlTransportSql @(
  'pg_stat_ssl',
  'pg_backend_pid()',
  'a.datname = current_database()',
  "a.usename IS DISTINCT FROM 'cloudsqladmin'",
  'clientBackends',
  'encryptedBackends',
  'unencryptedBackends'
)
$normalizedTransportSql = $contents[$CloudSqlTransportSql] -replace "`r`n", "`n"
$transportSqlSha256 = [System.Security.Cryptography.SHA256]::Create()
try {
  $transportSqlBytes = [System.Text.Encoding]::UTF8.GetBytes($normalizedTransportSql)
  $expectedTransportSqlHash = ([System.BitConverter]::ToString(
      $transportSqlSha256.ComputeHash($transportSqlBytes)
    )).Replace("-", "").ToLowerInvariant()
} finally {
  $transportSqlSha256.Dispose()
}
Require-Text $CloudSqlTransportCapture @(
  '[ValidateSet("dev", "prod")]',
  '[string]$Environment = "dev"',
  'AllowProductionEvidenceCapture',
  'ConfirmProductionInstance',
  'captureRequestedAtUtc',
  'yyyyMMdd''T''HHmmssfff''Z''',
  'Refusing to replace existing Cloud SQL transport evidence',
  'direct child of the repository artifacts directory',
  'expectedQuerySha256',
  'Assert-ReviewedJobContract',
  'private VPC access',
  '--update-env-vars=PGSSLMODE=require',
  'IMS_CLOUDSQL_TLS_EVIDENCE_',
  'Expected exactly one marker-scoped aggregate result',
  'Aggregate evidence output contained missing or unexpected fields',
  'capturedAtUtc',
  'clientBackends',
  'encryptedBackends',
  'unencryptedBackends',
  'trap ''rm -f',
  'run jobs executions delete',
  'run jobs executions describe',
  'executionCleanupConfirmed',
  'Remove-Item -LiteralPath $temporaryOutput'
)
if (-not $contents[$CloudSqlTransportCapture].Contains($expectedTransportSqlHash)) {
  $violations.Add("Cloud SQL transport evidence capture must pin the exact normalized aggregate query checksum.") | Out-Null
}
if ($contents[$CloudSqlTransportCapture].Contains("run jobs create")) {
  $violations.Add("Cloud SQL transport evidence capture must use an existing private-VPC job and must not create one.") | Out-Null
}
if ($contents[$CloudSqlTransportCapture].Contains("secrets versions access")) {
  $violations.Add("Cloud SQL transport evidence capture must not access credential payloads.") | Out-Null
}
if ($contents[$CloudSqlTransportCapture] -match '(?m)^\s*Move-Item\b.*\s-Force(?:\s|$)') {
  $violations.Add("Cloud SQL transport evidence capture must never overwrite an existing evidence artifact.") | Out-Null
}
$captureProdGuardIndex = $contents[$CloudSqlTransportCapture].IndexOf('if ($Environment -eq "prod")')
$captureGcloudInitIndex = $contents[$CloudSqlTransportCapture].IndexOf('$gcloud =')
$captureOutputCollisionIndex = $contents[$CloudSqlTransportCapture].IndexOf('if (Test-Path -LiteralPath $outputPath)')
if ($captureProdGuardIndex -lt 0 -or $captureGcloudInitIndex -lt 0 -or
    $captureProdGuardIndex -gt $captureGcloudInitIndex) {
  $violations.Add("Cloud SQL transport evidence capture must enforce both production confirmations before cloud access.") | Out-Null
}
if ($captureOutputCollisionIndex -lt 0 -or $captureOutputCollisionIndex -gt $captureGcloudInitIndex) {
  $violations.Add("Cloud SQL transport evidence capture must reject an existing output before cloud access.") | Out-Null
}
$captureEvidenceMatch = [regex]::Match(
  $contents[$CloudSqlTransportCapture],
  '(?ms)^\s*\$evidence\s*=\s*\[ordered\]@\{(?<body>.*?)^\s*\}'
)
if (-not $captureEvidenceMatch.Success) {
  $violations.Add("Cloud SQL transport evidence capture must define an inspectable ordered evidence envelope.") | Out-Null
} else {
  $captureEvidenceKeys = @([regex]::Matches(
      $captureEvidenceMatch.Groups["body"].Value,
      '(?m)^\s*(?<key>[A-Za-z][A-Za-z0-9]*)\s*='
    ) | ForEach-Object { [string]$_.Groups["key"].Value } | Sort-Object)
  $expectedCaptureEvidenceKeys = @(
    "capturedAtUtc", "clientBackends", "encryptedBackends", "environment",
    "instance", "projectId", "unencryptedBackends"
  ) | Sort-Object
  if (($captureEvidenceKeys -join ",") -cne ($expectedCaptureEvidenceKeys -join ",")) {
    $violations.Add("Cloud SQL transport evidence envelope must contain only environment/project/instance/time and three aggregate counts.") | Out-Null
  }
}
if ($contents[$CicdTerraform].Contains('release_builder = toset(["build-release.yml", "gcp-cost-controls.yml"])')) {
  $violations.Add("Cost control must not share the release-builder workflow identity binding.") | Out-Null
}
if ($contents[$CicdTerraform].Contains('/attribute.workflow_file/${each.value.workflow}')) {
  $violations.Add("CI/CD service-account WIF members must use exact workflow_ref, not workflow-file-only scope.") | Out-Null
}
if ($contents[$CicdTerraform].Contains('stage = "clouddeploy-stage-deployer"') -or
    $contents[$CicdTerraform].Contains('"stage"')) {
  $violations.Add("CI/CD Terraform must not grant an unimplemented stage deployment identity.") | Out-Null
}

foreach ($targetContract in @(
    @{ File = $CloudDeployDevTargets; Environment = "dev" },
    @{ File = $CloudDeployProdTargets; Environment = "prod" }
  )) {
  $targetText = $contents[$targetContract.File]
  $targetCount = ([regex]::Matches($targetText, '(?m)^kind:\s*Target\s*$')).Count
  $expectedExecutionAccount = "clouddeploy-$($targetContract.Environment)-deployer@custoking.iam.gserviceaccount.com"
  $executionAccountCount = ([regex]::Matches(
      $targetText,
      "(?m)^\s*serviceAccount:\s*$([regex]::Escape($expectedExecutionAccount))\s*$"
    )).Count
  $runtimeAccounts = @([regex]::Matches($targetText, '(?m)^\s*runtime_service_account:\s*(\S+)\s*$') |
    ForEach-Object { $_.Groups[1].Value })
  if ($targetCount -ne 7 -or $executionAccountCount -ne $targetCount) {
    $violations.Add("$($targetContract.File) must wire all seven targets to $expectedExecutionAccount.") | Out-Null
  }
  if ($runtimeAccounts.Count -ne $targetCount -or @($runtimeAccounts | Where-Object {
        $_ -notmatch "-$($targetContract.Environment)@custoking\.iam\.gserviceaccount\.com$"
      }).Count -gt 0) {
    $violations.Add("$($targetContract.File) must use seven environment-specific runtime identities.") | Out-Null
  }
  if ($targetText.Contains("305630109861-compute@developer.gserviceaccount.com")) {
    $violations.Add("$($targetContract.File) must not use the default Compute Engine service account.") | Out-Null
  }
}

Require-Text $CloudDeployRenderer @(
  '[ValidateSet("dev", "prod")]',
  "expectedExecutionAccount",
  "environment-specific dedicated runtime identity"
)
Require-Text $CloudDeployPipelineRenderer @(
  '[ValidateSet("dev", "prod")]',
  "Expected exactly seven",
  "delivery pipelines",
  "targetId"
)
Require-Text $AffectedResolver @(
  "ChangedFilesOverride",
  "deployment_reconciliation_required"
)
Require-Text $DetectWorkflow @("deployment_reconciliation_required")
Require-Text $BuildReleaseWorkflow @(
  "configuration-reconciliation-required",
  "No image was built and no release was created",
  "Ops / Reconcile deployment configuration",
  "Resolve immutable release images",
  "id: images",
  '$immutableRef = "$registry/$($entry.image)@$digest"',
  '${scanKey}_ref=$immutableRef',
  "immutableRef = `$immutableRef",
  "Trivy exact-digest HIGH/CRITICAL gate",
  "Trivy exact-digest SARIF evidence",
  "aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25 # v0.36.0",
  "ignore-unfixed: false",
  'exit-code: "1"',
  "Enforce exact-digest Trivy gates and evidence",
  "exact-digest-scan.json",
  '@sha256:[0-9a-f]{64}$',
  "Get-Item -LiteralPath `$tablePath",
  "Get-Item -LiteralPath `$sarifPath",
  "No immutable release image was scanned. Deployment is blocked."
)
if ($contents[$BuildReleaseWorkflow].Contains("Apply changed deployment configuration") -or
    $contents[$BuildReleaseWorkflow].Contains("gcloud deploy apply") -or
    $contents[$BuildReleaseWorkflow].Contains("apply_deployment_config")) {
  $violations.Add("Build/release must not reconcile Cloud Deploy control-plane configuration.") | Out-Null
}
$buildReleaseContent = $contents[$BuildReleaseWorkflow]
$releaseScanOutputs = @("identity", "school_core", "operations", "platform", "billing", "frontend", "api_gateway")
$releaseImageRefs = @([regex]::Matches($buildReleaseContent, '(?m)^\s*image-ref:\s*(?<value>.+?)\s*$'))
if ($releaseImageRefs.Count -ne ($releaseScanOutputs.Count * 2)) {
  $violations.Add("Build/release must perform exactly one table gate and one SARIF scan for each of the seven release image outputs.") | Out-Null
}
foreach ($scanOutput in $releaseScanOutputs) {
  $expectedRef = '${{ steps.images.outputs.' + $scanOutput + '_ref }}'
  $matchingRefs = @($releaseImageRefs | Where-Object { $_.Groups["value"].Value.Trim() -eq $expectedRef })
  if ($matchingRefs.Count -ne 2) {
    $violations.Add("Build/release must scan the immutable '$scanOutput' output exactly twice (table and SARIF), never a tag or rebuilt substitute.") | Out-Null
  }
  $expectedDigestKey = '${{ steps.images.outputs.' + $scanOutput + '_digest_key }}'
  if (-not $buildReleaseContent.Contains($expectedDigestKey)) {
    $violations.Add("Build/release evidence is not keyed by the resolved '$scanOutput' digest.") | Out-Null
  }
}
$releaseTrivyPinCount = [regex]::Matches(
  $buildReleaseContent,
  '(?m)^\s*uses:\s*aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25\s+#\s+v0\.36\.0\s*$'
).Count
$releaseIgnoreUnfixedCount = [regex]::Matches($buildReleaseContent, '(?m)^\s*ignore-unfixed:\s*false\s*$').Count
$releaseContinueCount = [regex]::Matches($buildReleaseContent, '(?m)^\s*continue-on-error:\s*true\s*$').Count
if ($releaseTrivyPinCount -ne 14 -or $releaseIgnoreUnfixedCount -ne 14 -or $releaseContinueCount -ne 14) {
  $violations.Add("Build/release exact-digest scans must use 14 pinned Trivy invocations, include unfixed findings, and defer failure to the aggregate pre-deploy gate.") | Out-Null
}
$releaseTableCount = [regex]::Matches($buildReleaseContent, '(?m)^\s*output:\s*release-evidence/trivy/.+\.table\.txt\s*$').Count
$releaseSarifCount = [regex]::Matches($buildReleaseContent, '(?m)^\s*output:\s*release-evidence/trivy/.+\.sarif\s*$').Count
if ($releaseTableCount -ne 7 -or $releaseSarifCount -ne 7) {
  $violations.Add("Build/release must preserve seven digest-keyed table paths and seven digest-keyed SARIF paths.") | Out-Null
}
$releaseGateIndex = $buildReleaseContent.IndexOf("- name: Enforce exact-digest Trivy gates and evidence", [System.StringComparison]::Ordinal)
$devDeployIndex = $buildReleaseContent.IndexOf("- name: Fast dev deployment", [System.StringComparison]::Ordinal)
$prodDeployIndex = $buildReleaseContent.IndexOf("- name: Create and serially promote Cloud Deploy releases", [System.StringComparison]::Ordinal)
if ($releaseGateIndex -lt 0 -or $devDeployIndex -le $releaseGateIndex -or $prodDeployIndex -le $releaseGateIndex) {
  $violations.Add("The aggregate exact-digest Trivy gate must precede every deploy or promotion step.") | Out-Null
}
Require-Text $ConfigWorkflow @(
  "workflow_dispatch",
  "environment: `${{ inputs.environment }}",
  "Enforce configuration branch boundary",
  "refs/heads/dev",
  "refs/heads/main",
  "DEPLOYMENT_CONFIG_SERVICE_ACCOUNT",
  "render-clouddeploy-targets.ps1",
  "render-clouddeploy-pipelines.ps1",
  "gcloud deploy apply",
  "releases created: 0",
  "rollouts created: 0"
)
if ($contents[$ConfigWorkflow].Contains("gcloud deploy releases") -or
    $contents[$ConfigWorkflow].Contains("gcloud deploy rollouts")) {
  $violations.Add("Configuration reconciliation workflow must not create releases or rollouts.") | Out-Null
}
Require-Text $RollbackWorkflow @(
  "Enforce rollback branch boundary",
  "refs/heads/main",
  "refs/heads/dev"
)

foreach ($file in @($RuntimeConfigurator, $ReportingConfigurator)) {
  Require-Text $file @(
    'if ($Apply -and $Environment -eq "prod" -and -not $AllowProduction)',
    "Dry run only"
  )
}

Require-Text $DependabotConfig @("package-ecosystem: npm", "package-ecosystem: maven", "package-ecosystem: docker")
Require-Text $CodeQlWorkflow @(
  "java-kotlin",
  "javascript-typescript",
  "security-extended",
  "github/codeql-action/analyze@5595ccaf912efad79be6eef63a5619ff05969be3 # v4"
)
Require-Text $ContainerWorkflow @(
  "Trivy HIGH/CRITICAL gate",
  "aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25 # v0.36.0",
  "ignore-unfixed: false",
  'exit-code: "1"'
)

$approvedActionPins = @{
  "actions/checkout@v4"                 = "11d5960a326750d5838078e36cf38b85af677262"
  "actions/checkout@v7"                 = "3d3c42e5aac5ba805825da76410c181273ba90b1"
  "actions/setup-java@v5"               = "b6effb05e454b25005698d916606bdc6ffcbf961"
  "actions/setup-node@v7"               = "820762786026740c76f36085b0efc47a31fe5020"
  "actions/upload-artifact@v4"          = "ea165f8d65b6e75b540449e92b4886f43607fa02"
  "actions/upload-artifact@v7"          = "043fb46d1a93c77aae656e7c1c64a875d1fc6a0a"
  "aquasecurity/trivy-action@v0.36.0"    = "ed142fd0673e97e23eac54620cfb913e5ce36c25"
  "docker/build-push-action@v7"         = "53b7df96c91f9c12dcc8a07bcb9ccacbed38856a"
  "docker/login-action@v4"              = "dbcb813823bdd20940b903addbd779551569679f"
  "docker/setup-buildx-action@v4"       = "bb05f3f5519dd87d3ba754cc423b652a5edd6d2c"
  "github/codeql-action@v4"             = "5595ccaf912efad79be6eef63a5619ff05969be3"
  "gitleaks/gitleaks-action@v3"         = "e0c47f4f8be36e29cdc102c57e68cb5cbf0e8d1e"
  "google-github-actions/auth@v3"       = "7c6bc770dae815cd3e89ee6cdf493a5fab2cc093"
  "google-github-actions/setup-gcloud@v3" = "aa5489c8933f4cc7a4f7d45035b3b1440c9c10db"
}
$actionSourceFiles = @(
  Get-ChildItem -LiteralPath (Join-Path $repoRoot ".github/workflows") -File |
    Where-Object { $_.Extension -in @(".yml", ".yaml") }
)
$localActionsRoot = Join-Path $repoRoot ".github/actions"
if (Test-Path -LiteralPath $localActionsRoot) {
  $actionSourceFiles += @(Get-ChildItem -LiteralPath $localActionsRoot -Recurse -File |
      Where-Object { $_.Name -in @("action.yml", "action.yaml") })
}
foreach ($actionSourceFile in $actionSourceFiles) {
  foreach ($line in Get-Content -LiteralPath $actionSourceFile.FullName) {
    if ($line -notmatch '^\s*-?\s*uses:\s*(?<target>[^\s#]+)(?:\s+#\s*(?<version>v\S+))?\s*$') {
      continue
    }
    $target = [string]$Matches.target
    $version = [string]$Matches.version
    if ($target.StartsWith("./")) {
      continue
    }
    if ($target -notmatch '^(?<owner>[^/@]+)/(?<repository>[^/@/]+)(?:/[^@]+)?@(?<sha>[0-9a-f]{40})$') {
      $violations.Add("External action is not pinned to a 40-character commit SHA: $($actionSourceFile.Name): $target") | Out-Null
      continue
    }
    $owner = [string]$Matches.owner
    $repository = [string]$Matches.repository
    $sha = [string]$Matches.sha
    if ([string]::IsNullOrWhiteSpace($version)) {
      $violations.Add("Pinned external action lacks a readable version comment: $($actionSourceFile.Name): $target") | Out-Null
      continue
    }
    $pinKey = "$owner/$repository@$version"
    if (-not $approvedActionPins.ContainsKey($pinKey)) {
      $violations.Add("External action/version is not in the reviewed pin inventory: $pinKey") | Out-Null
      continue
    }
    if ($sha -ne [string]$approvedActionPins[$pinKey]) {
      $violations.Add("External action pin differs from the reviewed GitHub commit for ${pinKey}.") | Out-Null
    }
  }
}

foreach ($script in @($ReadinessAudit, $GovernanceConfigurator, $RuntimeConfigurator,
    $ReportingConfigurator, $SecretRotationAudit, $CloudDeployRenderer,
    $CloudDeployPipelineRenderer, $AffectedResolver,
    $RestoreDrillScript, $CloudSqlTransportAudit, $CloudSqlTransportCapture)) {
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

if ($violations.Count -eq 0) {
  $transportJson = & (Join-Path $repoRoot $CloudSqlTransportAudit) -Environment source -ReportOnly
  $transportResult = ($transportJson -join "`n") | ConvertFrom-Json
  if (-not [bool]$transportResult.compliant) {
    $violations.Add("Checked-in Cloud SQL clients do not all require encrypted transport: $(@($transportResult.violations) -join '; ')") | Out-Null
  }

  function Resolve-TestChangeSet([string[]]$ChangedFiles) {
    $json = & (Join-Path $repoRoot $AffectedResolver) `
      -Environment prod -ChangedFilesOverride $ChangedFiles
    return ($json -join "`n") | ConvertFrom-Json
  }

  $configOnly = Resolve-TestChangeSet @("deploy/clouddeploy/targets-prod.yaml")
  if ($configOnly.has_service_changes -or @($configOnly.service_matrix.include).Count -ne 0 -or
      -not $configOnly.deployment_reconciliation_required) {
    $violations.Add("Config-only target change must require reconciliation with a zero-service matrix.") | Out-Null
  }

  $configAndOneService = Resolve-TestChangeSet @(
    "deploy/clouddeploy/targets-prod.yaml",
    "services/school-core-service/src/main/java/example/OneServiceChange.java"
  )
  $selected = @($configAndOneService.service_matrix.include)
  if ($selected.Count -ne 1 -or [string]$selected[0].name -ne "school-core-service" -or
      -not $configAndOneService.deployment_reconciliation_required) {
    $violations.Add("Target plus one service change must retain exactly that one service and require reconciliation.") | Out-Null
  }

  $pipelineOnly = Resolve-TestChangeSet @("deploy/clouddeploy/delivery-pipelines.yaml")
  if ($pipelineOnly.has_service_changes -or @($pipelineOnly.service_matrix.include).Count -ne 0 -or
      -not $pipelineOnly.deployment_reconciliation_required) {
    $violations.Add("Pipeline-only change must require reconciliation with a zero-service matrix.") | Out-Null
  }

  $oneManifest = Resolve-TestChangeSet @("deploy/cloudrun/platform-service.yaml")
  $manifestSelection = @($oneManifest.service_matrix.include)
  if ($manifestSelection.Count -ne 1 -or [string]$manifestSelection[0].name -ne "platform-service" -or
      $oneManifest.deployment_reconciliation_required) {
    $violations.Add("One service manifest must select exactly that service without control-plane reconciliation.") | Out-Null
  }
}

if ($violations.Count -gt 0) {
  Write-Host "Security governance control violations:"
  $violations | ForEach-Object { Write-Host "  $_" }
  exit 1
}

Write-Host "Security governance controls passed: guarded mutations, exact branch-scoped WIF identities, dedicated Cloud Deploy/runtime accounts, schema-only production recovery, fail-closed Cloud SQL transport checks, dependency coverage, CodeQL, and HIGH/CRITICAL container gates are present."
