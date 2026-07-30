import { useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  Check,
  ChevronRight,
  CircleDollarSign,
  Download,
  FilePlus2,
  Pencil,
  Plus,
  ReceiptIndianRupee,
  Search,
  Send,
  Settings2,
  ShieldCheck,
  Trash2,
  Users,
  X,
} from 'lucide-react';
import api from '../../../services/api';
import {
  assignFeePlan,
  createFeeBand,
  createFeeBandRevision,
  createFeeItem,
  getFeeConfigurationHealth,
  getFeeDiscountRules,
  getFeeStructure,
  publishFeeBand,
  recordFeePayment,
  saveFeeDiscountRule,
  saveFeeInstallments,
  updateFeeBand,
  updateFeeItem,
  deleteFeeItem,
  type FeeBandModel,
  type FeeConfigurationHealth,
  type FeeDiscountRule,
  type FeeInstallment,
  type FeeStructureModel,
} from '../../../services/feeApi';
import { useAuth } from '../../../contexts/AuthContext';
import { usePermissions } from '../../../hooks/usePermissions';
import { ModuleShell } from '../ui';
import { formatPaise, paiseToRupeeInput } from '../utils';
import type { WorkspaceData } from '../config';

type FeeView = 'overview' | 'configuration';
type ConfigView = 'plans' | 'concessions' | 'assignments';
type Option = { id: string | number; name?: string; label?: string; active?: boolean };

interface FeeModulePanelProps {
  workspace: WorkspaceData | null;
  onRefresh: () => void | Promise<void>;
  initialView?: FeeView;
}

const emptyStructure: FeeStructureModel = { academicYearId: '', academicYear: '', bands: [] };
const schedules = ['Monthly', 'Quarterly', 'Half-yearly', 'Annual'];
const statuses = ['ALL', 'PAID', 'PARTIAL', 'PENDING', 'OVERDUE'];

