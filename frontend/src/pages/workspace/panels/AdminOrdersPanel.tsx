import { ModuleShell, Stat } from '../ui';
import { formatMoney, prettyOrderStatus } from '../utils';

interface Props {
  orders: any[];
  stats: any;
  loading: boolean;
  notice: { type: string; msg: string } | null;
  schoolScopedParams?: Record<string, any>;
  onNewOrder: () => void;
  onMarkDesignApproved: (id: string) => void;
  onReorder: (row: any) => void;
}

export function AdminOrdersPanel({
  orders, stats, loading, notice, schoolScopedParams,
  onNewOrder, onMarkDesignApproved, onReorder,
}: Props) {
  return (
    <ModuleShell
      title="My orders"
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
          <div style={{ padding: 16 }}>Loading orders…</div>
        ) : (
          <table className="ck-table">
            <thead>
              <tr><th>Order</th><th>Category</th><th>Items</th><th>Amount</th><th>Status</th><th>Date</th><th /></tr>
            </thead>
            <tbody>
              {orders.map((row: any, i: number) => {
                const status = String(row.status).toUpperCase();
                return (
                  <tr key={i}>
                    <td><div className="tb">{row.id || row.code}</div><div className="ts">{row.description || row.title || row.category}</div></td>
                    <td>{row.category}</td>
                    <td>{row.items}</td>
                    <td>₹{formatMoney(Math.round(Number(row.totalAmount ?? row.amount ?? 0) / 100))}</td>
                    <td>
                      <span className={`ck-status ${status === 'DELIVERED' || status === 'APPROVED' ? 'sg' : status.includes('PROCESS') ? 'sb2' : status.includes('DESIGN') ? 'sam' : 'sgr'}`}>
                        {prettyOrderStatus(row.status)}
                      </span>
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
    </ModuleShell>
  );
}
