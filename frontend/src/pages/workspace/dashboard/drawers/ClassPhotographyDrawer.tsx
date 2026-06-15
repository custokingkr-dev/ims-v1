import React, { useState, useEffect, useCallback } from 'react';
import { CommandCenterDrawer } from '../components/CommandCenterDrawer';
import {
  fetchClassPhotographyPaymentStatus,
  sendPhotographyPaymentReminders,
  fetchCommandCenterMetrics,
} from '../../../../api/dashboardCommandCenterApi';
import type {
  PhotoContributionItem,
  ClassPhotographyPaymentStatusResponse,
  DashboardCommandCenterResponse,
} from '../../../../types/dashboardCommandCenter';
import { usePermissions } from '../../../../hooks/usePermissions';

interface Props {
  open: boolean;
  onClose: () => void;
  onMetricsRefresh?: (data: DashboardCommandCenterResponse) => void;
}

const CHANNELS = ['SMS', 'WhatsApp', 'Email', 'Push'];
const PAGE_SIZE = 20;

function fmt(paise: number): string {
  return '₹' + Math.round(paise / 100).toLocaleString('en-IN');
}

function StatusBadge({ status }: { status: PhotoContributionItem['status'] }) {
  const cfg = {
    PAID:    { label: 'Paid',    bg: '#e6f4ed', color: '#1a6840' },
    PARTIAL: { label: 'Partial', bg: '#fff3cd', color: '#856404' },
    PENDING: { label: 'Pending', bg: '#fde8e8', color: '#c0312b' },
  }[status] ?? { label: status, bg: '#f5f5f5', color: '#555' };
  return (
    <span style={{ fontSize: 11, fontWeight: 600, padding: '2px 7px', borderRadius: 10,
                   background: cfg.bg, color: cfg.color, whiteSpace: 'nowrap' }}>
      {cfg.label}
    </span>
  );
}

interface ConfirmModalProps {
  eventId: string;
  selected: PhotoContributionItem[];
  onClose: () => void;
  onSent: (sentCount: number, failedCount: number) => void;
}

function ConfirmReminderModal({ eventId, selected, onClose, onSent }: ConfirmModalProps) {
  const [channel, setChannel] = useState('SMS');
  const [message, setMessage] = useState(
    'Dear parent, your child\'s class photography contribution is pending. Please make the payment at the earliest.'
  );
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');

  async function handleSend() {
    setSending(true);
    setError('');
    try {
      const result = await sendPhotographyPaymentReminders(eventId, {
        studentIds: selected.map(s => s.studentId),
        channel,
        message,
      });
      onSent(result.sentCount, result.failedCount);
    } catch {
      setError('Failed to send reminders. Please try again.');
      setSending(false);
    }
  }

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.45)', zIndex: 1100,
                  display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ background: '#fff', borderRadius: 12, padding: 28, width: '100%',
                    maxWidth: 460, boxShadow: '0 8px 32px rgba(0,0,0,0.18)' }}>
        <h3 style={{ margin: '0 0 6px', fontSize: 17 }}>Send Payment Reminder</h3>
        <p style={{ margin: '0 0 16px', fontSize: 13, color: '#666' }}>
          {selected.length} student{selected.length !== 1 ? 's' : ''} selected
        </p>

        <label className="ck-field" style={{ display: 'block', marginBottom: 14 }}>
          <span style={{ fontSize: 12, fontWeight: 600, color: '#555', display: 'block', marginBottom: 4 }}>Channel</span>
          <select value={channel} onChange={e => setChannel(e.target.value)}
                  style={{ width: '100%', padding: '8px 10px', borderRadius: 8, border: '1px solid #ddd', fontSize: 14 }}>
            {CHANNELS.map(c => <option key={c} value={c}>{c}</option>)}
          </select>
        </label>

        <label className="ck-field" style={{ display: 'block', marginBottom: 14 }}>
          <span style={{ fontSize: 12, fontWeight: 600, color: '#555', display: 'block', marginBottom: 4 }}>Message</span>
          <textarea value={message} onChange={e => setMessage(e.target.value)} rows={3}
                    style={{ width: '100%', padding: '8px 10px', borderRadius: 8, border: '1px solid #ddd',
                             fontSize: 13, resize: 'vertical', boxSizing: 'border-box' }} />
        </label>

        <div style={{ background: '#f9f9f9', borderRadius: 8, padding: '10px 14px', marginBottom: 16 }}>
          <div style={{ fontSize: 11, fontWeight: 600, color: '#888', marginBottom: 6 }}>PREVIEW</div>
          <div style={{ fontSize: 13, color: '#444' }}>{message || <em style={{ color: '#aaa' }}>No message</em>}</div>
        </div>

        {error && <div className="ck-alert" style={{ marginBottom: 12, fontSize: 13 }}>{error}</div>}

        <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
          <button className="ck-btn-ghost" onClick={onClose} disabled={sending}>Cancel</button>
          <button className="ck-btn" onClick={handleSend} disabled={sending || !message.trim()}>
            {sending ? 'Sending…' : `Send to ${selected.length}`}
          </button>
        </div>
      </div>
    </div>
  );
}