function errorMessage(error: unknown, fallback: string): string {
  return (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    || (error instanceof Error ? error.message : fallback);
}

function valueOf(row: any, keys: string[], fallback: any = ''): any {
  for (const key of keys) {
    if (row?.[key] !== undefined && row?.[key] !== null) return row[key];
  }
  return fallback;
}

function rowStatus(row: any): string {
  const explicit = String(row?.status || '').toUpperCase();
  if (explicit) return explicit;
  const due = Number(valueOf(row, ['dueAmountPaise', 'dueAmount', 'due'], 0));
  const paid = Number(valueOf(row, ['paidPaise', 'paid'], 0));
  if (due <= 0) return 'PAID';
  return paid > 0 ? 'PARTIAL' : 'PENDING';
}

function statusTone(status: string): string {
  if (status === 'PAID' || status === 'PUBLISHED') return 'good';
  if (status === 'OVERDUE') return 'danger';
  if (status === 'PARTIAL' || status === 'DRAFT') return 'warn';
  return 'neutral';
}

function money(row: any, keys: string[]): number {
  return Number(valueOf(row, keys, 0) || 0);
}

function isoDate(monthOffset: number): string {
  const date = new Date();
  date.setMonth(date.getMonth() + monthOffset);
  date.setDate(10);
  return date.toISOString().slice(0, 10);
}

export function FeeModulePanel({ workspace, onRefresh, initialView = 'overview' }: FeeModulePanelProps) {
  const { user } = useAuth();
  const { can, canAny } = usePermissions();
  const schoolId = user?.branchId ?? undefined;
  const schoolParams = schoolId ? { schoolId } : {};
  const canManage = can('fee_structure:manage');
  const canAssign = can('fee:assign');
  const canCollect = canAny(['fee:collect', 'payment:create']);
  const canRemind = canAny(['fee:collect', 'notification:send']);

  const [view, setView] = useState<FeeView>(initialView);
  const [configView, setConfigView] = useState<ConfigView>('plans');
  const [years, setYears] = useState<Option[]>([]);
  const [classes, setClasses] = useState<Option[]>([]);
  const [sections, setSections] = useState<Option[]>([]);
  const [students, setStudents] = useState<any[]>([]);
  const [yearId, setYearId] = useState('');
  const [classId, setClassId] = useState('');
  const [sectionId, setSectionId] = useState('');
  const [summary, setSummary] = useState({ collected: 0, target: 0, overdue: 0 });
  const [rows, setRows] = useState<any[]>([]);
  const [overdueRows, setOverdueRows] = useState<any[]>([]);
  const [structure, setStructure] = useState<FeeStructureModel>(emptyStructure);
  const [health, setHealth] = useState<FeeConfigurationHealth | null>(null);
  const [rules, setRules] = useState<FeeDiscountRule[]>([]);
  const [selectedBandId, setSelectedBandId] = useState('');
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('ALL');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState('');
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [showPlanForm, setShowPlanForm] = useState(false);
  const [paymentRow, setPaymentRow] = useState<any | null>(null);
  const [payment, setPayment] = useState({ amount: '', mode: 'UPI', notes: '' });
  const [planForm, setPlanForm] = useState({
    name: '',
    classFrom: '1',
    classTo: '5',
    schedules: ['Quarterly', 'Annual'],
    discount: '0',
    gracePeriodDays: '7',
    lateFeeType: 'FIXED',
    lateFeeAmount: '100',
    lateFeeIntervalDays: '30',
  });
  const [itemForm, setItemForm] = useState({ name: '', frequency: 'Annual', amount: '', optional: false });
  const [editingItem, setEditingItem] = useState<{ id: string; name: string; frequency: string; amount: string; optional: boolean } | null>(null);
  const [bandEdit, setBandEdit] = useState({
    name: '',
    classFrom: '1',
    classTo: '1',
    schedules: [] as string[],
    discount: '0',
    gracePeriodDays: '0',
    lateFeeType: 'NONE',
    lateFeeAmount: '0',
    lateFeeIntervalDays: '0',
  });
  const [installments, setInstallments] = useState<FeeInstallment[]>([]);
  const [ruleForm, setRuleForm] = useState({
    name: '',
    ruleType: 'SIBLING',
    percentage: '',
    priority: '100',
  });
  const [assignment, setAssignment] = useState({
    studentId: '',
    bandId: '',
    schedule: '',
    discountRuleId: '',
    manualDiscount: '0',
    surcharge: '0',
    optionalItemIds: [] as string[],
  });

  const selectedBand = structure.bands.find((band) => band.id === selectedBandId) ?? structure.bands[0] ?? null;
  const publishedBands = structure.bands.filter((band) => band.status === 'PUBLISHED');
  const selectedYear = years.find((year) => String(year.id) === yearId);
  const hasActiveYearFlag = years.some((year) => typeof year.active === 'boolean');
  const isActiveYear = !hasActiveYearFlag || Boolean(selectedYear?.active);

  const refreshConfiguration = async (nextYearId = yearId) => {
    const [structureResult, healthResult, rulesResult] = await Promise.all([
      getFeeStructure(nextYearId || undefined, schoolId),
      getFeeConfigurationHealth(nextYearId || undefined, schoolId),
      getFeeDiscountRules(nextYearId || undefined, schoolId),
    ]);
    setStructure(structureResult.data);
    setHealth(healthResult.data);
    setRules(Array.isArray(rulesResult.data) ? rulesResult.data : []);
    const bands = structureResult.data?.bands ?? [];
    setSelectedBandId((current) => bands.some((band) => band.id === current) ? current : bands[0]?.id ?? '');
  };

  const refreshOverview = async (nextYearId = yearId) => {
    const params = { ...schoolParams, academicYearId: nextYearId || undefined };
    const [moduleResult, overdueResult] = await Promise.all([
      api.get('/fees/dashboard/module', { params }),
      api.get('/fees/dashboard/overdue-count', { params }),
    ]);
    const nextSummary = moduleResult.data?.summary ?? {};
    setSummary({
      collected: Number(nextSummary.collected || 0),
      target: Number(nextSummary.target || 0),
      overdue: Number(overdueResult.data?.count || 0),
    });
  };

  const loadBase = async () => {
    setLoading(true);
    setError('');
    try {
      const [yearsResult, classesResult] = await Promise.all([
        api.get<Option[]>('/academic-years', { params: schoolParams }),
        api.get<Option[]>('/classes', { params: schoolParams }),
      ]);
      const yearList = Array.isArray(yearsResult.data) ? yearsResult.data : [];
      setYears(yearList);
      setClasses(Array.isArray(classesResult.data) ? classesResult.data : []);
      const nextYearId = yearId || String(yearList.find((year) => year.active)?.id || yearList[0]?.id || '');
      setYearId(nextYearId);
      await Promise.all([refreshOverview(nextYearId), refreshConfiguration(nextYearId)]);
    } catch (loadError) {
      setError(errorMessage(loadError, 'Fee management could not be loaded.'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadBase();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!selectedBand) {
      setInstallments([]);
      return;
    }
    setInstallments(
      selectedBand.installments?.length
        ? selectedBand.installments.map((row) => ({ ...row }))
        : [
            { label: 'Term 1', dueDate: isoDate(0), sharePercent: 34 },
            { label: 'Term 2', dueDate: isoDate(3), sharePercent: 33 },
            { label: 'Term 3', dueDate: isoDate(6), sharePercent: 33 },
          ],
    );
    setBandEdit({
      name: selectedBand.name,
      classFrom: String(selectedBand.classFrom),
      classTo: String(selectedBand.classTo),
      schedules: [...selectedBand.activeSchedules],
      discount: String(selectedBand.discount),
      gracePeriodDays: String(selectedBand.gracePeriodDays),
      lateFeeType: selectedBand.lateFeeType,
      lateFeeAmount: paiseToRupeeInput(selectedBand.lateFeeAmount),
      lateFeeIntervalDays: String(selectedBand.lateFeeIntervalDays),
    });
    setEditingItem(null);
  }, [selectedBand?.id]);

  const changeYear = async (nextYearId: string) => {
    setYearId(nextYearId);
    setError('');
    try {
      await Promise.all([
        refreshOverview(nextYearId),
        refreshConfiguration(nextYearId),
        sectionId ? changeSection(sectionId, nextYearId) : Promise.resolve(),
      ]);
    } catch (loadError) {
      setError(errorMessage(loadError, 'Fee configuration could not be loaded.'));
    }
  };

  const changeClass = async (nextClassId: string) => {
    setClassId(nextClassId);
    setSectionId('');
    setSections([]);
    setStudents([]);
    setRows([]);
    setOverdueRows([]);
    if (!nextClassId) return;
    try {
      const result = await api.get<Option[]>(`/classes/${encodeURIComponent(nextClassId)}/sections`, { params: schoolParams });
      setSections(Array.isArray(result.data) ? result.data : []);
    } catch (loadError) {
      setError(errorMessage(loadError, 'Sections could not be loaded.'));
    }
  };

  const changeSection = async (nextSectionId: string, nextYearId = yearId) => {
    setSectionId(nextSectionId);
    setRows([]);
    setOverdueRows([]);
    setStudents([]);
    if (!classId || !nextSectionId) return;
    setLoading(true);
    setError('');
    try {
      const [reportResult, overdueResult, studentsResult] = await Promise.all([
        api.get('/fees/report', { params: { classId, sectionId: nextSectionId, academicYearId: nextYearId, ...schoolParams } }),
        api.get('/fees/overdue', { params: { classId, sectionId: nextSectionId, academicYearId: nextYearId, ...schoolParams } }),
        api.get(`/classes/${encodeURIComponent(classId)}/sections/${encodeURIComponent(nextSectionId)}/students`, { params: schoolParams }),
      ]);
      setRows(Array.isArray(reportResult.data) ? reportResult.data : []);
      setOverdueRows(Array.isArray(overdueResult.data) ? overdueResult.data : []);
      setStudents(Array.isArray(studentsResult.data) ? studentsResult.data : []);
    } catch (loadError) {
      setError(errorMessage(loadError, 'Student fee records could not be loaded.'));
    } finally {
      setLoading(false);
    }
  };

  const filteredRows = useMemo(() => {
    const query = search.trim().toLowerCase();
    return rows.filter((row) => {
      const nextStatus = rowStatus(row);
      if (status !== 'ALL' && status !== nextStatus) return false;
      const haystack = [
        valueOf(row, ['studentName', 'name']),
        valueOf(row, ['admissionNumber', 'admissionNo']),
        valueOf(row, ['feePlan', 'planName']),
        nextStatus,
      ].join(' ').toLowerCase();
      return !query || haystack.includes(query);
    });
  }, [rows, search, status]);

  const submitPayment = async () => {
    if (!paymentRow || !canCollect || !isActiveYear) return;
    const amount = Math.round(Number(payment.amount || 0) * 100);
    if (amount <= 0) {
      setError('Enter a payment amount greater than zero.');
      return;
    }
    setSaving('payment');
    setError('');
    try {
      await recordFeePayment({
        studentId: valueOf(paymentRow, ['studentId', 'id']),
        amount,
        mode: payment.mode,
        notes: payment.notes,
        paidAt: new Date().toISOString(),
        actorId: user?.userId,
        ...schoolParams,
      });
      setNotice('Payment recorded and the student ledger was refreshed.');
      setPaymentRow(null);
      await Promise.all([refreshOverview(), changeSection(sectionId), Promise.resolve(onRefresh())]);
    } catch (saveError) {
      setError(errorMessage(saveError, 'Payment could not be recorded.'));
    } finally {
      setSaving('');
    }
  };

  const createPlan = async () => {
    if (!schoolId || !canManage || !isActiveYear) return;
    setSaving('plan');
    setError('');
    try {
      const result = await createFeeBand({
        schoolId,
        name: planForm.name,
        classFrom: Number(planForm.classFrom),
        classTo: Number(planForm.classTo),
        schedules: planForm.schedules,
        discount: Number(planForm.discount),
        gracePeriodDays: Number(planForm.gracePeriodDays),
        lateFeeType: planForm.lateFeeType,
        lateFeeAmount: Number(planForm.lateFeeAmount),
        lateFeeIntervalDays: Number(planForm.lateFeeIntervalDays),
      });
      setShowPlanForm(false);
      setSelectedBandId(result.data.id);
      setNotice('Draft fee plan created. Add fee heads and installments before publishing.');
      await refreshConfiguration();
    } catch (saveError) {
      setError(errorMessage(saveError, 'Fee plan could not be created.'));
    } finally {
      setSaving('');
    }
  };

  const addItem = async () => {
    if (!selectedBand || selectedBand.status !== 'DRAFT' || !isActiveYear) return;
    setSaving('item');
    setError('');
    try {
      await createFeeItem({
        bandId: selectedBand.id,
        itemName: itemForm.name,
        frequency: itemForm.frequency,
        amount: Number(itemForm.amount),
        optional: itemForm.optional,
      });
      setItemForm({ name: '', frequency: 'Annual', amount: '', optional: false });
      setNotice('Fee head added to the draft plan.');
      await refreshConfiguration();
    } catch (saveError) {
      setError(errorMessage(saveError, 'Fee head could not be added.'));
    } finally {
      setSaving('');
    }
  };

  const savePlanSettings = async () => {
    if (!selectedBand || selectedBand.status !== 'DRAFT' || !isActiveYear) return;
    setSaving('plan-settings');
    setError('');
    try {
      await updateFeeBand(selectedBand.id, {
        name: bandEdit.name,
        classFrom: Number(bandEdit.classFrom),
        classTo: Number(bandEdit.classTo),
        discount: Number(bandEdit.discount),
        schedules: bandEdit.schedules,
        gracePeriodDays: Number(bandEdit.gracePeriodDays),
        lateFeeType: bandEdit.lateFeeType,
        lateFeeAmount: Number(bandEdit.lateFeeAmount),
        lateFeeIntervalDays: Number(bandEdit.lateFeeIntervalDays),
      });
      setNotice('Draft plan rules updated.');
      await refreshConfiguration();
    } catch (saveError) {
      setError(errorMessage(saveError, 'Draft plan rules could not be updated.'));
    } finally {
      setSaving('');
    }
  };

  const saveItemEdit = async () => {
    if (!editingItem || !selectedBand || selectedBand.status !== 'DRAFT' || !isActiveYear) return;
    setSaving(`item-${editingItem.id}`);
    setError('');
    try {
      await updateFeeItem(editingItem.id, {
        itemName: editingItem.name,
        frequency: editingItem.frequency,
        amount: Number(editingItem.amount),
        optional: editingItem.optional,
      });
      setEditingItem(null);
      setNotice('Fee head updated.');
      await refreshConfiguration();
    } catch (saveError) {
      setError(errorMessage(saveError, 'Fee head could not be updated.'));
    } finally {
      setSaving('');
    }
  };

  const removeItem = async (itemId: string) => {
    if (!selectedBand || selectedBand.status !== 'DRAFT' || !isActiveYear) return;
    setSaving(`item-${itemId}`);
    setError('');
    try {
      await deleteFeeItem(itemId);
      setEditingItem(null);
      setNotice('Fee head removed from the draft plan.');
      await refreshConfiguration();
    } catch (saveError) {
      setError(errorMessage(saveError, 'Fee head could not be removed.'));
    } finally {
      setSaving('');
    }
  };

  const saveInstallmentPlan = async () => {
    if (!selectedBand || selectedBand.status !== 'DRAFT' || !isActiveYear) return;
    setSaving('installments');
    setError('');
    try {
      await saveFeeInstallments(selectedBand.id, installments.map((row, index) => ({ ...row, sortOrder: index })));
      setNotice('Installment dates and percentages saved.');
      await refreshConfiguration();
    } catch (saveError) {
      setError(errorMessage(saveError, 'Installments could not be saved.'));
    } finally {
      setSaving('');
    }
  };

  const publishPlan = async () => {
    if (!selectedBand || selectedBand.status !== 'DRAFT' || !isActiveYear) return;
    setSaving('publish');
    setError('');
    try {
      await publishFeeBand(selectedBand.id);
      setNotice(`${selectedBand.name} is published and can now be assigned.`);
      await refreshConfiguration();
    } catch (saveError) {
      setError(errorMessage(saveError, 'Fee plan could not be published.'));
    } finally {
      setSaving('');
    }
  };

  const startPlanRevision = async () => {
    if (!selectedBand || selectedBand.status !== 'PUBLISHED' || !isActiveYear) return;
    setSaving('revision');
    setError('');
    try {
      const result = await createFeeBandRevision(selectedBand.id);
      await refreshConfiguration();
      setSelectedBandId(result.data.id);
      setNotice('A new draft revision was created from the published plan.');
    } catch (saveError) {
      setError(errorMessage(saveError, 'A new fee plan revision could not be created.'));
    } finally {
      setSaving('');
    }
  };

  const saveRule = async () => {
    if (!schoolId || !canManage || !isActiveYear) return;
    setSaving('rule');
    setError('');
    try {
      await saveFeeDiscountRule({
        schoolId,
        academicYearId: yearId,
        name: ruleForm.name,
        ruleType: ruleForm.ruleType,
        percentage: Number(ruleForm.percentage),
        priority: Number(ruleForm.priority),
        active: true,
      });
      setRuleForm({ name: '', ruleType: 'SIBLING', percentage: '', priority: '100' });
      setNotice('Concession rule saved for the selected academic year.');
      await refreshConfiguration();
    } catch (saveError) {
      setError(errorMessage(saveError, 'Concession rule could not be saved.'));
    } finally {
      setSaving('');
    }
  };

  const saveAssignment = async () => {
    if (!canAssign || !isActiveYear) return;
    const band = publishedBands.find((row) => row.id === assignment.bandId);
    if (!band) {
      setError('Select a published fee plan.');
      return;
    }
    setSaving('assignment');
    setError('');
    try {
      await assignFeePlan({
        studentId: assignment.studentId,
        bandId: assignment.bandId,
        schedule: assignment.schedule,
        optionalItemIds: assignment.optionalItemIds,
        bandDiscount: band.discount,
        discountRuleId: assignment.discountRuleId || null,
        manualDiscount: Number(assignment.manualDiscount),
        surcharge: Number(assignment.surcharge),
        academicYearId: yearId,
        actorId: user?.userId,
      });
      setNotice('The published fee plan is now connected to the student ledger.');
      if (sectionId) await changeSection(sectionId);
    } catch (saveError) {
      setError(errorMessage(saveError, 'Fee plan could not be assigned.'));
    } finally {
      setSaving('');
    }
  };

  const sendReminders = async () => {
    if (!classId || !sectionId || !canRemind || !isActiveYear) return;
    setSaving('reminders');
    setError('');
    try {
      const result = await api.post('/fees/send-reminders', { classId, sectionId, academicYearId: yearId, ...schoolParams });
      setNotice(`${Number(result.data?.queued || overdueRows.length)} overdue reminders queued.`);
    } catch (saveError) {
      setError(errorMessage(saveError, 'Reminders could not be queued.'));
    } finally {
      setSaving('');
    }
  };

  const exportCsv = () => {
    const header = ['Student', 'Admission no', 'Plan', 'Total', 'Paid', 'Due', 'Status'];
    const data = filteredRows.map((row) => [
      valueOf(row, ['studentName', 'name']),
      valueOf(row, ['admissionNumber', 'admissionNo']),
      valueOf(row, ['feePlan', 'planName']),
      paiseToRupeeInput(money(row, ['totalFee', 'totalAnnualFeePaise'])),
      paiseToRupeeInput(money(row, ['paidPaise', 'paid'])),
      paiseToRupeeInput(money(row, ['dueAmountPaise', 'dueAmount', 'due'])),
      rowStatus(row),
    ]);
    const csv = [header, ...data].map((line) => line.map((cell) => `"${String(cell ?? '').replace(/"/g, '""')}"`).join(',')).join('\n');
    const link = document.createElement('a');
    link.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
    link.download = 'fee-ledger.csv';
    link.click();
    URL.revokeObjectURL(link.href);
  };

  const collectionRate = summary.target ? Math.min(100, Math.round(summary.collected / summary.target * 100)) : 0;
  const totalInstallmentShare = installments.reduce((total, row) => total + Number(row.sharePercent || 0), 0);

  return (
    <ModuleShell
      title="Fee management"
      subtitle={`Collections and fee configuration for ${workspace?.school?.name || 'this school'}`}
      actions={
        <select aria-label="Academic year" value={yearId} onChange={(event) => void changeYear(event.target.value)}>
          {years.map((year) => <option key={year.id} value={year.id}>{year.label || year.name}{year.active ? ' (current)' : ''}</option>)}
        </select>
      }
    >
      <div className="erp-module">
        <div className="erp-tabs" role="tablist" aria-label="Fee module">
          <button className={view === 'overview' ? 'active' : ''} onClick={() => setView('overview')}><ReceiptIndianRupee size={16} /> Collections</button>
          <button className={view === 'configuration' ? 'active' : ''} onClick={() => setView('configuration')}><Settings2 size={16} /> Configuration</button>
        </div>

        {notice && <div className="erp-notice good"><Check size={16} /><span>{notice}</span><button aria-label="Dismiss" onClick={() => setNotice('')}><X size={15} /></button></div>}
        {error && <div className="erp-notice danger"><AlertTriangle size={16} /><span>{error}</span><button aria-label="Dismiss" onClick={() => setError('')}><X size={15} /></button></div>}
        {!isActiveYear && <div className="erp-notice neutral"><ShieldCheck size={16} /><span>Historical academic years are read-only. Switch to the current year to collect, assign, or change fee rules.</span></div>}

        {view === 'overview' && (
          <>
            <div className="erp-metrics">
              <div className="erp-metric"><span>Collection rate</span><strong>{collectionRate}%</strong><small>of annual target</small></div>
              <div className="erp-metric"><span>Collected</span><strong>Rs {formatPaise(summary.collected)}</strong><small>posted payments</small></div>
              <div className="erp-metric"><span>Receivable</span><strong>Rs {formatPaise(Math.max(0, summary.target - summary.collected))}</strong><small>remaining balance</small></div>
              <div className="erp-metric alert"><span>Overdue accounts</span><strong>{summary.overdue}</strong><small>need follow-up</small></div>
            </div>

            <div className="erp-toolbar">
              <div className="erp-filter-group">
                <select aria-label="Class" value={classId} onChange={(event) => void changeClass(event.target.value)}>
                  <option value="">Select class</option>
                  {classes.map((row) => <option key={row.id} value={row.id}>{row.name || row.label}</option>)}
                </select>
                <select aria-label="Section" disabled={!classId} value={sectionId} onChange={(event) => void changeSection(event.target.value)}>
                  <option value="">Select section</option>
                  {sections.map((row) => <option key={row.id} value={row.id}>{row.name || row.label}</option>)}
                </select>
                <label className="erp-search"><Search size={15} /><input aria-label="Search ledger" placeholder="Search student or admission no." value={search} onChange={(event) => setSearch(event.target.value)} /></label>
                <select aria-label="Payment status" value={status} onChange={(event) => setStatus(event.target.value)}>
                  {statuses.map((row) => <option key={row} value={row}>{row === 'ALL' ? 'All statuses' : row.toLowerCase()}</option>)}
                </select>
              </div>
              <div className="erp-action-group">
                <button className="erp-btn secondary" disabled={!filteredRows.length} onClick={exportCsv}><Download size={16} /> Export</button>
                <button className="erp-btn secondary" disabled={!isActiveYear || !canRemind || !overdueRows.length || saving === 'reminders'} onClick={() => void sendReminders()}><Send size={16} /> Remind overdue</button>
              </div>
            </div>

            <div className="erp-table-frame">
              <table className="erp-table">
                <thead><tr><th>Student</th><th>Fee plan</th><th>Total</th><th>Paid</th><th>Balance</th><th>Status</th><th aria-label="Actions" /></tr></thead>
                <tbody>
                  {!loading && filteredRows.map((row, index) => {
                    const nextStatus = rowStatus(row);
                    const name = String(valueOf(row, ['studentName', 'name'], 'Student'));
                    const due = money(row, ['dueAmountPaise', 'dueAmount', 'due']);
                    return (
                      <tr key={String(valueOf(row, ['studentId', 'assignmentId'], index))}>
                        <td><strong>{name}</strong><small>{valueOf(row, ['admissionNumber', 'admissionNo'], 'No admission number')}</small></td>
                        <td>{valueOf(row, ['feePlan', 'planName'], 'Not assigned')}<small>{valueOf(row, ['paymentSchedule', 'schedule'], '')}</small></td>
                        <td>Rs {formatPaise(money(row, ['totalFee', 'totalAnnualFeePaise', 'netPayable']))}</td>
                        <td>Rs {formatPaise(money(row, ['paidPaise', 'paid']))}</td>
                        <td><strong>Rs {formatPaise(due)}</strong>{money(row, ['lateFeePaise', 'lateFee']) > 0 && <small>includes Rs {formatPaise(money(row, ['lateFeePaise', 'lateFee']))} late fee</small>}</td>
                        <td><span className={`erp-status ${statusTone(nextStatus)}`}>{nextStatus.toLowerCase()}</span></td>
                        <td><button className="erp-icon-btn" title="Record payment" aria-label={`Record payment for ${name}`} disabled={!isActiveYear || !canCollect || due <= 0} onClick={() => { setPaymentRow(row); setPayment({ amount: paiseToRupeeInput(due), mode: 'UPI', notes: '' }); }}><CircleDollarSign size={17} /></button></td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
              {!loading && !filteredRows.length && <div className="erp-empty">{sectionId ? 'No student fee records match these filters.' : 'Select a class and section to open the student ledger.'}</div>}
              {loading && <div className="erp-empty">Loading fee records...</div>}
            </div>
          </>
        )}

        {view === 'configuration' && (
          <>
            <div className="erp-config-head">
              <div className="erp-tabs compact" role="tablist" aria-label="Fee configuration">
                <button className={configView === 'plans' ? 'active' : ''} onClick={() => setConfigView('plans')}>Plans</button>
                <button className={configView === 'concessions' ? 'active' : ''} onClick={() => setConfigView('concessions')}>Concessions</button>
                <button className={configView === 'assignments' ? 'active' : ''} onClick={() => setConfigView('assignments')}>Assignments</button>
              </div>
              {configView === 'plans' && <button className="erp-btn primary" disabled={!isActiveYear || !canManage} onClick={() => setShowPlanForm(true)}><Plus size={16} /> New fee plan</button>}
            </div>

            <div className={`erp-health ${health?.ready ? 'ready' : ''}`}>
              <ShieldCheck size={19} />
              <div><strong>{health?.ready ? 'Fee configuration is publish-ready' : `${health?.blockingIssues ?? 0} configuration checks need attention`}</strong><span>{health?.publishedPlans ?? 0} published, {health?.draftPlans ?? 0} draft, {health?.missingFeeHeads ?? 0} missing fee heads</span></div>
            </div>

            {configView === 'plans' && (
              <div className="erp-fee-layout">
                <aside className="erp-side-list">
                  <div className="erp-side-title">Fee plans <span>{structure.bands.length}</span></div>
                  {structure.bands.map((band) => (
                    <button key={band.id} className={selectedBand?.id === band.id ? 'active' : ''} onClick={() => setSelectedBandId(band.id)}>
                      <span><strong>{band.name}</strong><small>Classes {band.classFrom}-{band.classTo}</small></span>
                      <span className={`erp-status ${statusTone(band.status)}`}>{band.status.toLowerCase()}</span>
                    </button>
                  ))}
                  {!structure.bands.length && <div className="erp-empty compact">No fee plans for this year.</div>}
                </aside>

                <section className="erp-config-content">
                  {selectedBand ? (
                    <>
                      <header className="erp-section-head">
                        <div><span className="erp-eyebrow">Revision {selectedBand.revision}</span><h2>{selectedBand.name}</h2><p>Classes {selectedBand.classFrom}-{selectedBand.classTo} - Rs {formatPaise(selectedBand.annualTotal)} annually</p></div>
                        <span className={`erp-status ${statusTone(selectedBand.status)}`}>{selectedBand.status.toLowerCase()}</span>
                      </header>

                      <div className="erp-policy-strip">
                        <span><strong>{selectedBand.gracePeriodDays} days</strong> grace period</span>
                        <span><strong>{selectedBand.lateFeeType === 'NONE' ? 'No late fee' : `Rs ${formatPaise(selectedBand.lateFeeAmount)}`}</strong> {selectedBand.lateFeeType.toLowerCase()}</span>
                        <span><strong>{selectedBand.assignmentCount}</strong> assigned students</span>
                      </div>

                      {selectedBand.status === 'DRAFT' && canManage && isActiveYear && (
                        <div className="erp-subsection">
                          <div className="erp-subsection-head"><div><h3>Plan rules</h3><p>Edit the draft range, discount, grace period, and late-fee behavior.</p></div></div>
                          <div className="erp-form-grid policy-editor">
                            <label className="wide">Plan name<input value={bandEdit.name} onChange={(event) => setBandEdit({ ...bandEdit, name: event.target.value })} /></label>
                            <label>Class from<input type="number" min="1" max="12" value={bandEdit.classFrom} onChange={(event) => setBandEdit({ ...bandEdit, classFrom: event.target.value })} /></label>
                            <label>Class to<input type="number" min="1" max="12" value={bandEdit.classTo} onChange={(event) => setBandEdit({ ...bandEdit, classTo: event.target.value })} /></label>
                            <label>Default discount %<input type="number" min="0" max="100" value={bandEdit.discount} onChange={(event) => setBandEdit({ ...bandEdit, discount: event.target.value })} /></label>
                            <label>Grace period days<input type="number" min="0" value={bandEdit.gracePeriodDays} onChange={(event) => setBandEdit({ ...bandEdit, gracePeriodDays: event.target.value })} /></label>
                            <label>Late fee policy<select value={bandEdit.lateFeeType} onChange={(event) => setBandEdit({ ...bandEdit, lateFeeType: event.target.value })}><option value="NONE">No late fee</option><option value="FIXED">Fixed per overdue installment</option><option value="DAILY">Recurring by interval</option></select></label>
                            <label>Late fee amount (Rs)<input type="number" min="0" disabled={bandEdit.lateFeeType === 'NONE'} value={bandEdit.lateFeeAmount} onChange={(event) => setBandEdit({ ...bandEdit, lateFeeAmount: event.target.value })} /></label>
                            <label>Recurring interval days<input type="number" min="1" disabled={bandEdit.lateFeeType !== 'DAILY'} value={bandEdit.lateFeeIntervalDays} onChange={(event) => setBandEdit({ ...bandEdit, lateFeeIntervalDays: event.target.value })} /></label>
                            <fieldset className="wide"><legend>Allowed payment schedules</legend><div className="erp-check-grid">{schedules.map((schedule) => <label className="erp-check" key={schedule}><input type="checkbox" checked={bandEdit.schedules.includes(schedule)} onChange={() => setBandEdit((current) => ({ ...current, schedules: current.schedules.includes(schedule) ? current.schedules.filter((row) => row !== schedule) : [...current.schedules, schedule] }))} /> {schedule}</label>)}</div></fieldset>
                          </div>
                          <div className="erp-action-row end"><button className="erp-btn secondary" disabled={!bandEdit.name || !bandEdit.schedules.length || saving === 'plan-settings'} onClick={() => void savePlanSettings()}><Check size={16} /> Save rules</button></div>
                        </div>
                      )}

                      <div className="erp-subsection">
                        <div className="erp-subsection-head"><div><h3>Fee heads</h3><p>Components roll up to the annual plan total.</p></div></div>
                        <div className="erp-table-frame flush">
                          <table className="erp-table compact">
                            <thead><tr><th>Fee head</th><th>Frequency</th><th>Type</th><th>Amount</th><th aria-label="Actions" /></tr></thead>
                            <tbody>{selectedBand.items.map((item) => {
                              const isEditing = editingItem?.id === item.id;
                              return <tr key={item.id}>
                                <td>{isEditing ? <input aria-label="Fee head name" value={editingItem.name} onChange={(event) => setEditingItem({ ...editingItem, name: event.target.value })} /> : <strong>{item.name}</strong>}</td>
                                <td>{isEditing ? <select aria-label="Fee head frequency" value={editingItem.frequency} onChange={(event) => setEditingItem({ ...editingItem, frequency: event.target.value })}>{schedules.map((row) => <option key={row}>{row}</option>)}</select> : item.frequency}</td>
                                <td>{isEditing ? <label className="erp-check"><input type="checkbox" checked={editingItem.optional} onChange={(event) => setEditingItem({ ...editingItem, optional: event.target.checked })} /> Optional</label> : item.optional ? 'Optional' : 'Required'}</td>
                                <td>{isEditing ? <input aria-label="Fee head amount" type="number" min="0" value={editingItem.amount} onChange={(event) => setEditingItem({ ...editingItem, amount: event.target.value })} /> : `Rs ${formatPaise(item.amount)}`}</td>
                                <td>{selectedBand.status === 'DRAFT' && canManage && isActiveYear && <div className="erp-action-group item-actions">{isEditing ? <><button className="erp-icon-btn" title="Save fee head" aria-label="Save fee head" disabled={saving === `item-${item.id}`} onClick={() => void saveItemEdit()}><Check size={16} /></button><button className="erp-icon-btn" title="Cancel" aria-label="Cancel editing" onClick={() => setEditingItem(null)}><X size={16} /></button></> : <><button className="erp-icon-btn" title="Edit fee head" aria-label={`Edit ${item.name}`} onClick={() => setEditingItem({ id: item.id, name: item.name, frequency: item.frequency, amount: paiseToRupeeInput(item.amount), optional: item.optional })}><Pencil size={15} /></button><button className="erp-icon-btn danger" title="Remove fee head" aria-label={`Remove ${item.name}`} disabled={saving === `item-${item.id}`} onClick={() => void removeItem(item.id)}><Trash2 size={15} /></button></>}</div>}</td>
                              </tr>;
                            })}</tbody>
                          </table>
                          {!selectedBand.items.length && <div className="erp-empty compact">Add at least one fee head before publication.</div>}
                        </div>
                        {selectedBand.status === 'DRAFT' && canManage && isActiveYear && (
                          <div className="erp-inline-form fee-head">
                            <input aria-label="Fee head name" placeholder="Fee head name" value={itemForm.name} onChange={(event) => setItemForm({ ...itemForm, name: event.target.value })} />
                            <select aria-label="Frequency" value={itemForm.frequency} onChange={(event) => setItemForm({ ...itemForm, frequency: event.target.value })}>{schedules.map((row) => <option key={row}>{row}</option>)}</select>
                            <input aria-label="Amount in rupees" type="number" min="0" placeholder="Amount (Rs)" value={itemForm.amount} onChange={(event) => setItemForm({ ...itemForm, amount: event.target.value })} />
                            <label className="erp-check"><input type="checkbox" checked={itemForm.optional} onChange={(event) => setItemForm({ ...itemForm, optional: event.target.checked })} /> Optional</label>
                            <button className="erp-btn secondary" disabled={!itemForm.name || !itemForm.amount || saving === 'item'} onClick={() => void addItem()}><Plus size={16} /> Add</button>
                          </div>
                        )}
                      </div>

                      <div className="erp-subsection">
                        <div className="erp-subsection-head">
                          <div><h3>Installment schedule</h3><p>Due dates and shares must total 100%.</p></div>
                          <span className={`erp-status ${Math.abs(totalInstallmentShare - 100) < 0.01 ? 'good' : 'danger'}`}>{totalInstallmentShare}%</span>
                        </div>
                        <div className="erp-installments">
                          {installments.map((installment, index) => (
                            <div key={`${installment.label}-${index}`} className="erp-installment-row">
                              <input aria-label={`Installment ${index + 1} label`} disabled={selectedBand.status !== 'DRAFT'} value={installment.label} onChange={(event) => setInstallments((current) => current.map((row, rowIndex) => rowIndex === index ? { ...row, label: event.target.value } : row))} />
                              <input aria-label={`${installment.label} due date`} type="date" disabled={selectedBand.status !== 'DRAFT'} value={installment.dueDate} onChange={(event) => setInstallments((current) => current.map((row, rowIndex) => rowIndex === index ? { ...row, dueDate: event.target.value } : row))} />
                              <label><input aria-label={`${installment.label} percentage`} type="number" min="1" max="100" disabled={selectedBand.status !== 'DRAFT'} value={installment.sharePercent} onChange={(event) => setInstallments((current) => current.map((row, rowIndex) => rowIndex === index ? { ...row, sharePercent: Number(event.target.value) } : row))} /><span>%</span></label>
                              {selectedBand.status === 'DRAFT' && <button className="erp-icon-btn" aria-label={`Remove ${installment.label}`} onClick={() => setInstallments((current) => current.filter((_, rowIndex) => rowIndex !== index))}><X size={16} /></button>}
                            </div>
                          ))}
                        </div>
                        {selectedBand.status === 'DRAFT' && canManage && isActiveYear && (
                          <div className="erp-action-row">
                            <button className="erp-btn secondary" onClick={() => setInstallments((current) => [...current, { label: `Installment ${current.length + 1}`, dueDate: isoDate(current.length * 2), sharePercent: 0 }])}><Plus size={16} /> Add installment</button>
                            <button className="erp-btn secondary" disabled={saving === 'installments'} onClick={() => void saveInstallmentPlan()}><Check size={16} /> Save schedule</button>
                          </div>
                        )}
                      </div>

                      {selectedBand.status === 'DRAFT' && canManage && isActiveYear && (
                        <div className="erp-publish">
                          <div><strong>Publish this revision</strong><span>Publishing freezes this plan for reliable student assignments.</span></div>
                          <button className="erp-btn primary" disabled={saving === 'publish' || !selectedBand.items.length || Math.abs(totalInstallmentShare - 100) > 0.01} onClick={() => void publishPlan()}><ShieldCheck size={16} /> Publish plan</button>
                        </div>
                      )}
                      {selectedBand.status === 'PUBLISHED' && canManage && isActiveYear && (
                        <div className="erp-publish">
                          <div><strong>Published revision</strong><span>Create a draft copy to change this plan without altering existing assignments.</span></div>
                          <button className="erp-btn secondary" disabled={saving === 'revision'} onClick={() => void startPlanRevision()}><FilePlus2 size={16} /> Create revision</button>
                        </div>
                      )}
                    </>
                  ) : <div className="erp-empty">Create a fee plan to begin configuration.</div>}
                </section>
              </div>
            )}

            {configView === 'concessions' && (
              <div className="erp-split">
                <section className="erp-config-content">
                  <div className="erp-section-head"><div><span className="erp-eyebrow">Policy library</span><h2>Concession rules</h2><p>Reusable school-level rules applied during student assignment.</p></div></div>
                  <div className="erp-table-frame flush">
                    <table className="erp-table"><thead><tr><th>Rule</th><th>Type</th><th>Discount</th><th>Priority</th><th>Status</th></tr></thead>
                      <tbody>{rules.map((rule) => <tr key={rule.id}><td><strong>{rule.name}</strong></td><td>{rule.ruleType.replace(/_/g, ' ').toLowerCase()}</td><td>{rule.percentage}%</td><td>{rule.priority}</td><td><span className={`erp-status ${rule.active ? 'good' : 'neutral'}`}>{rule.active ? 'active' : 'inactive'}</span></td></tr>)}</tbody>
                    </table>
                    {!rules.length && <div className="erp-empty compact">No concession rules configured.</div>}
                  </div>
                </section>
                <aside className="erp-form-panel">
                  <h3>New concession</h3>
                  <label>Name<input placeholder="e.g. Sibling concession" value={ruleForm.name} onChange={(event) => setRuleForm({ ...ruleForm, name: event.target.value })} /></label>
                  <label>Rule type<select value={ruleForm.ruleType} onChange={(event) => setRuleForm({ ...ruleForm, ruleType: event.target.value })}><option value="SIBLING">Sibling</option><option value="STAFF_WARD">Staff ward</option><option value="EARLY_PAYMENT">Early payment</option><option value="RTE">RTE</option><option value="MERIT">Merit</option><option value="MANUAL">Manual</option></select></label>
                  <label>Discount percentage<input type="number" min="0" max="100" value={ruleForm.percentage} onChange={(event) => setRuleForm({ ...ruleForm, percentage: event.target.value })} /></label>
                  <label>Evaluation priority<input type="number" min="1" value={ruleForm.priority} onChange={(event) => setRuleForm({ ...ruleForm, priority: event.target.value })} /></label>
                  <button className="erp-btn primary" disabled={!isActiveYear || !canManage || !ruleForm.name || !ruleForm.percentage || saving === 'rule'} onClick={() => void saveRule()}><FilePlus2 size={16} /> Save rule</button>
                </aside>
              </div>
            )}

            {configView === 'assignments' && (
              <div className="erp-split assignments">
                <section className="erp-config-content">
                  <div className="erp-section-head"><div><span className="erp-eyebrow">Student connection</span><h2>Assign a published plan</h2><p>The assignment feeds the student ledger, collection dashboard, receipts, and reminders.</p></div></div>
                  <div className="erp-form-grid">
                    <label>Class<select value={classId} onChange={(event) => void changeClass(event.target.value)}><option value="">Select class</option>{classes.map((row) => <option key={row.id} value={row.id}>{row.name || row.label}</option>)}</select></label>
                    <label>Section<select disabled={!classId} value={sectionId} onChange={(event) => void changeSection(event.target.value)}><option value="">Select section</option>{sections.map((row) => <option key={row.id} value={row.id}>{row.name || row.label}</option>)}</select></label>
                    <label>Student<select disabled={!sectionId} value={assignment.studentId} onChange={(event) => setAssignment({ ...assignment, studentId: event.target.value })}><option value="">Select student</option>{students.map((row) => <option key={row.id} value={row.id}>{row.name || row.fullName} - {row.admissionNumber || row.admissionNo || 'No admission no.'}</option>)}</select></label>
                    <label>Published fee plan<select value={assignment.bandId} onChange={(event) => { const band = publishedBands.find((row) => row.id === event.target.value); setAssignment({ ...assignment, bandId: event.target.value, schedule: band?.activeSchedules[0] || '', optionalItemIds: [] }); }}><option value="">Select plan</option>{publishedBands.map((band) => <option key={band.id} value={band.id}>{band.name} - Rs {formatPaise(band.annualTotal)}</option>)}</select></label>
                    <label>Payment schedule<select disabled={!assignment.bandId} value={assignment.schedule} onChange={(event) => setAssignment({ ...assignment, schedule: event.target.value })}><option value="">Select schedule</option>{publishedBands.find((row) => row.id === assignment.bandId)?.activeSchedules.map((row) => <option key={row}>{row}</option>)}</select></label>
                    <label>Concession rule<select value={assignment.discountRuleId} onChange={(event) => setAssignment({ ...assignment, discountRuleId: event.target.value })}><option value="">No automatic concession</option>{rules.filter((rule) => rule.active).map((rule) => <option key={rule.id} value={rule.id}>{rule.name} - {rule.percentage}%</option>)}</select></label>
                    <label>Manual discount %<input type="number" min="0" max="100" value={assignment.manualDiscount} onChange={(event) => setAssignment({ ...assignment, manualDiscount: event.target.value })} /></label>
                    <label>Surcharge %<input type="number" min="0" max="100" value={assignment.surcharge} onChange={(event) => setAssignment({ ...assignment, surcharge: event.target.value })} /></label>
                  </div>
                  {(publishedBands.find((row) => row.id === assignment.bandId)?.items.some((item) => item.optional)) && (
                    <fieldset className="erp-optional-fees">
                      <legend>Optional fee heads for this student</legend>
                      <div className="erp-check-grid">
                        {publishedBands.find((row) => row.id === assignment.bandId)?.items
                          .filter((item) => item.optional)
                          .map((item) => (
                            <label className="erp-check" key={item.id}>
                              <input
                                type="checkbox"
                                checked={assignment.optionalItemIds.includes(item.id)}
                                onChange={() => setAssignment((current) => ({
                                  ...current,
                                  optionalItemIds: current.optionalItemIds.includes(item.id)
                                    ? current.optionalItemIds.filter((id) => id !== item.id)
                                    : [...current.optionalItemIds, item.id],
                                }))}
                              />
                              {item.name} - Rs {formatPaise(item.amount)}
                            </label>
                          ))}
                      </div>
                    </fieldset>
                  )}
                  <div className="erp-action-row end"><button className="erp-btn primary" disabled={!isActiveYear || !canAssign || !assignment.studentId || !assignment.bandId || !assignment.schedule || saving === 'assignment'} onClick={() => void saveAssignment()}><Users size={16} /> Assign plan</button></div>
                </section>
                <aside className="erp-connection-map">
                  <h3>Connected workflow</h3>
                  {['Academic year and class', 'Published fee plan', 'Student assignment', 'Ledger and installments', 'Payments, receipts and reminders'].map((label, index) => <div key={label}><span>{index + 1}</span><p>{label}</p>{index < 4 && <ChevronRight size={15} />}</div>)}
                </aside>
              </div>
            )}
          </>
        )}
      </div>

      {showPlanForm && (
        <div className="erp-dialog-backdrop" onMouseDown={() => setShowPlanForm(false)}>
          <div className="erp-dialog" role="dialog" aria-modal="true" aria-labelledby="fee-plan-title" onMouseDown={(event) => event.stopPropagation()}>
            <header><div><span className="erp-eyebrow">Draft lifecycle</span><h2 id="fee-plan-title">Create fee plan</h2></div><button className="erp-icon-btn" aria-label="Close" onClick={() => setShowPlanForm(false)}><X size={18} /></button></header>
            <div className="erp-form-grid">
              <label className="wide">Plan name<input autoFocus placeholder="e.g. Primary fee plan 2026-27" value={planForm.name} onChange={(event) => setPlanForm({ ...planForm, name: event.target.value })} /></label>
              <label>Class from<input type="number" min="1" max="12" value={planForm.classFrom} onChange={(event) => setPlanForm({ ...planForm, classFrom: event.target.value })} /></label>
              <label>Class to<input type="number" min="1" max="12" value={planForm.classTo} onChange={(event) => setPlanForm({ ...planForm, classTo: event.target.value })} /></label>
              <label>Default discount %<input type="number" min="0" max="100" value={planForm.discount} onChange={(event) => setPlanForm({ ...planForm, discount: event.target.value })} /></label>
              <label>Grace period days<input type="number" min="0" value={planForm.gracePeriodDays} onChange={(event) => setPlanForm({ ...planForm, gracePeriodDays: event.target.value })} /></label>
              <label>Late fee policy<select value={planForm.lateFeeType} onChange={(event) => setPlanForm({ ...planForm, lateFeeType: event.target.value })}><option value="NONE">No late fee</option><option value="FIXED">Fixed</option><option value="DAILY">Daily</option></select></label>
              <label>Late fee amount (Rs)<input type="number" min="0" disabled={planForm.lateFeeType === 'NONE'} value={planForm.lateFeeAmount} onChange={(event) => setPlanForm({ ...planForm, lateFeeAmount: event.target.value })} /></label>
              <label>Late fee interval days<input type="number" min="0" disabled={planForm.lateFeeType === 'NONE'} value={planForm.lateFeeIntervalDays} onChange={(event) => setPlanForm({ ...planForm, lateFeeIntervalDays: event.target.value })} /></label>
              <fieldset className="wide"><legend>Allowed payment schedules</legend><div className="erp-check-grid">{schedules.map((schedule) => <label className="erp-check" key={schedule}><input type="checkbox" checked={planForm.schedules.includes(schedule)} onChange={() => setPlanForm((current) => ({ ...current, schedules: current.schedules.includes(schedule) ? current.schedules.filter((row) => row !== schedule) : [...current.schedules, schedule] }))} /> {schedule}</label>)}</div></fieldset>
            </div>
            <footer><button className="erp-btn secondary" onClick={() => setShowPlanForm(false)}>Cancel</button><button className="erp-btn primary" disabled={!planForm.name || !planForm.schedules.length || saving === 'plan'} onClick={() => void createPlan()}><Plus size={16} /> Create draft</button></footer>
          </div>
        </div>
      )}

      {paymentRow && (
        <div className="erp-dialog-backdrop" onMouseDown={() => setPaymentRow(null)}>
          <div className="erp-dialog narrow" role="dialog" aria-modal="true" aria-labelledby="payment-title" onMouseDown={(event) => event.stopPropagation()}>
            <header><div><span className="erp-eyebrow">Student ledger</span><h2 id="payment-title">Record payment</h2></div><button className="erp-icon-btn" aria-label="Close" onClick={() => setPaymentRow(null)}><X size={18} /></button></header>
            <div className="erp-payment-student"><ReceiptIndianRupee size={20} /><div><strong>{valueOf(paymentRow, ['studentName', 'name'])}</strong><span>Balance Rs {formatPaise(money(paymentRow, ['dueAmountPaise', 'dueAmount', 'due']))}</span></div></div>
            <div className="erp-form-grid single">
              <label>Amount (Rs)<input autoFocus type="number" min="0.01" step="0.01" value={payment.amount} onChange={(event) => setPayment({ ...payment, amount: event.target.value })} /></label>
              <label>Payment mode<select value={payment.mode} onChange={(event) => setPayment({ ...payment, mode: event.target.value })}><option>UPI</option><option>Cash</option><option>Bank transfer</option><option>Cheque</option></select></label>
              <label>Notes<textarea rows={3} value={payment.notes} onChange={(event) => setPayment({ ...payment, notes: event.target.value })} /></label>
            </div>
            <footer><button className="erp-btn secondary" onClick={() => setPaymentRow(null)}>Cancel</button><button className="erp-btn primary" disabled={!payment.amount || saving === 'payment'} onClick={() => void submitPayment()}><CircleDollarSign size={16} /> Record payment</button></footer>
          </div>
        </div>
      )}
    </ModuleShell>
  );
}
