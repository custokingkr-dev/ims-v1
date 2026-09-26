$ErrorActionPreference = "Stop"
# Execute only the retry function from the source, with in-process fakes. No gcloud process is run.
$source = Join-Path $PSScriptRoot "configure-private-workflow-dev.ps1"
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($source, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw "Provisioning script parse failed" }
$definition = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq "Add-QuotationBucketBinding" }, $true)
if (-not $definition) { throw "Retry function not found" }
. ([scriptblock]::Create($definition.Extent.Text))

$ProjectId = "custoking-dev"
$bucket = "$ProjectId-quotation-documents"
$roleId = "quotationDocumentRuntime"
$roleName = "projects/$ProjectId/roles/$roleId"
$operationsIdentity = "ims-operations-dev@$ProjectId.iam.gserviceaccount.com"
$expectedPermissions = @("storage.buckets.get", "storage.objects.create", "storage.objects.get", "storage.objects.delete")
$gcloud = "Invoke-FakeGcloud"
$propagationMessage = "HTTPError 400: Role ($roleName) does not exist in the resource's hierarchy."

function Invoke-FakeGcloud {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
  $script:Calls++
  foreach ($required in @("--project=custoking-dev", "--role=$roleName", "--member=serviceAccount:$operationsIdentity", "gs://$bucket")) {
    if ($Arguments -notcontains $required) { throw "Missing constrained argument: $required" }
  }
  if ($script:Calls -le $script:Failures) { $global:LASTEXITCODE = 1; Write-Output $script:FailureMessage }
  else { $global:LASTEXITCODE = 0 }
}
function Read-GcloudJson {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
  $script:Reads++
  if ($Arguments[0] -eq "iam") { return [pscustomobject]@{ name=$roleName; deleted=$false; includedPermissions=$expectedPermissions } }
  if ($script:WrongBucket) { return @() }
  return [pscustomobject]@{ name=$bucket; uniform_bucket_level_access=$true; public_access_prevention="enforced"; location="ASIA-SOUTH2" }
}
function Start-Sleep { param([int]$Seconds) if ($Seconds -ne 10) { throw "Unexpected delay" }; $script:Sleeps++ }
function Run-Case([string]$Name, [int]$Failures, [string]$Message, [bool]$WrongBucket, [int]$ExpectedCalls, [int]$ExpectedSleeps, [bool]$ExpectedFailure) {
  $script:Calls=0; $script:Reads=0; $script:Sleeps=0; $script:Failures=$Failures; $script:FailureMessage=$Message; $script:WrongBucket=$WrongBucket
  $failed=$false
  try { Add-QuotationBucketBinding } catch { $failed=$true }
  if ($script:Calls -ne $ExpectedCalls -or $script:Sleeps -ne $ExpectedSleeps -or $failed -ne $ExpectedFailure) {
    throw "$Name failed: calls=$script:Calls sleeps=$script:Sleeps failed=$failed"
  }
  Write-Output "Passed: $Name"
}
Run-Case "immediate success" 0 "" $false 1 0 $false
Run-Case "verified propagation retries then succeeds" 2 $propagationMessage $false 3 2 $false
Run-Case "403 is never retried" 6 "HTTPError 403: Permission denied" $false 1 0 $true
Run-Case "different 400 is never retried" 6 "HTTPError 400: Invalid role permissions" $false 1 0 $true
Run-Case "wrong project bucket is never retried" 6 $propagationMessage $true 1 0 $true
Run-Case "propagation retry has hard attempt limit" 99 $propagationMessage $false 6 5 $true
