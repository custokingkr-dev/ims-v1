import { useEffect, useState } from 'react';
import type { NavigateFunction } from 'react-router-dom';
import type { AuthUser } from '../../types/auth';
import api from '../../services/api';
import { reportingClient } from '../../services/reportingApi';
import { type WorkspaceData, withDerivedModuleGroups } from './config';

interface WorkspaceBootstrapOptions {
  user: AuthUser | null;
  isPlatformAdmin: boolean;
  isZoneAdmin: boolean;
  isOperations: boolean;
  setActiveModules: (modules: Set<string>) => void;
  logout: () => Promise<void>;
  navigate: NavigateFunction;
}

/** Loads the workspace and school entitlements together, preserving scoped requests and recovery. */
export function useWorkspaceBootstrap({ user, isPlatformAdmin, isZoneAdmin, isOperations,
  setActiveModules, logout, navigate }: WorkspaceBootstrapOptions) {
  const [workspace, setWorkspace] = useState<WorkspaceData | null>(null);
  const [workspaceError, setWorkspaceError] = useState('');
  // schoolScopedParams: used for non-platform-admin API calls that need a schoolId filter.
  const schoolScopedParams = !isPlatformAdmin && user?.branchId
    ? { schoolId: user.branchId }
    : undefined;
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

  return { workspace, workspaceError, refresh, schoolScopedParams };
}
