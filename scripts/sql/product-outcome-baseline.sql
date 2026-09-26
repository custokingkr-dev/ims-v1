\set ON_ERROR_STOP on
\pset tuples_only on
\pset format unaligned
\if :{?from_date}
\else
\echo 'Required: -v from_date=YYYY-MM-DD -v through_date=YYYY-MM-DD'
SELECT 1 / 0 AS missing_from_date;
\endif
\if :{?through_date}
\else
\echo 'Required: -v through_date=YYYY-MM-DD'
SELECT 1 / 0 AS missing_through_date;
\endif

SELECT (:'from_date'::date <= :'through_date'::date) AS window_valid \gset
\if :window_valid
\else
\echo 'from_date must be on or before through_date'
SELECT 1 / 0 AS invalid_measurement_window;
\endif

-- Offline operator analysis only. Never wire this cross-schema script into an
-- application endpoint. Aggregate JSON contains no student/user/receipt identifiers.
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '2s';
SET LOCAL TIME ZONE 'Asia/Kolkata';

DO $visibility$
DECLARE target regclass; relation_name text; unrestricted boolean;
BEGIN
  FOREACH relation_name IN ARRAY ARRAY['student.import_batches','student.students',
      'attendance.attendance_daily','attendance.attendance_student_records',
      'firefighting.firefighting_requests','fee.payment_records','fee.fee_assignments',
      'billing.billing_payments','billing.billing_invoices'] LOOP
    target := to_regclass(relation_name);
    IF target IS NULL THEN RAISE EXCEPTION 'Missing measurement source: %', relation_name; END IF;
    SELECT r.rolsuper OR r.rolbypassrls OR NOT c.relrowsecurity OR (pg_has_role(current_user, c.relowner, 'USAGE') AND NOT c.relforcerowsecurity)
      INTO unrestricted FROM pg_class c JOIN pg_roles r ON r.rolname=current_user WHERE c.oid=target;
    IF NOT unrestricted THEN RAISE EXCEPTION 'Incomplete RLS visibility for %. Use an explicitly authorized offline analytical connection.', relation_name; END IF;
  END LOOP;
END $visibility$;

SELECT jsonb_build_object('section','measurement-context','fromDate', :'from_date'::date,
  'throughDate', :'through_date'::date,'observedAt',current_timestamp,'timeZone',current_setting('TimeZone'),
  'readOnly',current_setting('transaction_read_only')='on',
  'validWindow', :'from_date'::date <= :'through_date'::date,
  'moneyUnits','INR minor units (paise); 100 paise = INR 1. School fees and platform invoice collections remain separate.',
  'scope','All rows visible to the explicitly authorized offline analytical role; current snapshots are not historical snapshots.');

WITH batches AS (
  SELECT * FROM student.import_batches WHERE created_at >= :'from_date'::date AND created_at < :'through_date'::date + 1
), surviving AS (
  SELECT import_batch_id, count(*) AS records FROM student.students
  WHERE import_batch_id IN (SELECT id FROM batches) GROUP BY import_batch_id
)
SELECT jsonb_build_object('section','student-imports','batchesCreated',count(*),
  'batchesDone',count(*) FILTER(WHERE b.status='DONE'),
  'rowsPreviewed',COALESCE(sum(total_rows),0),'previewValidationErrors',COALESCE(sum(error_count),0),
  'rowsReportedInsertedInDoneBatches',COALESCE(sum(inserted) FILTER(WHERE b.status='DONE'),0),
  'rowsReportedSkippedInDoneBatches',COALESCE(sum(skipped) FILTER(WHERE b.status='DONE'),0),
  'doneBatchesWithRowCountMismatch',count(*) FILTER(WHERE b.status='DONE' AND inserted+skipped<>total_rows),
  'doneBatchesWithFewerCurrentStudentRowsThanReportedInserted',count(*) FILTER(WHERE b.status='DONE' AND COALESCE(s.records,0)<inserted),
  'completionP50Seconds',percentile_cont(0.50) WITHIN GROUP(ORDER BY extract(epoch FROM completed_at-created_at)) FILTER(WHERE b.status='DONE' AND completed_at>=created_at),
  'completionP95Seconds',percentile_cont(0.95) WITHIN GROUP(ORDER BY extract(epoch FROM completed_at-created_at)) FILTER(WHERE b.status='DONE' AND completed_at>=created_at),
  'currentRowCaveat','Survivor differences require review: permanent student deletion can legitimately reduce current linked rows.')
FROM batches b LEFT JOIN surviving s ON s.import_batch_id=b.id;

