import { lazy } from 'react';
import type { AuthUser } from '../../types/auth';
import type { PanelKey, WorkspaceData } from './config';
import type { useWorkspaceNavigation } from './useWorkspaceNavigation';

// Panels are navigation-level features. Loading them on demand keeps large,
// role-specific modules out of the initial workspace download without changing
// the panel API or its state ownership.
const HomePanel = lazy(() => import('./panels/HomePanel').then((module) => ({ default: module.HomePanel })));
const StudentsPanel = lazy(() => import('./panels/StudentsPanel').then((module) => ({ default: module.StudentsPanel })));
const FeeModulePanel = lazy(() => import('./panels/FeeModulePanel').then((module) => ({ default: module.FeeModulePanel })));
const AttendanceModulePanel = lazy(() => import('./panels/AttendanceModulePanel').then((module) => ({ default: module.AttendanceModulePanel })));
const TimetableStudioPanel = lazy(() => import('./panels/TimetableStudioPanel').then((module) => ({ default: module.TimetableStudioPanel })));
const StaffPanel = lazy(() => import('./panels/StaffPanel').then((module) => ({ default: module.StaffPanel })));
const PlanningPanel = lazy(() => import('./panels/PlanningPanel').then((module) => ({ default: module.PlanningPanel })));
const CatalogPanel = lazy(() => import('./panels/CatalogPanel').then((module) => ({ default: module.CatalogPanel })));
const AddStudentPanel = lazy(() => import('./panels/AddStudentPanel').then((module) => ({ default: module.AddStudentPanel })));
const SchoolStructurePanel = lazy(() => import('./panels/SchoolStructurePanel').then((module) => ({ default: module.SchoolStructurePanel })));
const BulkImportPanel = lazy(() => import('./panels/BulkImportPanel').then((module) => ({ default: module.BulkImportPanel })));
const PhotoImportPanel = lazy(() => import('./panels/PhotoImportPanel').then((module) => ({ default: module.PhotoImportPanel })));
const StudentExportPanel = lazy(() => import('./panels/StudentExportPanel').then((module) => ({ default: module.StudentExportPanel })));
const FirefightingDashboardPanel = lazy(() => import('./panels/FirefightingDashboardPanel').then((module) => ({ default: module.FirefightingDashboardPanel })));
const FirefightingNewPanel = lazy(() => import('./panels/FirefightingNewPanel').then((module) => ({ default: module.FirefightingNewPanel })));
const FirefightingApprovalsPanel = lazy(() => import('./panels/FirefightingApprovalsPanel').then((module) => ({ default: module.FirefightingApprovalsPanel })));
const FirefightingOrdersPanel = lazy(() => import('./panels/FirefightingOrdersPanel').then((module) => ({ default: module.FirefightingOrdersPanel })));
const SaErpPanel = lazy(() => import('./panels/SaErpPanel').then((module) => ({ default: module.SaErpPanel })));
const SaRevenuePanel = lazy(() => import('./panels/SaRevenuePanel').then((module) => ({ default: module.SaRevenuePanel })));
const SaCatalogPanel = lazy(() => import('./panels/SaCatalogPanel').then((module) => ({ default: module.SaCatalogPanel })));
const SaAllOrdersPanel = lazy(() => import('./panels/SaAllOrdersPanel').then((module) => ({ default: module.SaAllOrdersPanel })));
const SaNewOrderPanel = lazy(() => import('./panels/SaNewOrderPanel').then((module) => ({ default: module.SaNewOrderPanel })));
const SaSchoolsPanel = lazy(() => import('./panels/SaSchoolsPanel').then((module) => ({ default: module.SaSchoolsPanel })));
const SaInvoicesPanel = lazy(() => import('./panels/SaInvoicesPanel').then((module) => ({ default: module.SaInvoicesPanel })));
const ZoneAdminPanel = lazy(() => import('./panels/ZoneAdminPanel').then((module) => ({ default: module.ZoneAdminPanel })));

interface PanelSelection {
  panel: PanelKey;
  setPanel: (panel: PanelKey, replace?: boolean) => void;
}

interface SchoolPanelsProps extends PanelSelection {
  panelAllowed: boolean;
  workspace: WorkspaceData | null;
  refresh: () => Promise<void>;
  schoolScopedParams?: { schoolId: number };
  user: AuthUser | null;
  isZoneAdmin: boolean;
  isTeacher: boolean;
  dashboardModuleAccess: ReturnType<typeof useWorkspaceNavigation>['dashboardModuleAccess'];
  canImportStudents: boolean;
  canCreateStudents: boolean;
}

// Containers only select panels. State and drafts remain owned by the workspace hooks/provider.
export function SchoolWorkspacePanels({ panel, panelAllowed, workspace, setPanel, refresh,
  schoolScopedParams, user, isZoneAdmin, isTeacher, dashboardModuleAccess,
  canImportStudents, canCreateStudents }: SchoolPanelsProps) {
  return <>
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

    {panelAllowed && panel === 'addstudent' && <AddStudentPanel setPanel={setPanel} onRefresh={refresh} schoolScopedParams={schoolScopedParams} canImportStudents={canImportStudents} />}

    {panelAllowed && panel === 'classsetup' && <SchoolStructurePanel schoolId={user?.branchId ?? undefined} onSaved={refresh} />}

    {panelAllowed && panel === 'bulkimport' && <BulkImportPanel setPanel={setPanel} onRefresh={refresh} schoolScopedParams={schoolScopedParams} canCreateStudents={canCreateStudents} />}

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

    {panelAllowed && panel === 'planning' && workspace && <PlanningPanel workspace={workspace} onRefresh={refresh} setPanel={setPanel} />}

  </>;
}

export function PlatformWorkspacePanels({ panel, isPlatformAdmin, setPanel, setSaInvBadge }: PanelSelection & {
  isPlatformAdmin: boolean;
  setSaInvBadge: (value: number) => void;
}) {
  return <>
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

  </>;
}

export function FirefightingWorkspacePanels({ panel, panelAllowed, isPlatformAdmin, setPanel,
  openFfDraft, ffEditingCode, refresh }: PanelSelection & {
  panelAllowed: boolean;
  isPlatformAdmin: boolean;
  openFfDraft: (code: string) => void;
  ffEditingCode: string | null;
  refresh: () => Promise<void>;
}) {
  return <>
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
  </>;
}
