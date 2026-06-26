param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectId,

    [string]$Region = "asia-south2",
    [string]$Network = "default",
    [string]$ConnectorName = "msg91-egress",
    [string]$ConnectorRange = "10.8.0.0/28",
    [string]$RouterName = "msg91-egress-router",
    [string]$NatName = "msg91-egress-nat",
    [string]$AddressName = "msg91-egress-ip"
)

$ErrorActionPreference = "Stop"

$gcloud = "gcloud"
$cmdPath = "C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
if (Test-Path $cmdPath) {
    $gcloud = $cmdPath
}

function Run-Gcloud {
    param([string[]]$Args)
    & $gcloud @Args
    if ($LASTEXITCODE -ne 0) {
        throw "gcloud failed: $($Args -join ' ')"
    }
}

Run-Gcloud @("config", "set", "project", $ProjectId)
Run-Gcloud @("services", "enable",
    "run.googleapis.com",
    "vpcaccess.googleapis.com",
    "compute.googleapis.com")

$address = & $gcloud compute addresses describe $AddressName `
    --project $ProjectId `
    --region $Region `
    --format "value(address)" 2>$null
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($address)) {
    Run-Gcloud @("compute", "addresses", "create", $AddressName,
        "--project", $ProjectId,
        "--region", $Region)
    $address = & $gcloud compute addresses describe $AddressName `
        --project $ProjectId `
        --region $Region `
        --format "value(address)"
}

$connector = & $gcloud compute networks vpc-access connectors describe $ConnectorName `
    --project $ProjectId `
    --region $Region `
    --format "value(name)" 2>$null
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($connector)) {
    Run-Gcloud @("compute", "networks", "vpc-access", "connectors", "create", $ConnectorName,
        "--project", $ProjectId,
        "--region", $Region,
        "--network", $Network,
        "--range", $ConnectorRange)
}

$router = & $gcloud compute routers describe $RouterName `
    --project $ProjectId `
    --region $Region `
    --format "value(name)" 2>$null
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($router)) {
    Run-Gcloud @("compute", "routers", "create", $RouterName,
        "--project", $ProjectId,
        "--region", $Region,
        "--network", $Network)
}

$nat = & $gcloud compute routers nats describe $NatName `
    --project $ProjectId `
    --router $RouterName `
    --region $Region `
    --format "value(name)" 2>$null
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($nat)) {
    Run-Gcloud @("compute", "routers", "nats", "create", $NatName,
        "--project", $ProjectId,
        "--router", $RouterName,
        "--region", $Region,
        "--nat-all-subnet-ip-ranges",
        "--nat-external-ip-pool", $AddressName,
        "--enable-logging")
}

Write-Host "MSG91 static egress IP: $address"
Write-Host "Whitelist this IP in MSG91 for production Cloud Run outbound calls."
Write-Host "Use this Cloud Build substitution: _NOTIFICATION_VPC_CONNECTOR=$ConnectorName"
