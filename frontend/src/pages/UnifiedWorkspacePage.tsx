import { DragEvent, type CSSProperties, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../services/api';
import { useAuth } from '../contexts/AuthContext';

type WorkspaceData = any;
type PanelKey =
  | 'home' | 'students' | 'fees' | 'feestructure' | 'attendance' | 'timetable'
  | 'addstudent' | 'bulkimport' | 'staff' | 'catalog' | 'orders' | 'planning'
  | 'ff-dashboard' | 'ff-new' | 'ff-approvals' | 'ff-orders'
  | 'sa-all-orders' | 'sa-new-order' | 'sa-invoices'
  | 'sa-schools' | 'sa-erp' | 'sa-revenue' | 'sa-catalog';

const ADMIN_NAV_SECTIONS: Array<{ title: string; fire?: boolean; items: Array<{ key: PanelKey; label: string; icon: string }> }> = [
  {
    title: 'School ERP',
    items: [
      { key: 'home', label: 'Dashboard', icon: '◼' },
      { key: 'students', label: 'Students', icon: '🎓' },
      { key: 'fees', label: 'Fee collection', icon: '₹' },
      { key: 'feestructure', label: 'Fee structure', icon: '📐' },
      { key: 'attendance', label: 'Attendance', icon: '✓' },
      { key: 'timetable', label: 'Timetable', icon: '📅' },
      { key: 'addstudent', label: 'Add student', icon: '➕' },
      { key: 'bulkimport', label: 'Bulk import', icon: '📥' },
      { key: 'staff', label: 'Staff & HR', icon: '👥' }
    ]
  },
  {
    title: 'Supply OS',
    items: [
      { key: 'catalog', label: 'Catalog', icon: '⊞' },
      { key: 'orders', label: 'My orders', icon: '📦' },
      { key: 'planning', label: 'Annual plan', icon: '🗓' }
    ]
  },
  {
    title: 'Firefighting',
    fire: true,
    items: [
      { key: 'ff-dashboard', label: 'All requests', icon: '📋' },
      { key: 'ff-new', label: 'New request', icon: '➕' },
      { key: 'ff-approvals', label: 'Approvals', icon: '✅' },
      { key: 'ff-orders', label: 'Placed orders', icon: '📦' }
    ]
  }
];

const SUPERADMIN_NAV_SECTIONS: Array<{ title: string; fire?: boolean; items: Array<{ key: PanelKey; label: string; icon: string }> }> = [
  {
    title: 'Operations',
    items: [
      { key: 'orders', label: 'Order approvals', icon: '📦' },
      { key: 'sa-all-orders', label: 'All orders', icon: '📋' },
      { key: 'sa-new-order', label: 'New order request', icon: '✏️' },
      { key: 'sa-invoices', label: 'Invoices', icon: '🧾' }
    ]
  },
  {
    title: 'Schools',
    items: [
      { key: 'sa-schools', label: 'School accounts', icon: '🏫' },
      { key: 'sa-erp', label: 'ERP activity', icon: '📊' }
    ]
  },
  {
    title: 'Analytics',
    items: [
      { key: 'sa-revenue', label: 'Revenue', icon: '₹' },
      { key: 'sa-catalog', label: 'Catalog mgmt', icon: '📋' }
    ]
  }
];

const PANEL_TITLES: Record<PanelKey, string> = {
  home: 'Dashboard',
  students: 'Students',
  fees: 'Fee collection',
  feestructure: 'Fee structure',
  attendance: 'Attendance',
  timetable: 'Timetable',
  addstudent: 'Add student',
  bulkimport: 'Bulk import',
  staff: 'Staff & HR',
  catalog: 'Catalog',
  orders: 'My orders',
  planning: 'Annual plan',
  'ff-dashboard': '🔥 Firefighting — requests',
  'ff-new': '🔥 New request',
  'ff-approvals': '🔥 Pending approvals',
  'ff-orders': '🔥 Placed orders',
  'sa-all-orders': 'All orders',
  'sa-new-order': 'New order request',
  'sa-invoices': 'Invoices',
  'sa-schools': 'School accounts',
  'sa-erp': 'ERP activity',
  'sa-revenue': 'Revenue',
  'sa-catalog': 'Catalog management'
};

export default function UnifiedWorkspacePage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [workspace, setWorkspace] = useState<WorkspaceData | null>(null);
  const [workspaceError, setWorkspaceError] = useState('');
  const [panel, setPanel] = useState<PanelKey>(user?.role === 'SUPERADMIN' ? 'orders' : 'home');
  const [saving, setSaving] = useState<string>('');
  const [studentForm, setStudentForm] = useState({ admissionNumber: '', boardRegistrationNumber: '', fullName: '', dateOfBirth: '', gender: 'Male', gradeLevel: 'Class 9', sectionName: 'A', academicYear: '2025–26', admissionDate: '', houseNumber: '', street: '', locality: '', city: 'Hyderabad', state: 'Telangana', pinCode: '', fatherName: '', fatherContactNumber: '', paymentSchedule: 'Monthly', manualDiscountOverride: '0' });
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
  const [attendanceSummary, setAttendanceSummary] = useState<any>({ dateLabel: '', overallPercent: 0, sections: [], allSubmitted: false, nonWorkingDay: false });
  const [attendanceFilters, setAttendanceFilters] = useState({ date: todayIso(), classId: '', sectionId: '' });
  const [attendanceClassOptions, setAttendanceClassOptions] = useState<any[]>([]);
  const [attendanceSectionOptions, setAttendanceSectionOptions] = useState<any[]>([]);
  const [attendanceSectionInfo, setAttendanceSectionInfo] = useState<any | null>(null);
  const [attendancePresentCount, setAttendancePresentCount] = useState('');
  const [attendanceSaveError, setAttendanceSaveError] = useState('');
  const [attendanceToast, setAttendanceToast] = useState('');
  const [timetableForm, setTimetableForm] = useState({ day: 'Monday', period: 'P6', classSection: '9-B', subject: '', teacher: '' });
  const [staffForm, setStaffForm] = useState({ name: '', designation: '', department: '', monthlySalary: '42000', payrollStatus: 'Pending' });
  const [orderForm, setOrderForm] = useState({ category: 'Uniforms', title: 'Class order', items: '100 units', amount: '100000', status: 'In transit' });
  const [planForm, setPlanForm] = useState({ term: 'Term 1', category: 'Stationery', quantity: '200 units', amount: '38000', status: 'Planned' });
  const [ffForm, setFfForm] = useState({ title: '', category: 'Furniture & fixtures', amount: '250000', quotesCount: '2', winner: 'Custoking', summary: '' });
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [photoFile, setPhotoFile] = useState<File | null>(null);
  const [photoPreviewUrl, setPhotoPreviewUrl] = useState('');
  const [photoError, setPhotoError] = useState('');
  const [photoFeedback, setPhotoFeedback] = useState<{ type: 'success' | 'error'; message: string } | null>(null);
  const [photoDragActive, setPhotoDragActive] = useState(false);
  const [photoZoom, setPhotoZoom] = useState(1);
  const [photoOffsetX, setPhotoOffsetX] = useState(0);
  const [photoOffsetY, setPhotoOffsetY] = useState(0);
  const bulkImportInputRef = useRef<HTMLInputElement | null>(null);
  const [bulkImportDragActive, setBulkImportDragActive] = useState(false);
  const [bulkImportError, setBulkImportError] = useState('');
  const [bulkImportWarning, setBulkImportWarning] = useState('');
  const [bulkImportFileName, setBulkImportFileName] = useState('');
  const [bulkImportPreview, setBulkImportPreview] = useState<any | null>(null);
  const [bulkImportProgress, setBulkImportProgress] = useState<any | null>(null);
  const [bulkImportToast, setBulkImportToast] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

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
  const [liveOrders, setLiveOrders] = useState<any[] | null>(null);
  const [liveOrderStats, setLiveOrderStats] = useState<any | null>(null);
  const [ordersLoading, setOrdersLoading] = useState(false);
  const [pendingApprovalOrders, setPendingApprovalOrders] = useState<any[]>([]);
  const [pendingApprovalLoading, setPendingApprovalLoading] = useState(false);
  const [approvalActionSaving, setApprovalActionSaving] = useState<string>('');
  const [approvalNotice, setApprovalNotice] = useState<{ type: 'success' | 'error'; msg: string } | null>(null);
  const [rejectModalOrderId, setRejectModalOrderId] = useState<string | null>(null);
  const [rejectReason, setRejectReason] = useState('');

// SA — all orders
const [saAllOrders, setSaAllOrders] = useState<any[]>([]);
const [saAllOrdersLoading, setSaAllOrdersLoading] = useState(false);
const [saAllOrdersError, setSaAllOrdersError] = useState('');
const [saOrderStats, setSaOrderStats] = useState<any>(null);
const [saOrderFilter, setSaOrderFilter] = useState({ cat: '', status: '', search: '' });

// SA — order detail modal
const [saDetailOpen, setSaDetailOpen] = useState(false);
const [saDetailOrder, setSaDetailOrder] = useState<any | null>(null);
const [saDetailLoading, setSaDetailLoading] = useState(false);
const [saDetailError, setSaDetailError] = useState('');
const [saNewStatus, setSaNewStatus] = useState('');
const [saStatusSaving, setSaStatusSaving] = useState(false);

// SA — new order
const [saActiveCat, setSaActiveCat] = useState<string | null>(null);
const [saNewOrderForm, setSaNewOrderForm] = useState<any>({});
const [saNewOrderErrors, setSaNewOrderErrors] = useState<Record<string, string>>({});
const [saNewOrderSaving, setSaNewOrderSaving] = useState(false);
const [saNewOrderNotice, setSaNewOrderNotice] = useState('');
const [saEventItems, setSaEventItems] = useState<Array<{ type: string; qty: string; notes: string }>>([]);
const [saSchoolOptions, setSaSchoolOptions] = useState<any[]>([]);

// SA — invoices
const [saInvoices, setSaInvoices] = useState<any[]>([]);
const [saInvoicesLoading, setSaInvoicesLoading] = useState(false);
const [saInvoicesError, setSaInvoicesError] = useState('');
const [saInvStats, setSaInvStats] = useState<any>(null);
const [saInvBadge, setSaInvBadge] = useState(0);

// SA — invoice modal
const [saInvOpen, setSaInvOpen] = useState(false);
const [saInvData, setSaInvData] = useState<any>({});
const [saInvEditing, setSaInvEditing] = useState(false);
const [saInvSaving, setSaInvSaving] = useState(false);
const [saInvError, setSaInvError] = useState('');
const [saInvExistingId, setSaInvExistingId] = useState<string | null>(null);

// SA — schools
const [saSchools, setSaSchools] = useState<any[]>([]);
const [saSchoolsLoading, setSaSchoolsLoading] = useState(false);
const [saSchoolsError, setSaSchoolsError] = useState('');
const [saOnboardOpen, setSaOnboardOpen] = useState(false);
const [saOnboardForm, setSaOnboardForm] = useState({ name: '', shortCode: '', city: '', state: '', contactEmail: '', contactPhone: '', classCount: '12', sectionCount: '2' });
const [saOnboardErrors, setSaOnboardErrors] = useState<Record<string, string>>({});
const [saOnboardSaving, setSaOnboardSaving] = useState(false);


  const schoolScopedParams = undefined;

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
    const adminOnlyPanels: PanelKey[] = ['home', 'students', 'fees', 'feestructure', 'attendance', 'timetable', 'addstudent', 'bulkimport', 'staff', 'catalog', 'planning', 'ff-dashboard', 'ff-new', 'ff-approvals', 'ff-orders'];
    if (adminOnlyPanels.includes(panel)) setPanel('orders');
  }, [user?.role, panel]);

  useEffect(() => {
    if (workspace && panel === 'students') {
      loadStudents();
    }
  }, [workspace, panel, studentFilters]);

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
  const EVENT_RATES: Record<string, number> = { Trophy: 500, Medal: 150, Certificate: 20, 'Banner/Backdrop': 800, Standee: 600, Brochure: 15 };
  const SA_NEW_ORDER_CATEGORIES = [
    { key: 'UNIFORMS', icon: '👕', title: 'Uniforms & apparel', desc: 'Sets, PE kits, alterations' },
    { key: 'NOTEBOOKS', icon: '📘', title: 'Notebooks & books', desc: 'Ruled, practice, custom' },
    { key: 'IDCARDS', icon: '🪪', title: 'ID cards & lanyards', desc: 'PVC, photo, QR/barcode' },
    { key: 'STATIONERY', icon: '✏️', title: 'Stationery kits', desc: 'Pens, pencils, geometry' },
    { key: 'HOUSEKEEPING', icon: '🧹', title: 'Housekeeping', desc: 'Daily, weekly, AMC' },
    { key: 'CUSTOM', icon: '🍱', title: 'Food & canteen', desc: 'Canteen mgmt, pantry' },
    { key: 'EVENTS', icon: '🎉', title: 'Events & print', desc: 'Trophies, banners, certs' },
    { key: 'CUSTOM', icon: '💬', title: 'Custom / other', desc: 'Anything not listed' },
  ] as const;
  const saCategoryMeta = SA_NEW_ORDER_CATEGORIES.find((item) => item.key === saActiveCat) || null;

  const toPaise = (rupees: number) => Math.round(rupees * 100);
  const calcUniform = () => { const subtotalRs = uniformForm.items.reduce((s, r) => s + r.qty * r.unitPrice, 0); const gstRs = Math.round(subtotalRs * 0.05); return { subtotalRs, gstRs, totalRs: subtotalRs + gstRs }; };
  const calcNotebook = () => { const subtotalRs = notebookForm.items.reduce((s, r) => s + r.qty * r.unitPrice, 0); const gstRs = Math.round(subtotalRs * 0.12); return { subtotalRs, gstRs, totalRs: subtotalRs + gstRs }; };
  const calcStationery = () => { const kitCost = stationeryForm.items.reduce((s, r) => s + r.perKit * r.unitPrice, 0); const subtotalRs = kitCost * stationeryForm.numKits; const gstRs = Math.round(subtotalRs * 0.12); return { subtotalRs, gstRs, totalRs: subtotalRs + gstRs }; };
  const calcIdCard = () => { const total = idCardForm.studentCount + idCardForm.staffCount + idCardForm.spareCards; const cardCost = total * 30; const lanyardCost = idCardForm.lanyardIncluded.startsWith('Yes') ? total * 8 : 0; const subtotalRs = cardCost + lanyardCost; const gstRs = Math.round(subtotalRs * 0.18); return { subtotalRs, gstRs, totalRs: subtotalRs + gstRs, total }; };
  const calcHousekeeping = () => { const months = ({ '1 month': 1, '3 months': 3, '6 months': 6, 'Academic year': 10 } as Record<string, number>)[housekeepingForm.duration] ?? 3; const subtotalRs = housekeepingForm.staffRequired * months * 9000; return { subtotalRs, gstRs: 0, totalRs: subtotalRs, months }; };
  const calcEvents = () => { const subtotalRs = eventsForm.items.reduce((s, r) => s + r.qty * r.unitPrice, 0); return { subtotalRs, gstRs: 0, totalRs: subtotalRs }; };
  const calcHealth = () => { const totalRs = healthForm.items.reduce((s, r) => s + r.qty * r.unitPrice, 0); return { subtotalRs: totalRs, gstRs: 0, totalRs }; };
  const addUniformItem = () => setUniformForm((f) => ({ ...f, items: [...f.items, { name: '', sizeBreakdown: '', qty: 0, unitPrice: 0 }] }));
  const addNotebookType = () => setNotebookForm((f) => ({ ...f, items: [...f.items, { type: '', size: 'A4', pages: '120', qty: 0, unitPrice: 0 }] }));
  const addStationeryItem = () => setStationeryForm((f) => ({ ...f, items: [...f.items, { name: '', brand: '', perKit: 1, unitPrice: 0 }] }));
  const addEventItem = () => setEventsForm((f) => ({ ...f, items: [...f.items, { name: '', spec: '', qty: 0, unitPrice: 0 }] }));
  const uniformSummaryLines = () => [...uniformForm.items.filter((r) => (r.name || '').trim() || r.qty > 0 || r.unitPrice > 0).map((r) => ({ label: `${r.name || 'Item'} × ${r.qty || 0}`, value: (r.qty || 0) * (r.unitPrice || 0) })), { label: 'GST 5%', value: calcUniform().gstRs }];
  const prettyOrderStatus = (status?: string) => {
    const value = String(status || '').toUpperCase();
    if (value === 'DESIGN_APPROVAL') return 'Design approval';
    if (value === 'DESIGN_APPROVED_PROCESSING') return 'Design approved · Order in processing';
    if (value === 'AWAITING_APPROVAL') return 'Awaiting approval';
    if (value === 'PROCESSING') return 'Processing';
    if (value === 'APPROVED') return 'Approved';
    return value.replace(/_/g, ' ');
  };
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
  const catalogTiles = [{ key: 'UNIFORMS', emoji: '👕', name: 'Uniforms & apparel', desc: 'Shirts, pants, PE kit, blazers, ties, shoes', pill: 'Recurring', pillClass: 'pg', headerBg: 'var(--g1)', imgQ: 'school+uniform' }, { key: 'NOTEBOOKS', emoji: '📓', name: 'Notebooks', desc: 'A4/A5 ruled, plain, graph, school diary/planner', pill: 'Recurring', pillClass: 'pg', headerBg: 'var(--b1)', imgQ: 'notebook+school' }, { key: 'STATIONERY', emoji: '🖊', name: 'Stationery', desc: 'Pens, pencils, erasers, rulers, craft supplies', pill: 'Recurring', pillClass: 'pg', headerBg: 'var(--pu1)', imgQ: 'stationery+pens' }, { key: 'IDCARDS', emoji: '🪪', name: 'ID cards', desc: 'PVC photo ID, lanyards, QR / barcode', pill: 'One-time', pillClass: 'pam', headerBg: 'var(--am1)', imgQ: 'identity+card' }, { key: 'HOUSEKEEPING', emoji: '🧹', name: 'Housekeeping', desc: 'Daily / weekly cleaning contracts, supplies', pill: 'Service', pillClass: 'pb', headerBg: '#e1f5ee', imgQ: 'cleaning+school' }, { key: 'EVENTS', emoji: '🏆', name: 'Events & print', desc: 'Trophies, certificates, banners, backdrops', pill: 'One-time', pillClass: 'pam', headerBg: '#fdecea', imgQ: 'school+trophy+event' }, { key: 'HEALTH', emoji: '🩺', name: 'Health & safety', desc: 'First aid, sanitizers, fire equipment, PPE', pill: 'Recurring', pillClass: 'pg', headerBg: 'var(--re1)', imgQ: 'first+aid+medical' }];
  const orderRows = liveOrders ?? workspace?.orders ?? [];
  const orderStats = liveOrderStats;

  const currentTitle = user?.role === 'SUPERADMIN' && panel === 'orders' ? 'Supply order approvals' : PANEL_TITLES[panel];
  const navSections = user?.role === 'SUPERADMIN' ? SUPERADMIN_NAV_SECTIONS : ADMIN_NAV_SECTIONS;
  const isFire = panel.startsWith('ff-');
  const feeSummary = workspace?.fees?.summary ?? { progressPercent: 0, collected: 0, outstanding: 0, overdueCount: 0, target: 0 };
  const filteredFeeRecords = useMemo(() => {
    const records = workspace?.fees?.records || [];
    return records.filter((row: any) => (feeFilters.className === 'All' || row.className === feeFilters.className) && (feeFilters.sectionName === 'All' || row.sectionName === feeFilters.sectionName));
  }, [workspace, feeFilters]);
  const firstApproval = useMemo(() => (workspace?.firefighting?.requests || []).find((r: any) => r.status === 'AWAITING_APPROVAL') || null, [workspace]);
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
            <span className="ck-pill">{user?.role === 'SUPERADMIN' ? 'Super Admin' : 'Admin'}</span>
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
          {panel === 'home' && (
            <>
              <div className="ck-alert ck-alert-am"><span>⚠</span><div><strong>{workspace.dashboard.feeOverdueCount} students</strong> have overdue fees — Term 2 deadline is Jan 31. <button className="ck-inline-link" onClick={() => setPanel('fees')}>Review →</button></div></div>
              <div className="ck-stats ck-s4">
                <Stat label="Students" value={workspace.dashboard.students} sub={`${workspace.dashboard.sections} sections`} pill="+3 this month" tone="blue" onClick={() => setPanel('students')} />
                <Stat label="Today's attendance" value={`${workspace.dashboard.attendancePercent}%`} sub={`${workspace.dashboard.attendancePresent} / ${workspace.dashboard.students} present`} pill="Marked ✓" tone="green" onClick={() => setPanel('attendance')} />
                <Stat label="Fees collected" value={`₹${workspace.dashboard.feeCollectedLakh}L`} sub={`of ₹${workspace.dashboard.feeTargetLakh}L this term`} pill={`${workspace.dashboard.feeOverdueCount} overdue`} tone="red" onClick={() => setPanel('fees')} />
                <Stat label="Firefighting" value={workspace.dashboard.firefightingActive} sub="Active requests" pill={`${workspace.dashboard.pendingApprovals} need approval`} tone="orange" onClick={() => setPanel('ff-dashboard')} />
              </div>
              <div className="ck-two-col">
                <div className="ck-card">
                  <div className="ck-card-h"><div className="ck-card-t">Recent activity</div><div className="ck-card-a">See all</div></div>
                  {workspace.recentActivity.map((item: any, index: number) => (
                    <div className="ck-act-row" key={index}>
                      <div className="ck-act-icon">{item.icon}</div>
                      <div className="ck-act-info"><div className="ck-act-name">{item.title}</div><div className="ck-act-meta">{item.meta}</div></div>
                      <span className={`ck-status ${item.tagClass || 'sg'}`}>{item.tag}</span>
                    </div>
                  ))}
                </div>
                <div className="ck-card">
                  <div className="ck-card-h"><div className="ck-card-t">Action center</div></div>
                  <div className="ck-list-block">
                    <button className="ck-cta-row" onClick={() => setPanel('addstudent')}><strong>Enroll a student</strong><span>Create admission and auto-generate fee dues</span></button>
                    <button className="ck-cta-row" onClick={() => setPanel('catalog')}><strong>Place a supply order</strong><span>Use the catalog for uniforms, notebooks and more</span></button>
                    <button className="ck-cta-row" onClick={() => setPanel('ff-approvals')}><strong>Review firefighting approvals</strong><span>{workspace.dashboard.pendingApprovals} requests awaiting decision</span></button>
                    <button className="ck-cta-row" onClick={() => setPanel('planning')}><strong>Update the annual plan</strong><span>Lock in pricing and quantities for the next term</span></button>
                  </div>
                </div>
              </div>
            </>
          )}

          {panel === 'students' && (
            <ModuleShell title="Students" subtitle={`${studentsView.filteredCount || 0} enrolled · ${studentsView.filteredSections || 0} sections · Academic year 2024–25`} actions={<><button className="ck-btn ck-btn-ghost" onClick={() => setPanel('bulkimport')}>Bulk import</button><button className="ck-btn ck-btn-g" onClick={() => { setEditingStudentId(null); resetStudentForm(); setPanel('addstudent'); }}>+ Add student</button></>}>
              {photoFeedback ? <div className={`ck-alert ${photoFeedback.type === 'success' ? 'ck-alert-g' : 'ck-alert-re'}`}><span>{photoFeedback.type === 'success' ? '✓' : '!'}</span><div>{photoFeedback.message}</div></div> : null}
              <div className="ck-form-card" style={{ marginBottom: 16 }}>
                <div className="ck-form-body">
                  <div className="ck-form-grid ck-fg-4">
                    <Field label="Class"><select value={studentFilters.className} onChange={(e) => setStudentFilters({ ...studentFilters, className: e.target.value })}><option>All</option>{(studentsView.filters?.classes || []).map((value: string) => <option key={value} value={value}>{value}</option>)}</select></Field>
                    <Field label="Section"><select value={studentFilters.sectionName} onChange={(e) => setStudentFilters({ ...studentFilters, sectionName: e.target.value })}><option>All</option>{(studentsView.filters?.sections || []).map((value: string) => <option key={value} value={value}>{value}</option>)}</select></Field>
                    <Field label="Fee Status"><select value={studentFilters.feeStatus} onChange={(e) => setStudentFilters({ ...studentFilters, feeStatus: e.target.value })}><option>All</option><option>Paid</option><option>Overdue</option><option>Pending</option><option>Partial</option></select></Field>
                  </div>
                </div>
              </div>
              <div className="ck-card">
                <table className="ck-table"><thead><tr><th>Student</th><th>Admission / Roll</th><th>Father name</th><th>Father contact</th><th>Fee status</th><th>Attendance</th><th /></tr></thead><tbody>
                  {studentsLoading ? (
                    <tr><td colSpan={7}><div className="ts">Loading students…</div></td></tr>
                  ) : (studentsView.items || []).length === 0 ? (
                    <tr><td colSpan={7}><div className="ts">No students match the selected filters.</div></td></tr>
                  ) : (
                    (studentsView.items || []).map((student: any) => {
                      const attendanceValue = attendanceNumber(student.attendance);
                      return (
                        <tr key={student.id}>
                          <td><div className="ck-student-cell">{student.photoUrl ? <img src={student.photoUrl} alt={student.name} className="ck-student-avatar" /> : <div className="ck-student-avatar ck-student-avatar-fallback">{initials(student.name)}</div>}<div><div className="tb">{student.name}</div><div className="ts">{student.classSection} · {student.academicYear}</div></div></div></td>
                          <td><div className="tb">{student.admissionNumber}</div><div className="ts">Roll {student.rollNo}</div></td>
                          <td><div className="tb">{student.fatherName || '—'}</div><div className="ts">Contact {student.fatherContact || '—'}</div></td>
                          <td>{student.fatherContact || student.parentPhone || '—'}</td>
                          <td><span className={`ck-status ${student.feeStatus === 'Paid' ? 'sg' : student.feeStatus === 'Overdue' ? 'sr' : 'sam'}`}>{student.feeStatus}</span></td>
                          <td><div className="ck-mini-progress-cell"><div className="tb">{student.attendance}</div><div className="ck-mini-progress"><div className="ck-mini-progress-fill" style={{ width: `${attendanceValue}%` }} /></div></div></td>
                          <td><button className="ck-btn ck-btn-ghost" onClick={() => openStudentModal(student)}>View</button></td>
                        </tr>
                      );
                    })
                  )}
                </tbody></table>
              </div>
            </ModuleShell>
          )}

          {panel === 'fees' && workspace && (() => {
            const safeReportRows = Array.isArray(reportRows) ? reportRows : [];
            const safeOverdueRows = Array.isArray(overdueRows) ? overdueRows : [];
            const selectedReportRow = safeReportRows.find((row: any) => String(row.studentId || row.assignmentId || '') === selectedReportStudentId) || safeReportRows[0] || null;
            return <ModuleShell
              title="Fee collection"
              subtitle="Per-student fee assignment, dues tracking, overdue reporting and receipt generation"
              actions={<button className="ck-btn ck-btn-ghost" onClick={handleSendReminders} disabled={reminderSaving || !(feeFilters.className && feeFilters.sectionName)} title={feeFilters.className && feeFilters.sectionName ? 'Queue reminder messages for overdue students' : 'Select a class and section first'}>{reminderSaving ? 'Sending…' : 'Send reminders'}</button>}
            >
              <div className="ck-panel-stack">
                {feeLoadError ? <div className="ck-alert ck-alert-am"><span>!</span><div>{feeLoadError}</div></div> : null}
                {reminderNotice ? <div className="ck-alert ck-alert-g"><span>✓</span><div>{reminderNotice}</div></div> : null}

                <div className="ck-card ck-progress-card">
                  <div className="ck-progress-wrap">
                    <div className="ck-progress-label"><span>Academic year collection · {workspace?.school?.meta || 'Current year'}</span><span>{feeSummary.progressPercent}%</span></div>
                    <div className="ck-progress-bar"><div className="ck-progress-fill" style={{ width: `${feeSummary.progressPercent}%`, background: feeSummary.progressPercent < 60 ? 'linear-gradient(to right, var(--am), #f5b041)' : 'linear-gradient(to right, var(--g), #2ecc71)' }} /></div>
                    <div className="ck-progress-meta">₹{formatMoney(Math.round(Number(feeSummary.collected || 0) / 100))} collected · ₹{formatMoney(Math.round(Number(feeSummary.outstanding || 0) / 100))} outstanding · <span style={{ color: Number(feeSummary.overdueCount || 0) > 0 ? 'var(--am)' : 'var(--ink2)', fontWeight: 700 }}>{feeSummary.overdueCount} overdue accounts</span></div>
                  </div>
                </div>

                <div className="ck-stats ck-s4">
                  <Stat label="Total payable" value={`₹${formatLakh(Math.round(Number(feeSummary.target || 0) / 100))}`} sub={`Full value ₹${formatMoney(Math.round(Number(feeSummary.target || 0) / 100))}`} pill="Across all schedules" tone="blue" />
                  <Stat label="Collected" value={`₹${formatLakh(Math.round(Number(feeSummary.collected || 0) / 100))}`} sub={`Full value ₹${formatMoney(Math.round(Number(feeSummary.collected || 0) / 100))}`} pill="Live updates" tone="green" />
                  <Stat label="Outstanding" value={`₹${formatLakh(Math.round(Number(feeSummary.outstanding || 0) / 100))}`} sub={`Full value ₹${formatMoney(Math.round(Number(feeSummary.outstanding || 0) / 100))}`} pill={`${feeSummary.overdueCount} overdue`} tone="red" />
                  <Stat label="Schedules" value="4" sub="Monthly · Quarterly · Half-yearly · Annual" pill="Configurable" tone="orange" />
                </div>

                <div className="ck-card">
                  <div className="ck-card-h"><div className="ck-card-t">Record installment payment</div></div>
                  {paymentSuccess ? <div style={{ padding: '16px 16px 0' }}><div className="ck-alert ck-alert-g"><span>✓</span><div>{paymentSuccess}</div></div></div> : null}
                  <div style={{ padding: '16px 16px 0' }} className="ts">Student selection</div>
                  <div className="ck-form-grid ck-fg-3" style={{ padding: 16 }}>
                    <Field label="Class"><select value={paymentSelection.classId} onChange={(e) => handlePaymentClassChange(e.target.value)}><option value="">Select class</option>{feeClasses.map((row: any) => <option key={row.id} value={row.id}>{row.name}</option>)}</select></Field>
                    <Field label="Section"><select disabled={!paymentSelection.classId} value={paymentSelection.sectionId} onChange={(e) => handlePaymentSectionChange(e.target.value)}><option value="">Select section</option>{paymentOptions.sections.map((row: any) => <option key={row.id} value={row.id}>{row.name}</option>)}</select>{!paymentSelection.classId ? <div className="ts">Select a class first</div> : null}</Field>
                    <Field label="Student"><select disabled={!paymentSelection.sectionId} value={paymentSelection.studentId} onChange={(e) => handlePaymentStudentChange(e.target.value)}><option value="">Select student</option>{paymentOptions.students.map((student: any) => <option key={student.id} value={student.id}>{student.name} · {student.admissionNo}</option>)}</select>{!paymentSelection.sectionId ? <div className="ts">Select a section first</div> : null}</Field>
                  </div>
                  <div style={{ padding: '0 16px' }}><div className="ck-divider" /></div>
                  <div style={{ padding: '16px 16px 0' }} className="ts">Payment details</div>
                  <div className="ck-form-grid ck-fg-3" style={{ padding: 16 }}>
                    <Field label="Amount"><input value={paymentForm.amount} onChange={(e) => setPaymentForm({ ...paymentForm, amount: e.target.value })} /></Field>
                    <Field label="Mode"><select value={paymentForm.paymentMode} onChange={(e) => setPaymentForm({ ...paymentForm, paymentMode: e.target.value })}><option>UPI</option><option>Cash</option><option>Bank transfer</option><option>Cheque</option></select></Field>
                    <Field label="Notes"><input value={paymentForm.notes} onChange={(e) => setPaymentForm({ ...paymentForm, notes: e.target.value })} placeholder="Optional" /></Field>
                  </div>
                  {paymentDuePreview ? <div style={{ padding: '0 16px 16px' }}><div className="ck-alert ck-alert-re"><span>₹</span><div><strong>{paymentDuePreview.feePlan}</strong> · {paymentDuePreview.schedule}<div>Total fee ₹{formatMoney(Math.round(Number(paymentDuePreview.totalFee || 0) / 100))} · Discount ₹{formatMoney(Math.round(Number(paymentDuePreview.discount || 0) / 100))} · Paid ₹{formatMoney(Math.round(Number(paymentDuePreview.paid || 0) / 100))} · <span style={{ color: Number(paymentDuePreview.dueAmount) > 0 ? '#A32D2D' : undefined, fontWeight: 700 }}>Due ₹{formatMoney(Math.round(Number(paymentDuePreview.dueAmount || 0) / 100))}</span></div></div></div></div> : null}
                  {paymentError ? <div style={{ padding: '0 16px 16px' }}><div className="ck-alert ck-alert-re"><span>!</span><div>{paymentError}</div></div></div> : null}
                  <div className="ck-actions-inline" style={{ padding: '0 16px 16px' }}><button disabled={!(paymentForm.studentId && Number(paymentForm.amount) > 0 && paymentForm.paymentMode)} className="ck-btn ck-btn-g" onClick={handleRecordPayment}>Save payment</button></div>
                </div>

                <div className="ck-card">
                  <div className="ck-card-h ck-card-h-wrap">
                    <div className="ck-card-t">Reports & filters</div>
                    <div className="ck-card-inline-filters">
                      <select value={feeFilters.className} onChange={(e) => handleReportClassChange(e.target.value)}><option value="">Select class</option>{feeClasses.map((row: any) => <option key={row.id} value={row.id}>{row.name}</option>)}</select>
                      <select disabled={!feeFilters.className} value={feeFilters.sectionName} onChange={async (e) => { const sectionId = e.target.value; setFeeFilters((f) => ({ ...f, sectionName: sectionId })); setReportRows([]); setOverdueRows([]); setSelectedReportStudentId(null); if (feeFilters.className && sectionId) { await loadFeeReports(feeFilters.className, sectionId); } }}><option value="">Select section</option>{reportOptions.sections.map((row: any) => <option key={row.id} value={row.id}>{row.name}</option>)}</select>
                      <button className="ck-btn ck-btn-ghost" onClick={exportReportCsv} disabled={!safeReportRows.length}>Export CSV</button>
                    </div>
                  </div>

                  {!feeFilters.className || !feeFilters.sectionName ? <div className="ck-import-zone" style={{ margin: 16 }}><div className="iz-title">Select a class and section to load fee reports</div></div> : reportLoading ? <div style={{ padding: 24, textAlign: 'center', color: 'var(--ink3)' }}>Loading records…</div> : <table className="ck-table"><thead><tr><th>Student</th><th>Plan / Schedule</th><th>Total Annual Fee</th><th>Discounts / Surcharge</th><th>Paid</th><th>Due</th><th>Status</th><th>Receipt</th></tr></thead><tbody>
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
                      return <tr key={rowKey} className={isSelected ? 'ck-row-selected' : ''} style={{ cursor: 'pointer' }} onClick={() => setSelectedReportStudentId(selectedId)}><td><div className="tb">{studentName}</div><div className="ts">{[classSection, admissionNumber ? `Adm. ${admissionNumber}` : ''].filter(Boolean).join(' · ') || '—'}</div></td><td><div className="tb">{row.planName || '—'}</div><div className="ts">{paymentSchedule}</div></td><td>₹{formatMoney(Math.round(Number(row.totalAnnualFee || 0) / 100))}</td><td><div className="tb">Discount ₹{formatMoney(Math.round(Number(approvedDiscount || 0) / 100))}</div><div className="ts">Surcharge ₹{formatMoney(Math.round(Number(surchargeAmount || 0) / 100))}</div></td><td>₹{formatMoney(Math.round(Number(row.paid || 0) / 100))}</td><td>₹{formatMoney(Math.round(Number(dueAmount || 0) / 100))}</td><td><span className={`ck-status ${row.status === 'Paid' ? 'sg' : row.status === 'Pending' ? 'sam' : 'sr'}`}>{row.status || 'Pending'}</span></td><td>{paymentId ? <button className="ck-btn ck-btn-ghost" onClick={(e) => { e.stopPropagation(); openReceiptPdf(paymentId); }}>PDF</button> : '—'}</td></tr>;
                    })}
                  </tbody></table>}
                </div>

                <div className="ck-card">
                  <div className="ck-card-h"><div className="ck-card-t">Overdue list <span className="ck-pill pr">{safeOverdueRows.length}</span></div></div>
                  {!feeFilters.className || !feeFilters.sectionName ? <div className="ck-import-zone" style={{ margin: 16 }}><div className="iz-title">Select a class and section above to load overdue records</div></div> : safeOverdueRows.length ? <table className="ck-table"><thead><tr><th>Student</th><th>Schedule</th><th>Due Amount</th><th>Days Overdue</th></tr></thead><tbody>{safeOverdueRows.map((row: any, i: number) => { const days = Number(row.daysOverdue || 0); const dayColor = days > 60 ? 'var(--re)' : days > 30 ? 'var(--am)' : 'var(--ink2)'; return <tr key={row.studentId || row.assignmentId || `${row.studentName || row.student || 'student'}-${i}`}><td><div className="tb">{row.studentName || row.student || '—'}</div><div className="ts">{row.classSection || [row.className, row.sectionName].filter(Boolean).join(' · ') || '—'}</div></td><td>{row.schedule || '—'}</td><td>₹{formatMoney(Math.round(Number(row.dueAmount || 0) / 100))}</td><td style={{ color: dayColor, fontWeight: 700 }}>{days}</td></tr>; })}</tbody></table> : <div style={{ padding: 16 }}>No overdue records for this section.</div>}
                </div>

                {selectedReportRow ? <div className="ck-card">
                  <div className="ck-card-h"><div className="ck-card-t">Per-student dues statement — {selectedReportRow.studentName || selectedReportRow.student || 'Student'}</div></div>
                  <div style={{ padding: 16 }}>
                    <div className="ck-alert ck-alert-g"><span>✓</span><div>Total annual fee ₹{formatMoney(Math.round(Number(selectedReportRow.totalAnnualFee || 0) / 100))} − approved discounts ₹{formatMoney(Math.round(Number(selectedReportRow.approvedDiscount ?? selectedReportRow.discounts ?? 0) / 100))} − payments ₹{formatMoney(Math.round(Number(selectedReportRow.paid || 0) / 100))} = due ₹{formatMoney(Math.round(Number(selectedReportRow.dueAmount ?? selectedReportRow.due ?? 0) / 100))}</div></div>
                    {Array.isArray(selectedReportRow.installments) && selectedReportRow.installments.length ? <table className="ck-table"><thead><tr><th>Installment</th><th>Due date</th><th>Paid date</th><th>Amount</th><th>Status</th></tr></thead><tbody>{selectedReportRow.installments.map((ins: any, idx: number) => <tr key={ins.installmentNo || ins.dueDate || idx}><td>#{ins.installmentNo || idx + 1}</td><td>{ins.dueDate || '—'}</td><td>{ins.paidDate || '—'}</td><td>₹{formatMoney(Math.round(Number(ins.amount || 0) / 100))}</td><td><span className={`ck-status ${ins.status === 'Paid' ? 'sg' : ins.status === 'Pending' ? 'sam' : 'sr'}`}>{ins.status || 'Pending'}</span></td></tr>)}</tbody></table> : <div className="ck-import-zone" style={{ marginTop: 16 }}><div className="iz-title">Installment-wise statement is not available for this fee record yet.</div></div>}
                  </div>
                </div> : null}
              </div>
            </ModuleShell>;
          })()}

          {panel === 'feestructure' && (
            <ModuleShell
              title="Fee structure"
              subtitle={`Define class bands, fee items and payment schedules · Academic year ${feeStructureData.academicYear || '2025–26'}`}
              actions={<><button className="ck-btn ck-btn-ghost" onClick={exportFeeStructurePdf}>Export PDF</button><button className="ck-btn ck-btn-ghost" onClick={() => { setShowBandForm((prev) => !prev); setShowFeeItemForm(false); }}>+ Add band</button><button className="ck-btn ck-btn-g" onClick={() => { setShowFeeItemForm((prev) => !prev); setShowBandForm(false); }}>+ Add item</button></>}
            >
              {feeStructureToast ? <div className="ck-alert ck-alert-g" style={{ marginBottom: 16 }}><span>✓</span><div>{feeStructureToast}</div></div> : null}
              {feeStructureError ? <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>!</span><div>{feeStructureError}</div></div> : null}
              {showBandForm ? <div className="ck-form-card" style={{ marginBottom: 16 }}>
                <div className="ck-form-head">Add band</div>
                <div className="ck-form-body">
                  <div className="ck-form-grid ck-fg-6">
                    <Field label="Band name"><input value={bandForm.name} onChange={(e) => setBandForm((prev: any) => ({ ...prev, name: e.target.value }))} placeholder="Class 1–5" /></Field>
                    <Field label="Class from"><select value={bandForm.classFrom} onChange={(e) => setBandForm((prev: any) => ({ ...prev, classFrom: e.target.value }))}>{Array.from({ length: 12 }, (_, i) => i + 1).map((n) => <option key={n} value={n}>{n}</option>)}</select></Field>
                    <Field label="Class to"><select value={bandForm.classTo} onChange={(e) => setBandForm((prev: any) => ({ ...prev, classTo: e.target.value }))}>{Array.from({ length: 12 }, (_, i) => i + 1).map((n) => <option key={n} value={n}>{n}</option>)}</select></Field>
                    <Field label="Discount %"><input type="number" min={0} max={100} value={bandForm.discount} onChange={(e) => setBandForm((prev: any) => ({ ...prev, discount: e.target.value }))} /></Field>
                    <div className="ck-field"><label>Payment schedules</label><div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', minHeight: 44, alignItems: 'center' }}>{['Monthly', 'Quarterly', 'Half-yearly', 'Annual'].map((schedule) => <button type="button" key={schedule} className={`ck-pill ${bandForm.schedules.includes(schedule) ? 'pg' : ''}`} style={{ border: bandForm.schedules.includes(schedule) ? '1px solid var(--g2)' : '1px solid var(--border)', background: bandForm.schedules.includes(schedule) ? 'var(--g1)' : '#fff', cursor: 'pointer' }} onClick={() => toggleBandFormSchedule(schedule, 'create')}>{schedule}</button>)}</div></div>
                    <div className="ck-field"><label>&nbsp;</label><div style={{ display: 'flex', gap: 10 }}><button className="ck-btn ck-btn-g" onClick={addFeeBand}>Create band</button><button className="ck-btn ck-btn-ghost" onClick={() => setShowBandForm(false)}>Cancel</button></div></div>
                  </div>
                </div>
              </div> : null}
              {showFeeItemForm ? <div className="ck-form-card" style={{ marginBottom: 16 }}>
                <div className="ck-form-head">Add fee item</div>
                <div className="ck-form-body">
                  <div className="ck-form-grid ck-fg-6">
                    <Field label="Class band"><select value={feeItemForm.bandId} onChange={(e) => setFeeItemForm({ ...feeItemForm, bandId: e.target.value })} disabled={feeStructureLoading || !(feeStructureData.bands || []).length}><option value="">{feeStructureLoading ? 'Loading class bands…' : (feeStructureData.bands || []).length ? 'Select class band' : 'No class bands found'}</option>{(feeStructureData.bands || []).map((band: any) => <option key={band.id} value={band.id}>{band.name}</option>)}</select></Field>
                    <Field label="Item name"><input value={feeItemForm.itemName} onChange={(e) => setFeeItemForm({ ...feeItemForm, itemName: e.target.value })} placeholder="Tuition fee" /></Field>
                    <Field label="Frequency"><select value={feeItemForm.frequency} onChange={(e) => setFeeItemForm({ ...feeItemForm, frequency: e.target.value })}><option>Monthly</option><option>Quarterly</option><option>Half-yearly</option><option>Annual</option></select></Field>
                    <Field label="Amount (₹)"><input type="number" min={0} value={feeItemForm.amount} onChange={(e) => setFeeItemForm({ ...feeItemForm, amount: e.target.value })} /></Field>
                    <div className="ck-field"><label>&nbsp;</label><button className="ck-btn ck-btn-g" disabled={saving === 'fee-structure-add'} onClick={addFeeStructureItem}>Add</button></div>
                    <div className="ck-field"><label>&nbsp;</label><button className="ck-btn ck-btn-ghost" onClick={() => setShowFeeItemForm(false)}>Cancel</button></div>
                  </div>
                </div>
              </div> : null}
              {feeStructureLoading ? <div className="ck-card" style={{ padding: 16 }}>Loading fee structure…</div> : null}
              {(feeStructureData.bands || []).length === 0 && !feeStructureLoading ? (
                <div className="ck-import-zone" style={{ margin: '24px 0' }}>
                  <div className="iz-title">No fee bands yet</div>
                  <div className="ts" style={{ marginTop: 8 }}>Create a band (e.g. "Class 1–5") to start adding fee items and payment schedules.</div>
                  <button className="ck-btn ck-btn-g" style={{ marginTop: 16 }} onClick={() => setShowBandForm(true)}>+ Create first band</button>
                </div>
              ) : null}

              {(feeStructureData.bands || []).map((band: any) => {
                const totalPaise = Number(band.annualTotal || 0);
                const totalRupees = Math.round(totalPaise / 100);
                const discount = Number(band.discount || 0);
                const savings = Math.round(totalRupees * discount / 100);
                const activeSchedules = Array.isArray(band.activeSchedules) ? band.activeSchedules : [];
                const isExpanded = expandedBandIds.includes(band.id);
                const isEditingBand = editingBandId === band.id;
                const editSchedules = band.editSchedules || activeSchedules;
                return <div className="ck-fee-group" key={band.id} style={{ marginBottom: 16, overflow: 'hidden' }}>
                  <div className="ck-fee-head" style={{ cursor: 'pointer', gap: 16 }} onClick={() => toggleBandAccordion(band.id)}>
                    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, flex: 1 }}>
                      <div style={{ fontSize: 18, transform: isExpanded ? 'rotate(0deg)' : 'rotate(-90deg)', transition: 'transform 0.2s' }}>▾</div>
                      <div style={{ flex: 1 }}>
                        {isEditingBand ? <div className="ck-form-grid ck-fg-6" onClick={(e) => e.stopPropagation()}>
                          <Field label="Band name"><input value={band.editName ?? band.name} onChange={(e) => setFeeStructureData((prev: any) => ({ ...prev, bands: prev.bands.map((row: any) => row.id === band.id ? { ...row, editName: e.target.value } : row) }))} /></Field>
                          <Field label="Class from"><select value={band.editClassFrom ?? band.classFrom} onChange={(e) => setFeeStructureData((prev: any) => ({ ...prev, bands: prev.bands.map((row: any) => row.id === band.id ? { ...row, editClassFrom: e.target.value } : row) }))}>{Array.from({ length: 12 }, (_, i) => i + 1).map((n) => <option key={n} value={n}>{n}</option>)}</select></Field>
                          <Field label="Class to"><select value={band.editClassTo ?? band.classTo} onChange={(e) => setFeeStructureData((prev: any) => ({ ...prev, bands: prev.bands.map((row: any) => row.id === band.id ? { ...row, editClassTo: e.target.value } : row) }))}>{Array.from({ length: 12 }, (_, i) => i + 1).map((n) => <option key={n} value={n}>{n}</option>)}</select></Field>
                          <Field label="Discount %"><input type="number" min={0} max={100} value={band.editDiscount ?? band.discount ?? 0} onChange={(e) => setFeeStructureData((prev: any) => ({ ...prev, bands: prev.bands.map((row: any) => row.id === band.id ? { ...row, editDiscount: e.target.value } : row) }))} /></Field>
                          <div className="ck-field"><label>Payment schedules</label><div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', minHeight: 44, alignItems: 'center' }}>{['Monthly', 'Quarterly', 'Half-yearly', 'Annual'].map((schedule) => <button type="button" key={schedule} className={`ck-pill ${editSchedules.includes(schedule) ? 'pg' : ''}`} style={{ border: editSchedules.includes(schedule) ? '1px solid var(--g2)' : '1px solid var(--border)', background: editSchedules.includes(schedule) ? 'var(--g1)' : '#fff', cursor: 'pointer' }} onClick={(e) => { e.stopPropagation(); toggleBandFormSchedule(schedule, 'edit', band.id); }}>{schedule}</button>)}</div></div>
                          <div className="ck-field"><label>&nbsp;</label><div style={{ display: 'flex', gap: 8 }}><button className="ck-btn ck-btn-g" onClick={(e) => { e.stopPropagation(); saveFeeBandEdit(band); }}>Save</button><button className="ck-btn ck-btn-ghost" onClick={(e) => { e.stopPropagation(); setEditingBandId(''); }}>Cancel</button></div></div>
                        </div> : <>
                          <div className="ck-fee-name" style={{ fontSize: 15, fontWeight: 500 }}>{band.name} <span className="ts">· Classes {band.classFrom}–{band.classTo}</span></div>
                          <div style={{ display: 'flex', gap: 8, marginTop: 10, flexWrap: 'wrap' }}>{['Monthly', 'Quarterly', 'Half-yearly', 'Annual'].map((schedule) => <button type="button" key={schedule} className={`ck-pill ${activeSchedules.includes(schedule) ? 'pg' : ''}`} style={{ border: activeSchedules.includes(schedule) ? '1px solid var(--g2)' : '1px solid var(--border)', background: activeSchedules.includes(schedule) ? 'var(--g1)' : '#fff', cursor: 'pointer' }} onClick={(e) => { e.stopPropagation(); toggleBandSchedule(band, schedule); }}>{schedule}</button>)}</div>
                        </>}
                      </div>
                    </div>
                    {!isEditingBand ? <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                      <div className="ck-fee-amt">₹{formatMoney(totalRupees)}</div>
                      <button className="ck-btn ck-btn-ghost" onClick={(e) => { e.stopPropagation(); setEditingBandId(band.id); setExpandedBandIds((prev) => prev.includes(band.id) ? prev : [...prev, band.id]); setFeeStructureData((prev: any) => ({ ...prev, bands: prev.bands.map((row: any) => row.id === band.id ? { ...row, editName: row.name, editClassFrom: String(row.classFrom), editClassTo: String(row.classTo), editDiscount: String(row.discount ?? 0), editSchedules: [...(row.activeSchedules || [])] } : row) })); }}>Edit</button>
                      <button className="ck-btn ck-btn-ghost" style={{ color: '#A32D2D', borderColor: '#f5c0bc' }} onClick={(e) => { e.stopPropagation(); setConfirmDeleteBandId(confirmDeleteBandId === band.id ? '' : band.id); }}>Delete band</button>
                    </div> : null}
                  </div>
                  {confirmDeleteBandId === band.id ? <div style={{ padding: '0 16px 12px', textAlign: 'right' }}><span className="ts">Delete band '{band.name}' and all its items? </span><button className="ck-inline-action" onClick={() => deleteFeeBand(band.id, band.name)}>Yes</button> / <button className="ck-inline-action" onClick={() => setConfirmDeleteBandId('')}>No</button></div> : null}
                  {isExpanded ? <div onClick={(e) => e.stopPropagation()}>
                    <div className="ck-card" style={{ border: 'none', borderTop: '1px solid var(--border)', borderRadius: 0, boxShadow: 'none' }}>
                      {(band.items || []).length ? <table className="ck-table"><thead><tr><th>Item name</th><th>Frequency</th><th>Amount</th><th>% of total</th><th>Actions</th></tr></thead><tbody>
                        {(band.items || []).map((item: any) => {
                          const isEditing = editingFeeItem?.id === item.id;
                          const pct = totalPaise > 0 ? Math.round(Number(item.amount || 0) / totalPaise * 100) : 0;
                          return <tr key={item.id}>
                            <td>{isEditing ? <input value={editingFeeItem.name} onChange={(e) => setEditingFeeItem({ ...editingFeeItem, name: e.target.value })} /> : <div className="tb">{item.name}</div>}</td>
                            <td>{isEditing ? <select value={editingFeeItem.frequency} onChange={(e) => setEditingFeeItem({ ...editingFeeItem, frequency: e.target.value })}><option>Monthly</option><option>Quarterly</option><option>Half-yearly</option><option>Annual</option></select> : <span className="ck-pill pb">{item.frequency}</span>}</td>
                            <td style={{ textAlign: 'right' }}>{isEditing ? <input type="number" min={0} value={editingFeeItem.amount} onChange={(e) => setEditingFeeItem({ ...editingFeeItem, amount: e.target.value })} /> : `₹${formatMoney(Math.round(Number(item.amount || 0) / 100))}`}</td>
                            <td><span className="ts">{pct}%</span></td>
                            <td><div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>{isEditing ? <><button className="ck-btn ck-btn-g" onClick={saveFeeStructureItem}>Save</button><button className="ck-btn ck-btn-ghost" onClick={() => setEditingFeeItem(null)}>Cancel</button></> : <><button className="ck-btn ck-btn-ghost" onClick={() => setEditingFeeItem({ id: item.id, name: item.name, frequency: item.frequency, amount: Math.round(Number(item.amount || 0) / 100) })}>Edit</button><button className="ck-btn ck-btn-ghost" onClick={() => setConfirmRemoveFeeItemId(confirmRemoveFeeItemId === item.id ? '' : item.id)}>Remove</button></>}</div>{confirmRemoveFeeItemId === item.id && !isEditing ? <div className="ts" style={{ marginTop: 6, textAlign: 'right' }}>Remove this item? <button className="ck-inline-action" onClick={() => removeFeeStructureItem(item.id)}>Yes</button> / <button className="ck-inline-action" onClick={() => setConfirmRemoveFeeItemId('')}>No</button></div> : null}</td>
                          </tr>;
                        })}
                      </tbody></table> : <div style={{ padding: 20, textAlign: 'center' }} className="ts">No fee items yet. Use '+ Add item' to add one.</div>}
                    </div>
                    <div className="ck-fee-footer">
                      <div className="ck-fee-footer-left">
                        <label className="ts" style={{ display: 'block', marginBottom: 6 }}>Discount</label>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                          <input className="ck-fee-discount-input" type="number" min={0} max={100} value={band.discount} onChange={(e) => handleDiscountChange(band.id, e.target.value)} />
                          <span>%</span>
                          <span className="ts">saves <strong style={{ color: '#085041' }}>₹{formatMoney(savings)}</strong></span>
                        </div>
                      </div>
                      <div className="ck-fee-footer-right">Total annual: ₹{formatMoney(totalRupees)}</div>
                    </div>
                  </div> : null}
                </div>;
              })}
              <div className="ck-card" style={{ marginTop: 24 }}>
                <div className="ck-card-h"><div className="ck-card-t">Assign fee plan to student</div></div>
                <div className="ck-form-grid ck-fg-6" style={{ padding: 16 }}>
                  <Field label="Class"><select value={assignSelection.classId} onChange={(e) => handleAssignClassChange(e.target.value)}><option value="">Select class</option>{feeClasses.map((row: any) => <option key={row.id} value={row.id}>{row.name}</option>)}</select></Field>
                  <Field label="Section"><select disabled={!assignSelection.classId} value={assignSelection.sectionId} onChange={(e) => handleAssignSectionChange(e.target.value)}><option value="">Select section</option>{assignOptions.sections.map((row: any) => <option key={row.id} value={row.id}>{row.name}</option>)}</select>{!assignSelection.classId ? <div className="ts">Select a class first</div> : null}</Field>
                  <Field label="Student"><select disabled={!assignSelection.sectionId} value={assignSelection.studentId} onChange={(e) => handleAssignStudentChange(e.target.value)}><option value="">Select student</option>{assignOptions.students.map((student: any) => <option key={student.id} value={student.id}>{student.name} · {student.admissionNo}</option>)}</select>{!assignSelection.sectionId ? <div className="ts">Select a section first</div> : null}</Field>
                  <Field label="Fee plan"><select value={feeAssignForm.bandId} onChange={(e) => handleFeePlanChange(e.target.value)}><option value="">Select fee plan</option>{(feeStructureData.bands || []).map((band: any) => <option key={band.id} value={band.id}>{band.name} · ₹{formatMoney(Math.round(Number(band.annualTotal || 0) / 100))}</option>)}</select>{feeAssignHint ? <div className="ts">{feeAssignHint}</div> : null}</Field>
                  <Field label="Payment schedule"><select disabled={!feeAssignForm.bandId} value={feeAssignForm.paymentSchedule} onChange={(e) => setFeeAssignForm({ ...feeAssignForm, paymentSchedule: e.target.value, surcharge: e.target.value === 'Annual' ? '0' : feeAssignForm.surcharge })}><option value="">Select schedule</option>{(((feeStructureData.bands || []).find((band: any) => band.id === feeAssignForm.bandId)?.activeSchedules) || []).map((schedule: string) => <option key={schedule} value={schedule}>{schedule}</option>)}</select></Field>
                  <Field label="Discount % (from band)"><input readOnly value={feeAssignForm.bandDiscount} /><div className="ts">Set in Fee structure</div></Field>
                  <Field label="Manual student discount"><input type="number" min={0} max={100} value={feeAssignForm.manualDiscount} onChange={(e) => setFeeAssignForm({ ...feeAssignForm, manualDiscount: e.target.value })} /></Field>
                  <Field label="Installment surcharge %"><input type="number" min={0} max={100} value={feeAssignForm.surcharge} onChange={(e) => setFeeAssignForm({ ...feeAssignForm, surcharge: e.target.value })} /></Field>
                </div>
                {feeAssignError ? <div style={{ padding: '0 16px 16px' }}><div className="ck-alert ck-alert-re"><span>!</span><div>{feeAssignError}</div></div></div> : null}
                {feeAssignForm.bandId ? <div style={{ padding: '0 16px 16px' }}>
                  <div className="ck-card" style={{ boxShadow: 'none' }}>
                    <div className="ck-card-h"><div className="ck-card-t">Fee items in this band</div></div>
                    <table className="ck-table"><thead><tr><th>Item name</th><th>Frequency</th><th>Amount</th></tr></thead><tbody>{(((feeStructureData.bands || []).find((band: any) => band.id === feeAssignForm.bandId)?.items) || []).map((item: any) => <tr key={item.id}><td>{item.name}</td><td>{item.frequency}</td><td>₹{formatMoney(Math.round(Number(item.amount || 0) / 100))}</td></tr>)}</tbody></table>
                  </div>
                </div> : null}
                {feeAssignForm.bandId && feeAssignForm.paymentSchedule ? (() => { const band = (feeStructureData.bands || []).find((row: any) => row.id === feeAssignForm.bandId); const total = Math.round(Number(band?.annualTotal || 0) / 100); const bandDiscountAmt = Math.round(total * Number(feeAssignForm.bandDiscount || 0) / 100); const manualDiscountAmt = Math.round(total * Number(feeAssignForm.manualDiscount || 0) / 100); const surchargeAmt = feeAssignForm.paymentSchedule === 'Annual' ? 0 : Math.round(total * Number(feeAssignForm.surcharge || 0) / 100); const netPayable = total - bandDiscountAmt - manualDiscountAmt + surchargeAmt; return <div style={{ padding: '0 16px 16px' }}><div className="ck-alert ck-alert-g"><span>₹</span><div><strong>Live net payable preview</strong><div>Total annual fee ₹{formatMoney(total)} · Band discount −₹{formatMoney(bandDiscountAmt)} · Manual discount −₹{formatMoney(manualDiscountAmt)} · Surcharge +₹{formatMoney(surchargeAmt)} · <span style={{ color: '#085041', fontWeight: 700 }}>Net payable ₹{formatMoney(netPayable)}</span></div></div></div></div>; })() : null}
                <div className="ck-actions-inline" style={{ padding: '0 16px 16px' }}><button disabled={!(feeAssignForm.studentId && feeAssignForm.bandId && feeAssignForm.paymentSchedule)} className="ck-btn ck-btn-g" onClick={submitFeeAssignment}>Assign / update plan</button></div>
              </div>
            </ModuleShell>
          )}

          {panel === 'addstudent' && (
            <ModuleShell title="Add student" subtitle="Capture complete student master data, validate unique IDs and auto-create fee assignment" actions={<button className="ck-btn ck-btn-ghost" onClick={() => setPanel('bulkimport')}>Bulk import instead</button>}>
              {photoFeedback && panel === 'addstudent' ? <div className={`ck-alert ${photoFeedback.type === 'success' ? 'ck-alert-g' : 'ck-alert-re'}`}><span>{photoFeedback.type === 'success' ? '✓' : '!'}</span><div>{photoFeedback.message}</div></div> : null}
              <div className="ck-form-card">
                <div className="ck-form-head">Student profile</div>
                <div className="ck-form-body">
                  <div className="ck-form-grid ck-fg-3">
                    <Field label="Admission Number *"><input value={studentForm.admissionNumber} onChange={(e) => setStudentForm({ ...studentForm, admissionNumber: e.target.value })} placeholder="Manual unique ID" /></Field>
                    <Field label="Board Registration Number"><input value={studentForm.boardRegistrationNumber} onChange={(e) => setStudentForm({ ...studentForm, boardRegistrationNumber: e.target.value })} placeholder="Alphanumeric" /></Field>
                    <Field label="Full name *"><input value={studentForm.fullName} onChange={(e) => setStudentForm({ ...studentForm, fullName: e.target.value })} placeholder="Student full name" /></Field>
                    <Field label="Date of birth"><input type="date" value={studentForm.dateOfBirth} onChange={(e) => setStudentForm({ ...studentForm, dateOfBirth: e.target.value })} /></Field>
                    <Field label="Gender"><select value={studentForm.gender} onChange={(e) => setStudentForm({ ...studentForm, gender: e.target.value })}><option>Male</option><option>Female</option><option>Other</option></select></Field>
                    <Field label="Admission date"><input type="date" value={studentForm.admissionDate} onChange={(e) => setStudentForm({ ...studentForm, admissionDate: e.target.value })} /></Field>
                    <Field label="Class *"><select value={studentForm.gradeLevel} onChange={(e) => setStudentForm({ ...studentForm, gradeLevel: e.target.value })}><option>Class 1</option><option>Class 2</option><option>Class 3</option><option>Class 4</option><option>Class 5</option><option>Class 6</option><option>Class 7</option><option>Class 8</option><option>Class 9</option><option>Class 10</option><option>Class 11</option><option>Class 12</option></select></Field>
                    <Field label="Section"><select value={studentForm.sectionName} onChange={(e) => setStudentForm({ ...studentForm, sectionName: e.target.value })}><option>A</option><option>B</option><option>C</option><option>D</option></select></Field>
                    <Field label="Academic year"><input value={studentForm.academicYear} onChange={(e) => setStudentForm({ ...studentForm, academicYear: e.target.value })} /></Field>
                  </div>

                  <div className="ck-form-grid ck-fg-3" style={{ marginTop: 16 }}>
                    <Field label="House number"><input value={studentForm.houseNumber} onChange={(e) => setStudentForm({ ...studentForm, houseNumber: e.target.value })} /></Field>
                    <Field label="Street"><input value={studentForm.street} onChange={(e) => setStudentForm({ ...studentForm, street: e.target.value })} /></Field>
                    <Field label="Locality"><input value={studentForm.locality} onChange={(e) => setStudentForm({ ...studentForm, locality: e.target.value })} /></Field>
                    <Field label="City"><input value={studentForm.city} onChange={(e) => setStudentForm({ ...studentForm, city: e.target.value })} /></Field>
                    <Field label="State"><input value={studentForm.state} onChange={(e) => setStudentForm({ ...studentForm, state: e.target.value })} /></Field>
                    <Field label="PIN code"><input value={studentForm.pinCode} onChange={(e) => setStudentForm({ ...studentForm, pinCode: e.target.value.replace(/\D/g, '').slice(0, 6) })} /></Field>
                    <Field label="Father name"><input value={studentForm.fatherName} onChange={(e) => setStudentForm({ ...studentForm, fatherName: e.target.value })} /></Field>
                    <Field label="Father contact number"><input value={studentForm.fatherContactNumber} onChange={(e) => setStudentForm({ ...studentForm, fatherContactNumber: e.target.value.replace(/\D/g, '').slice(0, 10) })} /></Field>
                    <Field label="Default payment schedule"><select value={studentForm.paymentSchedule} onChange={(e) => setStudentForm({ ...studentForm, paymentSchedule: e.target.value })}><option>Monthly</option><option>Quarterly</option><option>Half-yearly</option><option>Annual</option></select></Field>
                  </div>

                  <div className="ck-photo-panel">
                    <div className="ck-photo-panel-copy">
                      <h3>Student profile photo</h3>
                      <p>Upload a clear face photo. Accepted formats: JPG, PNG, WEBP. Maximum 2MB.</p>
                    </div>
                    <input ref={fileInputRef} type="file" accept="image/jpeg,image/png,image/webp" style={{ display: 'none' }} onChange={(e) => { const file = e.target.files?.[0]; if (file) selectPhoto(file); }} />
                    <div className={`ck-photo-dropzone ${photoDragActive ? 'drag' : ''} ${photoPreviewUrl ? 'has-image' : ''}`} onDragOver={(e) => { e.preventDefault(); setPhotoDragActive(true); }} onDragLeave={() => setPhotoDragActive(false)} onDrop={handlePhotoDrop}>
                      <div className="ck-photo-drop-icon">🖼</div><div className="ck-photo-drop-title">Drag and drop the student photo here</div><div className="ck-photo-drop-sub">JPG, PNG or WEBP · up to 2MB</div><div className="ck-actions-inline"><button type="button" className="ck-btn ck-btn-g" onClick={() => fileInputRef.current?.click()}>Browse file</button>{photoFile ? <button type="button" className="ck-btn ck-btn-ghost" onClick={resetPhotoState}>Remove photo</button> : null}</div>
                    </div>
                    {photoError ? <div className="ck-photo-error">{photoError}</div> : null}
                    {photoPreviewUrl ? <div className="ck-photo-editor"><div><div className="ck-photo-frame"><img src={photoPreviewUrl} alt="Student preview" className="ck-photo-preview-image" style={{ transform: `translate(${photoOffsetX}px, ${photoOffsetY}px) scale(${photoZoom})` }} /></div><div className="ck-photo-help">Live preview before saving</div></div><div className="ck-photo-controls"><Field label="Zoom"><input type="range" min="1" max="2.5" step="0.01" value={photoZoom} onChange={(e) => setPhotoZoom(Number(e.target.value))} /></Field><Field label="Move left / right"><input type="range" min="-140" max="140" step="1" value={photoOffsetX} onChange={(e) => setPhotoOffsetX(Number(e.target.value))} /></Field><Field label="Move up / down"><input type="range" min="-140" max="140" step="1" value={photoOffsetY} onChange={(e) => setPhotoOffsetY(Number(e.target.value))} /></Field></div></div> : null}
                  </div>

                  <div className="ck-alert ck-alert-g" style={{ marginTop: 16 }}><span>✓</span><div><strong>Validation rules</strong><div>Admission number and full name are required. Student records are saved against the admin's school automatically, and fee assignment will be created on save using the selected payment schedule.</div></div></div>
                  <div className="ck-actions-inline"><button className="ck-btn ck-btn-ghost" type="button" onClick={resetStudentForm}>Clear form</button><button className="ck-btn ck-btn-g" disabled={saving === 'student'} onClick={handleSaveStudent}>{saving === 'student' ? 'Saving…' : 'Save & enrol student →'}</button></div>
                </div>
              </div>
            </ModuleShell>
          )}

          {panel === 'bulkimport' && (
            <ModuleShell title="Bulk import" subtitle="Upload .xlsx or .csv files, preview validations, and import valid students only.">
              <input ref={bulkImportInputRef} type="file" accept=".xlsx,.csv" style={{ display: 'none' }} onChange={(e) => { const file = e.target.files?.[0]; if (file) handleBulkImportFile(file); }} />
              <div className={`ck-import-zone ${bulkImportDragActive ? 'ck-import-zone-active' : ''}`} onDragOver={(e) => { e.preventDefault(); setBulkImportDragActive(true); }} onDragLeave={() => setBulkImportDragActive(false)} onDrop={handleBulkImportDrop}>
                <div className="ck-iz-icon">📊</div>
                <div className="ck-iz-title">Drop your Excel or CSV file here</div>
                <div className="ck-iz-sub">.xlsx, .csv supported · Max 5 MB · Up to 500 rows</div>
                <div className="ck-actions-inline" style={{ justifyContent: 'center', marginTop: 14 }}>
                  <button className="ck-btn ck-btn-g" type="button" onClick={() => bulkImportInputRef.current?.click()}>Browse file</button>
                  <button className="ck-btn ck-btn-ghost" type="button" onClick={downloadImportTemplate}>Download sample template</button>
                </div>
                {bulkImportFileName ? <div className="ck-iz-file">Selected file: {bulkImportFileName}</div> : null}
              </div>
              {bulkImportError ? <div className="ck-alert ck-alert-r" style={{ marginTop: 16 }}><span>!</span><div>{bulkImportError}</div></div> : null}
              {bulkImportWarning ? <div className="ck-alert ck-alert-am" style={{ marginTop: 16 }}><span>!</span><div>{bulkImportWarning}</div></div> : null}
              {bulkImportToast ? <div className={`ck-alert ${bulkImportToast.type === 'success' ? 'ck-alert-g' : 'ck-alert-r'}`} style={{ marginTop: 16 }}><span>{bulkImportToast.type === 'success' ? '✓' : '!'}</span><div>{bulkImportToast.message}</div></div> : null}
              {bulkImportProgress ? <div className="ck-card" style={{ marginTop: 16 }}><div className="ck-form-body"><div className="ck-progress-wrap"><div className="ck-progress-label"><span>Import progress</span><strong>{bulkImportProgress.pct}%</strong></div><div className="ck-progress-bar"><div className="ck-progress-fill" style={{ width: `${bulkImportProgress.pct || 0}%` }} /></div></div>{bulkImportProgress.done ? <div className="ts">Done · {bulkImportProgress.inserted} inserted · {bulkImportProgress.skipped} skipped</div> : null}</div></div> : null}
              {bulkImportPreview ? (
                <div className="ck-card" style={{ marginTop: 16 }}>
                  <div className="ck-card-h">
                    <div>
                      <div className="ck-card-t">Preview — {bulkImportPreview.rows?.length || 0} rows detected</div>
                      <div className="ck-import-badges"><span className="ck-status sg">{bulkImportPreview.validCount || 0} valid</span><span className="ck-status sr">{bulkImportPreview.errorCount || 0} errors</span><span className="ck-status sam">{bulkImportPreview.warningCount || 0} warnings</span></div>
                    </div>
                    <button className="ck-btn ck-btn-g" disabled={(bulkImportPreview.validCount || 0) === 0 || Boolean(bulkImportProgress?.done) || saving === 'bulk-import-confirm'} onClick={confirmBulkImport}>{bulkImportProgress?.done ? 'Done' : saving === 'bulk-import-confirm' ? 'Importing…' : `Import ${bulkImportPreview.validCount || 0} valid rows`}</button>
                  </div>
                  <table className="ck-table">
                    <thead><tr><th>#</th><th>Name</th><th>Class</th><th>Section</th><th>Admission No.</th><th>Phone</th><th>Status</th></tr></thead>
                    <tbody>
                      {(bulkImportPreview.rows || []).map((row: any) => <tr key={row.rowNumber} className={row.statusTone === 'sr' ? 'ck-row-error' : row.statusTone === 'sam' ? 'ck-row-warning' : row.statusTone === 'spu' ? 'ck-row-duplicate' : ''}><td>{row.rowNumber}</td><td>{row.name}</td><td>{row.className}</td><td>{row.sectionName}</td><td>{row.admissionNo}</td><td>{row.phone}</td><td><span className={`ck-status ${row.statusTone}`}>{row.status}</span>{row.status !== 'Valid' ? <div className="ts" style={{ marginTop: 4 }}>{row.description}</div> : null}</td></tr>)}
                    </tbody>
                  </table>
                </div>
              ) : null}
              {bulkImportProgress?.done && bulkImportProgress?.skippedRows?.length ? <div className="ck-card" style={{ marginTop: 16 }}><div className="ck-card-h"><div className="ck-card-t">Skipped rows</div></div><div className="ck-form-body">{bulkImportProgress.skippedRows.map((row: any, index: number) => <div key={index} className="ts" style={{ marginBottom: 8 }}>Row {row.rowNumber}: {row.reason}</div>)}</div></div> : null}
            </ModuleShell>
          )}

          {panel === 'attendance' && (() => {
            const totalEnrolled = Number(attendanceSectionInfo?.totalEnrolled || 0);
            const presentCount = attendancePresentCount === '' ? NaN : Number(attendancePresentCount);
            const validPresent = Number.isFinite(presentCount) && presentCount >= 0 && presentCount <= totalEnrolled;
            const presentPct = validPresent && totalEnrolled > 0 ? Math.round((presentCount / totalEnrolled) * 100) : null;
            const absentCount = validPresent ? Math.max(totalEnrolled - presentCount, 0) : totalEnrolled;
            return (
            <ModuleShell
              title="Attendance"
              subtitle={`Today · ${attendanceSummary?.dateLabel || formatLongDate(attendanceFilters.date)} · ${Number(attendanceSummary?.overallPercent || 0).toFixed(1)}% overall`}
              actions={<button className="ck-btn ck-btn-g" disabled={!attendanceSummary?.allSubmitted || saving === 'attendance-submit-day'} onClick={submitAttendanceDay}>Submit today's attendance</button>}
            >
              {attendanceSummary?.nonWorkingDay ? <div className="ck-alert ck-alert-am" style={{ marginBottom: 16 }}><span>⚠</span><div>This is a non-working day. Attendance can still be recorded manually.</div></div> : null}
              {attendanceToast ? <div className="ck-alert ck-alert-g" style={{ marginBottom: 16 }}><span>✓</span><div>{attendanceToast}</div></div> : null}
              {attendanceSaveError ? <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>!</span><div>{attendanceSaveError}</div></div> : null}
              <div className="ck-class-grid" style={{ marginBottom: 16 }}>
                {(attendanceSummary?.sections || []).map((row: any, i: number) => {
                  const pct = row.presentPercent == null ? null : Number(row.presentPercent);
                  return <div className="ck-class-card" key={i}>
                    <div className="ck-cc-grade">{row.sectionName}</div>
                    <div className="ck-cc-sec">{row.totalStudents} students</div>
                    <div className="ck-cc-count">{pct == null ? '—' : `${Math.round(pct)}%`}</div>
                    <div className="ck-mini-progress" style={{ marginTop: 8, marginBottom: 8 }}><div className="ck-mini-progress-fill" style={{ width: `${pct || 0}%` }} /></div>
                    <div className="ck-cc-cl">{row.teacherName}</div>
                    <div style={{ marginTop: 8 }}><span className={`ck-status ${row.status === 'Submitted' ? 'sg' : 'sam'}`}>{row.status}</span></div>
                  </div>;
                })}
              </div>

              <div className="ck-card" style={{ marginBottom: 16 }}>
                <div className="ck-form-body">
                  <div className="ck-form-grid ck-fg-4">
                    <Field label="Date"><input type="date" value={attendanceFilters.date} onChange={(e) => handleAttendanceDateChange(e.target.value)} /></Field>
                    <Field label="Class"><select value={attendanceFilters.classId} onChange={(e) => handleAttendanceClassChange(e.target.value)}><option value="">Select class</option>{attendanceClassOptions.map((row: any) => <option key={row.id} value={row.id}>{row.name}</option>)}</select></Field>
                    <Field label="Section" hint={!attendanceFilters.classId ? 'Select a class first' : undefined}><select disabled={!attendanceFilters.classId} value={attendanceFilters.sectionId} onChange={(e) => handleAttendanceSectionChange(e.target.value)}><option value="">Select section</option>{attendanceSectionOptions.map((row: any) => <option key={row.id} value={row.id}>{row.name}</option>)}</select></Field>
                    <Field label="Total students"><input value={attendanceSectionInfo?.totalEnrolled ?? ''} readOnly /></Field>
                  </div>
                </div>
              </div>

              {!attendanceFilters.date || !attendanceFilters.classId || !attendanceFilters.sectionId ? <div className="ck-import-zone" style={{ marginBottom: 16 }}>Select date, class and section to load student count</div> : null}
              {attendanceSectionInfo && Number(attendanceSectionInfo.totalEnrolled || 0) === 0 ? <div className="ck-alert ck-alert-am" style={{ marginBottom: 16 }}><span>i</span><div>No students enrolled in this section.</div></div> : null}
              {attendanceSectionInfo?.existingRecord ? <div className="ts" style={{ marginBottom: 12 }}>Previously saved — {Math.round(Number(attendanceSectionInfo.existingRecord.presentPercent || 0))}% present. You can update below.</div> : null}

              {attendanceSectionInfo && Number(attendanceSectionInfo.totalEnrolled || 0) > 0 ? <div className="ck-two-col" style={{ gridTemplateColumns: '280px 1fr', marginBottom: 16 }}>
                <div className="ck-card">
                  <div className="ck-form-head">Present %</div>
                  <div className="ck-form-body" style={{ background: 'var(--color-background-secondary, var(--ck-bg))' }}>
                    <div style={{ fontSize: 32, fontWeight: 500, color: '#085041', lineHeight: 1.1 }}>{presentPct == null ? '—' : `${presentPct}%`}</div>
                    <div className="ts" style={{ marginTop: 6 }}>{presentPct == null ? 'enter count below' : `${presentCount} present · ${absentCount} absent`}</div>
                  </div>
                </div>
                <div className="ck-card">
                  <div className="ck-form-head">No. of students present</div>
                  <div className="ck-form-body">
                    <div className="ts" style={{ marginBottom: 8 }}>Enter how many out of {totalEnrolled} students are present today</div>
                    <div style={{ display: 'flex', gap: 8, alignItems: 'stretch' }}>
                      <input style={{ fontSize: 22, flex: 1 }} type="number" min={0} max={totalEnrolled} value={attendancePresentCount} onChange={(e) => { setAttendancePresentCount(e.target.value); setAttendanceSaveError(''); setAttendanceToast(''); }} />
                      <div style={{ display: 'grid', gap: 6 }}>
                        <button className="ck-btn ck-btn-ghost" type="button" onClick={() => setAttendancePresentCount(String(Math.min(totalEnrolled, (Number(attendancePresentCount || 0) + 1))))}>▲</button>
                        <button className="ck-btn ck-btn-ghost" type="button" onClick={() => setAttendancePresentCount(String(Math.max(0, (Number(attendancePresentCount || 0) - 1))))}>▼</button>
                      </div>
                    </div>
                    <div className="ck-mini-progress" style={{ marginTop: 10 }}><div className="ck-mini-progress-fill" style={{ width: `${presentPct || 0}%`, background: '#1D9E75' }} /></div>
                    <div className="ts" style={{ marginTop: 6 }}>{presentPct == null ? '—' : `${presentPct}% present — ${absentCount} absent`}</div>
                    {!Number.isFinite(presentCount) || validPresent ? <div className="ts" style={{ marginTop: 6, color: '#085041' }}>{presentPct == null ? '' : `${absentCount} students will be marked absent`}</div> : <div className="ts" style={{ marginTop: 6, color: '#A32D2D' }}>Cannot exceed total ({totalEnrolled})</div>}
                    <div style={{ marginTop: 12 }}><button className="ck-btn ck-btn-g" disabled={!validPresent || saving === 'attendance-save'} onClick={saveAttendanceEntry}>Save attendance</button></div>
                  </div>
                </div>
              </div> : null}
            </ModuleShell>
            );
          })()}

          {panel === 'timetable' && (
            <ModuleShell title="Timetable" subtitle="Academic year 2024–25" actions={<button className="ck-btn ck-btn-g" onClick={() => submitAction('timetable', api.post('/workspace/timetable', timetableForm), 'timetable')}>Add timetable entry</button>}>
              <div className="ck-card" style={{ marginBottom: 16 }}>
                <div className="ck-form-grid ck-fg-5">
                  <Field label="Day"><select value={timetableForm.day} onChange={(e) => setTimetableForm({ ...timetableForm, day: e.target.value })}><option>Monday</option><option>Tuesday</option><option>Wednesday</option><option>Thursday</option><option>Friday</option></select></Field>
                  <Field label="Period"><input value={timetableForm.period} onChange={(e) => setTimetableForm({ ...timetableForm, period: e.target.value })} /></Field>
                  <Field label="Class"><input value={timetableForm.classSection} onChange={(e) => setTimetableForm({ ...timetableForm, classSection: e.target.value })} /></Field>
                  <Field label="Subject"><input value={timetableForm.subject} onChange={(e) => setTimetableForm({ ...timetableForm, subject: e.target.value })} /></Field>
                  <Field label="Teacher"><input value={timetableForm.teacher} onChange={(e) => setTimetableForm({ ...timetableForm, teacher: e.target.value })} /></Field>
                </div>
              </div>
              <div className="ck-card"><table className="ck-table"><thead><tr><th>Day</th><th>Period</th><th>Class</th><th>Subject</th><th>Teacher</th></tr></thead><tbody>{workspace.timetable.map((row: any, i: number) => <tr key={i}><td>{row.day}</td><td>{row.period}</td><td>{row.classSection}</td><td>{row.subject}</td><td>{row.teacher}</td></tr>)}</tbody></table></div>
            </ModuleShell>
          )}

          {panel === 'staff' && (
            <ModuleShell title="Staff & HR" subtitle="68 staff · Payroll pending ₹12.4L" actions={<button className="ck-btn ck-btn-g" onClick={() => submitAction('staff', api.post('/workspace/staff', staffForm), 'staff')}>+ Add staff</button>}>
              <div className="ck-card" style={{ marginBottom: 16 }}>
                <div className="ck-form-grid ck-fg-5">
                  <Field label="Name"><input value={staffForm.name} onChange={(e) => setStaffForm({ ...staffForm, name: e.target.value })} /></Field>
                  <Field label="Designation"><input value={staffForm.designation} onChange={(e) => setStaffForm({ ...staffForm, designation: e.target.value })} /></Field>
                  <Field label="Department"><input value={staffForm.department} onChange={(e) => setStaffForm({ ...staffForm, department: e.target.value })} /></Field>
                  <Field label="Monthly salary"><input value={staffForm.monthlySalary} onChange={(e) => setStaffForm({ ...staffForm, monthlySalary: e.target.value })} /></Field>
                  <Field label="Payroll"><select value={staffForm.payrollStatus} onChange={(e) => setStaffForm({ ...staffForm, payrollStatus: e.target.value })}><option>Pending</option><option>Processed</option></select></Field>
                </div>
              </div>
              <div className="ck-card"><table className="ck-table"><thead><tr><th>Name</th><th>Designation</th><th>Department</th><th>Payroll</th><th>Monthly salary</th></tr></thead><tbody>{workspace.staff.map((row: any) => <tr key={row.id}><td>{row.name}</td><td>{row.designation}</td><td>{row.department}</td><td><span className={`ck-status ${row.payrollStatus === 'Processed' ? 'sg' : 'sam'}`}>{row.payrollStatus}</span></td><td>₹{formatMoney(row.monthlySalary)}</td></tr>)}</tbody></table></div>
            </ModuleShell>
          )}

          {panel === 'catalog' && (
            <div className="ck-content">
              <div className="ck-ph">
                <div>
                  <h1 className="ck-page-title">Catalog</h1>
                  <p className="ck-page-sub">Order directly from Custoking — uniforms, stationery, ID cards, services and more</p>
                </div>
                <button className="ck-btn ck-btn-ghost" onClick={() => setPanel('orders')}>View my orders →</button>
              </div>

              {catalogNotice && (
                <div className={`ck-alert ${catalogNotice.type === 'error' ? 'ck-alert-re' : 'ck-alert-g'}`} style={{ marginBottom: 16 }}>
                  <span>{catalogNotice.type === 'error' ? '✕' : catalogNotice.type === 'draft' ? '✦' : '✓'}</span>
                  <div>{catalogNotice.msg}</div>
                </div>
              )}

              {activeCat === null ? (
                <>
                  <div style={{ position: 'relative', marginBottom: 22 }}>
                    <input
                      placeholder="Search catalog — uniforms, notebooks, ID cards…"
                      value={catalogSearch}
                      onChange={(e) => setCatalogSearch(e.target.value)}
                      style={{ width: '100%', border: '1px solid var(--border2)', borderRadius: 20, padding: '10px 20px 10px 44px', fontSize: 13.5, fontFamily: 'DM Sans, sans-serif', outline: 'none', background: 'var(--white)' }}
                    />
                    <span style={{ position: 'absolute', left: 16, top: '50%', transform: 'translateY(-50%)', color: 'var(--ink3)', fontSize: 15 }}>🔍</span>
                  </div>
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 14, marginBottom: 28 }}>
                    {catalogTiles
                      .filter((c) => !catalogSearch || `${c.name} ${c.desc}`.toLowerCase().includes(catalogSearch.toLowerCase()))
                      .map((c) => (
                        <div key={c.key} onClick={() => { setActiveCat(c.key); setCatalogNotice(null); }} style={{ background: 'var(--white)', border: '1px solid var(--border)', borderRadius: 'var(--r)', overflow: 'hidden', cursor: 'pointer' }}>
                          <div style={{ height: 90, background: c.headerBg, position: 'relative', overflow: 'hidden' }}>
                            <img src={`https://source.unsplash.com/featured/400x200/?${c.imgQ}`} alt={c.name} loading="lazy" style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block', position: 'absolute', inset: 0 }} onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }} />
                            <span style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 38 }}>{c.emoji}</span>
                          </div>
                          <div style={{ padding: '13px 14px 14px' }}>
                            <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 3 }}>{c.name}</div>
                            <div style={{ fontSize: 12, color: 'var(--ink3)', marginBottom: 10 }}>{c.desc}</div>
                            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                              <span className={`pill ${c.pillClass}`}>{c.pill}</span>
                              <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--g)' }}>Order →</span>
                            </div>
                          </div>
                        </div>
                      ))}
                    <div onClick={() => setPanel('ff-new')} style={{ background: 'var(--or1)', border: '1.5px dashed var(--or2)', borderRadius: 'var(--r)', padding: 14, cursor: 'pointer', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', textAlign: 'center', minHeight: 175 }}>
                      <div style={{ fontSize: 30, marginBottom: 8 }}>🔥</div>
                      <div style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--or)', marginBottom: 4 }}>Don't see what you need?</div>
                      <div style={{ fontSize: 12, color: 'var(--or)', opacity: .8 }}>Use Firefighting to raise a custom request with quotations</div>
                    </div>
                  </div>
                </>
              ) : (
                <div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 22 }}>
                    <button className="ck-btn ck-btn-ghost" style={{ fontSize: 12 }} onClick={() => { setActiveCat(null); setCatalogNotice(null); }}>← Back to catalog</button>
                    <span style={{ fontSize: 13, color: 'var(--ink3)' }}>Catalog / {activeCat}</span>
                  </div>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 280px', gap: 18 }}>
                    <div>
                      <div className="ck-form-card">
                        <div className="ck-form-head">
                          {activeCat === 'UNIFORMS' ? '👕 Uniform order' : activeCat === 'NOTEBOOKS' ? '📓 Notebooks order' : activeCat === 'STATIONERY' ? '🖊 Stationery order' : activeCat === 'IDCARDS' ? '🪪 ID card order' : activeCat === 'HOUSEKEEPING' ? '🧹 Housekeeping service' : activeCat === 'EVENTS' ? '🏆 Events & print order' : '🩺 Health & safety order'}
                        </div>
                        <div className="ck-form-body">
                          {activeCat === 'UNIFORMS' && (
                            <>
                              <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 10 }}>
                                <div style={{ fontSize: 20 }}>👕</div>
                                <div>
                                  <div style={{ fontFamily: 'Merriweather, serif', fontSize: 18, fontWeight: 700 }}>Uniform order</div>
                                  <div className="ts">School uniforms, PE kit, blazers, ties — with school branding</div>
                                </div>
                              </div>
                              <div className="ck-form-card" style={{ boxShadow: 'none', borderRadius: 18 }}>
                                <div className="ck-form-head">Order details</div>
                                <div className="ck-form-body">
                                  <div className="ck-form-grid ck-fg-2" style={{ marginBottom: 18 }}>
                                    <div className="field"><label>Academic year</label><select value={uniformForm.academicYear} onChange={(e) => setUniformForm((f) => ({ ...f, academicYear: e.target.value }))}><option>2024–25</option><option>2025–26</option></select></div>
                                    <div className="field"><label>Required by date *</label><input type="date" value={uniformForm.requiredByDate} onChange={(e) => setUniformForm((f) => ({ ...f, requiredByDate: e.target.value }))} /></div>
                                    <div className="field"><label>Class group</label><select value={uniformForm.classGroup} onChange={(e) => setUniformForm((f) => ({ ...f, classGroup: e.target.value }))}><option>Class 1–5</option><option>Class 6–8</option><option>Class 9–10</option><option>Class 11–12</option><option>All classes</option></select></div>
                                    <div className="field"><label>Logo on uniform</label><select value={uniformForm.logoOnUniform} onChange={(e) => setUniformForm((f) => ({ ...f, logoOnUniform: e.target.value }))}><option>Yes — school logo embroidered</option><option>Yes — printed logo</option><option>No logo</option></select></div>
                                  </div>
                                  <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--ink3)', letterSpacing: '.08em', textTransform: 'uppercase', marginBottom: 10 }}>Items & quantities</div>
                                  <table style={{ width: '100%', borderCollapse: 'separate', borderSpacing: 0, border: '1px solid var(--border)', borderRadius: 14, overflow: 'hidden' }}>
                                    <thead><tr style={{ background: 'var(--bg)' }}><th style={thStyle}>Item</th><th style={thStyle}>Size breakdown</th><th style={thStyle}>Qty</th><th style={thStyle}>Unit ₹</th><th style={thStyle}>Total</th></tr></thead>
                                    <tbody>{uniformForm.items.map((row, i) => <tr key={i}><td style={tdStyle}><input value={row.name} onChange={(e) => setUniformForm((f) => { const items = [...f.items]; items[i] = { ...items[i], name: e.target.value }; return { ...f, items }; })} style={{ ...inlineInputStyle, minWidth: 180 }} placeholder="Item name" /></td><td style={tdStyle}><input value={row.sizeBreakdown || ''} onChange={(e) => setUniformForm((f) => { const items = [...f.items]; items[i] = { ...items[i], sizeBreakdown: e.target.value }; return { ...f, items }; })} style={{ ...inlineInputStyle, minWidth: 180 }} placeholder="S:20 M:60 L:15 XL:5" /></td><td style={tdStyle}><input type="number" value={row.qty} onChange={(e) => setUniformForm((f) => { const items = [...f.items]; items[i] = { ...items[i], qty: +e.target.value || 0 }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 90 }} /></td><td style={tdStyle}><input type="number" value={row.unitPrice} onChange={(e) => setUniformForm((f) => { const items = [...f.items]; items[i] = { ...items[i], unitPrice: +e.target.value || 0 }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 100 }} /></td><td style={{ ...tdStyle, fontWeight: 700, color: 'var(--g)' }}>₹{formatMoney((row.qty || 0) * (row.unitPrice || 0))}</td></tr>)}</tbody>
                                  </table>
                                  <div style={{ marginTop: 12 }}><button className="ck-btn ck-btn-ghost" onClick={addUniformItem}>+ Add item</button></div>
                                  <div className="field" style={{ marginTop: 18 }}><label>Special instructions</label><input value={uniformForm.specialInstructions} onChange={(e) => setUniformForm((f) => ({ ...f, specialInstructions: e.target.value }))} placeholder="e.g. school name on collar, colour spec, packaging..." /></div>
                                </div>
                              </div>
                            </>
                          )}
                          {activeCat === 'NOTEBOOKS' && (
                            <>
                              <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 10 }}>
                                <div style={{ fontSize: 20 }}>📓</div>
                                <div>
                                  <div style={{ fontFamily: 'Merriweather, serif', fontSize: 18, fontWeight: 700 }}>Notebooks order</div>
                                  <div className="ts">Ruled, plain, graph, school diary — with school logo cover</div>
                                </div>
                              </div>
                              <div className="ck-form-card" style={{ boxShadow: 'none', borderRadius: 18 }}>
                                <div className="ck-form-head">Order details</div>
                                <div className="ck-form-body">
                                  <div className="ck-form-grid ck-fg-3" style={{ marginBottom: 18 }}>
                                    <div className="field"><label>No. of students *</label><input type="number" value={notebookForm.numStudents} onChange={(e) => setNotebookForm((f) => ({ ...f, numStudents: +e.target.value || 0 }))} /></div>
                                    <div className="field"><label>Notebooks per student</label><input type="number" value={notebookForm.notebooksPerStudent} onChange={(e) => setNotebookForm((f) => ({ ...f, notebooksPerStudent: +e.target.value || 0 }))} /></div>
                                    <div className="field"><label>Required by</label><input type="date" value={notebookForm.requiredByDate} onChange={(e) => setNotebookForm((f) => ({ ...f, requiredByDate: e.target.value }))} /></div>
                                    <div className="field"><label>Cover logo</label><select value={notebookForm.coverLogo} onChange={(e) => setNotebookForm((f) => ({ ...f, coverLogo: e.target.value }))}><option>School logo — printed</option><option>School logo — embossed</option><option>No logo</option></select></div>
                                    <div className="field"><label>Delivery</label><select value={notebookForm.delivery} onChange={(e) => setNotebookForm((f) => ({ ...f, delivery: e.target.value }))}><option>Deliver to school</option><option>Warehouse pickup</option></select></div>
                                    <div className="field"><label>School name on spine</label><select value={notebookForm.schoolNameOnSpine} onChange={(e) => setNotebookForm((f) => ({ ...f, schoolNameOnSpine: e.target.value }))}><option>Yes</option><option>No</option></select></div>
                                  </div>
                                  <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--ink3)', letterSpacing: '.08em', textTransform: 'uppercase', marginBottom: 10 }}>Notebook types</div>
                                  <table style={{ width: '100%', borderCollapse: 'separate', borderSpacing: 0, border: '1px solid var(--border)', borderRadius: 14, overflow: 'hidden' }}>
                                    <thead><tr style={{ background: 'var(--bg)' }}><th style={thStyle}>Type</th><th style={thStyle}>Size</th><th style={thStyle}>Pages</th><th style={thStyle}>Qty</th><th style={thStyle}>Unit ₹</th><th style={thStyle}>Total</th></tr></thead>
                                    <tbody>{notebookForm.items.map((row, i) => <tr key={i}><td style={tdStyle}><input value={row.type} onChange={(e) => setNotebookForm((f) => { const items = [...f.items]; items[i] = { ...items[i], type: e.target.value }; return { ...f, items }; })} style={{ ...inlineInputStyle, minWidth: 160 }} placeholder="Notebook type" /></td><td style={tdStyle}><select value={row.size} onChange={(e) => setNotebookForm((f) => { const items = [...f.items]; items[i] = { ...items[i], size: e.target.value }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 84 }}><option>A4</option><option>A5</option><option>Long</option></select></td><td style={tdStyle}><select value={row.pages} onChange={(e) => setNotebookForm((f) => { const items = [...f.items]; items[i] = { ...items[i], pages: e.target.value }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 92 }}><option>60 pg</option><option>80 pg</option><option>120</option><option>160</option><option>200 pg</option></select></td><td style={tdStyle}><input type="number" value={row.qty} onChange={(e) => setNotebookForm((f) => { const items = [...f.items]; items[i] = { ...items[i], qty: +e.target.value || 0 }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 96 }} /></td><td style={tdStyle}><input type="number" value={row.unitPrice} onChange={(e) => setNotebookForm((f) => { const items = [...f.items]; items[i] = { ...items[i], unitPrice: +e.target.value || 0 }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 96 }} /></td><td style={{ ...tdStyle, fontWeight: 700, color: 'var(--g)' }}>₹{formatMoney((row.qty || 0) * (row.unitPrice || 0))}</td></tr>)}</tbody>
                                  </table>
                                  <div style={{ marginTop: 12 }}><button className="ck-btn ck-btn-ghost" onClick={addNotebookType}>+ Add type</button></div>
                                </div>
                              </div>
                            </>
                          )}
                          {activeCat === 'STATIONERY' && (
                            <>
                              <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 10 }}>
                                <div style={{ fontSize: 20 }}>🖊</div>
                                <div>
                                  <div style={{ fontFamily: 'Merriweather, serif', fontSize: 18, fontWeight: 700 }}>Stationery order</div>
                                  <div className="ts">Per-student kit or bulk supply — pens, pencils, rulers, craft supplies</div>
                                </div>
                              </div>
                              <div className="ck-form-card" style={{ boxShadow: 'none', borderRadius: 18 }}>
                                <div className="ck-form-head">Order details</div>
                                <div className="ck-form-body">
                                  <div className="ck-form-grid ck-fg-3" style={{ marginBottom: 18 }}>
                                    <div className="field"><label>Pack type</label><select value={stationeryForm.packType} onChange={(e) => setStationeryForm((f) => ({ ...f, packType: e.target.value }))}><option>Per-student kit</option><option>Bulk classroom supply</option></select></div>
                                    <div className="field"><label>Number of kits *</label><input type="number" value={stationeryForm.numKits} onChange={(e) => setStationeryForm((f) => ({ ...f, numKits: +e.target.value || 0 }))} /></div>
                                    <div className="field"><label>Required by</label><input type="date" value={stationeryForm.requiredByDate} onChange={(e) => setStationeryForm((f) => ({ ...f, requiredByDate: e.target.value }))} /></div>
                                  </div>
                                  <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--ink3)', letterSpacing: '.08em', textTransform: 'uppercase', marginBottom: 10 }}>Kit contents</div>
                                  <table style={{ width: '100%', borderCollapse: 'separate', borderSpacing: 0, border: '1px solid var(--border)', borderRadius: 14, overflow: 'hidden' }}>
                                    <thead><tr style={{ background: 'var(--bg)' }}><th style={thStyle}>Item</th><th style={thStyle}>Brand / spec</th><th style={thStyle}>Per kit</th><th style={thStyle}>Unit ₹</th><th style={thStyle}>Kit sub</th></tr></thead>
                                    <tbody>{stationeryForm.items.map((row, i) => <tr key={i}><td style={tdStyle}><input value={row.name} onChange={(e) => setStationeryForm((f) => { const items = [...f.items]; items[i] = { ...items[i], name: e.target.value }; return { ...f, items }; })} style={{ ...inlineInputStyle, minWidth: 180 }} placeholder="Item" /></td><td style={tdStyle}><input value={row.brand} onChange={(e) => setStationeryForm((f) => { const items = [...f.items]; items[i] = { ...items[i], brand: e.target.value }; return { ...f, items }; })} style={{ ...inlineInputStyle, minWidth: 180 }} placeholder="Brand / spec" /></td><td style={tdStyle}><input type="number" value={row.perKit} onChange={(e) => setStationeryForm((f) => { const items = [...f.items]; items[i] = { ...items[i], perKit: +e.target.value || 0 }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 84 }} /></td><td style={tdStyle}><input type="number" value={row.unitPrice} onChange={(e) => setStationeryForm((f) => { const items = [...f.items]; items[i] = { ...items[i], unitPrice: +e.target.value || 0 }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 90 }} /></td><td style={{ ...tdStyle, fontWeight: 700, color: 'var(--g)' }}>₹{formatMoney((row.perKit || 0) * (row.unitPrice || 0))}</td></tr>)}</tbody>
                                  </table>
                                  <div style={{ marginTop: 12 }}><button className="ck-btn ck-btn-ghost" onClick={addStationeryItem}>+ Add item</button></div>
                                </div>
                              </div>
                            </>
                          )}
                          {activeCat === 'IDCARDS' && (
                            <div className="ck-form-grid ck-fg-3">
                              <div className="field"><label>Student count</label><input type="number" value={idCardForm.studentCount} onChange={(e) => setIdCardForm((f) => ({ ...f, studentCount: +e.target.value || 0 }))} /></div>
                              <div className="field"><label>Staff count</label><input type="number" value={idCardForm.staffCount} onChange={(e) => setIdCardForm((f) => ({ ...f, staffCount: +e.target.value || 0 }))} /></div>
                              <div className="field"><label>Spare cards</label><input type="number" value={idCardForm.spareCards} onChange={(e) => setIdCardForm((f) => ({ ...f, spareCards: +e.target.value || 0 }))} /></div>
                            </div>
                          )}
                          {activeCat === 'HOUSEKEEPING' && (
                            <div className="ck-form-grid ck-fg-2">
                              <div className="field"><label>Duration</label><select value={housekeepingForm.duration} onChange={(e) => setHousekeepingForm((f) => ({ ...f, duration: e.target.value }))}><option>1 month</option><option>3 months</option><option>6 months</option><option>Academic year</option></select></div>
                              <div className="field"><label>Staff required</label><input type="number" value={housekeepingForm.staffRequired} onChange={(e) => setHousekeepingForm((f) => ({ ...f, staffRequired: +e.target.value || 0 }))} /></div>
                            </div>
                          )}
                          {activeCat === 'EVENTS' && (
                            <>
                              <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 10 }}>
                                <div style={{ fontSize: 20 }}>🏆</div>
                                <div>
                                  <div style={{ fontFamily: 'Merriweather, serif', fontSize: 18, fontWeight: 700 }}>Events & print</div>
                                  <div className="ts">Trophies, certificates, banners, backdrops for school events</div>
                                </div>
                              </div>
                              <div className="ck-form-card" style={{ boxShadow: 'none', borderRadius: 18 }}>
                                <div className="ck-form-head">Event details</div>
                                <div className="ck-form-body">
                                  <div className="ck-form-grid ck-fg-2" style={{ marginBottom: 18 }}>
                                    <div className="field"><label>Event name</label><input value={eventsForm.eventName} onChange={(e) => setEventsForm((f) => ({ ...f, eventName: e.target.value }))} placeholder="e.g. Annual Day 2025" /></div>
                                    <div className="field"><label>Event date</label><input type="date" value={eventsForm.eventDate} onChange={(e) => setEventsForm((f) => ({ ...f, eventDate: e.target.value }))} /></div>
                                    <div className="field"><label>Delivery deadline</label><input type="date" value={eventsForm.deliveryDeadline} onChange={(e) => setEventsForm((f) => ({ ...f, deliveryDeadline: e.target.value }))} /></div>
                                  </div>
                                  <table style={{ width: '100%', borderCollapse: 'separate', borderSpacing: 0, border: '1px solid var(--border)', borderRadius: 14, overflow: 'hidden' }}>
                                    <thead><tr style={{ background: 'var(--bg)' }}><th style={thStyle}>Item</th><th style={thStyle}>Spec</th><th style={thStyle}>Qty</th><th style={thStyle}>Unit ₹</th><th style={thStyle}>Total</th></tr></thead>
                                    <tbody>{eventsForm.items.map((row, i) => <tr key={i}><td style={tdStyle}><input value={row.name} onChange={(e) => setEventsForm((f) => { const items = [...f.items]; items[i] = { ...items[i], name: e.target.value }; return { ...f, items }; })} style={{ ...inlineInputStyle, minWidth: 180 }} placeholder="Item" /></td><td style={tdStyle}><input value={row.spec} onChange={(e) => setEventsForm((f) => { const items = [...f.items]; items[i] = { ...items[i], spec: e.target.value }; return { ...f, items }; })} style={{ ...inlineInputStyle, minWidth: 220 }} placeholder="Specification" /></td><td style={tdStyle}><input type="number" value={row.qty} onChange={(e) => setEventsForm((f) => { const items = [...f.items]; items[i] = { ...items[i], qty: +e.target.value || 0 }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 88 }} /></td><td style={tdStyle}><input type="number" value={row.unitPrice} onChange={(e) => setEventsForm((f) => { const items = [...f.items]; items[i] = { ...items[i], unitPrice: +e.target.value || 0 }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 96 }} /></td><td style={{ ...tdStyle, fontWeight: 700, color: 'var(--g)' }}>₹{formatMoney((row.qty || 0) * (row.unitPrice || 0))}</td></tr>)}</tbody>
                                  </table>
                                  <div style={{ marginTop: 12 }}><button className="ck-btn ck-btn-ghost" onClick={addEventItem}>+ Add item</button></div>
                                  <div style={{ marginTop: 18, background: 'var(--bg)', borderRadius: 12, padding: '16px 18px', display: 'flex', justifyContent: 'space-between', fontSize: 16 }}><span>Event total</span><strong style={{ color: 'var(--g)' }}>₹{formatMoney(calcEvents().totalRs)}</strong></div>
                                </div>
                              </div>
                            </>
                          )}
                          {activeCat === 'HEALTH' && (
                            <table style={{ width: '100%', borderCollapse: 'collapse', border: '1px solid var(--border)' }}><thead><tr style={{ background: 'var(--bg)' }}><th style={thStyle}>Item</th><th style={thStyle}>Qty</th><th style={thStyle}>Unit ₹</th></tr></thead><tbody>{healthForm.items.map((row, i) => <tr key={i}><td style={tdStyle}>{row.name}</td><td style={tdStyle}><input type="number" value={row.qty} onChange={(e) => setHealthForm((f) => { const items = [...f.items]; items[i] = { ...items[i], qty: +e.target.value || 0 }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 80 }} /></td><td style={tdStyle}><input type="number" value={row.unitPrice} onChange={(e) => setHealthForm((f) => { const items = [...f.items]; items[i] = { ...items[i], unitPrice: +e.target.value || 0 }; return { ...f, items }; })} style={{ ...inlineInputStyle, width: 80 }} /></td></tr>)}</tbody></table>
                          )}
                        </div>
                      </div>
                    </div>
                    <OrderSummaryPanel
                      accentVar="var(--g)"
                      borderVar="var(--g2)"
                      lines={
                        activeCat === 'UNIFORMS' ? uniformSummaryLines() :
                        activeCat === 'NOTEBOOKS' ? [...notebookForm.items.map((r) => ({ label: `${r.type || 'Notebook'} · ${r.qty} units`, value: r.qty * r.unitPrice })), { label: 'GST 12%', value: calcNotebook().gstRs }] :
                        activeCat === 'STATIONERY' ? [{ label: 'Kit cost per student', value: stationeryForm.items.reduce((s, r) => s + r.perKit * r.unitPrice, 0) }, { label: `${stationeryForm.numKits} kits`, value: calcStationery().subtotalRs }, { label: 'GST 12%', value: calcStationery().gstRs }] :
                        activeCat === 'IDCARDS' ? [{ label: `${calcIdCard().total} cards`, value: calcIdCard().subtotalRs }, { label: 'GST', value: calcIdCard().gstRs }] :
                        activeCat === 'HOUSEKEEPING' ? [{ label: `${housekeepingForm.staffRequired} staff`, value: calcHousekeeping().subtotalRs }] :
                        activeCat === 'EVENTS' ? eventsForm.items.map((r) => ({ label: `${r.name || 'Item'} × ${r.qty}`, value: r.qty * r.unitPrice })) :
                        healthForm.items.map((r) => ({ label: `${r.name} × ${r.qty}`, value: r.qty * r.unitPrice }))
                      }
                      total={
                        activeCat === 'UNIFORMS' ? calcUniform().totalRs :
                        activeCat === 'NOTEBOOKS' ? calcNotebook().totalRs :
                        activeCat === 'STATIONERY' ? calcStationery().totalRs :
                        activeCat === 'IDCARDS' ? calcIdCard().totalRs :
                        activeCat === 'HOUSEKEEPING' ? calcHousekeeping().totalRs :
                        activeCat === 'EVENTS' ? calcEvents().totalRs :
                        calcHealth().totalRs
                      }
                      delivery={activeCat === 'UNIFORMS' ? '3–4 weeks' : activeCat === 'NOTEBOOKS' ? '1–2 weeks' : activeCat === 'STATIONERY' ? '5–7 days' : activeCat === 'HOUSEKEEPING' ? 'Service contract' : activeCat === 'EVENTS' ? 'Ready as planned' : '3–10 days'}
                      saving={catalogSaving}
                      onPlace={() => submitCatalogOrder(
                        activeCat,
                        activeCat === 'UNIFORMS' ? uniformForm : activeCat === 'NOTEBOOKS' ? notebookForm : activeCat === 'STATIONERY' ? stationeryForm : activeCat === 'IDCARDS' ? idCardForm : activeCat === 'HOUSEKEEPING' ? housekeepingForm : activeCat === 'EVENTS' ? eventsForm : healthForm,
                        activeCat === 'UNIFORMS' ? calcUniform() : activeCat === 'NOTEBOOKS' ? calcNotebook() : activeCat === 'STATIONERY' ? calcStationery() : activeCat === 'IDCARDS' ? calcIdCard() : activeCat === 'HOUSEKEEPING' ? calcHousekeeping() : activeCat === 'EVENTS' ? calcEvents() : calcHealth(),
                        activeCat === 'UNIFORMS' ? uniformForm.requiredByDate : activeCat === 'NOTEBOOKS' ? notebookForm.requiredByDate : activeCat === 'STATIONERY' ? stationeryForm.requiredByDate : activeCat === 'IDCARDS' ? idCardForm.requiredByDate : activeCat === 'HOUSEKEEPING' ? housekeepingForm.startDate : activeCat === 'EVENTS' ? eventsForm.deliveryDeadline : healthForm.requiredByDate,
                        true
                      )}
                      onDraft={() => submitCatalogOrder(
                        activeCat,
                        activeCat === 'UNIFORMS' ? uniformForm : activeCat === 'NOTEBOOKS' ? notebookForm : activeCat === 'STATIONERY' ? stationeryForm : activeCat === 'IDCARDS' ? idCardForm : activeCat === 'HOUSEKEEPING' ? housekeepingForm : activeCat === 'EVENTS' ? eventsForm : healthForm,
                        activeCat === 'UNIFORMS' ? calcUniform() : activeCat === 'NOTEBOOKS' ? calcNotebook() : activeCat === 'STATIONERY' ? calcStationery() : activeCat === 'IDCARDS' ? calcIdCard() : activeCat === 'HOUSEKEEPING' ? calcHousekeeping() : activeCat === 'EVENTS' ? calcEvents() : calcHealth(),
                        activeCat === 'UNIFORMS' ? uniformForm.requiredByDate : activeCat === 'NOTEBOOKS' ? notebookForm.requiredByDate : activeCat === 'STATIONERY' ? stationeryForm.requiredByDate : activeCat === 'IDCARDS' ? idCardForm.requiredByDate : activeCat === 'HOUSEKEEPING' ? housekeepingForm.startDate : activeCat === 'EVENTS' ? eventsForm.deliveryDeadline : healthForm.requiredByDate,
                        false
                      )}
                    />
                  </div>
                </div>
              )}
            </div>
          )}

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
          {panel === 'sa-erp' && user?.role === 'SUPERADMIN' && (<ModuleShell title="ERP activity" subtitle="Placeholder panel for future ERP activity analytics"><div className="ck-card"><div style={{ padding: 24, color: 'var(--ink2)' }}>ERP activity panel is ready for future data wiring.</div></div></ModuleShell>)}
          {panel === 'sa-revenue' && user?.role === 'SUPERADMIN' && (<ModuleShell title="Revenue" subtitle="Placeholder panel for future revenue analytics"><div className="ck-card"><div style={{ padding: 24, color: 'var(--ink2)' }}>Revenue panel is ready for future analytics.</div></div></ModuleShell>)}
          {panel === 'sa-catalog' && user?.role === 'SUPERADMIN' && (<ModuleShell title="Catalog management" subtitle="Placeholder panel for future superadmin catalog controls"><div className="ck-card"><div style={{ padding: 24, color: 'var(--ink2)' }}>Catalog management panel is ready for future controls.</div></div></ModuleShell>)}
          {panel === 'orders' && user?.role !== 'SUPERADMIN' && (<ModuleShell title="My orders" subtitle="All supply orders from Custoking — track, reorder, download invoices" actions={<button className="ck-btn ck-btn-g" onClick={() => setPanel('catalog')}>+ New order</button>}>{catalogNotice ? <div className={`ck-alert ${catalogNotice.type === 'error' ? 'ck-alert-re' : 'ck-alert-g'}`} style={{ marginBottom: 16 }}><span>{catalogNotice.type === 'error' ? '✕' : '✓'}</span><div>{catalogNotice.msg}</div></div> : null}<div className="ck-grid ck-grid-4" style={{ marginBottom: 16 }}><Stat label="Active orders" value={orderStats?.activeOrders ?? 0} sub="Awaiting, processing, transit" pill="Live" tone="blue" /><Stat label="Term spend" value={`₹${formatMoney(Math.round(Number(orderStats?.termSpend || 0) / 100))}`} sub="Placed this term" pill="Paise→₹" tone="green" /><Stat label="Active services" value={orderStats?.activeServices ?? 0} sub="Running contracts" pill="Service" tone="blue" /><Stat label="Delivered" value={orderStats?.deliveredCount ?? 0} sub="Completed orders" pill="Closed" tone="orange" /></div><div className="ck-card">{ordersLoading ? <div style={{ padding: 16 }}>Loading orders…</div> : <table className="ck-table"><thead><tr><th>Order</th><th>Category</th><th>Items</th><th>Amount</th><th>Status</th><th>Date</th><th /></tr></thead><tbody>{orderRows.map((row: any, i: number) => <tr key={i}><td><div className="tb">{row.id || row.code}</div><div className="ts">{row.description || row.title || row.category}</div></td><td>{row.category}</td><td>{row.items}</td><td>₹{formatMoney(Math.round(Number(row.totalAmount ?? row.amount ?? 0) / 100))}</td><td><span className={`ck-status ${String(row.status).toUpperCase() === 'DELIVERED' ? 'sg' : String(row.status).toUpperCase() === 'APPROVED' ? 'sg' : String(row.status).toUpperCase().includes('PROCESS') ? 'sb2' : String(row.status).toUpperCase().includes('DESIGN') ? 'sam' : 'sgr'}`}>{prettyOrderStatus(row.status)}</span></td><td>{row.placedAt || row.date || '—'}</td><td>{String(row.status).toUpperCase() === 'DESIGN_APPROVAL' ? <button className="ck-btn ck-btn-ghost" onClick={() => markDesignApproved(row.id)}>Mark design approved</button> : <button className="ck-btn ck-btn-ghost" onClick={async () => { await api.post('/supply/orders', { category: row.category, orderData: row.orderData || JSON.stringify({ title: row.description || row.category }), subtotal: row.subtotal || 0, gst: row.gst || 0, totalAmount: row.totalAmount || 0, requiredByDate: row.requiredByDate || null, status: 'DRAFT', ...(schoolScopedParams || {}) }); loadLiveOrders(); }}>Reorder</button>}</td></tr>)}</tbody></table>}</div></ModuleShell>)}

          {panel === 'planning' && (
            <ModuleShell title="Annual plan" subtitle="Map your full-year supply requirements by term — lock in pricing early" actions={<><button className="ck-btn ck-btn-ghost">Export PDF</button><button className="ck-btn ck-btn-g" onClick={() => submitAction('plan', api.post('/workspace/annual-plan', planForm), 'planning')}>Save plan</button></>}>
              <div className="ck-alert ck-alert-g"><span>✦</span><div><strong>Custoking insight:</strong> Schools that plan annually save an average of 12% vs ad-hoc ordering. Your student count ({workspace.school.students}) is pre-filled — adjust quantities as needed and lock in Term 1 by March.</div></div>
              <div className="ck-card" style={{ marginBottom: 16 }}>
                <div className="ck-form-grid ck-fg-5">
                  <Field label="Term"><select value={planForm.term} onChange={(e) => setPlanForm({ ...planForm, term: e.target.value })}><option>Term 1</option><option>Term 2</option><option>Term 3</option></select></Field>
                  <Field label="Category"><input value={planForm.category} onChange={(e) => setPlanForm({ ...planForm, category: e.target.value })} /></Field>
                  <Field label="Quantity"><input value={planForm.quantity} onChange={(e) => setPlanForm({ ...planForm, quantity: e.target.value })} /></Field>
                  <Field label="Amount"><input value={planForm.amount} onChange={(e) => setPlanForm({ ...planForm, amount: e.target.value })} /></Field>
                  <Field label="Status"><select value={planForm.status} onChange={(e) => setPlanForm({ ...planForm, status: e.target.value })}><option>Planned</option><option>Draft</option><option>Locked</option></select></Field>
                </div>
              </div>
              <div className="ck-card"><table className="ck-table"><thead><tr><th>Term</th><th>Category</th><th>Status</th><th>Quantity</th><th>Amount</th></tr></thead><tbody>{workspace.annualPlan.terms.map((row: any, i: number) => <tr key={i}><td>{row.term}</td><td>{row.category}</td><td><span className={`ck-status ${row.status === 'Planned' ? 'sg' : 'sam'}`}>{row.status}</span></td><td>{row.quantity}</td><td>₹{formatMoney(row.amount)}</td></tr>)}</tbody></table></div>
            </ModuleShell>
          )}

          {panel === 'ff-dashboard' && (
            <ModuleShell title="🔥 Firefighting requests" subtitle="Non-catalog procurement with 2-quotation approval workflow" actions={<button className="ck-btn ck-btn-or" onClick={() => setPanel('ff-new')}>+ New request</button>}>
              <div className="ck-stats ck-s4">
                <Stat label="Active requests" value={workspace.firefighting.requests.length} sub="In pipeline" pill="2 need approval" tone="orange" />
                <Stat label="Total value" value="₹4.8L" sub="Non-catalog spend" pill="This year" tone="orange" />
                <Stat label="Custoking won" value="3" sub="Of 7 requests" pill="43% win rate" tone="green" />
                <Stat label="Fulfilled" value={workspace.firefighting.orders.length} sub="Completed orders" pill="All delivered" tone="green" />
              </div>
              <div className="ck-pipeline">{['DRAFT', 'QUOTES_SUBMITTED', 'AWAITING_APPROVAL', 'FULFILLED'].map((status) => <div className="ck-pipe-col" key={status}><div className="ck-pipe-head"><div className="ck-pipe-label">{status.replace(/_/g, ' ')}</div><div className="ck-pipe-count">{workspace.firefighting.requests.filter((r: any) => r.status === status).length}</div></div><div className="ck-pipe-body">{workspace.firefighting.requests.filter((r: any) => r.status === status).map((r: any) => <div className="ck-pipe-card" key={r.code}><div className="pc-id">{r.code}</div><div className="pc-title">{r.title}</div><div className="pc-meta">{r.summary}</div>{r.amount ? <div className="pc-amount">₹{formatMoney(r.amount)}</div> : null}</div>)}</div></div>)}</div>
            </ModuleShell>
          )}

          {panel === 'ff-new' && (
            <ModuleShell title="🔥 New request" subtitle="Raise a procurement request for anything not in the Custoking catalog">
              <div className="ck-step-bar"><div className="ck-step done"><span>1</span><div><strong>Describe need</strong><small>What do you need?</small></div></div><div className="ck-step active"><span>2</span><div><strong>Add quotations</strong><small>Min. 2 required</small></div></div><div className="ck-step"><span>3</span><div><strong>Review & submit</strong><small>Send for approval</small></div></div></div>
              <div className="ck-form-card"><div className="ck-form-head">What do you need?</div><div className="ck-form-body"><div className="ck-form-grid ck-fg-2"><Field label="Request title *"><input value={ffForm.title} onChange={(e) => setFfForm({ ...ffForm, title: e.target.value })} /></Field><Field label="Category"><select value={ffForm.category} onChange={(e) => setFfForm({ ...ffForm, category: e.target.value })}><option>Furniture & fixtures</option><option>Electronics & security</option><option>Lab equipment</option><option>Sports & playground</option><option>Services & AMC</option><option>Civil & construction</option><option>Events & occasions</option><option>Other</option></select></Field><Field label="Estimated budget (₹)"><input value={ffForm.amount} onChange={(e) => setFfForm({ ...ffForm, amount: e.target.value })} /></Field><Field label="Quotation count"><input value={ffForm.quotesCount} onChange={(e) => setFfForm({ ...ffForm, quotesCount: e.target.value })} /></Field></div><Field label="Description"><textarea value={ffForm.summary} onChange={(e) => setFfForm({ ...ffForm, summary: e.target.value })} /></Field><div className="ck-alert ck-alert-g" style={{ marginTop: 14 }}><span>✦</span><div>Custoking quote can be auto-marked recommended when it meets quantity, deadline and GST criteria.</div></div><div className="ck-actions-inline"><button className="ck-btn ck-btn-ghost">Save as draft</button><button className="ck-btn ck-btn-or" onClick={() => submitAction('ff', api.post('/workspace/firefighting', ffForm), 'ff-dashboard')}>Submit for approval →</button></div></div></div>
            </ModuleShell>
          )}

          {panel === 'ff-approvals' && (
            <ModuleShell title="🔥 Pending approvals" subtitle="Review quotations and approve requests">
              {firstApproval ? <div className="ck-approval-card"><div className="ck-approval-head"><div><div className="pc-id">{firstApproval.code} · Raised by Admin · {firstApproval.date}</div><div className="ck-approval-title">{firstApproval.title}</div></div><span className="ck-status sam">Awaiting your approval</span></div><div className="ck-approval-body"><div className="ck-approval-grid"><Info label="Category" value={firstApproval.category} /><Info label="Budget estimate" value={`₹${formatMoney(firstApproval.amount)}`} /><Info label="Quotes" value={String(firstApproval.quotesCount)} /><Info label="Preferred vendor" value={firstApproval.winner || '—'} /></div><div className="ck-alert ck-alert-g"><span>✦</span><div><strong>Custoking quote — all 6 criteria met</strong><div>Category supported · before deadline · feasible quantity · under budget · install included · GST invoice ready</div></div></div></div><div className="ck-approval-actions"><button className="ck-btn ck-btn-g" onClick={() => submitAction('ff-approve', api.post(`/workspace/firefighting/${firstApproval.code}/approve`), 'ff-orders')}>Approve & place order</button><button className="ck-btn ck-btn-ghost" onClick={() => submitAction('ff-reject', api.post(`/workspace/firefighting/${firstApproval.code}/reject`), 'ff-dashboard')}>Reject</button></div></div> : <div className="ck-card">No pending approvals.</div>}
            </ModuleShell>
          )}

          {panel === 'ff-orders' && (
            <ModuleShell title="🔥 Placed orders" subtitle="Firefighting requests fulfilled by Custoking">
              <div className="ck-card"><table className="ck-table"><thead><tr><th>Request</th><th>Category</th><th>Via</th><th>Amount</th><th>Status</th><th>Date</th></tr></thead><tbody>{workspace.firefighting.orders.map((row: any, i: number) => <tr key={i}><td><div className="tb">{row.code}</div><div className="ts">{row.title}</div></td><td>{row.category}</td><td>{row.via}</td><td>₹{formatMoney(row.amount)}</td><td><span className={`ck-status ${row.status === 'Delivered' ? 'sg' : 'sb2'}`}>{row.status}</span></td><td>{row.date}</td></tr>)}</tbody></table></div>
            </ModuleShell>
          )}
        </div>

        {saDetailOpen && (<div className="ck-modal-bg" onClick={() => setSaDetailOpen(false)}><div className="ck-modal" onClick={(e) => e.stopPropagation()}><div className="ck-modal-h"><div className="ck-modal-title">Order detail</div><button className="ck-modal-x" onClick={() => setSaDetailOpen(false)}>×</button></div><div className="ck-modal-body">{saDetailLoading ? <div className="ts">Loading order…</div> : saDetailError ? <div className="ts">{saDetailError}</div> : !saDetailOrder ? <div className="ts">Record not found.</div> : <><div className="ck-student-modal-info" style={{ marginBottom: 16 }}><Info label="Order ID" value={String(saDetailOrder.id || '—')} /><Info label="School" value={String(saDetailOrder.schoolName || saDetailOrder.school || '—')} /><Info label="Category" value={String(saDetailOrder.category || '—')} /><Info label="Amount" value={`₹${formatMoney(Math.round(Number(saDetailOrder.totalAmount || 0) / 100))}`} /><Info label="Delivery" value={String(saDetailOrder.estimatedDelivery || saDetailOrder.requiredByDate || '—')} /><Info label="Status" value={String(saDetailOrder.status || '—')} /></div><div className="ck-form-card"><div className="ck-form-head">Update status</div><div className="ck-form-body"><Field label="Status"><select value={saNewStatus} onChange={(e) => setSaNewStatus(e.target.value)}><option>AWAITING_APPROVAL</option><option>IN_PROGRESS</option><option>APPROVED</option><option>PROCESSING</option><option>DELIVERED</option></select></Field></div></div></>}</div><div className="ck-modal-foot"><button className="ck-btn ck-btn-ghost" onClick={() => saDetailOrder && openSaInvoiceFromOrder(saDetailOrder.id, saDetailOrder.schoolName || '—', saDetailOrder.schoolId ?? null, Number(saDetailOrder.totalAmount || 0))}>Generate invoice</button><button className="ck-btn ck-btn-ghost" onClick={() => alert(`WhatsApp sent to ${saDetailOrder?.schoolName || saDetailOrder?.school || 'school'}`)}>WhatsApp school</button><button className="ck-btn ck-btn-ghost" onClick={() => alert(`Downloading order sheet for ${saDetailOrder?.id || 'order'}`)}>Download order sheet</button><button className="ck-btn ck-btn-g" disabled={saStatusSaving} onClick={saveSaOrderStatus}>{saStatusSaving ? 'Saving…' : 'Update status'}</button></div></div></div>)}
        {saInvOpen && (<div className="ck-modal-bg" onClick={() => { setSaInvOpen(false); void loadSaInvoices(); }}><div className="ck-modal" onClick={(e) => e.stopPropagation()}><div className="ck-modal-h"><div className="ck-modal-title">Invoice</div><button className="ck-modal-x" onClick={() => { setSaInvOpen(false); void loadSaInvoices(); }}>×</button></div><div className="ck-modal-body">{saInvError ? <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>✕</span><div>{saInvError}</div></div> : null}<div className="ck-form-grid ck-fg-2"><Field label="Invoice number"><input value={saInvExistingId || 'Draft'} disabled /></Field><Field label="Bill to"><input value={saInvData.school || ''} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => setSaInvData({ ...saInvData, school: e.target.value })} /></Field><Field label="Order ref"><input value={saInvData.orderRef || ''} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => setSaInvData({ ...saInvData, orderRef: e.target.value })} /></Field><Field label="Description"><input value={saInvData.description || ''} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => setSaInvData({ ...saInvData, description: e.target.value })} /></Field><Field label="Qty"><input type="number" min="1" value={saInvData.qty || 1} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => { const qty = Number(e.target.value || 0); const rate = Number(saInvData.rate || 0); const amount = qty * rate; const gstAmount = Math.round(amount * 0.12); setSaInvData({ ...saInvData, qty, amount, gstAmount, total: amount + gstAmount }); }} /></Field><Field label="Rate (paise)"><input type="number" min="0" value={saInvData.rate || 0} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => { const rate = Number(e.target.value || 0); const qty = Number(saInvData.qty || 0); const amount = qty * rate; const gstAmount = Math.round(amount * 0.12); setSaInvData({ ...saInvData, rate, amount, gstAmount, total: amount + gstAmount }); }} /></Field><Field label="Status"><select value={saInvData.status || 'Awaiting payment'} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => setSaInvData({ ...saInvData, status: e.target.value })}><option>Awaiting payment</option><option>Paid</option></select></Field><Field label="Due date"><input type="date" value={saInvData.dueAt || ''} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => setSaInvData({ ...saInvData, dueAt: e.target.value })} /></Field></div><div className="ck-card" style={{ marginTop: 16 }}><div className="ck-form-body"><div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}><span>Subtotal</span><strong>₹{formatMoney(Math.round(Number(saInvData.amount || 0) / 100))}</strong></div><div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}><span>GST 12%</span><strong>₹{formatMoney(Math.round(Number(saInvData.gstAmount || 0) / 100))}</strong></div><div style={{ display: 'flex', justifyContent: 'space-between' }}><span>Total</span><strong>₹{formatMoney(Math.round(Number(saInvData.total || 0) / 100))}</strong></div></div></div></div><div className="ck-modal-foot"><button className="ck-btn ck-btn-ghost" onClick={() => alert(`Downloading ${(saInvExistingId || 'draft')}.pdf`)}>Download PDF</button>{saInvExistingId && !saInvEditing ? <button className="ck-btn ck-btn-ghost" onClick={() => setSaInvEditing(true)}>Edit invoice</button> : null}{saInvExistingId && saInvEditing ? <button className="ck-btn ck-btn-ghost" disabled={saInvSaving} onClick={saveSaInvoiceEdit}>{saInvSaving ? 'Saving…' : 'Save changes'}</button> : null}<button className="ck-btn ck-btn-g" disabled={saInvSaving} onClick={sendSaInvoice}>{saInvSaving ? 'Sending…' : 'Send to school'}</button></div></div></div>)}
        {saOnboardOpen && (<div className="ck-modal-bg" onClick={() => setSaOnboardOpen(false)}><div className="ck-modal" onClick={(e) => e.stopPropagation()}><div className="ck-modal-h"><div className="ck-modal-title">Onboard school</div><button className="ck-modal-x" onClick={() => setSaOnboardOpen(false)}>×</button></div><div className="ck-modal-body">{saOnboardErrors._ ? <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>✕</span><div>{saOnboardErrors._}</div></div> : null}<div className="ck-form-grid ck-fg-2"><Field label="School name *"><input value={saOnboardForm.name} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, name: e.target.value })} /></Field><Field label="Short code *"><input value={saOnboardForm.shortCode} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, shortCode: e.target.value })} /></Field><Field label="City *"><input value={saOnboardForm.city} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, city: e.target.value })} /></Field><Field label="State"><input value={saOnboardForm.state} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, state: e.target.value })} /></Field><Field label="No. of classes *"><input type="number" min={1} max={12} value={saOnboardForm.classCount} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, classCount: e.target.value })} />{saOnboardErrors.classCount ? <div className="ts" style={{ color: 'var(--re)', marginTop: 6 }}>{saOnboardErrors.classCount}</div> : <div className="ts" style={{ marginTop: 6 }}>Creates classes 1 to {saOnboardForm.classCount || 12}</div>}</Field><Field label="Sections per class *"><input type="number" min={1} max={26} value={saOnboardForm.sectionCount} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, sectionCount: e.target.value })} />{saOnboardErrors.sectionCount ? <div className="ts" style={{ color: 'var(--re)', marginTop: 6 }}>{saOnboardErrors.sectionCount}</div> : <div className="ts" style={{ marginTop: 6 }}>Creates sections A to {String.fromCharCode(64 + Math.max(1, Math.min(26, Number(saOnboardForm.sectionCount || 2))))}</div>}</Field><Field label="Contact email"><input value={saOnboardForm.contactEmail} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, contactEmail: e.target.value })} /></Field><Field label="Contact phone"><input value={saOnboardForm.contactPhone} onChange={(e) => setSaOnboardForm({ ...saOnboardForm, contactPhone: e.target.value })} /></Field></div></div><div className="ck-modal-foot"><button className="ck-btn ck-btn-ghost" onClick={() => setSaOnboardOpen(false)}>Cancel</button><button className="ck-btn ck-btn-g" disabled={saOnboardSaving} onClick={submitSaOnboard}>{saOnboardSaving ? 'Saving…' : 'Create school'}</button></div></div></div>)}
        {studentModalOpen && (
          <div className="ck-modal-bg" onClick={() => setStudentModalOpen(false)}>
            <div className="ck-modal" onClick={(e) => e.stopPropagation()}>
              <div className="ck-modal-h">
                <div className="ck-modal-title">Student details</div>
                <button className="ck-modal-x" onClick={() => setStudentModalOpen(false)}>×</button>
              </div>
              <div className="ck-modal-body">
                {studentModalLoading || !studentDetail ? (
                  <div className="ts">Loading student details…</div>
                ) : (
                  <div className="ck-student-modal-grid">
                    <div className="ck-student-modal-hero">
                      {studentDetail.photoUrl ? <img src={studentDetail.photoUrl} alt={studentDetail.name} className="ck-student-avatar ck-student-avatar-lg" /> : <div className="ck-student-avatar ck-student-avatar-fallback ck-student-avatar-lg">{initials(studentDetail.name)}</div>}
                      <div>
                        <div className="ck-modal-title" style={{ fontSize: 24 }}>{studentDetail.fullName || studentDetail.name}</div>
                        <div className="ts">{studentDetail.classSection} · {studentDetail.academicYear}</div>
                      </div>
                    </div>
                    <div className="ck-student-modal-info">
                      <Info label="Admission No" value={studentDetail.admissionNumber} />
                      <Info label="Roll No" value={studentDetail.rollNo} />
                      <Info label="Board Registration No" value={studentDetail.boardRegistrationNumber || '—'} />
                      <Info label="Father name" value={studentDetail.fatherName || '—'} />
                      <Info label="Father contact" value={studentDetail.fatherContact || studentDetail.parentPhone || '—'} />
                      <Info label="Mother name" value={studentDetail.motherName || '—'} />
                      <Info label="Date of birth" value={studentDetail.dateOfBirth || '—'} />
                    </div>
                    <div className="ck-form-card">
                      <div className="ck-form-head">Address</div>
                      <div className="ck-form-body">
                        <div className="ts">{formatAddress(studentDetail.address)}</div>
                      </div>
                    </div>
                    <div className="ck-form-card">
                      <div className="ck-form-head">Attendance & fees</div>
                      <div className="ck-form-body">
                        <div className="ck-progress-wrap">
                          <div className="ck-progress-label"><span>Attendance</span><strong>{studentDetail.attendance}</strong></div>
                          <div className="ck-progress-bar"><div className="ck-progress-fill" style={{ width: `${attendanceNumber(studentDetail.attendance)}%` }} /></div>
                        </div>
                        <span className={`ck-status ${studentDetail.feeStatus === 'Paid' ? 'sg' : studentDetail.feeStatus === 'Overdue' ? 'sr' : 'sam'}`}>{studentDetail.feeStatus}</span>
                      </div>
                    </div>
                  </div>
                )}
              </div>
              <div className="ck-modal-foot">
                <button className="ck-btn ck-btn-ghost" onClick={() => setStudentModalOpen(false)}>Close</button>
                {studentDetail ? <button className="ck-btn ck-btn-g" onClick={() => populateStudentForm(studentDetail)}>Edit Student</button> : null}
              </div>
            </div>
          </div>
        )}
      </main>
    </div>
  );
}

