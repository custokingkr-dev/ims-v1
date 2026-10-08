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

# Ignore formatting whitespace while preserving quoted Java literals (notably "Bearer ").
function Normalize-JavaContract {
    param([string]$Text)
    [regex]::Replace($Text, '"(?:\\.|[^"\\])*"|\s+', {
        param($match)
        if ($match.Value.StartsWith('"')) { return $match.Value }
        return ''
    })
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

# Exact additional machine capabilities: identity-directory has only two label reads;
# password recovery draining has one bounded scheduler action. No wildcard controller exemptions.
$iamOnlyControllerContracts["services/identity-service/src/main/java/com/custoking/ims/identityservice/api/internal/PasswordResetDrainController.java"] = @{
    MethodMapping = '@PostMapping("/drain")'; Route = "/api/v1/internal/password-reset/drain"; Service = "identity-service"
    CallerEnv = "PASSWORD_RESET_DRAIN_CALLER_SERVICE_ACCOUNTS"; Scheduler = $false
}
$iamOnlyControllerContracts["services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/internal/IdentityDirectoryController.java"] = @{
    MethodMappings = @('@GetMapping("/schools/{id}")','@GetMapping("/zones/{id}")'); Route = "/api/v1/internal/identity-directory"; Service = "school-core-service"
    CallerEnv = "IDENTITY_DIRECTORY_CALLER_SERVICE_ACCOUNTS"; DirectoryPeer = $true; Scheduler = $false
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
        $expectedMappings = @($iamOnlyContract.MethodMappings)
        if ($null -ne $iamOnlyContract.MethodMapping) { $expectedMappings = @([string]$iamOnlyContract.MethodMapping) }
        if ($mappedMethodCount -ne $expectedMappings.Count -or @($expectedMappings | Where-Object { -not $source.Contains($_) }).Count -gt 0) {
            $violations.Add("IAM-only controller mapping drifted from its single approved method: $relative")
        }
        if (-not $source.Contains("Cloud Run IAM")) {
            $violations.Add("IAM-only controller does not declare its transport authentication boundary: $relative")
        }
        if ($gateway.Contains([string]$iamOnlyContract.Route)) {
            $violations.Add("IAM-only controller route is exposed through the API gateway: $($iamOnlyContract.Route)")
        }
        if ($iamOnlyContract.Scheduler -ne $false -and (-not $asyncScheduler.Contains([string]$iamOnlyContract.Route) -or
            -not $asyncScheduler.Contains('roles/run.invoker') -or
            -not $asyncScheduler.Contains('--oidc-service-account-email='))) {
            $violations.Add("IAM-only controller lacks OIDC Scheduler/run.invoker wiring: $relative")
        }
        $callerEnv = [string]$iamOnlyContract.CallerEnv
        if ([string]::IsNullOrEmpty($callerEnv)) {
            $callerEnv = if ($iamOnlyContract.Service -eq "platform-service") { "ASYNC_DRAIN_CALLER_SERVICE_ACCOUNTS" } else { "OUTBOX_RELAY_CALLER_SERVICE_ACCOUNTS" }
        }
        $securityRoot = Join-Path (Split-Path (Split-Path $file.FullName -Parent) -Parent) "../security"
        $filterPath = Join-Path $securityRoot "MachineCallerFilter.java"
        $filter = Read-RequiredFile $filterPath
        foreach ($required in @('@Component','extends OncePerRequestFilter','GOOGLE.verify(token)','https://accounts.google.com','email_verified','SERVICE_OIDC_AUDIENCES','getHeader("Authorization")','verifier.apply','allowedServiceAccountPlaceholder')) {
            if ($required -eq 'allowedServiceAccountPlaceholder') { $required = $callerEnv }
            if (-not $filter.Contains($required)) { $violations.Add("Machine caller proof missing in ${relative}: $required") }
        }
        if (-not $filter.Contains([string]$iamOnlyContract.Route)) { $violations.Add("Machine route lacks exact signed caller filter: $relative") }
        $manifest = Read-RequiredFile (Join-Path $CloudRunDirectory "$($iamOnlyContract.Service).yaml")
        if (-not $manifest.Contains($callerEnv) -or -not $manifest.Contains("SERVICE_OIDC_AUDIENCES")) { $violations.Add("Machine route lacks exact caller/audience deployment configuration: $relative") }
        if ($iamOnlyContract.DirectoryPeer) {
            $peer=Read-RequiredFile "services/identity-service/src/main/java/com/custoking/ims/identityservice/infrastructure/TenantSchoolClient.java"
            foreach ($required in @('/api/v1/internal/identity-directory/schools/','/api/v1/internal/identity-directory/zones/','headers.setBearerAuth(identityToken)')) {
                if (-not $peer.Contains($required)) { $violations.Add("Directory route lacks signed identity peer proof: $required") }
            }
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

    # One exact factored report authority, never a controller-name exemption. Verify the
    # complete fail-closed helper and its production OIDC/purpose binding, then prove that
    # the only mapped method verifies before accepting/processing the asynchronous body.
    if ($normalizedRelative -eq "services/platform-service/src/main/java/com/custoking/ims/platformservice/api/internal/GenericNotificationReportController.java") {
        $hasApprovedCentralizedScopedGuard = $true
        $reportSource = ($source -replace '(?s)/\*.*?\*/','') -replace '(?m)^\s*//[^\r\n]*',''
        $compact = Normalize-JavaContract $reportSource
        $authorityPath = Join-Path $ServicesRoot "platform-service/src/main/java/com/custoking/ims/platformservice/application/NotificationReportAuthority.java"
        $authority = Normalize-JavaContract (((Read-RequiredFile $authorityPath) -replace '(?s)/\*.*?\*/','') -replace '(?m)^\s*//[^\r\n]*','')
        $authorityFragments = @(
            'this.identities=identities;this.enabled=enabled;this.token=value(token);this.sharedToken=value(sharedToken);this.providerToken=value(providerToken);this.callers=accounts(callers);this.forbidden=accounts(forbidden);',
            'public VerifiedReporter verify(String authorization, String supplied) { if(!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification report reconciliation is unavailable"); int length=token.getBytes(StandardCharsets.UTF_8).length; if(length<32 || length>512 || token.isBlank() || equal(token,sharedToken) || equal(token,providerToken) || callers.isEmpty() || !Collections.disjoint(callers,forbidden) || !equal(token,supplied)) deny(); String principal=authorization!=null && authorization.startsWith("Bearer ") ? identities.verifiedEmail(authorization.substring(7)).orElse(null):null; if(principal==null || !callers.contains(principal.toLowerCase(Locale.ROOT))) deny(); return new VerifiedReporter(principal.toLowerCase(Locale.ROOT)); }',
            'private VerifiedReporter(String principal) { this.principal=principal; }',
            'private static void deny() { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid notification report authority"); }',
            'private static boolean equal(String expected,String supplied) { return supplied!=null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)); }',
            'private static String value(String value) { return value==null?"":value; }',
            'private static Set<String> accounts(String input) { return Arrays.stream(value(input).split(",")).map(String::trim).filter(v->!v.isEmpty()).map(v->v.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet()); }'
        )
        foreach ($fragment in $authorityFragments) {
            if (-not $authority.Contains((Normalize-JavaContract $fragment))) {
                $hasApprovedCentralizedScopedGuard=$false
                $violations.Add("Factored report authority fail-closed contract drifted: $relative")
            }
        }
        foreach ($fragment in @(
            'private final com.custoking.ims.platformservice.application.NotificationReportAuthority authority;',
            'this.authority=new com.custoking.ims.platformservice.application.NotificationReportAuthority(identities,enabled,reportToken,sharedToken,providerToken,callers,forbiddenCallers);',
            '@RequestMapping("/api/v1/internal/notifications/reports")', '@PostMapping(value="/reconcile",consumes="application/json")',
            'GoogleIdentityTokenVerifier identities', '${notification.report-reconciliation.enabled:false}',
            '${notification.report-reconciliation.token:}', '${notification.status.token:}', '${notification.msg91.auth-key:}',
            'NOTIFICATION_REPORT_CALLER_SERVICE_ACCOUNTS', 'USER_CONTEXT_CALLER_SERVICE_ACCOUNTS', 'NOTIFICATION_DELIVERY_CALLER_SERVICE_ACCOUNTS',
            'service.reconcile(report,reporter);'
        )) {
            if (-not $compact.Contains((Normalize-JavaContract $fragment))) {
                $hasApprovedCentralizedScopedGuard=$false
                $violations.Add("Factored report controller binding drifted: $relative")
            }
        }
        if ([regex]::Matches($reportSource,'@(GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)').Count -ne 1 -or
            [regex]::Matches($reportSource,'@RequestMapping').Count -ne 1 -or
             $reportSource -notmatch 'HttpServletRequest request,HttpServletResponse response\) throws java\.io\.IOException\s*\{\s*var reporter=authority\.verify\(authorization,token\);' -or
            [regex]::Matches($reportSource,'authority\.verify\(authorization,token\)').Count -ne 1 -or
            $reportSource.IndexOf('authority.verify(authorization,token)') -ge $reportSource.IndexOf('BODY.receive(request,response,body->')) {
            $hasApprovedCentralizedScopedGuard=$false
            $violations.Add("Factored report authority must guard the sole route before body processing: $relative")
        }
        $reportFilter=Read-RequiredFile (Join-Path $ServicesRoot "platform-service/src/main/java/com/custoking/ims/platformservice/security/MachineCallerFilter.java")
        $oidc=Read-RequiredFile (Join-Path $ServicesRoot "platform-service/src/main/java/com/custoking/ims/platformservice/security/GoogleIdentityTokenVerifier.java")
        $reportFilter=($reportFilter -replace '(?s)/\*.*?\*/','') -replace '(?m)^\s*//[^\r\n]*',''
        $oidc=($oidc -replace '(?s)/\*.*?\*/','') -replace '(?m)^\s*//[^\r\n]*',''
        $oidcCompact=Normalize-JavaContract $oidc
        $verifiedEmailContract='public Optional<String> verifiedEmail(String idToken) { if (audiences.isEmpty()) { return Optional.empty(); } try { var payload = tokenVerifier.verify(idToken).getPayload(); if (!CallerIdentity.audienceAllowed(payload.getAudience(), audiences)) { return Optional.empty(); } if (!Boolean.TRUE.equals(payload.get("email_verified"))) { return Optional.empty(); } return Optional.ofNullable((String) payload.get("email")); } catch (TokenVerifier.VerificationException e) { log.debug("oidc.token-invalid reason={}", e.getMessage()); return Optional.empty(); } }'
        if (-not $oidcCompact.Contains((Normalize-JavaContract $verifiedEmailContract))) { $hasApprovedCentralizedScopedGuard=$false; $violations.Add("Factored report OIDC verification contract drifted: $relative") }
        foreach ($fragment in @('if(path.equals("/api/v1/internal/notifications/reports/reconcile")) return "NOTIFICATION_REPORT_CALLER_SERVICE_ACCOUNTS";','GOOGLE.verify(token)','SERVICE_OIDC_AUDIENCES','email_verified','https://accounts.google.com')) {
            if (-not $reportFilter.Contains($fragment)) { $hasApprovedCentralizedScopedGuard=$false; $violations.Add("Factored report dedicated signed purpose drifted: $relative") }
        }
        foreach ($fragment in @('TokenVerifier.newBuilder().setIssuer(GOOGLE_ISSUER).build()','https://accounts.google.com','if (audiences.isEmpty())','tokenVerifier.verify(idToken).getPayload()','if (!CallerIdentity.audienceAllowed(payload.getAudience(), audiences))','if (!Boolean.TRUE.equals(payload.get("email_verified")))','return Optional.empty();')) {
            if (-not $oidc.Contains($fragment)) { $hasApprovedCentralizedScopedGuard=$false; $violations.Add("Factored report OIDC verification contract drifted: $relative") }
        }
        if ($gateway.Contains('/api/v1/internal/notifications/reports')) { $hasApprovedCentralizedScopedGuard=$false; $violations.Add("Factored report route must not be gateway-exposed: $relative") }
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

foreach ($serviceName in @("identity-service","school-core-service","operations-service","platform-service","billing-service")) {
    $filterFile=Get-ChildItem (Join-Path $ServicesRoot "$serviceName/src/main/java") -Recurse -Filter MachineCallerFilter.java | Select-Object -First 1
    if ($null -eq $filterFile) { $violations.Add("Missing deployed principal carrier guard: $serviceName"); continue }
    $filter=Read-RequiredFile $filterFile.FullName
    foreach ($required in @('env.matchesProfiles("prod","dev")','env.getProperty("K_SERVICE")','X-IMS-Principal-Carrier-Token','USER_CONTEXT_CALLER_SERVICE_ACCOUNTS','PRINCIPAL_CARRIER_REJECTED','GOOGLE.verify(token)')) {
        if (-not $filter.Contains($required)) { $violations.Add("Principal carrier proof missing in ${serviceName}: $required") }
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Service authorization boundary violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Service authorization boundary audit passed: token-scoped routes fail closed; exact machine capabilities require signed OIDC callers and deployed user context requires a signed authorized carrier."
