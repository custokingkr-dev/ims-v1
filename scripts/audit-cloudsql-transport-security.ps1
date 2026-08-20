param(
  [ValidateSet("source", "dev", "prod")]
  [string]$Environment = "source",
  [string]$ProjectId = $(if ($env:GCP_PROJECT_ID) { $env:GCP_PROJECT_ID } else { throw "ProjectId is required: pass -ProjectId explicitly or set GCP_PROJECT_ID. It used to default to the pre-split project, which is being deleted." }),
  [string]$Region = "asia-south2",
  [string]$PgStatSslEvidencePath = "",
  [ValidateRange(1, 1440)]
  [int]$EvidenceMaxAgeMinutes = 30,
  [string]$OutputJson = "",
  [switch]$ReportOnly,
  [string]$GcloudPath = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$violations = [System.Collections.Generic.List[string]]::new()
$sourceResults = [System.Collections.Generic.List[object]]::new()
$liveResults = [System.Collections.Generic.List[object]]::new()
$secureModes = @("require", "verify-ca", "verify-full")
$serviceNames = @(
  "identity-service",
  "school-core-service",
  "operations-service",
  "platform-service",
  "billing-service"
)
$jobConstructorFiles = @(
  "scripts/invoke-create-app-rt-role-cloudsql.ps1",
  "scripts/invoke-production-gateway-smoke.ps1",
  "scripts/invoke-scale-fixture.ps1",
  "scripts/audit-legacy-compatibility-cloudsql.ps1",
  "scripts/capture-cloudsql-transport-evidence.ps1"
)
$existingJobReconciliationFiles = @(
  "scripts/invoke-production-gateway-smoke.ps1",
  "scripts/invoke-scale-fixture.ps1",
  "scripts/capture-cloudsql-transport-evidence.ps1"
)

function Resolve-RepoPath([string]$RelativePath) {
  Join-Path $repoRoot $RelativePath
}

function Get-JdbcSslMode([string]$Url) {
  if ($Url -match '(?i)[?&]sslmode=([^&#]+)') {
    return $Matches[1].ToLowerInvariant()
  }
  return "prefer"
}

foreach ($serviceName in $serviceNames) {
  $relativePath = "deploy/cloudrun/$serviceName.yaml"
  $path = Resolve-RepoPath $relativePath
  if (-not (Test-Path -LiteralPath $path)) {
    $null = $violations.Add("Missing Cloud Run manifest: $relativePath")
    continue
  }
  $jdbcValueLines = @(Get-Content -LiteralPath $path | Where-Object {
      $_ -match '^\s*value:\s*jdbc:postgresql://'
    })
  $manifestJdbcUrls = @()
  foreach ($line in $jdbcValueLines) {
    if ($line -match '^\s*value:\s*(?<url>jdbc:postgresql://[^\s#]+)') {
      $manifestJdbcUrls += [string]$Matches.url
    }
    if ($line -match '#\s*from-param:\s*(?<url>jdbc:postgresql://\S+)') {
      $manifestJdbcUrls += [string]$Matches.url
    }
  }
  $secureUrls = @($manifestJdbcUrls | Where-Object {
      (Get-JdbcSslMode $_) -in $secureModes
    })
  $null = $sourceResults.Add([ordered]@{
      type = "cloud-run-manifest"
      resource = $serviceName
      jdbcUrlCount = $jdbcValueLines.Count
      renderedAndParameterizedUrlCount = $manifestJdbcUrls.Count
      encryptionRequiredCount = $secureUrls.Count
    })
  if ($jdbcValueLines.Count -ne 2 -or $manifestJdbcUrls.Count -ne 4 -or $secureUrls.Count -ne 4) {
    $null = $violations.Add("$relativePath must require TLS in both rendered and parameterized forms of exactly the runtime and Flyway JDBC URLs.")
  }
}

foreach ($relativePath in $jobConstructorFiles) {
  $path = Resolve-RepoPath $relativePath
  if (-not (Test-Path -LiteralPath $path)) {
    $null = $violations.Add("Missing Cloud SQL job constructor: $relativePath")
    continue
  }
  $text = Get-Content -Raw -LiteralPath $path
  $requiresTls = $text.Contains("PGSSLMODE=require")
  $mustReconcileExistingJob = $relativePath -in $existingJobReconciliationFiles
  $updateMatches = @([regex]::Matches(
      $text,
      '(?ims)&\s+\$Gcloud\s+run\s+jobs\s+update\b(?<block>.*?)(?=\r?\n\s*if\s*\(\$LASTEXITCODE)'
    ))
  $reconcilesExistingJob = $false
  if ($mustReconcileExistingJob -and $updateMatches.Count -eq 1) {
    $updateBlock = [string]$updateMatches[0].Groups["block"].Value
    $hasExactTlsMerge = $updateBlock -match '(?m)^\s*--update-env-vars=PGSSLMODE=require(?:\s*`)?\s*(?:\|\s*(?:Write-Output|Out-Null))?\s*$'
    $updateFlags = @([regex]::Matches($updateBlock, '(?m)^\s*(?<flag>--[a-z0-9-]+)(?:=|\s|`)') |
      ForEach-Object { [string]$_.Groups["flag"].Value } |
      Sort-Object -Unique)
    $unexpectedUpdateFlags = @($updateFlags | Where-Object {
        $_ -notin @("--project", "--region", "--update-env-vars")
      })
    $reconcilesExistingJob = $hasExactTlsMerge -and $unexpectedUpdateFlags.Count -eq 0
  }
  $null = $sourceResults.Add([ordered]@{
      type = "psql-job-constructor"
      resource = $relativePath
      pgsslmode = if ($requiresTls) { "require" } else { "missing-or-weaker" }
      reconcilesExistingJob = $reconcilesExistingJob
    })
  if (-not $requiresTls -or $text -match 'PGSSLMODE=(disable|allow|prefer)') {
    $null = $violations.Add("$relativePath must set PGSSLMODE=require or stronger and must not permit plaintext fallback.")
  }
  if ($mustReconcileExistingJob -and -not $reconcilesExistingJob) {
    $null = $violations.Add("$relativePath must reconcile an existing job with only --update-env-vars=PGSSLMODE=require.")
  }
}

$allScriptText = @(Get-ChildItem -LiteralPath (Resolve-RepoPath "scripts") -Filter "*.ps1" -File |
  ForEach-Object { Get-Content -Raw -LiteralPath $_.FullName }) -join "`n"
if ($allScriptText -match 'PGSSLMODE=(disable|allow|prefer)') {
  $null = $violations.Add("A checked-in PowerShell job path still permits plaintext or TLS fallback through PGSSLMODE.")
}

$serverSslMode = $null
$sessionEvidence = $null
$sessionEvidenceVerified = $false
if ($Environment -ne "source") {
  $gcloud = if (-not [string]::IsNullOrWhiteSpace($GcloudPath)) {
    $GcloudPath
  } elseif ($env:OS -eq "Windows_NT") {
    "gcloud.cmd"
  } else {
    "gcloud"
  }

  function Invoke-GcloudJson([string[]]$Arguments) {
    $json = @(& $gcloud @Arguments)
    if ($LASTEXITCODE -ne 0) {
      throw "Read-only gcloud inspection failed: gcloud $($Arguments -join ' ')"
    }
    return (($json -join "`n") | ConvertFrom-Json)
  }

  $instanceName = "custoking-db-$Environment"
  $instance = Invoke-GcloudJson @(
    "sql", "instances", "describe", $instanceName,
    "--project=$ProjectId", "--format=json"
  )
  $serverSslMode = [string]$instance.settings.ipConfiguration.sslMode
  $privateIpOnly = (
    -not [bool]$instance.settings.ipConfiguration.ipv4Enabled -and
    @($instance.ipAddresses | Where-Object { $_.type -eq "PRIVATE" }).Count -gt 0
  )
  $null = $liveResults.Add([ordered]@{
      type = "cloud-sql-instance"
      resource = $instanceName
      privateIpOnly = $privateIpOnly
      sslMode = $serverSslMode
      requireSsl = [bool]$instance.settings.ipConfiguration.requireSsl
      encryptedConnectionsEnforced = $serverSslMode -in @("ENCRYPTED_ONLY", "TRUSTED_CLIENT_CERTIFICATE_REQUIRED")
    })
  if ($serverSslMode -notin @("ENCRYPTED_ONLY", "TRUSTED_CLIENT_CERTIFICATE_REQUIRED")) {
    $null = $violations.Add("$instanceName does not reject unencrypted connections (sslMode=$serverSslMode).")
  }

  foreach ($serviceName in $serviceNames) {
    $resourceName = "custoking-$serviceName-$Environment"
    $service = Invoke-GcloudJson @(
      "run", "services", "describe", $resourceName,
      "--project=$ProjectId", "--region=$Region", "--format=json"
    )
    $environmentVariables = @{}
    foreach ($item in @($service.spec.template.spec.containers[0].env)) {
      if (-not $item.valueFrom) {
        $environmentVariables[[string]$item.name] = [string]$item.value
      }
    }
    $runtimeMode = Get-JdbcSslMode ([string]$environmentVariables["SPRING_DATASOURCE_URL"])
    $flywayMode = Get-JdbcSslMode ([string]$environmentVariables["FLYWAY_URL"])
    $null = $liveResults.Add([ordered]@{
        type = "cloud-run-service"
        resource = $resourceName
        runtimeSslMode = $runtimeMode
        flywaySslMode = $flywayMode
        encryptionRequired = $runtimeMode -in $secureModes -and $flywayMode -in $secureModes
      })
    if ($runtimeMode -notin $secureModes -or $flywayMode -notin $secureModes) {
      $null = $violations.Add("$resourceName does not require TLS for both runtime and Flyway JDBC connections.")
    }
  }

  $jobs = @(Invoke-GcloudJson @(
      "run", "jobs", "list",
      "--project=$ProjectId", "--region=$Region", "--format=json"
    ))
  foreach ($jobSummary in $jobs) {
    $jobName = [string]$jobSummary.metadata.name
    if ($jobName -notmatch "-$Environment(?:$|-)") {
      continue
    }
    $job = Invoke-GcloudJson @(
      "run", "jobs", "describe", $jobName,
      "--project=$ProjectId", "--region=$Region", "--format=json"
    )
    $container = $job.spec.template.spec.template.spec.containers[0]
    if ($null -eq $container) {
      $container = $job.spec.template.template.containers[0]
    }
    $containerEnvironment = @($container.env)
    $sslVariable = @($containerEnvironment | Where-Object { $_.name -eq "PGSSLMODE" } | Select-Object -First 1)
    $hasDatabaseEnvironment = @($containerEnvironment | Where-Object {
        [string]$_.name -match '^(PGHOST|PGPORT|PGDATABASE|PGUSER|PGPASSWORD|DATABASE_URL|DB_HOST|DB_NAME|DB_USER|DB_PASSWORD)$'
      }).Count -gt 0
    $commandText = @(@($container.command) + @($container.args)) -join " "
    $hasDatabaseCommand = $commandText -match '(?i)(?:^|\s)(psql|pg_isready)(?:\s|$)'
    if ($sslVariable.Count -eq 0 -and -not $hasDatabaseEnvironment -and -not $hasDatabaseCommand) {
      continue
    }
    $mode = if ($sslVariable.Count -eq 0) {
      "prefer"
    } else {
      ([string]$sslVariable[0].value).ToLowerInvariant()
    }
    $null = $liveResults.Add([ordered]@{
        type = "cloud-run-job"
        resource = $jobName
        pgsslmode = $mode
        encryptionRequired = $mode -in $secureModes
      })
    if ($mode -notin $secureModes) {
      $null = $violations.Add("$jobName permits an unencrypted database connection (PGSSLMODE=$mode).")
    }
  }

  if ([string]::IsNullOrWhiteSpace($PgStatSslEvidencePath)) {
    $null = $violations.Add("No pg_stat_ssl evidence was supplied for active $Environment client sessions.")
  } elseif (-not (Test-Path -LiteralPath $PgStatSslEvidencePath)) {
    $null = $violations.Add("pg_stat_ssl evidence does not exist: $PgStatSslEvidencePath")
  } else {
    $sessionEvidence = Get-Content -Raw -LiteralPath $PgStatSslEvidencePath | ConvertFrom-Json
    $capturedAtUtc = [datetime]::MinValue
    $hasValidTimestamp = [datetime]::TryParse(
      [string]$sessionEvidence.capturedAtUtc,
      [System.Globalization.CultureInfo]::InvariantCulture,
      [System.Globalization.DateTimeStyles]::AssumeUniversal -bor [System.Globalization.DateTimeStyles]::AdjustToUniversal,
      [ref]$capturedAtUtc
    )
    $evidenceAge = [datetime]::UtcNow - $capturedAtUtc
    $evidenceMatches = (
      [string]$sessionEvidence.environment -eq $Environment -and
      [string]$sessionEvidence.projectId -eq $ProjectId -and
      [string]$sessionEvidence.instance -eq $instanceName -and
      $hasValidTimestamp -and
      $evidenceAge.TotalMinutes -ge -5 -and
      $evidenceAge.TotalMinutes -le $EvidenceMaxAgeMinutes -and
      [int64]$sessionEvidence.clientBackends -gt 0 -and
      [int64]$sessionEvidence.unencryptedBackends -eq 0 -and
      [int64]$sessionEvidence.encryptedBackends -eq [int64]$sessionEvidence.clientBackends
    )
    $sessionEvidenceVerified = $evidenceMatches
    if (-not $evidenceMatches) {
      $null = $violations.Add("pg_stat_ssl evidence is stale, missing, mismatched, empty, or contains unencrypted client sessions.")
    }
  }
}

$result = [ordered]@{
  auditedAtUtc = [datetime]::UtcNow.ToString("o")
  environment = $Environment
  source = @($sourceResults)
  live = @($liveResults)
  serverSslMode = $serverSslMode
  activeSessionEncryptionVerified = $sessionEvidenceVerified
  compliant = $violations.Count -eq 0
  violations = @($violations)
}
$json = $result | ConvertTo-Json -Depth 10
if (-not [string]::IsNullOrWhiteSpace($OutputJson)) {
  $outputDirectory = Split-Path -Parent $OutputJson
  if (-not [string]::IsNullOrWhiteSpace($outputDirectory)) {
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
  }
  $json | Set-Content -LiteralPath $OutputJson
}
$json

if ($violations.Count -gt 0 -and -not $ReportOnly) {
  throw "Cloud SQL transport-security audit failed: $($violations -join '; ')"
}
