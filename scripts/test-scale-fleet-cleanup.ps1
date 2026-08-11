param(
    [string]$Docker = "docker",
    [string]$PostgresImage = "postgres:16-alpine"
)

$ErrorActionPreference = "Stop"
$containerName = "ims-scale-cleanup-test-$(([guid]::NewGuid()).ToString('N').Substring(0, 10))"
$containerStarted = $false
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$cleanupSql = Join-Path $repositoryRoot "load-tests\sql\cleanup-scale-fleet.sql"
$fixtureSql = Join-Path $repositoryRoot "load-tests\sql\test-cleanup-scale-fleet.sql"

function Invoke-ExpectedFailure {
    param([string[]]$Arguments)

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = @(& $Docker @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Output = $output }
}

try {
    & $Docker run -d --rm --name $containerName `
        -e POSTGRES_PASSWORD=postgres `
        -e POSTGRES_DB=cleanup_test `
        $PostgresImage | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not start disposable PostgreSQL container." }
    $containerStarted = $true

    $ready = $false
    for ($attempt = 1; $attempt -le 40; $attempt++) {
        $probe = Invoke-ExpectedFailure -Arguments @(
            "exec", $containerName, "pg_isready", "-U", "postgres", "-d", "cleanup_test"
        )
        if ($probe.ExitCode -eq 0) { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw "Disposable PostgreSQL 16 did not become ready." }

    & $Docker cp $cleanupSql "${containerName}:/tmp/cleanup-scale-fleet.sql" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not copy cleanup SQL into the test container." }
    & $Docker cp $fixtureSql "${containerName}:/tmp/test-cleanup-scale-fleet.sql" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not copy fixture SQL into the test container." }

    $successInvocation = Invoke-ExpectedFailure -Arguments @(
        "exec", $containerName, "psql", "-X", "-q", "-t", "-A", "-v", "ON_ERROR_STOP=1",
        "-U", "postgres", "-d", "cleanup_test", "-f", "/tmp/test-cleanup-scale-fleet.sql"
    )
    $successOutput = $successInvocation.Output
    if ($successInvocation.ExitCode -ne 0) {
        throw "Cleanup success/idempotence fixture failed: $($successOutput -join [Environment]::NewLine)"
    }

    $cleanupMarkers = @($successOutput | Where-Object { $_ -like "IMS_SCALE_CLEANUP|*" })
    $testMarker = @($successOutput | Where-Object { $_ -like "IMS_SCALE_CLEANUP_TEST|*" })
    if ($cleanupMarkers.Count -ne 2 -or $testMarker.Count -ne 1) {
        throw "Expected two cleanup markers and one test marker."
    }
    $firstPass = $cleanupMarkers[0].Substring("IMS_SCALE_CLEANUP|".Length) | ConvertFrom-Json
    $secondPass = $cleanupMarkers[1].Substring("IMS_SCALE_CLEANUP|".Length) | ConvertFrom-Json
    $testResult = $testMarker[0].Substring("IMS_SCALE_CLEANUP_TEST|".Length) | ConvertFrom-Json

    $firstImportRows = $firstPass.before.PSObject.Properties["student.import_rows"].Value.scope
    $firstDeletedImportRows = $firstPass.deleted.PSObject.Properties["student.import_rows"].Value
    $firstDimStudents = $firstPass.deleted.PSObject.Properties["reporting.dim_student"].Value
    if ($firstImportRows -ne 1000 -or $firstDeletedImportRows -ne 1000 -or $firstDimStudents -ne 500) {
        throw "First-pass import/projection counts do not match the fixture."
    }
    foreach ($property in $secondPass.deleted.PSObject.Properties) {
        if ([long]$property.Value -ne 0) {
            throw "Second pass was not idempotent: $($property.Name) deleted $($property.Value)."
        }
    }

    & $Docker exec $containerName psql -X -q -v ON_ERROR_STOP=1 `
        -U postgres -d cleanup_test -c `
        "CREATE TABLE cleanup_test.unhandled_projection (id text PRIMARY KEY, school_id bigint); INSERT INTO cleanup_test.unhandled_projection VALUES ('residue',900000000); INSERT INTO tenant_school.schools VALUES (900000000,'SCALE-000'),(900000001,'SCALE-001'); INSERT INTO reporting.dim_school VALUES (900000000);" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not seed the residue rollback fixture." }

    $residueFailure = Invoke-ExpectedFailure -Arguments @(
        "exec", $containerName, "psql", "-X", "-q", "-t", "-A", "-v", "ON_ERROR_STOP=1",
        "-v", "base_school_id=900000000", "-v", "school_count=2",
        "-U", "postgres", "-d", "cleanup_test", "-f", "/tmp/cleanup-scale-fleet.sql"
    )
    if ($residueFailure.ExitCode -eq 0 -or
        -not ($residueFailure.Output -match "unhandled scale school residue remains")) {
        throw "Unhandled-residue case did not fail closed."
    }
    $residueRollbackJson = ((& $Docker exec $containerName psql -X -q -t -A `
        -U postgres -d cleanup_test -c `
        "SELECT json_build_object('school',(SELECT count(*) FROM tenant_school.schools WHERE id=900000000),'dimSchool',(SELECT count(*) FROM reporting.dim_school WHERE id=900000000),'unhandled',(SELECT count(*) FROM cleanup_test.unhandled_projection WHERE school_id=900000000),'outsideSchool',(SELECT count(*) FROM tenant_school.schools WHERE id=800000000));") -join "") | ConvertFrom-Json
    if ($residueRollbackJson.school -ne 1 -or $residueRollbackJson.dimSchool -ne 1 -or
        $residueRollbackJson.unhandled -ne 1 -or $residueRollbackJson.outsideSchool -ne 1) {
        throw "Unhandled-residue failure did not roll back all prior deletes."
    }

    & $Docker exec $containerName psql -X -q -v ON_ERROR_STOP=1 `
        -U postgres -d cleanup_test -c `
        "DELETE FROM cleanup_test.unhandled_projection; DELETE FROM reporting.dim_school WHERE id=900000000; DELETE FROM tenant_school.schools WHERE id=900000000; INSERT INTO tenant_school.schools VALUES (900000000,'REAL-SCHOOL');" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not seed the reserved-ID guard fixture." }

    $guardFailure = Invoke-ExpectedFailure -Arguments @(
        "exec", $containerName, "psql", "-X", "-q", "-t", "-A", "-v", "ON_ERROR_STOP=1",
        "-v", "base_school_id=900000000", "-v", "school_count=2",
        "-U", "postgres", "-d", "cleanup_test", "-f", "/tmp/cleanup-scale-fleet.sql"
    )
    if ($guardFailure.ExitCode -eq 0 -or
        -not ($guardFailure.Output -match "reserved scale school id range contains non-scale data")) {
        throw "Reserved non-scale school case did not fail closed."
    }
    $guardRollbackJson = ((& $Docker exec $containerName psql -X -q -t -A `
        -U postgres -d cleanup_test -c `
        "SELECT json_build_object('reservedNonScaleSchool',(SELECT count(*) FROM tenant_school.schools WHERE id=900000000 AND short_code='REAL-SCHOOL'),'outsideSchool',(SELECT count(*) FROM tenant_school.schools WHERE id=800000000),'outsideImportRows',(SELECT count(*) FROM student.import_rows WHERE school_id=800000000));") -join "") | ConvertFrom-Json
    if ($guardRollbackJson.reservedNonScaleSchool -ne 1 -or $guardRollbackJson.outsideSchool -ne 1 -or
        $guardRollbackJson.outsideImportRows -ne 7) {
        throw "Reserved-ID guard failure changed protected rows."
    }

    [ordered]@{
        postgresMajor = $testResult.postgresMajor
        firstPass = [ordered]@{
            importRowsBefore = $firstImportRows
            importRowsDeleted = $firstDeletedImportRows
            dimStudentsDeleted = $firstDimStudents
            importBatchesByStatus = $firstPass.importBatchesByStatusBefore
            outsideScopePreserved = $testResult.outsideScopePreserved
        }
        secondPassIdempotent = $testResult.secondPassIdempotent
        residueFailureRolledBack = $true
        reservedIdGuardRolledBack = $true
    } | ConvertTo-Json -Depth 6
} finally {
    if ($containerStarted -and $containerName -like "ims-scale-cleanup-test-*") {
        & $Docker rm -f $containerName | Out-Null
    }
}
