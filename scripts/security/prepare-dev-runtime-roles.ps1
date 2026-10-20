param(
  [switch]$Apply,
  [string]$ExpectedSqlSha256 = "",
  [string]$OutputDirectory = "artifacts/security-runtime-role-preparation"
)
$ErrorActionPreference = "Stop"
function Invoke-PreparationGcloud {
  param([string[]]$Arguments, [switch]$CaptureOutput)
  # Windows PowerShell 5 treats ordinary native stderr progress as an error record.
  # Exit status, rather than progress output, is authoritative for native commands.
  $previousPreference=$ErrorActionPreference
  try {
    $ErrorActionPreference='Continue'
    $nativeOutput=@(& gcloud.cmd @Arguments 2>$null)
    $nativeExitCode=$LASTEXITCODE
    return @{ExitCode=$nativeExitCode;Output=$(if($CaptureOutput){$nativeOutput}else{@()})}
  } finally { $ErrorActionPreference=$previousPreference }
}
# This provisioner is deliberately dev-only and prepares roles without changing any service revision.
$project = "custoking-dev"
$region = "asia-south2"
$ownerAccount = "ims-school-core-dev@custoking-dev.iam.gserviceaccount.com"
$repo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "../.."))
$sqlPath = Join-Path $repo "docs/security-remediation/runtime-role-cutover.sql"
$sqlBytes = [System.IO.File]::ReadAllBytes($sqlPath)
$sha = [System.Security.Cryptography.SHA256]::Create()
try { $sqlHash = ([BitConverter]::ToString($sha.ComputeHash($sqlBytes))).Replace("-", "").ToLowerInvariant() }
finally { $sha.Dispose() }
$grantSql = [System.Text.Encoding]::UTF8.GetString($sqlBytes).TrimStart([char]0xfeff)
if ([regex]::Matches($grantSql, '(?im)^\s*BEGIN;\s*$').Count -ne 1 -or [regex]::Matches($grantSql, '(?im)^\s*COMMIT;\s*$').Count -ne 1) {
  throw "Reviewed grant SQL must contain exactly one outer BEGIN and COMMIT."
}
$grantSql = [regex]::Replace($grantSql, '(?im)^\s*(BEGIN|COMMIT);\s*$', '')
if ($Apply -and ($ExpectedSqlSha256 -notmatch '^[a-fA-F0-9]{64}$' -or $ExpectedSqlSha256.ToLowerInvariant() -ne $sqlHash)) {
  throw "Apply requires the exact SQL SHA256 from the reviewed dry run."
}
$mappings = @(
  @{ role="ims_identity_rt"; secret="identity-runtime-db-password-dev"; account="ims-identity-dev@custoking-dev.iam.gserviceaccount.com"; variable="IDENTITY_RUNTIME_PASSWORD" },
  @{ role="ims_school_core_rt"; secret="school-core-runtime-db-password-dev"; account=$ownerAccount; variable="SCHOOL_CORE_RUNTIME_PASSWORD" },
  @{ role="ims_operations_rt"; secret="operations-runtime-db-password-dev"; account="ims-operations-dev@custoking-dev.iam.gserviceaccount.com"; variable="OPERATIONS_RUNTIME_PASSWORD" },
  @{ role="ims_platform_rt"; secret="platform-runtime-db-password-dev"; account="ims-platform-dev@custoking-dev.iam.gserviceaccount.com"; variable="PLATFORM_RUNTIME_PASSWORD" },
  @{ role="ims_billing_rt"; secret="billing-runtime-db-password-dev"; account="ims-billing-dev@custoking-dev.iam.gserviceaccount.com"; variable="BILLING_RUNTIME_PASSWORD" }
)
$jobName = "ims-dev-runtime-roles-" + [DateTime]::UtcNow.ToString("yyyyMMddHHmmss") + "-" + [Guid]::NewGuid().ToString("N").Substring(0,8)

