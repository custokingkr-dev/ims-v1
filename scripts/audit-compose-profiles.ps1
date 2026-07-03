param(
    [string]$ComposeFile = "docker-compose.yml"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $ComposeFile)) {
    throw "Compose file not found: $ComposeFile"
}

$compose = Get-Content -Raw -Path $ComposeFile
$violations = New-Object System.Collections.Generic.List[string]

$coreServices = @(
    "identity-service",
    "school-core-service",
    "frontend",
    "api-gateway"
)

$fullOnlyServices = @(
    "platform-service",
    "operations-service",
    "billing-service",
)

foreach ($service in $coreServices) {
    if (-not ($compose -match "(?m)^  $([regex]::Escape($service)):\r?\n    profiles: \[`"core`", `"full`"\]")) {
        $violations.Add("Core local-runtime service must be in core and full profiles: $service")
    }
}

foreach ($service in $fullOnlyServices) {
    if (-not ($compose -match "(?m)^  $([regex]::Escape($service)):\r?\n    profiles: \[`"full`"\]")) {
        $violations.Add("Full-only local-runtime service must be in the full profile: $service")
    }
}

$gatewayBlock = [regex]::Match($compose, "(?ms)^  api-gateway:\r?\n(?<body>.*?)(?=^  [a-zA-Z0-9_-]+:|^volumes:|\z)")
if (-not $gatewayBlock.Success) {
    $violations.Add("api-gateway service block not found in compose file.")
} elseif ($gatewayBlock.Groups["body"].Value -match "(?m)^    depends_on:") {
    $violations.Add("api-gateway must not depend_on the full service graph; profiles must control local memory usage.")
}

if ($violations.Count -gt 0) {
    Write-Host "Compose profile audit violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Compose profile audit passed: core/full profiles can control local runtime size."
