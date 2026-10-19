import { useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { AuthUser } from '../../types/auth';
import {
  type PanelKey,
  ACCOUNTANT_NAV_SECTIONS, ADMIN_NAV_SECTIONS, OPERATIONS_NAV_SECTIONS,
  SUPERADMIN_NAV_SECTIONS, TEACHER_NAV_SECTIONS, VIEWER_NAV_SECTIONS,
  ZONE_ADMIN_NAV_SECTIONS, PANEL_TITLES, filterNavSectionsForModules,
} from './config';

interface WorkspaceNavigationOptions {
  role: AuthUser['role'] | undefined;
  activeModules: Set<string> | null;
  can: (permission: string) => boolean;
  canAny: (permissions: string[]) => boolean;
}

/** Owns task URLs and UI permission/entitlement policy; server authorization remains authoritative. */
export function useWorkspaceNavigation({ role, activeModules, can, canAny }: WorkspaceNavigationOptions) {
  const [searchParams, setSearchParams] = useSearchParams();
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

  const requestedPanel = searchParams.get('panel');
  const panel = requestedPanel && Object.prototype.hasOwnProperty.call(PANEL_TITLES, requestedPanel) ? requestedPanel as PanelKey : defaultPanel;
  const setPanel = (nextPanel: PanelKey, replace = false) => {
    setSearchParams(previous => {
      const next = new URLSearchParams(previous);
      next.set('panel', nextPanel);
      if (nextPanel !== 'ff-new') next.delete('request');
      return next;
    }, { replace });
  };

  // ffEditingCode: passed into FirefightingNewPanel when opening a draft from the dashboard.
  const ffEditingCode = panel === 'ff-new' ? searchParams.get('request') : null;
  const openFfDraft = (code: string) => setSearchParams({ panel: 'ff-new', request: code });

  useEffect(() => {
    if (!isPlatformAdmin) return;
    const adminOnlyPanels: PanelKey[] = [
      'home', 'students', 'fees', 'feestructure', 'attendance',
      'timetable', 'addstudent', 'bulkimport', 'staff', 'catalog', 'planning',
      'ff-new', 'ff-approvals',
    ];
    if (adminOnlyPanels.includes(panel)) setPanel('orders');
  }, [isPlatformAdmin, panel]);

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
      case 'nb-work': return ['workflow:read'];
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
  const feeSubpanelAllowed = panel === 'feestructure' && allowedPanelKeys.includes('fees') && panelAllowedByPermission(panel);
  const panelAllowed = allowedPanelKeys.includes(panel) || studentSubpanelAllowed || feeSubpanelAllowed;
  const dashboardModuleAccess = {
    erp: isPlatformAdmin || resolvedActiveModules.has('ERP'),
    supplyOs: isPlatformAdmin || resolvedActiveModules.has('SUPPLY_OS'),
  };

  useEffect(() => {
    if (!entitlementsReady) return;
    if (!panelAllowed && allowedPanelKeys.length) {
      setPanel(allowedPanelKeys.includes(defaultPanel) ? defaultPanel : allowedPanelKeys[0], true);
    } else if (!requestedPanel || !Object.prototype.hasOwnProperty.call(PANEL_TITLES, requestedPanel)) {
      setPanel(panel, true);
    }
  }, [allowedPanelKeys.join('|'), defaultPanel, panel, panelAllowed, entitlementsReady, requestedPanel]);

  return {
    role, isPlatformAdmin, isZoneAdmin, isOperations, isTeacher,
    panel, setPanel, openFfDraft, ffEditingCode, currentTitle,
    navSections, panelAllowed, entitlementsReady, dashboardModuleAccess,
  };
}