# Only ASCII hex passwords are interpolated into SQL. They are read by the job from managed refs.
# Never use shell tracing, psql echo, password command-line arguments, or credential-bearing files.
$shell = "set -eu`nset +x`n"
foreach ($mapping in $mappings) {
  $shell += ('test "${#' + $mapping.variable + '}" -eq 64 || exit 21' + "`n")
  $shell += ('case "$' + $mapping.variable + '" in *[!0-9a-f]*) exit 22;; esac' + "`n")
}
$roleCheck = "BEGIN;`nSET LOCAL statement_timeout='30s';`nSET LOCAL lock_timeout='10s';`nSET LOCAL client_min_messages='error';`nDO `$managed`$ BEGIN`n"
foreach ($mapping in $mappings) {
  $marker = "ims-managed-runtime-role-v1:" + $mapping.secret
  $roleCheck += "IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='$($mapping.role)' AND coalesce(shobj_description(oid,'pg_authid'),'') <> '$marker') THEN RAISE EXCEPTION 'Unmanaged existing runtime role'; END IF;`n"
}
$roleCheck += "END `$managed`$;`n"
$delimiter = "IMS_REVIEWED_GRANT_SQL_" + $sqlHash
if (($roleCheck + $grantSql) -match "(?m)^$delimiter`$") { throw "Unexpected SQL delimiter collision." }
$shell += "{`ncat <<'" + $delimiter + "'`n" + $roleCheck + $grantSql + "`n" + $delimiter + "`n"
foreach ($mapping in $mappings) {
  $shell += ('printf "ALTER ROLE ' + $mapping.role + ' PASSWORD ''%s'';\n" "$' + $mapping.variable + '"' + "`n")
  $shell += ('printf "%s\n" "COMMENT ON ROLE ' + $mapping.role + " IS 'ims-managed-runtime-role-v1:" + $mapping.secret + "';" + '"' + "`n")
}
$shell += "printf '%s\n' 'COMMIT;'`n} | psql -X -q -v ON_ERROR_STOP=1 >/dev/null 2>/dev/null`nprintf '%s\n' 'IMS_MANAGED_DEV_RUNTIME_ROLES_PREPARED'`n"

$environment = @(
  @{name="PGHOST";value="10.92.0.3"}, @{name="PGPORT";value="5432"},
  @{name="PGDATABASE";value="custoking_dev"}, @{name="PGUSER";value="appuser"},
  @{name="PGSSLMODE";value="require"}, @{name="PGCONNECT_TIMEOUT";value="10"},
  @{name="PGPASSWORD";valueFrom=@{secretKeyRef=@{name="db-password-dev";key="latest"}}}
)
foreach ($mapping in $mappings) { $environment += @{name=$mapping.variable;valueFrom=@{secretKeyRef=@{name=$mapping.secret;key="latest"}}} }
$job = @{
  apiVersion="run.googleapis.com/v1";kind="Job";metadata=@{name=$jobName;labels=@{purpose="managed-dev-runtime-role-preparation"}}
  spec=@{template=@{
    metadata=@{annotations=@{"run.googleapis.com/network-interfaces"='[{"network":"default","subnetwork":"default"}]';"run.googleapis.com/vpc-access-egress"="private-ranges-only"}}
    spec=@{taskCount=1;template=@{spec=@{
      serviceAccountName=$ownerAccount;maxRetries=0;timeoutSeconds=120
      containers=@(@{image="docker.io/library/postgres@sha256:95206741a5b214807675e14165369d05b93a9cf692223b616d07cca227e74b0b";command=@("/bin/sh");args=@("-c",$shell);resources=@{limits=@{cpu="1";memory="512Mi"}};env=$environment})
    }}}
  }}
}
$output = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) { $OutputDirectory } else { Join-Path $repo $OutputDirectory }
New-Item -ItemType Directory -Force -Path $output | Out-Null
$jobFile = Join-Path $output "reviewed-preparation-job.json"
$job | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $jobFile -Encoding UTF8
$plan = [ordered]@{
  project=$project;region=$region;applyRequested=[bool]$Apply;sqlSha256=$sqlHash;job=$jobName
  credentialsInJobFile=$false;serviceRevisionChanges=$false;rolePreparationOnly=$true
  roles=@($mappings | ForEach-Object { @{role=$_.role;secret=$_.secret;runtimeAccount=$_.account;passwordVersionPolicy="Create only for a new/no-version managed secret; otherwise reuse ENABLED latest without reading its value."} })
  existingResourcesPolicy="Refuse unmanaged secret labels and unmanaged role provenance. Never overwrite an existing managed password version."
  temporarySecretAccess="Owner job gets four additional secretAccessor bindings; only newly added bindings are removed in finally."
  reviewedJobFile=$jobFile
}
$plan | ConvertTo-Json -Depth 7
if (-not $Apply) { return }

