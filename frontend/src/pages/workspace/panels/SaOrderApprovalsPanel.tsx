import { ModuleShell, PanelMessage, Stat } from '../ui';
import { formatIsoDay, formatMoney } from '../utils';
import { useState } from 'react';
import { ProductOrderDetail } from '../../../features/catalog/ProductOrderDetail';
import { useCategoryLabel } from '../../../features/catalog/useCategoryLabel';

interface Props {
  orders: any[];
  loading: boolean;
  notice: { type: string; msg: string } | null;
  savingId: string;
  rejectModalOrderId: string | null;
  rejectReason: string;
  onRefresh: () => void;
  onApprove: (id: string) => void;
  onOpenRejectModal: (id: string) => void;
  onCloseRejectModal: () => void;
  onSetRejectReason: (reason: string) => void;
  onReject: () => void;
}

export function SaOrderApprovalsPanel({
  orders, loading, notice, savingId,
  rejectModalOrderId, rejectReason,
  onRefresh, onApprove, onOpenRejectModal, onCloseRejectModal, onSetRejectReason, onReject,
}: Props) {
  const [selectedOrder, setSelectedOrder] = useState<string | null>(null);
  const categoryLabel = useCategoryLabel();
  if (selectedOrder) return <ProductOrderDetail orderId={selectedOrder} onBack={() => setSelectedOrder(null)} onChanged={onRefresh} />;
  return (
    <ModuleShell
      title="Supply order approvals"
      subtitle="Review submitted orders, quotations and approval prerequisites"
      actions={<button className="ck-btn ck-btn-ghost" onClick={onRefresh}>↻ Refresh</button>}
    >
      {notice && (
        <div className={`ck-alert ${notice.type === 'error' ? 'ck-alert-re' : 'ck-alert-g'}`} style={{ marginBottom: 16 }}>
          <span>{notice.type === 'error' ? '✕' : '✓'}</span>
          <div>{notice.msg}</div>
        </div>
      )}
      <div className="ck-stats ck-s4" style={{ marginBottom: 16 }}>
        <Stat
          label="Awaiting approval"
          value={orders.length}
          sub="Orders ready for pricing and final review"
          pill={orders.length > 0 ? 'Action needed' : 'All clear'}
          tone={orders.length > 0 ? 'orange' : 'green'}
        />
      </div>
      <div className="ck-card">
        {loading ? (
          <PanelMessage>Loading orders…</PanelMessage>
        ) : orders.length === 0 ? (
          <PanelMessage>No orders awaiting final approval.</PanelMessage>
        ) : (
          <div className="ck-table-wrap"><table className="ck-table">
            <thead>
              <tr>
                <th>Order ID</th><th>School</th><th>Category</th>
                <th>Amount</th><th>Placed on</th><th style={{ textAlign: 'right' }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {orders.map((row: any) => (
                <tr key={row.id}>
                  {/* The Description column repeated the Category verbatim: the order row carries no
                      description, title or items field, so the whole column could only ever echo its
                      neighbour. An order awaiting pricing also has no placedAt, and falling through to
                      an em dash hid how long it had been waiting; createdAt is always present. */}
                  <td>
                    <div className="tb">{row.id}</div>
                    {row.estimatedDelivery ? <div className="ts">{row.estimatedDelivery}</div> : null}
                  </td>
                  <td><div className="tb">{row.schoolName}</div></td>
                  <td>{categoryLabel(row.category)}</td>
                  <td>{row.pricingStatus === 'PENDING_PRICING'
                    ? <span className="ck-pill ck-pill-am">Pending pricing</span>
                    : <span style={{ fontWeight: 600 }}>{`₹${formatMoney(Number(row.totalAmount ?? 0) / 100)}`}</span>}</td>
                  <td style={{ color: 'var(--ink3)' }}>{formatIsoDay(row.placedAt || row.createdAt)}</td>
                  <td>
                    <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
                      {Number(row.formVersion) === 2 ? <button className="ck-btn ck-btn-g" onClick={() => setSelectedOrder(row.id)}>Review order</button> : <button className="ck-btn ck-btn-g" disabled={savingId === row.id} onClick={() => onApprove(row.id)}>
                        {savingId === row.id ? 'Saving…' : 'Approve'}
                      </button>}
                      <button className="ck-btn ck-btn-ghost" disabled={savingId === row.id} onClick={() => onOpenRejectModal(row.id)}>
                        Return
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table></div>
        )}
      </div>

      {rejectModalOrderId && (
        <div className="ck-modal-bg" onClick={onCloseRejectModal}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()}>
            <div className="ck-modal-h">
              <div className="ck-modal-title">Return order for revision</div>
              <button className="ck-modal-x" onClick={onCloseRejectModal}>×</button>
            </div>
            <div className="ck-modal-body">
              <p style={{ marginBottom: 12, color: 'var(--ink2)', fontSize: 13 }}>
                Order <strong>{rejectModalOrderId}</strong> will be returned for revision and the admin will be notified.
              </p>
              <div className="field">
                <label>Reason for returning (shown to school admin)</label>
                <textarea
                  value={rejectReason}
                  onChange={e => onSetRejectReason(e.target.value)}
                  placeholder="e.g. Budget exceeded, missing specification details, please revise quantity…"
                  rows={3}
                />
              </div>
            </div>
            <div className="ck-modal-foot">
              <button className="ck-btn ck-btn-ghost" onClick={onCloseRejectModal}>Cancel</button>
              <button className="ck-btn ck-btn-g" disabled={savingId === rejectModalOrderId} onClick={onReject}>
                {savingId === rejectModalOrderId ? 'Returning…' : 'Return to admin'}
              </button>
            </div>
          </div>
        </div>
      )}
    </ModuleShell>
  );
}
