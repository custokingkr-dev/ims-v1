import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../../services/api';
import { reportingClient } from '../../services/reportingApi';
import { useWorkspaceBootstrap } from './useWorkspaceBootstrap';

vi.mock('../../services/api');
vi.mock('../../services/reportingApi', () => ({ reportingClient: { getWorkspace: vi.fn() } }));
const snapshot = { school: { name: 'School seven', meta: 'ERP' }, dashboard: {} } as any;
const options: Parameters<typeof useWorkspaceBootstrap>[0] = {
  user: { userId: 3, branchId: 7, fullName: 'School admin', email: 'a@test', accessToken: '', role: 'ADMIN' },
  isPlatformAdmin: false, isZoneAdmin: false, isOperations: false,
  setActiveModules: vi.fn(), logout: vi.fn(), navigate: vi.fn(),
};
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(reportingClient.getWorkspace).mockResolvedValue(snapshot);
  vi.mocked(api.get).mockResolvedValue({ data: [{ moduleCode: 'students' }, { moduleCode: 'orders' }] });
});
afterEach(cleanup);

describe('workspace bootstrap ownership', () => {
  it('loads the assigned school snapshot and entitlements as one successful bootstrap', async () => {
    const { result } = renderHook(() => useWorkspaceBootstrap(options));
    await waitFor(() => expect(result.current.workspace).toBe(snapshot));
    expect(reportingClient.getWorkspace).toHaveBeenCalledWith({ schoolId: 7 });
    expect(api.get).toHaveBeenCalledWith('/schools/7/modules/active');
    expect(options.setActiveModules).toHaveBeenCalledWith(new Set(['STUDENTS', 'ORDERS', 'ERP', 'SUPPLY_OS']));
  });

  it('keeps entitlements pending after a partial failure and retries both requests', async () => {
    vi.mocked(api.get).mockRejectedValueOnce({ response: { data: { message: 'Entitlements unavailable' } } });
    const { result } = renderHook(() => useWorkspaceBootstrap(options));
    await waitFor(() => expect(result.current.workspaceError).toBe('Entitlements unavailable'));
    expect(result.current.workspace).toBeNull();
    expect(options.setActiveModules).not.toHaveBeenCalled();
    await act(() => result.current.refresh());
    expect(result.current.workspace).toBe(snapshot);
    expect(result.current.workspaceError).toBe('');
    expect(reportingClient.getWorkspace).toHaveBeenCalledTimes(2);
  });

  it('handles revoked authentication through the existing logout and login recovery', async () => {
    vi.mocked(reportingClient.getWorkspace).mockRejectedValueOnce({ response: { data: { message: 'Invalid access token' } } });
    renderHook(() => useWorkspaceBootstrap(options));
    await waitFor(() => expect(options.logout).toHaveBeenCalledOnce());
    expect(options.navigate).toHaveBeenCalledWith('/login', { replace: true });
    expect(options.setActiveModules).not.toHaveBeenCalled();
  });

  it('does not issue an unscoped school request for an unassigned administrator', async () => {
    const { result } = renderHook(() => useWorkspaceBootstrap({ ...options, user: { ...options.user!, branchId: null } }));
    await waitFor(() => expect(result.current.workspaceError).toBe('This account is not assigned to a school.'));
    expect(reportingClient.getWorkspace).not.toHaveBeenCalled();
    expect(api.get).not.toHaveBeenCalled();
  });

  it.each(['platform', 'zone', 'operations'] as const)('bootstraps the %s shell without a school-wide report request', async (role) => {
    const { result } = renderHook(() => useWorkspaceBootstrap({ ...options,
      user: { ...options.user!, branchId: null, zoneName: 'North zone' },
      isPlatformAdmin: role === 'platform', isZoneAdmin: role === 'zone', isOperations: role === 'operations',
    }));
    await waitFor(() => expect(result.current.workspace).not.toBeNull());
    expect(reportingClient.getWorkspace).not.toHaveBeenCalled();
    expect(api.get).not.toHaveBeenCalled();
    if (role === 'platform') expect(options.setActiveModules).not.toHaveBeenCalled();
    else expect(options.setActiveModules).toHaveBeenCalledWith(new Set(['ORDERS', 'FIREFIGHTING', 'SUPPLY_OS']));
  });
});
