import { useEffect, useMemo, useRef, useState } from 'react';
import api from '../../../services/api';
import { useAuth } from '../../../contexts/AuthContext';
import { usePermissions } from '../../../hooks/usePermissions';
import { ModuleShell, Field, Stat } from '../ui';
import { formatLakh, formatPaise, paiseToRupeeInput } from '../utils';
import type { WorkspaceData } from '../config';

interface Props {
  workspace: WorkspaceData | null;
  onRefresh: () => void | Promise<void>;
}

interface FeeClass {
  id: string | number;
  name: string;
}

interface FeeSection {
  id: string | number;
  name: string;
}

interface PaymentStudent {
  id: string | number;
  name: string;
  admissionNo: string;
  feePlan: string;
  schedule: string;
  totalFee: number;
  discount: number;
  paid: number;
  dueAmount: number;
  installments?: unknown[];
}

const PAYMENT_MODES = ['UPI', 'Cash', 'Bank transfer', 'Cheque'];
const STATUS_FILTERS = ['ALL', 'Paid', 'Partial', 'Pending', 'Overdue'];

function errMessage(err: unknown, fallback: string): string {
  if (err instanceof Error && err.message) return err.message;
  return (err as { response?: { data?: { message?: string } } })?.response?.data?.message || fallback;
}

function firstValue(row: any, keys: string[], fallback: any = ''): any {
  for (const key of keys) {
    if (row?.[key] !== undefined && row?.[key] !== null) return row[key];
  }
  return fallback;
}

function asPaise(row: any, keys: string[]): number {
  return Number(firstValue(row, keys, 0) || 0);
}

function reportRowId(row: any, index = 0): string {
  return String(row?.studentId || row?.assignmentId || row?.paymentId || `${firstValue(row, ['studentName', 'student'], 'student')}-${index}`);
}

function studentName(row: any): string {
  return String(firstValue(row, ['studentName', 'student', 'name'], 'Student'));
}

function classSection(row: any): string {
  return String(row?.classSection || [row?.className, row?.sectionName].filter(Boolean).join(' - ') || '');
}

function feePlan(row: any): string {
  return String(firstValue(row, ['feePlan', 'planName'], 'Unassigned plan'));
}

function feeSchedule(row: any): string {
  return String(firstValue(row, ['paymentSchedule', 'schedule'], 'Schedule not set'));
}

function duePaise(row: any): number {
  return asPaise(row, ['dueAmountPaise', 'dueAmount', 'due']);
}

function paidPaise(row: any): number {
  return asPaise(row, ['paidPaise', 'paid']);
}

function totalPaise(row: any): number {
  return asPaise(row, ['totalFee', 'totalAnnualFeePaise', 'totalAnnualFee']);
}

function discountPaise(row: any): number {
  return asPaise(row, ['discount', 'approvedDiscountPaise', 'approvedDiscount', 'discounts']);
}

function surchargePaise(row: any): number {
  return asPaise(row, ['surchargeAmountPaise', 'surchargeAmount', 'surcharge']);
}

function paymentId(row: any): string {
  return String(row?.payments?.[0]?.paymentId || row?.paymentId || '');
}

function normalizeStatus(value: unknown): string {
  const key = String(value || '').trim().toUpperCase();
  if (key === 'PAID') return 'Paid';
  if (key === 'OVERDUE') return 'Overdue';
  if (key === 'PARTIAL') return 'Partial';
  if (key === 'PENDING') return 'Pending';
  return String(value || '').trim();
}

function computedStatus(row: any): string {
  const explicit = normalizeStatus(row?.status);
  if (explicit) return explicit;
  const due = duePaise(row);
  const paid = paidPaise(row);
  if (due <= 0) return 'Paid';
  if (paid > 0) return 'Partial';
  return 'Pending';
}

function nameInitials(value: string): string {
  return (
    value
      .split(' ')
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase() || '')
      .join('') || 'ST'
  );
}

