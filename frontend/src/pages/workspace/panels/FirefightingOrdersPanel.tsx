import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell } from '../ui';
import { formatMoney } from '../utils';
import type { FirefightingRequest } from '../../../types/workspace';
import { getDisplayStatus } from '../../../shared/display/status';
import { markFirefightingVendorPaid } from '../../../api/dashboardCommandCenterApi';
import { type FfTrackItem, formatFfDate, labelFfStatus } from './ffUtils';

interface Props {
  isSuperAdmin: boolean;
  onRefresh: () => Promise<void>;
}

export function FirefightingOrdersPanel({ isSuperAdmin, onRefresh }: Props) {
  const [saFfRequests, setSaFfRequests] = useState<FirefightingRequest[]>([]);
  const [saFfLoading, setSaFfLoading] = useState(false);
  const [saFfError, setSaFfError] = useState('');
  const [ffTrackOpen, setFfTrackOpen] = useState(false);
  const [ffTrackRequest, setFfTrackRequest] = useState<FirefightingRequest | null>(null);
  const [ffTimeline, setFfTimeline] = useState<FfTrackItem[]>([]);
  const [ffTimelineLoading, setFfTimelineLoading] = useState(false);
  const [ffTimelineError, setFfTimelineError] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const [vendorPaidLoading, setVendorPaidLoading] = useState<string | null>(null);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 3500);
    return () => clearTimeout(t);
  }, [toast]);

  useEffect(() => {
    setSaFfLoading(true);
    setSaFfError('');
    api.get('/ff/requests', { params: { limit: 200 } })
      .then((res) => setSaFfRequests(Array.isArray(res.data) ? res.data : []))
      .catch(() => { setSaFfRequests([]); setSaFfError('Failed to load orders. Please refresh to try again.'); })
      .finally(() => setSaFfLoading(false));
  }, []);

  const openFfTrack = async (req: FirefightingRequest) => {
    setFfTrackRequest(req);
    setFfTrackOpen(true);
    setFfTimeline([]);
    setFfTimelineError(false);
    setFfTimelineLoading(true);
    try {
      const res = await api.get<FfTrackItem[]>(`/ff/requests/${req.code}/timeline`);
      setFfTimeline(res.data || []);
    } catch {
      setFfTimeline([]);
      setFfTimelineError(true);
    } finally {
      setFfTimelineLoading(false);
    }
  };

  const approveFfCustoking = async (req: FirefightingRequest) => {
    try {
      await api.post(`/ff/requests/${req.code}/approve-custoking`, {});
      const res = await api.get('/ff/requests', { params: { limit: 200 } });
      setSaFfRequests(Array.isArray(res.data) ? res.data : []);
      await onRefresh();
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Failed to approve for Custoking fulfilment';
      setToast(msg);
    }
  };

  const fulfillFfRequest = async (req: FirefightingRequest) => {
    try {
      await api.patch(`/ff/requests/${req.code}/fulfill`);
      const res = await api.get('/ff/requests', { params: { limit: 200 } });
      setSaFfRequests(Array.isArray(res.data) ? res.data : []);
      await onRefresh();
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Failed to mark as delivered';
      setToast(msg);
    }
  };

  const handleMarkVendorPaid = async (req: FirefightingRequest) => {
    const note = window.prompt('Payment note / paid by (optional — press OK to skip, Cancel to abort):');
    if (note === null) return; // user cancelled
    setVendorPaidLoading(req.code);
    try {
      await markFirefightingVendorPaid(req.code, note ? { notes: note } : undefined);
      const res = await api.get('/ff/requests', { params: { limit: 200 } });
      setSaFfRequests(Array.isArray(res.data) ? res.data : []);
      await onRefresh();
      setToast('Vendor payment recorded');
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Failed to mark vendor as paid';
      setToast(msg);
    } finally {
      setVendorPaidLoading(null);
    }
  };

  const allReqs = saFfRequests;
  const displayRows = allReqs.filter((r) => ['FULFILLED', 'APPROVED', 'CUSTOKING_APPROVED'].includes(r.status));

  return (
    <>
      <ModuleShell
        title={isSuperAdmin ? 'Urgent Procurement — Fulfilment Tracking' : 'Urgent Procurement — Placed Orders'}
        subtitle={isSuperAdmin ? 'Approve for Custoking fulfilment and mark orders as delivered' : 'Urgent procurement requests approved and fulfilled by Custoking'}
      >
        {saFfLoading && <div style={{ padding: '10px 0', color: 'var(--ink3)', fontSize: 13 }}>Loading orders…</div>}
        {saFfError && <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>✕</span><div>{saFfError}</div></div>}
        {isSuperAdmin && (
          <div className="ck-alert ck-alert-b" style={{ marginBottom: 18 }}>
            <span>ℹ</span>
            <div>Once Finance Review and Admin Approval are complete, click <strong>Move to Fulfilment</strong> to start Custoking processing. After delivery and invoice, click <strong>Mark as Delivered</strong>.</div>
          </div>
        )}
        <div className="ck-card">
          <table className="ck-table">
            <thead>
              <tr>
                <th>Request</th><th>Category</th><th>Vendor</th><th>Amount</th><th>Status</th><th>Date</th>
                {isSuperAdmin && <th>Action</th>}
              </tr>
            </thead>
            <tbody>
              {displayRows.map((row, i) => (
                <tr key={i}>
                  <td onClick={() => void openFfTrack(row)} style={{ cursor: 'pointer' }}>
                    <div className="tb">{row.code}</div><div className="ts">{row.title}</div>
                  </td>
                  <td>{row.category}</td>
                  <td>{row.winnerVendor || row.winner || '—'}</td>
                  <td style={{ fontWeight: 600, color: 'var(--g)' }}>₹{formatMoney(Number(row.winnerAmount || row.amount || 0))}</td>
                  <td>
                    <span className={`ck-status ${row.status === 'FULFILLED' ? 'sg' : row.status === 'CUSTOKING_APPROVED' ? 'sg' : 'sb2'}`}>
                      {getDisplayStatus(row.status)}
                    </span>
                  </td>
                  <td style={{ color: 'var(--ink3)' }}>{formatFfDate(row.createdAt)}</td>
                  {isSuperAdmin && (
                    <td>
                      {row.status === 'APPROVED' && <button className="ck-btn ck-btn-or" style={{ fontSize: 11, padding: '5px 12px' }} onClick={() => void approveFfCustoking(row)}>Move to Fulfilment</button>}
                      {row.status === 'CUSTOKING_APPROVED' && <button className="ck-btn ck-btn-g" style={{ fontSize: 11, padding: '5px 12px' }} onClick={() => void fulfillFfRequest(row)}>Mark as Delivered</button>}
                      {row.status === 'FULFILLED' && <button className="ck-btn ck-btn-b" style={{ fontSize: 11, padding: '5px 12px' }} disabled={vendorPaidLoading === row.code} onClick={() => void handleMarkVendorPaid(row)}>{vendorPaidLoading === row.code ? 'Saving…' : 'Mark Vendor Paid'}</button>}
                    </td>
                  )}
                </tr>
              ))}
              {displayRows.length === 0 && (
                <tr><td colSpan={isSuperAdmin ? 7 : 6} style={{ textAlign: 'center', color: 'var(--ink3)', padding: 20 }}>No approved orders yet.</td></tr>
              )}
            </tbody>
          </table>
        </div>
        <div className="ck-alert ck-alert-g" style={{ marginTop: 16 }}><span>✦</span><div><strong>Custoking note:</strong> Click any request title to view the full approval timeline.</div></div>
      </ModuleShell>

      {ffTrackOpen && ffTrackRequest && (
        <div className="ck-modal-bg" onClick={() => setFfTrackOpen(false)}>
          <div className="ck-modal" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 520 }}>
            <div className="ck-modal-h">
              <div>
                <div className="ck-modal-title">{ffTrackRequest.title}</div>
                <div style={{ fontSize: 11, color: 'var(--ink3)', fontFamily: 'monospace', marginTop: 2 }}>{ffTrackRequest.code} · {ffTrackRequest.status}</div>
              </div>
              <button className="ck-modal-x" onClick={() => setFfTrackOpen(false)}>×</button>
            </div>
            <div className="ck-modal-body">
              {ffTimelineLoading ? <div style={{ textAlign: 'center', color: 'var(--ink3)', padding: 20 }}>Loading timeline…</div>
              : ffTimelineError ? <div className="ck-alert ck-alert-re"><span>✕</span><div>Failed to load timeline. Please close and try again.</div></div>
              : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 0 }}>
                  {ffTimeline.map((item, i) => (
                    <div key={i} style={{ display: 'flex', gap: 14, alignItems: 'flex-start', paddingBottom: i < ffTimeline.length - 1 ? 20 : 0, position: 'relative' }}>
                      {i < ffTimeline.length - 1 && <div style={{ position: 'absolute', left: 10, top: 22, width: 2, height: 'calc(100% - 4px)', background: 'var(--g)', zIndex: 0 }} />}
                      <div style={{ width: 22, height: 22, borderRadius: '50%', flexShrink: 0, background: 'var(--g)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 10, color: '#fff', fontWeight: 700, zIndex: 1 }}>
                        ✓
                      </div>
                      <div style={{ flex: 1, paddingTop: 2 }}>
                        <div style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--ink)' }}>{labelFfStatus(item.status)}</div>
                        <div style={{ fontSize: 12, color: 'var(--ink3)', marginTop: 2 }}>{formatFfDate(item.at)}</div>
                      </div>
                    </div>
                  ))}
                  {ffTimeline.length === 0 && <div style={{ textAlign: 'center', color: 'var(--ink3)', padding: 16 }}>No timeline data available.</div>}
                </div>
              )}
            </div>
            <div className="ck-modal-foot"><button className="ck-btn ck-btn-ghost" onClick={() => setFfTrackOpen(false)}>Close</button></div>
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
