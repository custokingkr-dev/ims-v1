param(
    [ValidateRange(1000, 25000000)]
    [int]$Rows = 1000000,
    [string]$PostgresImage = "postgres:16-alpine",
    [string]$OutputJson = "artifacts/capacity-gates/attendance-partition-rehearsal.json",
    # Optional workspace-relative host directory for PostgreSQL data. This lets large rehearsals use
    # a drive with enough capacity without changing Docker Desktop's global data-root configuration.
    [string]$DataDirectory = "",
    # Optional override for the host drive that backs Docker Desktop's native data volume. When it is
    # omitted the launcher resolves Docker's wsl\disk junction, including a relocated VHDX.
    [ValidatePattern('^$|^[A-Za-z]:?$')]
    [string]$DockerStorageDrive = "",
    [ValidatePattern('^[1-9][0-9]*GB$')]
    [string]$MaxWalSize = "1GB",
    [ValidateRange(4, 32)]
    [double]$MinimumHostFreeGiB = 8,
    # Measured by the checked-in low-peak 1M regression: target final relations plus rollback heap,
    # after source and registry are released but before target release.
    [ValidateRange(1, [long]::MaxValue)]
    [long]$PeakBytesPerMillionRows = 461496320
)

$ErrorActionPreference = "Stop"
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker is required for the isolated attendance partition rehearsal."
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$sqlPath = (Resolve-Path (Join-Path $repoRoot "load-tests/sql/rehearse-attendance-partitioning.sql")).Path
$containerName = "ims-attendance-partition-$([datetime]::UtcNow.ToString('yyyyMMddHHmmss'))"
$startedAt = [datetime]::UtcNow
$containerCreated = $false
$temporaryDataDirectoryCreated = $false
$resolvedDataDirectory = $null
$dataVolumeName = $null
$dataVolumeCreated = $false
$temporaryRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot "tmp"))
$minimumObservedHostFreeBytes = [long]::MaxValue
$storageDriveName = if ([string]::IsNullOrWhiteSpace($DataDirectory)) {
    if (-not [string]::IsNullOrWhiteSpace($DockerStorageDrive)) {
        $DockerStorageDrive.TrimEnd(':')
    } else {
        $dockerDiskDirectory = Join-Path $env:LOCALAPPDATA "Docker\wsl\disk"
        $dockerDiskItem = Get-Item -LiteralPath $dockerDiskDirectory -Force -ErrorAction SilentlyContinue
        $dockerHostPath = if ($null -ne $dockerDiskItem -and
            $null -ne $dockerDiskItem.Target -and @($dockerDiskItem.Target).Count -gt 0) {
            [string]@($dockerDiskItem.Target)[0]
        } elseif ($null -ne $dockerDiskItem) {
            $dockerDiskItem.FullName
        } else {
            # Docker Desktop's standard location when the data directory does not exist yet or cannot
            # be inspected. The explicit override remains available for nonstandard installations.
            Join-Path $env:LOCALAPPDATA "Docker\wsl\disk"
        }
        ([IO.Path]::GetPathRoot([IO.Path]::GetFullPath($dockerHostPath))).TrimEnd('\').TrimEnd(':')
    }
} else {
    ([IO.Path]::GetPathRoot([IO.Path]::GetFullPath(
          $(if ([IO.Path]::IsPathRooted($DataDirectory)) { $DataDirectory } else {
              Join-Path $repoRoot $DataDirectory
            })
        ))).TrimEnd('\').TrimEnd(':')
}
$storageDrive = Get-PSDrive -Name $storageDriveName -PSProvider FileSystem
$hostFreeBytesBefore = [long]$storageDrive.Free
$walGiB = [double]($MaxWalSize -replace 'GB$', '')
$projectedRelationBytes = [long][math]::Ceiling(
    $PeakBytesPerMillionRows * ($Rows / 1000000.0) * 1.10
)
$requiredHostBytes = $projectedRelationBytes +
    [long]($walGiB * 1GB) + [long]($MinimumHostFreeGiB * 1GB)
if ($hostFreeBytesBefore -lt $requiredHostBytes) {
    throw ("Projected rehearsal peak plus WAL and the $MinimumHostFreeGiB GiB safety floor requires " +
        "$([math]::Round($requiredHostBytes / 1GB, 2)) GiB on ${storageDriveName}:, but only " +
        "$([math]::Round($hostFreeBytesBefore / 1GB, 2)) GiB is free.")
}

if (-not [string]::IsNullOrWhiteSpace($DataDirectory)) {
    $candidate = if ([IO.Path]::IsPathRooted($DataDirectory)) {
        $DataDirectory
    } else {
        Join-Path $repoRoot $DataDirectory
    }
    $resolvedDataDirectory = [IO.Path]::GetFullPath($candidate)
    $temporaryPrefix = $temporaryRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) +
        [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedDataDirectory.StartsWith($temporaryPrefix,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "DataDirectory must resolve below the workspace temporary root $temporaryRoot."
    }
    if (Test-Path -LiteralPath $resolvedDataDirectory) {
        if (@(Get-ChildItem -LiteralPath $resolvedDataDirectory -Force).Count -ne 0) {
            throw "DataDirectory must be absent or empty: $resolvedDataDirectory"
        }
    } else {
        New-Item -ItemType Directory -Force -Path $resolvedDataDirectory | Out-Null
        $temporaryDataDirectoryCreated = $true
    }
}

try {
    if ($null -eq $resolvedDataDirectory) {
        $dataVolumeName = "$containerName-data"
        $existingVolume = @(& docker volume ls --quiet --filter "name=^$dataVolumeName$")
        if ($existingVolume.Count -ne 0) {
            throw "Refusing to reuse existing Docker volume $dataVolumeName."
        }
        & docker volume create `
            --label com.custoking.ims.disposable=attendance-partition-rehearsal `
            --label "com.custoking.ims.container=$containerName" `
            $dataVolumeName | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Could not create disposable Docker data volume $dataVolumeName." }
        $dataVolumeCreated = $true
    }

    $dockerArguments = @(
        "run", "--detach", "--rm", "--name", $containerName,
        "--env", "POSTGRES_PASSWORD=rehearsal",
        "--mount", "type=bind,source=$sqlPath,target=/rehearsal.sql,readonly"
    )
    if ($null -ne $resolvedDataDirectory) {
        $dockerArguments += @(
            "--mount", "type=bind,source=$resolvedDataDirectory,target=/var/lib/postgresql/data"
        )
    } else {
        $dockerArguments += @(
            "--mount", "type=volume,source=$dataVolumeName,target=/var/lib/postgresql/data"
        )
    }
    # Bulk migration rehearsal only: larger WAL/checkpoint intervals avoid Docker Desktop bind-mount
    # checkpoint churn. These settings apply solely to this disposable container and are not runtime
    # recommendations for production Cloud SQL.
    $dockerArguments += @(
        $PostgresImage,
        "-c", "max_wal_size=$MaxWalSize",
        "-c", "checkpoint_timeout=30min",
        "-c", "checkpoint_completion_target=0.9",
        "-c", "wal_compression=on"
    )
    & docker @dockerArguments | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not start isolated PostgreSQL rehearsal container." }
    $containerCreated = $true

    $ready = $false
    for ($attempt = 1; $attempt -le 90; $attempt++) {
        # The official image briefly starts a temporary initialization server. Do not mistake that
        # socket for the final postmaster or psql can race the init shutdown/restart boundary.
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $containerLogs = ((& docker logs $containerName 2>&1) -join "`n")
            $initComplete = $containerLogs.Contains("PostgreSQL init process complete; ready for start up.")
            & docker exec $containerName pg_isready -U postgres *> $null
            $acceptingConnections = $LASTEXITCODE -eq 0
        } finally {
            $ErrorActionPreference = $previousPreference
        }
        if ($initComplete -and $acceptingConnections) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw "PostgreSQL rehearsal container did not become ready." }

    $dockerExecutable = (Get-Command docker -ErrorAction Stop).Source
    $processInfo = [Diagnostics.ProcessStartInfo]::new()
    $processInfo.FileName = $dockerExecutable
    $processInfo.Arguments = "exec $containerName psql -X -q -t -A -U postgres " +
        "-v ON_ERROR_STOP=1 -v rehearsal_rows=$Rows -f /rehearsal.sql"
    $processInfo.UseShellExecute = $false
    $processInfo.CreateNoWindow = $true
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true
    $psqlProcess = [Diagnostics.Process]::new()
    $psqlProcess.StartInfo = $processInfo
    if (-not $psqlProcess.Start()) { throw "Could not start the isolated psql rehearsal process." }
    $stdoutTask = $psqlProcess.StandardOutput.ReadToEndAsync()
    $stderrTask = $psqlProcess.StandardError.ReadToEndAsync()
    while (-not $psqlProcess.HasExited) {
        $currentFreeBytes = [long](Get-PSDrive -Name $storageDriveName -PSProvider FileSystem).Free
        $minimumObservedHostFreeBytes = [math]::Min($minimumObservedHostFreeBytes, $currentFreeBytes)
        if ($currentFreeBytes -lt [long]($MinimumHostFreeGiB * 1GB)) {
            & docker rm --force $containerName *> $null
            $psqlProcess.WaitForExit(30000) | Out-Null
            throw "Host free space fell below the $MinimumHostFreeGiB GiB safety floor; the disposable rehearsal was stopped and cleanup started."
        }
        Start-Sleep -Seconds 5
    }
    $psqlProcess.WaitForExit()
    $psqlExitCode = $psqlProcess.ExitCode
    $lines = @(($stdoutTask.GetAwaiter().GetResult() -split "`r?`n") +
        ($stderrTask.GetAwaiter().GetResult() -split "`r?`n") | Where-Object {
          -not [string]::IsNullOrWhiteSpace([string]$_)
        })
    if ($psqlExitCode -ne 0) {
        throw "Attendance partition rehearsal failed with psql process exit $psqlExitCode`: $($lines -join [Environment]::NewLine)"
    }
    $marker = @($lines | Where-Object { [string]$_ -like "IMS_ATTENDANCE_PARTITION_REHEARSAL|*" })
    if ($marker.Count -ne 1) {
        throw "Attendance partition rehearsal emitted $($marker.Count) result markers; expected one."
    }
    $payload = ([string]$marker[0]).Substring("IMS_ATTENDANCE_PARTITION_REHEARSAL|".Length) |
        ConvertFrom-Json
    $required = @(
        $payload.rowCountsMatch,
        $payload.forwardChecksumMatches,
        $payload.rollbackChecksumMatches,
        $payload.uniqueSemanticsPassed,
        $payload.foreignKeyPassed,
        $payload.checkConstraintPassed,
        $payload.rlsPassed,
        $payload.rlsBypassPassed,
        $payload.partitionPruningPassed,
        $payload.defaultPartitionPresent,
        $payload.rollbackPassed
    )
    if ($required -contains $false) {
        throw "Attendance partition rehearsal returned a failed correctness gate."
    }

    $evidence = [ordered]@{
        generatedAtUtc = [datetime]::UtcNow.ToString("o")
        postgresImage = $PostgresImage
        requestedRows = $Rows
        durationSeconds = [math]::Round(([datetime]::UtcNow - $startedAt).TotalSeconds, 2)
        storageMode = if ($null -ne $resolvedDataDirectory) { "workspace-bind-mount" } else { "docker-data-root" }
        temporaryDataDirectory = $resolvedDataDirectory
        temporaryDataVolume = $dataVolumeName
        storagePreflight = [ordered]@{
            drive = "${storageDriveName}:"
            hostFreeGiBBefore = [math]::Round($hostFreeBytesBefore / 1GB, 2)
            projectedRelationPeakGiB = [math]::Round($projectedRelationBytes / 1GB, 2)
            configuredWalGiB = $walGiB
            minimumHostFreeGiB = $MinimumHostFreeGiB
            requiredHostGiB = [math]::Round($requiredHostBytes / 1GB, 2)
            minimumObservedHostFreeGiB = [math]::Round($minimumObservedHostFreeBytes / 1GB, 2)
        }
        disposablePostgresSettings = [ordered]@{
            maxWalSize = $MaxWalSize
            checkpointTimeout = "30min"
            checkpointCompletionTarget = 0.9
            walCompression = "on"
        }
        flywayOwner = "school-core-service/attendance"
        deploymentStatus = "prototype-only-not-a-production-migration"
        result = $payload
    }
    $outputPath = Join-Path $repoRoot $OutputJson
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outputPath) | Out-Null
    $evidence | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $outputPath -Encoding UTF8
    $evidence | ConvertTo-Json -Depth 6
} finally {
    if ($containerCreated) {
        & docker rm --force $containerName *> $null
    }
    if ($dataVolumeCreated -and $null -ne $dataVolumeName) {
        $volumeJson = @(& docker volume inspect $dataVolumeName 2>$null)
        if ($LASTEXITCODE -eq 0) {
            $volume = (($volumeJson -join "`n") | ConvertFrom-Json)[0]
            if ([string]$volume.Labels.'com.custoking.ims.container' -ne $containerName) {
                throw "Refusing to remove Docker volume whose rehearsal label does not match: $dataVolumeName"
            }
            & docker volume rm $dataVolumeName *> $null
            if ($LASTEXITCODE -ne 0) { throw "Could not remove disposable Docker data volume $dataVolumeName." }
        }
    }
    if ($temporaryDataDirectoryCreated -and $null -ne $resolvedDataDirectory) {
        $verifiedPath = [IO.Path]::GetFullPath($resolvedDataDirectory)
        $verifiedPrefix = $temporaryRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) +
            [IO.Path]::DirectorySeparatorChar
        if (-not $verifiedPath.StartsWith($verifiedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to clean data directory outside ${temporaryRoot}: $verifiedPath"
        }
        Remove-Item -LiteralPath $verifiedPath -Recurse -Force
    }
}
