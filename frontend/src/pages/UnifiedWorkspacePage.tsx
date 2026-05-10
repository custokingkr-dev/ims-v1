import { DragEvent, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../services/api';
import { useAuth } from '../contexts/AuthContext';
import {
  type PanelKey, type WorkspaceData,
  ADMIN_NAV_SECTIONS, OPERATIONS_NAV_SECTIONS, SUPERADMIN_NAV_SECTIONS, ZONE_ADMIN_NAV_SECTIONS, PANEL_TITLES,
  SA_NEW_ORDER_CATEGORIES, CATALOG_TILES,
} from './workspace/config';
import {
  formatMoney, formatLakh, todayIso, formatAddress, initials,
  attendanceNumber, formatLongDate, splitCsvLine, defaultPlanName,
  computeSaOrderValue, toPaise, prettyOrderStatus, EVENT_RATES,
} from './workspace/utils';
import {
  ModuleShell, Field, Info, Stat, OrderSummaryPanel,
  thStyle, tdStyle, inlineInputStyle,
} from './workspace/ui';
import { HomePanel } from './workspace/panels/HomePanel';
import { StudentsPanel } from './workspace/panels/StudentsPanel';
import { FeesPanel } from './workspace/panels/FeesPanel';
import { FeeStructurePanel } from './workspace/panels/FeeStructurePanel';
import { AttendancePanel } from './workspace/panels/AttendancePanel';
import { TimetablePanel } from './workspace/panels/TimetablePanel';
import { StaffPanel } from './workspace/panels/StaffPanel';
import { PlanningPanel } from './workspace/panels/PlanningPanel';
import { CatalogPanel } from './workspace/panels/CatalogPanel';
import { AddStudentPanel } from './workspace/panels/AddStudentPanel';
import { BulkImportPanel } from './workspace/panels/BulkImportPanel';
import { FirefightingDashboardPanel } from './workspace/panels/FirefightingDashboardPanel';
import { FirefightingNewPanel } from './workspace/panels/FirefightingNewPanel';
import { FirefightingApprovalsPanel } from './workspace/panels/FirefightingApprovalsPanel';
import { FirefightingOrdersPanel } from './workspace/panels/FirefightingOrdersPanel';
import { SaErpPanel } from './workspace/panels/SaErpPanel';
import { SaRevenuePanel } from './workspace/panels/SaRevenuePanel';
import { SaCatalogPanel } from './workspace/panels/SaCatalogPanel';

export default function UnifiedWorkspacePage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  // ── Core ───────────────────────────────────────────────────────────────────
  const [workspace, setWorkspace] = useState<WorkspaceData | null>(null);
  const [workspaceError, setWorkspaceError] = useState('');
  const [panel, setPanel] = useState<PanelKey>(user?.role === 'SUPERADMIN' ? 'orders' : user?.role === 'ZONE_ADMIN' ? 'za-overview' : user?.role === 'OPERATIONS' ? 'home' : 'catalog');
  const [saving, setSaving] = useState<string>('');

  // ── Students ───────────────────────────────────────────────────────────────
  const [studentForm, setStudentForm] = useState({ admissionNumber: '', boardRegistrationNumber: '', fullName: '', dateOfBirth: '', gender: 'Male', gradeLevel: 'Class 9', sectionName: 'A', academicYear: '2025–26', admissionDate: '', houseNumber: '', street: '', locality: '', city: 'Hyderabad', state: 'Telangana', pinCode: '', fatherName: '', fatherContactNumber: '', paymentSchedule: 'Monthly', manualDiscountOverride: '0' });

  // ── Fees ───────────────────────────────────────────────────────────────────
  const [paymentForm, setPaymentForm] = useState({ studentId: '', studentName: '', amount: '', paymentMode: 'UPI', notes: '' });
  const [feeAssignForm, setFeeAssignForm] = useState({ studentId: '', bandId: '', paymentSchedule: '', bandDiscount: '0', manualDiscount: '0', surcharge: '2' });
  const [feeFilters, setFeeFilters] = useState({ className: '', sectionName: '' });
  const [feeClasses, setFeeClasses] = useState<any[]>([]);
  const [assignOptions, setAssignOptions] = useState<any>({ sections: [], students: [] });
  const [assignSelection, setAssignSelection] = useState<any>({ classId: '', sectionId: '', studentId: '' });
  const [paymentOptions, setPaymentOptions] = useState<any>({ sections: [], students: [] });
  const [paymentSelection, setPaymentSelection] = useState<any>({ classId: '', sectionId: '', studentId: '' });
  const [paymentDuePreview, setPaymentDuePreview] = useState<any | null>(null);
  const [paymentError, setPaymentError] = useState('');
  const [reportOptions, setReportOptions] = useState<any>({ sections: [] });
  const [reportRows, setReportRows] = useState<any[]>([]);
  const [overdueRows, setOverdueRows] = useState<any[]>([]);
  const [reportLoading, setReportLoading] = useState(false);
  const [selectedReportStudentId, setSelectedReportStudentId] = useState<string | null>(null);
  const [paymentSuccess, setPaymentSuccess] = useState('');
  const [feeLoadError, setFeeLoadError] = useState('');
  const [reminderSaving, setReminderSaving] = useState(false);
  const [reminderNotice, setReminderNotice] = useState('');
  const [studentFilters, setStudentFilters] = useState({ className: 'All', sectionName: 'All', feeStatus: 'All' });
  const [studentsView, setStudentsView] = useState<any>({ items: [], filteredCount: 0, filteredSections: 0, filters: { classes: [], sections: [], feeStatuses: ['Paid', 'Overdue', 'Pending', 'Partial'] } });
  const [studentsLoading, setStudentsLoading] = useState(false);
  const [studentDetail, setStudentDetail] = useState<any | null>(null);
  const [studentModalOpen, setStudentModalOpen] = useState(false);
  const [studentModalLoading, setStudentModalLoading] = useState(false);
  const [editingStudentId, setEditingStudentId] = useState<number | null>(null);

  // ── Fee structure ──────────────────────────────────────────────────────────
  const [feeItemForm, setFeeItemForm] = useState({ bandId: '', itemName: '', frequency: 'Annual', amount: '' });
  const [feeStructureData, setFeeStructureData] = useState<any>({ academicYear: '2025–26', academicYearId: 'ay_2024_25', bands: [] });
  const [feeStructureLoading, setFeeStructureLoading] = useState(false);
  const [showFeeItemForm, setShowFeeItemForm] = useState(false);
  const [showBandForm, setShowBandForm] = useState(false);
  const [bandForm, setBandForm] = useState<any>({ name: '', classFrom: '1', classTo: '5', discount: '0', schedules: ['Annual'] });
  const [editingBandId, setEditingBandId] = useState('');
  const [confirmDeleteBandId, setConfirmDeleteBandId] = useState('');
  const [expandedBandIds, setExpandedBandIds] = useState<string[]>([]);
  const [feeAssignHint, setFeeAssignHint] = useState('');
  const [feeAssignError, setFeeAssignError] = useState('');
  const [feeStructureError, setFeeStructureError] = useState('');
  const [feeStructureToast, setFeeStructureToast] = useState('');
  const [editingFeeItem, setEditingFeeItem] = useState<any | null>(null);
  const [confirmRemoveFeeItemId, setConfirmRemoveFeeItemId] = useState('');
  const discountTimers = useRef<Record<string, number>>({});

  // ── Attendance ─────────────────────────────────────────────────────────────
  const [attendanceSummary, setAttendanceSummary] = useState<any>({ dateLabel: '', overallPercent: 0, sections: [], allSubmitted: false, nonWorkingDay: false });
  const [attendanceFilters, setAttendanceFilters] = useState({ date: todayIso(), classId: '', sectionId: '' });
  const [attendanceClassOptions, setAttendanceClassOptions] = useState<any[]>([]);
  const [attendanceSectionOptions, setAttendanceSectionOptions] = useState<any[]>([]);
  const [attendanceSectionInfo, setAttendanceSectionInfo] = useState<any | null>(null);
  const [attendancePresentCount, setAttendancePresentCount] = useState('');
  const [attendanceSaveError, setAttendanceSaveError] = useState('');
  const [attendanceToast, setAttendanceToast] = useState('');

  // ── Timetable / staff / simple forms ──────────────────────────────────────
  const [timetableForm, setTimetableForm] = useState({ day: 'Monday', period: 'P6', classSection: '9-B', subject: '', teacher: '' });
  const [staffForm, setStaffForm] = useState({ name: '', designation: '', department: '', monthlySalary: '42000', payrollStatus: 'Pending' });
  const [orderForm, setOrderForm] = useState({ category: 'Uniforms', title: 'Class order', items: '100 units', amount: '100000', status: 'In transit' });
  const [planForm, setPlanForm] = useState({ term: 'Term 1', category: 'Stationery', quantity: '200 units', amount: '38000', status: 'Planned' });

  // ── Firefighting ───────────────────────────────────────────────────────────
  const emptyQuote = () => ({ vendorName: '', amount: '', deliveryTimeline: '', notes: '', documentUrl: '' });
  const ffFormInit = () => ({
    title: '', category: 'Furniture & fixtures', estimatedBudget: '', urgency: 'MEDIUM',
    requiredByDate: '', summary: '', quotations: [emptyQuote(), emptyQuote(), emptyQuote()],
  });
  const [ffForm, setFfForm] = useState(ffFormInit());
  const [ffStep, setFfStep] = useState<1 | 2 | 3>(1);
  const [ffSaving, setFfSaving] = useState(false);
  const [ffError, setFfError] = useState('');
  const [ffApprovalNotes, setFfApprovalNotes] = useState<Record<string, string>>({});
  const [ffApprovalDetails, setFfApprovalDetails] = useState<any[]>([]);
  const [ffApprovalLoading, setFfApprovalLoading] = useState(false);
  const [ffDraftSaving, setFfDraftSaving] = useState(false);
  const [ffEditingCode, setFfEditingCode] = useState<string | null>(null);
  const [ffExistingQuotes, setFfExistingQuotes] = useState<any[]>([]);
  const [saFfRequests, setSaFfRequests] = useState<any[]>([]);
  const [saFfLoading, setSaFfLoading] = useState(false);
  const [ffTrackOpen, setFfTrackOpen] = useState(false);
  const [ffTrackRequest, setFfTrackRequest] = useState<any>(null);
  const [ffTimeline, setFfTimeline] = useState<any[]>([]);
  const [ffTimelineLoading, setFfTimelineLoading] = useState(false);
  const setFfQuote = (idx: number, field: string, value: string) =>
    setFfForm(f => { const qs = f.quotations.map((q, i) => i === idx ? { ...q, [field]: value } : q); return { ...f, quotations: qs }; });

  // ── Photo upload (add-student) ─────────────────────────────────────────────
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [photoFile, setPhotoFile] = useState<File | null>(null);
  const [photoPreviewUrl, setPhotoPreviewUrl] = useState('');
  const [photoError, setPhotoError] = useState('');
  const [photoFeedback, setPhotoFeedback] = useState<{ type: 'success' | 'error'; message: string } | null>(null);
  const [photoDragActive, setPhotoDragActive] = useState(false);
  const [photoZoom, setPhotoZoom] = useState(1);
  const [photoOffsetX, setPhotoOffsetX] = useState(0);
  const [photoOffsetY, setPhotoOffsetY] = useState(0);

  // ── Bulk import ────────────────────────────────────────────────────────────
  const bulkImportInputRef = useRef<HTMLInputElement | null>(null);
  const [bulkImportDragActive, setBulkImportDragActive] = useState(false);
  const [bulkImportError, setBulkImportError] = useState('');
  const [bulkImportWarning, setBulkImportWarning] = useState('');
  const [bulkImportFileName, setBulkImportFileName] = useState('');
  const [bulkImportPreview, setBulkImportPreview] = useState<any | null>(null);
  const [bulkImportProgress, setBulkImportProgress] = useState<any | null>(null);
  const [bulkImportToast, setBulkImportToast] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  // ── Catalog (order forms per category) ────────────────────────────────────
  const [activeCat, setActiveCat] = useState<string | null>(null);
  const [uniformForm, setUniformForm] = useState({ academicYear: '2024–25', requiredByDate: '', classGroup: 'Class 6–8', logoOnUniform: 'Yes — school logo embroidered', specialInstructions: '', items: [{ name: 'Shirt (white)', sizeBreakdown: 'S:20 M:60 L:15 XL:5', qty: 100, unitPrice: 320 }, { name: 'Trousers / skirt', sizeBreakdown: 'S:20 M:60 L:15 XL:5', qty: 100, unitPrice: 480 }, { name: 'PE T-shirt', sizeBreakdown: 'S:30 M:50 L:20', qty: 100, unitPrice: 220 }] });
  const [notebookForm, setNotebookForm] = useState({ numStudents: 487, notebooksPerStudent: 6, requiredByDate: '', coverLogo: 'School logo — printed', delivery: 'Deliver to school', schoolNameOnSpine: 'Yes', items: [{ type: 'Ruled notebook', size: 'A4', pages: '120', qty: 1200, unitPrice: 45 }, { type: 'School diary', size: 'A5', pages: '200 pg', qty: 487, unitPrice: 80 }, { type: 'Graph notebook', size: 'A4', pages: '60 pg', qty: 300, unitPrice: 28 }] });
  const [stationeryForm, setStationeryForm] = useState({ packType: 'Per-student kit', numKits: 487, requiredByDate: '', items: [{ name: 'Ball pen (blue)', brand: 'Reynolds 045', perKit: 2, unitPrice: 6 }, { name: 'Pencil HB', brand: 'Natraj 621', perKit: 2, unitPrice: 5 }, { name: 'Eraser', brand: 'Apsara Non-dust', perKit: 1, unitPrice: 8 }, { name: 'Scale 30cm', brand: 'Camlin', perKit: 1, unitPrice: 12 }] });
  const [idCardForm, setIdCardForm] = useState({ studentCount: 487, staffCount: 68, spareCards: 20, lanyardIncluded: 'Yes — with hook', requiredByDate: '' });
  const [housekeepingForm, setHousekeepingForm] = useState({ contractType: 'Weekly — 3 days', startDate: '', duration: '3 months', staffRequired: 4 });
  const [eventsForm, setEventsForm] = useState({ eventName: '', eventDate: '', deliveryDeadline: '', items: [{ name: 'Trophy — gold', spec: '6 inch, resin base', qty: 20, unitPrice: 480 }, { name: 'Certificate', spec: 'A4, GSM 150, colour', qty: 200, unitPrice: 35 }, { name: 'Stage backdrop', spec: '12×8 ft, flex print', qty: 1, unitPrice: 4800 }] });
  const [healthForm, setHealthForm] = useState({ requiredByDate: '', deliveryTo: 'Main office', items: [{ name: 'First aid kit (standard)', qty: 6, unitPrice: 850 }, { name: 'Hand sanitizer 500ml', qty: 24, unitPrice: 180 }, { name: 'Disposable gloves (box 100)', qty: 10, unitPrice: 250 }] });
  const [catalogSaving, setCatalogSaving] = useState(false);
  const [catalogNotice, setCatalogNotice] = useState<{ type: 'success' | 'error' | 'draft'; msg: string } | null>(null);
  const [catalogSearch, setCatalogSearch] = useState('');

  // ── Orders ─────────────────────────────────────────────────────────────────
  const [liveOrders, setLiveOrders] = useState<any[] | null>(null);
  const [liveOrderStats, setLiveOrderStats] = useState<any | null>(null);
  const [ordersLoading, setOrdersLoading] = useState(false);
  const [pendingApprovalOrders, setPendingApprovalOrders] = useState<any[]>([]);
  const [pendingApprovalLoading, setPendingApprovalLoading] = useState(false);
  const [approvalActionSaving, setApprovalActionSaving] = useState<string>('');
  const [approvalNotice, setApprovalNotice] = useState<{ type: 'success' | 'error'; msg: string } | null>(null);
  const [rejectModalOrderId, setRejectModalOrderId] = useState<string | null>(null);
  const [rejectReason, setRejectReason] = useState('');

  // ── Superadmin — orders ────────────────────────────────────────────────────
  const [saAllOrders, setSaAllOrders] = useState<any[]>([]);
  const [saAllOrdersLoading, setSaAllOrdersLoading] = useState(false);
  const [saAllOrdersError, setSaAllOrdersError] = useState('');
  const [saOrderStats, setSaOrderStats] = useState<any>(null);
  const [saOrderFilter, setSaOrderFilter] = useState({ cat: '', status: '', search: '' });
  const [saDetailOpen, setSaDetailOpen] = useState(false);
  const [saDetailOrder, setSaDetailOrder] = useState<any | null>(null);
  const [saDetailLoading, setSaDetailLoading] = useState(false);
  const [saDetailError, setSaDetailError] = useState('');
  const [saNewStatus, setSaNewStatus] = useState('');
  const [saStatusSaving, setSaStatusSaving] = useState(false);

  // ── Superadmin — new order form ────────────────────────────────────────────
  const [saActiveCat, setSaActiveCat] = useState<string | null>(null);
  const [saNewOrderForm, setSaNewOrderForm] = useState<any>({});
  const [saNewOrderErrors, setSaNewOrderErrors] = useState<Record<string, string>>({});
  const [saNewOrderSaving, setSaNewOrderSaving] = useState(false);
  const [saNewOrderNotice, setSaNewOrderNotice] = useState('');
  const [saEventItems, setSaEventItems] = useState<Array<{ type: string; qty: string; notes: string }>>([]);
  const [saSchoolOptions, setSaSchoolOptions] = useState<any[]>([]);

  // ── Superadmin — invoices ──────────────────────────────────────────────────
  const [saInvoices, setSaInvoices] = useState<any[]>([]);
  const [saInvoicesLoading, setSaInvoicesLoading] = useState(false);
  const [saInvoicesError, setSaInvoicesError] = useState('');
  const [saInvStats, setSaInvStats] = useState<any>(null);
  const [saInvBadge, setSaInvBadge] = useState(0);
  const [saInvOpen, setSaInvOpen] = useState(false);
  const [saInvData, setSaInvData] = useState<any>({});
  const [saInvEditing, setSaInvEditing] = useState(false);
  const [saInvSaving, setSaInvSaving] = useState(false);
  const [saInvError, setSaInvError] = useState('');
  const [saInvExistingId, setSaInvExistingId] = useState<string | null>(null);

  // ── Superadmin — schools ───────────────────────────────────────────────────
  const [saSchools, setSaSchools] = useState<any[]>([]);
  const [saSchoolsLoading, setSaSchoolsLoading] = useState(false);
  const [saSchoolsError, setSaSchoolsError] = useState('');
  const [saOnboardOpen, setSaOnboardOpen] = useState(false);
  const [saOnboardForm, setSaOnboardForm] = useState({ name: '', shortCode: '', city: '', state: '', contactEmail: '', contactPhone: '', classCount: '12', sectionCount: '2' });
  const [saOnboardErrors, setSaOnboardErrors] = useState<Record<string, string>>({});
  const [saOnboardSaving, setSaOnboardSaving] = useState(false);


  // ── Derived / memoised ────────────────────────────────────────────────────
  const schoolScopedParams = user?.role !== 'SUPERADMIN' && user?.branchId
    ? { schoolId: user.branchId }
    : undefined;

  // ── API helpers — workspace ────────────────────────────────────────────────
  const refresh = async () => {
    try {
      setWorkspaceError('');
      const res = await api.get('/workspace', { params: schoolScopedParams });
      setWorkspace(res.data);
    } catch (error: any) {
      const message = error?.response?.data?.message || error?.message || 'Unable to load workspace.';
      if (['Invalid access token', 'Missing bearer token', 'Invalid refresh token'].includes(message)) {
        logout();
        navigate('/login', { replace: true });
        return;
      }
      setWorkspaceError(message);
    }
  };


  // ── API helpers — students ─────────────────────────────────────────────────
  const loadStudents = async (filters = studentFilters) => {
    try {
      setStudentsLoading(true);
      const params: Record<string, string> = {};
      if (filters.className !== 'All') params.class = filters.className;
      if (filters.sectionName !== 'All') params.section = filters.sectionName;
      if (filters.feeStatus !== 'All') params.feeStatus = filters.feeStatus;
      const res = await api.get('/students', { params: { ...params, ...(schoolScopedParams || {}) } });
      setStudentsView(res.data);
    } finally {
      setStudentsLoading(false);
    }
  };

  // ── API helpers — fees ────────────────────────────────────────────────────
  const loadFeeClasses = async () => {
    try {
      setFeeLoadError('');
      const res = await api.get('/classes', { params: schoolScopedParams });
      setFeeClasses(res.data || []);
    } catch (error: any) {
      setFeeClasses([]);
      setFeeLoadError(error?.response?.data?.message || error?.message || 'Could not load classes.');
    }
  };

  const loadSections = async (classId: string, target: 'assign' | 'payment' | 'report') => {
    try {
      setFeeLoadError('');
      const res = await api.get(`/classes/${encodeURIComponent(classId)}/sections`, { params: schoolScopedParams });
      if (target === 'assign') setAssignOptions((prev: any) => ({ ...prev, sections: res.data || [], students: [] }));
      if (target === 'payment') setPaymentOptions((prev: any) => ({ ...prev, sections: res.data || [], students: [] }));
      if (target === 'report') setReportOptions({ sections: res.data || [] });
    } catch (error: any) {
      setFeeLoadError(error?.response?.data?.message || error?.message || 'Could not load sections.');
      if (target === 'assign') setAssignOptions((prev: any) => ({ ...prev, sections: [], students: [] }));
      if (target === 'payment') setPaymentOptions((prev: any) => ({ ...prev, sections: [], students: [] }));
      if (target === 'report') setReportOptions({ sections: [] });
    }
  };

  const loadSectionStudents = async (classId: string, sectionId: string, target: 'assign' | 'payment') => {
    try {
      setFeeLoadError('');
      const res = await api.get(`/classes/${encodeURIComponent(classId)}/sections/${encodeURIComponent(sectionId)}/students`, { params: schoolScopedParams });
      if (target === 'assign') setAssignOptions((prev: any) => ({ ...prev, students: res.data || [] }));
      if (target === 'payment') setPaymentOptions((prev: any) => ({ ...prev, students: res.data || [] }));
    } catch (error: any) {
      setFeeLoadError(error?.response?.data?.message || error?.message || 'Could not load students.');
      if (target === 'assign') setAssignOptions((prev: any) => ({ ...prev, students: [] }));
      if (target === 'payment') setPaymentOptions((prev: any) => ({ ...prev, students: [] }));
    }
  };

  const loadFeeReports = async (classId: string, sectionId: string) => {
    setReportLoading(true);
    setFeeLoadError('');
    try {
      const [reportRes, overdueRes] = await Promise.all([
        api.get('/fees/report', { params: { classId, sectionId, ...(schoolScopedParams || {}) } }),
        api.get('/fees/overdue', { params: { classId, sectionId, ...(schoolScopedParams || {}) } })
      ]);
      const nextReportRows = Array.isArray(reportRes.data) ? reportRes.data : [];
      const nextOverdueRows = (Array.isArray(overdueRes.data) ? overdueRes.data : []).slice().sort((a: any, b: any) => Number(b?.daysOverdue || 0) - Number(a?.daysOverdue || 0));
      setReportRows(nextReportRows);
      setOverdueRows(nextOverdueRows);
      setSelectedReportStudentId((prev) => {
        if (prev && nextReportRows.some((row: any) => String(row.studentId || row.assignmentId || '') === prev)) return prev;
        const first = nextReportRows[0];
        return first ? String(first.studentId || first.assignmentId || '') : null;
      });
    } catch (error: any) {
      setReportRows([]);
      setOverdueRows([]);
      setSelectedReportStudentId(null);
      setFeeLoadError(error?.response?.data?.message || error?.message || 'Could not load fee reports.');
    } finally {
      setReportLoading(false);
    }
  };

  const openReceiptPdf = async (paymentId: string) => {
    const res = await api.get(`/fees/receipts/${encodeURIComponent(paymentId)}/pdf`, { responseType: 'blob' });
    const url = URL.createObjectURL(new Blob([res.data], { type: 'application/pdf' }));
    window.open(url, '_blank', 'noopener,noreferrer');
  };

  const handleAssignClassChange = async (classId: string) => {
    setAssignSelection({ classId, sectionId: '', studentId: '' });
    setAssignOptions({ sections: [], students: [] });
    setFeeAssignHint('');
    setFeeAssignError('');
    setFeeAssignForm({ studentId: '', bandId: '', paymentSchedule: '', bandDiscount: '0', manualDiscount: feeAssignForm.manualDiscount, surcharge: feeAssignForm.surcharge });
    if (!classId) return;
    await loadSections(classId, 'assign');
  };

  const handleAssignSectionChange = async (sectionId: string) => {
    setAssignSelection((prev: any) => ({ ...prev, sectionId, studentId: '' }));
    setAssignOptions((prev: any) => ({ ...prev, students: [] }));
    setFeeAssignHint('');
    setFeeAssignError('');
    setFeeAssignForm((prev) => ({ ...prev, studentId: '', bandId: '', paymentSchedule: '', bandDiscount: '0' }));
    if (!sectionId) return;
    await loadSectionStudents(assignSelection.classId, sectionId, 'assign');
  };

  const handleAssignStudentChange = async (studentId: string) => {
    setAssignSelection((prev: any) => ({ ...prev, studentId }));
    setFeeAssignError('');
    const selected = assignOptions.students.find((row: any) => String(row.id) === studentId);
    setFeeAssignForm((prev) => ({ ...prev, studentId }));
    if (!studentId || !assignSelection.classId) {
      setFeeAssignHint('');
      return;
    }
    try {
      const res = await api.get('/fee-structure/match', { params: { classId: assignSelection.classId } });
      const band = res.data || {};
      const schedules = Array.isArray(band.activeSchedules) ? band.activeSchedules : [];
      setFeeAssignForm((prev) => ({
        ...prev,
        studentId,
        bandId: band.id || '',
        paymentSchedule: schedules[0] || '',
        bandDiscount: String(band.discount ?? 0),
        surcharge: prev.paymentSchedule === 'Annual' ? '0' : prev.surcharge
      }));
      setFeeAssignHint('Auto-matched fee plan based on class. You can override below.');
    } catch (error: any) {
      setFeeAssignError(error?.response?.data?.message || error?.message || 'Could not auto-match fee plan.');
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
    await loadSectionStudents(paymentSelection.classId, sectionId, 'payment');
  };

  const handlePaymentStudentChange = (studentId: string) => {
    setPaymentSelection((prev: any) => ({ ...prev, studentId }));
    const selected = paymentOptions.students.find((row: any) => String(row.id) === studentId);
    setPaymentDuePreview(selected || null);
    setPaymentError('');
    setPaymentForm((prev) => ({ ...prev, studentId, studentName: selected?.name || '', amount: selected ? String(selected.dueAmount || '') : '' }));
  };

  const handleReportClassChange = async (classId: string) => {
    setFeeFilters({ className: classId, sectionName: '' });
    setReportRows([]);
    setOverdueRows([]);
    if (!classId) {
      setReportOptions({ sections: [] });
      return;
    }
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
        setPaymentError(`Amount ₹${paymentForm.amount} exceeds the due amount of ₹${formatMoney(Math.round(duePaise / 100))}. Please verify.`);
        return;
      }
      const payload = {
        studentId: paymentForm.studentId,
        amount: amountPaise,
        mode: paymentForm.paymentMode,
        notes: paymentForm.notes,
        paidAt: new Date().toISOString(),
        recordedBy: user?.userId || user?.email || 'current-user'
      };
      await api.post('/workspace/fees/record-payment', payload);
      await refresh();
      if (feeFilters.className && feeFilters.sectionName) {
        await loadFeeReports(feeFilters.className, feeFilters.sectionName);
      }
      if (paymentSelection.classId && paymentSelection.sectionId) {
        await loadSectionStudents(paymentSelection.classId, paymentSelection.sectionId, 'payment');
      }
      setPaymentSuccess(`Payment of ₹${paymentForm.amount} recorded for ${paymentForm.studentName || 'the selected student'}.`);
      setPaymentForm({ studentId: '', studentName: '', amount: '', paymentMode: 'UPI', notes: '' });
      setPaymentSelection((prev: any) => ({ ...prev, studentId: '' }));
      setPaymentDuePreview(null);
      window.setTimeout(() => setPaymentSuccess(''), 4000);
    } catch (error: any) {
      setPaymentError(error?.response?.data?.message || error?.message || 'Could not record payment.');
    } finally {
      setSaving('');
    }
  };

  const handleSendReminders = async () => {
    if (!(feeFilters.className && feeFilters.sectionName)) {
      setReminderNotice('Select a class and section before sending reminders.');
      return;
    }
    try {
      setReminderSaving(true);
      const res = await api.post('/fees/send-reminders', { classId: feeFilters.className, sectionId: feeFilters.sectionName });
      const queued = Number(res.data?.queued || overdueRows.length || 0);
      setReminderNotice(`Reminders queued for ${queued} overdue students.`);
    } catch (error: any) {
      setReminderNotice(error?.response?.data?.message || error?.message || 'Could not queue reminders.');
    } finally {
      setReminderSaving(false);
      window.setTimeout(() => setReminderNotice(''), 4000);
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
      Math.round(Number(r.totalAnnualFee || 0) / 100),
      Math.round(Number(r.approvedDiscount ?? r.discounts ?? 0) / 100),
      Math.round(Number(r.paid || 0) / 100),
      Math.round(Number(r.dueAmount ?? r.due ?? 0) / 100),
      r.status || ''
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

  const parseCsvRows = (content: string) => {
    const lines = content.replace(/\r/g, '').split('\n').filter((line) => line.trim().length > 0);
    if (!lines.length) return [] as any[];
    const headers = splitCsvLine(lines[0]);
    return lines.slice(1).map((line, index) => {
      const values = splitCsvLine(line);
      const row: Record<string, string | number> = { __rowNumber: index + 2 };
      headers.forEach((header, headerIndex) => {
        row[header] = values[headerIndex] || '';
      });
      return row;
    });
  };

  const handleBulkImportFile = async (file: File) => {
    const ext = file.name.toLowerCase();
    setBulkImportError('');
    setBulkImportWarning('');
    setBulkImportToast(null);
    setBulkImportProgress(null);
    if (!(ext.endsWith('.xlsx') || ext.endsWith('.csv'))) {
      setBulkImportError('Only .xlsx and .csv files are supported.');
      return;
    }
    if (file.size > 5 * 1024 * 1024) {
      setBulkImportError('Maximum file size is 5 MB.');
      return;
    }
    try {
      let rows: any[] = [];
      if (ext.endsWith('.csv')) {
        rows = parseCsvRows(await file.text());
      } else {
        const XLSX = await import('xlsx');
        const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array' });
        const sheet = workbook.Sheets[workbook.SheetNames[0]];
        rows = XLSX.utils.sheet_to_json(sheet, { defval: '' }).map((row: any, index: number) => ({ ...row, __rowNumber: index + 2 }));
      }
      if (rows.length > 500) {
        setBulkImportWarning('Maximum 500 rows per import. Please reduce the file and try again.');
        return;
      }
      setBulkImportFileName(file.name);
      const res = await api.post('/students/import/preview', { rows });
      setBulkImportPreview(res.data);
    } catch (error: any) {
      setBulkImportError(error?.response?.data?.message || error?.message || 'Could not parse and preview this file.');
    }
  };

  const handleBulkImportDrop = async (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setBulkImportDragActive(false);
    const file = event.dataTransfer.files?.[0];
    if (file) await handleBulkImportFile(file);
  };

  const downloadImportTemplate = async () => {
    const res = await api.get('/students/import/template', { responseType: 'blob' });
    const url = URL.createObjectURL(new Blob([res.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'student-import-template.xlsx';
    link.click();
    URL.revokeObjectURL(url);
  };

  const confirmBulkImport = async () => {
    if (!bulkImportPreview?.fileToken) return;
    try {
      setSaving('bulk-import-confirm');
      setBulkImportToast(null);
      const confirmRes = await api.post('/students/import/confirm', { fileToken: bulkImportPreview.fileToken });
      const jobId = confirmRes.data?.jobId;
      let done = false;
      let finalStatus: any = null;
      while (!done && jobId) {
        const statusRes = await api.get(`/students/import/status/${encodeURIComponent(jobId)}`);
        finalStatus = statusRes.data;
        setBulkImportProgress(finalStatus);
        done = Boolean(finalStatus?.done);
        if (!done) await new Promise((resolve) => setTimeout(resolve, 500));
      }
      setBulkImportToast({
        type: 'success',
        message: `${finalStatus?.inserted || 0} students imported successfully. ${finalStatus?.skipped || 0} rows skipped due to errors.`
      });
      await refresh();
    } catch (error: any) {
      setBulkImportToast({ type: 'error', message: error?.response?.data?.message || error?.message || 'Import failed.' });
    } finally {
      setSaving('');
    }
  };

  useEffect(() => {
    if (user?.role === 'SUPERADMIN') {
      setWorkspace({ school: { name: 'Custoking Platform', meta: 'Super Admin' } });
      loadPendingApprovalOrders();
      return;
    }
    refresh();
  }, [user?.role]);

  useEffect(() => {
    if (user?.role !== 'SUPERADMIN') return;
    const adminOnlyPanels: PanelKey[] = ['home', 'students', 'fees', 'feestructure', 'attendance', 'timetable', 'addstudent', 'bulkimport', 'staff', 'catalog', 'planning', 'ff-new', 'ff-approvals'];
    if (adminOnlyPanels.includes(panel)) setPanel('orders');
  }, [user?.role, panel]);

  useEffect(() => {
    if (panel === 'students') {
      loadStudents();
    }
  }, [panel, studentFilters]);

  useEffect(() => {
    if (panel === 'fees' || panel === 'feestructure') {
      loadFeeClasses();
    }
    if (panel === 'feestructure') {
      loadFeeStructure();
    }
  }, [panel]);


useEffect(() => {
  if (user?.role !== 'SUPERADMIN') return;
  if (panel === 'sa-all-orders') void loadSaAllOrders();
  if (panel === 'sa-new-order') void loadSaSchoolOptions();
  if (panel === 'sa-invoices') void loadSaInvoices();
  if (panel === 'sa-schools') void loadSaSchools();
}, [panel, user?.role]);

  // ── API helpers — fee structure ────────────────────────────────────────────
  const loadFeeStructure = async () => {
    try {
      setFeeStructureLoading(true);
      setFeeStructureError('');
      const res = await api.get('/fee-structure', { params: { academicYearId: 'ay_2024_25' } });
      setFeeStructureData(res.data || { academicYear: '2025–26', academicYearId: 'ay_2024_25', bands: [] });
      setExpandedBandIds([]);
      const firstBandId = res.data?.bands?.[0]?.id || '';
      setFeeItemForm((prev) => ({ ...prev, bandId: prev.bandId || firstBandId }));
    } catch (error: any) {
      setFeeStructureError(error?.response?.data?.message || error?.message || 'Could not load fee structure.');
    } finally {
      setFeeStructureLoading(false);
    }
  };

  const addFeeStructureItem = async () => {
    if (!feeItemForm.bandId || !feeItemForm.itemName.trim() || feeItemForm.amount === '') {
      setFeeStructureError('Item name and amount are required.');
      return;
    }
    try {
      setSaving('fee-structure-add');
      setFeeStructureError('');
      await api.post('/fee-structure/item', { bandId: feeItemForm.bandId, itemName: feeItemForm.itemName, frequency: feeItemForm.frequency, amount: Number(feeItemForm.amount) });
      const bandName = feeStructureData.bands.find((band: any) => band.id === feeItemForm.bandId)?.name || 'band';
      setFeeStructureToast(`Item added to ${bandName}.`);
      setShowFeeItemForm(false);
      setFeeItemForm((prev) => ({ ...prev, itemName: '', amount: '' }));
      await loadFeeStructure();
      setExpandedBandIds([feeItemForm.bandId]);
      await refresh();
    } catch (error: any) {
      setFeeStructureError(error?.response?.data?.message || error?.message || 'Could not add item.');
    } finally {
      setSaving('');
    }
  };

  const saveFeeStructureItem = async () => {
    if (!editingFeeItem?.id) return;
    try {
      setSaving('fee-structure-edit');
      await api.put(`/fee-structure/item/${encodeURIComponent(editingFeeItem.id)}`, {
        itemName: editingFeeItem.name,
        frequency: editingFeeItem.frequency,
        amount: Number(editingFeeItem.amount || 0)
      });
      setFeeStructureToast(`Updated ${editingFeeItem.name}.`);
      setEditingFeeItem(null);
      await loadFeeStructure();
      await refresh();
    } catch (error: any) {
      setFeeStructureError(error?.response?.data?.message || error?.message || 'Could not update item.');
    } finally {
      setSaving('');
    }
  };

  const removeFeeStructureItem = async (itemId: string) => {
    try {
      setSaving(`fee-structure-remove-${itemId}`);
      await api.delete(`/fee-structure/item/${encodeURIComponent(itemId)}`);
      setFeeStructureToast('Item removed.');
      setConfirmRemoveFeeItemId('');
      await loadFeeStructure();
      await refresh();
    } catch (error: any) {
      setFeeStructureError(error?.response?.data?.message || error?.message || 'Could not remove item.');
    } finally {
      setSaving('');
    }
  };

  const patchFeeBand = async (bandId: string, payload: any) => {
    try {
      await api.patch(`/fee-structure/band/${encodeURIComponent(bandId)}`, payload);
      await loadFeeStructure();
      await refresh();
    } catch (error: any) {
      setFeeStructureError(error?.response?.data?.message || error?.message || 'Could not update fee band.');
    }
  };

  const handleDiscountChange = (bandId: string, value: string) => {
    const nextBands = (feeStructureData.bands || []).map((band: any) => band.id === bandId ? { ...band, discount: value } : band);
    setFeeStructureData((prev: any) => ({ ...prev, bands: nextBands }));
    const existing = discountTimers.current[bandId];
    if (existing) window.clearTimeout(existing);
    discountTimers.current[bandId] = window.setTimeout(() => {
      patchFeeBand(bandId, { discount: Number(value || 0) });
    }, 400);
  };

  const toggleBandSchedule = async (band: any, schedule: string) => {
    const active = Array.isArray(band.activeSchedules) ? band.activeSchedules : [];
    const next = active.includes(schedule) ? active.filter((item: string) => item !== schedule) : [...active, schedule];
    const nextBands = (feeStructureData.bands || []).map((row: any) => row.id === band.id ? { ...row, activeSchedules: next } : row);
    setFeeStructureData((prev: any) => ({ ...prev, bands: nextBands }));
    await patchFeeBand(band.id, { schedules: next });
  };

  const exportFeeStructurePdf = async () => {
    const res = await api.get('/fee-structure/export', { params: { academicYearId: feeStructureData.academicYearId || 'ay_2024_25', format: 'pdf' }, responseType: 'blob' });
    const url = URL.createObjectURL(new Blob([res.data], { type: 'application/pdf' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'fee-structure.pdf';
    link.click();
    URL.revokeObjectURL(url);
  };

  const toggleBandAccordion = (bandId: string) => {
    setExpandedBandIds((prev) => prev.includes(bandId) ? prev.filter((id) => id !== bandId) : [...prev, bandId]);
  };

  const toggleBandFormSchedule = (schedule: string, target: 'create' | 'edit', bandId?: string) => {
    if (target === 'create') {
      setBandForm((prev: any) => ({ ...prev, schedules: prev.schedules.includes(schedule) ? prev.schedules.filter((item: string) => item !== schedule) : [...prev.schedules, schedule] }));
      return;
    }
    setFeeStructureData((prev: any) => ({
      ...prev,
      bands: prev.bands.map((band: any) => band.id === bandId ? { ...band, editSchedules: (band.editSchedules || band.activeSchedules || []).includes(schedule) ? (band.editSchedules || band.activeSchedules || []).filter((item: string) => item !== schedule) : [...(band.editSchedules || band.activeSchedules || []), schedule] } : band)
    }));
  };

  const addFeeBand = async () => {
    if (!bandForm.name.trim()) { setFeeStructureError('Band name is required.'); return; }
    if (Number(bandForm.classTo) < Number(bandForm.classFrom)) { setFeeStructureError('Class to must be greater than or equal to class from.'); return; }
    if (!(bandForm.schedules || []).length) { setFeeStructureError('Select at least one payment schedule.'); return; }
    try {
      setSaving('fee-band-add');
      setFeeStructureError('');
      await api.post('/fee-structure/band', { name: bandForm.name, classFrom: Number(bandForm.classFrom), classTo: Number(bandForm.classTo), discount: Number(bandForm.discount || 0), schedules: bandForm.schedules });
      setFeeStructureToast(`Band '${bandForm.name}' created.`);
      setShowBandForm(false);
      setBandForm({ name: '', classFrom: '1', classTo: '5', discount: '0', schedules: ['Annual'] });
      await loadFeeStructure();
    } catch (error: any) {
      setFeeStructureError(error?.response?.data?.message || error?.message || 'Could not create band.');
    } finally {
      setSaving('');
    }
  };

  const saveFeeBandEdit = async (band: any) => {
    const schedules = band.editSchedules || band.activeSchedules || [];
    const nextName = String(band.editName ?? band.name ?? '').trim();
    const nextClassFrom = Number(band.editClassFrom ?? band.classFrom ?? 0);
    const nextClassTo = Number(band.editClassTo ?? band.classTo ?? 0);
    const nextDiscount = Number(band.editDiscount ?? band.discount ?? 0);
    if (!nextName) { setFeeStructureError('Band name is required.'); return; }
    if (nextClassTo < nextClassFrom) { setFeeStructureError('Class to must be greater than or equal to class from.'); return; }
    if (!schedules.length) { setFeeStructureError('Select at least one payment schedule.'); return; }
    try {
      setSaving(`fee-band-edit-${band.id}`);
      setFeeStructureError('');
      await api.put(`/fee-structure/band/${encodeURIComponent(band.id)}`, { name: nextName, classFrom: nextClassFrom, classTo: nextClassTo, discount: nextDiscount, schedules });
      setFeeStructureToast(`Updated ${nextName}.`);
      setEditingBandId('');
      await loadFeeStructure();
    } catch (error: any) {
      setFeeStructureError(error?.response?.data?.message || error?.message || 'Could not update band.');
    } finally {
      setSaving('');
    }
  };

  const deleteFeeBand = async (bandId: string, bandName: string) => {
    try {
      setSaving(`fee-band-remove-${bandId}`);
      await api.delete(`/fee-structure/band/${encodeURIComponent(bandId)}`);
      setFeeStructureToast('Band deleted.');
      setConfirmDeleteBandId('');
      await loadFeeStructure();
    } catch (error: any) {
      setFeeStructureError(error?.response?.data?.message || error?.message || 'Could not delete band.');
    } finally {
      setSaving('');
    }
  };

  const handleFeePlanChange = (bandId: string) => {
    const band = (feeStructureData.bands || []).find((row: any) => row.id === bandId);
    const schedules = Array.isArray(band?.activeSchedules) ? band.activeSchedules : [];
    setFeeAssignForm((prev) => ({ ...prev, bandId, paymentSchedule: schedules.includes(prev.paymentSchedule) ? prev.paymentSchedule : (schedules[0] || ''), bandDiscount: String(band?.discount ?? 0) }));
  };

  const submitFeeAssignment = async () => {
    try {
      setSaving('assign-fee');
      setFeeAssignError('');
      const selectedBand = (feeStructureData.bands || []).find((band: any) => band.id === feeAssignForm.bandId);
      const total = Number(selectedBand?.annualTotal || 0);
      const bandDiscountPct = Number(feeAssignForm.bandDiscount || 0);
      const manualDiscountPct = Number(feeAssignForm.manualDiscount || 0);
      const surchargePct = feeAssignForm.paymentSchedule === 'Annual' ? 0 : Number(feeAssignForm.surcharge || 0);
      const netPayable = Math.round(total - Math.round(total * bandDiscountPct / 100) - Math.round(total * manualDiscountPct / 100) + (feeAssignForm.paymentSchedule === 'Annual' ? 0 : Math.round(total * surchargePct / 100)));
      await api.post('/fee-assignments', {
        studentId: feeAssignForm.studentId,
        bandId: feeAssignForm.bandId,
        schedule: feeAssignForm.paymentSchedule,
        bandDiscount: bandDiscountPct,
        manualDiscount: manualDiscountPct,
        surcharge: surchargePct,
        netPayable,
        academicYearId: feeStructureData.academicYearId,
        assignedBy: user?.userId || user?.email || 'current-user'
      });
      setFeeStructureToast('Fee plan assigned successfully');
      await refresh();
    } catch (error: any) {
      setFeeAssignError(error?.response?.data?.message || error?.message || 'Could not assign fee plan.');
    } finally {
      setSaving('');
    }
  };


  // ── API helpers — attendance ───────────────────────────────────────────────
  const loadAttendanceClasses = async () => {
    const res = await api.get('/classes', { params: schoolScopedParams });
    setAttendanceClassOptions(res.data || []);
  };

  const loadAttendanceSummary = async (dateValue: string) => {
    const queryDate = dateValue || 'today';
    const res = await api.get('/attendance/daily-summary', { params: { date: queryDate, ...(schoolScopedParams || {}) } });
    setAttendanceSummary(res.data || { dateLabel: '', overallPercent: 0, sections: [], allSubmitted: false, nonWorkingDay: false });
  };

  const loadAttendanceSectionInfo = async (dateValue: string, classId: string, sectionId: string) => {
    if (!dateValue || !classId || !sectionId) {
      setAttendanceSectionInfo(null);
      setAttendancePresentCount('');
      return;
    }
    const res = await api.get('/attendance/section-info', { params: { date: dateValue, classId, sectionId, ...(schoolScopedParams || {}) } });
    setAttendanceSectionInfo(res.data);
    const existing = res.data?.existingRecord;
    setAttendancePresentCount(existing?.presentCount != null ? String(existing.presentCount) : '');
  };

  const handleAttendanceDateChange = async (dateValue: string) => {
    setAttendanceFilters({ date: dateValue, classId: '', sectionId: '' });
    setAttendanceSectionOptions([]);
    setAttendanceSectionInfo(null);
    setAttendancePresentCount('');
    setAttendanceSaveError('');
    setAttendanceToast('');
    await loadAttendanceSummary(dateValue);
  };

  const handleAttendanceClassChange = async (classId: string) => {
    setAttendanceFilters((prev) => ({ ...prev, classId, sectionId: '' }));
    setAttendanceSectionInfo(null);
    setAttendancePresentCount('');
    setAttendanceSaveError('');
    setAttendanceToast('');
    if (!classId) {
      setAttendanceSectionOptions([]);
      return;
    }
    const res = await api.get(`/classes/${encodeURIComponent(classId)}/sections`, { params: schoolScopedParams });
    setAttendanceSectionOptions(res.data || []);
  };

  const handleAttendanceSectionChange = async (sectionId: string) => {
    const next = { ...attendanceFilters, sectionId };
    setAttendanceFilters(next);
    setAttendanceSaveError('');
    setAttendanceToast('');
    await loadAttendanceSectionInfo(next.date, next.classId, sectionId);
  };

  const saveAttendanceEntry = async () => {
    try {
      setSaving('attendance-save');
      setAttendanceSaveError('');
      const totalEnrolled = Number(attendanceSectionInfo?.totalEnrolled || 0);
      const presentCount = Number(attendancePresentCount || 0);
      const response = await api.post('/attendance/daily-entry', {
        date: attendanceFilters.date,
        classId: attendanceFilters.classId,
        sectionId: attendanceFilters.sectionId,
        academicYearId: 'ay_2024_25',
        totalEnrolled,
        presentCount,
        absentCount: Math.max(totalEnrolled - presentCount, 0),
        recordedBy: user?.userId || 'current-user'
      });
      const pct = Number(response.data?.presentPercent || 0);
      setAttendanceToast(`Saved — ${String(response.data?.classSection || '').replace('Class ', '')} · ${presentCount}/${totalEnrolled} present (${pct}%)`);
      await loadAttendanceSectionInfo(attendanceFilters.date, attendanceFilters.classId, attendanceFilters.sectionId);
      await loadAttendanceSummary(attendanceFilters.date);
      await refresh();
    } catch (error: any) {
      setAttendanceSaveError(error?.response?.data?.message || error?.message || 'Could not save attendance.');
    } finally {
      setSaving('');
    }
  };

  const submitAttendanceDay = async () => {
    try {
      setSaving('attendance-submit-day');
      setAttendanceSaveError('');
      await api.post('/attendance/submit-day', { date: attendanceFilters.date });
      setAttendanceToast(`Submitted attendance for ${attendanceSummary?.dateLabel || attendanceFilters.date}.`);
      await loadAttendanceSummary(attendanceFilters.date);
    } catch (error: any) {
      setAttendanceSaveError(error?.response?.data?.message || error?.message || 'Could not submit attendance day.');
    } finally {
      setSaving('');
    }
  };


  useEffect(() => {
    if (panel === 'attendance') {
      loadAttendanceClasses();
      loadAttendanceSummary(attendanceFilters.date);
    }
  }, [panel]);

  useEffect(() => { if (panel === 'orders') loadLiveOrders(); }, [panel]);

  useEffect(() => {
    if (panel === 'ff-approvals' && workspace) loadFfApprovalDetails();
  }, [panel, workspace]);

  useEffect(() => {
    if ((panel === 'ff-dashboard' || panel === 'ff-orders') && user?.role === 'SUPERADMIN') {
      void loadSaFfRequests();
    }
  }, [panel, user?.role]);

  // ── API helpers — firefighting ────────────────────────────────────────────
  const loadFfApprovalDetails = async () => {
    const pending = (workspace?.firefighting?.requests || [])
      .filter((r: any) => ['AWAITING_BURSAR', 'AWAITING_PRINCIPAL'].includes(r.status));
    if (!pending.length) { setFfApprovalDetails([]); return; }
    setFfApprovalLoading(true);
    try {
      const details = await Promise.all(
        pending.map((r: any) => api.get(`/ff/requests/${r.code}`).then(res => res.data).catch(() => null))
      );
      setFfApprovalDetails(details.filter(Boolean));
    } finally {
      setFfApprovalLoading(false);
    }
  };

  const openFfDraft = async (req: any) => {
    try {
      const res = await api.get(`/ff/requests/${req.code}`);
      const d = res.data;
      setFfEditingCode(d.code);
      setFfExistingQuotes(d.quotations || []);
      setFfForm({
        title: d.title || '',
        category: d.category || 'Furniture & fixtures',
        estimatedBudget: d.estimatedBudget ? String(d.estimatedBudget) : '',
        urgency: d.urgency || 'MEDIUM',
        requiredByDate: d.requiredByDate || '',
        summary: d.description || '',
        quotations: [emptyQuote(), emptyQuote(), emptyQuote()],
      });
      setFfStep(2);
      setPanel('ff-new');
    } catch (e: any) {
      alert(e?.response?.data?.message || 'Failed to open draft');
    }
  };

  const saveFfAsDraft = async () => {
    if (!ffForm.title.trim()) { setFfError('Request title is required'); return; }
    setFfDraftSaving(true); setFfError('');
    try {
      if (ffEditingCode) {
        await api.patch(`/ff/requests/${ffEditingCode}`, {
          title: ffForm.title, category: ffForm.category, urgency: ffForm.urgency,
          requiredByDate: ffForm.requiredByDate || null,
          estimatedBudget: ffForm.estimatedBudget ? Number(ffForm.estimatedBudget) : 0,
          description: ffForm.summary,
        });
      } else {
        await api.post('/workspace/firefighting', { ...ffForm, status: 'draft' });
      }
      await refresh();
      setPanel('ff-dashboard');
      setFfStep(1);
      setFfForm(ffFormInit());
      setFfEditingCode(null);
      setFfExistingQuotes([]);
    } catch (e: any) {
      setFfError(e?.response?.data?.message || 'Failed to save draft');
    } finally {
      setFfDraftSaving(false);
    }
  };

  const submitFfRequest = async () => {
    if (!ffForm.title.trim()) { setFfError('Request title is required'); return; }
    setFfSaving(true); setFfError('');
    try {
      if (ffEditingCode) {
        await api.patch(`/ff/requests/${ffEditingCode}`, {
          title: ffForm.title, category: ffForm.category, urgency: ffForm.urgency,
          requiredByDate: ffForm.requiredByDate || null,
          estimatedBudget: ffForm.estimatedBudget ? Number(ffForm.estimatedBudget) : 0,
          description: ffForm.summary,
        });
        const newQuotes = ffForm.quotations.filter(q => q.vendorName.trim());
        for (const q of newQuotes) {
          await api.post(`/ff/requests/${ffEditingCode}/quotations`, {
            vendorName: q.vendorName, amount: q.amount ? Number(q.amount) : 0,
            deliveryTimeline: q.deliveryTimeline, notes: q.notes, documentUrl: q.documentUrl,
          });
        }
        await api.post(`/ff/requests/${ffEditingCode}/submit`);
      } else {
        await api.post('/workspace/firefighting', ffForm);
      }
      await refresh();
      setPanel('ff-dashboard');
      setFfStep(1);
      setFfForm(ffFormInit());
      setFfEditingCode(null);
      setFfExistingQuotes([]);
    } catch (e: any) {
      setFfError(e?.response?.data?.message || 'Failed to submit request');
    } finally {
      setFfSaving(false);
    }
  };

  const approveFfRequest = async (req: any, chainPrincipal = false) => {
    const note = ffApprovalNotes[req.code] || '';
    const qid = ffApprovalNotes[`${req.code}_qid`] || (req.quotations?.[0]?.id || '');
    try {
      if (req.status === 'AWAITING_BURSAR') {
        await api.post(`/ff/requests/${req.code}/approve-bursar`, { note });
        if (chainPrincipal) {
          await api.post(`/ff/requests/${req.code}/approve-principal`, { note, selectedQuotationId: qid });
        }
      } else if (req.status === 'AWAITING_PRINCIPAL') {
        await api.post(`/ff/requests/${req.code}/approve-principal`, { note, selectedQuotationId: qid });
      }
      await refresh();
      await loadFfApprovalDetails();
    } catch (e: any) {
      alert(e?.response?.data?.message || 'Approval failed');
    }
  };

  const approveFfCustoking = async (req: any) => {
    try {
      await api.post(`/ff/requests/${req.code}/approve-custoking`, {});
      await refresh();
      await loadSaFfRequests();
    } catch (e: any) {
      alert(e?.response?.data?.message || 'Failed to approve for Custoking fulfillment');
    }
  };

  const fulfillFfRequest = async (req: any) => {
    try {
      await api.patch(`/ff/requests/${req.code}/fulfill`);
      await refresh();
      await loadSaFfRequests();
    } catch (e: any) {
      alert(e?.response?.data?.message || 'Failed to mark as fulfilled');
    }
  };

  const loadSaFfRequests = async () => {
    if (user?.role !== 'SUPERADMIN') return;
    setSaFfLoading(true);
    try {
      const res = await api.get('/ff/requests');
      setSaFfRequests(Array.isArray(res.data) ? res.data : []);
    } catch {
      setSaFfRequests([]);
    } finally {
      setSaFfLoading(false);
    }
  };

  const rejectFfRequest = async (req: any) => {
    const reason = window.prompt('Reason for rejection:');
    if (reason === null) return;
    try {
      await api.post(`/ff/requests/${req.code}/reject`, { reason });
      await refresh();
      await loadFfApprovalDetails();
    } catch (e: any) {
      alert(e?.response?.data?.message || 'Rejection failed');
    }
  };

  const openFfTrack = async (req: any) => {
    setFfTrackRequest(req);
    setFfTrackOpen(true);
    setFfTimeline([]);
    setFfTimelineLoading(true);
    try {
      const res = await api.get(`/ff/requests/${req.code}/timeline`);
      setFfTimeline(res.data || []);
    } catch {
      setFfTimeline([]);
    } finally {
      setFfTimelineLoading(false);
    }
  };

  // ── API helpers — superadmin ───────────────────────────────────────────────
  const loadSaAllOrders = async () => {
    setSaAllOrdersLoading(true); setSaAllOrdersError('');
    try {
      const [ordRes, statsRes] = await Promise.all([api.get('/sa/orders'), api.get('/sa/orders/stats')]);
      setSaAllOrders(Array.isArray(ordRes.data) ? ordRes.data : []);
      setSaOrderStats(statsRes.data || null);
    } catch (e: any) {
      setSaAllOrdersError(e?.response?.data?.message || 'Failed to load orders.');
    } finally {
      setSaAllOrdersLoading(false);
    }
  };

  const loadSaSchoolOptions = async () => {
    try { const res = await api.get('/sa/schools'); setSaSchoolOptions(Array.isArray(res.data) ? res.data : []); } catch { setSaSchoolOptions([]); }
  };

  const loadSaInvoices = async () => {
    setSaInvoicesLoading(true); setSaInvoicesError('');
    try {
      const [invRes, statsRes] = await Promise.all([api.get('/sa/invoices'), api.get('/sa/invoices/stats')]);
      setSaInvoices(Array.isArray(invRes.data) ? invRes.data : []);
      setSaInvStats(statsRes.data || null);
      setSaInvBadge(Number(statsRes.data?.pending || 0));
    } catch (e: any) {
      setSaInvoicesError(e?.response?.data?.message || 'Failed to load invoices.');
    } finally {
      setSaInvoicesLoading(false);
    }
  };

  const loadSaSchools = async () => {
    setSaSchoolsLoading(true); setSaSchoolsError('');
    try { const res = await api.get('/sa/schools'); setSaSchools(Array.isArray(res.data) ? res.data : []); } catch (e: any) { setSaSchoolsError(e?.response?.data?.message || 'Failed to load schools.'); } finally { setSaSchoolsLoading(false); }
  };

  const openSaOrderDetail = async (orderId: string) => {
    setSaDetailLoading(true); setSaDetailError(''); setSaDetailOpen(true); setSaDetailOrder(null);
    try { const res = await api.get(`/supply/orders/${orderId}`); setSaDetailOrder(res.data); setSaNewStatus(res.data?.status || ''); } catch (e: any) { setSaDetailError(e?.response?.data?.message || 'Failed to load order.'); } finally { setSaDetailLoading(false); }
  };

  const saveSaOrderStatus = async () => { if (!saDetailOrder) return; setSaStatusSaving(true); setSaDetailError(''); try { await api.patch(`/sa/orders/${saDetailOrder.id}/status`, { status: saNewStatus }); setSaDetailOpen(false); await loadSaAllOrders(); } catch (e: any) { setSaDetailError(e?.response?.data?.message || 'Save failed.'); } finally { setSaStatusSaving(false); } };
  const acceptSaOrder = async (orderId: string) => { try { await api.patch(`/sa/orders/${orderId}/status`, { status: 'IN_PROGRESS' }); await loadSaAllOrders(); } catch {} };
  const submitSaOrder = async () => { const errors: Record<string, string> = {}; if (!saNewOrderForm.schoolId) errors.school = 'School is required'; if (saActiveCat === 'CUSTOM' && !saNewOrderForm.description) errors.description = 'Description is required'; if (saActiveCat === 'UNIFORMS' && !saNewOrderForm.academicYear) errors.academicYear = 'Academic year is required'; if (saActiveCat === 'NOTEBOOKS' && !saNewOrderForm.deliveryDate) errors.deliveryDate = 'Delivery date is required'; if (saActiveCat === 'IDCARDS' && !saNewOrderForm.cardType) errors.cardType = 'Card type is required'; if (saActiveCat === 'STATIONERY' && !saNewOrderForm.kitQty) errors.kitQty = 'Quantity is required'; if (saActiveCat === 'HOUSEKEEPING') { if (!saNewOrderForm.startDate) errors.startDate = 'Start date is required'; if (!saNewOrderForm.duration) errors.duration = 'Duration is required'; } if (saActiveCat === 'EVENTS' && !saNewOrderForm.deliveryDate) errors.deliveryDate = 'Delivery date is required'; if (Object.keys(errors).length) { setSaNewOrderErrors(errors); return; } setSaNewOrderErrors({}); setSaNewOrderSaving(true); try { const value = computeSaOrderValue(saActiveCat || 'CUSTOM', saNewOrderForm, saEventItems); const res = await api.post('/sa/orders', { schoolId: saNewOrderForm.schoolId, category: saActiveCat, orderData: JSON.stringify({ ...saNewOrderForm, eventItems: saEventItems, title: saActiveCat }), subtotal: value * 100, gst: 0, totalAmount: value * 100, requiredByDate: saNewOrderForm.deliveryDate || saNewOrderForm.startDate || saNewOrderForm.requiredByDate || null, notes: saNewOrderForm.notes || '' }); setSaNewOrderNotice(`Order ${res.data?.id} created. Now visible in All Orders.`); setSaActiveCat(null); setSaNewOrderForm({}); setSaEventItems([]); await loadSaAllOrders(); window.setTimeout(() => { setSaNewOrderNotice(''); setPanel('sa-all-orders'); }, 1200); } catch (e: any) { setSaNewOrderErrors({ _api: e?.response?.data?.message || 'Failed to create order.' }); } finally { setSaNewOrderSaving(false); } };
  const openSaInvoiceFromOrder = async (orderId: string, school: string, schoolId: number | null, valuePaise: number) => { setSaInvError(''); setSaInvEditing(false); setSaInvSaving(false); try { const res = await api.get(`/sa/invoices/by-order/${orderId}`); if (res.data) { setSaInvData({ ...res.data }); setSaInvExistingId(res.data.id); } else { const today = todayIso(); const due = new Date(Date.now() + 14 * 864e5).toISOString().slice(0, 10); const gstAmount = Math.round(valuePaise * 0.12); setSaInvData({ orderRef: orderId, school, schoolId, description: orderId, qty: 1, rate: valuePaise, amount: valuePaise, gstAmount, total: valuePaise + gstAmount, status: 'Awaiting payment', issuedAt: today, dueAt: due }); setSaInvExistingId(null); } } catch { setSaInvData({ orderRef: orderId, school, schoolId, description: orderId, qty: 1, rate: valuePaise, amount: valuePaise, gstAmount: Math.round(valuePaise * 0.12), total: valuePaise + Math.round(valuePaise * 0.12), status: 'Awaiting payment', issuedAt: todayIso(), dueAt: new Date(Date.now() + 14 * 864e5).toISOString().slice(0, 10) }); setSaInvExistingId(null); } setSaInvOpen(true); };
  const openSaInvoiceView = async (invoiceId: string) => { setSaInvError(''); setSaInvEditing(false); setSaInvSaving(false); try { const res = await api.get('/sa/invoices'); const inv = (Array.isArray(res.data) ? res.data : []).find((i: any) => i.id === invoiceId); if (!inv) { alert('Record not found'); return; } setSaInvData({ ...inv }); setSaInvExistingId(invoiceId); setSaInvOpen(true); } catch { alert('Failed to load invoice'); } };
  const openBlankSaInvoice = () => { setSaInvError(''); setSaInvEditing(true); setSaInvSaving(false); setSaInvExistingId(null); const issuedAt = todayIso(); const dueAt = new Date(Date.now() + 14 * 864e5).toISOString().slice(0, 10); setSaInvData({ school: '', orderRef: '', description: '', qty: 1, rate: 0, amount: 0, gstAmount: 0, total: 0, status: 'Awaiting payment', issuedAt, dueAt, notes: '' }); setSaInvOpen(true); };
  const sendSaInvoice = async () => { setSaInvSaving(true); setSaInvError(''); try { if (saInvExistingId) { alert(`Invoice already sent. Resending to ${saInvData.school}.`); return; } const amount = Number(saInvData.qty || 0) * Number(saInvData.rate || 0); const res = await api.post('/sa/invoices', { orderRef: saInvData.orderRef, school: saInvData.school, schoolId: saInvData.schoolId ?? null, description: saInvData.description, qty: Number(saInvData.qty || 0), rate: Number(saInvData.rate || 0), amount, notes: saInvData.notes || '' }); setSaInvExistingId(res.data.id); setSaInvData({ ...res.data }); alert(`Invoice ${res.data.id} sent to ${saInvData.school}`); await loadSaInvoices(); } catch (e: any) { setSaInvError(e?.response?.data?.message || 'Save failed. Please try again.'); } finally { setSaInvSaving(false); } };
  const saveSaInvoiceEdit = async () => { if (!saInvExistingId) return; setSaInvSaving(true); setSaInvError(''); try { await api.patch(`/sa/invoices/${saInvExistingId}`, { description: saInvData.description, qty: Number(saInvData.qty || 0), rate: Number(saInvData.rate || 0), school: saInvData.school, status: saInvData.status }); setSaInvEditing(false); await loadSaInvoices(); } catch (e: any) { setSaInvError(e?.response?.data?.message || 'Save failed. Please try again.'); } finally { setSaInvSaving(false); } };
  const submitSaOnboard = async () => { const errors: Record<string, string> = {}; if (!saOnboardForm.name) errors.name = 'School name is required'; if (!saOnboardForm.shortCode) errors.shortCode = 'Short code is required'; if (!saOnboardForm.city) errors.city = 'City is required'; const classCount = Number(saOnboardForm.classCount || 0); const sectionCount = Number(saOnboardForm.sectionCount || 0); if (!Number.isInteger(classCount) || classCount < 1 || classCount > 12) errors.classCount = 'Classes must be between 1 and 12'; if (!Number.isInteger(sectionCount) || sectionCount < 1 || sectionCount > 26) errors.sectionCount = 'Sections must be between 1 and 26'; if (Object.keys(errors).length) { setSaOnboardErrors(errors); return; } setSaOnboardErrors({}); setSaOnboardSaving(true); try { await api.post('/schools', { ...saOnboardForm, classCount, sectionCount }); alert(`${saOnboardForm.name} onboarded successfully`); setSaOnboardOpen(false); setSaOnboardForm({ name: '', shortCode: '', city: '', state: '', contactEmail: '', contactPhone: '', classCount: '12', sectionCount: '2' }); await loadSaSchools(); await loadSaSchoolOptions(); } catch (e: any) { setSaOnboardErrors({ _: e?.response?.data?.message || 'Save failed. Please try again.' }); } finally { setSaOnboardSaving(false); } };
  const saCategoryMeta = SA_NEW_ORDER_CATEGORIES.find((item) => item.key === saActiveCat) || null;

  // ── Catalog order calculations (closures over form state) ─────────────────
  const calcUniform      = () => { const subtotalRs = uniformForm.items.reduce((s, r) => s + r.qty * r.unitPrice, 0); const gstRs = Math.round(subtotalRs * 0.05); return { subtotalRs, gstRs, totalRs: subtotalRs + gstRs }; };
  const calcNotebook     = () => { const subtotalRs = notebookForm.items.reduce((s, r) => s + r.qty * r.unitPrice, 0); const gstRs = Math.round(subtotalRs * 0.12); return { subtotalRs, gstRs, totalRs: subtotalRs + gstRs }; };
  const calcStationery   = () => { const kitCost = stationeryForm.items.reduce((s, r) => s + r.perKit * r.unitPrice, 0); const subtotalRs = kitCost * stationeryForm.numKits; const gstRs = Math.round(subtotalRs * 0.12); return { subtotalRs, gstRs, totalRs: subtotalRs + gstRs }; };
  const calcIdCard       = () => { const total = idCardForm.studentCount + idCardForm.staffCount + idCardForm.spareCards; const cardCost = total * 30; const lanyardCost = idCardForm.lanyardIncluded.startsWith('Yes') ? total * 8 : 0; const subtotalRs = cardCost + lanyardCost; const gstRs = Math.round(subtotalRs * 0.18); return { subtotalRs, gstRs, totalRs: subtotalRs + gstRs, total }; };
  const calcHousekeeping = () => { const months = ({ '1 month': 1, '3 months': 3, '6 months': 6, 'Academic year': 10 } as Record<string, number>)[housekeepingForm.duration] ?? 3; const subtotalRs = housekeepingForm.staffRequired * months * 9000; return { subtotalRs, gstRs: 0, totalRs: subtotalRs, months }; };
  const calcEvents       = () => { const subtotalRs = eventsForm.items.reduce((s, r) => s + r.qty * r.unitPrice, 0); return { subtotalRs, gstRs: 0, totalRs: subtotalRs }; };
  const calcHealth       = () => { const totalRs = healthForm.items.reduce((s, r) => s + r.qty * r.unitPrice, 0); return { subtotalRs: totalRs, gstRs: 0, totalRs }; };

  // ── Catalog item mutations ─────────────────────────────────────────────────
  const addUniformItem    = () => setUniformForm((f) => ({ ...f, items: [...f.items, { name: '', sizeBreakdown: '', qty: 0, unitPrice: 0 }] }));
  const addNotebookType   = () => setNotebookForm((f) => ({ ...f, items: [...f.items, { type: '', size: 'A4', pages: '120', qty: 0, unitPrice: 0 }] }));
  const addStationeryItem = () => setStationeryForm((f) => ({ ...f, items: [...f.items, { name: '', brand: '', perKit: 1, unitPrice: 0 }] }));
  const addEventItem      = () => setEventsForm((f) => ({ ...f, items: [...f.items, { name: '', spec: '', qty: 0, unitPrice: 0 }] }));
  const uniformSummaryLines = () => [...uniformForm.items.filter((r) => (r.name || '').trim() || r.qty > 0 || r.unitPrice > 0).map((r) => ({ label: `${r.name || 'Item'} × ${r.qty || 0}`, value: (r.qty || 0) * (r.unitPrice || 0) })), { label: 'GST 5%', value: calcUniform().gstRs }];

  // ── API helpers — orders ───────────────────────────────────────────────────
  const markDesignApproved = async (orderId: string) => {
    try {
      await api.post(`/supply/orders/${orderId}/design-approved`);
      setCatalogNotice({ type: 'success', msg: `Order ${orderId} marked design approved and moved to superadmin review.` });
      await loadLiveOrders();
      if (user?.role === 'SUPERADMIN') await loadPendingApprovalOrders();
    } catch (e: any) {
      setCatalogNotice({ type: 'error', msg: e?.response?.data?.message || 'Failed to update design status.' });
    }
  };
  const loadLiveOrders = async () => { setOrdersLoading(true); try { const [ordRes, statsRes] = await Promise.all([api.get('/supply/orders', { params: schoolScopedParams }), api.get('/supply/orders/stats', { params: schoolScopedParams })]); setLiveOrders(ordRes.data); setLiveOrderStats(statsRes.data); } catch {} finally { setOrdersLoading(false); } };
  const loadPendingApprovalOrders = async () => {
    setPendingApprovalLoading(true);
    try {
      const res = await api.get('/supply/orders/pending-approval');
      setPendingApprovalOrders(Array.isArray(res.data) ? res.data : []);
    } catch (e: any) {
      setApprovalNotice({ type: 'error', msg: e?.response?.data?.message || 'Failed to load orders.' });
    } finally {
      setPendingApprovalLoading(false);
    }
  };
  const approveOrder = async (orderId: string) => {
    setApprovalActionSaving(orderId);
    setApprovalNotice(null);
    try {
      await api.post(`/supply/orders/${orderId}/superadmin-approve`);
      setApprovalNotice({ type: 'success', msg: `Order ${orderId} approved and marked for fulfilment.` });
      await loadPendingApprovalOrders();
    } catch (e: any) {
      setApprovalNotice({ type: 'error', msg: e?.response?.data?.message || 'Approval failed.' });
    } finally {
      setApprovalActionSaving('');
    }
  };
  const rejectOrder = async () => {
    if (!rejectModalOrderId) return;
    setApprovalActionSaving(rejectModalOrderId);
    setApprovalNotice(null);
    try {
      await api.post(`/supply/orders/${rejectModalOrderId}/superadmin-reject`, { reason: rejectReason || 'Rejected by Superadmin' });
      setApprovalNotice({ type: 'success', msg: `Order ${rejectModalOrderId} sent back for revision.` });
      setRejectModalOrderId(null);
      setRejectReason('');
      await loadPendingApprovalOrders();
    } catch (e: any) {
      setApprovalNotice({ type: 'error', msg: e?.response?.data?.message || 'Rejection failed.' });
    } finally {
      setApprovalActionSaving('');
    }
  };
  const submitCatalogOrder = async (category: string, orderData: object, calcs: { subtotalRs: number; gstRs: number; totalRs: number }, requiredByDate: string, place: boolean) => { setCatalogSaving(true); setCatalogNotice(null); try { const res = await api.post('/supply/orders', { category, orderData: JSON.stringify({ ...orderData, title: category }), subtotal: toPaise(calcs.subtotalRs), gst: toPaise(calcs.gstRs), totalAmount: toPaise(calcs.totalRs), requiredByDate: requiredByDate || null, status: 'DRAFT', ...(schoolScopedParams || {}) }); const orderId: string = res.data.id; if (place) { await api.post(`/supply/orders/${orderId}/place`); setCatalogNotice({ type: 'success', msg: category === 'UNIFORMS' ? 'Uniform order placed. It has moved to Design approval. After design approval it will go to superadmin for final approval.' : category === 'NOTEBOOKS' ? 'Notebook order placed. It has moved to Design approval. After design approval it will go to superadmin for final approval.' : category === 'STATIONERY' || category === 'EVENTS' ? 'Order placed. It is now in processing and pending superadmin approval.' : 'Order placed! You will receive a confirmation within 2 hours. Track it under My Orders.' }); setActiveCat(null); setPanel('orders'); loadLiveOrders(); } else { setCatalogNotice({ type: 'draft', msg: 'Draft saved.' }); window.setTimeout(() => setCatalogNotice(null), 3000); } } catch (e: any) { setCatalogNotice({ type: 'error', msg: e?.response?.data?.message || e?.message || 'Failed to save order.' }); } finally { setCatalogSaving(false); } };
  const catalogTiles = CATALOG_TILES;
  const orderRows = liveOrders ?? workspace?.orders ?? [];
  const orderStats = liveOrderStats;

  const currentTitle = user?.role === 'SUPERADMIN' && panel === 'orders' ? 'Supply order approvals' : PANEL_TITLES[panel];
  const navSections = user?.role === 'SUPERADMIN' ? SUPERADMIN_NAV_SECTIONS : user?.role === 'ZONE_ADMIN' ? ZONE_ADMIN_NAV_SECTIONS : user?.role === 'OPERATIONS' ? OPERATIONS_NAV_SECTIONS : ADMIN_NAV_SECTIONS;
  const isFire = panel.startsWith('ff-');
  const feeSummary = workspace?.fees?.summary ?? { progressPercent: 0, collected: 0, outstanding: 0, overdueCount: 0, target: 0 };
  const filteredFeeRecords = useMemo(() => {
    const records = workspace?.fees?.records || [];
    return records.filter((row: any) => (feeFilters.className === 'All' || row.className === feeFilters.className) && (feeFilters.sectionName === 'All' || row.sectionName === feeFilters.sectionName));
  }, [workspace, feeFilters]);
  const allFfReqs = useMemo(() => user?.role === 'SUPERADMIN' ? saFfRequests : (workspace?.firefighting?.requests || []), [user?.role, saFfRequests, workspace]);
  const firstApproval = useMemo(() => allFfReqs.find((r: any) => ['AWAITING_BURSAR', 'AWAITING_PRINCIPAL'].includes(r.status)) || null, [allFfReqs]);
  const ffPendingCount = useMemo(() => allFfReqs.filter((r: any) => ['AWAITING_BURSAR', 'AWAITING_PRINCIPAL'].includes(r.status)).length, [allFfReqs]);
  const saOrderCategoryOptions = useMemo(
    () => Array.from(new Set(saAllOrders.map((row: any) => String(row.category || '')).filter(Boolean))).sort(),
    [saAllOrders]
  );
  const saOrderStatusOptions = useMemo(
    () => Array.from(new Set(saAllOrders.map((row: any) => String(row.status || '')).filter(Boolean))).sort(),
    [saAllOrders]
  );
  const filteredSaAllOrders = useMemo(() => {
    const search = saOrderFilter.search.trim().toLowerCase();
    return saAllOrders.filter((row: any) => {
      const categoryOk = !saOrderFilter.cat || row.category === saOrderFilter.cat;
      const statusOk = !saOrderFilter.status || row.status === saOrderFilter.status;
      const text = `${row.id || ''} ${row.schoolName || ''}`.toLowerCase();
      const searchOk = !search || text.includes(search);
      return categoryOk && statusOk && searchOk;
    });
  }, [saAllOrders, saOrderFilter]);

  async function submitAction(action: string, request: Promise<any>, nextPanel?: PanelKey) {
    try {
      setSaving(action);
      await request;
      await refresh();
      if (nextPanel) setPanel(nextPanel);
    } finally {
      setSaving('');
    }
  }

  // ── Photo upload helpers ───────────────────────────────────────────────────
  function resetPhotoState() {
    setPhotoFile(null);
    setPhotoPreviewUrl('');
    setPhotoError('');
    setPhotoDragActive(false);
    setPhotoZoom(1);
    setPhotoOffsetX(0);
    setPhotoOffsetY(0);
    if (fileInputRef.current) fileInputRef.current.value = '';
  }

  function resetStudentForm() {
    setStudentForm({ admissionNumber: '', boardRegistrationNumber: '', fullName: '', dateOfBirth: '', gender: 'Male', gradeLevel: 'Class 9', sectionName: 'A', academicYear: '2025–26', admissionDate: '', houseNumber: '', street: '', locality: '', city: 'Hyderabad', state: 'Telangana', pinCode: '', fatherName: '', fatherContactNumber: '', paymentSchedule: 'Monthly', manualDiscountOverride: '0' });
    resetPhotoState();
  }

  function validateImageFile(file: File) {
    const allowed = ['image/jpeg', 'image/png', 'image/webp'];
    if (!allowed.includes(file.type)) {
      throw new Error('Only JPG, PNG, or WEBP files are allowed.');
    }
    if (file.size > 2 * 1024 * 1024) {
      throw new Error('Photo must be 2MB or smaller.');
    }
  }

  function selectPhoto(file: File) {
    try {
      validateImageFile(file);
      if (photoPreviewUrl) URL.revokeObjectURL(photoPreviewUrl);
      setPhotoFile(file);
      setPhotoPreviewUrl(URL.createObjectURL(file));
      setPhotoError('');
      setPhotoFeedback(null);
      setPhotoZoom(1);
      setPhotoOffsetX(0);
      setPhotoOffsetY(0);
    } catch (error: any) {
      setPhotoFile(null);
      setPhotoPreviewUrl('');
      setPhotoError(error?.message || 'Invalid photo file.');
    }
  }

  function handlePhotoDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault();
    setPhotoDragActive(false);
    const file = event.dataTransfer.files?.[0];
    if (file) selectPhoto(file);
  }

  async function createCroppedImageBlob() {
    if (!photoPreviewUrl || !photoFile) return null;
    const image = new Image();
    image.src = photoPreviewUrl;
    await new Promise((resolve, reject) => {
      image.onload = resolve;
      image.onerror = reject;
    });
    const canvas = document.createElement('canvas');
    canvas.width = 512;
    canvas.height = 512;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('Could not prepare the photo for upload.');
    context.fillStyle = '#ffffff';
    context.fillRect(0, 0, canvas.width, canvas.height);

    const baseScale = Math.max(canvas.width / image.width, canvas.height / image.height);
    const finalScale = baseScale * photoZoom;
    const drawWidth = image.width * finalScale;
    const drawHeight = image.height * finalScale;
    const x = (canvas.width - drawWidth) / 2 + photoOffsetX;
    const y = (canvas.height - drawHeight) / 2 + photoOffsetY;
    context.drawImage(image, x, y, drawWidth, drawHeight);

    const mimeType = photoFile.type === 'image/png' ? 'image/png' : photoFile.type === 'image/webp' ? 'image/webp' : 'image/jpeg';
    const blob: Blob | null = await new Promise((resolve) => canvas.toBlob(resolve, mimeType, 0.92));
    if (!blob) throw new Error('Could not generate cropped photo.');
    return blob;
  }

  // ── Student save / edit ───────────────────────────────────────────────────
  async function handleSaveStudent() {
    try {
      setSaving('student');
      setPhotoError('');
      setPhotoFeedback(null);
      const payload = { ...studentForm };
      const studentResponse = await api.post('/workspace/students', payload);
      const createdStudent = studentResponse.data?.student || studentResponse.data;

      if (photoFile) {
        const croppedBlob = await createCroppedImageBlob();
        if (!croppedBlob) throw new Error('Photo preview is not ready yet.');
        const extension = photoFile.name.split('.').pop() || (photoFile.type === 'image/png' ? 'png' : photoFile.type === 'image/webp' ? 'webp' : 'jpg');
        const uploadFile = new File([croppedBlob], `student-photo.${extension}`, { type: croppedBlob.type || photoFile.type });
        const formData = new FormData();
        formData.append('file', uploadFile);
        await api.post(`/students/${createdStudent.id}/photo`, formData, {
          headers: { 'Content-Type': 'multipart/form-data' }
        });
        setPhotoFeedback({ type: 'success', message: 'Student saved and photo uploaded successfully.' });
      } else {
        setPhotoFeedback({ type: 'success', message: 'Student saved successfully.' });
      }

      await refresh();
      resetStudentForm();
      setPanel('students');
    } catch (error: any) {
      const message = error?.response?.data?.message || error?.message || 'Unable to save student.';
      setPhotoFeedback({ type: 'error', message });
    } finally {
      setSaving('');
    }
  }


  async function openStudentModal(student: any) {
    try {
      setStudentModalOpen(true);
      setStudentModalLoading(true);
      const res = await api.get(`/students/${student.id}`);
      setStudentDetail(res.data);
    } catch {
      setStudentDetail(student);
    } finally {
      setStudentModalLoading(false);
    }
  }

  function populateStudentForm(student: any) {
    const address = student.address || {};
    setEditingStudentId(Number(student.id));
    setStudentForm({
      admissionNumber: student.admissionNumber || '',
      boardRegistrationNumber: student.boardRegistrationNumber || '',
      fullName: student.fullName || student.name || '',
      dateOfBirth: student.dateOfBirth || '',
      gender: student.gender || 'Male',
      gradeLevel: student.className || 'Class 9',
      sectionName: student.sectionName || 'A',
      academicYear: student.academicYear || '2025–26',
      admissionDate: student.joined || '',
      houseNumber: address.houseNumber || '',
      street: address.street || '',
      locality: address.locality || '',
      city: address.city || 'Hyderabad',
      state: address.state || 'Telangana',
      pinCode: address.pinCode || '',
      fatherName: student.fatherName || '',
      fatherContactNumber: student.fatherContact || student.parentPhone || '',
      paymentSchedule: 'Monthly',
      manualDiscountOverride: '0'
    });
    setPanel('addstudent');
    setStudentModalOpen(false);
  }

  // ── Render ─────────────────────────────────────────────────────────────────
  if (!workspace && workspaceError) {
    return (
      <div className="ck-loading" style={{ padding: '48px 24px', display: 'grid', gap: '12px', textAlign: 'center' }}>
        <div style={{ fontSize: '28px' }}>⚠️</div>
        <div style={{ fontFamily: 'Fraunces, serif', fontSize: '24px' }}>Workspace could not load</div>
        <div style={{ color: '#5a5a5a' }}>{workspaceError}</div>
        <div style={{ display: 'flex', gap: '12px', justifyContent: 'center', flexWrap: 'wrap' }}>
          <button className="ck-btn ck-btn-ghost" onClick={() => refresh()}>Retry</button>
          <button className="ck-btn ck-btn-primary" onClick={() => { logout(); navigate('/login', { replace: true }); }}>Back to login</button>
        </div>
      </div>
    );
  }

  if (!workspace) return <div className="ck-loading">Loading workspace…</div>;

  return (
    <div className="workspace-shell">
      <aside className="ck-sidebar">
        <div className="ck-sb-header">
          <div className="ck-sb-logo">custoking</div>
          <div className="ck-school-name">{workspace.school.name}</div>
          <div className="ck-school-meta">{workspace.school.meta}</div>
        </div>

        <nav className="ck-nav">
          {navSections.map((section) => (
            <div key={section.title}>
              {section.fire ? (
                <div className="ck-fire-header">
                  <div className="ck-fire-label">🔥 Firefighting</div>
                  <div className="ck-fire-sub">Non-catalog procurement</div>
                </div>
              ) : (
                <div className="ck-nav-section">{section.title}</div>
              )}
              {section.items.map((item) => (
                <button
                  key={item.key}
                  className={`ck-nav-item ${panel === item.key ? 'on' : ''} ${section.fire ? 'fire' : ''}`}
                  onClick={() => setPanel(item.key)}
                >
                  <span>{item.icon}</span>
                  <span>{item.label}</span>
                  {item.key === 'sa-invoices' && saInvBadge > 0 && (
                    <span style={{ marginLeft: 'auto', background: 'var(--re)', color: '#fff', fontSize: 9, fontWeight: 700, padding: '1px 6px', borderRadius: 8 }}>
                      {saInvBadge}
                    </span>
                  )}
                </button>
              ))}
              {!section.fire && <div className="ck-sep" />}
            </div>
          ))}
        </nav>

        <div className="ck-user-card">
          <div className="ck-nav-section" style={{ padding: 0, marginBottom: 8 }}>Signed in as</div>
          <div className="ck-user-name">{user?.fullName}</div>
          <div className="ck-user-meta">{user?.email}</div>
          <div className="ck-badge-row">
            <span className="ck-pill">{user?.role === 'SUPERADMIN' ? 'Super Admin' : user?.role === 'OPERATIONS' ? 'Operations' : 'Admin'}</span>
            <span className="ck-pill">{user?.branchName || 'Global'}</span>
          </div>
          <button className="ck-btn ck-btn-ghost" onClick={logout}>Logout</button>
        </div>
      </aside>

      <main className="ck-main">
        <div className="ck-topbar">
          <div className="ck-topbar-title">{currentTitle}</div>
          {user?.role === 'SUPERADMIN' && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginRight: 'auto', marginLeft: 12 }}>
              <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--g)', background: 'var(--g1)', padding: '4px 10px', borderRadius: 8 }}>
                Custoking Platform
              </span>
              <button className="ck-btn ck-btn-ghost" onClick={() => navigate('/schools')}>
                🏫 Manage schools
              </button>
            </div>
          )}
          {isFire ? (
            <button className="ck-btn ck-btn-or" onClick={() => setPanel('ff-new')}>+ New FF request</button>
          ) : null}
        </div>

        <div className="ck-content">
          {panel === 'home' && workspace && <HomePanel workspace={workspace} setPanel={setPanel} />}

          {panel === 'students' && <StudentsPanel setPanel={setPanel} onRefresh={refresh} />}

          {panel === 'fees' && <FeesPanel workspace={workspace} onRefresh={refresh} />}

          {panel === 'feestructure' && <FeeStructurePanel onRefresh={refresh} />}

          {panel === 'addstudent' && <AddStudentPanel setPanel={setPanel} onRefresh={refresh} />}

          {panel === 'bulkimport' && <BulkImportPanel onRefresh={refresh} schoolScopedParams={schoolScopedParams} />}

          {panel === 'attendance' && <AttendancePanel onRefresh={refresh} schoolScopedParams={schoolScopedParams} />}

          {panel === 'timetable' && workspace && <TimetablePanel workspace={workspace} onRefresh={refresh} />}

          {panel === 'staff' && workspace && <StaffPanel workspace={workspace} onRefresh={refresh} />}

          {panel === 'catalog' && <CatalogPanel setPanel={setPanel} />}

          {panel === 'orders' && user?.role === 'SUPERADMIN' && (
            <ModuleShell
              title="Supply order approvals"
              subtitle="Review admin orders pending superadmin approval — notebooks and uniforms after design approval, stationery and events from processing"
              actions={<button className="ck-btn ck-btn-ghost" onClick={loadPendingApprovalOrders}>↻ Refresh</button>}
            >
              {approvalNotice && (
                <div className={`ck-alert ${approvalNotice.type === 'error' ? 'ck-alert-re' : 'ck-alert-g'}`} style={{ marginBottom: 16 }}>
                  <span>{approvalNotice.type === 'error' ? '✕' : '✓'}</span>
                  <div>{approvalNotice.msg}</div>
                </div>
              )}
              <div className="ck-stats ck-s4" style={{ marginBottom: 16 }}>
                <Stat label="Awaiting approval" value={pendingApprovalOrders.length} sub="Orders in design-approved processing state" pill={pendingApprovalOrders.length > 0 ? 'Action needed' : 'All clear'} tone={pendingApprovalOrders.length > 0 ? 'orange' : 'green'} />
              </div>
              <div className="ck-card">
                {pendingApprovalLoading ? (
                  <div style={{ padding: 24, textAlign: 'center', color: 'var(--ink3)' }}>Loading orders…</div>
                ) : pendingApprovalOrders.length === 0 ? (
                  <div style={{ padding: 24, textAlign: 'center', color: 'var(--ink3)' }}>No orders awaiting approval. Uniform and notebook orders appear after design approval. Stationery and events orders appear from processing.</div>
                ) : (
                  <table className="ck-table">
                    <thead><tr><th>Order ID</th><th>School</th><th>Category</th><th>Description</th><th>Amount</th><th>Placed on</th><th style={{ textAlign: 'right' }}>Actions</th></tr></thead>
                    <tbody>
                      {pendingApprovalOrders.map((row: any) => (
                        <tr key={row.id}>
                          <td><div className="tb">{row.id}</div><div className="ts">{row.estimatedDelivery || '—'}</div></td>
                          <td><div className="tb">{row.schoolName}</div></td>
                          <td>{row.category}</td>
                          <td><div style={{ maxWidth: 220 }}>{row.description || row.title || row.category}</div><div className="ts">{row.items}</div></td>
                          <td style={{ fontWeight: 600 }}>₹{formatMoney(Math.round(Number(row.totalAmount ?? 0) / 100))}</td>
                          <td style={{ color: 'var(--ink3)' }}>{row.placedAt ? new Date(row.placedAt).toLocaleDateString('en-IN') : row.date || '—'}</td>
                          <td><div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
                            <button className="ck-btn ck-btn-g" disabled={approvalActionSaving === row.id} onClick={() => approveOrder(row.id)}>{approvalActionSaving === row.id ? 'Saving…' : '✓ Approve'}</button>
                            <button className="ck-btn ck-btn-ghost" disabled={approvalActionSaving === row.id} onClick={() => { setRejectModalOrderId(row.id); setRejectReason(''); }}>Return</button>
                          </div></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
              {rejectModalOrderId && (
                <div className="ck-modal-bg" onClick={() => setRejectModalOrderId(null)}>
                  <div className="ck-modal" onClick={(e) => e.stopPropagation()}>
                    <div className="ck-modal-h"><div className="ck-modal-title">Return order for revision</div><button className="ck-modal-x" onClick={() => setRejectModalOrderId(null)}>×</button></div>
                    <div className="ck-modal-body">
                      <p style={{ marginBottom: 12, color: 'var(--ink2)', fontSize: 13 }}>Order <strong>{rejectModalOrderId}</strong> will be returned to <em>Design approval</em> and the admin will be notified.</p>
                      <div className="field"><label>Reason for returning (shown to school admin)</label><textarea value={rejectReason} onChange={e => setRejectReason(e.target.value)} placeholder="e.g. Budget exceeded, missing specification details, please revise quantity…" rows={3} /></div>
                    </div>
                    <div className="ck-modal-foot"><button className="ck-btn ck-btn-ghost" onClick={() => setRejectModalOrderId(null)}>Cancel</button><button className="ck-btn ck-btn-g" disabled={approvalActionSaving === rejectModalOrderId} onClick={rejectOrder}>{approvalActionSaving === rejectModalOrderId ? 'Returning…' : 'Return to admin'}</button></div>
                  </div>
                </div>
              )}
            </ModuleShell>
          )}
          {panel === 'sa-all-orders' && user?.role === 'SUPERADMIN' && (<ModuleShell title="All orders" subtitle="All catalog orders across all schools" actions={<button className="ck-btn ck-btn-g" onClick={() => setPanel('sa-new-order')}>+ New order request</button>}><div className="ck-grid ck-grid-4" style={{ marginBottom: 16 }}><Stat label="Total orders" value={saOrderStats?.total ?? 0} sub="Across all schools" pill="Live" tone="blue" /><Stat label="New requests" value={saOrderStats?.newRequests ?? 0} sub="Awaiting approval" pill="Needs review" tone="orange" /><Stat label="In progress" value={saOrderStats?.inProgress ?? 0} sub="Approved or processing" pill="Active" tone="green" /><Stat label="GMV" value={`₹${formatMoney(Math.round(Number(saOrderStats?.gmv || 0) / 100))}`} sub="Gross merchandise value" pill="Paise→₹" tone="blue" /></div><div className="ck-form-card" style={{ marginBottom: 16 }}><div className="ck-form-body"><div className="ck-form-grid ck-fg-3"><Field label="Category"><select value={saOrderFilter.cat} onChange={(e) => setSaOrderFilter({ ...saOrderFilter, cat: e.target.value })}><option value="">All</option>{saOrderCategoryOptions.map((cat) => <option key={cat} value={cat}>{cat}</option>)}</select></Field><Field label="Status"><select value={saOrderFilter.status} onChange={(e) => setSaOrderFilter({ ...saOrderFilter, status: e.target.value })}><option value="">All</option>{saOrderStatusOptions.map((status) => <option key={status} value={status}>{status}</option>)}</select></Field><Field label="Search"><input value={saOrderFilter.search} onChange={(e) => setSaOrderFilter({ ...saOrderFilter, search: e.target.value })} placeholder="School or order ID" /></Field></div></div></div><div className="ck-card">{saAllOrdersLoading ? <div style={{ padding: 16 }}>Loading orders…</div> : saAllOrdersError ? <div style={{ padding: 16 }}>{saAllOrdersError}</div> : (<table className="ck-table"><thead><tr><th>Order</th><th>School</th><th>Category</th><th>Amount</th><th>Status</th><th>Placed</th><th /></tr></thead><tbody>{filteredSaAllOrders.length === 0 ? <tr><td colSpan={7}><div className="ts">No orders found.</div></td></tr> : filteredSaAllOrders.map((row: any) => (<tr key={row.id}><td><div className="tb">{row.id}</div><div className="ts">{row.description || row.title || row.category}</div></td><td>{row.schoolName || row.school || '—'}</td><td>{row.category}</td><td>₹{formatMoney(Math.round(Number(row.totalAmount ?? 0) / 100))}</td><td><span className={`ck-status ${String(row.status).includes('DELIVER') ? 'sg' : String(row.status).includes('APPROV') || String(row.status).includes('PROGRESS') ? 'sb2' : 'sam'}`}>{row.status}</span></td><td>{row.placedAt || row.createdAt || '—'}</td><td style={{ display: 'flex', gap: 8 }}><button className="ck-btn ck-btn-ghost" onClick={() => openSaOrderDetail(row.id)}>View</button>{String(row.status).toUpperCase() === 'AWAITING_APPROVAL' ? <button className="ck-btn ck-btn-g" onClick={() => acceptSaOrder(row.id)}>Accept</button> : <button className="ck-btn ck-btn-ghost" onClick={() => openSaInvoiceFromOrder(row.id, row.schoolName || row.school || '—', row.schoolId ?? null, Number(row.totalAmount || 0))}>Invoice</button>}</td></tr>))}</tbody></table>)}</div></ModuleShell>)}
          {panel === 'sa-new-order' && user?.role === 'SUPERADMIN' && (<ModuleShell title="New order request" subtitle="Select a category — each has a tailored intake form."><div className="sa-order-request">{saNewOrderNotice ? <div className="ck-alert ck-alert-g" style={{ marginBottom: 16 }}><span>✓</span><div>{saNewOrderNotice}</div></div> : null}{!saActiveCat ? <><div className="sa-category-label">Select supply category</div><div className="sa-category-grid">{SA_NEW_ORDER_CATEGORIES.map((item, idx) => <button key={`${item.title}-${idx}`} className="sa-category-card" onClick={() => { setSaActiveCat(item.key); setSaNewOrderErrors({}); }}><div className="sa-category-icon" aria-hidden="true">{item.icon}</div><div className="sa-category-title">{item.title}</div><div className="sa-category-desc">{item.desc}</div></button>)}</div></> : <div className="sa-order-form-card"><div className="sa-order-form-head"><button className="sa-order-back" onClick={() => { setSaActiveCat(null); setSaNewOrderErrors({}); }}>← Change</button>{saCategoryMeta ? <div className="sa-order-selected"><div className="sa-order-selected-icon">{saCategoryMeta.icon}</div><div><div className="sa-order-selected-title">{saCategoryMeta.title}</div><div className="sa-order-selected-desc">{saCategoryMeta.desc}</div></div></div> : null}</div>{saNewOrderErrors._api ? <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>✕</span><div>{saNewOrderErrors._api}</div></div> : null}<div className="ck-form-grid ck-fg-2"><Field label="School *" error={saNewOrderErrors.school}><select value={saNewOrderForm.schoolId || ''} onChange={(e) => setSaNewOrderForm({ ...saNewOrderForm, schoolId: Number(e.target.value) || '' })}><option value="">Select school</option>{saSchoolOptions.map((school: any) => <option key={school.id} value={school.id}>{school.name}</option>)}</select></Field><Field label="Notes"><input value={saNewOrderForm.notes || ''} onChange={(e) => setSaNewOrderForm({ ...saNewOrderForm, notes: e.target.value })} placeholder="Optional notes" /></Field>{saActiveCat === 'UNIFORMS' && <><Field label="Academic year *" error={saNewOrderErrors.academicYear}><input value={saNewOrderForm.academicYear || ''} onChange={(e) => setSaNewOrderForm({ ...saNewOrderForm, academicYear: e.target.value })} placeholder="2025-26" /></Field><Field label="Total units"><input type="number" min="0" value={saNewOrderForm.size_m || ''} onChange={(e) => setSaNewOrderForm({ ...saNewOrderForm, size_m: e.target.value })} placeholder="Enter total units" /></Field></>}{saActiveCat === 'NOTEBOOKS' && <><Field label="Academic year"><input value={saNewOrderForm.academicYear || ''} onChange={(e) => setSaNewOrderForm({ ...saNewOrderForm, academicYear: e.target.value })} placeholder="2025-26" /></Field><Field label="Delivery date *" error={saNewOrderErrors.deliveryDate}><input type="date" value={saNewOrderForm.deliveryDate || ''} onChange={(e) => setSaNewOrderForm({ ...saNewOrderForm, deliveryDate: e.target.value, notebookRows: [{ qty: saNewOrderForm.notebookQty || '' }] })} /></Field><Field label="Notebook qty"><input type="number" min="0" value={saNewOrderForm.notebookQty || ''} onChange={(e) => setSaNewOrderForm({ ...saNewOrderForm, notebookQty: e.target.value, notebookRows: [{ qty: e.target.value }] })} placeholder="Enter notebook count" /></Field></>}{saActiveCat === 'IDCARDS' && <><Field label="Card type *" error={saNewOrderErrors.cardType}><input value={saNewOrderForm.cardType || ''} onChange={(e) => setSaNewOrderForm({ ...saNewOrderForm, cardType: e.target.value })} placeholder="Student / Staff / Dual" /></Field><Field label="Delivery date *" error={saNewOrderErrors.deliveryDate}><input type="date" value={saNewOrderForm.deliveryDate || ''} onChange={(e) => setSaNewOrderForm({ ...saNewOrderForm, deliveryDate: e.target.value })} /></Field><Field label="Student count"><input type="number" min="0" value={saNewOrderForm.studentCount || ''} onChange={(e) => setSaNewOrderForm({ ...saNewOrderForm, studentCount: e.target.value })} /></Field><Field label="Staff count"><input type="number" min="0" value={saNewOrderForm.staffCount || ''} onChange={(e) => setSaNewOrderForm({ ...saNewOrderForm, staffCount: e.target.value })} /></Field></>}{saActiveCat === 'STATIONERY' && <Field label="Kit qty *" error={saNewOrderErrors.kitQty}><input type="number" min="0" value={saNewOrderForm.kitQty || ''} onChange={(e) => setSaNewOrderForm({ ...saNewOrderForm, kitQty: e.target.value })} placeholder="Enter kit quantity" /></Field>}{saActiveCat === 'HOUSEKEEPING' && <><Field label="Start date *" error={saNewOrderErrors.startDate}><input type="date" value={saNewOrderForm.startDate || ''} onChange={(e) => setSaNewOrderForm({ ...saNewOrderForm, startDate: e.target.value })} /></Field><Field label="Duration months *" error={saNewOrderErrors.duration}><input type="number" min="1" value={saNewOrderForm.duration || ''} onChange={(e) => setSaNewOrderForm({ ...saNewOrderForm, duration: e.target.value })} placeholder="12" /></Field><Field label="Monthly rate"><input type="number" min="0" value={saNewOrderForm.monthlyRate || ''} onChange={(e) => setSaNewOrderForm({ ...saNewOrderForm, monthlyRate: e.target.value })} placeholder="Enter monthly rate" /></Field></>}{saActiveCat === 'EVENTS' && <><Field label="Delivery date *" error={saNewOrderErrors.deliveryDate}><input type="date" value={saNewOrderForm.deliveryDate || ''} onChange={(e) => setSaNewOrderForm({ ...saNewOrderForm, deliveryDate: e.target.value })} /></Field><Field label="Item type"><select value={saEventItems[0]?.type || ''} onChange={(e) => setSaEventItems([{ ...(saEventItems[0] || { qty: '', notes: '' }), type: e.target.value }])}><option value="">Select</option>{Object.keys(EVENT_RATES).map((k) => <option key={k} value={k}>{k}</option>)}</select></Field><Field label="Qty"><input type="number" min="0" value={saEventItems[0]?.qty || ''} onChange={(e) => setSaEventItems([{ ...(saEventItems[0] || { type: '', notes: '' }), qty: e.target.value }])} /></Field></>}{saActiveCat === 'CUSTOM' && <><Field label="Description *" error={saNewOrderErrors.description}><textarea value={saNewOrderForm.description || ''} onChange={(e) => setSaNewOrderForm({ ...saNewOrderForm, description: e.target.value })} placeholder="Describe the requirement" /></Field><Field label="Budget"><input type="number" min="0" value={saNewOrderForm.budget || ''} onChange={(e) => setSaNewOrderForm({ ...saNewOrderForm, budget: e.target.value })} placeholder="Enter expected budget" /></Field></>}<div className="sa-order-summary"><span>Estimated value</span><strong>₹{formatMoney(computeSaOrderValue(saActiveCat, saNewOrderForm, saEventItems))}</strong></div></div><div className="ck-actions-inline" style={{ marginTop: 16 }}><button className="ck-btn ck-btn-ghost" disabled={saNewOrderSaving}>Save as draft</button><button className="ck-btn ck-btn-g" disabled={saNewOrderSaving} onClick={submitSaOrder}>{saNewOrderSaving ? 'Creating…' : 'Create order →'}</button></div></div>}</div></ModuleShell>)}
          {panel === 'sa-invoices' && user?.role === 'SUPERADMIN' && (<ModuleShell title="Invoices" subtitle="All superadmin invoices across schools" actions={<button className="ck-btn ck-btn-g" onClick={openBlankSaInvoice}>+ Create invoice</button>}><div className="ck-grid ck-grid-4" style={{ marginBottom: 16 }}><Stat label="Sent this month" value={saInvStats?.sentThisMonth ?? 0} sub="Invoices issued" pill="Current" tone="blue" /><Stat label="Paid" value={saInvStats?.paid ?? 0} sub="Settled invoices" pill="Received" tone="green" /><Stat label="Pending" value={saInvStats?.pending ?? 0} sub="Awaiting payment" pill="Action" tone="orange" /><Stat label="Total invoiced" value={`₹${formatMoney(Math.round(Number(saInvStats?.totalInvoiced || 0) / 100))}`} sub="Grand total" pill="Paise→₹" tone="blue" /></div><div className="ck-card">{saInvoicesLoading ? <div style={{ padding: 16 }}>Loading invoices…</div> : saInvoicesError ? <div style={{ padding: 16 }}>{saInvoicesError}</div> : <table className="ck-table"><thead><tr><th>Invoice</th><th>School</th><th>Order ref</th><th>Total</th><th>Status</th><th>Issued</th><th /></tr></thead><tbody>{saInvoices.length === 0 ? <tr><td colSpan={7}><div className="ts">No invoices found.</div></td></tr> : saInvoices.map((row: any) => <tr key={row.id}><td><div className="tb">{row.id}</div><div className="ts">{row.description || 'Invoice'}</div></td><td>{row.school || '—'}</td><td>{row.orderRef || '—'}</td><td>₹{formatMoney(Math.round(Number(row.total || 0) / 100))}</td><td><span className={`ck-status ${String(row.status).toLowerCase().includes('paid') ? 'sg' : 'sam'}`}>{row.status}</span></td><td>{row.issuedAt || '—'}</td><td style={{ display: 'flex', gap: 8 }}><button className="ck-btn ck-btn-ghost" onClick={() => openSaInvoiceView(row.id)}>View</button>{String(row.status).toLowerCase().includes('awaiting') ? <button className="ck-btn ck-btn-ghost" onClick={() => alert(`Invoice ${row.id} resent to ${row.school}`)}>Resend</button> : <button className="ck-btn ck-btn-ghost" onClick={() => alert(`Downloading ${row.id}.pdf`)}>Download</button>}</td></tr>)}</tbody></table>}</div></ModuleShell>)}
          {panel === 'sa-schools' && user?.role === 'SUPERADMIN' && (<ModuleShell title="School accounts" subtitle="All schools with admin and GMV stats" actions={<button className="ck-btn ck-btn-g" onClick={() => setSaOnboardOpen(true)}>+ Onboard school</button>}><div className="ck-card">{saSchoolsLoading ? <div style={{ padding: 16 }}>Loading schools…</div> : saSchoolsError ? <div style={{ padding: 16 }}>{saSchoolsError}</div> : <table className="ck-table"><thead><tr><th>School</th><th>Short code</th><th>City</th><th>Classes</th><th>Sections / class</th><th>Admin</th><th>Orders YTD</th><th>GMV YTD</th><th>ERP since</th></tr></thead><tbody>{saSchools.length === 0 ? <tr><td colSpan={9}><div className="ts">No schools found.</div></td></tr> : saSchools.map((school: any) => <tr key={school.id}><td><div className="tb">{school.name}</div><div className="ts">{school.active ? 'Active' : 'Inactive'}</div></td><td>{school.shortCode || '—'}</td><td>{school.city || '—'}</td><td>{school.configuredClassCount ?? '—'}</td><td>{school.configuredSectionCount ?? '—'}</td><td>{school.adminEmail || '—'}</td><td>{school.ordersYTD ?? 0}</td><td>₹{formatMoney(Math.round(Number(school.gmvYTD || 0) / 100))}</td><td>{school.erpSince || '—'}</td></tr>)}</tbody></table>}</div></ModuleShell>)}
          {panel === 'sa-erp' && user?.role === 'SUPERADMIN' && <SaErpPanel />}
          {panel === 'sa-revenue' && user?.role === 'SUPERADMIN' && <SaRevenuePanel />}
          {panel === 'sa-catalog' && user?.role === 'SUPERADMIN' && <SaCatalogPanel />}
          {panel === 'orders' && user?.role !== 'SUPERADMIN' && (<ModuleShell title="My orders" subtitle="All supply orders from Custoking — track, reorder, download invoices" actions={<button className="ck-btn ck-btn-g" onClick={() => setPanel('catalog')}>+ New order</button>}>{catalogNotice ? <div className={`ck-alert ${catalogNotice.type === 'error' ? 'ck-alert-re' : 'ck-alert-g'}`} style={{ marginBottom: 16 }}><span>{catalogNotice.type === 'error' ? '✕' : '✓'}</span><div>{catalogNotice.msg}</div></div> : null}<div className="ck-grid ck-grid-4" style={{ marginBottom: 16 }}><Stat label="Active orders" value={orderStats?.activeOrders ?? 0} sub="Awaiting, processing, transit" pill="Live" tone="blue" /><Stat label="Term spend" value={`₹${formatMoney(Math.round(Number(orderStats?.termSpend || 0) / 100))}`} sub="Placed this term" pill="Paise→₹" tone="green" /><Stat label="Active services" value={orderStats?.activeServices ?? 0} sub="Running contracts" pill="Service" tone="blue" /><Stat label="Delivered" value={orderStats?.deliveredCount ?? 0} sub="Completed orders" pill="Closed" tone="orange" /></div><div className="ck-card">{ordersLoading ? <div style={{ padding: 16 }}>Loading orders…</div> : <table className="ck-table"><thead><tr><th>Order</th><th>Category</th><th>Items</th><th>Amount</th><th>Status</th><th>Date</th><th /></tr></thead><tbody>{orderRows.map((row: any, i: number) => <tr key={i}><td><div className="tb">{row.id || row.code}</div><div className="ts">{row.description || row.title || row.category}</div></td><td>{row.category}</td><td>{row.items}</td><td>₹{formatMoney(Math.round(Number(row.totalAmount ?? row.amount ?? 0) / 100))}</td><td><span className={`ck-status ${String(row.status).toUpperCase() === 'DELIVERED' ? 'sg' : String(row.status).toUpperCase() === 'APPROVED' ? 'sg' : String(row.status).toUpperCase().includes('PROCESS') ? 'sb2' : String(row.status).toUpperCase().includes('DESIGN') ? 'sam' : 'sgr'}`}>{prettyOrderStatus(row.status)}</span></td><td>{row.placedAt || row.date || '—'}</td><td>{String(row.status).toUpperCase() === 'DESIGN_APPROVAL' ? <button className="ck-btn ck-btn-ghost" onClick={() => markDesignApproved(row.id)}>Mark design approved</button> : <button className="ck-btn ck-btn-ghost" onClick={async () => { await api.post('/supply/orders', { category: row.category, orderData: row.orderData || JSON.stringify({ title: row.description || row.category }), subtotal: row.subtotal || 0, gst: row.gst || 0, totalAmount: row.totalAmount || 0, requiredByDate: row.requiredByDate || null, status: 'DRAFT', ...(schoolScopedParams || {}) }); loadLiveOrders(); }}>Reorder</button>}</td></tr>)}</tbody></table>}</div></ModuleShell>)}

          {panel === 'planning' && workspace && <PlanningPanel workspace={workspace} onRefresh={refresh} />}

          {panel === 'ff-dashboard' && <FirefightingDashboardPanel isSuperAdmin={user?.role === 'SUPERADMIN'} adminRequests={workspace?.firefighting?.requests ?? []} setPanel={setPanel} onOpenFfDraft={(code) => { setFfEditingCode(code); setPanel('ff-new'); }} />}

          {panel === 'ff-new' && <FirefightingNewPanel editingCode={ffEditingCode} setPanel={setPanel} onRefresh={refresh} />}

          {panel === 'ff-approvals' && <FirefightingApprovalsPanel pendingRequests={workspace?.firefighting?.requests ?? []} onRefresh={refresh} />}

          {panel === 'ff-orders' && <FirefightingOrdersPanel isSuperAdmin={user?.role === 'SUPERADMIN'} adminRequests={workspace?.firefighting?.requests ?? []} onRefresh={refresh} />}
        </div>

        {saDetailOpen && (<div className="ck-modal-bg" onClick={() => setSaDetailOpen(false)}><div className="ck-modal" onClick={(e) => e.stopPropagation()}><div className="ck-modal-h"><div className="ck-modal-title">Order detail</div><button className="ck-modal-x" onClick={() => setSaDetailOpen(false)}>×</button></div><div className="ck-modal-body">{saDetailLoading ? <div className="ts">Loading order…</div> : saDetailError ? <div className="ts">{saDetailError}</div> : !saDetailOrder ? <div className="ts">Record not found.</div> : <><div className="ck-student-modal-info" style={{ marginBottom: 16 }}><Info label="Order ID" value={String(saDetailOrder.id || '—')} /><Info label="School" value={String(saDetailOrder.schoolName || saDetailOrder.school || '—')} /><Info label="Category" value={String(saDetailOrder.category || '—')} /><Info label="Amount" value={`₹${formatMoney(Math.round(Number(saDetailOrder.totalAmount || 0) / 100))}`} /><Info label="Delivery" value={String(saDetailOrder.estimatedDelivery || saDetailOrder.requiredByDate || '—')} /><Info label="Status" value={String(saDetailOrder.status || '—')} /></div><div className="ck-form-card"><div className="ck-form-head">Update status</div><div className="ck-form-body"><Field label="Status"><select value={saNewStatus} onChange={(e) => setSaNewStatus(e.target.value)}><option>AWAITING_APPROVAL</option><option>IN_PROGRESS</option><option>APPROVED</option><option>PROCESSING</option><option>DELIVERED</option></select></Field></div></div></>}</div><div className="ck-modal-foot"><button className="ck-btn ck-btn-ghost" onClick={() => saDetailOrder && openSaInvoiceFromOrder(saDetailOrder.id, saDetailOrder.schoolName || '—', saDetailOrder.schoolId ?? null, Number(saDetailOrder.totalAmount || 0))}>Generate invoice</button><button className="ck-btn ck-btn-ghost" onClick={() => alert(`WhatsApp sent to ${saDetailOrder?.schoolName || saDetailOrder?.school || 'school'}`)}>WhatsApp school</button><button className="ck-btn ck-btn-ghost" onClick={() => alert(`Downloading order sheet for ${saDetailOrder?.id || 'order'}`)}>Download order sheet</button><button className="ck-btn ck-btn-g" disabled={saStatusSaving} onClick={saveSaOrderStatus}>{saStatusSaving ? 'Saving…' : 'Update status'}</button></div></div></div>)}
        {saInvOpen && (<div className="ck-modal-bg" onClick={() => { setSaInvOpen(false); void loadSaInvoices(); }}><div className="ck-modal" onClick={(e) => e.stopPropagation()}><div className="ck-modal-h"><div className="ck-modal-title">Invoice</div><button className="ck-modal-x" onClick={() => { setSaInvOpen(false); void loadSaInvoices(); }}>×</button></div><div className="ck-modal-body">{saInvError ? <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>✕</span><div>{saInvError}</div></div> : null}<div className="ck-form-grid ck-fg-2"><Field label="Invoice number"><input value={saInvExistingId || 'Draft'} disabled /></Field><Field label="Bill to"><input value={saInvData.school || ''} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => setSaInvData({ ...saInvData, school: e.target.value })} /></Field><Field label="Order ref"><input value={saInvData.orderRef || ''} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => setSaInvData({ ...saInvData, orderRef: e.target.value })} /></Field><Field label="Description"><input value={saInvData.description || ''} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => setSaInvData({ ...saInvData, description: e.target.value })} /></Field><Field label="Qty"><input type="number" min="1" value={saInvData.qty || 1} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => { const qty = Number(e.target.value || 0); const rate = Number(saInvData.rate || 0); const amount = qty * rate; const gstAmount = Math.round(amount * 0.12); setSaInvData({ ...saInvData, qty, amount, gstAmount, total: amount + gstAmount }); }} /></Field><Field label="Rate (paise)"><input type="number" min="0" value={saInvData.rate || 0} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => { const rate = Number(e.target.value || 0); const qty = Number(saInvData.qty || 0); const amount = qty * rate; const gstAmount = Math.round(amount * 0.12); setSaInvData({ ...saInvData, rate, amount, gstAmount, total: amount + gstAmount }); }} /></Field><Field label="Status"><select value={saInvData.status || 'Awaiting payment'} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => setSaInvData({ ...saInvData, status: e.target.value })}><option>Awaiting payment</option><option>Paid</option></select></Field><Field label="Due date"><input type="date" value={saInvData.dueAt || ''} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => setSaInvData({ ...saInvData, dueAt: e.target.value })} /></Field></div><div className="ck-card" style={{ marginTop: 16 }}><div className="ck-form-body"><div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}><span>Subtotal</span><strong>₹{formatMoney(Math.round(Number(saInvData.amount || 0) / 100))}</strong></div><div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}><span>GST 12%</span><strong>₹{formatMoney(Math.round(Number(saInvData.gstAmount || 0) / 100))}</strong></div><div style={{ display: 'flex', justifyContent: 'space-between' }}><span>Total</span><strong>₹{formatMoney(Math.round(Number(saInvData.total || 0) / 100))}</strong></div></div></div></div><div className="ck-modal-foot"><button className="ck-btn ck-btn-ghost" onClick={() => alert(`Downloading ${(saInvExistingId || 'draft')}.pdf`)}>Download PDF</button>{saInvExistingId && !saInvEditing ? <button className="ck-btn ck-btn-ghost" onClick={() => setSaInvEditing(true)}>Edit invoice</button> : null}{saInvExistingId && saInvEditing ? <button className="ck-btn ck-btn-ghost" disabled={saInvSaving} onClick={saveSaInvoiceEdit}>{saInvSaving ? 'Saving…' : 'Save changes'}</button> : null}<button className="ck-btn ck-btn-g" disabled={saInvSaving} onClick={sendSaInvoice}>{saInvSaving ? 'Sending…' : 'Send to school'}</button></div></div></div>)}
        {saOnboardOpen && (<div className="ck-modal-bg" onClick={() => setSaOnboardOpen(false)}><div className="ck-modal" onClick={(e) => e.stopPropagation()}><div className="ck-modal-h"><div className="ck-modal-title">Onboard school</div><button className="ck-modal-x" onClick={() => setSaOnboardOpen(false)}>×</button></div><div className="ck-modal-body">{saOnboardErrors._ ? <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>✕</span><div>{saOnboardErrors._}</div></div> : null}<div className="ck-form-grid ck-fg-2"><Field label="School name *"><input value={saOnboardForm.name} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, name: e.target.value })} /></Field><Field label="Short code *"><input value={saOnboardForm.shortCode} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, shortCode: e.target.value })} /></Field><Field label="City *"><input value={saOnboardForm.city} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, city: e.target.value })} /></Field><Field label="State"><input value={saOnboardForm.state} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, state: e.target.value })} /></Field><Field label="No. of classes *"><input type="number" min={1} max={12} value={saOnboardForm.classCount} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, classCount: e.target.value })} />{saOnboardErrors.classCount ? <div className="ts" style={{ color: 'var(--re)', marginTop: 6 }}>{saOnboardErrors.classCount}</div> : <div className="ts" style={{ marginTop: 6 }}>Creates classes 1 to {saOnboardForm.classCount || 12}</div>}</Field><Field label="Sections per class *"><input type="number" min={1} max={26} value={saOnboardForm.sectionCount} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, sectionCount: e.target.value })} />{saOnboardErrors.sectionCount ? <div className="ts" style={{ color: 'var(--re)', marginTop: 6 }}>{saOnboardErrors.sectionCount}</div> : <div className="ts" style={{ marginTop: 6 }}>Creates sections A to {String.fromCharCode(64 + Math.max(1, Math.min(26, Number(saOnboardForm.sectionCount || 2))))}</div>}</Field><Field label="Contact email"><input value={saOnboardForm.contactEmail} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, contactEmail: e.target.value })} /></Field><Field label="Contact phone"><input value={saOnboardForm.contactPhone} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, contactPhone: e.target.value })} /></Field></div></div><div className="ck-modal-foot"><button className="ck-btn ck-btn-ghost" onClick={() => setSaOnboardOpen(false)}>Cancel</button><button className="ck-btn ck-btn-g" disabled={saOnboardSaving} onClick={submitSaOnboard}>{saOnboardSaving ? 'Saving…' : 'Create school'}</button></div></div></div>)}

      </main>
    </div>
  );
}

