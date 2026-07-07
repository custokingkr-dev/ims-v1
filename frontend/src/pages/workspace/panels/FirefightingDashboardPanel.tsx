import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Stat } from '../ui';
import { formatMoney } from '../utils';
import type { FirefightingRequest } from '../../../types/workspace';
import type { PanelKey } from '../config';

interface FfTrackItem {
  status: string;
  at: string;
}

interface Props {
  isSuperAdmin: boolean;
  setPanel: (key: PanelKey) => void;
  onOpenFfDraft: (code: string) => void;
}

function formatFfDate(value?: string): string {
  if (!value) return '—';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleString(undefined, { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function labelFfStatus(status: string): string {
  return status
    .split('_')
    .map((w) => w.charAt(0) + w.slice(1).toLowerCase())
    .join(' ');
}

export function FirefightingDashboardPanel({ isSuperAdmin, setPanel, onOpenFfDraft }: Props) {
  const [saFfRequests, setSaFfRequests] = useState<FirefightingRequest[]>([]);
  const [saFfLoading, setSaFfLoading] = useState(false);
  const [saFfError, setSaFfError] = useState('');
  const [ffTrackOpen, setFfTrackOpen] = useState(false);
  const [ffTrackRequest, setFfTrackRequest] = useState<FirefightingRequest | null>(null);
  const [ffTimeline, setFfTimeline] = useState<FfTrackItem[]>([]);
  const [ffTimelineLoading, setFfTimelineLoading] = useState(false);
  const [ffTimelineError, setFfTimelineError] = useState(false);

  useEffect(() => {
    setSaFfLoading(true);
    setSaFfError('');
    api.get('/ff/requests', { params: { limit: 200 } })
      .then((res) => setSaFfRequests(Array.isArray(res.data) ? res.data : []))
      .catch(() => { setSaFfRequests([]); setSaFfError('Failed to load requests. Please refresh to try again.'); })
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

  const allReqs = saFfRequests;
  if (saFfLoading) return (
    <div style={{ padding: '0 0 16px' }}>
      <div className="ck-stats ck-s4" style={{ marginBottom: 20 }}>
        {[1,2,3,4].map(i => (
          <div key={i} className="ck-card" style={{ padding: 20 }}>
            <div className="ck-skeleton ck-skeleton-text" style={{ width: '50%', marginBottom: 8 }} />
            <div className="ck-skeleton" style={{ height: 32, width: '60%', borderRadius: 6, marginBottom: 8 }} />
            <div className="ck-skeleton ck-skeleton-text" style={{ width: '40%' }} />
          </div>
        ))}
      </div>
      <div className="ck-skeleton ck-skeleton-title" style={{ width: 200, marginBottom: 16 }} />
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12 }}>
        {[1,2,3,4].map(i => (
          <div key={i} className="ck-card" style={{ padding: 16, minHeight: 160 }}>
            <div className="ck-skeleton ck-skeleton-text" style={{ width: '70%', marginBottom: 12 }} />
            {[1,2].map(j => <div key={j} className="ck-skeleton ck-skeleton-text" style={{ marginBottom: 8 }} />)}
          </div>
        ))}
      </div>
    </div>
  );

  const totalReqs = allReqs.length;
  const awaitingCount = allReqs.filter((r) => ['AWAITING_BURSAR', 'AWAITING_PRINCIPAL'].includes(r.status)).length;
  const fulfilledReqs = allReqs.filter((r) => r.status === 'FULFILLED');
  const totalValue = allReqs.reduce((s, r) => s + (Number(r.amount) || 0), 0);
  const pipelineCols = [
    { label: 'Draft', statuses: ['DRAFT'], tone: 'ink3' },
    { label: 'Quotes submitted', statuses: ['AWAITING_BURSAR', 'AWAITING_PRINCIPAL'], tone: 'am' },
    { label: 'Approved', statuses: ['APPROVED', 'CUSTOKING_APPROVED'], tone: 'b' },
    { label: 'Fulfilled', statuses: ['FULFILLED'], tone: 'g' },
    { label: 'Rejected', statuses: ['REJECTED'], tone: 're' },
  ];

  return (
    <>
      <ModuleShell title="Urgent Procurement — Request Dashboard" subtitle="Non-catalog urgent procurement — transparent 3-stage approval workflow" actions={!isSuperAdmin ? <button className="ck-btn ck-btn-or" onClick={() => setPanel('ff-new')}>+ New Urgent Request</button> : undefined}>
        {saFfError && <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>✕</span><div>{saFfError}</div></div>}
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
                      {r.urgency === 'HIGH' && <div className="ck-urgency-strip high" style={{ marginTop: 6 }}>High urgency</div>}
                      {r.status === 'DRAFT' && <div style={{ fontSize: 10, color: 'var(--ink3)', marginTop: 4 }}>Click to add quotations</div>}
                      {r.status === 'CUSTOKING_APPROVED' && <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--g)', marginTop: 4 }}>✦ Approved for Fulfilment</div>}
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
          <div><strong>Custoking insight:</strong> Raise an urgent procurement request for any non-catalog item. Add 2–3 vendor quotations, get Finance Review and Admin Approval, and Custoking fulfils with a single GST invoice.</div>
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
                <div className="ck-timeline">
                  {[1,2,3].map(i => (
                    <div key={i} className="ck-timeline-item">
                      <div className="ck-timeline-left">
                        <div className="ck-timeline-dot" style={{ background: 'var(--border2)' }} />
                        {i < 3 && <div className="ck-timeline-line" />}
                      </div>
                      <div className="ck-timeline-body">
                        <div className="ck-skeleton ck-skeleton-text" style={{ width: '60%', marginBottom: 6 }} />
                        <div className="ck-skeleton ck-skeleton-text" style={{ width: '40%' }} />
                      </div>
                    </div>
                  ))}
                </div>
              ) : ffTimelineError ? (
                <div className="ck-alert ck-alert-re"><span>✕</span><div>Failed to load timeline. Please close and try again.</div></div>
              ) : (
                <div className="ck-timeline">
                  {ffTimeline.map((item, i) => (
                    <div key={i} className="ck-timeline-item">
                      <div className="ck-timeline-left">
                        <div className="ck-timeline-dot done">✓</div>
                        {i < ffTimeline.length - 1 && <div className="ck-timeline-line done" />}
                      </div>
                      <div className="ck-timeline-body">
                        <div className="ck-timeline-title">{labelFfStatus(item.status)}</div>
                        <div className="ck-timeline-meta">{formatFfDate(item.at)}</div>
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
