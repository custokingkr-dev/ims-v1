$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$containerName = 'ims-outcome-baseline-' + [guid]::NewGuid().ToString('N')
$analysisSql = Get-Content -LiteralPath (Join-Path $repoRoot 'scripts/sql/product-outcome-baseline.sql') -Raw
function Invoke-TestSql([string]$Sql) {
    $result = $Sql | docker exec -i $containerName psql -X -q -U postgres -v ON_ERROR_STOP=1 -v from_date=2026-09-01 -v through_date=2026-09-30 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Local analytical query failed: $result" }
    return $result
}
function Assert-True([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-Rejected([string]$Prefix, [string]$Expected) {
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $result = ($Prefix + "`n" + $analysisSql) | docker exec -i $containerName psql -X -q -U postgres -v ON_ERROR_STOP=1 -v from_date=2026-09-01 -v through_date=2026-09-30 2>&1
        $failed = $LASTEXITCODE -ne 0
    } finally { $ErrorActionPreference = $savedPreference }
    Assert-True ($failed -and ($result -join "`n").Contains($Expected)) "Expected safe rejection: $Expected"
}
try {
    docker run --detach --name $containerName --network none -e POSTGRES_HOST_AUTH_METHOD=trust postgres:16 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start isolated local PostgreSQL' }
    $ready = $false
    for ($attempt=0; $attempt -lt 40; $attempt++) {
        docker exec $containerName pg_isready -U postgres *> $null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) { throw 'Local PostgreSQL did not become ready' }
    # Minimal fixtures retain the production source column names and SQL types.
    # They verify analytical semantics, not a substitute for each service's migration suite.
    Invoke-TestSql @'
CREATE SCHEMA student; CREATE SCHEMA attendance; CREATE SCHEMA firefighting; CREATE SCHEMA fee; CREATE SCHEMA billing;
CREATE TABLE student.import_batches(id varchar(255), status varchar(255), total_rows integer, error_count integer, inserted integer, skipped integer, created_at timestamptz, completed_at timestamptz);
CREATE TABLE student.students(id bigint, import_batch_id varchar(255));
INSERT INTO student.import_batches VALUES ('b1','DONE',2,0,2,0,'2026-09-01 10:00+05:30','2026-09-01 10:02+05:30'),('b2','DONE',3,1,1,1,'2026-09-02 10:00+05:30','2026-09-02 10:06+05:30');
INSERT INTO student.students VALUES (1,'b1'),(2,'b1');
CREATE TABLE attendance.attendance_daily(id varchar(255), total_enrolled integer, attendance_date date);
CREATE TABLE attendance.attendance_student_records(id varchar(255), attendance_daily_id varchar(255), school_id bigint, attendance_date date, status varchar(20));
INSERT INTO attendance.attendance_daily VALUES ('d1',2,'2026-09-01'),('d2',2,'2026-09-02');
INSERT INTO attendance.attendance_student_records VALUES ('m1','d1',7,'2026-09-01','PRESENT'),('m2','d1',7,'2026-09-01','LATE'),('m3','d2',7,'2026-09-02','ABSENT');
CREATE TABLE firefighting.firefighting_requests(code varchar(255),status varchar(255),created_at timestamptz,principal_approved_at timestamptz,custoking_approved_at timestamptz,fulfilled_at timestamptz);
INSERT INTO firefighting.firefighting_requests VALUES ('r1','AWAITING_BURSAR','2026-09-01',NULL,NULL,NULL),('r2','FULFILLED','2026-09-01','2026-09-02','2026-09-03','2026-09-05'),('r3','FULFILLED','2026-08-01',NULL,NULL,NULL);
CREATE TABLE fee.payment_records(id varchar(255),amount bigint,paid_at timestamptz);
CREATE TABLE fee.fee_assignments(id varchar(255),net_payable bigint,paid_amount bigint);
INSERT INTO fee.payment_records VALUES ('p1',100,'2026-09-02'),('p2',25,'2026-09-20'),('old',50,'2026-08-20');
INSERT INTO fee.fee_assignments VALUES ('a1',1000,125);
CREATE TABLE billing.billing_payments(id bigint,amount bigint,payment_date date,payment_mode varchar(255));
CREATE TABLE billing.billing_invoices(id bigint,balance_amount bigint,status varchar(255));
INSERT INTO billing.billing_payments VALUES (1,100,'2026-09-02','BANK'),(2,500,'2026-09-02','LEGACY');
INSERT INTO billing.billing_invoices VALUES (1,300,'ISSUED'),(2,700,'CANCELLED');
'@ | Out-Null
    $output = Invoke-TestSql $analysisSql
    $records = @($output | Where-Object { [string]$_ -match '^\{' } | ForEach-Object { $_ | ConvertFrom-Json })
    Assert-True ($records.Count -eq 6) 'Expected six aggregate evidence sections'
    $context = $records | Where-Object section -eq 'measurement-context'
    $imports = $records | Where-Object section -eq 'student-imports'
    $attendance = $records | Where-Object section -eq 'attendance'
    $procurement = $records | Where-Object section -eq 'urgent-procurement'
    $cash = $records | Where-Object section -eq 'recorded-collections'
    Assert-True ($context.readOnly -and $context.validWindow) 'Analytical transaction must be read-only with valid date window'
    Assert-True ($context.moneyUnits.StartsWith('INR minor units (paise); 100 paise = INR 1.')) 'Stored amounts must be identified as paise, never rupees'
    Assert-True ($imports.batchesDone -eq 2 -and $imports.doneBatchesWithRowCountMismatch -eq 1 -and $imports.doneBatchesWithFewerCurrentStudentRowsThanReportedInserted -eq 1 -and $imports.completionP50Seconds -eq 240) 'Import reconciliation or duration calculation differs'
    Assert-True ($attendance.recordedRegisters -eq 2 -and $attendance.marksRecorded -eq 3 -and $attendance.registersWithAllEnrollmentSlotsMarked -eq 1 -and $attendance.marksLate -eq 1) 'Attendance completeness must include marks and retain missing enrollment slots'
    Assert-True ($procurement.approvalQueueNow -eq 1 -and $procurement.fulfillmentsInWindow -eq 1 -and $procurement.fulfilledRequestsMissingTimestamp -eq 1 -and $procurement.custokingApprovalToFulfillmentP50Hours -eq 48) 'Procurement age/fulfillment calculation differs'
    Assert-True ($cash.schoolFeeAmountRecorded -eq 125 -and $cash.schoolFeeOutstandingNow -eq 875 -and $cash.platformInvoicePaymentAmountRecorded -eq 100 -and $cash.legacyInvoiceStatusDerivedPaidAmount -eq 500 -and $cash.platformInvoiceOutstandingNow -eq 300) 'Cash must separate actual entries, legacy mirrors, and cancelled balances'
    Assert-True (($output -join "`n") -notmatch '"(studentId|receiptNumber|email|schoolId)"') 'Only aggregate output is allowed'
    Invoke-TestSql 'DO $$ BEGIN IF (SELECT count(*) FROM student.import_batches) <> 2 OR (SELECT sum(amount) FROM fee.payment_records) <> 175 THEN RAISE EXCEPTION ''Read-only measurement mutated source data''; END IF; END $$;' | Out-Null
    Assert-Rejected '\set from_date 2026-10-01' 'from_date must be on or before through_date'
    Invoke-TestSql 'CREATE ROLE scoped; GRANT USAGE ON SCHEMA student,attendance,firefighting,fee,billing TO scoped; GRANT SELECT ON ALL TABLES IN SCHEMA student,attendance,firefighting,fee,billing TO scoped; ALTER TABLE student.students ENABLE ROW LEVEL SECURITY;' | Out-Null
    Assert-Rejected 'SET ROLE scoped;' 'Incomplete RLS visibility'
    Write-Output 'PASS: six outcome sections, reconciliation, attendance coverage, approval/fulfillment timing, legacy cash separation, read-only source preservation, and invalid-window/RLS rejection.'
} finally {
    docker rm --force $containerName *> $null
}