function ModuleShell({ title, subtitle, actions, children }: { title: string; subtitle: string; actions?: React.ReactNode; children: React.ReactNode }) {
  return <><div className="ck-ph"><div className="ck-ph-l"><h1>{title}</h1><p>{subtitle}</p></div><div className="ck-actions-inline">{actions}</div></div>{children}</>;
}
function Field({ label, children, hint, error }: { label: string; children: React.ReactNode; hint?: string; error?: string }) {
  return <div className="ck-field"><label>{label}</label>{children}{error ? <div className="ts" style={{ marginTop: 4, color: "#b42318" }}>{error}</div> : hint ? <div className="ts" style={{ marginTop: 4 }}>{hint}</div> : null}</div>;
}
function Info({ label, value }: { label: string; value: string }) {
  return <div className="ck-info"><div className="ck-info-l">{label}</div><div className="ck-info-v">{value}</div></div>;
}
function Stat({ label, value, sub, pill, tone, onClick }: { label: string; value: string | number; sub: string; pill: string; tone: 'green' | 'blue' | 'orange' | 'red'; onClick?: () => void }) {
  const toneClass = tone === 'green' ? 'pg' : tone === 'blue' ? 'pb' : tone === 'orange' ? 'po' : 'pr';
  return <button className="ck-stat" onClick={onClick}><div className="ck-stat-l">{label}</div><div className="ck-stat-v">{value}</div><div className="ck-stat-s">{sub}</div><div className={`ck-pill ${toneClass}`}>{pill}</div></button>;
}

