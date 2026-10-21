import { Suspense, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { usePermissions } from '../hooks/usePermissions';
import { X, ChevronDown, PanelLeft, PanelLeftClose } from 'lucide-react';
import { isItemOnlyOperator, useWorkspaceNavigation } from './workspace/useWorkspaceNavigation';
import { useWorkspaceDrawer } from './workspace/useWorkspaceDrawer';
import { useSupplyOrders } from './workspace/useSupplyOrders';
import { SupplyOrdersPanels } from './workspace/SupplyOrdersPanels';
import { useWorkspaceBootstrap } from './workspace/useWorkspaceBootstrap';
import { SchoolWorkspacePanels, PlatformWorkspacePanels, FirefightingWorkspacePanels } from './workspace/WorkspacePanels';
import { humaniseCode } from '../shared/display/status';
import { NavIcon } from '../shared/display/icons';
import { ModuleShell } from './workspace/ui';
import { WorkspaceDraftProvider, useConfirmWorkspaceExit } from './workspace/WorkspaceDrafts';

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
  } = useWorkspaceNavigation({ role: user?.role, activeModules, can, canAny, itemOnlyOperator: isItemOnlyOperator(user) });
  const {
    sidebarOpen, setSidebarOpen, drawerIsModal,
    sidebarRef, mainRef, menuToggleRef, sidebarCloseRef,
    pinned, togglePinned, openGroups, toggleGroup,
  } = useWorkspaceDrawer(role);

  // ── Core workspace state ────────────────────────────────────────────────────
  const { workspace, workspaceError, refresh, schoolScopedParams } = useWorkspaceBootstrap({
    user, isPlatformAdmin, isZoneAdmin, isOperations, setActiveModules, logout, navigate,
  });
  // saInvBadge: invoice notification badge in the SA nav sidebar.
  const [saInvBadge, setSaInvBadge] = useState(0);

  const supplyOrders = useSupplyOrders({
    panel, panelAllowed, entitlementsReady, isPlatformAdmin, schoolScopedParams,
  });

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

          <SchoolWorkspacePanels
            panel={panel} panelAllowed={panelAllowed} workspace={workspace} setPanel={setPanel}
            refresh={refresh} schoolScopedParams={schoolScopedParams} user={user}
            isZoneAdmin={isZoneAdmin} isTeacher={isTeacher} dashboardModuleAccess={dashboardModuleAccess}
            canImportStudents={can('student:import')} canCreateStudents={can('student:create')}
          />

          <SupplyOrdersPanels
            panel={panel} panelAllowed={panelAllowed} isPlatformAdmin={isPlatformAdmin}
            isOperations={isOperations} setPanel={setPanel} snapshotOrders={workspace?.orders}
            orders={supplyOrders}
          />
          <PlatformWorkspacePanels
            panel={panel} isPlatformAdmin={isPlatformAdmin} setPanel={setPanel} setSaInvBadge={setSaInvBadge}
          />
          <FirefightingWorkspacePanels
            panel={panel} panelAllowed={panelAllowed} isPlatformAdmin={isPlatformAdmin}
            setPanel={setPanel} openFfDraft={openFfDraft} ffEditingCode={ffEditingCode} refresh={refresh}
          />
          </Suspense>
        </div>
      </main>
    </div>
  );
}
