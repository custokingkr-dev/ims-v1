import Paginator from '../../../components/Paginator';
import { ModuleShell, Stat } from '../ui';
import { formatMoney, prettyOrderStatus } from '../utils';

// Supply order status → stage index mapping.
// DESIGN_APPROVAL / DESIGN_APPROVED_PROCESSING are mid-review sub-states.
const ORDER_STAGES = [
  { label: 'Draft',       statuses: ['DRAFT'] },
  { label: 'Submitted',   statuses: ['SUBMITTED', 'AWAITING_APPROVAL'] },
  { label: 'Approval',    statuses: ['DESIGN_APPROVAL', 'DESIGN_APPROVED_PROCESSING', 'APPROVED'] },
  { label: 'Processing',  statuses: ['PROCESSING'] },
  { label: 'Delivered',   statuses: ['DELIVERED', 'FULFILLED'] },
] as const;

function resolveStageIdx(status: string): number {
  const upper = status.toUpperCase();
  return ORDER_STAGES.findIndex((s) => (s.statuses as readonly string[]).includes(upper));
}

function orderStatusClass(status: string): string {
  if (['DELIVERED', 'FULFILLED'].includes(status)) return 'spaid';
  if (status === 'APPROVED' || status === 'CUSTOKING_APPROVED') return 'sapproved';
  if (status === 'PROCESSING') return 'spending';
  if (status.includes('DESIGN')) return 'spartial';
  if (['SUBMITTED', 'AWAITING_APPROVAL'].includes(status)) return 'sneutral';
  return 'sneutral'; // DRAFT, etc.
}

function OrderWorkflowBanner({ status }: { status: string }) {
  const currentIdx = resolveStageIdx(status);
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 0, padding: '10px 0', marginBottom: 4 }}>
      {ORDER_STAGES.map((stage, i) => {
        const done = currentIdx > i;
        const active = currentIdx === i;
        return (
          <div key={stage.label} style={{ display: 'flex', alignItems: 'center', flex: i < ORDER_STAGES.length - 1 ? '1' : 'none' }}>
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 3 }}>
              <div style={{
                width: 20, height: 20, borderRadius: '50%', flexShrink: 0,
                background: done ? 'var(--g)' : active ? 'var(--b)' : 'var(--border2)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: 9, color: done || active ? '#fff' : 'var(--ink3)', fontWeight: 700,
              }}>
                {done ? '✓' : i + 1}
              </div>
              <div style={{ fontSize: 9.5, fontWeight: active ? 700 : 500, color: done ? 'var(--g)' : active ? 'var(--b)' : 'var(--ink3)', whiteSpace: 'nowrap' }}>
                {stage.label}
              </div>
            </div>
            {i < ORDER_STAGES.length - 1 && (
              <div style={{ flex: 1, height: 2, background: done ? 'var(--g)' : 'var(--border2)', margin: '0 3px', marginBottom: 14 }} />
            )}
          </div>
        );
      })}
    </div>
  );
}

interface Props {
  orders: any[];
  stats: any;
  loading: boolean;
  notice: { type: string; msg: string } | null;
  schoolScopedParams?: Record<string, any>;
  page?: number;
  totalPages?: number;
  onPageChange?: (page: number) => void;
  onNewOrder: () => void;
  onMarkDesignApproved: (id: string) => void;
  onReorder: (row: any) => void;
}

