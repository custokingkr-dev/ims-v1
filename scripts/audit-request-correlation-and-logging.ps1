$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
. (Join-Path $PSScriptRoot "microservice-build-catalog.ps1")

$violations = New-Object System.Collections.Generic.List[string]
$javaServices = @(Get-MicroserviceBuildCatalog | Where-Object {
    $_.Name -ne "frontend" -and $_.Name -ne "api-gateway"
})

foreach ($service in $javaServices) {
    $serviceRoot = Join-Path $repoRoot $service.Context
    $pom = Join-Path $serviceRoot "pom.xml"
    $logback = Join-Path $serviceRoot "src/main/resources/logback-spring.xml"
    $filter = Get-ChildItem -Path (Join-Path $serviceRoot "src/main/java") -Recurse -Filter "RequestCorrelationFilter.java" -ErrorAction SilentlyContinue | Select-Object -First 1

    if (-not (Test-Path $pom)) {
        $violations.Add("$($service.Name) is missing pom.xml")
        continue
    }

    $pomText = Get-Content -Raw -Path $pom
    if (-not $pomText.Contains("logstash-logback-encoder")) {
        $violations.Add("$($service.Name) is missing logstash-logback-encoder dependency")
    }

    if (-not $filter) {
        $violations.Add("$($service.Name) is missing RequestCorrelationFilter.java")
    } else {
        $filterText = Get-Content -Raw -Path $filter.FullName
        foreach ($required in @("X-Request-Id", "MDC.put(`"requestId`"", "response.setHeader", "MDC.remove(`"requestId`"")) {
            if (-not $filterText.Contains($required)) {
                $violations.Add("$($service.Name) request correlation filter missing: $required")
            }
        }
    }

    if (-not (Test-Path $logback)) {
        $violations.Add("$($service.Name) is missing logback-spring.xml")
    } else {
        $logbackText = Get-Content -Raw -Path $logback
        foreach ($required in @("LoggingEventCompositeJsonEncoder", "<mdc/>", "spring.application.name")) {
            if (-not $logbackText.Contains($required)) {
                $violations.Add("$($service.Name) logback-spring.xml missing: $required")
            }
        }
    }
}

$gateway = Get-Content -Raw -Path (Join-Path $repoRoot "services/api-gateway/server.js")
foreach ($required in @("randomUUID", "x-request-id", "outboundHeaders(req, requestId)", "headers['x-request-id'] = requestId")) {
    if (-not $gateway.Contains($required)) {
        $violations.Add("api-gateway missing request-id propagation contract: $required")
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Request correlation and structured logging violations found:"
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host "Request correlation and structured logging audit passed for $($javaServices.Count) Java services and api-gateway."
