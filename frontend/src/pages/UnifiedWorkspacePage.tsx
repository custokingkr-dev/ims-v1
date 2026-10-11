import { reportingClient } from '../services/reportingApi';
import { lazy, Suspense, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../services/api';
import { useAuth } from '../contexts/AuthContext';
import { usePermissions } from '../hooks/usePermissions';
import { X, ChevronDown, PanelLeft, PanelLeftClose } from 'lucide-react';
import { type WorkspaceData, withDerivedModuleGroups } from './workspace/config';
import { useWorkspaceNavigation } from './workspace/useWorkspaceNavigation';
import { useWorkspaceDrawer } from './workspace/useWorkspaceDrawer';
import { useSupplyOrders } from './workspace/useSupplyOrders';
import { SupplyOrdersPanels } from './workspace/SupplyOrdersPanels';
import { humaniseCode } from '../shared/display/status';
import { NavIcon } from '../shared/display/icons';
import { ModuleShell } from './workspace/ui';
import { WorkspaceDraftProvider, useConfirmWorkspaceExit } from './workspace/WorkspaceDrafts';

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
const SaAllOrdersPanel = lazy(() => import('./workspace/panels/SaAllOrdersPanel').then((module) => ({ default: module.SaAllOrdersPanel })));
const SaNewOrderPanel = lazy(() => import('./workspace/panels/SaNewOrderPanel').then((module) => ({ default: module.SaNewOrderPanel })));
const SaSchoolsPanel = lazy(() => import('./workspace/panels/SaSchoolsPanel').then((module) => ({ default: module.SaSchoolsPanel })));
const SaInvoicesPanel = lazy(() => import('./workspace/panels/SaInvoicesPanel').then((module) => ({ default: module.SaInvoicesPanel })));
const ZoneAdminPanel = lazy(() => import('./workspace/panels/ZoneAdminPanel').then((module) => ({ default: module.ZoneAdminPanel })));

export default function UnifiedWorkspacePage() {
  const { user } = useAuth();
  return <WorkspaceDraftProvider key={`${user?.userId}:${user?.branchId}:${user?.zoneId}`}><WorkspaceContent /></WorkspaceDraftProvider>;
}

function WorkspaceContent() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const confirmExit = useConfirmWorkspaceExit();
  const { can, canAny } = usePermissions();

  // null means entitlements are still loading. Keeping that distinct from an
  // entitled-to-nothing school prevents the navigation from collapsing during login.
  const [activeModules, setActiveModules] = useState<Set<string> | null>(null);

  const {
    role, isPlatformAdmin, isZoneAdmin, isOperations, isTeacher,
    panel, setPanel, openFfDraft, ffEditingCode, currentTitle,
    navSections, panelAllowed, entitlementsReady, dashboardModuleAccess,
  } = useWorkspaceNavigation({ role: user?.role, activeModules, can, canAny });
  const {
    sidebarOpen, setSidebarOpen, drawerIsModal,
    sidebarRef, mainRef, menuToggleRef, sidebarCloseRef,
    pinned, togglePinned, openGroups, toggleGroup,
  } = useWorkspaceDrawer(role);

  // ── Core workspace state ────────────────────────────────────────────────────
  const [workspace, setWorkspace] = useState<WorkspaceData | null>(null);
  const [workspaceError, setWorkspaceError] = useState('');
  // saInvBadge: invoice notification badge in the SA nav sidebar.
  const [saInvBadge, setSaInvBadge] = useState(0);

  // schoolScopedParams: used for non-platform-admin API calls that need a schoolId filter.
  const schoolScopedParams = !isPlatformAdmin && user?.branchId
    ? { schoolId: user.branchId }
    : undefined;
  const supplyOrders = useSupplyOrders({
    panel, panelAllowed, entitlementsReady, isPlatformAdmin, schoolScopedParams,
  });

  // ── Workspace data loader ───────────────────────────────────────────────────
  const refresh = async () => {
    try {
      setWorkspaceError('');
      if (isZoneAdmin || (isOperations && !user?.branchId)) {
        setWorkspace({
          school: {
            name: isZoneAdmin ? user?.zoneName || 'Zone administration' : 'Custoking Operations',
            meta: isZoneAdmin ? 'Assigned zone schools' : 'Assigned school workflows',
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
      const workspaceRequest = reportingClient.getWorkspace(schoolScopedParams).then(data => ({ data }));
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
      return;
    }
    refresh();
  }, [isPlatformAdmin, isZoneAdmin, user?.branchId, user?.zoneId]);

  // ── Derived values ──────────────────────────────────────────────────────────
  const isFire = panel.startsWith('ff-');

  // ── Render ─────────────────────────────────────────────────────────────────
  if (!workspace && workspaceError) {
    return (
      <div className="ck-loading" style={{ padding: '48px 24px', display: 'grid', gap: '12px', textAlign: 'center' }}>
        <div style={{ fontSize: '28px' }}>⚠️</div>
        <div style={{ fontFamily: 'var(--ck-font-heading)', fontSize: '24px' }}>Workspace could not load</div>
        <div style={{ color: 'var(--ck-text-secondary)' }}>{workspaceError}</div>
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
        ref={sidebarRef}
        id="ck-sidebar-nav"
        className={`ck-sidebar${sidebarOpen ? ' open' : ''}${pinned ? ' pinned' : ''}`}
        // Modal semantics only while this really is a drawer. On desktop the
        // same element is a persistent rail and must keep its complementary
        // landmark role.
        role={drawerIsModal ? 'dialog' : undefined}
        aria-modal={drawerIsModal ? true : undefined}
        aria-label={drawerIsModal ? 'Navigation menu' : undefined}
        tabIndex={drawerIsModal ? -1 : undefined}
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
            ref={sidebarCloseRef}
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
              <div className="ck-user-meta">{humaniseCode(role) || 'User'}</div>
            </div>
          </div>
          <div className="ck-badge-row ck-user-card-details" style={{ marginTop: 10 }}>
            <button
              className="ck-btn ck-btn-ghost ck-btn-sm"
              onClick={() => { if (confirmExit()) { logout(); navigate('/login', { replace: true }); } }}
            >
              Sign out
            </button>
          </div>
        </div>
      </aside>

      <main className="ck-main" ref={mainRef}>
        <div className="ck-topbar">
          <button
            ref={menuToggleRef}
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
              <button className="ck-btn ck-btn-ghost" onClick={() => { if (confirmExit()) navigate('/schools'); }}>
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

          {panelAllowed && isZoneAdmin && (panel === 'za-overview' || panel === 'za-schools') && <ZoneAdminPanel zoneId={user?.zoneId} zoneName={user?.zoneName} view={panel === 'za-overview' ? 'overview' : 'schools'} setPanel={setPanel} />}

          {panelAllowed && panel === 'catalog' && (
            <CatalogPanel
              setPanel={setPanel}
              financialYearStartMonth={workspace?.school?.financialYearStartMonth}
            />
          )}

          <SupplyOrdersPanels
            panel={panel} panelAllowed={panelAllowed} isPlatformAdmin={isPlatformAdmin}
            isOperations={isOperations} setPanel={setPanel} snapshotOrders={workspace?.orders}
            orders={supplyOrders}
          />
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





          {panelAllowed && panel === 'planning' && workspace && <PlanningPanel workspace={workspace} onRefresh={refresh} setPanel={setPanel} />}

          {panelAllowed && panel === 'ff-dashboard' && (
            <FirefightingDashboardPanel
              isSuperAdmin={isPlatformAdmin}
              setPanel={setPanel}
              onOpenFfDraft={openFfDraft}
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
