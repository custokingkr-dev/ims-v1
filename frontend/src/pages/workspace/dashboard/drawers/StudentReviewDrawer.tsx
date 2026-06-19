import React, { useState, useEffect, useCallback } from 'react';
import { CommandCenterDrawer } from '../components/CommandCenterDrawer';
import {
  fetchIdCardReviewStatus,
  initiateIdCardReview,
  fetchFullNameVerificationStatus,
  initiateFullNameVerification,
  fetchCampaignItems,
  updateReviewItem,
  verifyFullName,
} from '../../../../api/dashboardCommandCenterApi';
import type {
  IdCardReviewStatusResponse,
  FullNameVerificationStatusResponse,
  ReviewItemDetail,
} from '../../../../types/dashboardCommandCenter';
import { usePermissions } from '../../../../hooks/usePermissions';

interface Props {
  open: boolean;
  onClose: () => void;
  onMetricsRefresh?: () => void;
}

type TabKey = 'id-card' | 'full-name';

const PAGE_SIZE = 20;

// ── Status badge ──────────────────────────────────────────────────────────────

function ItemStatusBadge({ status }: { status: ReviewItemDetail['status'] }) {
  const cfg: Record<string, { label: string; bg: string; color: string }> = {
    COMPLETED:       { label: 'Done',        bg: '#e6f4ed', color: '#1a6840' },
    NEEDS_CORRECTION:{ label: 'Needs Fix',   bg: '#fff3cd', color: '#856404' },
    PENDING:         { label: 'Pending',     bg: '#fde8e8', color: '#c0312b' },
  };
  const c = cfg[status] ?? { label: status, bg: '#f5f5f5', color: '#555' };
  return (
    <span style={{ fontSize: 11, fontWeight: 600, padding: '2px 7px',
                   borderRadius: 10, background: c.bg, color: c.color, whiteSpace: 'nowrap' }}>
      {c.label}
    </span>
  );
}

// ── Progress bar ──────────────────────────────────────────────────────────────

function ProgressBar({ pct }: { pct: number }) {
  return (
    <div style={{ background: '#e8eaf6', borderRadius: 4, height: 8, overflow: 'hidden' }}>
      <div style={{ width: `${Math.min(100, pct)}%`, height: '100%',
                    background: 'linear-gradient(90deg, #5c6bc0, #3949ab)', transition: 'width .3s' }} />
    </div>
  );
}

// ── ID Card Tab ───────────────────────────────────────────────────────────────

