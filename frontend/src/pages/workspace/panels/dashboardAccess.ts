import type { ActionModule } from '../../../types/workspace';

export interface DashboardModuleAccess {
  erp: boolean;
  supplyOs: boolean;
}

export type DashboardFilterKey = 'all' | ActionModule | 'urgentProcurement';

const ERP_MODULES: ActionModule[] = ['fees', 'students', 'attendance'];
const SUPPLY_OS_MODULES: ActionModule[] = ['supply', 'firefighting'];

export function canAccessDashboardModule(module: ActionModule, access: DashboardModuleAccess): boolean {
  if (ERP_MODULES.includes(module)) return access.erp;
  if (SUPPLY_OS_MODULES.includes(module)) return access.supplyOs;
  return false;
}

export function filterKeysForDashboardAccess(access: DashboardModuleAccess): DashboardFilterKey[] {
  return [
    'all',
    ...(access.erp ? ERP_MODULES : []),
    ...(access.supplyOs ? ['urgentProcurement' as DashboardFilterKey] : []),
  ];
}

export function matchesDashboardFilter(module: ActionModule, filter: DashboardFilterKey): boolean {
  if (filter === 'all') return true;
  if (filter === 'urgentProcurement') return SUPPLY_OS_MODULES.includes(module);
  return module === filter;
}

export function dashboardModuleLabel(module: ActionModule): string {
  switch (module) {
    case 'fees':
      return 'Fees & Finance';
    case 'students':
      return 'Student Lifecycle';
    case 'attendance':
      return 'Attendance';
    case 'supply':
    case 'firefighting':
      return 'Urgent Procurement';
    default:
      return module;
  }
}

export function dashboardFilterLabel(filter: DashboardFilterKey): string {
  if (filter === 'all') return 'All modules';
  if (filter === 'urgentProcurement') return 'Urgent Procurement';
  return dashboardModuleLabel(filter);
}
