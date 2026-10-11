import { lazy } from 'react';
import type { PanelKey } from './config';
import type { useSupplyOrders } from './useSupplyOrders';

const SaOrderApprovalsPanel = lazy(() => import('./panels/SaOrderApprovalsPanel').then(module => ({ default: module.SaOrderApprovalsPanel })));
const AdminOrdersPanel = lazy(() => import('./panels/AdminOrdersPanel').then(module => ({ default: module.AdminOrdersPanel })));
const SaAllOrdersPanel = lazy(() => import('./panels/SaAllOrdersPanel').then(module => ({ default: module.SaAllOrdersPanel })));

interface SupplyOrdersPanelsProps {
  panel: PanelKey;
  panelAllowed: boolean;
  isPlatformAdmin: boolean;
  isOperations: boolean;
  setPanel: (panel: PanelKey) => void;
  snapshotOrders: any[] | undefined;
  orders: ReturnType<typeof useSupplyOrders>;
}

/** Role-specific order panels share the page's persistent order state and action owner. */
export function SupplyOrdersPanels({ panel, panelAllowed, isPlatformAdmin, isOperations, setPanel, snapshotOrders, orders }: SupplyOrdersPanelsProps) {
  const orderRows: any[] = orders.liveOrders?.content ?? snapshotOrders ?? [];
  return <>
    {panel === 'orders' && isPlatformAdmin && (
      <SaOrderApprovalsPanel
        orders={orders.pendingApprovalOrders}
        loading={orders.pendingApprovalLoading}
        notice={orders.approvalNotice}
        savingId={orders.approvalActionSaving}
        rejectModalOrderId={orders.rejectModalOrderId}
        rejectReason={orders.rejectReason}
        onRefresh={orders.loadPendingApprovalOrders}
        onApprove={orders.approveOrder}
        onOpenRejectModal={(id) => { orders.setRejectModalOrderId(id); orders.setRejectReason(''); }}
        onCloseRejectModal={() => orders.setRejectModalOrderId(null)}
        onSetRejectReason={orders.setRejectReason}
        onReject={orders.rejectOrder}
      />
    )}
    {panelAllowed && panel === 'orders' && isOperations && (
      <SaAllOrdersPanel onNewOrder={() => setPanel('catalog')} canManage={false} />
    )}
    {panelAllowed && panel === 'orders' && !isPlatformAdmin && !isOperations && (
      <AdminOrdersPanel
        orders={orderRows}
        stats={orders.liveOrderStats}
        loading={orders.ordersLoading}
        notice={orders.catalogNotice}
        page={orders.ordersPage}
        totalPages={orders.liveOrders?.totalPages ?? 1}
        onPageChange={(p) => orders.loadLiveOrders(p)}
        onNewOrder={() => setPanel('catalog')}
        onMarkDesignApproved={orders.markDesignApproved}
        onReorder={orders.reorder}
      />
    )}
  </>;
}
