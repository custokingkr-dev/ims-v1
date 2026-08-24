param(
  [string]$ProjectId = $(if ($env:GCP_PROJECT_ID) { $env:GCP_PROJECT_ID } else { throw "ProjectId is required: pass -ProjectId explicitly or set GCP_PROJECT_ID. It used to default to the pre-split project, which is being deleted." }),
  [string]$Region = "asia-south2",
  [string]$Repository = "custokingkr-dev/ims-v1",
  [string]$WorkloadIdentityPool = "github-pool",
  [string]$WorkloadIdentityProvider = "github-provider",
  [int]$SecretAgeWarningDays = 30,
  [string]$OutputJson = "",
  [switch]$IncludeSecretVersionMetadata,
  [switch]$FailOnBlockers
)

$ErrorActionPreference = "Stop"
$GcloudCommand = if ($env:OS -eq "Windows_NT") { "gcloud.cmd" } else { "gcloud" }
$GhCommand = "gh"

function Invoke-NativeJson {
  param(
    [Parameter(Mandatory = $true)][string]$Command,
    [Parameter(Mandatory = $true)][string[]]$Arguments,
    [switch]$AllowFailure
  )

  $previousPreference = $ErrorActionPreference
  try {
    $ErrorActionPreference = "Continue"
    $output = & $Command @Arguments 2>$null
    $exitCode = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previousPreference
  }
  $global:LASTEXITCODE = 0
  if ($exitCode -ne 0) {
    if ($AllowFailure) {
      return $null
    }
    throw "$Command failed with exit code ${exitCode}: $($Arguments -join ' ')"
  }
  $text = ($output -join "`n").Trim()
  if ([string]::IsNullOrWhiteSpace($text)) {
    return $null
  }
  $parsed = $text | ConvertFrom-Json
  Write-Output $parsed
}

function Invoke-GcloudJson {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
  return Invoke-NativeJson -Command $GcloudCommand -Arguments @($Arguments + "--format=json")
}

function Invoke-GhApiJson {
  param(
    [Parameter(Mandatory = $true)][string]$Endpoint,
    [switch]$AllowFailure,
    [switch]$Paginate
  )
  $arguments = @("api")
  if ($Paginate) {
    $arguments += @("--paginate", "--slurp")
  }
  $arguments += $Endpoint
  return Invoke-NativeJson -Command $GhCommand -Arguments $arguments -AllowFailure:$AllowFailure
}

function Expand-PagedResults {
  param([object]$Pages)

  $items = New-Object System.Collections.Generic.List[object]
  foreach ($page in @($Pages)) {
    foreach ($item in @($page)) {
      $items.Add($item) | Out-Null
    }
  }
  return $items
}

function Get-CodeScanningSummary {
  param(
    [Parameter(Mandatory = $true)][string]$Ref,
    [Parameter(Mandatory = $true)][object[]]$Alerts
  )

  $severityCounts = [ordered]@{
    total = $Alerts.Count
    critical = @($Alerts | Where-Object { $_.rule.security_severity_level -eq "critical" }).Count
    high = @($Alerts | Where-Object { $_.rule.security_severity_level -eq "high" }).Count
    medium = @($Alerts | Where-Object { $_.rule.security_severity_level -eq "medium" }).Count
    low = @($Alerts | Where-Object { $_.rule.security_severity_level -eq "low" }).Count
    unknown = @($Alerts | Where-Object { [string]::IsNullOrWhiteSpace([string]$_.rule.security_severity_level) }).Count
  }

  $toolCategories = @($Alerts |
    Group-Object {
      "{0}|{1}|{2}" -f $_.tool.name, $_.most_recent_instance.category, $_.most_recent_instance.commit_sha
    } |
    Sort-Object Name |
    ForEach-Object {
      $sample = $_.Group[0]
      [ordered]@{
        tool = [string]$sample.tool.name
        category = [string]$sample.most_recent_instance.category
        commitSha = [string]$sample.most_recent_instance.commit_sha
        total = $_.Count
        critical = @($_.Group | Where-Object { $_.rule.security_severity_level -eq "critical" }).Count
        high = @($_.Group | Where-Object { $_.rule.security_severity_level -eq "high" }).Count
        medium = @($_.Group | Where-Object { $_.rule.security_severity_level -eq "medium" }).Count
        low = @($_.Group | Where-Object { $_.rule.security_severity_level -eq "low" }).Count
        unknown = @($_.Group | Where-Object { [string]::IsNullOrWhiteSpace([string]$_.rule.security_severity_level) }).Count
      }
    })

  return [ordered]@{
    ref = $Ref
    counts = $severityCounts
    toolCategories = $toolCategories
  }
}