export function FeesPanel({ workspace, onRefresh }: Props) {
  const { user } = useAuth();
  const { can, canAny } = usePermissions();
  const role = String(user?.role || '').toUpperCase();
  const isPlatformAdmin = role === 'SUPERADMIN' || can('platform:admin');
  const canCollectFees = isPlatformAdmin || canAny(['fee:collect', 'payment:create']);
  const canSendFeeReminders = isPlatformAdmin || canAny(['fee:collect', 'notification:send']);
  const schoolScopedParams = !isPlatformAdmin && user?.branchId ? { schoolId: user.branchId } : undefined;

  const [feeSummary, setFeeSummary] = useState({
    progressPercent: 0,
    collected: 0,
    outstanding: 0,
    overdueCount: 0,
    target: 0,
  });
  const [saving, setSaving] = useState('');
  const [feeClasses, setFeeClasses] = useState<FeeClass[]>([]);
  const [feeLoadError, setFeeLoadError] = useState('');

  const [paymentForm, setPaymentForm] = useState({
    studentId: '',
    studentName: '',
    amount: '',
    paymentMode: 'UPI',
    notes: '',
  });
  const [paymentOptions, setPaymentOptions] = useState<{ sections: FeeSection[]; students: PaymentStudent[] }>({
    sections: [],
    students: [],
  });
  const [paymentSelection, setPaymentSelection] = useState({ classId: '', sectionId: '', studentId: '' });
  const [paymentDuePreview, setPaymentDuePreview] = useState<PaymentStudent | null>(null);
  const [paymentError, setPaymentError] = useState('');
  const [paymentSuccess, setPaymentSuccess] = useState('');

  const [feeFilters, setFeeFilters] = useState({ className: '', sectionName: '' });
  const [reportOptions, setReportOptions] = useState<{ sections: FeeSection[] }>({ sections: [] });
  const [reportRows, setReportRows] = useState<any[]>([]);
  const [overdueRows, setOverdueRows] = useState<any[]>([]);
  const [reportLoading, setReportLoading] = useState(false);
  const [selectedReportStudentId, setSelectedReportStudentId] = useState<string | null>(null);
  const [ledgerSearch, setLedgerSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');

  const [reminderSaving, setReminderSaving] = useState(false);
  const [reminderNotice, setReminderNotice] = useState('');
  const [reminderError, setReminderError] = useState('');

  const paymentTimerRef = useRef<number | null>(null);

  const loadFeeSummary = async () => {
    try {
      const [moduleRes, overdueRes] = await Promise.all([
        api.get('/fees/dashboard/module', { params: schoolScopedParams }),
        api.get('/fees/dashboard/overdue-count', { params: schoolScopedParams }),
      ]);
      const s = (moduleRes.data as { summary?: { collected?: number; target?: number } })?.summary ?? {};
      const collected = Number(s.collected || 0);
      const target = Number(s.target || 0);
      const outstanding = Math.max(0, target - collected);
      const progressPercent = target > 0 ? Math.round((collected / target) * 100) : 0;
      const overdueCount = Number((overdueRes.data as { count?: number })?.count || 0);
      setFeeSummary({ progressPercent, collected, outstanding, overdueCount, target });
    } catch {
      // Keep the previous summary cards if the dashboard service is temporarily unavailable.
    }
  };

  const loadFeeClasses = async () => {
    try {
      setFeeLoadError('');
      const res = await api.get<FeeClass[]>('/classes', { params: schoolScopedParams });
      setFeeClasses(Array.isArray(res.data) ? res.data : []);
    } catch (err) {
      setFeeClasses([]);
      setFeeLoadError(errMessage(err, 'Could not load classes.'));
    }
  };

  const loadSections = async (classId: string, target: 'payment' | 'report') => {
    try {
      setFeeLoadError('');
      const res = await api.get<FeeSection[]>(`/classes/${encodeURIComponent(classId)}/sections`, { params: schoolScopedParams });
      const sections = Array.isArray(res.data) ? res.data : [];
      if (target === 'payment') setPaymentOptions((prev) => ({ ...prev, sections, students: [] }));
      if (target === 'report') setReportOptions({ sections });
    } catch (err) {
      setFeeLoadError(errMessage(err, 'Could not load sections.'));
      if (target === 'payment') setPaymentOptions((prev) => ({ ...prev, sections: [], students: [] }));
      if (target === 'report') setReportOptions({ sections: [] });
    }
  };

  const loadSectionStudents = async (classId: string, sectionId: string) => {
    try {
      setFeeLoadError('');
      const res = await api.get('/fees/report', { params: { classId, sectionId, ...(schoolScopedParams || {}) } });
      const students = (Array.isArray(res.data) ? res.data : []).map((r: any) => ({
        id: r.studentId,
        name: studentName(r),
        admissionNo: String(r.admissionNumber || r.admissionNo || ''),
        feePlan: feePlan(r),
        schedule: feeSchedule(r),
        totalFee: totalPaise(r),
        discount: discountPaise(r),
        paid: paidPaise(r),
        dueAmount: duePaise(r),
        installments: Array.isArray(r.installments) ? r.installments : [],
      }));
      setPaymentOptions((prev) => ({ ...prev, students }));
    } catch (err) {
      setFeeLoadError(errMessage(err, 'Could not load students.'));
      setPaymentOptions((prev) => ({ ...prev, students: [] }));
    }
  };

  const loadFeeReports = async (classId: string, sectionId: string) => {
    setReportLoading(true);
    setFeeLoadError('');
    try {
      const [reportRes, overdueRes] = await Promise.all([
        api.get('/fees/report', { params: { classId, sectionId, ...(schoolScopedParams || {}) } }),
        api.get('/fees/overdue', { params: { classId, sectionId, ...(schoolScopedParams || {}) } }),
      ]);
      const nextReportRows = Array.isArray(reportRes.data) ? reportRes.data : [];
      const nextOverdueRows = (Array.isArray(overdueRes.data) ? overdueRes.data : [])
        .slice()
        .sort((a: any, b: any) => Number(b?.daysOverdue || 0) - Number(a?.daysOverdue || 0));
      setReportRows(nextReportRows);
      setOverdueRows(nextOverdueRows);
      setSelectedReportStudentId((prev) => {
        if (prev && nextReportRows.some((row: any, idx: number) => reportRowId(row, idx) === prev)) return prev;
        const first = nextReportRows[0];
        return first ? reportRowId(first, 0) : null;
      });
    } catch (err) {
      setReportRows([]);
      setOverdueRows([]);
      setSelectedReportStudentId(null);
      setFeeLoadError(errMessage(err, 'Could not load fee reports.'));
    } finally {
      setReportLoading(false);
    }
  };

  const openReceiptPdf = async (receiptPaymentId: string) => {
    try {
      setPaymentError('');
      const res = await api.get(`/fees/receipts/${encodeURIComponent(receiptPaymentId)}/pdf`, {
        params: schoolScopedParams,
        responseType: 'blob',
      });
      const url = URL.createObjectURL(new Blob([res.data], { type: 'application/pdf' }));
      window.open(url, '_blank', 'noopener,noreferrer');
    } catch (err) {
      setPaymentError(errMessage(err, 'Could not open receipt PDF.'));
    }
  };

  const handlePaymentClassChange = async (classId: string) => {
    setPaymentSelection({ classId, sectionId: '', studentId: '' });
    setPaymentOptions({ sections: [], students: [] });
    setPaymentDuePreview(null);
    setPaymentError('');
    setPaymentForm((prev) => ({ ...prev, studentId: '', studentName: '', amount: '' }));
    if (!classId) return;
    await loadSections(classId, 'payment');
  };

  const handlePaymentSectionChange = async (sectionId: string) => {
    setPaymentSelection((prev) => ({ ...prev, sectionId, studentId: '' }));
    setPaymentOptions((prev) => ({ ...prev, students: [] }));
    setPaymentDuePreview(null);
    setPaymentError('');
    setPaymentForm((prev) => ({ ...prev, studentId: '', studentName: '', amount: '' }));
    if (!sectionId) return;
    await loadSectionStudents(paymentSelection.classId, sectionId);
  };

  const handlePaymentStudentChange = (studentId: string) => {
    setPaymentSelection((prev) => ({ ...prev, studentId }));
    const selected = paymentOptions.students.find((row) => String(row.id) === studentId) || null;
    setPaymentDuePreview(selected);
    setPaymentError('');
    setSelectedReportStudentId(studentId || selectedReportStudentId);
    setPaymentForm((prev) => ({
      ...prev,
      studentId,
      studentName: selected?.name || '',
      amount: selected ? paiseToRupeeInput(selected.dueAmount) : '',
    }));
  };

  const handleReportClassChange = async (classId: string) => {
    setFeeFilters({ className: classId, sectionName: '' });
    setReportRows([]);
    setOverdueRows([]);
    setSelectedReportStudentId(null);
    setLedgerSearch('');
    if (!classId) { setReportOptions({ sections: [] }); return; }
    await loadSections(classId, 'report');
  };

  const handleReportSectionChange = async (sectionId: string) => {
    setFeeFilters((f) => ({ ...f, sectionName: sectionId }));
    setReportRows([]);
    setOverdueRows([]);
    setSelectedReportStudentId(null);
    if (feeFilters.className && sectionId) await loadFeeReports(feeFilters.className, sectionId);
  };

  const handleRecordPayment = async () => {
    if (!canCollectFees) {
      setPaymentError('You need fee:collect or payment:create permission to record payments.');
      return;
    }

    try {
      setSaving('payment');
      setPaymentError('');
      setPaymentSuccess('');
      const due = Number(paymentDuePreview?.dueAmount || 0);
      const amountPaise = Math.round(Number(paymentForm.amount || 0) * 100);
      if (!paymentForm.studentId || amountPaise <= 0 || !paymentForm.paymentMode) {
        setPaymentError('Select a student, amount, and payment mode before saving.');
        return;
      }
      if (paymentDuePreview && amountPaise > due) {
        setPaymentError(`Amount Rs ${paiseToRupeeInput(amountPaise)} exceeds the due amount of Rs ${formatPaise(due)}.`);
        return;
      }

      await api.post('/workspace/fees/record-payment', {
        studentId: paymentForm.studentId,
        amount: amountPaise,
        mode: paymentForm.paymentMode,
        notes: paymentForm.notes,
        paidAt: new Date().toISOString(),
        recordedBy: user?.userId || user?.email || 'current-user',
        ...(schoolScopedParams || {}),
      });
      await Promise.resolve(onRefresh());
      await loadFeeSummary();
      if (feeFilters.className && feeFilters.sectionName) {
        await loadFeeReports(feeFilters.className, feeFilters.sectionName);
      }
      if (paymentSelection.classId && paymentSelection.sectionId) {
        await loadSectionStudents(paymentSelection.classId, paymentSelection.sectionId);
      }
      setPaymentSuccess(`Payment of Rs ${paiseToRupeeInput(amountPaise)} recorded for ${paymentForm.studentName || 'the selected student'}.`);
      setPaymentForm({ studentId: '', studentName: '', amount: '', paymentMode: 'UPI', notes: '' });
      setPaymentSelection((prev) => ({ ...prev, studentId: '' }));
      setPaymentDuePreview(null);
      if (paymentTimerRef.current) window.clearTimeout(paymentTimerRef.current);
      paymentTimerRef.current = window.setTimeout(() => setPaymentSuccess(''), 4000);
    } catch (err) {
      setPaymentError(errMessage(err, 'Could not record payment.'));
    } finally {
      setSaving('');
    }
  };

  const handleSendReminders = async () => {
    setReminderNotice('');
    setReminderError('');
    if (!canSendFeeReminders) {
      setReminderError('You need fee:collect or notification:send permission to queue reminders.');
      return;
    }
    if (!(feeFilters.className && feeFilters.sectionName)) {
      setReminderError('Select a class and section before sending reminders.');
      return;
    }
    try {
      setReminderSaving(true);
      const res = await api.post('/fees/send-reminders', {
        classId: feeFilters.className,
        sectionId: feeFilters.sectionName,
        ...(schoolScopedParams || {}),
      });
      const queued = Number(res.data?.queued || overdueRows.length || 0);
      setReminderNotice(`Reminders queued for ${queued} overdue student${queued === 1 ? '' : 's'}.`);
      window.setTimeout(() => setReminderNotice(''), 4000);
    } catch (err) {
      setReminderError(errMessage(err, 'Could not queue reminders.'));
    } finally {
      setReminderSaving(false);
    }
  };

  useEffect(() => {
    void loadFeeClasses();
    void loadFeeSummary();
    return () => {
      if (paymentTimerRef.current) window.clearTimeout(paymentTimerRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const safeReportRows = Array.isArray(reportRows) ? reportRows : [];
  const safeOverdueRows = Array.isArray(overdueRows) ? overdueRows : [];
  const filteredReportRows = useMemo(() => {
    const query = ledgerSearch.trim().toLowerCase();
    return safeReportRows.filter((row, index) => {
      const status = computedStatus(row);
      const statusMatches = statusFilter === 'ALL' || status.toLowerCase() === statusFilter.toLowerCase();
      if (!statusMatches) return false;
      if (!query) return true;
      const searchable = [
        reportRowId(row, index),
        studentName(row),
        row.admissionNumber,
        row.admissionNo,
        classSection(row),
        feePlan(row),
        feeSchedule(row),
      ].filter(Boolean).join(' ').toLowerCase();
      return searchable.includes(query);
    });
  }, [ledgerSearch, safeReportRows, statusFilter]);

  const selectedReportRow =
    safeReportRows.find((row, idx) => reportRowId(row, idx) === selectedReportStudentId) ||
    filteredReportRows[0] ||
    null;
  const selectedStatement: any = paymentDuePreview || selectedReportRow;
  const selectedStatementName = selectedStatement ? studentName(selectedStatement) : '';
  const selectedInstallments = Array.isArray(selectedStatement?.installments) ? selectedStatement.installments : [];
  const ledgerLoaded = Boolean(feeFilters.className && feeFilters.sectionName);

  const exportReportCsv = () => {
    const headers = ['Student', 'Class', 'Plan', 'Schedule', 'Total Fee', 'Discount', 'Paid', 'Due', 'Status'];
    const rows = filteredReportRows.map((r, index) => [
      studentName(r),
      classSection(r),
      feePlan(r),
      feeSchedule(r),
      paiseToRupeeInput(totalPaise(r)),
      paiseToRupeeInput(discountPaise(r)),
      paiseToRupeeInput(paidPaise(r)),
      paiseToRupeeInput(duePaise(r)),
      computedStatus(r),
    ]);
    const esc = (v: unknown) => `"${String(v ?? '').replace(/"/g, '""')}"`;
    const csv = [headers, ...rows].map((r) => r.map(esc).join(',')).join('\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `fee-report-${feeFilters.className || 'class'}-${feeFilters.sectionName || 'section'}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  function feeStatusBadge(statusValue: string) {
    const status = normalizeStatus(statusValue) || 'Pending';
    const map: Record<string, string> = {
      Paid: 'spaid',
      Overdue: 'soverdue',
      Partial: 'spartial',
      Pending: 'spending',
    };
    return <span className={`ck-status ${map[status] ?? 'sneutral'}`}>{status}</span>;
  }

  if (!workspace) return null;

  return (
    <ModuleShell
      title="Fee Collections"
      subtitle={`Academic year ${workspace?.school?.meta || 'current'} - collect, reconcile, and follow up dues`}
      actions={
        <div className="ck-fees-header-actions">
          <span className={`ck-status ${canCollectFees ? 'sapproved' : 'sneutral'}`}>
            {canCollectFees ? 'Can collect' : 'Read-only'}
          </span>
          <button
            type="button"
            className="ck-btn ck-btn-ghost"
            onClick={handleSendReminders}
            disabled={!canSendFeeReminders || reminderSaving || !ledgerLoaded || safeOverdueRows.length === 0}
            title={!canSendFeeReminders ? 'Fee collection or notification permission is required' : !ledgerLoaded ? 'Select a class and section first' : 'Queue reminder messages for overdue students'}
          >
            {reminderSaving ? 'Sending...' : 'Send reminders'}
          </button>
        </div>
      }
    >
      <div className="ck-panel-stack ck-fees-module">
        {feeLoadError ? <div className="ck-alert ck-alert-am"><span>!</span><div>{feeLoadError}</div></div> : null}
        {reminderNotice ? <div className="ck-alert ck-alert-g"><span>OK</span><div>{reminderNotice}</div></div> : null}
        {reminderError ? <div className="ck-alert ck-alert-re"><span>!</span><div>{reminderError}</div></div> : null}
        {!canCollectFees ? (
          <div className="ck-alert ck-alert-am">
            <span>i</span>
            <div>You can review fee records. Recording payments requires fee:collect or payment:create.</div>
          </div>
        ) : null}

        <div className="ck-stats ck-s4 ck-fees-kpis">
          <Stat label="Collection rate" value={`${feeSummary.progressPercent}%`} sub={`Rs ${formatPaise(feeSummary.collected)} collected`} pill="Live dashboard" tone={feeSummary.progressPercent >= 75 ? 'green' : 'orange'} />
          <Stat label="Collected" value={`Rs ${formatLakh(Math.round(Number(feeSummary.collected || 0) / 100))}`} sub={`Full value Rs ${formatPaise(feeSummary.collected)}`} pill="Receipted" tone="green" />
          <Stat label="Outstanding" value={`Rs ${formatLakh(Math.round(Number(feeSummary.outstanding || 0) / 100))}`} sub={`Full value Rs ${formatPaise(feeSummary.outstanding)}`} pill={`${feeSummary.overdueCount} overdue`} tone={feeSummary.overdueCount > 0 ? 'red' : 'green'} />
          <Stat label="Annual target" value={`Rs ${formatLakh(Math.round(Number(feeSummary.target || 0) / 100))}`} sub={`Full value Rs ${formatPaise(feeSummary.target)}`} pill="Assigned fees" tone="blue" />
        </div>

        <div className="ck-fees-workbench">
          <div className="ck-card ck-fees-card">
            <div className="ck-card-h">
              <div>
                <div className="ck-card-t">Collection Workbench</div>
                <div className="ck-card-sub">Select a student, verify due amount, then record the installment.</div>
              </div>
              <span className={`ck-status ${canCollectFees ? 'sapproved' : 'sneutral'}`}>
                {canCollectFees ? 'Payment enabled' : 'Read-only'}
              </span>
            </div>
            <div className="ck-fees-card-body">
              {paymentSuccess ? <div className="ck-alert ck-alert-g"><span>OK</span><div>{paymentSuccess}</div></div> : null}
              {paymentError ? <div className="ck-alert ck-alert-re"><span>!</span><div>{paymentError}</div></div> : null}

              <div className="ck-form-grid ck-fg-3">
                <Field label="Class">
                  <select value={paymentSelection.classId} onChange={(e) => handlePaymentClassChange(e.target.value)}>
                    <option value="">Select class</option>
                    {feeClasses.map((row) => <option key={row.id} value={row.id}>{row.name}</option>)}
                  </select>
                </Field>
                <Field label="Section" hint={!paymentSelection.classId ? 'Select a class first' : undefined}>
                  <select disabled={!paymentSelection.classId} value={paymentSelection.sectionId} onChange={(e) => handlePaymentSectionChange(e.target.value)}>
                    <option value="">Select section</option>
                    {paymentOptions.sections.map((row) => <option key={row.id} value={row.id}>{row.name}</option>)}
                  </select>
                </Field>
                <Field label="Student" hint={!paymentSelection.sectionId ? 'Select a section first' : undefined}>
                  <select disabled={!paymentSelection.sectionId} value={paymentSelection.studentId} onChange={(e) => handlePaymentStudentChange(e.target.value)}>
                    <option value="">Select student</option>
                    {paymentOptions.students.map((student) => (
                      <option key={student.id} value={student.id}>{student.name} - {student.admissionNo}</option>
                    ))}
                  </select>
                </Field>
              </div>

              <div className="ck-fees-due-strip">
                {paymentDuePreview ? (
                  <>
                    <div>
                      <strong>{paymentDuePreview.feePlan}</strong>
                      <span>{paymentDuePreview.schedule}</span>
                    </div>
                    <div className="ck-fees-due-money">
                      Due Rs {formatPaise(paymentDuePreview.dueAmount)}
                    </div>
                  </>
                ) : (
                  <>
                    <div>
                      <strong>No student selected</strong>
                      <span>Due amount auto-fills after selecting a fee-assigned student.</span>
                    </div>
                    <div className="ck-fees-due-money">Rs 0.00</div>
                  </>
                )}
              </div>

              <div className="ck-form-grid ck-fg-3">
                <Field label="Amount">
                  <input type="number" min={0} step="0.01" value={paymentForm.amount} onChange={(e) => setPaymentForm({ ...paymentForm, amount: e.target.value })} />
                </Field>
                <Field label="Mode">
                  <select value={paymentForm.paymentMode} onChange={(e) => setPaymentForm({ ...paymentForm, paymentMode: e.target.value })}>
                    {PAYMENT_MODES.map((mode) => <option key={mode}>{mode}</option>)}
                  </select>
                </Field>
                <Field label="Notes">
                  <input value={paymentForm.notes} onChange={(e) => setPaymentForm({ ...paymentForm, notes: e.target.value })} placeholder="Optional" />
                </Field>
              </div>

              <div className="ck-actions-inline">
                <button
                  type="button"
                  disabled={!canCollectFees || !(paymentForm.studentId && Number(paymentForm.amount) > 0 && paymentForm.paymentMode) || saving === 'payment'}
                  className="ck-btn ck-btn-g"
                  onClick={handleRecordPayment}
                >
                  {saving === 'payment' ? 'Saving...' : 'Save payment'}
                </button>
              </div>
            </div>
          </div>

          <div className="ck-card ck-fees-card ck-fees-statement">
            <div className="ck-card-h">
              <div>
                <div className="ck-card-t">Student Statement</div>
                <div className="ck-card-sub">Current fee position for the selected student.</div>
              </div>
            </div>
            <div className="ck-fees-card-body">
              {selectedStatement ? (
                <>
                  <div className="ck-fees-student-head">
                    <div className="ck-user-avatar">{nameInitials(selectedStatementName)}</div>
                    <div>
                      <div className="ck-fees-student-name">{selectedStatementName}</div>
                      <div className="ck-muted">
                        {firstValue(selectedStatement, ['admissionNo', 'admissionNumber'], 'No admission no.')}
                      </div>
                    </div>
                  </div>

                  <div className="ck-fees-money-grid">
                    <div><span>Payable</span><strong>Rs {formatPaise(totalPaise(selectedStatement))}</strong></div>
                    <div><span>Discount</span><strong>Rs {formatPaise(discountPaise(selectedStatement))}</strong></div>
                    <div><span>Paid</span><strong className="ck-amt-green">Rs {formatPaise(paidPaise(selectedStatement))}</strong></div>
                    <div><span>Due</span><strong className={duePaise(selectedStatement) > 0 ? 'ck-amt-red' : 'ck-amt-green'}>Rs {formatPaise(duePaise(selectedStatement))}</strong></div>
                  </div>

                  <div className="ck-fees-statement-meta">
                    <span>{feePlan(selectedStatement)}</span>
                    <span>{feeSchedule(selectedStatement)}</span>
                    {feeStatusBadge(computedStatus(selectedStatement))}
                  </div>

                  {selectedInstallments.length > 0 ? (
                    <div className="ck-table-wrap ck-fees-mini-table">
                      <table className="ck-table">
                        <thead><tr><th>Installment</th><th>Due date</th><th className="col-money">Amount</th><th>Status</th></tr></thead>
                        <tbody>
                          {selectedInstallments.map((ins: any, idx: number) => (
                            <tr key={ins.installmentNo || ins.dueDate || idx}>
                              <td>#{ins.installmentNo || idx + 1}</td>
                              <td>{ins.dueDate || '-'}</td>
                              <td className="col-money">Rs {formatPaise(asPaise(ins, ['amountPaise', 'amount']))}</td>
                              <td>{feeStatusBadge(ins.status || 'Pending')}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  ) : (
                    <div className="ck-fees-empty">Installment-level detail is not available for this fee record.</div>
                  )}
                </>
              ) : (
                <div className="ck-fees-empty">Select a student from the workbench or ledger to view a statement.</div>
              )}
            </div>
          </div>
        </div>

        <div className="ck-card ck-fees-card">
          <div className="ck-card-h ck-card-h-wrap">
            <div>
              <div className="ck-card-t">Class Fee Ledger</div>
              <div className="ck-card-sub">Load a class-section ledger, search students, and export the current view.</div>
            </div>
            <div className="ck-fees-ledger-actions">
              <button type="button" className="ck-btn ck-btn-ghost" onClick={exportReportCsv} disabled={!filteredReportRows.length}>
                Export CSV
              </button>
            </div>
          </div>
          <div className="ck-fees-card-body">
            <div className="ck-fees-ledger-filters">
              <Field label="Class">
                <select value={feeFilters.className} onChange={(e) => handleReportClassChange(e.target.value)}>
                  <option value="">Select class</option>
                  {feeClasses.map((row) => <option key={row.id} value={row.id}>{row.name}</option>)}
                </select>
              </Field>
              <Field label="Section">
                <select disabled={!feeFilters.className} value={feeFilters.sectionName} onChange={(e) => handleReportSectionChange(e.target.value)}>
                  <option value="">Select section</option>
                  {reportOptions.sections.map((row) => <option key={row.id} value={row.id}>{row.name}</option>)}
                </select>
              </Field>
              <Field label="Search">
                <input value={ledgerSearch} onChange={(e) => setLedgerSearch(e.target.value)} placeholder="Student, admission, plan" />
              </Field>
              <Field label="Status">
                <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
                  {STATUS_FILTERS.map((status) => <option key={status}>{status}</option>)}
                </select>
              </Field>
            </div>
          </div>

          {!ledgerLoaded ? (
            <div className="ck-import-zone ck-fees-zone"><div className="iz-title">Select a class and section to load fee records.</div></div>
          ) : reportLoading ? (
            <div className="ck-fees-empty">Loading fee records...</div>
          ) : !filteredReportRows.length ? (
            <div className="ck-import-zone ck-fees-zone"><div className="iz-title">No fee records match the current filters.</div></div>
          ) : (
            <div className="ck-table-wrap">
              <table className="ck-table">
                <thead>
                  <tr>
                    <th>Student</th>
                    <th>Plan / Schedule</th>
                    <th className="col-money">Payable</th>
                    <th className="col-money">Discount / Surcharge</th>
                    <th className="col-money">Paid</th>
                    <th className="col-money">Due</th>
                    <th>Status</th>
                    <th>Receipt</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredReportRows.map((row, idx) => {
                    const rowKey = reportRowId(row, idx);
                    const receiptId = paymentId(row);
                    const isSelected = selectedReportStudentId ? selectedReportStudentId === rowKey : idx === 0;
                    return (
                      <tr key={rowKey} className={isSelected ? 'ck-row-selected' : ''} onClick={() => setSelectedReportStudentId(rowKey)}>
                        <td>
                          <button type="button" className="ck-table-link" onClick={(e) => { e.stopPropagation(); setSelectedReportStudentId(rowKey); }}>
                            {studentName(row)}
                          </button>
                          <div className="ts">{[classSection(row), firstValue(row, ['admissionNumber', 'admissionNo'], '') ? `Adm. ${firstValue(row, ['admissionNumber', 'admissionNo'], '')}` : ''].filter(Boolean).join(' - ') || '-'}</div>
                        </td>
                        <td><div className="tb">{feePlan(row)}</div><div className="ts">{feeSchedule(row)}</div></td>
                        <td className="col-money">Rs {formatPaise(totalPaise(row))}</td>
                        <td className="col-money"><div className="tb">Rs {formatPaise(discountPaise(row))}</div><div className="ts">Surcharge Rs {formatPaise(surchargePaise(row))}</div></td>
                        <td className="col-money ck-amt-green">Rs {formatPaise(paidPaise(row))}</td>
                        <td className={`col-money ${duePaise(row) > 0 ? 'ck-amt-red' : 'ck-amt-green'}`}>Rs {formatPaise(duePaise(row))}</td>
                        <td>{feeStatusBadge(computedStatus(row))}</td>
                        <td>
                          {receiptId ? (
                            <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" onClick={(e) => { e.stopPropagation(); void openReceiptPdf(receiptId); }}>
                              PDF
                            </button>
                          ) : (
                            <span className="ck-muted">-</span>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>

        <div className="ck-card ck-fees-card">
          <div className="ck-card-h">
            <div>
              <div className="ck-card-t">Overdue Follow-up Queue</div>
              <div className="ck-card-sub">Oldest dues are sorted first for office follow-up.</div>
            </div>
            <span className={`ck-pill ${safeOverdueRows.length > 0 ? 'pr' : 'pg'}`}>{safeOverdueRows.length} accounts</span>
          </div>
          {!ledgerLoaded ? (
            <div className="ck-import-zone ck-fees-zone"><div className="iz-title">Select a ledger above to load overdue records.</div></div>
          ) : safeOverdueRows.length ? (
            <div className="ck-table-wrap">
              <table className="ck-table">
                <thead><tr><th>Student</th><th>Schedule</th><th className="col-money">Due Amount</th><th className="col-money">Days Overdue</th></tr></thead>
                <tbody>
                  {safeOverdueRows.map((row, i) => {
                    const days = Number(row.daysOverdue || 0);
                    return (
                      <tr key={row.studentId || row.assignmentId || `${studentName(row)}-${i}`}>
                        <td><div className="tb">{studentName(row)}</div><div className="ts">{classSection(row) || '-'}</div></td>
                        <td>{feeSchedule(row)}</td>
                        <td className="col-money ck-amt-red">Rs {formatPaise(duePaise(row))}</td>
                        <td className={`col-money ${days > 60 ? 'ck-amt-red' : days > 30 ? 'ck-amt-amber' : ''}`}>{days}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="ck-fees-empty">No overdue records for this section.</div>
          )}
        </div>
      </div>
    </ModuleShell>
  );
}