function OrderSummaryPanel({ accentVar, borderVar, lines, total, delivery, onPlace, onDraft, saving }: { accentVar: string; borderVar: string; lines: Array<{ label: string; value: number }>; total: number; delivery: string; onPlace: () => void; onDraft: () => void; saving: boolean }) {
  return <div className="ck-card" style={{ borderColor: borderVar, alignSelf: 'start', position: 'sticky', top: 88 }}><div className="ck-form-head" style={{ color: accentVar }}>Order summary</div><div className="ck-form-body">{lines.map((line, idx) => <div key={idx} style={{ display: 'flex', justifyContent: 'space-between', gap: 12, fontSize: 13, marginBottom: 8 }}><span style={{ color: 'var(--ink2)' }}>{line.label}</span><strong>₹{formatMoney(line.value)}</strong></div>)}<div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, paddingTop: 12, marginTop: 12, borderTop: '1px solid var(--border)', fontSize: 15 }}><span>Total</span><strong style={{ color: accentVar }}>₹{formatMoney(total)}</strong></div><div className="ts" style={{ marginTop: 10 }}>Estimated delivery: {delivery}</div><div style={{ display: 'grid', gap: 10, marginTop: 16 }}><button className="ck-btn ck-btn-g" disabled={saving} onClick={onPlace}>{saving ? 'Saving…' : 'Place order →'}</button><button className="ck-btn ck-btn-ghost" disabled={saving} onClick={onDraft}>{saving ? 'Saving…' : 'Save as draft'}</button></div></div></div>;
}

