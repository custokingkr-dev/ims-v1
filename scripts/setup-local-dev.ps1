param(
    [ValidateSet("none", "postgres", "core", "full")]
    [string]$ComposeProfile = "none",

    [switch]$SkipDependencyInstall,
    [switch]$SkipDockerBuild,
    [switch]$ResetData,
    [switch]$RemoveOrphans,
    [switch]$RunTests
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
. (Join-Path $PSScriptRoot "local-java.ps1")

function Require-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command is missing from PATH: $Name"
    }
}

function Assert-NodeRuntime {
    $versionText = (node --version).Trim()
    if ($versionText -notmatch '^v(?<major>\d+)\.') {
        throw "Unable to parse Node.js version from '$versionText'."
    }

    $major = [int]$Matches.major
    if ($major -ne 24) {
        throw "Node.js 24 LTS is required for frontend and api-gateway stability. Current version: $versionText. Install Node.js 24 and retry."
    }
}

function Run-Step {
    param(
        [string]$Name,
        [scriptblock]$Body
    )
    Write-Host ""
    Write-Host "==> $Name"
    & $Body
    Write-Host "OK: $Name"
}

function Get-RequiredSchemasForProfile {
    param([string]$Profile)

    $coreSchemas = @("identity", "tenant_school", "student", "attendance", "fee", "catalog")
    if ($Profile -eq "full") {
        return $coreSchemas + @("workflow", "firefighting", "reporting", "notification", "audit", "billing")
    }
    return $coreSchemas
}

Push-Location $repoRoot
try {
    Run-Step "verify required tools" {
        Require-Command git
        Require-Command docker
        Require-Command node
        Require-Command npm
        Assert-NodeRuntime
        $javaHome = Resolve-RequiredJavaHome -MinimumMajor 25
        $script:ResolvedJavaHome = $javaHome
        Write-Host "Using JAVA_HOME=$javaHome"
    }

    $previousJavaHome = $env:JAVA_HOME
    $previousPath = $env:Path
    $env:JAVA_HOME = $script:ResolvedJavaHome
    $env:Path = "$script:ResolvedJavaHome\bin;$env:Path"

    Run-Step "show local tool versions" {
        git --version
        docker --version
        docker compose version
        node --version
        npm --version
        java -version
    }

    Run-Step "ensure local .env exists" {
        $envFile = Join-Path $repoRoot ".env"
        $example = Join-Path $repoRoot ".env.example"
        if (-not (Test-Path $envFile)) {
            Copy-Item -Path $example -Destination $envFile
            Write-Host "Created .env from .env.example"
        } else {
            Write-Host ".env already exists; leaving it unchanged"
        }
    }

    if ($ResetData) {
        Run-Step "reset local compose data" {
            Write-Host "Removing known local Custoking containers and the ims-v1_postgres_data volume."
            $localContainers = @(
                "custoking-postgres",
                "custoking-identity-service",
                "custoking-school-core-service",
                "custoking-operations-service",
                "custoking-platform-service",
                "custoking-billing-service",
                "custoking-frontend",
                "custoking-api-gateway",
                "custoking-audit-service",
                "custoking-tenant-school-service",
                "custoking-student-service",
                "custoking-attendance-service",
                "custoking-fee-service",
                "custoking-catalog-service",
                "custoking-notification-service",
                "custoking-reporting-service"
            )
            foreach ($container in $localContainers) {
                $id = docker ps -aq --filter "name=^/$container$"
                if ($id) {
                    docker rm -f $container | Out-Null
                }
            }
            $volume = docker volume ls -q --filter "name=^ims-v1_postgres_data$"
            if ($volume) {
                docker volume rm ims-v1_postgres_data | Out-Null
            }
        }
    }

    if (-not $SkipDependencyInstall) {
        Run-Step "install frontend dependencies" {
            Push-Location (Join-Path $repoRoot "frontend")
            try {
                npm ci
            } finally {
                Pop-Location
            }
        }

        Run-Step "install api-gateway dependencies" {
            Push-Location (Join-Path $repoRoot "services\api-gateway")
            try {
                npm ci
            } finally {
                Pop-Location
            }
        }
    }

    if ($ComposeProfile -eq "postgres") {
        Run-Step "validate compose" {
            docker compose config --quiet
        }
        Run-Step "start postgres" {
            $composeArgs = @("compose", "up", "-d", "postgres")
            if ($RemoveOrphans) {
                $composeArgs += "--remove-orphans"
            }
            docker @composeArgs
        }
    } elseif ($ComposeProfile -in @("core", "full")) {
        Run-Step "validate compose profile $ComposeProfile" {
            docker compose --profile $ComposeProfile config --quiet
        }
        Run-Step "start compose profile $ComposeProfile" {
            $composeArgs = @("compose", "--profile", $ComposeProfile, "up", "-d")
            if (-not $SkipDockerBuild) {
                $composeArgs += "--build"
            }
            if ($RemoveOrphans) {
                $composeArgs += "--remove-orphans"
            }
            docker @composeArgs
        }
    } else {
        Run-Step "validate core compose profile" {
            docker compose --profile core config --quiet
        }
    }

    if ($ComposeProfile -in @("core", "full")) {
        Run-Step "ensure local runtime database grants" {
            $requiredSchemas = Get-RequiredSchemasForProfile -Profile $ComposeProfile
            & (Join-Path $repoRoot "scripts\ensure-app-rt-local.ps1") -RequiredSchemas $requiredSchemas
        }
    }

    if ($RunTests) {
        Run-Step "run repository test catalog" {
            & (Join-Path $repoRoot "scripts\invoke-microservice-tests.ps1")
        }
    }

    Write-Host ""
    Write-Host "Local setup completed."
    if ($ComposeProfile -eq "none") {
        Write-Host "Start the app with: powershell -ExecutionPolicy Bypass -File scripts\setup-local-dev.ps1 -ComposeProfile core -SkipDependencyInstall"
    } elseif ($ComposeProfile -eq "postgres") {
        Write-Host "Postgres is available on localhost:5432."
    } else {
        Write-Host "Open the app at http://localhost after containers become healthy."
    }
} finally {
    if (Get-Variable -Name previousJavaHome -Scope Local -ErrorAction SilentlyContinue) {
        $env:JAVA_HOME = $previousJavaHome
    }
    if (Get-Variable -Name previousPath -Scope Local -ErrorAction SilentlyContinue) {
        $env:Path = $previousPath
    }
    Pop-Location
}