WITH registers AS (
  SELECT id,total_enrolled FROM attendance.attendance_daily
  WHERE attendance_date >= :'from_date'::date AND attendance_date <= :'through_date'::date
), records AS (
  SELECT r.* FROM attendance.attendance_student_records r JOIN registers d ON d.id=r.attendance_daily_id
), counts AS (
  SELECT attendance_daily_id,count(*) AS marks FROM records GROUP BY attendance_daily_id
)
SELECT jsonb_build_object('section','attendance','recordedRegisters',(SELECT count(*) FROM registers),
  'recordedSchoolDays',(SELECT count(DISTINCT (school_id,attendance_date)) FROM records),
  'marksRecorded',(SELECT count(*) FROM records),
  'marksPresent',(SELECT count(*) FROM records WHERE status='PRESENT'),
  'marksLate',(SELECT count(*) FROM records WHERE status='LATE'),
  'marksAbsent',(SELECT count(*) FROM records WHERE status='ABSENT'),
  'marksOnLeave',(SELECT count(*) FROM records WHERE status='LEAVE'),
  'enrollmentSlotsInRecordedRegisters',(SELECT COALESCE(sum(total_enrolled),0) FROM registers),
  'registersWithAllEnrollmentSlotsMarked',(SELECT count(*) FROM registers d LEFT JOIN counts c ON c.attendance_daily_id=d.id WHERE d.total_enrolled>0 AND COALESCE(c.marks,0)=d.total_enrolled),
  'coverageCaveat','Completeness covers recorded registers only. Missing expected school days/sections need an independently defined operating calendar.');

SELECT jsonb_build_object('section','urgent-procurement',
  'requestsCreatedInWindow',count(*) FILTER(WHERE created_at >= :'from_date'::date AND created_at < :'through_date'::date+1),
  'approvalQueueNow',count(*) FILTER(WHERE status IN ('AWAITING_BURSAR','AWAITING_PRINCIPAL','APPROVED')),
  'queueAgeSinceCreationP95Hours',percentile_cont(0.95) WITHIN GROUP(ORDER BY extract(epoch FROM current_timestamp-created_at)/3600)
    FILTER(WHERE status IN ('AWAITING_BURSAR','AWAITING_PRINCIPAL','APPROVED') AND created_at<=current_timestamp),
  'principalApprovalsInWindow',count(*) FILTER(WHERE principal_approved_at >= :'from_date'::date AND principal_approved_at < :'through_date'::date+1),
  'creationToPrincipalApprovalP50Hours',percentile_cont(0.50) WITHIN GROUP(ORDER BY extract(epoch FROM principal_approved_at-created_at)/3600)
    FILTER(WHERE principal_approved_at >= :'from_date'::date AND principal_approved_at < :'through_date'::date+1 AND principal_approved_at>=created_at),
  'fulfillmentsInWindow',count(*) FILTER(WHERE fulfilled_at >= :'from_date'::date AND fulfilled_at < :'through_date'::date+1),
  'custokingApprovalToFulfillmentP50Hours',percentile_cont(0.50) WITHIN GROUP(ORDER BY extract(epoch FROM fulfilled_at-custoking_approved_at)/3600)
    FILTER(WHERE fulfilled_at >= :'from_date'::date AND fulfilled_at < :'through_date'::date+1 AND fulfilled_at>=custoking_approved_at),
  'fulfilledRequestsMissingTimestamp',count(*) FILTER(WHERE status='FULFILLED' AND fulfilled_at IS NULL),
  'agingCaveat','Creation-based age includes time spent drafting because submission timestamps are unavailable.')
FROM firefighting.firefighting_requests;

SELECT jsonb_build_object('section','recorded-collections',
  'schoolFeePaymentCount',(SELECT count(*) FROM fee.payment_records WHERE paid_at >= :'from_date'::date AND paid_at < :'through_date'::date+1),
  'schoolFeeAmountRecorded',(SELECT COALESCE(sum(amount),0) FROM fee.payment_records WHERE paid_at >= :'from_date'::date AND paid_at < :'through_date'::date+1),
  'schoolFeeOutstandingNow',(SELECT COALESCE(sum(GREATEST(net_payable-paid_amount,0)),0) FROM fee.fee_assignments),
  'platformInvoicePaymentAmountRecorded',(SELECT COALESCE(sum(amount),0) FROM billing.billing_payments
    WHERE payment_date >= :'from_date'::date AND payment_date <= :'through_date'::date AND COALESCE(payment_mode,'')<>'LEGACY'),
  'legacyInvoiceStatusDerivedPaidAmount',(SELECT COALESCE(sum(amount),0) FROM billing.billing_payments
    WHERE payment_date >= :'from_date'::date AND payment_date <= :'through_date'::date AND payment_mode='LEGACY'),
  'platformInvoiceOutstandingNow',(SELECT COALESCE(sum(GREATEST(balance_amount,0)),0) FROM billing.billing_invoices WHERE upper(status)<>'CANCELLED'),
  'cashCaveat','Recorded collections are not bank reconciliation, net cash after refunds, or recognized revenue. Legacy paid-status mirrors are separated because their dates are invoice-derived.');

SELECT jsonb_build_object('section','not-observable-from-these-records',
  'independentOperatorSuccess','Unavailable: requires observed task completion without assistance.',
  'supportCost','Unavailable: requires attributable support time and cost records.',
  'grossMargin','Unavailable: requires actual supplier costs, recognized revenue, refunds, and attributable operating costs.',
  'attendanceCalendarCoverage','Unavailable: expected operating days/sections and holidays are not defined by recorded marks.',
  'approvalSubmissionLeadTime','Unavailable: request submission timestamp is not stored.');
ROLLBACK;