const thStyle: CSSProperties = { textAlign: 'left', padding: '10px 12px', fontSize: 12, color: 'var(--ink2)' };
const tdStyle: CSSProperties = { padding: '10px 12px', borderTop: '1px solid var(--border)', fontSize: 13, verticalAlign: 'middle' };
const inlineInputStyle: CSSProperties = { width: '100%', border: '1px solid var(--border2)', borderRadius: 8, padding: '6px 8px', fontSize: 12, fontFamily: 'DM Sans, sans-serif' };

function initials(name: string) {
  return name.split(' ').filter(Boolean).slice(0, 2).map((part) => part[0]?.toUpperCase() || '').join('') || 'ST';
}

function formatMoney(value: any) {
  const n = typeof value === 'number' ? value : Number(value || 0);
  return new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 }).format(n);
}

function formatLakh(value: any) {
  const n = typeof value === 'number' ? value : Number(value || 0);
  return n >= 100000 ? `${(n / 100000).toFixed(1)}L` : formatMoney(n);
}

function attendanceNumber(value?: string) {
  const match = `${value || ''}`.match(/\d+(?:\.\d+)?/);
  return match ? Number(match[0]) : 0;
}

function formatLongDate(value?: string) {
  if (!value) return '—';
  return new Date(`${value}T00:00:00`).toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric' });
}

