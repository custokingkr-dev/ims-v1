param(
    [string] $VerifierPath = (Join-Path $PSScriptRoot "verify-dev-onboarding-10000.ps1")
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $VerifierPath -PathType Leaf)) {
    throw "VerifierPath does not exist: $VerifierPath"
}
$resolvedVerifier = (Resolve-Path -LiteralPath $VerifierPath).Path
$tokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
    $resolvedVerifier,
    [ref]$tokens,
    [ref]$parseErrors) | Out-Null
if (@($parseErrors).Count -gt 0) {
    $messages = @($parseErrors | ForEach-Object { $_.Message }) -join "; "
    throw "Verifier parser audit failed: $messages"
}

$source = Get-Content -LiteralPath $resolvedVerifier -Raw
$requiredMarkers = @(
    '$batchCount = 20',
    '$rowsPerBatch = 500',
    'for ($batchNumber = 1; $batchNumber -le $batchCount; $batchNumber++)',
    'AdmissionNo = "ONB-$RunId-',
    'Class = "Scale Class $classNumber"',
    'Section = "Scale $($sectionNumber.ToString(''0000''))"',
    'PLAN_ONLY_NO_NETWORK',
    '/api/v1/students/imports/preview',
    '/api/v1/students/imports/confirm',
    '/api/v1/students/imports/batches',
    '/api/v1/students/imports/usage',
    'SchoolId must be in the reserved synthetic range (>= 900000000).',
    'Remote dev onboarding writes require -AllowRemoteDevWrites.',
    'Production-labelled hosts are forbidden for this dev-only verifier.',
    'ExpectedDevHost must exactly match the remote dev gateway host.',
    '[Environment]::GetEnvironmentVariable',
    'completed final-batch retry',
    'previewedBatches = [long]$batchCount',
    'attemptedRows = [long]$totalRows',
    'insertedRows = [long]$totalRows',
    '$syntheticRunLabel = "ONB-$runId"',
    'syntheticRunLabel = $syntheticRunLabel',
    '[System.IO.FileMode]::CreateNew',
    'containsTokensOrStudentPii = $false',
    'cleanupRequired = $true'
)
$violations = [System.Collections.Generic.List[string]]::new()
foreach ($marker in $requiredMarkers) {
    if (-not $source.Contains($marker)) {
        $violations.Add("required source marker missing: $marker") | Out-Null
    }
}

$prohibitedPatterns = @(
    '(?im)Write-(Output|Host|Verbose|Debug).*(accessToken|loginPassword|fileToken)',
    '(?im)Set-Content\s+.*EvidencePath',
    '(?im)Out-File\s+.*EvidencePath',
    '(?im)Remove-Item\s+.*(student|import|batch)',
    '(?im)GatewayBaseUrl\s*=\s*["'']https://[^"'']*prod'
)
foreach ($pattern in $prohibitedPatterns) {
    if ($source -match $pattern) {
        $violations.Add("prohibited source pattern present: $pattern") | Out-Null
    }
}
if ($violations.Count -gt 0) {
    throw "Verifier source audit failed:`n - $($violations -join "`n - ")"
}

$admissionNumbers = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$classes = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$sections = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
for ($batchNumber = 1; $batchNumber -le 20; $batchNumber++) {
    for ($rowIndex = 1; $rowIndex -le 500; $rowIndex++) {
        $globalIndex = (($batchNumber - 1) * 500) + $rowIndex
        $sectionNumber = [int][Math]::Ceiling($globalIndex / 40.0)
        $classNumber = (($sectionNumber - 1) % 12) + 1
        if (-not $admissionNumbers.Add("ONB-audit-$($globalIndex.ToString('00000'))")) {
            throw "Synthetic generation audit found a duplicate admission number."
        }
        $classes.Add("Scale Class $classNumber") | Out-Null
        $sections.Add("Scale $($sectionNumber.ToString('0000'))") | Out-Null
    }
}
if ($admissionNumbers.Count -ne 10000 -or $classes.Count -ne 12 -or $sections.Count -ne 250) {
    throw "Synthetic generation audit did not produce 10,000 unique rows across 12 classes and 250 sections."
}

$powershellExe = (Get-Process -Id $PID).Path
if ([string]::IsNullOrWhiteSpace($powershellExe) -or -not (Test-Path -LiteralPath $powershellExe)) {
    throw "Could not resolve the current PowerShell executable for plan-only validation."
}

$planOutput = @(& $powershellExe -NoProfile -ExecutionPolicy Bypass -File $resolvedVerifier 2>&1)
if ($LASTEXITCODE -ne 0 -or ($planOutput -join "`n") -notmatch 'PLAN_ONLY_NO_NETWORK') {
    throw "Verifier plan-only execution failed or did not prove no-network mode."
}

function Assert-NegativeGate {
    param(
        [string] $Name,
        [string[]] $Arguments,
        [string] $ExpectedMessage
    )
    $previousErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = @(& $powershellExe -NoProfile -ExecutionPolicy Bypass -File $resolvedVerifier @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    $text = $output -join "`n"
    if ($exitCode -eq 0) {
        throw "$Name unexpectedly passed."
    }
    if ($text -notmatch [regex]::Escape($ExpectedMessage)) {
        throw "$Name did not fail for the expected reason."
    }
}

Assert-NegativeGate -Name "production host rejection" -Arguments @(
    '-Execute',
    '-GatewayBaseUrl', 'https://custoking-api-gateway-prod.example.invalid',
    '-ExpectedDevHost', 'custoking-api-gateway-prod.example.invalid',
    '-SchoolId', '900000000',
    '-AllowRemoteDevWrites'
) -ExpectedMessage "Production-labelled hosts are forbidden"

Assert-NegativeGate -Name "reserved school rejection" -Arguments @(
    '-Execute',
    '-GatewayBaseUrl', 'https://custoking-api-gateway-dev.example.invalid',
    '-ExpectedDevHost', 'custoking-api-gateway-dev.example.invalid',
    '-SchoolId', '899999999',
    '-AllowRemoteDevWrites'
) -ExpectedMessage "reserved synthetic range"

Assert-NegativeGate -Name "remote write opt-in rejection" -Arguments @(
    '-Execute',
    '-GatewayBaseUrl', 'https://custoking-api-gateway-dev.example.invalid',
    '-ExpectedDevHost', 'custoking-api-gateway-dev.example.invalid',
    '-SchoolId', '900000000'
) -ExpectedMessage "AllowRemoteDevWrites"

Assert-NegativeGate -Name "expected host mismatch rejection" -Arguments @(
    '-Execute',
    '-GatewayBaseUrl', 'https://custoking-api-gateway-dev.example.invalid',
    '-ExpectedDevHost', 'different-dev.example.invalid',
    '-SchoolId', '900000000',
    '-AllowRemoteDevWrites'
) -ExpectedMessage "ExpectedDevHost must exactly match"

Write-Output "IMS_DEV_ONBOARDING_VERIFIER_AUDIT|passed=true|parser=true|planOnly=true|source=true|syntheticRows=10000|negativeGates=4"
