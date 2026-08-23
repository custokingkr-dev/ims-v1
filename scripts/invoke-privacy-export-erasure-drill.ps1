param(
    [string]$OutputJson = ""
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$serviceRoot = Join-Path $repositoryRoot "services/school-core-service"
$maven = if ($env:OS -eq "Windows_NT") { Join-Path $repositoryRoot "mvnw.cmd" } else { Join-Path $repositoryRoot "mvnw" }
$testSelector = 'StudentExportArchiveWriterTest,StudentOnboardingScaleCertificationIntegrationTest#inMemoryExportIsChecksummedAndSchoolCoreEraseLeavesControlTenantUntouched'
. (Join-Path $PSScriptRoot "local-java.ps1")
$previousJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = Resolve-RequiredJavaHome -MinimumMajor 25

Push-Location $serviceRoot
try {
    $output = @(& $maven -B "-Donboarding.scale.certification=true" "-Dtest=$testSelector" test --no-transfer-progress 2>&1)
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { Write-Host $_ }
    if ($exitCode -ne 0) { throw "Synthetic privacy export/erasure drill failed with exit code $exitCode." }

    $marker = @($output | Where-Object { [string]$_ -match '^IMS_ONBOARDING_ERASE_RESULT\|' }) | Select-Object -Last 1
    if (-not $marker) {
        throw "Synthetic erase evidence marker was not produced. Docker/Testcontainers may be unavailable; skipped tests are not evidence."
    }
    $pattern = '^IMS_ONBOARDING_ERASE_RESULT\|synthetic=true\|idempotent=true\|exportRows=(\d+)\|sha256=([0-9a-f]{64})\|targetBefore=(\{.*?\})\|targetAfter=(\{.*?\})\|controlBefore=(\{.*?\})\|controlAfter=(\{.*\})$'
    $match = [regex]::Match([string]$marker, $pattern)
    if (-not $match.Success) { throw "Synthetic erase evidence marker did not match the reviewed non-sensitive schema." }

    $targetBefore = $match.Groups[3].Value | ConvertFrom-Json -AsHashtable
    $targetAfter = $match.Groups[4].Value | ConvertFrom-Json -AsHashtable
    $controlBefore = $match.Groups[5].Value | ConvertFrom-Json -AsHashtable
    $controlAfter = $match.Groups[6].Value | ConvertFrom-Json -AsHashtable
    if (@($targetBefore.Values | Where-Object { [long]$_ -gt 0 }).Count -eq 0) {
        throw "Synthetic target fixture was empty before erasure."
    }
    if (@($targetAfter.Values | Where-Object { [long]$_ -ne 0 }).Count -gt 0) {
        throw "One or more school-core target counts remained after erasure."
    }
    if (($controlBefore | ConvertTo-Json -Compress) -ne ($controlAfter | ConvertTo-Json -Compress)) {
        throw "Control-tenant counts changed during the synthetic erasure drill."
    }

    $evidence = [ordered]@{
        generatedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        synthetic = $true
        scope = "school-core schemas only"
        exportArchiveContractPassed = $true
        exportRowCount = [int]$match.Groups[1].Value
        exportSha256 = $match.Groups[2].Value
        eraseIdempotent = $true
        targetBefore = $targetBefore
        targetAfter = $targetAfter
        controlTenantPreserved = $true
        productionOffboardingCertified = $false
    }
    if (-not [string]::IsNullOrWhiteSpace($OutputJson)) {
        $resolvedOutput = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $OutputJson))
        [System.IO.File]::WriteAllText($resolvedOutput, ($evidence | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
    }
    $evidence | ConvertTo-Json -Depth 8
}
finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
}
