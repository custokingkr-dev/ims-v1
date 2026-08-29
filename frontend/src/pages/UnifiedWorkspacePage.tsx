import { lazy, Suspense, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../services/api';
import { useAuth } from '../contexts/AuthContext';
import { usePermissions } from '../hooks/usePermissions';
import { X, ChevronDown, PanelLeft, PanelLeftClose } from 'lucide-react';
import {
  type PanelKey, type WorkspaceData,
  ACCOUNTANT_NAV_SECTIONS, ADMIN_NAV_SECTIONS, OPERATIONS_NAV_SECTIONS,
  SUPERADMIN_NAV_SECTIONS, TEACHER_NAV_SECTIONS, VIEWER_NAV_SECTIONS,
  ZONE_ADMIN_NAV_SECTIONS, PANEL_TITLES, filterNavSectionsForModules, withDerivedModuleGroups,
} from './workspace/config';
import { NavIcon } from '../shared/display/icons';
import { ModuleShell } from './workspace/ui';

// Panels are navigation-level features. Loading them on demand keeps large,
// role-specific modules out of the initial workspace download without changing
// the panel API or its state ownership.
const HomePanel = lazy(() => import('./workspace/panels/HomePanel').then((module) => ({ default: module.HomePanel })));
const StudentsPanel = lazy(() => import('./workspace/panels/StudentsPanel').then((module) => ({ default: module.StudentsPanel })));
const FeeModulePanel = lazy(() => import('./workspace/panels/FeeModulePanel').then((module) => ({ default: module.FeeModulePanel })));
const AttendanceModulePanel = lazy(() => import('./workspace/panels/AttendanceModulePanel').then((module) => ({ default: module.AttendanceModulePanel })));
const TimetableStudioPanel = lazy(() => import('./workspace/panels/TimetableStudioPanel').then((module) => ({ default: module.TimetableStudioPanel })));
const StaffPanel = lazy(() => import('./workspace/panels/StaffPanel').then((module) => ({ default: module.StaffPanel })));
const PlanningPanel = lazy(() => import('./workspace/panels/PlanningPanel').then((module) => ({ default: module.PlanningPanel })));
const CatalogPanel = lazy(() => import('./workspace/panels/CatalogPanel').then((module) => ({ default: module.CatalogPanel })));
const AddStudentPanel = lazy(() => import('./workspace/panels/AddStudentPanel').then((module) => ({ default: module.AddStudentPanel })));
const SchoolStructurePanel = lazy(() => import('./workspace/panels/SchoolStructurePanel').then((module) => ({ default: module.SchoolStructurePanel })));
const BulkImportPanel = lazy(() => import('./workspace/panels/BulkImportPanel').then((module) => ({ default: module.BulkImportPanel })));
const PhotoImportPanel = lazy(() => import('./workspace/panels/PhotoImportPanel').then((module) => ({ default: module.PhotoImportPanel })));
const StudentExportPanel = lazy(() => import('./workspace/panels/StudentExportPanel').then((module) => ({ default: module.StudentExportPanel })));
const FirefightingDashboardPanel = lazy(() => import('./workspace/panels/FirefightingDashboardPanel').then((module) => ({ default: module.FirefightingDashboardPanel })));
const FirefightingNewPanel = lazy(() => import('./workspace/panels/FirefightingNewPanel').then((module) => ({ default: module.FirefightingNewPanel })));
const FirefightingApprovalsPanel = lazy(() => import('./workspace/panels/FirefightingApprovalsPanel').then((module) => ({ default: module.FirefightingApprovalsPanel })));
const FirefightingOrdersPanel = lazy(() => import('./workspace/panels/FirefightingOrdersPanel').then((module) => ({ default: module.FirefightingOrdersPanel })));
const SaErpPanel = lazy(() => import('./workspace/panels/SaErpPanel').then((module) => ({ default: module.SaErpPanel })));
const SaRevenuePanel = lazy(() => import('./workspace/panels/SaRevenuePanel').then((module) => ({ default: module.SaRevenuePanel })));
const SaCatalogPanel = lazy(() => import('./workspace/panels/SaCatalogPanel').then((module) => ({ default: module.SaCatalogPanel })));
const SaOrderApprovalsPanel = lazy(() => import('./workspace/panels/SaOrderApprovalsPanel').then((module) => ({ default: module.SaOrderApprovalsPanel })));
const AdminOrdersPanel = lazy(() => import('./workspace/panels/AdminOrdersPanel').then((module) => ({ default: module.AdminOrdersPanel })));
const SaAllOrdersPanel = lazy(() => import('./workspace/panels/SaAllOrdersPanel').then((module) => ({ default: module.SaAllOrdersPanel })));
const SaNewOrderPanel = lazy(() => import('./workspace/panels/SaNewOrderPanel').then((module) => ({ default: module.SaNewOrderPanel })));
const SaSchoolsPanel = lazy(() => import('./workspace/panels/SaSchoolsPanel').then((module) => ({ default: module.SaSchoolsPanel })));
const SaInvoicesPanel = lazy(() => import('./workspace/panels/SaInvoicesPanel').then((module) => ({ default: module.SaInvoicesPanel })));

export default function UnifiedWorkspacePage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const { can, canAny } = usePermissions();

  const role = user?.role;
  const isPlatformAdmin = role === 'SUPERADMIN' || can('platform:admin');
  const isZoneAdmin = !isPlatformAdmin && (role === 'ZONE_ADMIN' || can('zone:manage'));
  const isOperations = role === 'OPERATIONS';
  const isAccountant = role === 'ACCOUNTANT';
  const isTeacher = role === 'TEACHER';
  const isViewer = role === 'VIEWER';
  const defaultPanel: PanelKey = isPlatformAdmin
    ? 'orders'
    : isOperations
      ? 'photoimport'
    : isZoneAdmin
      ? 'za-overview'
      : 'home';

  // ── Core workspace state ────────────────────────────────────────────────────
  const [workspace, setWorkspace] = useState<WorkspaceData | null>(null);
  const [workspaceError, setWorkspaceError] = useState('');
  const [panel, setPanel] = useState<PanelKey>(defaultPanel);

  // ffEditingCode: passed into FirefightingNewPanel when opening a draft from the dashboard.
  const [ffEditingCode, setFfEditingCode] = useState<string | null>(null);

  // saInvBadge: invoice notification badge in the SA nav sidebar.
  const [saInvBadge, setSaInvBadge] = useState(0);

  // schoolScopedParams: used for non-platform-admin API calls that need a schoolId filter.
  const schoolScopedParams = !isPlatformAdmin && user?.branchId
    ? { schoolId: user.branchId }
    : undefined;

  // null means entitlements are still loading. Keeping that distinct from an
  // entitled-to-nothing school prevents the navigation from collapsing during login.
  const [activeModules, setActiveModules] = useState<Set<string> | null>(null);

  // ── Supply order state (AdminOrdersPanel and SaOrderApprovalsPanel need page-level state) ──
  // liveOrders holds the full PageResponse envelope { content, page, size, totalElements, totalPages, last }
  const [liveOrders, setLiveOrders] = useState<any | null>(null);
  const [ordersPage, setOrdersPage] = useState(0);
  const [liveOrderStats, setLiveOrderStats] = useState<any | null>(null);
  const [ordersLoading, setOrdersLoading] = useState(false);
  const [catalogNotice, setCatalogNotice] = useState<{ type: string; msg: string } | null>(null);

  const [pendingApprovalOrders, setPendingApprovalOrders] = useState<any[]>([]);
  const [pendingApprovalLoading, setPendingApprovalLoading] = useState(false);
  const [approvalActionSaving, setApprovalActionSaving] = useState('');
  const [approvalNotice, setApprovalNotice] = useState<{ type: string; msg: string } | null>(null);
  const [rejectModalOrderId, setRejectModalOrderId] = useState<string | null>(null);
  const [rejectReason, setRejectReason] = useState('');
  const [designApprovingSaving, setDesignApprovingSaving] = useState('');
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [pinned, setPinned] = useState(() => {
    try { return localStorage.getItem('ck_nav_pinned') === '1'; } catch { return false; }
  });
  const togglePinned = () => {
    setPinned((prev) => {
      const next = !prev;
      try { localStorage.setItem('ck_nav_pinned', next ? '1' : '0'); } catch { /* ignore */ }
      return next;
    });
  };
  const navGroupsKey = `ck_nav_groups:${role ?? 'default'}`;
  const [openGroups, setOpenGroups] = useState<Record<string, boolean>>(() => {
    try { return JSON.parse(localStorage.getItem(navGroupsKey) || '{}'); } catch { return {}; }
  });
  const toggleGroup = (title: string) => {
    setOpenGroups((prev) => {
      const next = { ...prev, [title]: !(prev[title] ?? true) };
      try { localStorage.setItem(navGroupsKey, JSON.stringify(next)); } catch { /* ignore */ }
      return next;
    });
  };

  // ── Workspace data loader ───────────────────────────────────────────────────
  const refresh = async () => {
    try {
      setWorkspaceError('');
      if (isOperations && !user?.branchId) {
        setWorkspace({
          school: {
            name: 'Custoking Operations',
            meta: 'Assigned school workflows',
          },
          dashboard: {
            students: 0,
            sections: 0,
            attendancePercent: 0,
            attendancePresent: 0,
            feeCollectedLakh: 0,
            feeTargetLakh: 0,
            feeOverdueCount: 0,
            firefightingActive: 0,
            pendingApprovals: 0,
          },
          recentActivity: [],
          orders: [],
          staff: [],
          annualPlan: { terms: [] },
        });
        setActiveModules(withDerivedModuleGroups(['ORDERS', 'FIREFIGHTING']));
        return;
      }
      if (!isPlatformAdmin && !user?.branchId) {
        throw new Error('This account is not assigned to a school.');
      }
      const workspaceRequest = api.get('/workspace', { params: schoolScopedParams });
      const modulesRequest = isPlatformAdmin
        ? Promise.resolve<string[]>([])
        : api.get(`/schools/${user!.branchId}/modules/active`).then(res =>
            (Array.isArray(res.data) ? res.data : []).map((module: any) =>
              String(module.moduleCode).toUpperCase()
            )
          );
      const [workspaceResponse, moduleCodes] = await Promise.all([workspaceRequest, modulesRequest]);
      setWorkspace(workspaceResponse.data);
      if (!isPlatformAdmin) {
        setActiveModules(withDerivedModuleGroups(moduleCodes));
      }
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

  // ── Supply order loaders and actions ───────────────────────────────────────
  const loadLiveOrders = async (page = 0) => {
    setOrdersLoading(true);
    try {
      const [ordRes, statsRes] = await Promise.all([
        api.get('/supply/orders', { params: { ...schoolScopedParams, page, size: 20 } }),
        api.get('/supply/orders/stats', { params: schoolScopedParams }),
      ]);
      // GET /supply/orders now returns a real PageResponse envelope
      // ({content, page, size, totalElements, totalPages}). Fall back to treating a
      // bare array as a single page for resilience against older/mocked responses.
      const ordersData: any = ordRes.data;
      const content = Array.isArray(ordersData) ? ordersData : (ordersData?.content ?? []);
      const totalPages = Array.isArray(ordersData) ? 1 : (ordersData?.totalPages ?? 1);
      setLiveOrders({
        content,
        totalPages,
        page,
      });
      setOrdersPage(page);
      setLiveOrderStats(statsRes.data);
    } catch (e: any) {
      setCatalogNotice({ type: 'error', msg: e?.response?.data?.message || 'Failed to load orders.' });
    } finally { setOrdersLoading(false); }
  };

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
      await api.post(`/supply/orders/${rejectModalOrderId}/superadmin-reject`, {
        reason: rejectReason || 'Rejected by Superadmin',
      });
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

  const markDesignApproved = async (orderId: string) => {
    if (designApprovingSaving === orderId) return;
    setDesignApprovingSaving(orderId);
    try {
      await api.post(`/supply/orders/${orderId}/design-approved`);
      setCatalogNotice({ type: 'success', msg: `Order ${orderId} marked design approved and moved to superadmin review.` });
      await loadLiveOrders();
      if (isPlatformAdmin) await loadPendingApprovalOrders();
    } catch (e: any) {
      setCatalogNotice({ type: 'error', msg: e?.response?.data?.message || 'Failed to update design status.' });
    } finally {
      setDesignApprovingSaving('');
    }
  };

  // ── Bootstrap and panel-change effects ─────────────────────────────────────
  useEffect(() => {
    if (isPlatformAdmin) {
      setWorkspace({
        school: { name: 'Custoking Platform', meta: 'Super Admin' },
        dashboard: {
          students: 0,
          sections: 0,
          attendancePercent: 0,
          attendancePresent: 0,
          feeCollectedLakh: 0,
          feeTargetLakh: 0,
          feeOverdueCount: 0,
          firefightingActive: 0,
          pendingApprovals: 0,
        },
        recentActivity: [],
        staff: [],
        annualPlan: { terms: [] },
      });
      loadPendingApprovalOrders();
      return;
    }
    refresh();
  }, [isPlatformAdmin]);

  useEffect(() => {
    if (!isPlatformAdmin) return;
    const adminOnlyPanels: PanelKey[] = [
      'home', 'students', 'fees', 'feestructure', 'attendance',
      'timetable', 'addstudent', 'bulkimport', 'staff', 'catalog', 'planning',
      'ff-new', 'ff-approvals',
    ];
    if (adminOnlyPanels.includes(panel)) setPanel('orders');
  }, [isPlatformAdmin, panel]);

  useEffect(() => {
    if (panel === 'orders') {
      loadLiveOrders();
      if (isPlatformAdmin) loadPendingApprovalOrders();
    }
  }, [panel]);

  // ── Derived values ──────────────────────────────────────────────────────────
  const currentTitle = isPlatformAdmin && panel === 'orders'
    ? 'Supply order approvals'
    : PANEL_TITLES[panel];

  const rawNavSections = isPlatformAdmin
    ? SUPERADMIN_NAV_SECTIONS
    : isZoneAdmin
      ? ZONE_ADMIN_NAV_SECTIONS
      : isOperations
        ? OPERATIONS_NAV_SECTIONS
        : isAccountant
          ? ACCOUNTANT_NAV_SECTIONS
          : isTeacher
            ? TEACHER_NAV_SECTIONS
            : isViewer
              ? VIEWER_NAV_SECTIONS
              : ADMIN_NAV_SECTIONS;

  // Filter out nav items gated by a module the school hasn't been entitled to.
  // Items without a `module` field, and everything for platform admins, are always shown.
  const panelPermissionAny = (key: PanelKey): string[] | null => {
    switch (key) {
      case 'students': return ['student:read'];
      case 'addstudent': return ['student:create'];
      case 'bulkimport': return ['student:import'];
      case 'photoimport': return ['student:photo-import'];
      case 'studentexport': return ['student:export'];
      case 'attendance': return ['attendance:read'];
      case 'timetable': return ['timetable:read'];
      case 'staff': return ['staff:read'];
      case 'classsetup': return ['school:update'];
      case 'fees': return ['fee:read', 'payment:read'];
      case 'feestructure': return ['fee_structure:read', 'fee:read'];
      case 'catalog': return ['order:read'];
      case 'orders': return ['order:read'];
      case 'planning': return ['plan:read', 'order:read'];
      case 'ff-dashboard': return ['firefighting:read'];
      case 'ff-new': return ['firefighting:create'];
      case 'ff-approvals': return ['firefighting:approve'];
      case 'ff-orders': return ['firefighting:read'];
      default: return null;
    }
  };
  const panelAllowedByPermission = (key: PanelKey) => {
    if (isPlatformAdmin) return true;
    const required = panelPermissionAny(key);
    return !required || canAny(required);
  };
  const entitlementsReady = isPlatformAdmin || activeModules !== null;
  const resolvedActiveModules = activeModules ?? new Set<string>();
  const moduleFilteredSections = isPlatformAdmin || !entitlementsReady
    ? rawNavSections
    : filterNavSectionsForModules(rawNavSections, resolvedActiveModules);
  const navSections = isPlatformAdmin
    ? moduleFilteredSections
    : moduleFilteredSections
        .map(section => ({ ...section, items: section.items.filter(item => panelAllowedByPermission(item.key)) }))
        .filter(section => section.items.length > 0);
  const allowedPanelKeys = navSections.flatMap(section => section.items.map(item => item.key));
  const studentSubpanelAllowed = (panel === 'addstudent' || panel === 'bulkimport')
    && allowedPanelKeys.includes('students')
    && panelAllowedByPermission(panel);
  const panelAllowed = isPlatformAdmin || allowedPanelKeys.includes(panel) || studentSubpanelAllowed;
  const dashboardModuleAccess = {
    erp: isPlatformAdmin || resolvedActiveModules.has('ERP'),
    supplyOs: isPlatformAdmin || resolvedActiveModules.has('SUPPLY_OS'),
  };

  const isFire = panel.startsWith('ff-');
  // liveOrders is a PageResponse envelope; fall back to workspace snapshot for first render
  const orderRows: any[] = (liveOrders?.content) ?? workspace?.orders ?? [];

  useEffect(() => {
    if (!panelAllowed) {
      setPanel(allowedPanelKeys.includes(defaultPanel) ? defaultPanel : (allowedPanelKeys[0] ?? panel));
    }
  }, [allowedPanelKeys.join('|'), defaultPanel, panel, panelAllowed]);

  // ── Render ─────────────────────────────────────────────────────────────────
  if (!workspace && workspaceError) {
    return (
      <div className="ck-loading" style={{ padding: '48px 24px', display: 'grid', gap: '12px', textAlign: 'center' }}>
        <div style={{ fontSize: '28px' }}>⚠️</div>
        <div style={{ fontFamily: 'Fraunces, serif', fontSize: '24px' }}>Workspace could not load</div>
        <div style={{ color: '#5a5a5a' }}>{workspaceError}</div>
        <div style={{ display: 'flex', gap: '12px', justifyContent: 'center', flexWrap: 'wrap' }}>
          <button className="ck-btn ck-btn-ghost" onClick={() => refresh()}>Retry</button>
          <button className="ck-btn ck-btn-primary" onClick={() => { logout(); navigate('/login', { replace: true }); }}>
            Back to login
          </button>
        </div>
      </div>
    );
  }

  if (!workspace || !entitlementsReady) {
    return (
      <div className="ck-loading" role="status" aria-live="polite">
        Loading school workspace...
      </div>
    );
  }

  return (
    <div className="workspace-shell">
      {/* Mobile sidebar backdrop */}
      <div
        className={`ck-sidebar-backdrop${sidebarOpen ? ' open' : ''}`}
        onClick={() => setSidebarOpen(false)}
        aria-hidden="true"
      />
      <aside
        id="ck-sidebar-nav"
        className={`ck-sidebar${sidebarOpen ? ' open' : ''}${pinned ? ' pinned' : ''}`}
      >
        <div className="ck-sb-header">
          <div className="ck-sb-monogram" aria-hidden>CK</div>
          <div className="ck-sb-logo">custoking</div>
          <div className="ck-school-name">{workspace.school.name}</div>
          <div className="ck-school-meta">{workspace.school.meta}</div>
          {workspace?.school?.name && (
            <div className="ck-sb-school-badge">
              {workspace.school.name}
            </div>
          )}
          <button
            className="ck-sb-pin"
            onClick={togglePinned}
            aria-label="Pin/Unpin sidebar"
            aria-pressed={pinned}
          >
            {pinned ? <PanelLeftClose size={16} strokeWidth={2} aria-hidden /> : <PanelLeft size={16} strokeWidth={2} aria-hidden />}
          </button>
          <button
            className="ck-sb-close"
            onClick={() => setSidebarOpen(false)}
            aria-label="Close navigation menu"
          >
            <X size={17} strokeWidth={2} aria-hidden />
          </button>
        </div>

        <nav className="ck-nav">
          {navSections.map((section) => {
            const open = openGroups[section.title] ?? true;
            return (
              <div key={section.title} className={`ck-nav-group${open ? '' : ' closed'}${section.fire ? ' fire' : ''}`}>
                <button
                  type="button"
                  className="ck-nav-group-header"
                  aria-expanded={open}
                  onClick={() => toggleGroup(section.title)}
                >
                  <span className="ck-nav-group-title">{section.fire ? 'Urgent Procurement' : section.title}</span>
                  <ChevronDown className="ck-nav-chevron" size={13} aria-hidden />
                </button>
                {section.items.map((item) => (
                  <button
                    key={item.key}
                    className={`ck-nav-item ${panel === item.key ? 'on' : ''} ${section.fire ? 'fire' : ''}`}
                    onClick={() => { setPanel(item.key); setSidebarOpen(false); }}
                    title={item.label}
                    aria-label={item.label}
                    aria-current={panel === item.key ? 'page' : undefined}
                  >
                    <NavIcon panelKey={item.key} fallback={item.icon} />
                    <span className="ck-nav-label">{item.label}</span>
                    {item.key === 'sa-invoices' && saInvBadge > 0 && (
                      <span className="ck-nav-badge">{saInvBadge}</span>
                    )}
                  </button>
                ))}
              </div>
            );
          })}
        </nav>

        <div className="ck-user-card">
          <div className="ck-user-card-inner">
            <div className="ck-user-avatar" aria-hidden="true">
              {(user?.fullName ?? user?.email ?? 'U').charAt(0).toUpperCase()}
            </div>
            <div className="ck-user-card-details">
              <div className="ck-user-name">{user?.fullName ?? user?.email}</div>
              <div className="ck-user-meta">{role?.replace('_', ' ') ?? 'User'}</div>
            </div>
          </div>
          <div className="ck-badge-row ck-user-card-details" style={{ marginTop: 10 }}>
            <button
              className="ck-btn ck-btn-ghost ck-btn-sm"
              onClick={() => { logout(); navigate('/login', { replace: true }); }}
            >
              Sign out
            </button>
          </div>
        </div>
      </aside>

      <main className="ck-main">
        <div className="ck-topbar">
          <button
            className="ck-menu-toggle"
            onClick={() => setSidebarOpen(v => !v)}
            aria-label="Open navigation menu"
            aria-expanded={sidebarOpen}
            aria-controls="ck-sidebar-nav"
          >
            ☰
          </button>
          {!isFire && <div className="ck-topbar-title">{currentTitle}</div>}
          {isPlatformAdmin && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginRight: 'auto', marginLeft: 12 }}>
              <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--g)', background: 'var(--g1)', padding: '4px 10px', borderRadius: 8 }}>
                Custoking Platform
              </span>
              <button className="ck-btn ck-btn-ghost" onClick={() => navigate('/schools')}>
                🏫 Manage schools
              </button>
            </div>
          )}
        </div>

        <div className="ck-content">
          <Suspense fallback={<div className="ck-loading" role="status" aria-live="polite">Loading module...</div>}>
          {!panelAllowed && !isPlatformAdmin && (
            <ModuleShell title="Module unavailable" subtitle="This module is not enabled for this school.">
              <div className="ck-card">
                <div style={{ padding: 24, color: 'var(--ink2)' }}>
                  Ask the platform superadmin to enable ERP or Supply OS for this school.
                </div>
              </div>
            </ModuleShell>
          )}

          {panelAllowed && panel === 'home' && workspace && (
            <HomePanel
              workspace={workspace}
              setPanel={setPanel}
              moduleAccess={dashboardModuleAccess}
            />
          )}

          {panelAllowed && panel === 'students' && <StudentsPanel setPanel={setPanel} onRefresh={refresh} />}

          {panelAllowed && panel === 'fees' && <FeeModulePanel workspace={workspace} onRefresh={refresh} initialView="overview" />}

          {panelAllowed && panel === 'feestructure' && <FeeModulePanel workspace={workspace} onRefresh={refresh} initialView="configuration" />}

          {panelAllowed && panel === 'addstudent' && <AddStudentPanel setPanel={setPanel} onRefresh={refresh} schoolScopedParams={schoolScopedParams} canImportStudents={can('student:import')} />}

          {panelAllowed && panel === 'classsetup' && <SchoolStructurePanel schoolId={user?.branchId ?? undefined} onSaved={refresh} />}

          {panelAllowed && panel === 'bulkimport' && <BulkImportPanel setPanel={setPanel} onRefresh={refresh} schoolScopedParams={schoolScopedParams} canCreateStudents={can('student:create')} />}

          {panelAllowed && panel === 'photoimport' && <PhotoImportPanel />}

          {panelAllowed && panel === 'studentexport' && <StudentExportPanel />}

          {panelAllowed && panel === 'attendance' && <AttendanceModulePanel onRefresh={refresh} schoolScopedParams={schoolScopedParams} />}

          {panelAllowed && panel === 'timetable' && <TimetableStudioPanel readOnly={isTeacher} staff={workspace?.staff} />}

          {panelAllowed && panel === 'staff' && workspace && <StaffPanel workspace={workspace} onRefresh={refresh} />}

          {(panel === 'za-overview' || panel === 'za-schools') && (
            <ModuleShell
              title={panel === 'za-overview' ? 'Zone overview' : 'Zone schools'}
              subtitle="Zone-admin dashboard is coming soon."
            >
              <div className="ck-card">
                <div style={{ padding: 24, color: 'var(--ink2)' }}>
                  Zone admin dashboard is coming soon. This view is not built yet — check back later.
                </div>
              </div>
            </ModuleShell>
          )}

          {panelAllowed && panel === 'catalog' && (
            <CatalogPanel
              setPanel={setPanel}
              financialYearStartMonth={workspace?.school?.financialYearStartMonth}
            />
          )}

          {panel === 'orders' && isPlatformAdmin && (
            <SaOrderApprovalsPanel
              orders={pendingApprovalOrders}
              loading={pendingApprovalLoading}
              notice={approvalNotice}
              savingId={approvalActionSaving}
              rejectModalOrderId={rejectModalOrderId}
              rejectReason={rejectReason}
              onRefresh={loadPendingApprovalOrders}
              onApprove={approveOrder}
              onOpenRejectModal={(id) => { setRejectModalOrderId(id); setRejectReason(''); }}
              onCloseRejectModal={() => setRejectModalOrderId(null)}
              onSetRejectReason={setRejectReason}
              onReject={rejectOrder}
            />
          )}
          {panel === 'sa-all-orders' && isPlatformAdmin && (
            <SaAllOrdersPanel onNewOrder={() => setPanel('sa-new-order')} />
          )}
          {panel === 'sa-new-order' && isPlatformAdmin && (
            <SaNewOrderPanel onOrderCreated={() => setPanel('sa-all-orders')} />
          )}
          {panel === 'sa-invoices' && isPlatformAdmin && (
            <SaInvoicesPanel onBadgeChange={(n) => setSaInvBadge(n)} />
          )}
          {panel === 'sa-schools' && isPlatformAdmin && <SaSchoolsPanel />}
          {panel === 'sa-erp' && isPlatformAdmin && <SaErpPanel />}
          {panel === 'sa-revenue' && isPlatformAdmin && <SaRevenuePanel />}
          {panel === 'sa-catalog' && isPlatformAdmin && <SaCatalogPanel />}

          {panelAllowed && panel === 'orders' && isOperations && (
            <SaAllOrdersPanel onNewOrder={() => setPanel('catalog')} canManage={false} />
          )}

          {panelAllowed && panel === 'orders' && !isPlatformAdmin && !isOperations && (
            <AdminOrdersPanel
              orders={orderRows}
              stats={liveOrderStats}
              loading={ordersLoading}
              notice={catalogNotice}
              page={ordersPage}
              totalPages={liveOrders?.totalPages ?? 1}
              onPageChange={(p) => loadLiveOrders(p)}
              onNewOrder={() => setPanel('catalog')}
              onMarkDesignApproved={markDesignApproved}
              onReorder={async (row) => {
                try {
                  await api.post('/supply/orders', {
                    category: row.category,
                    orderData: row.orderData || JSON.stringify({ title: row.description || row.category }),
                    subtotal: row.subtotal || 0,
                    gst: row.gst || 0,
                    totalAmount: row.totalAmount || 0,
                    requiredByDate: row.requiredByDate || null,
                    status: 'DRAFT',
                    ...(schoolScopedParams || {}),
                  });
                  setCatalogNotice({ type: 'success', msg: 'Reorder placed as draft.' });
                  loadLiveOrders(0);
                } catch (e: any) {
                  setCatalogNotice({ type: 'error', msg: e?.response?.data?.message || 'Failed to place reorder.' });
                }
              }}
            />
          )}

          {panelAllowed && panel === 'planning' && workspace && <PlanningPanel workspace={workspace} onRefresh={refresh} setPanel={setPanel} />}

          {panelAllowed && panel === 'ff-dashboard' && (
            <FirefightingDashboardPanel
              isSuperAdmin={isPlatformAdmin}
              setPanel={setPanel}
              onOpenFfDraft={(code) => { setFfEditingCode(code); setPanel('ff-new'); }}
            />
          )}
          {panelAllowed && panel === 'ff-new' && (
            <FirefightingNewPanel editingCode={ffEditingCode} setPanel={setPanel} onRefresh={refresh} />
          )}
          {panelAllowed && panel === 'ff-approvals' && (
            <FirefightingApprovalsPanel
              isSuperAdmin={isPlatformAdmin}
              onRefresh={refresh}
            />
          )}
          {panelAllowed && panel === 'ff-orders' && (
            <FirefightingOrdersPanel
              isSuperAdmin={isPlatformAdmin}
              onRefresh={refresh}
            />
          )}
          </Suspense>
        </div>
      </main>
    </div>
  );
}
