import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Stat } from '../ui';
import { formatMoney } from '../utils';
import type { FirefightingRequest } from '../../../types/workspace';
import type { PanelKey } from '../config';

interface FfTrackItem {
  state: string;
  title: string;
  meta: string;
  note?: string;
}

interface Props {
  isSuperAdmin: boolean;
  adminRequests: FirefightingRequest[];
  setPanel: (key: PanelKey) => void;
  onOpenFfDraft: (code: string) => void;
}

export function FirefightingDashboardPanel({ isSuperAdmin, adminRequests, setPanel, onOpenFfDraft }: Props) {
  const [saFfRequests, setSaFfRequests] = useState<FirefightingRequest[]>([]);
  const [saFfLoading, setSaFfLoading] = useState(false);
  const [ffTrackOpen, setFfTrackOpen] = useState(false);
  const [ffTrackRequest, setFfTrackRequest] = useState<FirefightingRequest | null>(null);
  const [ffTimeline, setFfTimeline] = useState<FfTrackItem[]>([]);
  const [ffTimelineLoading, setFfTimelineLoading] = useState(false);

  useEffect(() => {
    if (!isSuperAdmin) return;
    setSaFfLoading(true);
    api.get<FirefightingRequest[]>('/ff/requests')
      .then((res) => setSaFfRequests(Array.isArray(res.data) ? res.data : []))
      .catch(() => setSaFfRequests([]))
      .finally(() => setSaFfLoading(false));
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

  const allReqs = isSuperAdmin ? saFfRequests : adminRequests;
  if (isSuperAdmin && saFfLoading) return <div className="ck-card" style={{ padding: 24, textAlign: 'center', color: 'var(--ink3)' }}>Loading firefighting requests…</div>;

  const totalReqs = allReqs.length;
  const awaitingCount = allReqs.filter((r) => ['AWAITING_BURSAR', 'AWAITING_PRINCIPAL'].includes(r.status)).length;
  const fulfilledReqs = allReqs.filter((r) => r.status === 'FULFILLED');
  const totalValue = allReqs.reduce((s, r) => s + (Number(r.amount) || 0), 0);
  const pipelineCols = [
    { label: 'Draft', statuses: ['DRAFT'], tone: 'ink3' },
    { label: 'Quotes submitted', statuses: ['AWAITING_BURSAR', 'AWAITING_PRINCIPAL'], tone: 'am' },
    { label: 'Approved', statuses: ['APPROVED', 'CUSTOKING_APPROVED'], tone: 'b' },
    { label: 'Fulfilled', statuses: ['FULFILLED'], tone: 'g' },
  ];

  return (
    <>
      <ModuleShell title="🔥 Firefighting requests" subtitle="Non-catalog procurement — transparent 3-stage approval workflow" actions={!isSuperAdmin ? <button className="ck-btn ck-btn-or" onClick={() => setPanel('ff-new')}>+ New request</button> : undefined}>
        <div className="ck-stats ck-s4">
          <Stat label="Total requests" value={totalReqs} sub="This academic year" pill={`${awaitingCount > 0 ? awaitingCount + ' active' : 'None active'}`} tone="orange" onClick={() => {}} />
          <Stat label="Awaiting approval" value={awaitingCount} sub="Quotes submitted" pill={awaitingCount > 0 ? 'Action needed' : 'All clear'} tone={awaitingCount > 0 ? 'orange' : 'green'} onClick={() => setPanel('ff-approvals')} />
          <Stat label="Total value" value={`₹${formatMoney(totalValue)}`} sub="Non-catalog spend" pill="This year" tone="blue" onClick={() => {}} />
          <Stat label="Placed orders" value={fulfilledReqs.length} sub="Fulfilled by Custoking" pill="All delivered" tone="green" onClick={() => setPanel('ff-orders')} />
        </div>
        <div style={{ marginBottom: 14, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div style={{ fontSize: 15, fontWeight: 600 }}>Request pipeline</div>
        </div>
        <div className="ck-pipeline">
          {pipelineCols.map(col => {
            const colReqs = allReqs.filter((r) => col.statuses.includes(r.status));
            return (
              <div className="ck-pipe-col" key={col.label}>
                <div className="ck-pipe-head">
                  <div className="ck-pipe-label" style={{ color: `var(--${col.tone})` }}>{col.label}</div>
                  <div className="ck-pipe-count" style={{ background: `var(--${col.tone}1,#f4f4f0)`, color: `var(--${col.tone})` }}>{colReqs.length}</div>
                </div>
                <div className="ck-pipe-body">
                  {colReqs.map((r) => (
                    <div className="ck-pipe-card" key={r.code}
                      onClick={() => {
                        if (r.status === 'DRAFT') { onOpenFfDraft(r.code); return; }
                        if (['AWAITING_BURSAR', 'AWAITING_PRINCIPAL'].includes(r.status)) { setPanel('ff-approvals'); return; }
                        if (['FULFILLED', 'APPROVED', 'CUSTOKING_APPROVED'].includes(r.status)) { void openFfTrack(r); }
                      }}
                      style={{ cursor: 'pointer' }}>
                      <div className="pc-id">{r.code}</div>
                      <div className="pc-title">{r.title}</div>
                      <div className="pc-meta">{r.category}{r.urgency === 'HIGH' ? ' · Urgent' : ''}</div>
                      {r.status === 'DRAFT' && <div style={{ fontSize: 10, color: 'var(--ink3)', marginTop: 4 }}>Click to add quotations</div>}
                      {r.status === 'CUSTOKING_APPROVED' && <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--g)', marginTop: 4 }}>✦ Custoking fulfils approved</div>}
                      {r.amount ? <div className="pc-amount">₹{formatMoney(Number(r.amount))}</div> : null}
                    </div>
                  ))}
                  {colReqs.length === 0 && <div style={{ padding: '10px 8px', fontSize: 12, color: 'var(--ink3)' }}>None</div>}
                </div>
              </div>
            );
          })}
        </div>
        <div className="ck-alert ck-alert-g" style={{ marginTop: 18 }}>
          <span>✦</span>
          <div><strong>Custoking insight:</strong> Raise a firefighting request for any non-catalog item. Add 2–3 vendor quotations, get bursar and principal approval, and Custoking fulfils with a single GST invoice.</div>
        </div>
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
              {ffTimelineLoading ? (
                <div style={{ textAlign: 'center', color: 'var(--ink3)', padding: 20 }}>Loading timeline…</div>
              ) : (
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
            <div className="ck-modal-foot">
              <button className="ck-btn ck-btn-ghost" onClick={() => setFfTrackOpen(false)}>Close</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
