param(
    [string]$ProjectId = "custoking-ims",
    [string]$Region = "asia-south2",
    [string]$DirectSmokeServiceAccount,
    [string]$GcloudPath = "gcloud"
)

$ErrorActionPreference = "Stop"

$sdkGcloud = "C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
if ($GcloudPath -eq "gcloud" -and (Test-Path -LiteralPath $sdkGcloud)) {
    $GcloudPath = $sdkGcloud
}
if ([string]::IsNullOrWhiteSpace($DirectSmokeServiceAccount)) {
    $DirectSmokeServiceAccount = "direct-service-smoke@$ProjectId.iam.gserviceaccount.com"
}

function Invoke-Gcloud {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    & $GcloudPath @Args
    if ($LASTEXITCODE -ne 0) {
        throw "gcloud command failed with exit code ${LASTEXITCODE}: gcloud $($Args -join ' ')"
    }
}

function Invoke-GcloudWithRetry {
    param(
        [string[]]$CommandArgs,
        [int]$Attempts = 6,
        [int]$DelaySeconds = 10
    )

    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        & $GcloudPath @CommandArgs
        if ($LASTEXITCODE -eq 0) {
            return
        }
        if ($attempt -eq $Attempts) {
            throw "gcloud command failed with exit code ${LASTEXITCODE}: gcloud $($CommandArgs -join ' ')"
        }
        Start-Sleep -Seconds $DelaySeconds
    }
}

$serviceAccountName = $DirectSmokeServiceAccount.Split("@")[0]

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    & $GcloudPath iam service-accounts describe $DirectSmokeServiceAccount "--project=$ProjectId" *> $null
    $serviceAccountExists = $LASTEXITCODE -eq 0
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
if (-not $serviceAccountExists) {
    Invoke-Gcloud iam service-accounts create $serviceAccountName `
        "--project=$ProjectId" `
        "--display-name=IMS direct service smoke"
    Start-Sleep -Seconds 10
}

foreach ($service in @("custoking-school-core-service")) {
    Invoke-GcloudWithRetry -CommandArgs @(
        "run", "services", "add-iam-policy-binding", $service,
        "--project=$ProjectId",
        "--region=$Region",
        "--member=serviceAccount:$DirectSmokeServiceAccount",
        "--role=roles/run.invoker"
    )
}

foreach ($secret in @("catalog-read-token", "tenant-school-read-token")) {
    Invoke-GcloudWithRetry -CommandArgs @(
        "secrets", "add-iam-policy-binding", $secret,
        "--project=$ProjectId",
        "--member=serviceAccount:$DirectSmokeServiceAccount",
        "--role=roles/secretmanager.secretAccessor"
    )
}

Write-Host "Direct service smoke identity is ready: $DirectSmokeServiceAccount"
