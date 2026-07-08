import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field, Info } from '../ui';
import { formatMoney } from '../utils';
import type { FirefightingRequest, Quotation } from '../../../types/workspace';
import { usePermissions } from '../../../hooks/usePermissions';
import { formatFfDate } from './ffUtils';

const FF_STAGES = [
  { key: 'DRAFT', label: 'Draft' },
  { key: 'AWAITING_BURSAR', label: 'Finance Review' },
  { key: 'AWAITING_PRINCIPAL', label: 'Admin Approval' },
  { key: 'APPROVED', label: 'Approved (awaiting Custoking)' },
  { key: 'CUSTOKING_APPROVED', label: 'Custoking Approved' },
  { key: 'FULFILLED', label: 'Fulfilled' },
] as const;

const FF_STAGE_ORDER = FF_STAGES.map((s) => s.key);

function WorkflowStepper({ status }: { status: string }) {
  const currentIdx = FF_STAGE_ORDER.indexOf(status as (typeof FF_STAGE_ORDER)[number]);
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 0, padding: '10px 18px', borderBottom: '1px solid var(--border)', background: 'var(--bg)' }}>
      {FF_STAGES.map((stage, i) => {
        const done = i < currentIdx;
        const active = i === currentIdx;
        return (
          <div key={stage.key} style={{ display: 'flex', alignItems: 'center', flex: i < FF_STAGES.length - 1 ? '1' : 'none' }}>
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4, minWidth: 0 }}>
              <div style={{
                width: 22, height: 22, borderRadius: '50%', flexShrink: 0,
                background: done ? 'var(--g)' : active ? 'var(--b)' : 'var(--border2)',
                border: active ? '2px solid var(--b)' : 'none',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: 10, color: done || active ? '#fff' : 'var(--ink3)', fontWeight: 700,
              }}>
                {done ? '✓' : i + 1}
              </div>
              <div style={{ fontSize: 10, fontWeight: active ? 700 : 500, color: done ? 'var(--g)' : active ? 'var(--b)' : 'var(--ink3)', whiteSpace: 'nowrap' }}>
                {stage.label}
              </div>
            </div>
            {i < FF_STAGES.length - 1 && (
              <div style={{ flex: 1, height: 2, background: done ? 'var(--g)' : 'var(--border2)', margin: '0 4px', marginBottom: 16 }} />
            )}
          </div>
        );
      })}
    </div>
  );
}

interface Props {
  isSuperAdmin: boolean;
  onRefresh: () => Promise<void>;
}