function Get-ProjectRolesForMember {
  param([object]$Policy, [string]$Member)
  return @($Policy.bindings |
      Where-Object { @($_.members) -contains $Member } |
      ForEach-Object { [string]$_.role } |
      Sort-Object -Unique)
}

function Get-CloudRunInvokers {
  param([string]$ServiceName)
  $policy = Invoke-GcloudJson run services get-iam-policy $ServiceName `
    "--project=$ProjectId" "--region=$Region"
  return @($policy.bindings |
      Where-Object { $_.role -eq "roles/run.invoker" } |
      ForEach-Object { @($_.members) } |
      Sort-Object -Unique)
}

$project = Invoke-GcloudJson projects describe $ProjectId
$projectNumber = [string]$project.projectNumber
$defaultComputeEmail = "$projectNumber-compute@developer.gserviceaccount.com"
$defaultComputeMember = "serviceAccount:$defaultComputeEmail"
$projectPolicy = Invoke-GcloudJson projects get-iam-policy $ProjectId
$wif = Invoke-GcloudJson iam workload-identity-pools providers describe $WorkloadIdentityProvider `
  "--project=$ProjectId" --location=global "--workload-identity-pool=$WorkloadIdentityPool"

$runServices = @(Invoke-GcloudJson run services list --platform=managed `
    "--project=$ProjectId" "--region=$Region")
$prodServices = @($runServices | Where-Object { $_.metadata.name -match "-prod$" })
$prodRun = @($prodServices | ForEach-Object {
    $serviceName = [string]$_.metadata.name
    [ordered]@{
      service = $serviceName
      runtimeServiceAccount = [string]$_.spec.template.spec.serviceAccountName
      ingress = [string]$_.metadata.annotations.'run.googleapis.com/ingress'
      defaultUrlDisabled = [string]$_.metadata.annotations.'run.googleapis.com/default-url-disabled' -eq "true"
      invokers = @(Get-CloudRunInvokers -ServiceName $serviceName)
    }
  })

$runJobs = @(Invoke-GcloudJson run jobs list "--project=$ProjectId" "--region=$Region")
$jobIdentities = @($runJobs | ForEach-Object {
    [ordered]@{
      job = [string]$_.metadata.name
      runtimeServiceAccount = [string]$_.spec.template.spec.template.spec.serviceAccountName
    }
  })

$reportingSubscription = Invoke-GcloudJson pubsub subscriptions describe `
  ims-reporting-service-push-prod "--project=$ProjectId"
$pushEndpoint = [uri][string]$reportingSubscription.pushConfig.pushEndpoint
$reporting = [ordered]@{
  subscription = "ims-reporting-service-push-prod"
  hasQueryString = -not [string]::IsNullOrWhiteSpace($pushEndpoint.Query)
  oidcServiceAccount = [string]$reportingSubscription.pushConfig.oidcToken.serviceAccountEmail
  audienceMatchesServiceUrl = [string]$reportingSubscription.pushConfig.oidcToken.audience -eq `
    [string](($prodServices | Where-Object { $_.metadata.name -eq "custoking-platform-service-prod" }).status.url)
}

$token = (& $GcloudCommand auth print-access-token 2>$null | Select-Object -First 1).Trim()
if ([string]::IsNullOrWhiteSpace($token)) {
  throw "Could not obtain a short-lived access token for read-only Cloud Deploy inventory."
}
$headers = @{ Authorization = "Bearer $token" }
$targetUri = "https://clouddeploy.googleapis.com/v1/projects/$ProjectId/locations/$Region/targets?pageSize=100"
$targetResponse = Invoke-RestMethod -Method Get -Headers $headers -Uri $targetUri
$deployTargets = @($targetResponse.targets | ForEach-Object {
    [ordered]@{
      target = [string]$_.targetId
      executionServiceAccounts = @($_.executionConfigs | ForEach-Object { [string]$_.serviceAccount } | Sort-Object -Unique)
    }
  })

$secrets = @(Invoke-GcloudJson secrets list "--project=$ProjectId")
$secretMetadata = @($secrets | ForEach-Object {
    [ordered]@{
      name = ([string]$_.name -replace '^.*/', '')
      createTime = [string]$_.createTime
      hasRotationSchedule = -not [string]::IsNullOrWhiteSpace([string]$_.rotation.rotationPeriod) -and
        -not [string]::IsNullOrWhiteSpace([string]$_.rotation.nextRotationTime)
      latestEnabledVersion = $null
      latestEnabledCreateTime = $null
      latestEnabledAgeDays = $null
    }
  })

if ($IncludeSecretVersionMetadata) {
  foreach ($secret in $secretMetadata) {
    $encodedName = [uri]::EscapeDataString([string]$secret.name)
    $versionUri = "https://secretmanager.googleapis.com/v1/projects/$ProjectId/secrets/$encodedName/versions?pageSize=100&filter=state%3AENABLED"
    $versionResponse = Invoke-RestMethod -Method Get -Headers $headers -Uri $versionUri
    $latest = @($versionResponse.versions | Sort-Object { [datetime]$_.createTime } -Descending | Select-Object -First 1)
    if ($latest.Count -gt 0) {
      $created = [datetime]$latest[0].createTime
      $secret.latestEnabledVersion = [string]$latest[0].name -replace '^.*/', ''
      $secret.latestEnabledCreateTime = $created.ToUniversalTime().ToString("o")
      $secret.latestEnabledAgeDays = [math]::Floor(((Get-Date).ToUniversalTime() - $created.ToUniversalTime()).TotalDays)
    }
  }
}
$token = $null
$headers = $null

$securityPolicies = @(Invoke-GcloudJson compute security-policies list "--project=$ProjectId")
$urlMaps = @(Invoke-GcloudJson compute url-maps list "--project=$ProjectId")
$backendServices = @(Invoke-GcloudJson compute backend-services list "--project=$ProjectId")
$serverlessNegs = @(Invoke-GcloudJson compute network-endpoint-groups list "--project=$ProjectId")

$repo = Invoke-GhApiJson -Endpoint "repos/$Repository"
$rulesets = @(Invoke-GhApiJson -Endpoint "repos/$Repository/rulesets" |
  Where-Object { $null -ne $_ -and $null -ne $_.id })
$mainProtection = Invoke-GhApiJson -Endpoint "repos/$Repository/branches/main/protection" -AllowFailure
$devProtection = Invoke-GhApiJson -Endpoint "repos/$Repository/branches/dev/protection" -AllowFailure
$environments = Invoke-GhApiJson -Endpoint "repos/$Repository/environments"
$prodBranchPolicies = Invoke-GhApiJson -Endpoint `
  "repos/$Repository/environments/prod/deployment-branch-policies" -AllowFailure
$devBranchPolicies = Invoke-GhApiJson -Endpoint `
  "repos/$Repository/environments/dev/deployment-branch-policies" -AllowFailure

$defaultRef = "refs/heads/$([string]$repo.default_branch)"
$devRef = "refs/heads/dev"
$defaultRefEncoded = [uri]::EscapeDataString($defaultRef)
$devRefEncoded = [uri]::EscapeDataString($devRef)
$defaultCodeAlerts = @(Expand-PagedResults (Invoke-GhApiJson -Endpoint `
      "repos/$Repository/code-scanning/alerts?state=open&ref=$defaultRefEncoded&per_page=100" -Paginate -AllowFailure))
$devCodeAlerts = @(Expand-PagedResults (Invoke-GhApiJson -Endpoint `
      "repos/$Repository/code-scanning/alerts?state=open&ref=$devRefEncoded&per_page=100" -Paginate -AllowFailure))
$defaultCodeScanning = Get-CodeScanningSummary -Ref $defaultRef -Alerts $defaultCodeAlerts
$devCodeScanning = Get-CodeScanningSummary -Ref $devRef -Alerts $devCodeAlerts
$codeAlertCounts = $defaultCodeScanning.counts

$dependabotAccess = Invoke-GhApiJson -Endpoint `
  "repos/$Repository/dependabot/alerts?state=open&per_page=1" -AllowFailure
$secretScanningAccess = Invoke-GhApiJson -Endpoint `
  "repos/$Repository/secret-scanning/alerts?state=open&per_page=1" -AllowFailure

$forbiddenDefaultRoles = @(
  "roles/artifactregistry.writer",
  "roles/cloudbuild.builds.builder",
  "roles/iam.serviceAccountUser",
  "roles/run.admin",
  "roles/secretmanager.secretAccessor"
)
$defaultComputeRoles = @(Get-ProjectRolesForMember -Policy $projectPolicy -Member $defaultComputeMember)
$githubDeployMember = "serviceAccount:github-actions-sa@$ProjectId.iam.gserviceaccount.com"
$githubDeployRoles = @(Get-ProjectRolesForMember -Policy $projectPolicy -Member $githubDeployMember)
$wifCondition = [string]$wif.attributeCondition
$runtimeOnDefault = @($prodRun | Where-Object { $_.runtimeServiceAccount -eq $defaultComputeEmail })
$jobsOnDefault = @($jobIdentities | Where-Object { $_.runtimeServiceAccount -eq $defaultComputeEmail })
$targetsOnDefault = @($deployTargets | Where-Object { @($_.executionServiceAccounts) -contains $defaultComputeEmail })
$blockers = New-Object System.Collections.Generic.List[string]
if ($runtimeOnDefault.Count -gt 0) { $blockers.Add("production Cloud Run services still use default compute") | Out-Null }
if ($jobsOnDefault.Count -gt 0) { $blockers.Add("Cloud Run jobs still use default compute") | Out-Null }
if ($targetsOnDefault.Count -gt 0) { $blockers.Add("Cloud Deploy targets still use default compute for execution") | Out-Null }
if ($reporting.hasQueryString) { $blockers.Add("production reporting push endpoint still contains a query credential") | Out-Null }
if ($reporting.oidcServiceAccount -eq $defaultComputeEmail) { $blockers.Add("production reporting push still uses default compute OIDC identity") | Out-Null }
if ($wifCondition -notmatch "repository_id" -or $wifCondition -notmatch "workflow_ref" -or $wifCondition -notmatch "assertion.ref") {
  $blockers.Add("WIF trust is not restricted by immutable repository id, workflow and ref") | Out-Null
}
if ($null -eq $mainProtection -and $rulesets.Count -eq 0) { $blockers.Add("main has no visible branch protection or ruleset") | Out-Null }
if ($null -eq $devProtection -and $rulesets.Count -eq 0) { $blockers.Add("dev has no visible branch protection or ruleset") | Out-Null }
if ([int]$defaultCodeScanning.counts.critical -gt 0 -or [int]$defaultCodeScanning.counts.high -gt 0) {
  $blockers.Add("GitHub code scanning has open HIGH or CRITICAL alerts on $defaultRef") | Out-Null
}
if ([int]$devCodeScanning.counts.critical -gt 0 -or [int]$devCodeScanning.counts.high -gt 0) {
  $blockers.Add("GitHub code scanning has open HIGH or CRITICAL alerts on $devRef") | Out-Null
}

$result = [ordered]@{
  generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
  project = [ordered]@{ id = $ProjectId; number = $projectNumber; region = $Region }
  repository = [ordered]@{
    name = $Repository
    id = [string]$repo.id
    ownerId = [string]$repo.owner.id
    visibility = [string]$repo.visibility
    rulesetCount = $rulesets.Count
    mainProtectionVisible = $null -ne $mainProtection
    devProtectionVisible = $null -ne $devProtection
    environments = @($environments.environments | ForEach-Object { [string]$_.name })
    prodDeploymentBranches = @($prodBranchPolicies.branch_policies | ForEach-Object { [string]$_.name })
    devDeploymentBranches = @($devBranchPolicies.branch_policies | ForEach-Object { [string]$_.name })
    codeScanningOpen = $codeAlertCounts
    codeScanningOpenByRef = [ordered]@{
      default = $defaultCodeScanning
      dev = $devCodeScanning
    }
    dependabotAlertsApiReadable = $null -ne $dependabotAccess
    secretScanningAlertsApiReadable = $null -ne $secretScanningAccess
  }
  workloadIdentity = [ordered]@{
    state = [string]$wif.state
    condition = $wifCondition
    attributeMapping = $wif.attributeMapping
  }
  iam = [ordered]@{
    defaultCompute = [ordered]@{
      email = $defaultComputeEmail
      projectRoles = $defaultComputeRoles
      forbiddenRolesPresent = @($defaultComputeRoles | Where-Object { $forbiddenDefaultRoles -contains $_ })
    }
    githubDeploy = [ordered]@{ email = $githubDeployMember -replace '^serviceAccount:', ''; projectRoles = $githubDeployRoles }
  }
  productionCloudRun = $prodRun
  cloudRunJobs = $jobIdentities
  cloudDeployTargets = $deployTargets
  reportingPush = $reporting
  secrets = [ordered]@{
    count = $secretMetadata.Count
    withoutRotationSchedule = @($secretMetadata | Where-Object { -not $_.hasRotationSchedule }).Count
    overAgeWarning = @($secretMetadata | Where-Object { $null -ne $_.latestEnabledAgeDays -and $_.latestEnabledAgeDays -gt $SecretAgeWarningDays }).Count
    metadata = $secretMetadata
  }
  ingress = [ordered]@{
    cloudArmorPolicyCount = $securityPolicies.Count
    urlMapCount = $urlMaps.Count
    backendServiceCount = $backendServices.Count
    serverlessNegCount = @($serverlessNegs | Where-Object { $_.networkEndpointType -eq "SERVERLESS" }).Count
  }
  blockers = @($blockers)
  blockerCount = $blockers.Count
}

$json = $result | ConvertTo-Json -Depth 12
if (-not [string]::IsNullOrWhiteSpace($OutputJson)) {
  $parent = Split-Path -Parent $OutputJson
  if (-not [string]::IsNullOrWhiteSpace($parent)) {
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
  }
  $json | Set-Content -LiteralPath $OutputJson -Encoding UTF8
}
$json

if ($FailOnBlockers -and $blockers.Count -gt 0) {
  exit 1
}
