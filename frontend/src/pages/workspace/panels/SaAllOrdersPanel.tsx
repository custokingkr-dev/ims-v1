import { useEffect, useMemo, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field, Info, Stat } from '../ui';
import { formatMoney, todayIso } from '../utils';

interface Props {
  onNewOrder: () => void;
}

export function SaAllOrdersPanel({ onNewOrder }: Props) {
  const [orders, setOrders] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [stats, setStats] = useState<any>(null);
  const [filter, setFilter] = useState({ cat: '', status: '', search: '' });

  const [detailOpen, setDetailOpen] = useState(false);
  const [detailOrder, setDetailOrder] = useState<any | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState('');
  const [newStatus, setNewStatus] = useState('');
  const [statusSaving, setStatusSaving] = useState(false);

  const [invOpen, setInvOpen] = useState(false);
  const [invData, setInvData] = useState<any>({});
  const [invEditing, setInvEditing] = useState(false);
  const [invSaving, setInvSaving] = useState(false);
  const [invError, setInvError] = useState('');
  const [invExistingId, setInvExistingId] = useState<string | null>(null);

  const load = async () => {
    setLoading(true); setError('');
    try {
      const [ordRes, statsRes] = await Promise.all([api.get('/sa/orders'), api.get('/sa/orders/stats')]);
      setOrders(Array.isArray(ordRes.data) ? ordRes.data : []);
      setStats(statsRes.data || null);
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Failed to load orders.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const categoryOptions = useMemo(
    () => Array.from(new Set(orders.map((r: any) => String(r.category || '')).filter(Boolean))).sort(),
    [orders],
  );
  const statusOptions = useMemo(
    () => Array.from(new Set(orders.map((r: any) => String(r.status || '')).filter(Boolean))).sort(),
    [orders],
  );
  const filtered = useMemo(() => {
    const search = filter.search.trim().toLowerCase();
    return orders.filter((r: any) => {
      const categoryOk = !filter.cat || r.category === filter.cat;
      const statusOk = !filter.status || r.status === filter.status;
      const text = `${r.id || ''} ${r.schoolName || ''}`.toLowerCase();
      return categoryOk && statusOk && (!search || text.includes(search));
    });
  }, [orders, filter]);

  const openDetail = async (orderId: string) => {
    setDetailLoading(true); setDetailError(''); setDetailOpen(true); setDetailOrder(null);
    try {
      const res = await api.get(`/supply/orders/${orderId}`);
      setDetailOrder(res.data); setNewStatus(res.data?.status || '');
    } catch (e: any) {
      setDetailError(e?.response?.data?.message || 'Failed to load order.');
    } finally {
      setDetailLoading(false);
    }
  };

  const saveStatus = async () => {
    if (!detailOrder) return;
    setStatusSaving(true); setDetailError('');
    try {
      await api.patch(`/sa/orders/${detailOrder.id}/status`, { status: newStatus });
      setDetailOpen(false); await load();
    } catch (e: any) {
      setDetailError(e?.response?.data?.message || 'Save failed.');
    } finally {
      setStatusSaving(false);
    }
  };

  const acceptOrder = async (orderId: string) => {
    try { await api.patch(`/sa/orders/${orderId}/status`, { status: 'IN_PROGRESS' }); await load(); } catch {}
  };

  const openInvoiceFromOrder = async (orderId: string, school: string, schoolId: number | null, valuePaise: number) => {
    setInvError(''); setInvEditing(false); setInvSaving(false);
    try {
      const res = await api.get(`/sa/invoices/by-order/${orderId}`);
      if (res.data) {
        setInvData({ ...res.data }); setInvExistingId(res.data.id);
      } else {
        const today = todayIso();
        const due = new Date(Date.now() + 14 * 864e5).toISOString().slice(0, 10);
        const gstAmount = Math.round(valuePaise * 0.12);
        setInvData({ orderRef: orderId, school, schoolId, description: orderId, qty: 1, rate: valuePaise, amount: valuePaise, gstAmount, total: valuePaise + gstAmount, status: 'Awaiting payment', issuedAt: today, dueAt: due });
        setInvExistingId(null);
      }
    } catch {
      const gstAmount = Math.round(valuePaise * 0.12);
      setInvData({ orderRef: orderId, school, schoolId, description: orderId, qty: 1, rate: valuePaise, amount: valuePaise, gstAmount, total: valuePaise + gstAmount, status: 'Awaiting payment', issuedAt: todayIso(), dueAt: new Date(Date.now() + 14 * 864e5).toISOString().slice(0, 10) });
      setInvExistingId(null);
    }
    setInvOpen(true);
  };

  const sendInvoice = async () => {
    setInvSaving(true); setInvError('');
    try {
      if (invExistingId) { alert(`Invoice already sent. Resending to ${invData.school}.`); return; }
      const amount = Number(invData.qty || 0) * Number(invData.rate || 0);
      const res = await api.post('/sa/invoices', { orderRef: invData.orderRef, school: invData.school, schoolId: invData.schoolId ?? null, description: invData.description, qty: Number(invData.qty || 0), rate: Number(invData.rate || 0), amount, notes: invData.notes || '' });
      setInvExistingId(res.data.id); setInvData({ ...res.data });
      alert(`Invoice ${res.data.id} sent to ${invData.school}`);
    } catch (e: any) {
      setInvError(e?.response?.data?.message || 'Save failed. Please try again.');
    } finally {
      setInvSaving(false);
    }
  };

  const saveInvEdit = async () => {
    if (!invExistingId) return;
    setInvSaving(true); setInvError('');
    try {
      await api.patch(`/sa/invoices/${invExistingId}`, { description: invData.description, qty: Number(invData.qty || 0), rate: Number(invData.rate || 0), school: invData.school, status: invData.status });
      setInvEditing(false);
    } catch (e: any) {
      setInvError(e?.response?.data?.message || 'Save failed. Please try again.');
    } finally {
      setInvSaving(false);
    }
  };

  return (
    <>
      <ModuleShell title="All orders" subtitle="All catalog orders across all schools" actions={<button className="ck-btn ck-btn-g" onClick={onNewOrder}>+ New order request</button>}>
        <div className="ck-grid ck-grid-4" style={{ marginBottom: 16 }}>
          <Stat label="Total orders" value={stats?.total ?? 0} sub="Across all schools" pill="Live" tone="blue" />
          <Stat label="New requests" value={stats?.newRequests ?? 0} sub="Awaiting approval" pill="Needs review" tone="orange" />
          <Stat label="In progress" value={stats?.inProgress ?? 0} sub="Approved or processing" pill="Active" tone="green" />
          <Stat label="GMV" value={`₹${formatMoney(Math.round(Number(stats?.gmv || 0) / 100))}`} sub="Gross merchandise value" pill="Paise→₹" tone="blue" />
        </div>
        <div className="ck-form-card" style={{ marginBottom: 16 }}>
          <div className="ck-form-body">
            <div className="ck-form-grid ck-fg-3">
              <Field label="Category">
                <select value={filter.cat} onChange={(e) => setFilter({ ...filter, cat: e.target.value })}>
                  <option value="">All</option>
                  {categoryOptions.map((cat) => <option key={cat} value={cat}>{cat}</option>)}
                </select>
              </Field>
              <Field label="Status">
                <select value={filter.status} onChange={(e) => setFilter({ ...filter, status: e.target.value })}>
                  <option value="">All</option>
                  {statusOptions.map((s) => <option key={s} value={s}>{s}</option>)}
                </select>
              </Field>
              <Field label="Search">
                <input value={filter.search} onChange={(e) => setFilter({ ...filter, search: e.target.value })} placeholder="School or order ID" />
              </Field>
            </div>
          </div>
        </div>
        <div className="ck-card">
          {loading ? <div style={{ padding: 16 }}>Loading orders…</div>
          : error ? <div style={{ padding: 16 }}>{error}</div>
          : (
            <table className="ck-table">
              <thead><tr><th>Order</th><th>School</th><th>Category</th><th>Amount</th><th>Status</th><th>Placed</th><th /></tr></thead>
              <tbody>
                {filtered.length === 0
                  ? <tr><td colSpan={7}><div className="ts">No orders found.</div></td></tr>
                  : filtered.map((row: any) => (
                    <tr key={row.id}>
                      <td><div className="tb">{row.id}</div><div className="ts">{row.description || row.title || row.category}</div></td>
                      <td>{row.schoolName || row.school || '—'}</td>
                      <td>{row.category}</td>
                      <td>₹{formatMoney(Math.round(Number(row.totalAmount ?? 0) / 100))}</td>
                      <td><span className={`ck-status ${String(row.status).includes('DELIVER') ? 'sg' : String(row.status).includes('APPROV') || String(row.status).includes('PROGRESS') ? 'sb2' : 'sam'}`}>{row.status}</span></td>
                      <td>{row.placedAt || row.createdAt || '—'}</td>
                      <td style={{ display: 'flex', gap: 8 }}>
                        <button className="ck-btn ck-btn-ghost" onClick={() => openDetail(row.id)}>View</button>
                        {String(row.status).toUpperCase() === 'AWAITING_APPROVAL'
                          ? <button className="ck-btn ck-btn-g" onClick={() => acceptOrder(row.id)}>Accept</button>
                          : <button className="ck-btn ck-btn-ghost" onClick={() => openInvoiceFromOrder(row.id, row.schoolName || row.school || '—', row.schoolId ?? null, Number(row.totalAmount || 0))}>Invoice</button>}
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>
          )}
        </div>
      </ModuleShell>

      {detailOpen && (
        <div className="ck-modal-bg" onClick={() => setDetailOpen(false)}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()}>
            <div className="ck-modal-h">
              <div className="ck-modal-title">Order detail</div>
              <button className="ck-modal-x" onClick={() => setDetailOpen(false)}>×</button>
            </div>
            <div className="ck-modal-body">
              {detailLoading ? <div className="ts">Loading order…</div>
              : detailError ? <div className="ts">{detailError}</div>
              : !detailOrder ? <div className="ts">Record not found.</div>
              : (
                <>
                  <div className="ck-student-modal-info" style={{ marginBottom: 16 }}>
                    <Info label="Order ID" value={String(detailOrder.id || '—')} />
                    <Info label="School" value={String(detailOrder.schoolName || detailOrder.school || '—')} />
                    <Info label="Category" value={String(detailOrder.category || '—')} />
                    <Info label="Amount" value={`₹${formatMoney(Math.round(Number(detailOrder.totalAmount || 0) / 100))}`} />
                    <Info label="Delivery" value={String(detailOrder.estimatedDelivery || detailOrder.requiredByDate || '—')} />
                    <Info label="Status" value={String(detailOrder.status || '—')} />
                  </div>
                  <div className="ck-form-card">
                    <div className="ck-form-head">Update status</div>
                    <div className="ck-form-body">
                      <Field label="Status">
                        <select value={newStatus} onChange={(e) => setNewStatus(e.target.value)}>
                          <option>AWAITING_APPROVAL</option>
                          <option>IN_PROGRESS</option>
                          <option>APPROVED</option>
                          <option>PROCESSING</option>
                          <option>DELIVERED</option>
                        </select>
                      </Field>
                    </div>
                  </div>
                </>
              )}
            </div>
            <div className="ck-modal-foot">
              <button className="ck-btn ck-btn-ghost" onClick={() => detailOrder && openInvoiceFromOrder(detailOrder.id, detailOrder.schoolName || '—', detailOrder.schoolId ?? null, Number(detailOrder.totalAmount || 0))}>Generate invoice</button>
              <button className="ck-btn ck-btn-ghost" onClick={() => alert(`WhatsApp sent to ${detailOrder?.schoolName || detailOrder?.school || 'school'}`)}>WhatsApp school</button>
              <button className="ck-btn ck-btn-ghost" onClick={() => alert(`Downloading order sheet for ${detailOrder?.id || 'order'}`)}>Download order sheet</button>
              <button className="ck-btn ck-btn-g" disabled={statusSaving} onClick={saveStatus}>{statusSaving ? 'Saving…' : 'Update status'}</button>
            </div>
          </div>
        </div>
      )}

      {invOpen && (
        <div className="ck-modal-bg" onClick={() => setInvOpen(false)}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()}>
            <div className="ck-modal-h">
              <div className="ck-modal-title">Invoice</div>
              <button className="ck-modal-x" onClick={() => setInvOpen(false)}>×</button>
            </div>
            <div className="ck-modal-body">
              {invError && <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>✕</span><div>{invError}</div></div>}
              <div className="ck-form-grid ck-fg-2">
                <Field label="Invoice number"><input value={invExistingId || 'Draft'} disabled /></Field>
                <Field label="Bill to"><input value={invData.school || ''} disabled={!invEditing && !!invExistingId} onChange={(e) => setInvData({ ...invData, school: e.target.value })} /></Field>
                <Field label="Order ref"><input value={invData.orderRef || ''} disabled={!invEditing && !!invExistingId} onChange={(e) => setInvData({ ...invData, orderRef: e.target.value })} /></Field>
                <Field label="Description"><input value={invData.description || ''} disabled={!invEditing && !!invExistingId} onChange={(e) => setInvData({ ...invData, description: e.target.value })} /></Field>
                <Field label="Qty">
                  <input type="number" min="1" value={invData.qty || 1} disabled={!invEditing && !!invExistingId} onChange={(e) => { const qty = Number(e.target.value || 0); const rate = Number(invData.rate || 0); const amount = qty * rate; const gstAmount = Math.round(amount * 0.12); setInvData({ ...invData, qty, amount, gstAmount, total: amount + gstAmount }); }} />
                </Field>
                <Field label="Rate (paise)">
                  <input type="number" min="0" value={invData.rate || 0} disabled={!invEditing && !!invExistingId} onChange={(e) => { const rate = Number(e.target.value || 0); const qty = Number(invData.qty || 0); const amount = qty * rate; const gstAmount = Math.round(amount * 0.12); setInvData({ ...invData, rate, amount, gstAmount, total: amount + gstAmount }); }} />
                </Field>
                <Field label="Status">
                  <select value={invData.status || 'Awaiting payment'} disabled={!invEditing && !!invExistingId} onChange={(e) => setInvData({ ...invData, status: e.target.value })}>
                    <option>Awaiting payment</option>
                    <option>Paid</option>
                  </select>
                </Field>
                <Field label="Due date"><input type="date" value={invData.dueAt || ''} disabled={!invEditing && !!invExistingId} onChange={(e) => setInvData({ ...invData, dueAt: e.target.value })} /></Field>
              </div>
              <div className="ck-card" style={{ marginTop: 16 }}>
                <div className="ck-form-body">
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}><span>Subtotal</span><strong>₹{formatMoney(Math.round(Number(invData.amount || 0) / 100))}</strong></div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}><span>GST 12%</span><strong>₹{formatMoney(Math.round(Number(invData.gstAmount || 0) / 100))}</strong></div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}><span>Total</span><strong>₹{formatMoney(Math.round(Number(invData.total || 0) / 100))}</strong></div>
                </div>
              </div>
            </div>
            <div className="ck-modal-foot">
              <button className="ck-btn ck-btn-ghost" onClick={() => alert(`Downloading ${(invExistingId || 'draft')}.pdf`)}>Download PDF</button>
              {invExistingId && !invEditing ? <button className="ck-btn ck-btn-ghost" onClick={() => setInvEditing(true)}>Edit invoice</button> : null}
              {invExistingId && invEditing ? <button className="ck-btn ck-btn-ghost" disabled={invSaving} onClick={saveInvEdit}>{invSaving ? 'Saving…' : 'Save changes'}</button> : null}
              <button className="ck-btn ck-btn-g" disabled={invSaving} onClick={sendInvoice}>{invSaving ? 'Sending…' : 'Send to school'}</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
