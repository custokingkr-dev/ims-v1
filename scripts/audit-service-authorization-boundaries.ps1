param(
    [string]$ServicesRoot = "services",
    [string]$GatewayTemplate = "services/api-gateway/server.js",
    [string]$ComposeFile = "docker-compose.yml",
    [string]$CloudRunDirectory = "deploy/cloudrun",
    [string]$AsyncSchedulerScript = "scripts/configure-async-relay-scheduler.ps1"
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
$cloudRun = (Get-ChildItem -Path $CloudRunDirectory -Filter "*.yaml" -File |
    ForEach-Object { Get-Content -Raw -Path $_.FullName }) -join "`n"
$asyncScheduler = Read-RequiredFile $AsyncSchedulerScript

# These four request-driven maintenance routes deliberately use Google-signed OIDC at the
# private Cloud Run IAM boundary instead of an application shared secret. Keep this allowlist
# exact: one mapped method, one internal path, no gateway route, and matching Scheduler wiring.
$iamOnlyControllerContracts = @{
    "services/billing-service/src/main/java/com/custoking/ims/billingservice/api/internal/OutboxRelayTriggerController.java" = @{
        MethodMapping = '@PostMapping("/relay")'; Route = "/api/v1/internal/outbox/relay"; Service = "billing-service"
    }
    "services/operations-service/src/main/java/com/custoking/ims/operationsservice/api/internal/OutboxRelayTriggerController.java" = @{
        MethodMapping = '@PostMapping("/relay")'; Route = "/api/v1/internal/outbox/relay"; Service = "operations-service"
    }
    "services/platform-service/src/main/java/com/custoking/ims/platformservice/api/internal/AsyncWorkTriggerController.java" = @{
        MethodMapping = '@PostMapping("/drain")'; Route = "/api/v1/internal/async/drain"; Service = "platform-service"
    }
    "services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/internal/OutboxRelayTriggerController.java" = @{
        MethodMapping = '@PostMapping("/relay")'; Route = "/api/v1/internal/outbox/relay"; Service = "school-core-service"
    }
}

# A small number of controllers intentionally centralize a compound authorization contract
# in a private helper instead of repeating it in every mapped method. Keep these contracts
# explicit and exact so adding a helper named "authorize" cannot accidentally bypass the
# route-level scope audit. Every mapped method must invoke the helper, and the helper must
# continue to fail closed on the service token, actor role, and dedicated permission.
$centralizedScopedGuardContracts = @{
    "services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/StudentExportController.java" = @{
        MappedMethodCount = 3
        Invocation = 'authorize(token);'
        RequiredPatterns = @(
            'private\s+void\s+authorize\s*\(\s*String\s+token\s*\)',
            '!StringUtils\.hasText\(studentToken\)\s*\|\|\s*!studentToken\.equals\(token\)',
            'TenantScope\.requireOperationsOrSuperAdmin\(\);',
            'TenantScope\.requirePermission\("student:export"\);'
        )
    }
}

$serviceContracts = @(
    @{ Service = "platform-service"; Header = "X-Notification-Service-Token"; Secret = "notification-status-token"; Env = "NOTIFICATION_SERVICE_TOKEN" },
    @{ Service = "platform-service"; Header = "X-Audit-Service-Token"; Secret = "audit-ingest-token"; Env = "AUDIT_SERVICE_TOKEN" },
    @{ Service = "identity-service"; Header = "X-Identity-Service-Token"; Secret = "identity-introspection-token"; Env = "IDENTITY_SERVICE_TOKEN" },
    @{ Service = "school-core-service"; Header = "X-Tenant-School-Token"; Secret = "tenant-school-read-token"; Env = "TENANT_SCHOOL_SERVICE_TOKEN" },
    @{ Service = "school-core-service"; Header = "X-Student-Service-Token"; Secret = "student-read-token"; Env = "STUDENT_SERVICE_TOKEN" },
    @{ Service = "school-core-service"; Header = "X-Attendance-Service-Token"; Secret = "attendance-read-token"; Env = "ATTENDANCE_SERVICE_TOKEN" },
    @{ Service = "school-core-service"; Header = "X-Fee-Service-Token"; Secret = "fee-read-token"; Env = "FEE_SERVICE_TOKEN" },
    @{ Service = "school-core-service"; Header = "X-Catalog-Service-Token"; Secret = "catalog-read-token"; Env = "CATALOG_SERVICE_TOKEN" },
    # Phase 2: both token/header pairs are validated by the merged operations-service (accepts both).
    @{ Service = "operations-service"; Header = "X-Workflow-Service-Token"; Secret = "workflow-read-token"; Env = "WORKFLOW_SERVICE_TOKEN" },
    @{ Service = "operations-service"; Header = "X-Firefighting-Service-Token"; Secret = "firefighting-read-token"; Env = "FIREFIGHTING_SERVICE_TOKEN" },
    @{ Service = "platform-service"; Header = "X-Reporting-Service-Token"; Secret = "reporting-read-token"; Env = "REPORTING_SERVICE_TOKEN" },
    @{ Service = "billing-service"; Header = "X-Billing-Service-Token"; Secret = "billing-service-token"; Env = "BILLING_SERVICE_TOKEN" }
)

foreach ($contract in $serviceContracts) {
    if (-not $gateway.Contains($contract.Header) -or -not $gateway.Contains($contract.Env)) {
        $violations.Add("Gateway implementation missing service token contract for $($contract.Service): $($contract.Header) / $($contract.Env)")
    }
    if (-not $compose.Contains($contract.Env)) {
        $violations.Add("docker-compose.yml missing local service token env: $($contract.Env)")
    }
    if (-not $cloudRun.Contains($contract.Secret)) {
        $violations.Add("Cloud Run manifests missing Secret Manager token for $($contract.Service): $($contract.Secret)")
    }
}

$controllerFiles = Get-ChildItem -Path $ServicesRoot -Recurse -Filter "*Controller.java" |
        Where-Object { $_.FullName -notmatch "\\target\\" }

foreach ($file in $controllerFiles) {
    $source = Get-Content -Raw $file.FullName
    $relative = Resolve-Path -Relative $file.FullName
    $normalizedRelative = (($relative -replace "\\", "/") -replace "^\./", "")

    $iamOnlyContract = $iamOnlyControllerContracts[$normalizedRelative]
    if ($null -ne $iamOnlyContract) {
        $mappedMethodCount = [regex]::Matches($source, "@(GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)").Count
        if ($mappedMethodCount -ne 1 -or -not $source.Contains([string]$iamOnlyContract.MethodMapping)) {
            $violations.Add("IAM-only controller mapping drifted from its single approved method: $relative")
        }
        if (-not $source.Contains("Cloud Run IAM")) {
            $violations.Add("IAM-only controller does not declare its transport authentication boundary: $relative")
        }
        if ($gateway.Contains([string]$iamOnlyContract.Route)) {
            $violations.Add("IAM-only controller route is exposed through the API gateway: $($iamOnlyContract.Route)")
        }
        if (-not $asyncScheduler.Contains([string]$iamOnlyContract.Route) -or
            -not $asyncScheduler.Contains('roles/run.invoker') -or
            -not $asyncScheduler.Contains('--oidc-service-account-email=')) {
            $violations.Add("IAM-only controller lacks OIDC Scheduler/run.invoker wiring: $relative")
        }
        continue
    }

    $hasApprovedCentralizedScopedGuard = $false
    $centralizedGuardContract = $centralizedScopedGuardContracts[$normalizedRelative]
    if ($null -ne $centralizedGuardContract) {
        $mappedMethodCount = [regex]::Matches($source, "@(GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)").Count
        $guardInvocationCount = [regex]::Matches(
            $source,
            [regex]::Escape([string]$centralizedGuardContract.Invocation)
        ).Count
        $hasApprovedCentralizedScopedGuard =
            $mappedMethodCount -eq [int]$centralizedGuardContract.MappedMethodCount -and
            $guardInvocationCount -eq $mappedMethodCount

        foreach ($requiredPattern in $centralizedGuardContract.RequiredPatterns) {
            if ($source -notmatch $requiredPattern) {
                $hasApprovedCentralizedScopedGuard = $false
                $violations.Add("Centralized scoped authorization guard drifted in ${relative}: missing pattern $requiredPattern")
            }
        }

        if ($mappedMethodCount -ne [int]$centralizedGuardContract.MappedMethodCount) {
            $violations.Add("Centralized scoped authorization endpoint count drifted in ${relative}: expected $($centralizedGuardContract.MappedMethodCount), found $mappedMethodCount")
        }
        if ($guardInvocationCount -ne $mappedMethodCount) {
            $violations.Add("Every mapped endpoint must invoke the centralized scoped authorization guard: $relative")
        }
    }

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
        -not $hasApprovedCentralizedScopedGuard -and
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

Write-Host "Service authorization boundary audit passed: token-scoped routes fail closed and allowlisted scheduler routes require private Cloud Run OIDC wiring."