export function FirefightingApprovalsPanel({ isSuperAdmin, onRefresh }: Props) {
  const { can } = usePermissions();
  const canApprove = can('firefighting:approve');
  const [pendingRequests, setPendingRequests] = useState<FirefightingRequest[]>([]);
  const [ffApprovalDetails, setFfApprovalDetails] = useState<FirefightingRequest[]>([]);
  const [ffApprovalLoading, setFfApprovalLoading] = useState(false);
  const [ffApprovalLoadError, setFfApprovalLoadError] = useState('');
  const [ffApprovalNotes, setFfApprovalNotes] = useState<Record<string, string>>({});
  const [approving, setApproving] = useState<Record<string, boolean>>({});
  const [toast, setToast] = useState<string | null>(null);
  const [rejectModalOpen, setRejectModalOpen] = useState(false);
  const [rejectTarget, setRejectTarget] = useState<FirefightingRequest | null>(null);
  const [rejectReason, setRejectReason] = useState('');
  const [rejectError, setRejectError] = useState('');
  const [rejectSaving, setRejectSaving] = useState(false);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 3500);
    return () => clearTimeout(t);
  }, [toast]);

  const loadPendingRequests = async () => {
    try {
      const res = await api.get('/ff/requests/pending-approvals', { params: { limit: 200 } });
      setPendingRequests(Array.isArray(res.data) ? res.data : []);
    } catch {
      setPendingRequests([]);
    }
  };

  useEffect(() => { void loadPendingRequests(); }, []);

  const loadFfApprovalDetails = async () => {
    const pending = pendingRequests.filter((r) => ['AWAITING_BURSAR', 'AWAITING_PRINCIPAL', 'APPROVED'].includes(r.status));
    if (!pending.length) { setFfApprovalDetails([]); setFfApprovalLoadError(''); return; }
    setFfApprovalLoading(true);
    setFfApprovalLoadError('');
    try {
      const results = await Promise.all(
        pending.map((r) =>
          api.get<FirefightingRequest>(`/ff/requests/${r.code}`)
            .then((res) => ({ code: r.code, data: res.data }))
            .catch(() => ({ code: r.code, data: null }))
        )
      );
      const failed = results.filter((r) => r.data === null).map((r) => r.code);
      setFfApprovalDetails(results.filter((r) => r.data !== null).map((r) => r.data) as FirefightingRequest[]);
      if (failed.length) {
        setFfApprovalLoadError(`Could not load details for request(s): ${failed.join(', ')}. They may still be approved or rejected below once details load.`);
      }
    } finally {
      setFfApprovalLoading(false);
    }
  };

  useEffect(() => { void loadFfApprovalDetails(); }, [pendingRequests]);

  const approveFfRequest = async (req: FirefightingRequest, chainPrincipal = false) => {
    const note = ffApprovalNotes[req.code] || '';
    const qid = ffApprovalNotes[`${req.code}_qid`] || (req.quotations?.[0]?.id || '');
    setApproving((a) => ({ ...a, [req.code]: true }));
    try {
      if (req.status === 'AWAITING_BURSAR') {
        await api.post(`/ff/requests/${req.code}/approve-bursar`, { note });
        if (chainPrincipal) {
          await api.post(`/ff/requests/${req.code}/approve-principal`, { note, selectedQuotationId: qid });
        }
      } else if (req.status === 'AWAITING_PRINCIPAL') {
        await api.post(`/ff/requests/${req.code}/approve-principal`, { note, selectedQuotationId: qid });
      } else if (req.status === 'APPROVED') {
        await api.post(`/ff/requests/${req.code}/approve-custoking`, {});
      }
      await onRefresh();
      await loadPendingRequests();
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Approval failed';
      setToast(msg);
    } finally {
      setApproving((a) => ({ ...a, [req.code]: false }));
    }
  };

  const openRejectModal = (req: FirefightingRequest) => {
    setRejectTarget(req);
    setRejectReason('');
    setRejectModalOpen(true);
  };

  const confirmReject = async () => {
    if (!rejectTarget) return;
    if (!rejectReason.trim()) { setRejectError('Rejection reason is required'); return; }
    setRejectError('');
    setRejectSaving(true);
    try {
      await api.post(`/ff/requests/${rejectTarget.code}/reject`, { reason: rejectReason });
      await onRefresh();
      await loadPendingRequests();
      setRejectModalOpen(false);
      setRejectTarget(null);
      setRejectReason('');
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Rejection failed';
      setToast(msg);
      // Do NOT close the modal on error — keep context so the user can retry.
    } finally {
      setRejectSaving(false);
    }
  };

  return (
    <>
      <ModuleShell title="Urgent Procurement — Approval Queue" subtitle="Approve or reject procurement requests — Finance Review first, then Admin Approval">
        {ffApprovalLoading && <div className="ck-card" style={{ padding: 20, textAlign: 'center', color: 'var(--ink3)' }}>Loading approval requests…</div>}
        {ffApprovalLoadError && <div className="ck-alert ck-alert-am" style={{ marginBottom: 16 }}><span>⚠</span><div>{ffApprovalLoadError}</div></div>}
        {!ffApprovalLoading && ffApprovalDetails.length === 0 && <div className="ck-card" style={{ padding: 20, textAlign: 'center', color: 'var(--ink3)' }}>No pending approvals.</div>}
        {ffApprovalDetails.map((req) => (
          <div key={req.code} className="ck-form-card" style={{ marginBottom: 14 }}>
            <div className="ck-form-head" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <div>
                <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--ink3)', marginBottom: 2, fontFamily: 'monospace' }}>{req.code} · {formatFfDate(req.createdAt)}</div>
                <div style={{ fontSize: 14, fontWeight: 600 }}>{req.title}</div>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                {req.urgency === 'HIGH' && <span style={{ background: 'var(--re1)', color: 'var(--re)', fontSize: 10, fontWeight: 700, padding: '2px 7px', borderRadius: 5 }}>Urgent</span>}
                <span style={{ background: req.status === 'AWAITING_BURSAR' ? 'var(--am1)' : 'var(--b1)', color: req.status === 'AWAITING_BURSAR' ? 'var(--am)' : 'var(--b)', fontSize: 11.5, fontWeight: 700, padding: '4px 11px', borderRadius: 20 }}>
                  {req.status === 'AWAITING_BURSAR' ? '⏳ Finance Review Pending' : req.status === 'AWAITING_PRINCIPAL' ? '⏳ Admin Approval Pending' : '⏳ Custoking Approval Pending'}
                </span>
              </div>
            </div>
            <WorkflowStepper status={req.status} />
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
                  <Field label="Select winning quotation (for admin approval)">
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
              {canApprove && req.status === 'AWAITING_BURSAR' && (
                <>
                  <button className="ck-btn ck-btn-g" disabled={approving[req.code]} onClick={() => void approveFfRequest(req, false)}>{approving[req.code] ? 'Approving…' : '✓ Approve — Finance Review'}</button>
                  <button className="ck-btn" style={{ background: 'var(--b1)', color: 'var(--b)', border: '1px solid var(--b)', borderRadius: 20, padding: '8px 18px', fontSize: 13, fontWeight: 600, cursor: approving[req.code] ? 'not-allowed' : 'pointer', fontFamily: 'inherit', opacity: approving[req.code] ? 0.6 : 1 }} disabled={approving[req.code]} onClick={() => void approveFfRequest(req, true)}>{approving[req.code] ? 'Approving…' : '✓ Approve — Admin Approval'}</button>
                </>
              )}
              {canApprove && req.status === 'AWAITING_PRINCIPAL' && (
                <button className="ck-btn ck-btn-g" disabled={approving[req.code]} onClick={() => void approveFfRequest(req)}>{approving[req.code] ? 'Approving…' : '✓ Approve — Admin Approval'}</button>
              )}
              {canApprove && isSuperAdmin && req.status === 'APPROVED' && (
                <button className="ck-btn ck-btn-g" disabled={approving[req.code]} onClick={() => void approveFfRequest(req)}>{approving[req.code] ? 'Approving…' : '✓ Approve — Custoking'}</button>
              )}
              {canApprove && (
                <button style={{ background: 'var(--re1)', color: 'var(--re)', border: '1px solid #f5c0bc', borderRadius: 20, padding: '8px 18px', fontSize: 13, fontWeight: 600, cursor: 'pointer', fontFamily: 'inherit', marginLeft: 'auto' }} onClick={() => openRejectModal(req)}>Reject</button>
              )}
            </div>
          </div>
        ))}
      </ModuleShell>

      {rejectModalOpen && rejectTarget && (
        <div className="ck-modal-bg" onClick={() => setRejectModalOpen(false)}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 460 }}>
            <div className="ck-modal-h">
              <div className="ck-modal-title">Reject request</div>
              <button className="ck-modal-x" onClick={() => setRejectModalOpen(false)}>×</button>
            </div>
            <div className="ck-modal-body">
              <div style={{ marginBottom: 14, fontSize: 13.5, color: 'var(--ink2)' }}>
                Rejecting <strong>{rejectTarget.title}</strong> <span style={{ fontFamily: 'monospace', fontSize: 12 }}>({rejectTarget.code})</span>
              </div>
              {rejectError && <div className="ck-alert ck-alert-re" style={{ marginBottom: 10 }}><span>✕</span><div>{rejectError}</div></div>}
              <Field label="Reason for rejection">
                <textarea
                  value={rejectReason}
                  onChange={(e) => { setRejectReason(e.target.value); if (rejectError) setRejectError(''); }}
                  placeholder="Provide a reason — this appears in the audit log and is visible to the requester"
                  style={{ minHeight: 80 }}
                />
              </Field>
            </div>
            <div className="ck-modal-foot">
              <button className="ck-btn ck-btn-ghost" onClick={() => setRejectModalOpen(false)}>Cancel</button>
              <button style={{ background: 'var(--re1)', color: 'var(--re)', border: '1px solid #f5c0bc', borderRadius: 20, padding: '8px 18px', fontSize: 13, fontWeight: 600, cursor: 'pointer', fontFamily: 'inherit' }} disabled={rejectSaving} onClick={() => void confirmReject()}>{rejectSaving ? 'Rejecting…' : 'Confirm rejection'}</button>
            </div>
          </div>
        </div>
      )}

      {toast && (
        <div className="ck-command-toast ok" style={{ position: 'fixed', bottom: 24, right: 24, zIndex: 9999 }}>
          {toast}
        </div>
      )}
    </>
  );
}
