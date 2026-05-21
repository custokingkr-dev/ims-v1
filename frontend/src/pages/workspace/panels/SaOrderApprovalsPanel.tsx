import { ModuleShell, Stat } from '../ui';
import { formatMoney } from '../utils';

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
  return (
    <ModuleShell
      title="Supply order approvals"
      subtitle="Review admin orders pending superadmin approval — notebooks and uniforms after design approval, stationery and events from processing"
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
          sub="Orders in design-approved processing state"
          pill={orders.length > 0 ? 'Action needed' : 'All clear'}
          tone={orders.length > 0 ? 'orange' : 'green'}
        />
      </div>
      <div className="ck-card">
        {loading ? (
          <div style={{ padding: 24, textAlign: 'center', color: 'var(--ink3)' }}>Loading orders…</div>
        ) : orders.length === 0 ? (
          <div style={{ padding: 24, textAlign: 'center', color: 'var(--ink3)' }}>
            No orders awaiting approval. Uniform and notebook orders appear after design approval. Stationery and events orders appear from processing.
          </div>
        ) : (
          <table className="ck-table">
            <thead>
              <tr>
                <th>Order ID</th><th>School</th><th>Category</th><th>Description</th>
                <th>Amount</th><th>Placed on</th><th style={{ textAlign: 'right' }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {orders.map((row: any) => (
                <tr key={row.id}>
                  <td><div className="tb">{row.id}</div><div className="ts">{row.estimatedDelivery || '—'}</div></td>
                  <td><div className="tb">{row.schoolName}</div></td>
                  <td>{row.category}</td>
                  <td><div style={{ maxWidth: 220 }}>{row.description || row.title || row.category}</div><div className="ts">{row.items}</div></td>
                  <td style={{ fontWeight: 600 }}>₹{formatMoney(Math.round(Number(row.totalAmount ?? 0) / 100))}</td>
                  <td style={{ color: 'var(--ink3)' }}>{row.placedAt ? new Date(row.placedAt).toLocaleDateString('en-IN') : row.date || '—'}</td>
                  <td>
                    <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
                      <button className="ck-btn ck-btn-g" disabled={savingId === row.id} onClick={() => onApprove(row.id)}>
                        {savingId === row.id ? 'Saving…' : '✓ Approve'}
                      </button>
                      <button className="ck-btn ck-btn-ghost" disabled={savingId === row.id} onClick={() => onOpenRejectModal(row.id)}>
                        Return
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
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
                Order <strong>{rejectModalOrderId}</strong> will be returned to <em>Design approval</em> and the admin will be notified.
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