function IdCardTab({ canWrite }: { canWrite: boolean }) {
  const [status, setStatus] = useState<IdCardReviewStatusResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [initiating, setInitiating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [items, setItems] = useState<ReviewItemDetail[]>([]);
  const [page, setPage] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [filter, setFilter] = useState<string>('');
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const loadStatus = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const s = await fetchIdCardReviewStatus();
      setStatus(s);
    } catch {
      setError('Failed to load ID card review status.');
    } finally {
      setLoading(false);
    }
  }, []);

  const loadItems = useCallback(async (campaignId: string, pg: number, statusFilter: string) => {
    try {
      const r = await fetchCampaignItems(campaignId, {
        page: pg, size: PAGE_SIZE, status: statusFilter || undefined,
      });
      setItems(r.content);
      setTotalElements(r.totalElements);
    } catch {
      // silent — items are supplemental
    }
  }, []);

  useEffect(() => { loadStatus(); }, [loadStatus]);

  useEffect(() => {
    if (status?.campaignId) {
      loadItems(status.campaignId, page, filter);
    }
  }, [status?.campaignId, page, filter, loadItems]);

  const handleInitiate = async () => {
    setInitiating(true);
    setError(null);
    try {
      const s = await initiateIdCardReview({ dueDate: null });
      setStatus(s);
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })
        ?.response?.data?.message;
      setError(msg ?? 'Failed to initiate review campaign.');
    } finally {
      setInitiating(false);
    }
  };

  const handleChecklistUpdate = async (item: ReviewItemDetail, field: keyof ReviewItemDetail) => {
    const boolFields: (keyof ReviewItemDetail)[] = [
      'verifiedPhoto', 'verifiedFullName', 'verifiedAdmissionNo', 'verifiedClassSection',
      'verifiedRollNo', 'verifiedFatherName', 'verifiedFatherContact', 'verifiedAddress', 'verifiedBloodGroup',
    ];
    if (!boolFields.includes(field)) return;
    const patch: Record<string, boolean> = { [field]: !item[field] };
    try {
      const updated = await updateReviewItem(item.itemId, patch);
      setItems(prev => prev.map(i => i.itemId === updated.itemId ? updated : i));
    } catch {
      // silent optimistic failure
    }
  };

  if (loading) return <div style={{ padding: 24, color: '#888', textAlign: 'center' }}>Loading…</div>;
  if (error)   return <div style={{ padding: 24, color: '#c0312b' }}>{error}</div>;

  if (!status?.campaignId) {
    return (
      <div style={{ padding: 24 }}>
        <p style={{ marginBottom: 16, color: '#555' }}>
          No active ID Card Details review campaign. Initiate one to verify student card data across all enrolled students.
        </p>
        {canWrite && (
          <button onClick={handleInitiate} disabled={initiating}
            style={{ padding: '10px 20px', background: '#3949ab', color: '#fff',
                     border: 'none', borderRadius: 6, cursor: 'pointer', fontWeight: 600 }}>
            {initiating ? 'Starting…' : 'Initiate ID Card Review'}
          </button>
        )}
      </div>
    );
  }

  const pct = status.completionPercent ?? 0;
  const CHECKLIST: Array<{ key: keyof ReviewItemDetail; label: string }> = [
    { key: 'verifiedPhoto',         label: 'Photo' },
    { key: 'verifiedFullName',      label: 'Full Name' },
    { key: 'verifiedAdmissionNo',   label: 'Admission No.' },
    { key: 'verifiedClassSection',  label: 'Class / Section' },
    { key: 'verifiedRollNo',        label: 'Roll No.' },
    { key: 'verifiedFatherName',    label: "Father's Name" },
    { key: 'verifiedFatherContact', label: "Father's Contact" },
    { key: 'verifiedAddress',       label: 'Address' },
    { key: 'verifiedBloodGroup',    label: 'Blood Group' },
  ];

  return (
    <div>
      {/* Summary */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12, marginBottom: 20 }}>
        {[
          { label: 'Total', value: status.totalStudents },
          { label: 'Completed', value: status.completed },
          { label: 'Needs Fix', value: status.needsCorrection },
        ].map(m => (
          <div key={m.label} style={{ background: '#f5f6ff', borderRadius: 8, padding: '12px 16px' }}>
            <div style={{ fontSize: 22, fontWeight: 700, color: '#3949ab' }}>{m.value}</div>
            <div style={{ fontSize: 12, color: '#888', marginTop: 2 }}>{m.label}</div>
          </div>
        ))}
      </div>

      <div style={{ marginBottom: 16 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: '#555', marginBottom: 4 }}>
          <span>Completion</span><span>{pct.toFixed(1)}%</span>
        </div>
        <ProgressBar pct={pct} />
      </div>

      {/* Filter */}
      <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
        {['', 'PENDING', 'NEEDS_CORRECTION', 'COMPLETED'].map(s => (
          <button key={s} onClick={() => { setFilter(s); setPage(0); }}
            style={{ padding: '5px 12px', borderRadius: 20, border: '1px solid #c5cae9',
                     background: filter === s ? '#3949ab' : '#fff',
                     color: filter === s ? '#fff' : '#3949ab',
                     cursor: 'pointer', fontSize: 12, fontWeight: 600 }}>
            {s || 'All'}
          </button>
        ))}
      </div>

      {/* Items list */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {items.map(item => (
          <div key={item.itemId} style={{ border: '1px solid #e8eaf6', borderRadius: 8, overflow: 'hidden' }}>
            <div onClick={() => setExpandedId(expandedId === item.itemId ? null : item.itemId)}
              style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                       padding: '10px 14px', cursor: 'pointer', background: '#fafbff' }}>
              <div>
                <div style={{ fontWeight: 600, fontSize: 14 }}>{item.studentName}</div>
                <div style={{ fontSize: 12, color: '#888' }}>{item.admissionNo} · {item.className} {item.sectionName}</div>
              </div>
              <ItemStatusBadge status={item.status} />
            </div>

            {expandedId === item.itemId && (
              <div style={{ padding: '12px 14px', background: '#fff', borderTop: '1px solid #e8eaf6' }}>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 8 }}>
                  {CHECKLIST.map(c => (
                    <label key={c.key as string}
                      style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13,
                               cursor: canWrite ? 'pointer' : 'default' }}>
                      <input type="checkbox" checked={Boolean(item[c.key])}
                        disabled={!canWrite}
                        onChange={() => handleChecklistUpdate(item, c.key)}
                        style={{ width: 14, height: 14 }} />
                      {c.label}
                    </label>
                  ))}
                </div>
                {item.correctionNotes && (
                  <div style={{ marginTop: 8, fontSize: 12, color: '#856404',
                                background: '#fff3cd', padding: '6px 10px', borderRadius: 4 }}>
                    Note: {item.correctionNotes}
                  </div>
                )}
              </div>
            )}
          </div>
        ))}
      </div>

      {/* Pagination */}
      {totalElements > PAGE_SIZE && (
        <div style={{ display: 'flex', justifyContent: 'center', gap: 8, marginTop: 16 }}>
          <button disabled={page === 0} onClick={() => setPage(p => p - 1)}
            style={{ padding: '5px 14px', border: '1px solid #c5cae9', borderRadius: 4,
                     cursor: page === 0 ? 'default' : 'pointer', opacity: page === 0 ? 0.4 : 1 }}>
            Prev
          </button>
          <span style={{ lineHeight: '30px', fontSize: 13, color: '#555' }}>
            Page {page + 1} / {Math.ceil(totalElements / PAGE_SIZE)}
          </span>
          <button disabled={page >= Math.ceil(totalElements / PAGE_SIZE) - 1}
            onClick={() => setPage(p => p + 1)}
            style={{ padding: '5px 14px', border: '1px solid #c5cae9', borderRadius: 4,
                     cursor: page >= Math.ceil(totalElements / PAGE_SIZE) - 1 ? 'default' : 'pointer',
                     opacity: page >= Math.ceil(totalElements / PAGE_SIZE) - 1 ? 0.4 : 1 }}>
            Next
          </button>
        </div>
      )}
    </div>
  );
}

