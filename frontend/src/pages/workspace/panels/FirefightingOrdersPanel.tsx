import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell } from '../ui';
import { formatMoney } from '../utils';
import type { FirefightingRequest } from '../../../types/workspace';

interface FfTrackItem {
  state: string;
  title: string;
  meta: string;
  note?: string;
}

interface Props {
  isSuperAdmin: boolean;
  adminRequests: FirefightingRequest[];
  onRefresh: () => Promise<void>;
}

export function FirefightingOrdersPanel({ isSuperAdmin, adminRequests, onRefresh }: Props) {
  const [saFfRequests, setSaFfRequests] = useState<FirefightingRequest[]>([]);
  const [ffTrackOpen, setFfTrackOpen] = useState(false);
  const [ffTrackRequest, setFfTrackRequest] = useState<FirefightingRequest | null>(null);
  const [ffTimeline, setFfTimeline] = useState<FfTrackItem[]>([]);
  const [ffTimelineLoading, setFfTimelineLoading] = useState(false);

  useEffect(() => {
    if (!isSuperAdmin) return;
    api.get<FirefightingRequest[]>('/ff/requests')
      .then((res) => setSaFfRequests(Array.isArray(res.data) ? res.data : []))
      .catch(() => setSaFfRequests([]));
  }, [isSuperAdmin]);

  const openFfTrack = async (req: FirefightingRequest) => {
    setFfTrackRequest(req);
    setFfTrackOpen(true);
    setFfTimeline([]);
    setFfTimelineLoading(true);
    try {
      const res = await api.get<FfTrackItem[]>(`/ff/requests/${req.code}/timeline`);
      setFfTimeline(res.data || []);
    } catch {
      setFfTimeline([]);
    } finally {
      setFfTimelineLoading(false);
    }
  };

  const approveFfCustoking = async (req: FirefightingRequest) => {
    try {
      await api.post(`/ff/requests/${req.code}/approve-custoking`, {});
      if (isSuperAdmin) {
        const res = await api.get<FirefightingRequest[]>('/ff/requests');
        setSaFfRequests(Array.isArray(res.data) ? res.data : []);
      }
      await onRefresh();
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Failed to approve for Custoking fulfillment';
      alert(msg);
    }
  };

  const fulfillFfRequest = async (req: FirefightingRequest) => {
    try {
      await api.patch(`/ff/requests/${req.code}/fulfill`);
      if (isSuperAdmin) {
        const res = await api.get<FirefightingRequest[]>('/ff/requests');
        setSaFfRequests(Array.isArray(res.data) ? res.data : []);
      }
      await onRefresh();
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Failed to mark as fulfilled';
      alert(msg);
    }
  };

  const allReqs = isSuperAdmin ? saFfRequests : adminRequests;
  const displayRows = allReqs.filter((r) => ['FULFILLED', 'APPROVED', 'CUSTOKING_APPROVED'].includes(r.status));

  return (
    <>
      <ModuleShell
        title={isSuperAdmin ? '🔥 Firefighting — approve & fulfill' : '🔥 Placed orders'}
        subtitle={isSuperAdmin ? 'Approve for Custoking fulfillment and mark orders as delivered' : 'Firefighting requests approved and fulfilled by Custoking'}
      >
        {isSuperAdmin && (
          <div className="ck-alert ck-alert-b" style={{ marginBottom: 18 }}>
            <span>ℹ</span>
            <div>Once bursar and principal approve, click <strong>Approve for fulfillment</strong> to start Custoking processing. After delivery and invoice, click <strong>Mark fulfilled</strong>.</div>
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
                      {row.status === 'CUSTOKING_APPROVED' ? 'Custoking fulfils approved' : row.status}
                    </span>
                  </td>
                  <td style={{ color: 'var(--ink3)' }}>{row.date}</td>
                  {isSuperAdmin && (
                    <td>
                      {row.status === 'APPROVED' && <button className="ck-btn ck-btn-or" style={{ fontSize: 11, padding: '5px 12px' }} onClick={() => void approveFfCustoking(row)}>Approve for fulfillment</button>}
                      {row.status === 'CUSTOKING_APPROVED' && <button className="ck-btn ck-btn-g" style={{ fontSize: 11, padding: '5px 12px' }} onClick={() => void fulfillFfRequest(row)}>Mark fulfilled</button>}
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
              {ffTimelineLoading ? <div style={{ textAlign: 'center', color: 'var(--ink3)', padding: 20 }}>Loading timeline…</div> : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 0 }}>
                  {ffTimeline.map((item, i) => (
                    <div key={i} style={{ display: 'flex', gap: 14, alignItems: 'flex-start', paddingBottom: i < ffTimeline.length - 1 ? 20 : 0, position: 'relative' }}>
                      {i < ffTimeline.length - 1 && <div style={{ position: 'absolute', left: 10, top: 22, width: 2, height: 'calc(100% - 4px)', background: item.state === 'done' ? 'var(--g)' : 'var(--border2)', zIndex: 0 }} />}
                      <div style={{ width: 22, height: 22, borderRadius: '50%', flexShrink: 0, background: item.state === 'done' ? 'var(--g)' : item.state === 'active' ? 'var(--b)' : 'var(--border2)', border: item.state === 'active' ? '2px solid var(--b)' : 'none', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 10, color: item.state === 'pending' ? 'var(--ink3)' : '#fff', fontWeight: 700, zIndex: 1 }}>
                        {item.state === 'done' ? '✓' : item.state === 'active' ? '•' : '○'}
                      </div>
                      <div style={{ flex: 1, paddingTop: 2 }}>
                        <div style={{ fontSize: 13.5, fontWeight: 600, color: item.state === 'pending' ? 'var(--ink3)' : 'var(--ink)' }}>{item.title}</div>
                        <div style={{ fontSize: 12, color: 'var(--ink3)', marginTop: 2 }}>{item.meta}</div>
                        {item.note && <div style={{ fontSize: 12, color: 'var(--ink2)', marginTop: 5, background: 'var(--bg)', padding: '5px 10px', borderRadius: 6, borderLeft: '3px solid var(--border2)' }}>{item.note}</div>}
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
    </>
  );
}
