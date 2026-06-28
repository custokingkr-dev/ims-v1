param(
    [switch]$RunBuilds,
    [switch]$RunServiceTests,
    [switch]$RunDbAudit,
    [switch]$RunLegacyCompatibilityAudit,
    [switch]$RunSmoke,
    [string[]]$BuildServices,
    [string[]]$TestServices,
    [string]$GatewayBaseUrl = "http://localhost",
    [string]$PostgresContainer = "custoking-postgres",
    [string]$Database = "postgres",
    [string]$DbUser = "postgres",
    [string]$LegacyCompatibilityOutputJson,
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
. (Join-Path $PSScriptRoot "microservice-build-catalog.ps1")
$serviceBuilds = @(Get-MicroserviceBuildCatalog)

function Invoke-Step {
    param(
        [string]$Name,
        [scriptblock]$Command
    )

    Write-Host ""
    Write-Host "==> $Name"
    $global:LASTEXITCODE = 0
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
    Write-Host "OK: $Name"
}

Push-Location $repoRoot
try {
    Invoke-Step "microservice runtime boundary audit" {
        & (Join-Path $PSScriptRoot "audit-microservice-runtime-boundaries.ps1")
    }

    Invoke-Step "deployment boundary audit" {
        & (Join-Path $PSScriptRoot "audit-deployment-boundaries.ps1")
    }

    Invoke-Step "compose profile audit" {
        & (Join-Path $PSScriptRoot "audit-compose-profiles.ps1")
    }

    Invoke-Step "service authorization boundary audit" {
        & (Join-Path $PSScriptRoot "audit-service-authorization-boundaries.ps1")
    }

Invoke-Step "service contract coverage audit" {
    & (Join-Path $PSScriptRoot "audit-service-contract-coverage.ps1")
}

Invoke-Step "service package shape audit" {
    & (Join-Path $PSScriptRoot "audit-service-package-shape.ps1")
}

Invoke-Step "runtime schema dependency baseline audit" {
    & (Join-Path $PSScriptRoot "audit-runtime-schema-dependency-baseline.ps1")
}

Invoke-Step "service schema default audit" {
    & (Join-Path $PSScriptRoot "audit-service-schema-defaults.ps1")
}

Invoke-Step "service datasource pool audit" {
    & (Join-Path $PSScriptRoot "audit-service-datasource-pool.ps1")
}

Invoke-Step "legacy public retirement readiness audit" {
    & (Join-Path $PSScriptRoot "audit-legacy-public-retirement-readiness.ps1")
}

Invoke-Step "request correlation and structured logging audit" {
    & (Join-Path $PSScriptRoot "audit-request-correlation-and-logging.ps1")
}

Invoke-Step "microservice build catalog audit" {
    & (Join-Path $PSScriptRoot "audit-microservice-build-catalog.ps1")
}

    Invoke-Step "microservice test catalog audit" {
        & (Join-Path $PSScriptRoot "audit-microservice-test-catalog.ps1")
    }

    Invoke-Step "rollback runbook audit" {
        & (Join-Path $PSScriptRoot "audit-rollback-runbook.ps1")
    }

    Invoke-Step "observability runbook audit" {
        & (Join-Path $PSScriptRoot "audit-observability-runbook.ps1")
    }

    Invoke-Step "promotion preflight audit" {
        & (Join-Path $PSScriptRoot "audit-promotion-preflight.ps1")
    }

    Invoke-Step "cloud run revision export audit" {
        & (Join-Path $PSScriptRoot "audit-cloud-run-revision-export.ps1")
    }

    Invoke-Step "image digest export audit" {
        & (Join-Path $PSScriptRoot "audit-image-digest-export.ps1")
    }

    Invoke-Step "cloud build evidence export audit" {
        & (Join-Path $PSScriptRoot "audit-cloud-build-evidence-export.ps1")
    }

    Invoke-Step "secret manager evidence export audit" {
        & (Join-Path $PSScriptRoot "audit-secret-manager-evidence-export.ps1")
    }

    Invoke-Step "production readiness evidence audit" {
        & (Join-Path $PSScriptRoot "audit-production-readiness-evidence.ps1")
    }

    Invoke-Step "production readiness bundle audit" {
        & (Join-Path $PSScriptRoot "audit-production-readiness-bundle.ps1")
    }

    Invoke-Step "real environment readiness preflight audit" {
        & (Join-Path $PSScriptRoot "audit-real-environment-readiness-preflight.ps1")
    }

    Invoke-Step "promotion bundle manifest audit" {
        & (Join-Path $PSScriptRoot "audit-promotion-bundle-manifest.ps1")
    }

    if ($RunDbAudit) {
        Invoke-Step "microservice DB boundary audit" {
            & (Join-Path $PSScriptRoot "audit-microservice-db-boundaries.ps1") `
                -PostgresContainer $PostgresContainer `
                -Database $Database `
                -User $DbUser
        }

        Invoke-Step "app_rt runtime role privilege audit" {
            & (Join-Path $PSScriptRoot "audit-app-rt-privileges.ps1") `
                -PostgresContainer $PostgresContainer `
                -Database $Database `
                -DbUser $DbUser
        }
    }

    if ($RunLegacyCompatibilityAudit) {
        $legacyAuditArgs = @{
            PostgresContainer = $PostgresContainer
            Database = $Database
            DbUser = $DbUser
            FailOnNeedsBackfill = $true
        }
        if ($LegacyCompatibilityOutputJson) {
            $legacyAuditArgs.OutputJson = $LegacyCompatibilityOutputJson
        }

        Invoke-Step "legacy public compatibility audit" {
            & (Join-Path $PSScriptRoot "audit-legacy-compatibility-state.ps1") @legacyAuditArgs
        }
    }

    if ($RunBuilds) {
        $selectedBuilds = $serviceBuilds
        if ($BuildServices -and $BuildServices.Count -gt 0) {
            $requested = @{}
            foreach ($serviceName in $BuildServices) {
                $requested[$serviceName] = $true
            }
            $selectedBuilds = @($serviceBuilds | Where-Object { $requested.ContainsKey($_.Name) })
            $selectedNames = @{}
            foreach ($build in $selectedBuilds) {
                $selectedNames[$build.Name] = $true
            }
            $unknown = @($BuildServices | Where-Object { -not $selectedNames.ContainsKey($_) })
            if ($unknown.Count -gt 0) {
                throw "Unknown build service(s): $($unknown -join ', ')."
            }
        }

        foreach ($build in $selectedBuilds) {
            Invoke-Step "docker build $($build.Name)" {
                $tag = "ims-verify-$($build.Name)"
                $args = @("build", "-t", $tag)
                if ($build.BuildArgs) {
                    foreach ($buildArg in $build.BuildArgs) {
                        $args += @("--build-arg", $buildArg)
                    }
                }
                $args += $build.Context
                docker @args
            }
        }
    }

    if ($RunServiceTests) {
        $testArgs = @{}
        if ($TestServices -and $TestServices.Count -gt 0) {
            $testArgs.Services = $TestServices
        }

        Invoke-Step "microservice service tests" {
            & (Join-Path $PSScriptRoot "invoke-microservice-tests.ps1") @testArgs
        }
    }

    if ($RunSmoke) {
        Invoke-Step "microservice feature smoke" {
            & (Join-Path $PSScriptRoot "smoke-microservice-features.ps1") `
                -GatewayBaseUrl $GatewayBaseUrl `
                -PostgresContainer $PostgresContainer `
                -Database $Database `
                -DbUser $DbUser `
                -TimeoutSeconds $TimeoutSeconds
        }

    }

    Write-Host ""
    Write-Host "Microservice migration verification completed."
} finally {
    Pop-Location
}
