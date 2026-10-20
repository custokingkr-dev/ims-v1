import { useEffect, useState } from 'react';
import api from '../../services/api';
import { catalogClient } from '../../services/catalogApi';
import type { PanelKey } from './config';

interface SupplyOrderOptions {
  panel: PanelKey;
  panelAllowed: boolean;
  entitlementsReady: boolean;
  isPlatformAdmin: boolean;
  schoolScopedParams: { schoolId: number } | undefined;
}

/** Page-scoped order data and actions remain mounted when another workspace panel opens. */
export function useSupplyOrders({ panel, panelAllowed, entitlementsReady, isPlatformAdmin, schoolScopedParams }: SupplyOrderOptions) {
  // ── Supply order state (AdminOrdersPanel and SaOrderApprovalsPanel need page-level state) ──
  // liveOrders holds the full PageResponse envelope { content, page, size, totalElements, totalPages, last }
  const [liveOrders, setLiveOrders] = useState<any | null>(null);
  const [ordersPage, setOrdersPage] = useState(0);
  const [liveOrderStats, setLiveOrderStats] = useState<any | null>(null);
  const [ordersLoading, setOrdersLoading] = useState(false);
  const [catalogNotice, setCatalogNotice] = useState<{ type: string; msg: string } | null>(null);

  const [pendingApprovalOrders, setPendingApprovalOrders] = useState<any[]>([]);
  const [pendingApprovalLoading, setPendingApprovalLoading] = useState(false);
  const [approvalActionSaving, setApprovalActionSaving] = useState('');
  const [approvalNotice, setApprovalNotice] = useState<{ type: string; msg: string } | null>(null);
  const [rejectModalOrderId, setRejectModalOrderId] = useState<string | null>(null);
  const [rejectReason, setRejectReason] = useState('');
  const [designApprovingSaving, setDesignApprovingSaving] = useState('');
  // ── Supply order loaders and actions ───────────────────────────────────────
  const loadLiveOrders = async (page = 0) => {
    setOrdersLoading(true);
    try {
      const [ordRes, statsRes] = await Promise.all([
        catalogClient.getOrderPage({ ...schoolScopedParams, page, size: 20 }).then(data => ({ data })),
        catalogClient.getOrderSummary(schoolScopedParams).then(data => ({ data })),
      ]);
      // GET /supply/orders now returns a real PageResponse envelope
      // ({content, page, size, totalElements, totalPages}). Fall back to treating a
      // bare array as a single page for resilience against older/mocked responses.
      const ordersData: any = ordRes.data;
      const content = Array.isArray(ordersData) ? ordersData : (ordersData?.content ?? []);
      const totalPages = Array.isArray(ordersData) ? 1 : (ordersData?.totalPages ?? 1);
      setLiveOrders({
        content,
        totalPages,
        page,
      });
      setOrdersPage(page);
      setLiveOrderStats(statsRes.data);
    } catch (e: any) {
      setCatalogNotice({ type: 'error', msg: e?.response?.data?.message || 'Failed to load orders.' });
    } finally { setOrdersLoading(false); }
  };

  const loadPendingApprovalOrders = async () => {
    setPendingApprovalLoading(true);
    try {
      const res = await api.get('/catalog/orders/pending-approval');
      setPendingApprovalOrders(Array.isArray(res.data) ? res.data : []);
    } catch (e: any) {
      setApprovalNotice({ type: 'error', msg: e?.response?.data?.message || 'Failed to load orders.' });
    } finally {
      setPendingApprovalLoading(false);
    }
  };

  const approveOrder = async (orderId: string) => {
    setApprovalActionSaving(orderId);
    setApprovalNotice(null);
    try {
      await api.post(`/catalog/orders/${orderId}/superadmin-approve`);
      setApprovalNotice({ type: 'success', msg: `Order ${orderId} approved and marked for fulfilment.` });
      await loadPendingApprovalOrders();
    } catch (e: any) {
      setApprovalNotice({ type: 'error', msg: e?.response?.data?.message || 'Approval failed.' });
    } finally {
      setApprovalActionSaving('');
    }
  };

  const rejectOrder = async () => {
    if (!rejectModalOrderId) return;
    setApprovalActionSaving(rejectModalOrderId);
    setApprovalNotice(null);
    try {
      await api.post(`/catalog/orders/${rejectModalOrderId}/superadmin-reject`, {
        reason: rejectReason || 'Rejected by Superadmin',
      });
      setApprovalNotice({ type: 'success', msg: `Order ${rejectModalOrderId} sent back for revision.` });
      setRejectModalOrderId(null);
      setRejectReason('');
      await loadPendingApprovalOrders();
    } catch (e: any) {
      setApprovalNotice({ type: 'error', msg: e?.response?.data?.message || 'Rejection failed.' });
    } finally {
      setApprovalActionSaving('');
    }
  };

  const markDesignApproved = async (orderId: string) => {
    if (designApprovingSaving === orderId) return;
    setDesignApprovingSaving(orderId);
    try {
      await api.post(`/catalog/orders/${orderId}/design-approved`);
      setCatalogNotice({ type: 'success', msg: `Order ${orderId} marked design approved and moved to superadmin review.` });
      await loadLiveOrders();
      if (isPlatformAdmin) await loadPendingApprovalOrders();
    } catch (e: any) {
      setCatalogNotice({ type: 'error', msg: e?.response?.data?.message || 'Failed to update design status.' });
    } finally {
      setDesignApprovingSaving('');
    }
  };

  const reorder = async (row: any) => {
    try {
      await api.post('/catalog/orders', {
        category: row.category,
        orderData: row.orderData || JSON.stringify({ title: row.description || row.category }),
        subtotal: row.subtotal || 0,
        gst: row.gst || 0,
        totalAmount: row.totalAmount || 0,
        requiredByDate: row.requiredByDate || null,
        status: 'DRAFT',
        ...(schoolScopedParams || {}),
      });
      setCatalogNotice({ type: 'success', msg: 'Reorder placed as draft.' });
      loadLiveOrders(0);
    } catch (e: any) {
      setCatalogNotice({ type: 'error', msg: e?.response?.data?.message || 'Failed to place reorder.' });
    }
  };

  useEffect(() => {
    if (isPlatformAdmin) void loadPendingApprovalOrders();
  }, [isPlatformAdmin]);

  useEffect(() => {
    if (!entitlementsReady || !panelAllowed || panel !== 'orders') return;
    void loadLiveOrders();
    if (isPlatformAdmin) void loadPendingApprovalOrders();
  }, [panel, panelAllowed, entitlementsReady]);
  return {
    liveOrders, ordersPage, liveOrderStats, ordersLoading, catalogNotice,
    pendingApprovalOrders, pendingApprovalLoading, approvalActionSaving, approvalNotice,
    rejectModalOrderId, rejectReason, designApprovingSaving, setRejectModalOrderId, setRejectReason,
    loadLiveOrders, loadPendingApprovalOrders, approveOrder, rejectOrder, markDesignApproved, reorder,
  };
}
