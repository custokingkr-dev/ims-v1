import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../../services/api';
import { catalogClient } from '../../services/catalogApi';
import { useSupplyOrders } from './useSupplyOrders';

vi.mock('../../services/api');
vi.mock('../../services/catalogApi', () => ({ catalogClient: { getOrderPage: vi.fn(), getOrderSummary: vi.fn() } }));
const options: Parameters<typeof useSupplyOrders>[0] = {
  panel: 'home', panelAllowed: true, entitlementsReady: true, isPlatformAdmin: false,
  schoolScopedParams: { schoolId: 7 },
};
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.post).mockResolvedValue({ data: {} });
  vi.mocked(catalogClient.getOrderPage).mockResolvedValue({ content: [{ id: 'ORD-7' }], totalPages: 2 } as any);
  vi.mocked(catalogClient.getOrderSummary).mockResolvedValue({ total: 1 } as any);
});
afterEach(cleanup);

describe('workspace supply order ownership', () => {
  it('loads scoped order data only after the task is allowed and entitlements are ready', async () => {
    const { result, rerender } = renderHook(props => useSupplyOrders(props), {
      initialProps: { ...options, panel: 'orders' as const, entitlementsReady: false },
    });
    expect(catalogClient.getOrderPage).not.toHaveBeenCalled();
    rerender({ ...options, panel: 'orders', entitlementsReady: true, panelAllowed: false });
    expect(catalogClient.getOrderPage).not.toHaveBeenCalled();
    rerender({ ...options, panel: 'orders', entitlementsReady: true });
    await waitFor(() => expect(result.current.ordersLoading).toBe(false));
    expect(catalogClient.getOrderPage).toHaveBeenCalledWith({ schoolId: 7, page: 0, size: 20 });
    expect(catalogClient.getOrderSummary).toHaveBeenCalledWith({ schoolId: 7 });
    expect(result.current.liveOrders.content).toEqual([{ id: 'ORD-7' }]);
  });

  it('keeps rejection details available when the server returns a failure', async () => {
    vi.mocked(api.post).mockRejectedValueOnce({ response: { data: { message: 'Approval version changed' } } });
    const { result } = renderHook(() => useSupplyOrders(options));
    act(() => { result.current.setRejectModalOrderId('ORD-7'); result.current.setRejectReason('Revise quantity'); });
    await act(() => result.current.rejectOrder());
    expect(api.post).toHaveBeenCalledWith('/catalog/orders/ORD-7/superadmin-reject', { reason: 'Revise quantity' });
    expect(result.current.rejectModalOrderId).toBe('ORD-7');
    expect(result.current.rejectReason).toBe('Revise quantity');
    expect(result.current.approvalNotice?.msg).toBe('Approval version changed');
    expect(result.current.approvalActionSaving).toBe('');
  });

  it('reorders trophies as a school-scoped draft and reloads the first page', async () => {
    const { result } = renderHook(() => useSupplyOrders(options));
    await act(() => result.current.reorder({ category: 'TROPHIES', status: 'APPROVED', orderData: '{"items":[]}' }));
    expect(api.post).toHaveBeenCalledWith('/catalog/orders', expect.objectContaining({
      category: 'TROPHIES', schoolId: 7, status: 'DRAFT', orderData: '{"items":[]}',
    }));
    expect(catalogClient.getOrderPage).toHaveBeenCalledWith({ schoolId: 7, page: 0, size: 20 });
    expect(result.current.catalogNotice?.type).toBe('success');
  });
});