$tokenResult=Invoke-PreparationGcloud -Arguments @('auth','print-access-token',"--project=$project") -CaptureOutput
$tokenLines=$tokenResult.Output
if ($tokenResult.ExitCode -ne 0) { throw "Could not obtain a dev deployment access token." }
$accessToken = ($tokenLines -join "").Trim(); $tokenLines=$null
if (-not $accessToken) { throw "No deployment access token available." }
$headers = @{Authorization="Bearer $accessToken"}
function Invoke-SecretApi([string]$method,[string]$resource,$body=$null,[switch]$AllowMissing) {
  try {
    $arguments=@{Method=$method;Uri="https://secretmanager.googleapis.com/v1/$resource";Headers=$headers;ErrorAction="Stop"}
    if ($null -ne $body) { $arguments.Body=($body | ConvertTo-Json -Depth 20 -Compress);$arguments.ContentType="application/json" }
    return Invoke-RestMethod @arguments
  } catch {
    $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    if ($AllowMissing -and $status -eq 404) { return $null }
    throw "Secret Manager $method failed (HTTP $status); credentials and response bodies suppressed."
  }
}
function Set-SecretAccessor([string]$secret,[string]$account,[bool]$Add) {
  $resource="projects/$project/secrets/$secret"
  $policy=Invoke-SecretApi "GET" ($resource + ':getIamPolicy?options.requestedPolicyVersion=3')
  $bindings=@($policy.bindings | Where-Object { $null -ne $_ });$member="serviceAccount:$account"
  $matching=@($bindings | Where-Object { $_.role -eq 'roles/secretmanager.secretAccessor' -and -not $_.condition })
  $present=@($matching | Where-Object { $member -in $_.members }).Count -gt 0
  if ($Add -eq $present) { return $false }
  if ($Add) {
    if ($matching.Count -gt 0) { $matching[0].members=@($matching[0].members)+$member }
    else { $bindings+=@{role='roles/secretmanager.secretAccessor';members=@($member)} }
  } else {
    foreach($binding in $matching) { $binding.members=@($binding.members | Where-Object { $_ -ne $member }) }
    $bindings=@($bindings | Where-Object { @($_.members).Count -gt 0 })
  }
  $updated=@{version=3;bindings=$bindings};if($policy.etag){$updated.etag=$policy.etag}
  $null=Invoke-SecretApi "POST" ($resource + ':setIamPolicy') @{policy=$updated}
  return $true
}
$temporaryAccess=New-Object System.Collections.Generic.List[string]
$createdJob=$false;$completed=$false
try {
  # Validate every existing secret before generating or granting anything.
  $existing=@{}
  foreach($mapping in $mappings) {
    $secret=Invoke-SecretApi "GET" "projects/$project/secrets/$($mapping.secret)" -AllowMissing
    if($secret -and ($secret.labels.managed_by -ne 'ims-security-runtime-role-v1' -or $secret.labels.runtime_role -ne $mapping.role -or $secret.labels.environment -ne 'dev')) {
      throw "Refusing an existing unmanaged runtime password secret: $($mapping.secret)"
    }
    $existing[$mapping.secret]=$secret
  }
  foreach($mapping in $mappings) {
    $resource="projects/$project/secrets/$($mapping.secret)"
    if(-not $existing[$mapping.secret]) {
      $null=Invoke-SecretApi "POST" "projects/$project/secrets?secretId=$($mapping.secret)" @{replication=@{userManaged=@{replicas=@(@{location=$region})}};labels=@{managed_by='ims-security-runtime-role-v1';runtime_role=$mapping.role;environment='dev'}}
    }
    $versions=Invoke-SecretApi "GET" ($resource + '/versions?pageSize=100')
    $latest=@($versions.versions | Sort-Object { [long](($_.name -split '/')[-1]) } -Descending | Select-Object -First 1)
    if($latest.Count -gt 0 -and $latest[0].state -ne 'ENABLED') { throw "Managed secret latest version is not ENABLED; refusing automatic rotation or re-enablement." }
    if($latest.Count -eq 0) {
      $random=[System.Security.Cryptography.RandomNumberGenerator]::Create();$bytes=New-Object byte[] 32;$passwordBytes=$null
      try {
        $random.GetBytes($bytes)
        $hex=([BitConverter]::ToString($bytes)).Replace('-','').ToLowerInvariant()
        $passwordBytes=[System.Text.Encoding]::ASCII.GetBytes($hex)
        $null=Invoke-SecretApi "POST" ($resource + ':addVersion') @{payload=@{data=[Convert]::ToBase64String($passwordBytes)}}
      } finally { [Array]::Clear($bytes,0,$bytes.Length);if($passwordBytes){[Array]::Clear($passwordBytes,0,$passwordBytes.Length)};$hex=$null;$random.Dispose() }
    }
    $null=Set-SecretAccessor $mapping.secret $mapping.account $true
    if($mapping.account -ne $ownerAccount -and (Set-SecretAccessor $mapping.secret $ownerAccount $true)) { $temporaryAccess.Add($mapping.secret) }
  }
  $createdJob=$true # Track attempted creation before the CLI can fail after accepting the resource.
  $createResult=Invoke-PreparationGcloud -Arguments @('run','jobs','replace',$jobFile,"--project=$project","--region=$region",'--quiet')
  if($createResult.ExitCode -ne 0){throw "Could not create the dev role preparation job."}
  $executeResult=Invoke-PreparationGcloud -Arguments @('run','jobs','execute',$jobName,"--project=$project","--region=$region",'--wait','--quiet')
  if($executeResult.ExitCode -ne 0){throw "Dev role preparation failed; unmanaged roles/invalid credentials are refused. No service cutover was performed."}
  $completed=$true
} finally {
  $cleanupFailures=New-Object System.Collections.Generic.List[string]
  if($createdJob) {
    if($jobName -notmatch '^ims-dev-runtime-roles-[0-9]{14}-[a-f0-9]{8}$'){throw "Unexpected temporary job name; cleanup refused."}
    $deleteResult=Invoke-PreparationGcloud -Arguments @('run','jobs','delete',$jobName,"--project=$project","--region=$region",'--quiet')
    if($deleteResult.ExitCode -ne 0){$cleanupFailures.Add('temporary owner job (creation was attempted; verify whether it exists)')}
  }
  foreach($secret in $temporaryAccess) { try { $null=Set-SecretAccessor $secret $ownerAccount $false } catch { $cleanupFailures.Add("temporary secret grant $secret") } }
  $accessToken=$null;$headers=$null
  if($cleanupFailures.Count -gt 0){throw "Preparation cleanup incomplete: $($cleanupFailures -join ', '). Review these dev resources before cutover."}
}
if($completed) { @{project=$project;prepared=$true;serviceCutover=$false;secretValuesLogged=$false;temporaryJobRemoved=$true;temporaryGrantsRemoved=$true;sqlSha256=$sqlHash}|ConvertTo-Json; exit 0 }
