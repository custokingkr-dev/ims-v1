param(
    [string]$CloudBuildFile = "cloudbuild.yaml",
    [string]$ComposeFile = "docker-compose.yml",
    [string]$GatewayTemplate = "services/api-gateway/nginx.conf.template"
)

$ErrorActionPreference = "Stop"

function Read-RequiredFile {
    param([string]$Path)
    if (-not (Test-Path $Path)) {
        throw "Required file not found: $Path"
    }
    Get-Content -Raw $Path
}

$cloudBuild = Read-RequiredFile $CloudBuildFile
$compose = Read-RequiredFile $ComposeFile
$gateway = Read-RequiredFile $GatewayTemplate
$violations = New-Object System.Collections.Generic.List[string]

$services = @(
    @{ Key = "IDENTITY"; Name = "custoking-identity-service"; Context = "services/identity-service"; Upstream = "IDENTITY_UPSTREAM"; Token = "IDENTITY_SERVICE_TOKEN" },
    @{ Key = "TENANT_SCHOOL"; Name = "custoking-tenant-school-service"; Context = "services/tenant-school-service"; Upstream = "TENANT_SCHOOL_UPSTREAM"; Token = "TENANT_SCHOOL_SERVICE_TOKEN" },
    @{ Key = "STUDENT"; Name = "custoking-student-service"; Context = "services/student-service"; Upstream = "STUDENT_UPSTREAM"; Token = "STUDENT_SERVICE_TOKEN" },
    @{ Key = "ATTENDANCE"; Name = "custoking-attendance-service"; Context = "services/attendance-service"; Upstream = "ATTENDANCE_UPSTREAM"; Token = "ATTENDANCE_SERVICE_TOKEN" },
    @{ Key = "FEE"; Name = "custoking-fee-service"; Context = "services/fee-service"; Upstream = "FEE_UPSTREAM"; Token = "FEE_SERVICE_TOKEN" },
    @{ Key = "CATALOG"; Name = "custoking-catalog-service"; Context = "services/catalog-service"; Upstream = "CATALOG_UPSTREAM"; Token = "CATALOG_SERVICE_TOKEN" },
    @{ Key = "WORKFLOW"; Name = "custoking-workflow-service"; Context = "services/workflow-service"; Upstream = "WORKFLOW_UPSTREAM"; Token = "WORKFLOW_SERVICE_TOKEN" },
    @{ Key = "FIREFIGHTING"; Name = "custoking-firefighting-service"; Context = "services/firefighting-service"; Upstream = "FIREFIGHTING_UPSTREAM"; Token = "FIREFIGHTING_SERVICE_TOKEN" },
    @{ Key = "REPORTING"; Name = "custoking-reporting-service"; Context = "services/reporting-service"; Upstream = "REPORTING_UPSTREAM"; Token = "REPORTING_SERVICE_TOKEN" },
    @{ Key = "BILLING"; Name = "custoking-billing-service"; Context = "services/billing-service"; Upstream = "BILLING_UPSTREAM"; Token = "BILLING_SERVICE_TOKEN" },
    @{ Key = "AUDIT"; Name = "custoking-audit-service"; Context = "services/audit-service"; Upstream = "AUDIT_UPSTREAM"; Token = "AUDIT_SERVICE_TOKEN" },
    @{ Key = "NOTIFICATION"; Name = "custoking-notification-service"; Context = "services/notification-service"; Upstream = "NOTIFICATION_UPSTREAM"; Token = "NOTIFICATION_SERVICE_TOKEN" }
)

foreach ($retired in @("custoking-backend", "_BACKEND_SERVICE", "_BACKEND_IMAGE", "BACKEND_UPSTREAM", "./backend", "backend:")) {
    if ($cloudBuild.Contains($retired) -or $compose.Contains($retired) -or $gateway.Contains($retired)) {
        $violations.Add("Retired backend deployment reference still present: $retired")
    }
}

foreach ($service in $services) {
    foreach ($required in @($service.Name, $service.Context)) {
        if (-not $cloudBuild.Contains($required)) {
            $violations.Add("cloudbuild.yaml missing service deployment value: $required")
        }
    }
    if (-not $compose.Contains($service.Name)) {
        $violations.Add("docker-compose.yml missing service: $($service.Name)")
    }
    if (-not $gateway.Contains('${' + $service.Upstream + '}')) {
        $violations.Add("api-gateway template missing upstream: $($service.Upstream)")
    }
    if (-not $gateway.Contains('${' + $service.Token + '}')) {
        $violations.Add("api-gateway template missing internal token: $($service.Token)")
    }
    if (-not ($cloudBuild -match "$([regex]::Escape($service.Name))[\s\S]+?--no-allow-unauthenticated")) {
        $violations.Add("cloudbuild.yaml must keep extracted service private: $($service.Name)")
    }
}

foreach ($publicService in @("custoking-frontend", "custoking-api-gateway")) {
    if (-not ($cloudBuild -match "$([regex]::Escape($publicService))[\s\S]+?--allow-unauthenticated")) {
        $violations.Add("cloudbuild.yaml must expose public service: $publicService")
    }
}

foreach ($correlationHeader in @(
    'proxy_set_header X-Request-ID $request_id;',
    'proxy_set_header traceparent $http_traceparent;')) {
    if (-not $gateway.Contains($correlationHeader)) {
        $violations.Add("api-gateway template missing correlation forwarding header: $correlationHeader")
    }
}

foreach ($publicRoute in @(
    "location /api/v1/auth/",
    "location /api/v1/rbac/",
    "location /api/v1/students",
    "location /api/v1/fee-structure",
    "location /api/v1/supply/",
    "location /api/v1/dashboard")) {
    if (-not $gateway.Contains($publicRoute)) {
        $violations.Add("api-gateway template missing public route: $publicRoute")
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Deployment boundary violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Deployment boundary audit passed: Cloud Run and local gateway are service-only."
