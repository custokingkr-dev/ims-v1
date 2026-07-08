import React, { useEffect, useRef, useState } from 'react';
import api from '../../../services/api';
import { useAuth } from '../../../contexts/AuthContext';
import { usePermissions } from '../../../hooks/usePermissions';
import { ModuleShell, Field, Stat } from '../ui';
import { formatMoney, formatLakh } from '../utils';
import type { WorkspaceData } from '../config';

interface Props {
  workspace: WorkspaceData | null;
  onRefresh: () => void;
}

export function FeesPanel({ workspace, onRefresh }: Props) {
  const { user } = useAuth();
  const { can } = usePermissions();
  const schoolScopedParams = !can('platform:admin') && user?.branchId ? { schoolId: user.branchId } : undefined;

  // Real fee summary from school-core (the /workspace fees.summary is a hardcoded-0 stub).
  const [feeSummary, setFeeSummary] = useState({ progressPercent: 0, collected: 0, outstanding: 0, overdueCount: 0, target: 0 });

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
      // Non-fatal: keep the prior summary rather than blanking the cards.
    }
  };

  const [saving, setSaving] = useState('');
  const [feeClasses, setFeeClasses] = useState<any[]>([]);
  const [feeLoadError, setFeeLoadError] = useState('');

  const [paymentForm, setPaymentForm] = useState({ studentId: '', studentName: '', amount: '', paymentMode: 'UPI', notes: '' });
  const [paymentOptions, setPaymentOptions] = useState<any>({ sections: [], students: [] });
  const [paymentSelection, setPaymentSelection] = useState<any>({ classId: '', sectionId: '', studentId: '' });
  const [paymentDuePreview, setPaymentDuePreview] = useState<any | null>(null);
  const [paymentError, setPaymentError] = useState('');
  const [paymentSuccess, setPaymentSuccess] = useState('');

  const [feeFilters, setFeeFilters] = useState({ className: '', sectionName: '' });
  const [reportOptions, setReportOptions] = useState<any>({ sections: [] });
  const [reportRows, setReportRows] = useState<any[]>([]);
  const [overdueRows, setOverdueRows] = useState<any[]>([]);
  const [reportLoading, setReportLoading] = useState(false);
  const [selectedReportStudentId, setSelectedReportStudentId] = useState<string | null>(null);

  const [reminderSaving, setReminderSaving] = useState(false);
  const [reminderNotice, setReminderNotice] = useState('');
  const [reminderError, setReminderError] = useState('');

  const paymentTimerRef = useRef<number | null>(null);

  const loadFeeClasses = async () => {
    try {
      setFeeLoadError('');
      const res = await api.get('/classes', { params: schoolScopedParams });
      setFeeClasses(res.data || []);
    } catch (err: unknown) {
      setFeeClasses([]);
      setFeeLoadError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not load classes.'));
    }
  };

  const loadSections = async (classId: string, target: 'payment' | 'report') => {
    try {
      setFeeLoadError('');
      const res = await api.get(`/classes/${encodeURIComponent(classId)}/sections`, { params: schoolScopedParams });
      if (target === 'payment') setPaymentOptions((prev: any) => ({ ...prev, sections: res.data || [], students: [] }));
      if (target === 'report') setReportOptions({ sections: res.data || [] });
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not load sections.');
      setFeeLoadError(msg);
      if (target === 'payment') setPaymentOptions((prev: any) => ({ ...prev, sections: [], students: [] }));
      if (target === 'report') setReportOptions({ sections: [] });
    }
  };

  const loadSectionStudents = async (classId: string, sectionId: string) => {
    try {
      setFeeLoadError('');
      // Fee-aware source: only students with a fee plan can be collected against, and each
      // row carries plan/total/paid/due for the payment preview + amount auto-fill.
      const res = await api.get('/fees/report', { params: { classId, sectionId, ...(schoolScopedParams || {}) } });
      const students = (Array.isArray(res.data) ? res.data : []).map((r: any) => ({
        id: r.studentId,
        name: r.student,
        admissionNo: r.admissionNumber || r.admissionNo || '',
        feePlan: r.planName,
        schedule: r.schedule,
        totalFee: r.totalAnnualFee,
        discount: r.discounts ?? 0,
        paid: r.paid,
        dueAmount: r.due ?? r.dueAmount ?? 0,
      }));
      setPaymentOptions((prev: any) => ({ ...prev, students }));
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not load students.');
      setFeeLoadError(msg);
      setPaymentOptions((prev: any) => ({ ...prev, students: [] }));
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
        if (prev && nextReportRows.some((row: any) => String(row.studentId || row.assignmentId || '') === prev)) return prev;
        const first = nextReportRows[0];
        return first ? String(first.studentId || first.assignmentId || '') : null;
      });
    } catch (err: unknown) {
      setReportRows([]);
      setOverdueRows([]);
      setSelectedReportStudentId(null);
      setFeeLoadError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not load fee reports.'));
    } finally {
      setReportLoading(false);
    }
  };

  const openReceiptPdf = async (paymentId: string) => {
    try {
      setPaymentError('');
      const res = await api.get(`/fees/receipts/${encodeURIComponent(paymentId)}/pdf`, { responseType: 'blob' });
      const url = URL.createObjectURL(new Blob([res.data], { type: 'application/pdf' }));
      window.open(url, '_blank', 'noopener,noreferrer');
    } catch (err: unknown) {
      setPaymentError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not open receipt PDF.'));
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
    setPaymentSelection((prev: any) => ({ ...prev, sectionId, studentId: '' }));
    setPaymentOptions((prev: any) => ({ ...prev, students: [] }));
    setPaymentDuePreview(null);
    setPaymentError('');
    setPaymentForm((prev) => ({ ...prev, studentId: '', studentName: '', amount: '' }));
    if (!sectionId) return;
    await loadSectionStudents(paymentSelection.classId, sectionId);
  };

  const handlePaymentStudentChange = (studentId: string) => {
    setPaymentSelection((prev: any) => ({ ...prev, studentId }));
    const selected = paymentOptions.students.find((row: any) => String(row.id) === studentId);
    setPaymentDuePreview(selected || null);
    setPaymentError('');
    setPaymentForm((prev) => ({ ...prev, studentId, studentName: selected?.name || '', amount: selected ? String(Math.floor(Number(selected.dueAmount || 0) / 100)) : '' }));
  };

  const handleReportClassChange = async (classId: string) => {
    setFeeFilters({ className: classId, sectionName: '' });
    setReportRows([]);
    setOverdueRows([]);
    if (!classId) { setReportOptions({ sections: [] }); return; }
    await loadSections(classId, 'report');
  };

  const handleRecordPayment = async () => {
    try {
      setSaving('payment');
      setPaymentError('');
      setPaymentSuccess('');
      const duePaise = Number(paymentDuePreview?.dueAmount || 0);
      const amountPaise = Math.round(Number(paymentForm.amount || 0) * 100);
      if (paymentDuePreview && amountPaise > duePaise) {
        setPaymentError(`Amount ₹${paymentForm.amount} exceeds the due amount of ₹${formatMoney(duePaise / 100)}. Please verify.`);
        return;
      }
      const payload = {
        studentId: paymentForm.studentId,
        amount: amountPaise,
        mode: paymentForm.paymentMode,
        notes: paymentForm.notes,
        paidAt: new Date().toISOString(),
        recordedBy: user?.userId || user?.email || 'current-user',
      };
      await api.post('/workspace/fees/record-payment', payload);
      await onRefresh();
      await loadFeeSummary();
      if (feeFilters.className && feeFilters.sectionName) {
        await loadFeeReports(feeFilters.className, feeFilters.sectionName);
      }
      if (paymentSelection.classId && paymentSelection.sectionId) {
        await loadSectionStudents(paymentSelection.classId, paymentSelection.sectionId);
      }
      setPaymentSuccess(`Payment of ₹${paymentForm.amount} recorded for ${paymentForm.studentName || 'the selected student'}.`);
      setPaymentForm({ studentId: '', studentName: '', amount: '', paymentMode: 'UPI', notes: '' });
      setPaymentSelection((prev: any) => ({ ...prev, studentId: '' }));
      setPaymentDuePreview(null);
      if (paymentTimerRef.current) window.clearTimeout(paymentTimerRef.current);
      paymentTimerRef.current = window.setTimeout(() => setPaymentSuccess(''), 4000);
    } catch (err: unknown) {
      setPaymentError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not record payment.'));
    } finally {
      setSaving('');
    }
  };

  const handleSendReminders = async () => {
    setReminderNotice('');
    setReminderError('');
    if (!(feeFilters.className && feeFilters.sectionName)) {
      setReminderError('Select a class and section before sending reminders.');
      return;
    }
    try {
      setReminderSaving(true);
      const res = await api.post('/fees/send-reminders', { classId: feeFilters.className, sectionId: feeFilters.sectionName });
      const queued = Number(res.data?.queued || overdueRows.length || 0);
      setReminderNotice(`Reminders queued for ${queued} overdue students.`);
      window.setTimeout(() => setReminderNotice(''), 4000);
    } catch (err: unknown) {
      setReminderError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || (err instanceof Error ? err.message : 'Could not queue reminders.'));
    } finally {
      setReminderSaving(false);
    }
  };

  const exportReportCsv = () => {
    const safeRows = Array.isArray(reportRows) ? reportRows : [];
    const headers = ['Student', 'Class', 'Plan', 'Schedule', 'Total Fee', 'Discount', 'Paid', 'Due', 'Status'];
    const rows = safeRows.map((r: any) => [
      r.studentName || r.student || '',
      r.classSection || [r.className, r.sectionName].filter(Boolean).join(' · '),
      r.planName || '',
      r.paymentSchedule || r.schedule || '',
      (Number(r.totalAnnualFee || 0) / 100).toFixed(2),
      (Number(r.approvedDiscount ?? r.discounts ?? 0) / 100).toFixed(2),
      (Number(r.paid || 0) / 100).toFixed(2),
      (Number(r.dueAmount ?? r.due ?? 0) / 100).toFixed(2),
      r.status || '',
    ]);
    const esc = (v: any) => `"${String(v ?? '').replace(/"/g, '""')}"`;
    const csv = [headers, ...rows].map((r) => r.map(esc).join(',')).join('\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `fee-report-${feeFilters.className || 'class'}-${feeFilters.sectionName || 'section'}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  useEffect(() => {
    loadFeeClasses();
    loadFeeSummary();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const safeReportRows = Array.isArray(reportRows) ? reportRows : [];
  const safeOverdueRows = Array.isArray(overdueRows) ? overdueRows : [];
  const selectedReportRow = safeReportRows.find((row: any) => String(row.studentId || row.assignmentId || '') === selectedReportStudentId) || safeReportRows[0] || null;

  function feeStatusBadge(status: string): React.ReactElement {
    const map: Record<string, string> = {
      'Paid':    'sg spaid',
      'Overdue': 'sr soverdue',
      'Partial': 'sor spartial',
      'Pending': 'sam spending',
    };
    return <span className={`ck-status ${map[status] ?? 'sgr sneutral'}`}>{status}</span>;
  }

  if (!workspace) return null;

  return (
    <ModuleShell
      title="Fee Collections"
      subtitle="Per-student fee assignment, dues tracking, overdue reporting and receipt generation"
      actions={
        <button
          className="ck-btn ck-btn-ghost"
          onClick={handleSendReminders}
          disabled={reminderSaving || !(feeFilters.className && feeFilters.sectionName)}
          title={feeFilters.className && feeFilters.sectionName ? 'Queue reminder messages for overdue students' : 'Select a class and section first'}
        >
          {reminderSaving ? 'Sending…' : 'Send reminders'}
        </button>
      }
    >
      <div className="ck-panel-stack">
        {feeLoadError ? <div className="ck-alert ck-alert-am"><span>!</span><div>{feeLoadError}</div></div> : null}
        {reminderNotice ? <div className="ck-alert ck-alert-g"><span>✓</span><div>{reminderNotice}</div></div> : null}
        {reminderError ? <div className="ck-alert ck-alert-re"><span>!</span><div>{reminderError}</div></div> : null}

        <div className="ck-card ck-progress-card">
          <div className="ck-progress-wrap">
            <div className="ck-progress-label">
              <span>Academic year collection · {workspace?.school?.meta || 'Current year'}</span>
              <span>{feeSummary.progressPercent}%</span>
            </div>
            <div className="ck-progress-bar">
              <div className="ck-progress-fill" style={{ width: `${feeSummary.progressPercent}%`, background: feeSummary.progressPercent < 60 ? 'linear-gradient(to right, var(--am), #f5b041)' : 'linear-gradient(to right, var(--g), #2ecc71)' }} />
            </div>
            <div className="ck-progress-meta">
              ₹{formatMoney(Number(feeSummary.collected || 0) / 100)} collected ·
              ₹{formatMoney(Number(feeSummary.outstanding || 0) / 100)} outstanding ·
              <span style={{ color: Number(feeSummary.overdueCount || 0) > 0 ? 'var(--am)' : 'var(--ink2)', fontWeight: 700 }}>
                {feeSummary.overdueCount} overdue accounts
              </span>
            </div>
          </div>
        </div>

        <div className="ck-stats ck-s4">
          <Stat label="Total payable" value={`₹${formatLakh(Math.round(Number(feeSummary.target || 0) / 100))}`} sub={`Full value ₹${formatMoney(Number(feeSummary.target || 0) / 100)}`} pill="Across all schedules" tone="blue" />
          <Stat label="Collected" value={`₹${formatLakh(Math.round(Number(feeSummary.collected || 0) / 100))}`} sub={`Full value ₹${formatMoney(Number(feeSummary.collected || 0) / 100)}`} pill="Live updates" tone="green" />
          <Stat label="Outstanding" value={`₹${formatLakh(Math.round(Number(feeSummary.outstanding || 0) / 100))}`} sub={`Full value ₹${formatMoney(Number(feeSummary.outstanding || 0) / 100)}`} pill={`${feeSummary.overdueCount} overdue`} tone="red" />
          <Stat label="Schedules" value="4" sub="Monthly · Quarterly · Half-yearly · Annual" pill="Configurable" tone="orange" />
        </div>

        {/* Fee collection summary metric cards */}
        <div className="ck-stats ck-s4">
          {/* Total Collected — green accent with progress bar */}
          <div className="ck-metric-card ck-mc-green">
            <div className="ck-mc-label">Total Collected</div>
            <div className="ck-mc-value">₹{formatMoney(Number(feeSummary.collected || 0) / 100)}</div>
            <div className="ck-mc-sub">{feeSummary.progressPercent}% of target</div>
            <div className="ck-mc-bar-track">
              <div
                className="ck-mc-bar-fill"
                style={{ width: `${Math.min(100, Number(feeSummary.progressPercent || 0))}%`, background: 'var(--g)' }}
              />
            </div>
          </div>
          {/* Overdue — red accent */}
          <div className="ck-metric-card ck-mc-red">
            <div className="ck-mc-label">Overdue Accounts</div>
            <div className="ck-mc-value">{feeSummary.overdueCount}</div>
            <div className="ck-mc-sub">Students with past-due balance</div>
          </div>
          {/* Outstanding (Pending) — amber accent */}
          <div className="ck-metric-card ck-mc-amber">
            <div className="ck-mc-label">Outstanding</div>
            <div className="ck-mc-value">₹{formatMoney(Number(feeSummary.outstanding || 0) / 100)}</div>
            <div className="ck-mc-sub">Remaining to collect</div>
          </div>
          {/* Target — blue accent */}
          <div className="ck-metric-card ck-mc-blue">
            <div className="ck-mc-label">Annual Target</div>
            <div className="ck-mc-value">₹{formatMoney(Number(feeSummary.target || 0) / 100)}</div>
            <div className="ck-mc-sub">Total fees assigned</div>
          </div>
        </div>

        {/* Record payment */}
        <div className="ck-card">
          <div className="ck-card-h"><div className="ck-card-t">Record installment payment</div></div>
          {paymentSuccess ? <div style={{ padding: '16px 16px 0' }}><div className="ck-alert ck-alert-g"><span>✓</span><div>{paymentSuccess}</div></div></div> : null}
          <div style={{ padding: '16px 16px 0' }} className="ts">Student selection</div>
          <div className="ck-form-grid ck-fg-3" style={{ padding: 16 }}>
            <Field label="Class">
              <select value={paymentSelection.classId} onChange={(e) => handlePaymentClassChange(e.target.value)}>
                <option value="">Select class</option>
                {feeClasses.map((row: any) => <option key={row.id} value={row.id}>{row.name}</option>)}
              </select>
            </Field>
            <Field label="Section">
              <select disabled={!paymentSelection.classId} value={paymentSelection.sectionId} onChange={(e) => handlePaymentSectionChange(e.target.value)}>
                <option value="">Select section</option>
                {paymentOptions.sections.map((row: any) => <option key={row.id} value={row.id}>{row.name}</option>)}
              </select>
              {!paymentSelection.classId ? <div className="ts">Select a class first</div> : null}
            </Field>
            <Field label="Student">
              <select disabled={!paymentSelection.sectionId} value={paymentSelection.studentId} onChange={(e) => handlePaymentStudentChange(e.target.value)}>
                <option value="">Select student</option>
                {paymentOptions.students.map((student: any) => <option key={student.id} value={student.id}>{student.name} · {student.admissionNo}</option>)}
              </select>
              {!paymentSelection.sectionId ? <div className="ts">Select a section first</div> : null}
            </Field>
          </div>
          <div style={{ padding: '0 16px' }}><div className="ck-divider" /></div>
          <div style={{ padding: '16px 16px 0' }} className="ts">Payment details</div>
          <div className="ck-form-grid ck-fg-3" style={{ padding: 16 }}>
            <Field label="Amount"><input type="number" min={0} step="0.01" value={paymentForm.amount} onChange={(e) => setPaymentForm({ ...paymentForm, amount: e.target.value })} /></Field>
            <Field label="Mode">
              <select value={paymentForm.paymentMode} onChange={(e) => setPaymentForm({ ...paymentForm, paymentMode: e.target.value })}>
                <option>UPI</option><option>Cash</option><option>Bank transfer</option><option>Cheque</option>
              </select>
            </Field>
            <Field label="Notes"><input value={paymentForm.notes} onChange={(e) => setPaymentForm({ ...paymentForm, notes: e.target.value })} placeholder="Optional" /></Field>
          </div>
          {paymentDuePreview ? (
            <div style={{ padding: '0 16px 16px' }}>
              <div className="ck-alert ck-alert-re">
                <span>₹</span>
                <div>
                  <strong>{paymentDuePreview.feePlan}</strong> · {paymentDuePreview.schedule}
                  <div>Total fee ₹{formatMoney(Number(paymentDuePreview.totalFee || 0) / 100)} · Discount ₹{formatMoney(Number(paymentDuePreview.discount || 0) / 100)} · Paid ₹{formatMoney(Number(paymentDuePreview.paid || 0) / 100)} · <span style={{ color: Number(paymentDuePreview.dueAmount) > 0 ? '#A32D2D' : undefined, fontWeight: 700 }}>Due ₹{formatMoney(Number(paymentDuePreview.dueAmount || 0) / 100)}</span></div>
                </div>
              </div>
            </div>
          ) : null}
          {paymentError ? <div style={{ padding: '0 16px 16px' }}><div className="ck-alert ck-alert-re"><span>!</span><div>{paymentError}</div></div></div> : null}
          <div className="ck-actions-inline" style={{ padding: '0 16px 16px' }}>
            <button disabled={!(paymentForm.studentId && Number(paymentForm.amount) > 0 && paymentForm.paymentMode) || saving === 'payment'} className="ck-btn ck-btn-g" onClick={handleRecordPayment}>
              {saving === 'payment' ? 'Saving…' : 'Save payment'}
            </button>
          </div>
        </div>

        {/* Reports */}
        <div className="ck-card">
          <div className="ck-card-h ck-card-h-wrap">
            <div className="ck-card-t">Reports &amp; filters</div>
            <div className="ck-card-inline-filters">
              <select value={feeFilters.className} onChange={(e) => handleReportClassChange(e.target.value)}>
                <option value="">Select class</option>
                {feeClasses.map((row: any) => <option key={row.id} value={row.id}>{row.name}</option>)}
              </select>
              <select disabled={!feeFilters.className} value={feeFilters.sectionName} onChange={async (e) => {
                const sectionId = e.target.value;
                setFeeFilters((f) => ({ ...f, sectionName: sectionId }));
                setReportRows([]);
                setOverdueRows([]);
                setSelectedReportStudentId(null);
                if (feeFilters.className && sectionId) await loadFeeReports(feeFilters.className, sectionId);
              }}>
                <option value="">Select section</option>
                {reportOptions.sections.map((row: any) => <option key={row.id} value={row.id}>{row.name}</option>)}
              </select>
              <button className="ck-btn ck-btn-ghost" onClick={exportReportCsv} disabled={!safeReportRows.length}>Export CSV</button>
            </div>
          </div>

          {!feeFilters.className || !feeFilters.sectionName ? (
            <div className="ck-import-zone" style={{ margin: 16 }}><div className="iz-title">Select a class and section to load fee reports</div></div>
          ) : reportLoading ? (
            <div style={{ padding: 24, textAlign: 'center', color: 'var(--ink3)' }}>Loading records…</div>
          ) : !safeReportRows.length ? (
            <div className="ck-import-zone" style={{ margin: 16 }}><div className="iz-title">No records for this section</div></div>
          ) : (
            <div className="ck-table-wrap">
              <table className="ck-table">
                <thead>
                  <tr>
                    <th>Student</th>
                    <th>Plan / Schedule</th>
                    <th className="col-money">Total Annual Fee</th>
                    <th className="col-money">Discounts / Surcharge</th>
                    <th className="col-money">Paid</th>
                    <th className="col-money">Due</th>
                    <th>Status</th>
                    <th>Receipt</th>
                  </tr>
                </thead>
                <tbody>
                  {safeReportRows.map((row: any, idx: number) => {
                    const rowKey = row.assignmentId || row.studentId || row.paymentId || `${row.studentName || row.student || 'student'}-${row.admissionNumber || idx}`;
                    const studentName = row.studentName || row.student || '—';
                    const classSection = row.classSection || [row.className, row.sectionName].filter(Boolean).join(' · ');
                    const admissionNumber = row.admissionNumber || row.admissionNo || '';
                    const paymentSchedule = row.paymentSchedule || row.schedule || '—';
                    const approvedDiscount = row.approvedDiscount ?? row.discounts ?? 0;
                    const surchargeAmount = row.surchargeAmount ?? row.surcharge ?? 0;
                    const dueAmount = row.dueAmount ?? row.due ?? 0;
                    const paymentId = row.payments?.[0]?.paymentId || row.paymentId;
                    const selectedId = String(row.studentId || row.assignmentId || '');
                    const isSelected = selectedReportStudentId ? selectedReportStudentId === selectedId : idx === 0;
                    return (
                      <tr key={rowKey} className={isSelected ? 'ck-row-selected' : ''} style={{ cursor: 'pointer' }} onClick={() => setSelectedReportStudentId(selectedId)}>
                        <td><div className="tb">{studentName}</div><div className="ts">{[classSection, admissionNumber ? `Adm. ${admissionNumber}` : ''].filter(Boolean).join(' · ') || '—'}</div></td>
                        <td><div className="tb">{row.planName || '—'}</div><div className="ts">{paymentSchedule}</div></td>
                        <td className="col-money">₹{formatMoney(Number(row.totalAnnualFee || 0) / 100)}</td>
                        <td className="col-money"><div className="tb">Discount ₹{formatMoney(Number(approvedDiscount || 0) / 100)}</div><div className="ts">Surcharge ₹{formatMoney(Number(surchargeAmount || 0) / 100)}</div></td>
                        <td className="col-money ck-amt-green">₹{formatMoney(Number(row.paid || 0) / 100)}</td>
                        <td className={`col-money ${Number(dueAmount) > 0 ? 'ck-amt-red' : 'col-money'}`}>₹{formatMoney(Number(dueAmount || 0) / 100)}</td>
                        <td>{feeStatusBadge(row.status || 'Pending')}</td>
                        <td>{paymentId ? <button className="ck-btn ck-btn-ghost" onClick={(e) => { e.stopPropagation(); openReceiptPdf(paymentId); }}>PDF</button> : '—'}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {/* Overdue list */}
        <div className="ck-card">
          <div className="ck-card-h">
            <div className="ck-card-t">Overdue list <span className="ck-pill pr">{safeOverdueRows.length}</span></div>
          </div>
          {!feeFilters.className || !feeFilters.sectionName ? (
            <div className="ck-import-zone" style={{ margin: 16 }}><div className="iz-title">Select a class and section above to load overdue records</div></div>
          ) : safeOverdueRows.length ? (
            <div className="ck-table-wrap">
              <table className="ck-table">
                <thead><tr><th>Student</th><th>Schedule</th><th className="col-money">Due Amount</th><th className="col-money">Days Overdue</th></tr></thead>
                <tbody>
                  {safeOverdueRows.map((row: any, i: number) => {
                    const days = Number(row.daysOverdue || 0);
                    const dayColor = days > 60 ? 'var(--re)' : days > 30 ? 'var(--am)' : 'var(--ink2)';
                    return (
                      <tr key={row.studentId || row.assignmentId || `${row.studentName || row.student || 'student'}-${i}`}>
                        <td><div className="tb">{row.studentName || row.student || '—'}</div><div className="ts">{row.classSection || [row.className, row.sectionName].filter(Boolean).join(' · ') || '—'}</div></td>
                        <td>{row.schedule || '—'}</td>
                        <td className="col-money ck-amt-red">₹{formatMoney(Number(row.dueAmount || 0) / 100)}</td>
                        <td className="col-money" style={{ color: dayColor, fontWeight: 700 }}>{days}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          ) : (
            <div style={{ padding: 16 }}>No overdue records for this section.</div>
          )}
        </div>

        {/* Per-student statement */}
        {selectedReportRow ? (
          <div className="ck-card">
            <div className="ck-card-h"><div className="ck-card-t">Per-student dues statement — {selectedReportRow.studentName || selectedReportRow.student || 'Student'}</div></div>
            <div style={{ padding: 16 }}>
              <div className="ck-alert ck-alert-g">
                <span>✓</span>
                <div>Total annual fee ₹{formatMoney(Number(selectedReportRow.totalAnnualFee || 0) / 100)} − approved discounts ₹{formatMoney(Number(selectedReportRow.approvedDiscount ?? selectedReportRow.discounts ?? 0) / 100)} − payments ₹{formatMoney(Number(selectedReportRow.paid || 0) / 100)} = due ₹{formatMoney(Number(selectedReportRow.dueAmount ?? selectedReportRow.due ?? 0) / 100)}</div>
              </div>
              {Array.isArray(selectedReportRow.installments) && selectedReportRow.installments.length ? (
                <div className="ck-table-wrap">
                  <table className="ck-table">
                    <thead><tr><th>Installment</th><th>Due date</th><th>Paid date</th><th className="col-money">Amount</th><th>Status</th></tr></thead>
                    <tbody>
                      {selectedReportRow.installments.map((ins: any, idx: number) => (
                        <tr key={ins.installmentNo || ins.dueDate || idx}>
                          <td>#{ins.installmentNo || idx + 1}</td>
                          <td>{ins.dueDate || '—'}</td>
                          <td>{ins.paidDate || '—'}</td>
                          <td className="col-money">₹{formatMoney(Number(ins.amount || 0) / 100)}</td>
                          <td>{feeStatusBadge(ins.status || 'Pending')}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <div className="ck-import-zone" style={{ marginTop: 16 }}>
                  <div className="iz-title">Installment-wise statement is not available for this fee record yet.</div>
                </div>
              )}
            </div>
          </div>
        ) : null}
      </div>
    </ModuleShell>
  );
}