export function AdminOrdersPanel({
  orders, stats, loading, notice, schoolScopedParams,
  page = 0, totalPages = 1, onPageChange,
  onNewOrder, onMarkDesignApproved, onReorder,
}: Props) {
  return (
    <ModuleShell
      title="School Orders"
      subtitle="All supply orders from Custoking — track, reorder, download invoices"
      actions={<button className="ck-btn ck-btn-g" onClick={onNewOrder}>+ New order</button>}
    >
      {notice && (
        <div className={`ck-alert ${notice.type === 'error' ? 'ck-alert-re' : 'ck-alert-g'}`} style={{ marginBottom: 16 }}>
          <span>{notice.type === 'error' ? '✕' : '✓'}</span>
          <div>{notice.msg}</div>
        </div>
      )}
      <div className="ck-grid ck-grid-4" style={{ marginBottom: 16 }}>
        <Stat label="Active orders" value={stats?.activeOrders ?? 0} sub="Awaiting, processing, transit" pill="Live" tone="blue" />
        <Stat label="Term spend" value={`₹${formatMoney(Math.round(Number(stats?.termSpend || 0) / 100))}`} sub="Placed this term" pill="Paise→₹" tone="green" />
        <Stat label="Active services" value={stats?.activeServices ?? 0} sub="Running contracts" pill="Service" tone="blue" />
        <Stat label="Delivered" value={stats?.deliveredCount ?? 0} sub="Completed orders" pill="Closed" tone="orange" />
      </div>
      <div className="ck-card">
        {loading ? (
          <table className="ck-table">
            <thead>
              <tr><th>Order</th><th>Category</th><th>Items</th><th className="col-money">Amount</th><th>Workflow</th><th>Status</th><th>Date</th><th /></tr>
            </thead>
            <tbody>
              {[1,2,3,4,5].map(i => (
                <tr key={i} style={{ animationDelay: `${(i-1)*60}ms` }}>
                  <td><div className="ck-skeleton ck-skeleton-text" style={{ marginBottom: 4 }} /><div className="ck-skeleton ck-skeleton-text" style={{ width: '60%' }} /></td>
                  <td><div className="ck-skeleton ck-skeleton-text" /></td>
                  <td><div className="ck-skeleton ck-skeleton-text" style={{ width: 30 }} /></td>
                  <td><div className="ck-skeleton ck-skeleton-text" style={{ width: 60 }} /></td>
                  <td><div className="ck-skeleton" style={{ height: 20, borderRadius: 4 }} /></td>
                  <td><div className="ck-skeleton ck-skeleton-badge" /></td>
                  <td><div className="ck-skeleton ck-skeleton-text" style={{ width: 80 }} /></td>
                  <td />
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <table className="ck-table">
            <thead>
              <tr><th>Order</th><th>Category</th><th>Items</th><th className="col-money">Amount</th><th>Workflow</th><th>Status</th><th>Date</th><th /></tr>
            </thead>
            <tbody>
              {orders.length === 0 ? (
                <tr>
                  <td colSpan={8}>
                    <div style={{ padding: '40px 20px', textAlign: 'center' }}>
                      <div style={{ fontSize: 32, marginBottom: 12 }}>📦</div>
                      <div style={{ fontWeight: 600, marginBottom: 4 }}>No orders yet</div>
                      <div style={{ fontSize: 13, color: 'var(--ink3)' }}>Place your first order to see it here</div>
                    </div>
                  </td>
                </tr>
              ) : orders.map((row: any, i: number) => {
                const status = String(row.status).toUpperCase();
                return (
                  <tr key={i}>
                    <td><div className="tb">{row.id || row.code}</div><div className="ts">{row.description || row.title || row.category}</div></td>
                    <td>{row.category}</td>
                    <td>{row.items}</td>
                    <td className="col-money">₹{formatMoney(Math.round(Number(row.totalAmount ?? row.amount ?? 0) / 100))}</td>
                    <td style={{ minWidth: 200 }}>
                      <OrderWorkflowBanner status={status} />
                    </td>
                    <td>
                      <span className={`ck-status ${orderStatusClass(status)}`}>{prettyOrderStatus(row.status)}</span>
                    </td>
                    <td>{row.placedAt || row.date || '—'}</td>
                    <td>
                      {status === 'DESIGN_APPROVAL'
                        ? <button className="ck-btn ck-btn-ghost" onClick={() => onMarkDesignApproved(row.id)}>Mark design approved</button>
                        : <button className="ck-btn ck-btn-ghost" onClick={() => onReorder(row)}>Reorder</button>
                      }
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>

      {onPageChange && (
        <Paginator page={page} totalPages={totalPages} onPageChange={onPageChange} />
      )}
    </ModuleShell>
  );
}
