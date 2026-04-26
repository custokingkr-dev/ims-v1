import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field, Info } from '../ui';
import { formatMoney } from '../utils';
import type { FirefightingRequest, Quotation } from '../../../types/workspace';

interface Props {
  pendingRequests: FirefightingRequest[];
  onRefresh: () => Promise<void>;
}

export function FirefightingApprovalsPanel({ pendingRequests, onRefresh }: Props) {
  const [ffApprovalDetails, setFfApprovalDetails] = useState<FirefightingRequest[]>([]);
  const [ffApprovalLoading, setFfApprovalLoading] = useState(false);
  const [ffApprovalNotes, setFfApprovalNotes] = useState<Record<string, string>>({});

  const loadFfApprovalDetails = async () => {
    const pending = pendingRequests.filter((r) => ['AWAITING_BURSAR', 'AWAITING_PRINCIPAL'].includes(r.status));
    if (!pending.length) { setFfApprovalDetails([]); return; }
    setFfApprovalLoading(true);
    try {
      const details = await Promise.all(
        pending.map((r) => api.get<FirefightingRequest>(`/ff/requests/${r.code}`).then((res) => res.data).catch(() => null))
      );
      setFfApprovalDetails(details.filter(Boolean) as FirefightingRequest[]);
    } finally {
      setFfApprovalLoading(false);
    }
  };

  useEffect(() => { void loadFfApprovalDetails(); }, [pendingRequests]);

  const approveFfRequest = async (req: FirefightingRequest, chainPrincipal = false) => {
    const note = ffApprovalNotes[req.code] || '';
    const qid = ffApprovalNotes[`${req.code}_qid`] || (req.quotations?.[0]?.id || '');
    try {
      if (req.status === 'AWAITING_BURSAR') {
        await api.post(`/ff/requests/${req.code}/approve-bursar`, { note });
        if (chainPrincipal) {
          await api.post(`/ff/requests/${req.code}/approve-principal`, { note, selectedQuotationId: qid });
        }
      } else if (req.status === 'AWAITING_PRINCIPAL') {
        await api.post(`/ff/requests/${req.code}/approve-principal`, { note, selectedQuotationId: qid });
      }
      await onRefresh();
      await loadFfApprovalDetails();
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Approval failed';
      alert(msg);
    }
  };

  const rejectFfRequest = async (req: FirefightingRequest) => {
    const reason = window.prompt('Reason for rejection:');
    if (reason === null) return;
    try {
      await api.post(`/ff/requests/${req.code}/reject`, { reason });
      await onRefresh();
      await loadFfApprovalDetails();
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Rejection failed';
      alert(msg);
    }
  };

  return (
    <ModuleShell title="🔥 Pending approvals" subtitle="Approve or reject procurement requests — bursar first, then principal">
      {ffApprovalLoading && <div className="ck-card" style={{ padding: 20, textAlign: 'center', color: 'var(--ink3)' }}>Loading approval requests…</div>}
      {!ffApprovalLoading && ffApprovalDetails.length === 0 && <div className="ck-card" style={{ padding: 20, textAlign: 'center', color: 'var(--ink3)' }}>No pending approvals.</div>}
      {ffApprovalDetails.map((req) => (
        <div key={req.code} className="ck-form-card" style={{ marginBottom: 14 }}>
          <div className="ck-form-head" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--ink3)', marginBottom: 2, fontFamily: 'monospace' }}>{req.code} · {req.date}</div>
              <div style={{ fontSize: 14, fontWeight: 600 }}>{req.title}</div>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              {req.urgency === 'HIGH' && <span style={{ background: 'var(--re1)', color: 'var(--re)', fontSize: 10, fontWeight: 700, padding: '2px 7px', borderRadius: 5 }}>Urgent</span>}
              <span style={{ background: req.status === 'AWAITING_BURSAR' ? 'var(--am1)' : 'var(--b1)', color: req.status === 'AWAITING_BURSAR' ? 'var(--am)' : 'var(--b)', fontSize: 11.5, fontWeight: 700, padding: '4px 11px', borderRadius: 20 }}>
                {req.status === 'AWAITING_BURSAR' ? '⏳ Awaiting bursar review' : '⏳ Awaiting principal approval'}
              </span>
            </div>
          </div>
          <div className="ck-form-body">
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 16 }}>
              <Info label="Category" value={req.category || '—'} />
              <Info label="Required by" value={req.requiredByDate || '—'} />
              <Info label="Budget estimate" value={req.estimatedBudget ? `₹${formatMoney(req.estimatedBudget)}` : '—'} />
              <Info label="Description" value={req.description || req.summary || '—'} />
            </div>
            {(req.quotations || []).length > 0 ? (
              <>
                <div style={{ fontSize: 11.5, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.5px', color: 'var(--ink3)', marginBottom: 10 }}>Quotation comparison</div>
                <table className="ck-table" style={{ marginBottom: 14 }}>
                  <thead><tr><th>Vendor</th><th>Amount</th><th>Delivery</th><th>Document</th><th>Notes</th></tr></thead>
                  <tbody>
                    {(req.quotations || []).map((q: Quotation) => (
                      <tr key={q.id} style={q.isCustoking ? { background: '#f0faf4' } : {}}>
                        <td style={{ fontWeight: 600 }}>{q.vendorName || '—'}{q.isCustoking && <span style={{ fontSize: 10, fontWeight: 700, background: 'var(--g)', color: '#fff', padding: '1px 7px', borderRadius: 5, marginLeft: 6 }}>✦ Our quote</span>}</td>
                        <td style={{ fontWeight: 700, color: 'var(--g)' }}>₹{formatMoney(Number(q.amount))}</td>
                        <td>{q.deliveryTimeline || '—'}</td>
                        <td style={{ fontSize: 12, color: q.documentUrl ? 'var(--g)' : 'var(--ink3)' }}>{q.documentUrl || 'No file'}</td>
                        <td style={{ fontSize: 12, color: 'var(--ink2)' }}>{q.notes || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <Field label="Select winning quotation (for principal approval)">
                  <select value={ffApprovalNotes[`${req.code}_qid`] || (req.quotations?.[0]?.id || '')} onChange={(e) => setFfApprovalNotes(n => ({ ...n, [`${req.code}_qid`]: e.target.value }))}>
                    {(req.quotations || []).map((q: Quotation) => <option key={q.id} value={q.id}>{q.vendorName} — ₹{formatMoney(Number(q.amount))}</option>)}
                    <option value="">No preferred vendor yet</option>
                  </select>
                </Field>
              </>
            ) : (
              <div style={{ background: 'var(--bg)', border: '1px dashed var(--border2)', borderRadius: 7, padding: '10px 14px', fontSize: 12.5, color: 'var(--ink3)', marginBottom: 14 }}>No quotations submitted — admin can still approve based on description.</div>
            )}
            <Field label="Approval note (optional)">
              <input value={ffApprovalNotes[req.code] || ''} onChange={(e) => setFfApprovalNotes(n => ({ ...n, [req.code]: e.target.value }))} placeholder="Add a comment for the audit record…" />
            </Field>
          </div>
          <div style={{ display: 'flex', gap: 10, padding: '14px 18px', borderTop: '1px solid var(--border)', background: 'var(--bg)', flexWrap: 'wrap' }}>
            {req.status === 'AWAITING_BURSAR' && (
              <>
                <button className="ck-btn ck-btn-g" onClick={() => void approveFfRequest(req, false)}>✓ Approve as Bursar</button>
                <button className="ck-btn" style={{ background: 'var(--b1)', color: 'var(--b)', border: '1px solid var(--b)', borderRadius: 20, padding: '8px 18px', fontSize: 13, fontWeight: 600, cursor: 'pointer', fontFamily: 'inherit' }} onClick={() => void approveFfRequest(req, true)}>✓ Approve as Principal</button>
              </>
            )}
            {req.status === 'AWAITING_PRINCIPAL' && (
              <button className="ck-btn ck-btn-g" onClick={() => void approveFfRequest(req)}>✓ Approve as Principal</button>
            )}
            <button style={{ background: 'var(--re1)', color: 'var(--re)', border: '1px solid #f5c0bc', borderRadius: 20, padding: '8px 18px', fontSize: 13, fontWeight: 600, cursor: 'pointer', fontFamily: 'inherit', marginLeft: 'auto' }} onClick={() => void rejectFfRequest(req)}>Reject</button>
          </div>
        </div>
      ))}
    </ModuleShell>
  );
}
