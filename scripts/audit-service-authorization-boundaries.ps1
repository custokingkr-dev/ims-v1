param(
    [string]$ServicesRoot = "services",
    [string]$GatewayTemplate = "services/api-gateway/nginx.conf.template",
    [string]$ComposeFile = "docker-compose.yml",
    [string]$CloudBuildFile = "cloudbuild.yaml"
)

$ErrorActionPreference = "Stop"

function Read-RequiredFile {
    param([string]$Path)
    if (-not (Test-Path $Path)) {
        throw "Required file not found: $Path"
    }
    Get-Content -Raw $Path
}

$violations = New-Object System.Collections.Generic.List[string]
$gateway = Read-RequiredFile $GatewayTemplate
$compose = Read-RequiredFile $ComposeFile
$cloudBuild = Read-RequiredFile $CloudBuildFile

$serviceContracts = @(
    @{ Service = "notification-service"; Header = "X-Notification-Service-Token"; Secret = "notification-status-token"; Env = "NOTIFICATION_SERVICE_TOKEN" },
    @{ Service = "audit-service"; Header = "X-Audit-Service-Token"; Secret = "audit-ingest-token"; Env = "AUDIT_SERVICE_TOKEN" },
    @{ Service = "identity-service"; Header = "X-Identity-Service-Token"; Secret = "identity-introspection-token"; Env = "IDENTITY_SERVICE_TOKEN" },
    @{ Service = "tenant-school-service"; Header = "X-Tenant-School-Token"; Secret = "tenant-school-read-token"; Env = "TENANT_SCHOOL_SERVICE_TOKEN" },
    @{ Service = "student-service"; Header = "X-Student-Service-Token"; Secret = "student-read-token"; Env = "STUDENT_SERVICE_TOKEN" },
    @{ Service = "attendance-service"; Header = "X-Attendance-Service-Token"; Secret = "attendance-read-token"; Env = "ATTENDANCE_SERVICE_TOKEN" },
    @{ Service = "fee-service"; Header = "X-Fee-Service-Token"; Secret = "fee-read-token"; Env = "FEE_SERVICE_TOKEN" },
    @{ Service = "catalog-service"; Header = "X-Catalog-Service-Token"; Secret = "catalog-read-token"; Env = "CATALOG_SERVICE_TOKEN" },
    # Phase 2: both token/header pairs are validated by the merged operations-service (accepts both).
    @{ Service = "operations-service"; Header = "X-Workflow-Service-Token"; Secret = "workflow-read-token"; Env = "WORKFLOW_SERVICE_TOKEN" },
    @{ Service = "operations-service"; Header = "X-Firefighting-Service-Token"; Secret = "firefighting-read-token"; Env = "FIREFIGHTING_SERVICE_TOKEN" },
    @{ Service = "reporting-service"; Header = "X-Reporting-Service-Token"; Secret = "reporting-read-token"; Env = "REPORTING_SERVICE_TOKEN" },
    @{ Service = "billing-service"; Header = "X-Billing-Service-Token"; Secret = "billing-service-token"; Env = "BILLING_SERVICE_TOKEN" }
)

foreach ($contract in $serviceContracts) {
    if (-not $gateway.Contains("proxy_set_header $($contract.Header) `$`{$($contract.Env)`};")) {
        $violations.Add("Gateway template missing service token header for $($contract.Service): $($contract.Header)")
    }
    if (-not $compose.Contains($contract.Env)) {
        $violations.Add("docker-compose.yml missing local service token env: $($contract.Env)")
    }
    if (-not $cloudBuild.Contains($contract.Secret)) {
        $violations.Add("cloudbuild.yaml missing Secret Manager token for $($contract.Service): $($contract.Secret)")
    }
}

$controllerFiles = Get-ChildItem -Path $ServicesRoot -Recurse -Filter "*Controller.java" |
        Where-Object { $_.FullName -notmatch "\\target\\" }

foreach ($file in $controllerFiles) {
    $source = Get-Content -Raw $file.FullName
    $relative = Resolve-Path -Relative $file.FullName

    if ($source -match "StringUtils\.hasText\((readToken|serviceToken|statusToken|ingestToken|introspectionToken)\)\s*&&\s*!\1\.equals\(token\)") {
        $violations.Add("Controller uses fail-open optional service token check: $relative")
    }

    if ($source -match "if\s*\(\s*(pushToken|readToken|serviceToken|statusToken|ingestToken|introspectionToken)\s*==\s*null\s*\|\|\s*\1\.isBlank\(\)\s*\)\s*\{\s*return\s*;") {
        $violations.Add("Controller permits requests when service token configuration is blank: $relative")
    }

    if ($source -match "require(Token|ValidToken)\(token\);" -or
        $source -match "requireValidToken\(token != null \? token : tokenParam\);") {
        $violations.Add("Controller uses generic token guard without route-level scope: $relative")
    }

    if ($source -match "@(GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)" -and
        $source -notmatch "require(Token|ValidToken)\([^;]+,\s*`"[a-z][a-z0-9-]*:[a-z][a-z0-9:-]*`"\)" -and
        $source -notmatch "login\(" -and
        $source -notmatch "refresh\(" -and
        $source -notmatch "logout\(") {
        $violations.Add("Controller has mapped endpoints without a scoped token guard: $relative")
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Service authorization boundary violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Service authorization boundary audit passed: extracted services fail closed on internal tokens."