function todayIso() {
  const now = new Date();
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60000);
  return local.toISOString().slice(0, 10);
}

function formatAddress(address: any) {
  if (!address) return '—';
  return [address.houseNumber, address.street, address.locality, address.city, address.state, address.pinCode].filter(Boolean).join(', ') || '—';
}

function splitCsvLine(line: string) {
  const values: string[] = [];
  let current = '';
  let inQuotes = false;
  for (let i = 0; i < line.length; i += 1) {
    const char = line[i];
    if (char === '"') {
      if (inQuotes && line[i + 1] === '"') {
        current += '"';
        i += 1;
      } else {
        inQuotes = !inQuotes;
      }
    } else if (char === ',' && !inQuotes) {
      values.push(current.trim());
      current = '';
    } else {
      current += char;
    }
  }
  values.push(current.trim());
  return values;
}

function defaultPlanName(className?: string) {
  const value = String(className || '');
  if (value.includes('11') || value.includes('12')) return 'Class 11–12 · ₹56,000';
  if (value.includes('9') || value.includes('10')) return 'Class 9–10 · ₹48,000';
  if (value.includes('6') || value.includes('7') || value.includes('8')) return 'Class 6–8 · ₹44,000';
  return 'Class 1–5 · ₹40,000';
}

function computeSaOrderValue(cat: string, form: any, eventItems: any[]): number {
  const sizes = ['xxs', 'xs', 's', 'm', 'l', 'xl', 'xxl'];
  if (cat === 'UNIFORMS') return sizes.reduce((sum, key) => sum + (Number(form[`size_${key}`]) || 0), 0) * 300;
  if (cat === 'NOTEBOOKS') return (form.notebookRows ?? []).reduce((sum: number, row: any) => sum + (Number(row.qty) || 0), 0) * 40;
  if (cat === 'IDCARDS') return ((Number(form.studentCount) || 0) + (Number(form.staffCount) || 0)) * 60;
  if (cat === 'STATIONERY') return (Number(form.kitQty) || 0) * 90;
  if (cat === 'HOUSEKEEPING') return (Number(form.monthlyRate) || 0) * (parseInt(form.duration || '0', 10) || 0);
  if (cat === 'EVENTS') return eventItems.reduce((sum, row) => sum + (Number(row.qty) || 0) * (EVENT_RATES_GLOBAL[row.type] || 0), 0);
  if (cat === 'CUSTOM') return Number(form.budget) || 0;
  return 0;
}
const EVENT_RATES_GLOBAL: Record<string, number> = { Trophy: 500, Medal: 150, Certificate: 20, 'Banner/Backdrop': 800, Standee: 600, Brochure: 15 };
