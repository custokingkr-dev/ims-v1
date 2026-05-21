import { useState, useCallback } from 'react';
import api from '../../services/api';

export interface RoleView {
  id: number;
  name: string;
  description: string;
  permissions: string[];
}

export interface PermissionView {
  id: number;
  code: string;
  description: string;
}

export interface AssignmentView {
  id: number;
  role: string;
  active: boolean;
  schoolId: number | null;
  zoneId: number | null;
  assignedAt: string;
  assignedBy: number | null;
}

/** Encapsulates RBAC management state — use in an RBAC management page/panel. */
export function useRbacFeature() {
  const [roles, setRoles] = useState<RoleView[]>([]);
  const [permissions, setPermissions] = useState<PermissionView[]>([]);
  const [rolesLoading, setRolesLoading] = useState(false);
  const [permsLoading, setPermsLoading] = useState(false);
  const [rbacError, setRbacError] = useState('');

  const loadRoles = useCallback(async () => {
    setRolesLoading(true);
    setRbacError('');
    try {
      const data = await api.get<RoleView[]>('/api/v1/rbac/roles');
      setRoles(data.data);
    } catch {
      setRbacError('Failed to load roles');
    } finally {
      setRolesLoading(false);
    }
  }, []);

  const loadPermissions = useCallback(async () => {
    setPermsLoading(true);
    try {
      const data = await api.get<PermissionView[]>('/api/v1/rbac/permissions');
      setPermissions(data.data);
    } finally {
      setPermsLoading(false);
    }
  }, []);

  const getUserAssignments = useCallback(async (userId: number): Promise<AssignmentView[]> => {
    const resp = await api.get<AssignmentView[]>(`/api/v1/rbac/users/${userId}/roles`);
    return resp.data;
  }, []);

  const getUserPermissions = useCallback(async (userId: number): Promise<string[]> => {
    const resp = await api.get<string[]>(`/api/v1/rbac/users/${userId}/permissions`);
    return resp.data;
  }, []);

  const assignPlatformRole = useCallback(async (userId: number, role: string) => {
    await api.post(`/api/v1/rbac/users/${userId}/roles/platform`, { role });
  }, []);

  const assignSchoolRole = useCallback(async (userId: number, role: string, schoolId: number) => {
    await api.post(`/api/v1/rbac/users/${userId}/roles/school`, { role, schoolId });
  }, []);

  const assignZoneRole = useCallback(async (userId: number, role: string, zoneId: number) => {
    await api.post(`/api/v1/rbac/users/${userId}/roles/zone`, { role, zoneId });
  }, []);

  const revokeAssignment = useCallback(async (userId: number, assignmentId: number) => {
    await api.delete(`/api/v1/rbac/users/${userId}/roles/${assignmentId}`);
  }, []);

  return {
    roles, permissions,
    rolesLoading, permsLoading,
    rbacError, setRbacError,
    loadRoles, loadPermissions,
    getUserAssignments, getUserPermissions,
    assignPlatformRole, assignSchoolRole, assignZoneRole,
    revokeAssignment,
  };
}