export function ClassPhotographyDrawer({ open, onClose, onMetricsRefresh }: Props) {
  const { can } = usePermissions();
  const canNotify = can('fee:collect');

  const [data, setData] = useState<ClassPhotographyPaymentStatusResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [page, setPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState('');
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [showConfirm, setShowConfirm] = useState(false);
  const [toast, setToast] = useState('');

  const load = useCallback(async (pg: number, sf: string) => {
    setLoading(true);
    setError('');
    try {
      const res = await fetchClassPhotographyPaymentStatus({
        status: sf || undefined,
        page: pg,
        size: PAGE_SIZE,
      });
      setData(res);
    } catch {
      setError('Failed to load payment status.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (open) { setPage(0); setSelected(new Set()); load(0, statusFilter); }
  }, [open]);

  function handleFilterChange(sf: string) {
    setStatusFilter(sf);
    setPage(0);
    setSelected(new Set());
    load(0, sf);
  }

  function handlePageChange(pg: number) {
    setPage(pg);
    setSelected(new Set());
    load(pg, statusFilter);
  }

  function toggleRow(id: number) {
    setSelected(prev => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  }

  function toggleAll() {
    if (!data) return;
    const pendingIds = data.students.filter(s => s.status !== 'PAID').map(s => s.studentId);
    if (selected.size === pendingIds.length) {
      setSelected(new Set());
    } else {
      setSelected(new Set(pendingIds));
    }
  }

  async function handleReminderSent(sentCount: number, failedCount: number) {
    setShowConfirm(false);
    setSelected(new Set());
    const msg = failedCount > 0
      ? `${sentCount} reminder${sentCount !== 1 ? 's' : ''} sent, ${failedCount} failed.`
      : `${sentCount} reminder${sentCount !== 1 ? 's' : ''} sent successfully.`;
    setToast(msg);
    setTimeout(() => setToast(''), 4000);

    await load(page, statusFilter);
    try {
      const fresh = await fetchCommandCenterMetrics();
      onMetricsRefresh?.(fresh);
    } catch { /* best-effort */ }
  }

  const selectedItems = data?.students.filter(s => selected.has(s.studentId)) ?? [];
  const pendingIds = data?.students.filter(s => s.status !== 'PAID').map(s => s.studentId) ?? [];
  const allPendingSelected = pendingIds.length > 0 && pendingIds.every(id => selected.has(id));
  const totalPages = data ? Math.ceil(data.totalElements / PAGE_SIZE) : 0;

  return (
    <>
      <CommandCenterDrawer
        open={open}
        onClose={onClose}
        title="Class Photography — Payment Status"
        subtitle={data?.title ?? undefined}
      >
        {/* Event summary */}
        {data && data.eventId && (
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 20 }}>
            {[
              { label: 'Event Date', value: data.eventDate ?? '—' },
              { label: 'Total Budget', value: fmt(data.totalBudget) },
              { label: 'School Contribution', value: fmt(data.schoolContribution) },
              { label: 'Student Target', value: fmt(data.studentContributionTarget) },
              { label: 'Collected', value: fmt(data.collectedAmount), hi: true },
              { label: 'Pending', value: fmt(data.pendingAmount), warn: true },
            ].map(m => (
              <div key={m.label} style={{ flex: '1 1 130px', background: '#f8f9fa', borderRadius: 10,
                                          padding: '10px 14px', minWidth: 120 }}>
                <div style={{ fontSize: 11, color: '#888', fontWeight: 600 }}>{m.label}</div>
                <div style={{ fontSize: 18, fontWeight: 700,
                              color: m.hi ? '#1a6840' : m.warn ? '#c0312b' : '#1a1a1a', marginTop: 2 }}>
                  {m.value}
                </div>
              </div>
            ))}
          </div>
        )}

        {data && !data.eventId && !loading && (
          <div style={{ textAlign: 'center', padding: '40px 0', color: '#888' }}>
            No active class photography event found.
          </div>
        )}

        {/* Filters */}
        {data && data.eventId && (
          <div style={{ display: 'flex', gap: 10, marginBottom: 16, flexWrap: 'wrap' }}>
            <select value={statusFilter} onChange={e => handleFilterChange(e.target.value)}
                    style={{ padding: '7px 12px', borderRadius: 8, border: '1px solid #ddd', fontSize: 13 }}>
              <option value="">All statuses</option>
              <option value="PENDING">Pending</option>
              <option value="PARTIAL">Partial</option>
              <option value="PAID">Paid</option>
            </select>

            {canNotify && selected.size > 0 && (
              <button className="ck-btn" style={{ fontSize: 13 }}
                      onClick={() => setShowConfirm(true)}>
                Send Bulk Reminder ({selected.size})
              </button>
            )}
          </div>
        )}

        {/* Loading / error */}
        {loading && <div style={{ padding: 20, textAlign: 'center', color: '#888' }}>Loading…</div>}
        {error && <div className="ck-alert" style={{ marginBottom: 12 }}>{error}</div>}
        {toast && (
          <div style={{ background: '#e6f4ed', color: '#1a6840', borderRadius: 8, padding: '10px 14px',
                        marginBottom: 12, fontSize: 13, fontWeight: 600 }}>{toast}</div>
        )}

        {/* Table */}
        {!loading && data && data.eventId && data.students.length > 0 && (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <thead>
                <tr style={{ background: '#f5f5f5' }}>
                  {canNotify && (
                    <th style={{ padding: '8px 10px', textAlign: 'left', width: 36 }}>
                      <input type="checkbox" checked={allPendingSelected} onChange={toggleAll} />
                    </th>
                  )}
                  <th style={{ padding: '8px 10px', textAlign: 'left', fontWeight: 600 }}>Student</th>
                  <th style={{ padding: '8px 10px', textAlign: 'left', fontWeight: 600 }}>Class</th>
                  <th style={{ padding: '8px 10px', textAlign: 'left', fontWeight: 600 }}>Parent Contact</th>
                  <th style={{ padding: '8px 10px', textAlign: 'right', fontWeight: 600 }}>Expected</th>
                  <th style={{ padding: '8px 10px', textAlign: 'right', fontWeight: 600 }}>Paid</th>
                  <th style={{ padding: '8px 10px', textAlign: 'right', fontWeight: 600 }}>Pending</th>
                  <th style={{ padding: '8px 10px', textAlign: 'center', fontWeight: 600 }}>Status</th>
                  <th style={{ padding: '8px 10px', textAlign: 'left', fontWeight: 600 }}>Last Reminder</th>
                  {canNotify && <th style={{ padding: '8px 10px' }}></th>}
                </tr>
              </thead>
              <tbody>
                {data.students.map(s => (
                  <tr key={s.studentId} style={{ borderBottom: '1px solid #f0f0f0' }}>
                    {canNotify && (
                      <td style={{ padding: '8px 10px' }}>
                        {s.status !== 'PAID' && (
                          <input type="checkbox" checked={selected.has(s.studentId)}
                                 onChange={() => toggleRow(s.studentId)} />
                        )}
                      </td>
                    )}
                    <td style={{ padding: '8px 10px' }}>
                      <div style={{ fontWeight: 600 }}>{s.studentName}</div>
                      <div style={{ fontSize: 11, color: '#888' }}>{s.admissionNo}</div>
                    </td>
                    <td style={{ padding: '8px 10px', color: '#555' }}>{s.className} – {s.sectionName}</td>
                    <td style={{ padding: '8px 10px', color: '#555' }}>{s.parentPhone ?? '—'}</td>
                    <td style={{ padding: '8px 10px', textAlign: 'right', color: '#333' }}>{fmt(s.expectedAmount)}</td>
                    <td style={{ padding: '8px 10px', textAlign: 'right', color: '#1a6840' }}>{fmt(s.paidAmount)}</td>
                    <td style={{ padding: '8px 10px', textAlign: 'right',
                                 color: s.pendingAmount > 0 ? '#c0312b' : '#1a6840',
                                 fontWeight: s.pendingAmount > 0 ? 600 : 400 }}>
                      {fmt(s.pendingAmount)}
                    </td>
                    <td style={{ padding: '8px 10px', textAlign: 'center' }}>
                      <StatusBadge status={s.status} />
                    </td>
                    <td style={{ padding: '8px 10px', fontSize: 12, color: '#888' }}>
                      {s.lastReminderSentAt
                        ? new Date(s.lastReminderSentAt).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })
                        : '—'}
                    </td>
                    {canNotify && (
                      <td style={{ padding: '8px 10px' }}>
                        {s.status !== 'PAID' && (
                          <button className="ck-btn-ghost" style={{ fontSize: 12, padding: '4px 10px' }}
                                  onClick={() => { setSelected(new Set([s.studentId])); setShowConfirm(true); }}>
                            Remind
                          </button>
                        )}
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Empty state */}
        {!loading && data && data.eventId && data.students.length === 0 && (
          <div style={{ textAlign: 'center', padding: '40px 0', color: '#888' }}>
            No students match the selected filter.
          </div>
        )}

        {/* Pagination */}
        {totalPages > 1 && (
          <div style={{ display: 'flex', justifyContent: 'center', gap: 8, marginTop: 16 }}>
            <button className="ck-btn-ghost" disabled={page === 0}
                    onClick={() => handlePageChange(page - 1)}>← Prev</button>
            <span style={{ padding: '6px 12px', fontSize: 13, color: '#555' }}>
              Page {page + 1} of {totalPages}
            </span>
            <button className="ck-btn-ghost" disabled={page >= totalPages - 1}
                    onClick={() => handlePageChange(page + 1)}>Next →</button>
          </div>
        )}
      </CommandCenterDrawer>

      {showConfirm && data?.eventId && (
        <ConfirmReminderModal
          eventId={data.eventId}
          selected={selectedItems}
          onClose={() => { setShowConfirm(false); }}
          onSent={handleReminderSent}
        />
      )}
    </>
  );
}