// ── Full Name Tab ─────────────────────────────────────────────────────────────

function FullNameTab({ canWrite }: { canWrite: boolean }) {
  const [status, setStatus] = useState<FullNameVerificationStatusResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [initiating, setInitiating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [items, setItems] = useState<ReviewItemDetail[]>([]);
  const [page, setPage] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [filter, setFilter] = useState('');
  const [verifyingId, setVerifyingId] = useState<string | null>(null);
  const [suggestName, setSuggestName] = useState('');
  const [corrNotes, setCorrNotes] = useState('');
  const [verifierType, setVerifierType] = useState<'TEACHER' | 'PARENT' | 'BOTH'>('TEACHER');

  const loadStatus = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const s = await fetchFullNameVerificationStatus();
      setStatus(s);
    } catch {
      setError('Failed to load full name verification status.');
    } finally {
      setLoading(false);
    }
  }, []);

  const loadItems = useCallback(async (campaignId: string, pg: number, statusFilter: string) => {
    try {
      const r = await fetchCampaignItems(campaignId, {
        page: pg, size: PAGE_SIZE, status: statusFilter || undefined,
      });
      setItems(r.content);
      setTotalElements(r.totalElements);
    } catch {
      // silent
    }
  }, []);

  useEffect(() => { loadStatus(); }, [loadStatus]);
  useEffect(() => {
    if (status?.campaignId) loadItems(status.campaignId, page, filter);
  }, [status?.campaignId, page, filter, loadItems]);

  const handleInitiate = async () => {
    setInitiating(true);
    setError(null);
    try {
      const s = await initiateFullNameVerification({ verifier: verifierType, dueDate: null });
      setStatus(s);
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })
        ?.response?.data?.message;
      setError(msg ?? 'Failed to initiate campaign.');
    } finally {
      setInitiating(false);
    }
  };

  const handleConfirm = async (itemId: string, confirmed: boolean) => {
    try {
      const updated = await verifyFullName(itemId, {
        confirmed,
        suggestedFullName: confirmed ? null : (suggestName || null),
        correctionNotes: confirmed ? null : (corrNotes || null),
      });
      setItems(prev => prev.map(i => i.itemId === updated.itemId ? updated : i));
      if (!confirmed) { setSuggestName(''); setCorrNotes(''); }
      setVerifyingId(null);
    } catch {
      setError('Failed to save verification.');
    }
  };

  if (loading) return <div style={{ padding: 24, color: '#888', textAlign: 'center' }}>Loading…</div>;
  if (error)   return <div style={{ padding: 24, color: '#c0312b' }}>{error}</div>;

  if (!status?.campaignId) {
    return (
      <div style={{ padding: 24 }}>
        <p style={{ marginBottom: 16, color: '#555' }}>
          No active Full Name Verification campaign. This ensures student names match official records
          (Aadhaar, Birth Certificate, etc.).
        </p>
        {canWrite && (
          <div>
            <div style={{ marginBottom: 12 }}>
              <label style={{ fontSize: 13, fontWeight: 600, color: '#333', marginBottom: 6, display: 'block' }}>
                Who verifies?
              </label>
              <div style={{ display: 'flex', gap: 10 }}>
                {(['TEACHER', 'PARENT', 'BOTH'] as const).map(v => (
                  <label key={v} style={{ display: 'flex', alignItems: 'center', gap: 5,
                                          cursor: 'pointer', fontSize: 13 }}>
                    <input type="radio" name="verifier" value={v}
                      checked={verifierType === v}
                      onChange={() => setVerifierType(v)} />
                    {v.charAt(0) + v.slice(1).toLowerCase()}
                  </label>
                ))}
              </div>
            </div>
            <button onClick={handleInitiate} disabled={initiating}
              style={{ padding: '10px 20px', background: '#3949ab', color: '#fff',
                       border: 'none', borderRadius: 6, cursor: 'pointer', fontWeight: 600 }}>
              {initiating ? 'Starting…' : 'Initiate Full Name Verification'}
            </button>
          </div>
        )}
      </div>
    );
  }

  const pct = status.completionPercent ?? 0;

  return (
    <div>
      {/* Summary */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12, marginBottom: 20 }}>
        {[
          { label: 'Total',     value: status.totalStudents },
          { label: 'Confirmed', value: status.confirmed },
          { label: 'Needs Fix', value: status.correctionRequested },
        ].map(m => (
          <div key={m.label} style={{ background: '#f5f6ff', borderRadius: 8, padding: '12px 16px' }}>
            <div style={{ fontSize: 22, fontWeight: 700, color: '#3949ab' }}>{m.value}</div>
            <div style={{ fontSize: 12, color: '#888', marginTop: 2 }}>{m.label}</div>
          </div>
        ))}
      </div>

      <div style={{ marginBottom: 16 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: '#555', marginBottom: 4 }}>
          <span>Confirmation rate</span><span>{pct.toFixed(1)}%</span>
        </div>
        <ProgressBar pct={pct} />
      </div>

      {/* Filter */}
      <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
        {['', 'PENDING', 'NEEDS_CORRECTION', 'COMPLETED'].map(s => (
          <button key={s} onClick={() => { setFilter(s); setPage(0); }}
            style={{ padding: '5px 12px', borderRadius: 20, border: '1px solid #c5cae9',
                     background: filter === s ? '#3949ab' : '#fff',
                     color: filter === s ? '#fff' : '#3949ab',
                     cursor: 'pointer', fontSize: 12, fontWeight: 600 }}>
            {s || 'All'}
          </button>
        ))}
      </div>

      {/* Items */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {items.map(item => (
          <div key={item.itemId} style={{ border: '1px solid #e8eaf6', borderRadius: 8, overflow: 'hidden' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                          padding: '10px 14px', background: '#fafbff' }}>
              <div>
                <div style={{ fontWeight: 600, fontSize: 14 }}>
                  {item.currentFullName || item.studentName}
                </div>
                <div style={{ fontSize: 12, color: '#888' }}>
                  {item.admissionNo} · {item.className} {item.sectionName}
                </div>
                {item.suggestedFullName && (
                  <div style={{ fontSize: 12, color: '#856404', marginTop: 2 }}>
                    Suggested: <strong>{item.suggestedFullName}</strong>
                  </div>
                )}
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <ItemStatusBadge status={item.status} />
                {canWrite && item.status === 'PENDING' && verifyingId !== item.itemId && (
                  <button onClick={() => setVerifyingId(item.itemId)}
                    style={{ padding: '4px 10px', fontSize: 12, border: '1px solid #3949ab',
                             borderRadius: 4, color: '#3949ab', background: '#fff', cursor: 'pointer' }}>
                    Verify
                  </button>
                )}
              </div>
            </div>

            {verifyingId === item.itemId && (
              <div style={{ padding: '12px 14px', background: '#fff', borderTop: '1px solid #e8eaf6' }}>
                <div style={{ marginBottom: 10 }}>
                  <label style={{ fontSize: 12, fontWeight: 600, display: 'block', marginBottom: 4 }}>
                    Suggested correct name (if different)
                  </label>
                  <input value={suggestName} onChange={e => setSuggestName(e.target.value)}
                    placeholder="Leave blank if current name is correct"
                    style={{ width: '100%', padding: '7px 10px', border: '1px solid #c5cae9',
                             borderRadius: 4, fontSize: 13 }} />
                </div>
                <div style={{ marginBottom: 12 }}>
                  <label style={{ fontSize: 12, fontWeight: 600, display: 'block', marginBottom: 4 }}>
                    Notes (optional)
                  </label>
                  <textarea value={corrNotes} onChange={e => setCorrNotes(e.target.value)}
                    rows={2} style={{ width: '100%', padding: '7px 10px', border: '1px solid #c5cae9',
                                     borderRadius: 4, fontSize: 13, resize: 'vertical' }} />
                </div>
                <div style={{ display: 'flex', gap: 8 }}>
                  <button onClick={() => handleConfirm(item.itemId, true)}
                    style={{ flex: 1, padding: '8px', background: '#1a6840', color: '#fff',
                             border: 'none', borderRadius: 4, cursor: 'pointer', fontWeight: 600, fontSize: 13 }}>
                    Confirm Name Correct
                  </button>
                  <button onClick={() => handleConfirm(item.itemId, false)}
                    disabled={!suggestName.trim()}
                    style={{ flex: 1, padding: '8px', background: suggestName.trim() ? '#856404' : '#ccc',
                             color: '#fff', border: 'none', borderRadius: 4,
                             cursor: suggestName.trim() ? 'pointer' : 'default',
                             fontWeight: 600, fontSize: 13 }}>
                    Request Correction
                  </button>
                  <button onClick={() => { setVerifyingId(null); setSuggestName(''); setCorrNotes(''); }}
                    style={{ padding: '8px 14px', border: '1px solid #c5cae9', borderRadius: 4,
                             cursor: 'pointer', fontSize: 13 }}>
                    Cancel
                  </button>
                </div>
              </div>
            )}
          </div>
        ))}
      </div>

      {totalElements > PAGE_SIZE && (
        <div style={{ display: 'flex', justifyContent: 'center', gap: 8, marginTop: 16 }}>
          <button disabled={page === 0} onClick={() => setPage(p => p - 1)}
            style={{ padding: '5px 14px', border: '1px solid #c5cae9', borderRadius: 4,
                     cursor: page === 0 ? 'default' : 'pointer', opacity: page === 0 ? 0.4 : 1 }}>
            Prev
          </button>
          <span style={{ lineHeight: '30px', fontSize: 13, color: '#555' }}>
            Page {page + 1} / {Math.ceil(totalElements / PAGE_SIZE)}
          </span>
          <button disabled={page >= Math.ceil(totalElements / PAGE_SIZE) - 1}
            onClick={() => setPage(p => p + 1)}
            style={{ padding: '5px 14px', border: '1px solid #c5cae9', borderRadius: 4,
                     cursor: page >= Math.ceil(totalElements / PAGE_SIZE) - 1 ? 'default' : 'pointer',
                     opacity: page >= Math.ceil(totalElements / PAGE_SIZE) - 1 ? 0.4 : 1 }}>
            Next
          </button>
        </div>
      )}
    </div>
  );
}

