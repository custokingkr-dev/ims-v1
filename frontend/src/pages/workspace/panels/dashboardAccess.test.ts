import { describe, expect, it } from 'vitest';
import {
  canAccessDashboardModule,
  dashboardModuleLabel,
  filterKeysForDashboardAccess,
  matchesDashboardFilter,
} from './dashboardAccess';

describe('dashboard module access', () => {
  it('shows ERP dashboard modules only when ERP is enabled', () => {
    const access = { erp: true, supplyOs: false };

    expect(canAccessDashboardModule('fees', access)).toBe(true);
    expect(canAccessDashboardModule('students', access)).toBe(true);
    expect(canAccessDashboardModule('attendance', access)).toBe(true);
    expect(canAccessDashboardModule('supply', access)).toBe(false);
    expect(canAccessDashboardModule('firefighting', access)).toBe(false);
    expect(filterKeysForDashboardAccess(access)).toEqual(['all', 'fees', 'students', 'attendance']);
  });

  it('shows Supply OS dashboard modules only when Supply OS is enabled', () => {
    const access = { erp: false, supplyOs: true };

    expect(canAccessDashboardModule('fees', access)).toBe(false);
    expect(canAccessDashboardModule('students', access)).toBe(false);
    expect(canAccessDashboardModule('attendance', access)).toBe(false);
    expect(canAccessDashboardModule('supply', access)).toBe(true);
    expect(canAccessDashboardModule('firefighting', access)).toBe(true);
    expect(filterKeysForDashboardAccess(access)).toEqual(['all', 'urgentProcurement']);
    expect(matchesDashboardFilter('supply', 'urgentProcurement')).toBe(true);
    expect(matchesDashboardFilter('firefighting', 'urgentProcurement')).toBe(true);
    expect(matchesDashboardFilter('fees', 'urgentProcurement')).toBe(false);
  });

  it('renames supply and firefighting dashboard labels to Urgent Procurement', () => {
    expect(dashboardModuleLabel('supply')).toBe('Urgent Procurement');
    expect(dashboardModuleLabel('firefighting')).toBe('Urgent Procurement');
  });
});
