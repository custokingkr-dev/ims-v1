param(
    [string]$RunbookPath = "docs/MICROSERVICE-OBSERVABILITY-RUNBOOK.md",
    [string]$SmokeScript = "scripts/smoke-deployment-readiness.ps1",
    [string]$GatewayTemplate = "services/api-gateway/nginx.conf.template",
    [string]$GatewayTracing = "services/api-gateway/tracing.js",
    [string]$TraceVerificationScript = "scripts/verify-cloud-trace.ps1"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$runbookFile = Join-Path $repoRoot $RunbookPath
$smokeFile = Join-Path $repoRoot $SmokeScript
$gatewayFile = Join-Path $repoRoot $GatewayTemplate
$gatewayTracingFile = Join-Path $repoRoot $GatewayTracing
$traceVerificationFile = Join-Path $repoRoot $TraceVerificationScript

foreach ($file in @($runbookFile, $smokeFile, $gatewayFile, $gatewayTracingFile, $traceVerificationFile)) {
    if (-not (Test-Path $file)) {
        throw "Required observability file not found: $file"
    }
}

$runbook = Get-Content -Raw -Path $runbookFile
$smoke = Get-Content -Raw -Path $smokeFile
$gateway = Get-Content -Raw -Path $gatewayFile
$gatewayTracingSource = Get-Content -Raw -Path $gatewayTracingFile
$traceVerification = Get-Content -Raw -Path $traceVerificationFile
$violations = New-Object System.Collections.Generic.List[string]

foreach ($required in @(
    "/gateway-health",
    "/actuator/health",
    "scripts\smoke-deployment-readiness.ps1",
    "-OutputJson deployment-readiness-smoke.json",
    "X-Request-ID",
    "traceparent",
    "requestId",
    "outbox",
    "notificationInbox",
    "failed inbox rows",
    "5xx rate",
    "request latency",
    "legacy-compatibility-audit.json",
    "secret-manager-evidence.json",
    "cloud-run-iam-evidence.json",
    "legacy-retirement-evidence.json",
    "rollback-drill-evidence.json",
    "Cloud Run revision")) {
    if (-not $runbook.Contains($required)) {
        $violations.Add("Observability runbook missing required item: $required")
    }
}

foreach ($required in @(
    "deployment.environment.name",
    "service.version",
    "scripts\verify-cloud-trace.ps1",
    "Failed to export spans")) {
    if (-not $runbook.Contains($required)) {
        $violations.Add("Observability runbook missing trace delivery control: $required")
    }
}

foreach ($required in @(
    "defaultResource().merge",
    "resourceFromAttributes",
    "configuredResourceAttributes",
    "resourceFilter",
    "forceFlush")) {
    if (-not $gatewayTracingSource.Contains($required)) {
        $violations.Add("Gateway tracing missing explicit resource preservation: $required")
    }
}

foreach ($required in @(
    "+service.name:",
    "+deployment.environment.name:",
    "+service.version:",
    "latestReadyRevisionName",
    "exporterErrorCount",
    "Failed to export spans")) {
    if (-not $traceVerification.Contains($required)) {
        $violations.Add("Cloud Trace verification script missing required check: $required")
    }
}

foreach ($required in @(
    "[string]`$OutputJson",
    "ConvertTo-Json",
    "generatedAtUtc",
    "gatewayBaseUrl",
    "checks")) {
    if (-not $smoke.Contains($required)) {
        $violations.Add("Deployment readiness smoke missing JSON artifact support: $required")
    }
}

foreach ($header in @(
    'proxy_set_header X-Request-ID $request_id;',
    'proxy_set_header traceparent $http_traceparent;')) {
    if (-not $gateway.Contains($header)) {
        $violations.Add("Gateway template missing correlation header: $header")
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Observability runbook violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Observability runbook audit passed: health, smoke artifacts, correlation, async health, and promotion artifacts are documented."