// ── Main drawer ───────────────────────────────────────────────────────────────

export function StudentReviewDrawer({ open, onClose, onMetricsRefresh }: Props) {
  const { can } = usePermissions();
  const canWrite = can('student:update');
  const [tab, setTab] = useState<TabKey>('id-card');

  // Refresh dashboard metrics when drawer closes if data may have changed
  useEffect(() => {
    if (!open && onMetricsRefresh) {
      onMetricsRefresh();
    }
  }, [open, onMetricsRefresh]);

  const tabs: Array<{ key: TabKey; label: string }> = [
    { key: 'id-card',    label: 'ID Card Details' },
    { key: 'full-name',  label: 'Full Name Verification' },
  ];

  return (
    <CommandCenterDrawer
      open={open}
      onClose={onClose}
      title="Student Lifecycle Review"
    >
      {/* Tab bar */}
      <div style={{ display: 'flex', gap: 4, marginBottom: 20, borderBottom: '2px solid #e8eaf6',
                    paddingBottom: 0 }}>
        {tabs.map(t => (
          <button key={t.key} onClick={() => setTab(t.key)}
            style={{ padding: '8px 16px', border: 'none', borderRadius: '6px 6px 0 0',
                     background: tab === t.key ? '#3949ab' : 'transparent',
                     color: tab === t.key ? '#fff' : '#5c6bc0',
                     fontWeight: 600, fontSize: 13, cursor: 'pointer',
                     borderBottom: tab === t.key ? '2px solid #3949ab' : 'none',
                     marginBottom: -2 }}>
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'id-card'   && <IdCardTab canWrite={canWrite} />}
      {tab === 'full-name' && <FullNameTab canWrite={canWrite} />}
    </CommandCenterDrawer>
  );
}
