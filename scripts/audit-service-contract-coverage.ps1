param(
    [string]$GatewayTemplate = "services/api-gateway/nginx.conf.template",
    [string]$DeploymentSmokeScript = "scripts/smoke-deployment-readiness.ps1",
    [string]$GatewaySmokeScript = "scripts/smoke-gateway-routes.ps1",
    [string]$CloudBuildFile = "cloudbuild.yaml"
)

$ErrorActionPreference = "Stop"

$gateway = Get-Content -Raw -Path $GatewayTemplate
$deploymentSmoke = Get-Content -Raw -Path $DeploymentSmokeScript
$gatewaySmoke = Get-Content -Raw -Path $GatewaySmokeScript
$cloudBuild = Get-Content -Raw -Path $CloudBuildFile

$contracts = @(
    @{ Name = "identity"; Upstream = "IDENTITY_UPSTREAM"; Token = "IDENTITY_SERVICE_TOKEN"; GatewayPrefix = "/identity-api/v1/"; PublicPrefix = "/api/v1/auth/"; SmokeFeature = "identity:roles" }
    @{ Name = "tenant-school"; Upstream = "TENANT_SCHOOL_UPSTREAM"; Token = "TENANT_SCHOOL_SERVICE_TOKEN"; GatewayPrefix = "/tenant-api/v1/"; PublicPrefix = "/api/v1/schools"; SmokeFeature = "tenant-school:list-schools" }
    @{ Name = "student"; Upstream = "STUDENT_UPSTREAM"; Token = "STUDENT_SERVICE_TOKEN"; GatewayPrefix = "/student-api/v1/"; PublicPrefix = "/api/v1/students"; SmokeFeature = "student:list" }
    @{ Name = "attendance"; Upstream = "ATTENDANCE_UPSTREAM"; Token = "ATTENDANCE_SERVICE_TOKEN"; GatewayPrefix = "/attendance-api/v1/"; PublicPrefix = "/api/v1/attendance/"; SmokeFeature = "attendance:daily-summary" }
    @{ Name = "fee"; Upstream = "FEE_UPSTREAM"; Token = "FEE_SERVICE_TOKEN"; GatewayPrefix = "/fee-api/v1/"; PublicPrefix = "/api/v1/fee-structure"; SmokeFeature = "fee:structure" }
    @{ Name = "catalog"; Upstream = "CATALOG_UPSTREAM"; Token = "CATALOG_SERVICE_TOKEN"; GatewayPrefix = "/catalog-api/v1/"; PublicPrefix = "/api/v1/supply/"; SmokeFeature = "catalog:orders" }
    @{ Name = "workflow"; Upstream = "WORKFLOW_UPSTREAM"; Token = "WORKFLOW_SERVICE_TOKEN"; GatewayPrefix = "/workflow-api/v1/"; PublicPrefix = "/api/v1/workflows/"; SmokeFeature = "workflow:pending" }
    @{ Name = "firefighting"; Upstream = "FIREFIGHTING_UPSTREAM"; Token = "FIREFIGHTING_SERVICE_TOKEN"; GatewayPrefix = "/firefighting-api/v1/"; PublicPrefix = "/api/v1/ff/"; SmokeFeature = "firefighting:requests" }
    @{ Name = "reporting"; Upstream = "REPORTING_UPSTREAM"; Token = "REPORTING_SERVICE_TOKEN"; GatewayPrefix = "/reporting-api/v1/"; PublicPrefix = "/api/v1/dashboard"; SmokeFeature = "reporting:command-center" }
    @{ Name = "billing"; Upstream = "BILLING_UPSTREAM"; Token = "BILLING_SERVICE_TOKEN"; GatewayPrefix = "/billing-api/v1/"; PublicPrefix = "/api/v1/sa/invoices"; SmokeFeature = "billing:invoices" }
    @{ Name = "audit"; Upstream = "AUDIT_UPSTREAM"; Token = "AUDIT_SERVICE_TOKEN"; GatewayPrefix = "/audit-api/v1/"; PublicPrefix = "/api/v1/audit-logs"; SmokeFeature = "audit:logs" }
    @{ Name = "notification"; Upstream = "NOTIFICATION_UPSTREAM"; Token = "NOTIFICATION_SERVICE_TOKEN"; GatewayPrefix = "/notification-api/v1/"; PublicPrefix = "/api/v1/notifications/"; SmokeFeature = "notification:broadcasts" }
)

$violations = New-Object System.Collections.Generic.List[string]

foreach ($contract in $contracts) {
    foreach ($required in @($contract.Upstream, $contract.Token)) {
        if (-not $cloudBuild.Contains($required)) {
            $violations.Add("cloudbuild.yaml missing gateway contract for $($contract.Name): $required")
        }
        if (-not $gateway.Contains('${' + $required + '}')) {
            $violations.Add("Gateway template missing rendered value for $($contract.Name): $required")
        }
    }

    if (-not $gateway.Contains("location $($contract.GatewayPrefix)")) {
        $violations.Add("Gateway template missing diagnostic route prefix for $($contract.Name): $($contract.GatewayPrefix)")
    }
    if (-not $gateway.Contains($contract.PublicPrefix)) {
        $violations.Add("Gateway template missing public compatibility route for $($contract.Name): $($contract.PublicPrefix)")
    }
    if (-not $gatewaySmoke.Contains($contract.GatewayPrefix)) {
        $violations.Add("Gateway route smoke missing diagnostic route for $($contract.Name): $($contract.GatewayPrefix)")
    }
    if (-not $deploymentSmoke.Contains($contract.SmokeFeature)) {
        $violations.Add("Deployment readiness smoke missing service feature for $($contract.Name): $($contract.SmokeFeature)")
    }
}

foreach ($required in @("/api/v1/auth/login", "/gateway-health")) {
    if (-not $deploymentSmoke.Contains($required) -and -not $gatewaySmoke.Contains($required)) {
        $violations.Add("Smoke coverage missing core contract: $required")
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Service contract coverage violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Service contract coverage audit passed: gateway, deployment, and smoke contracts are service-only."
