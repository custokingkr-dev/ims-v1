import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field, Stat } from '../ui';
import { formatMoney, todayIso } from '../utils';

interface Props {
  onBadgeChange?: (count: number) => void;
}

export function SaInvoicesPanel({ onBadgeChange }: Props) {
  const [saInvoices, setSaInvoices] = useState<any[]>([]);
  const [saInvoicesLoading, setSaInvoicesLoading] = useState(false);
  const [saInvoicesError, setSaInvoicesError] = useState('');
  const [saInvStats, setSaInvStats] = useState<any>(null);
  const [saInvOpen, setSaInvOpen] = useState(false);
  const [saInvData, setSaInvData] = useState<any>({});
  const [saInvEditing, setSaInvEditing] = useState(false);
  const [saInvSaving, setSaInvSaving] = useState(false);
  const [saInvError, setSaInvError] = useState('');
  const [saInvExistingId, setSaInvExistingId] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 3500);
    return () => clearTimeout(t);
  }, [toast]);

  const loadSaInvoices = async () => {
    setSaInvoicesLoading(true); setSaInvoicesError('');
    try {
      const [invRes, statsRes] = await Promise.all([api.get('/sa/invoices'), api.get('/sa/invoices/stats')]);
      setSaInvoices(Array.isArray(invRes.data) ? invRes.data : []);
      setSaInvStats(statsRes.data || null);
      const pending = Number(statsRes.data?.pending || 0);
      onBadgeChange?.(pending);
    } catch (e: any) {
      setSaInvoicesError(e?.response?.data?.message || 'Failed to load invoices.');
    } finally {
      setSaInvoicesLoading(false);
    }
  };

  useEffect(() => { void loadSaInvoices(); }, []);

  const openBlankSaInvoice = () => {
    setSaInvError(''); setSaInvEditing(true); setSaInvSaving(false); setSaInvExistingId(null);
    setSaInvData({ school: '', orderRef: '', description: '', qty: 1, rate: 0, amount: 0, gstAmount: 0, total: 0, status: 'Awaiting payment', issuedAt: todayIso(), dueAt: new Date(Date.now() + 14 * 864e5).toISOString().slice(0, 10), notes: '' });
    setSaInvOpen(true);
  };

  const openSaInvoiceView = async (invoiceId: string) => {
    setSaInvError(''); setSaInvEditing(false); setSaInvSaving(false);
    try {
      const inv = saInvoices.find((i: any) => i.id === invoiceId);
      if (!inv) { setToast('Record not found'); return; }
      setSaInvData({ ...inv }); setSaInvExistingId(invoiceId); setSaInvOpen(true);
    } catch { setToast('Failed to load invoice'); }
  };

  const sendSaInvoice = async () => {
    // Note: resend is not wired here — there is no resend endpoint. This only
    // handles first-time invoice creation (saInvExistingId is falsy); the button
    // is disabled once an invoice already exists (see "Resend" below).
    setSaInvSaving(true); setSaInvError('');
    try {
      const amount = Number(saInvData.qty || 0) * Number(saInvData.rate || 0);
      const res = await api.post('/sa/invoices', { orderRef: saInvData.orderRef, school: saInvData.school, schoolId: saInvData.schoolId ?? null, description: saInvData.description, qty: Number(saInvData.qty || 0), rate: Number(saInvData.rate || 0), amount, notes: saInvData.notes || '' });
      setSaInvExistingId(res.data.id); setSaInvData({ ...res.data });
      setToast(`Invoice ${res.data.id} sent to ${saInvData.school}`);
      await loadSaInvoices();
    } catch (e: any) {
      setSaInvError(e?.response?.data?.message || 'Save failed. Please try again.');
    } finally {
      setSaInvSaving(false);
    }
  };

  const saveSaInvoiceEdit = async () => {
    if (!saInvExistingId) return;
    setSaInvSaving(true); setSaInvError('');
    try {
      await api.patch(`/sa/invoices/${saInvExistingId}`, { description: saInvData.description, qty: Number(saInvData.qty || 0), rate: Number(saInvData.rate || 0), school: saInvData.school, status: saInvData.status });
      setSaInvEditing(false);
      await loadSaInvoices();
    } catch (e: any) {
      setSaInvError(e?.response?.data?.message || 'Save failed. Please try again.');
    } finally {
      setSaInvSaving(false);
    }
  };

  const closeModal = () => { setSaInvOpen(false); void loadSaInvoices(); };

  return (
    <>
      <ModuleShell title="Invoices" subtitle="All platform invoices across schools" actions={<button className="ck-btn ck-btn-g" onClick={openBlankSaInvoice}>+ Create invoice</button>}>
        <div className="ck-grid ck-grid-4" style={{ marginBottom: 16 }}>
          <Stat label="Sent this month" value={saInvStats?.sentThisMonth ?? 0} sub="Invoices issued" pill="Current" tone="blue" />
          <Stat label="Paid" value={saInvStats?.paid ?? 0} sub="Settled invoices" pill="Received" tone="green" />
          <Stat label="Pending" value={saInvStats?.pending ?? 0} sub="Awaiting payment" pill="Action" tone="orange" />
          <Stat label="Total invoiced" value={`₹${formatMoney(Number(saInvStats?.totalInvoiced || 0) / 100)}`} sub="Grand total" pill="Paise→₹" tone="blue" />
        </div>
        <div className="ck-card">
          {saInvoicesLoading ? <div style={{ padding: 16 }}>Loading invoices…</div>
          : saInvoicesError ? <div style={{ padding: 16 }}>{saInvoicesError}</div>
          : <table className="ck-table">
            <thead><tr><th>Invoice</th><th>School</th><th>Order ref</th><th>Total</th><th>Status</th><th>Issued</th><th /></tr></thead>
            <tbody>
              {saInvoices.length === 0
                ? <tr><td colSpan={7}><div className="ts">No invoices found.</div></td></tr>
                : saInvoices.map((row: any) => (
                  <tr key={row.id}>
                    <td><div className="tb">{row.id}</div><div className="ts">{row.description || 'Invoice'}</div></td>
                    <td>{row.school || '—'}</td>
                    <td>{row.orderRef || '—'}</td>
                    <td>₹{formatMoney(Number(row.total || 0) / 100)}</td>
                    <td><span className={`ck-status ${String(row.status).toLowerCase().includes('paid') ? 'sg' : 'sam'}`}>{row.status}</span></td>
                    <td>{row.issuedAt || '—'}</td>
                    <td style={{ display: 'flex', gap: 8 }}>
                      <button className="ck-btn ck-btn-ghost" onClick={() => openSaInvoiceView(row.id)}>View</button>
                      {String(row.status).toLowerCase().includes('awaiting')
                        ? <button className="ck-btn ck-btn-ghost" disabled title="Coming soon">Resend</button>
                        : <button className="ck-btn ck-btn-ghost" disabled title="Coming soon">Download</button>}
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>}
        </div>
      </ModuleShell>

      {saInvOpen && (
        <div className="ck-modal-bg" onClick={closeModal}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()}>
            <div className="ck-modal-h">
              <div className="ck-modal-title">Invoice</div>
              <button className="ck-modal-x" onClick={closeModal}>×</button>
            </div>
            <div className="ck-modal-body">
              {saInvError && <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>✕</span><div>{saInvError}</div></div>}
              <div className="ck-form-grid ck-fg-2">
                <Field label="Invoice number"><input value={saInvExistingId || 'Draft'} disabled /></Field>
                <Field label="Bill to"><input value={saInvData.school || ''} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => setSaInvData({ ...saInvData, school: e.target.value })} /></Field>
                <Field label="Order ref"><input value={saInvData.orderRef || ''} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => setSaInvData({ ...saInvData, orderRef: e.target.value })} /></Field>
                <Field label="Description"><input value={saInvData.description || ''} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => setSaInvData({ ...saInvData, description: e.target.value })} /></Field>
                <Field label="Qty">
                  <input type="number" min="1" value={saInvData.qty || 1} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => { const qty = Number(e.target.value || 0); const rate = Number(saInvData.rate || 0); const amount = qty * rate; const gstAmount = Math.round(amount * 0.12); setSaInvData({ ...saInvData, qty, amount, gstAmount, total: amount + gstAmount }); }} />
                </Field>
                <Field label="Rate (paise)">
                  <input type="number" min="0" value={saInvData.rate || 0} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => { const rate = Number(e.target.value || 0); const qty = Number(saInvData.qty || 0); const amount = qty * rate; const gstAmount = Math.round(amount * 0.12); setSaInvData({ ...saInvData, rate, amount, gstAmount, total: amount + gstAmount }); }} />
                </Field>
                <Field label="Status">
                  <select value={saInvData.status || 'Awaiting payment'} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => setSaInvData({ ...saInvData, status: e.target.value })}>
                    <option>Awaiting payment</option>
                    <option>Paid</option>
                  </select>
                </Field>
                <Field label="Due date"><input type="date" value={saInvData.dueAt || ''} disabled={!saInvEditing && !!saInvExistingId} onChange={(e) => setSaInvData({ ...saInvData, dueAt: e.target.value })} /></Field>
              </div>
              <div className="ck-card" style={{ marginTop: 16 }}>
                <div className="ck-form-body">
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}><span>Subtotal</span><strong>₹{formatMoney(Number(saInvData.amount || 0) / 100)}</strong></div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}><span>GST 12%</span><strong>₹{formatMoney(Number(saInvData.gstAmount || 0) / 100)}</strong></div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}><span>Total</span><strong>₹{formatMoney(Number(saInvData.total || 0) / 100)}</strong></div>
                </div>
              </div>
            </div>
            <div className="ck-modal-foot">
              <button className="ck-btn ck-btn-ghost" disabled title="Coming soon">Download PDF</button>
              {saInvExistingId && !saInvEditing ? <button className="ck-btn ck-btn-ghost" onClick={() => setSaInvEditing(true)}>Edit invoice</button> : null}
              {saInvExistingId && saInvEditing ? <button className="ck-btn ck-btn-ghost" disabled={saInvSaving} onClick={saveSaInvoiceEdit}>{saInvSaving ? 'Saving…' : 'Save changes'}</button> : null}
              {saInvExistingId
                ? <button className="ck-btn ck-btn-g" disabled title="Coming soon">Resend to school</button>
                : <button className="ck-btn ck-btn-g" disabled={saInvSaving} onClick={sendSaInvoice}>{saInvSaving ? 'Sending…' : 'Send to school'}</button>}
            </div>
          </div>
        </div>
      )}

      {toast && (
        <div className="ck-command-toast ok">
          {toast}
        </div>
      )}
    </>
  );
}
