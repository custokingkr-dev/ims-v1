import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../services/api';
import { useAuth } from '../contexts/AuthContext';
import { usePermissions } from '../hooks/usePermissions';
import { X, ChevronDown, PanelLeft, PanelLeftClose } from 'lucide-react';
import {
  type PanelKey, type WorkspaceData,
  ACCOUNTANT_NAV_SECTIONS, ADMIN_NAV_SECTIONS, OPERATIONS_NAV_SECTIONS,
  SUPERADMIN_NAV_SECTIONS, TEACHER_NAV_SECTIONS, VIEWER_NAV_SECTIONS,
  ZONE_ADMIN_NAV_SECTIONS, PANEL_TITLES,
} from './workspace/config';
import { NavIcon } from '../shared/display/icons';
import { ModuleShell } from './workspace/ui';
import { HomePanel } from './workspace/panels/HomePanel';
import { StudentsPanel } from './workspace/panels/StudentsPanel';
import { FeesPanel } from './workspace/panels/FeesPanel';
import { FeeStructurePanel } from './workspace/panels/FeeStructurePanel';
import { AttendanceModulePanel } from './workspace/panels/AttendanceModulePanel';
import { TimetablePanel } from './workspace/panels/TimetablePanel';
import { StaffPanel } from './workspace/panels/StaffPanel';
import { PlanningPanel } from './workspace/panels/PlanningPanel';
import { CatalogPanel } from './workspace/panels/CatalogPanel';
import { AddStudentPanel } from './workspace/panels/AddStudentPanel';
import { SchoolStructurePanel } from './workspace/panels/SchoolStructurePanel';
import { BulkImportPanel } from './workspace/panels/BulkImportPanel';
import { FirefightingDashboardPanel } from './workspace/panels/FirefightingDashboardPanel';
import { FirefightingNewPanel } from './workspace/panels/FirefightingNewPanel';
import { FirefightingApprovalsPanel } from './workspace/panels/FirefightingApprovalsPanel';
import { FirefightingOrdersPanel } from './workspace/panels/FirefightingOrdersPanel';
import { SaErpPanel } from './workspace/panels/SaErpPanel';
import { SaRevenuePanel } from './workspace/panels/SaRevenuePanel';
import { SaCatalogPanel } from './workspace/panels/SaCatalogPanel';
import { SaOrderApprovalsPanel } from './workspace/panels/SaOrderApprovalsPanel';
import { AdminOrdersPanel } from './workspace/panels/AdminOrdersPanel';
import { SaAllOrdersPanel } from './workspace/panels/SaAllOrdersPanel';
import { SaNewOrderPanel } from './workspace/panels/SaNewOrderPanel';
import { SaSchoolsPanel } from './workspace/panels/SaSchoolsPanel';
import { SaInvoicesPanel } from './workspace/panels/SaInvoicesPanel';

export default function UnifiedWorkspacePage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const { can } = usePermissions();

  const role = user?.role;
  const isPlatformAdmin = role === 'SUPERADMIN' || can('platform:admin');
  const isZoneAdmin = !isPlatformAdmin && (role === 'ZONE_ADMIN' || can('zone:manage'));
  const isOperations = role === 'OPERATIONS';
  const isAccountant = role === 'ACCOUNTANT';
  const isTeacher = role === 'TEACHER';
  const isViewer = role === 'VIEWER';
  const defaultPanel: PanelKey = isPlatformAdmin
    ? 'orders'
    : isZoneAdmin
      ? 'za-overview'
      : isOperations || isTeacher || isViewer || isAccountant
        ? 'home'
        : 'catalog';

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
      setWorkspace({ school: { name: 'Custoking Platform', meta: 'Super Admin' } });
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

  const navSections = isPlatformAdmin
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
  const allowedPanelKeys = navSections.flatMap(section => section.items.map(item => item.key));

  const isFire = panel.startsWith('ff-');
  // liveOrders is a PageResponse envelope; fall back to workspace snapshot for first render
  const orderRows: any[] = (liveOrders?.content) ?? workspace?.orders ?? [];

  useEffect(() => {
    if (!allowedPanelKeys.includes(panel)) {
      setPanel(defaultPanel);
    }
  }, [allowedPanelKeys.join('|'), defaultPanel, panel]);

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

  if (!workspace) return <div className="ck-loading">Loading workspace…</div>;

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
          <div className="ck-topbar-title">{currentTitle}</div>
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
          {isFire && (
            <button className="ck-btn ck-btn-or" onClick={() => setPanel('ff-new')}>+ New Urgent Request</button>
          )}
        </div>

        <div className="ck-content">
          {panel === 'home' && workspace && <HomePanel workspace={workspace} setPanel={setPanel} />}

          {panel === 'students' && <StudentsPanel setPanel={setPanel} onRefresh={refresh} />}

          {panel === 'fees' && <FeesPanel workspace={workspace} onRefresh={refresh} />}

          {panel === 'feestructure' && <FeeStructurePanel onRefresh={refresh} />}

          {panel === 'addstudent' && <AddStudentPanel setPanel={setPanel} onRefresh={refresh} />}

          {panel === 'classsetup' && <SchoolStructurePanel schoolId={user?.branchId ?? undefined} onSaved={refresh} />}

          {panel === 'bulkimport' && <BulkImportPanel onRefresh={refresh} schoolScopedParams={schoolScopedParams} />}

          {panel === 'attendance' && <AttendanceModulePanel onRefresh={refresh} schoolScopedParams={schoolScopedParams} />}

          {panel === 'timetable' && workspace && <TimetablePanel workspace={workspace} onRefresh={refresh} />}

          {panel === 'staff' && workspace && <StaffPanel workspace={workspace} onRefresh={refresh} />}

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

          {panel === 'catalog' && <CatalogPanel setPanel={setPanel} />}

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

          {panel === 'orders' && !isPlatformAdmin && (
            <AdminOrdersPanel
              orders={orderRows}
              stats={liveOrderStats}
              loading={ordersLoading}
              notice={catalogNotice}
              schoolScopedParams={schoolScopedParams}
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

          {panel === 'planning' && workspace && <PlanningPanel workspace={workspace} onRefresh={refresh} setPanel={setPanel} />}

          {panel === 'ff-dashboard' && (
            <FirefightingDashboardPanel
              isSuperAdmin={isPlatformAdmin}
              adminRequests={workspace?.firefighting?.requests ?? []}
              setPanel={setPanel}
              onOpenFfDraft={(code) => { setFfEditingCode(code); setPanel('ff-new'); }}
            />
          )}
          {panel === 'ff-new' && (
            <FirefightingNewPanel editingCode={ffEditingCode} setPanel={setPanel} onRefresh={refresh} />
          )}
          {panel === 'ff-approvals' && (
            <FirefightingApprovalsPanel
              pendingRequests={workspace?.firefighting?.requests ?? []}
              onRefresh={refresh}
            />
          )}
          {panel === 'ff-orders' && (
            <FirefightingOrdersPanel
              isSuperAdmin={isPlatformAdmin}
              adminRequests={workspace?.firefighting?.requests ?? []}
              onRefresh={refresh}
            />
          )}
        </div>
      </main>
    </div>
  );
}
