import React, { useState, useEffect, useCallback } from 'react';
import { CommandCenterDrawer } from '../components/CommandCenterDrawer';
import { fetchFeeDefaulters, sendFeeReminders, fetchCommandCenterMetrics } from '../../../../api/dashboardCommandCenterApi';
import type {
  FeeDefaulterItem,
  FeeDefaulterListResponse,
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

function formatRupees(paise: number): string {
  return '₹' + (paise / 100).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function ReminderStatusBadge({ status }: { status: FeeDefaulterItem['reminderStatus'] }) {
  const cfg = {
    NOT_SENT: { label: 'Not sent', bg: '#f5f5f5', color: '#555' },
    SENT:     { label: 'Sent',     bg: '#e6f4ed', color: '#1a6840' },
    PENDING:  { label: 'Pending',  bg: '#fff3cd', color: '#856404' },
    FAILED:   { label: 'Failed',   bg: '#fde8e8', color: '#c0312b' },
  }[status];
  return (
    <span style={{ fontSize: 11, fontWeight: 600, padding: '2px 7px', borderRadius: 10,
                   background: cfg.bg, color: cfg.color, whiteSpace: 'nowrap' }}>
      {cfg.label}
    </span>
  );
}

interface ConfirmModalProps {
  selected: FeeDefaulterItem[];
  onClose: () => void;
  onSent: (sentCount: number, failedCount: number) => void;
}

function ConfirmReminderModal({ selected, onClose, onSent }: ConfirmModalProps) {
  const [channel, setChannel] = useState('SMS');
  const [message, setMessage] = useState(
    'Dear parent, your child\'s fee payment is overdue. Please clear the dues at the earliest.'
  );
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');

  async function handleSend() {
    setSending(true);
    setError('');
    try {
      const result = await sendFeeReminders({
        studentIds: selected.map(s => s.studentId),
        channel,
        message,
        context: 'FEE_OVERDUE',
      });
      onSent(result.sentCount, result.failedCount);
      onClose();
    } catch {
      setError('Failed to send reminders. Please try again.');
    } finally {
      setSending(false);
    }
  }

  return (
    <div style={{
      position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)',
      zIndex: 500, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16,
    }}>
      <div style={{
        background: 'var(--surface)', borderRadius: 10, padding: 24,
        width: 'min(100%, 480px)', boxShadow: '0 8px 40px rgba(0,0,0,0.2)',
      }}>
        <h3 style={{ margin: '0 0 4px', fontSize: 15, fontWeight: 700 }}>Send Fee Reminder</h3>
        <p style={{ margin: '0 0 16px', fontSize: 12, color: 'var(--text-muted)' }}>
          {selected.length} student{selected.length !== 1 ? 's' : ''} selected
        </p>

        {error && (
          <div className="ck-alert ck-alert-error" style={{ marginBottom: 12, fontSize: 12 }}>{error}</div>
        )}

        <div className="ck-field" style={{ marginBottom: 12 }}>
          <label htmlFor="rm-channel">Channel</label>
          <select id="rm-channel" value={channel} onChange={e => setChannel(e.target.value)}>
            {CHANNELS.map(c => <option key={c} value={c}>{c}</option>)}
          </select>
        </div>

        <div className="ck-field" style={{ marginBottom: 16 }}>
          <label htmlFor="rm-message">Message</label>
          <textarea
            id="rm-message" rows={4}
            value={message} onChange={e => setMessage(e.target.value)}
          />
        </div>

        <div style={{ background: 'var(--surface-2)', borderRadius: 6, padding: '10px 12px', marginBottom: 16, fontSize: 12 }}>
          <strong>Preview:</strong><br />
          <span style={{ color: 'var(--text-muted)' }}>{message}</span>
        </div>

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <button className="ck-btn ck-btn-ghost" onClick={onClose} disabled={sending}>Cancel</button>
          <button className="ck-btn ck-btn-g" onClick={handleSend} disabled={sending || !message.trim()}>
            {sending ? 'Sending…' : `Send to ${selected.length}`}
          </button>
        </div>
      </div>
    </div>
  );
}

export function FeeDefaultersDrawer({ open, onClose, onMetricsRefresh }: Props) {
  const { can } = usePermissions();
  const [data, setData] = useState<FeeDefaulterListResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [page, setPage] = useState(0);

  // Filters
  const [classId, setClassId] = useState('');
  const [sectionId, setSectionId] = useState('');
  const [reminderStatusFilter, setReminderStatusFilter] = useState('');

  // Selection
  const [selected, setSelected] = useState<Set<number>>(new Set());

  // Modal
  const [confirmItems, setConfirmItems] = useState<FeeDefaulterItem[] | null>(null);

  // Toast
  const [toast, setToast] = useState('');

  const load = useCallback(async () => {
    if (!open) return;
    setLoading(true);
    setError('');
    try {
      const result = await fetchFeeDefaulters({
        classId: classId || undefined,
        sectionId: sectionId || undefined,
        reminderStatus: reminderStatusFilter || undefined,
        page,
        size: PAGE_SIZE,
      });
      setData(result);
    } catch {
      setError('Could not load fee defaulters. Please try again.');
    } finally {
      setLoading(false);
    }
  }, [open, classId, sectionId, reminderStatusFilter, page]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => { if (!open) { setSelected(new Set()); setPage(0); } }, [open]);

  function toggleSelect(id: number) {
    setSelected(prev => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  }

  function toggleAll() {
    if (!data) return;
    const allIds = data.items.map(i => i.studentId);
    setSelected(prev => prev.size === allIds.length ? new Set() : new Set(allIds));
  }

  function handleSendOne(item: FeeDefaulterItem) {
    setConfirmItems([item]);
  }

  function handleBulkSend() {
    if (!data || selected.size === 0) return;
    setConfirmItems(data.items.filter(i => selected.has(i.studentId)));
  }

  async function handleAfterSent(sentCount: number, failedCount: number) {
    setSelected(new Set());
    setToast(`Sent ${sentCount} reminder${sentCount !== 1 ? 's' : ''}${failedCount > 0 ? `, ${failedCount} failed` : ''}`);
    setTimeout(() => setToast(''), 3500);
    await load();
    // Refresh dashboard metrics
    try {
      const metrics = await fetchCommandCenterMetrics();
      onMetricsRefresh?.(metrics);
    } catch { /* non-critical */ }
  }

  const canNotify = can('fee:collect');
  const items = data?.items ?? [];
  const totalPages = data ? Math.ceil(data.totalElements / PAGE_SIZE) : 0;

  return (
    <>
      <CommandCenterDrawer
        title="Fee Defaulters"
        subtitle={data ? `${data.totalDefaulters} students · ${formatRupees(data.totalOverdueAmount)} overdue` : 'Loading…'}
        open={open}
        onClose={onClose}
        footer={canNotify && selected.size > 0 ? (
          <>
            <span style={{ fontSize: 12, color: 'var(--text-muted)', marginRight: 'auto' }}>
              {selected.size} selected
            </span>
            <button className="ck-btn ck-btn-ghost" onClick={() => setSelected(new Set())}>Clear</button>
            <button className="ck-btn ck-btn-g" onClick={handleBulkSend}>
              Send Bulk Reminder ({selected.size})
            </button>
          </>
        ) : undefined}
      >
        {/* Filters */}
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 14 }}>
          <input
            placeholder="Class ID filter"
            value={classId}
            onChange={e => { setClassId(e.target.value); setPage(0); }}
            style={{ flex: '1 1 120px', fontSize: 12, padding: '4px 8px',
                     border: '1px solid var(--border)', borderRadius: 4 }}
          />
          <input
            placeholder="Section ID filter"
            value={sectionId}
            onChange={e => { setSectionId(e.target.value); setPage(0); }}
            style={{ flex: '1 1 120px', fontSize: 12, padding: '4px 8px',
                     border: '1px solid var(--border)', borderRadius: 4 }}
          />
          <select
            value={reminderStatusFilter}
            onChange={e => { setReminderStatusFilter(e.target.value); setPage(0); }}
            style={{ flex: '1 1 140px', fontSize: 12, padding: '4px 8px',
                     border: '1px solid var(--border)', borderRadius: 4 }}
          >
            <option value="">All reminder statuses</option>
            <option value="NOT_SENT">Not sent</option>
            <option value="SENT">Sent</option>
            <option value="FAILED">Failed</option>
          </select>
        </div>

        {/* Summary strip */}
        {data && (
          <div style={{ display: 'flex', gap: 16, marginBottom: 14, flexWrap: 'wrap' }}>
            <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>
              <strong style={{ color: 'var(--text)' }}>{data.totalDefaulters}</strong> defaulters
            </span>
            <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>
              <strong style={{ color: '#c0312b' }}>{formatRupees(data.totalOverdueAmount)}</strong> total due
            </span>
            {data.oldestDueDays > 0 && (
              <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                Oldest due: <strong style={{ color: '#c0312b' }}>{data.oldestDueDays}d ago</strong>
              </span>
            )}
          </div>
        )}

        {/* States */}
        {loading && (
          <div style={{ textAlign: 'center', padding: 40, color: 'var(--text-muted)', fontSize: 13 }}>
            Loading…
          </div>
        )}
        {!loading && error && (
          <div className="ck-alert ck-alert-error" style={{ fontSize: 12 }}>{error}</div>
        )}
        {!loading && !error && items.length === 0 && (
          <div style={{ textAlign: 'center', padding: 40, color: 'var(--text-muted)', fontSize: 13 }}>
            No fee defaulters found for current filters.
          </div>
        )}

        {/* Table */}
        {!loading && items.length > 0 && (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
              <thead>
                <tr style={{ borderBottom: '2px solid var(--border)' }}>
                  {canNotify && (
                    <th style={{ width: 32, padding: '6px 4px' }}>
                      <input type="checkbox"
                        checked={selected.size === items.length && items.length > 0}
                        onChange={toggleAll}
                      />
                    </th>
                  )}
                  <th style={{ padding: '6px 8px', textAlign: 'left', fontWeight: 600, color: 'var(--text-muted)' }}>Student</th>
                  <th style={{ padding: '6px 8px', textAlign: 'left', fontWeight: 600, color: 'var(--text-muted)' }}>Class</th>
                  <th style={{ padding: '6px 8px', textAlign: 'right', fontWeight: 600, color: 'var(--text-muted)' }}>Due</th>
                  <th style={{ padding: '6px 8px', textAlign: 'right', fontWeight: 600, color: 'var(--text-muted)' }}>Days</th>
                  <th style={{ padding: '6px 8px', textAlign: 'left', fontWeight: 600, color: 'var(--text-muted)' }}>Parent</th>
                  <th style={{ padding: '6px 8px', textAlign: 'center', fontWeight: 600, color: 'var(--text-muted)' }}>Reminder</th>
                  {canNotify && (
                    <th style={{ padding: '6px 8px', textAlign: 'center', fontWeight: 600, color: 'var(--text-muted)' }}>Action</th>
                  )}
                </tr>
              </thead>
              <tbody>
                {items.map(item => (
                  <tr key={item.studentId}
                      style={{ borderBottom: '1px solid var(--border)',
                               background: selected.has(item.studentId) ? 'var(--surface-2)' : undefined }}>
                    {canNotify && (
                      <td style={{ padding: '8px 4px', textAlign: 'center' }}>
                        <input type="checkbox"
                          checked={selected.has(item.studentId)}
                          onChange={() => toggleSelect(item.studentId)}
                        />
                      </td>
                    )}
                    <td style={{ padding: '8px 8px' }}>
                      <div style={{ fontWeight: 600 }}>{item.studentName}</div>
                      <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{item.admissionNo}</div>
                    </td>
                    <td style={{ padding: '8px 8px', color: 'var(--text-muted)' }}>
                      {item.className} – {item.sectionName}
                    </td>
                    <td style={{ padding: '8px 8px', textAlign: 'right', fontWeight: 700, color: '#c0312b' }}>
                      {formatRupees(item.dueAmount)}
                    </td>
                    <td style={{ padding: '8px 8px', textAlign: 'right', color: item.daysOverdue > 30 ? '#c0312b' : 'var(--text)' }}>
                      {item.daysOverdue}d
                    </td>
                    <td style={{ padding: '8px 8px' }}>
                      {item.parentPhone ? (
                        <div>
                          <div style={{ fontSize: 11 }}>{item.parentName ?? '—'}</div>
                          <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{item.parentPhone}</div>
                        </div>
                      ) : (
                        <span style={{ fontSize: 11, color: '#c0312b' }}>No contact</span>
                      )}
                    </td>
                    <td style={{ padding: '8px 8px', textAlign: 'center' }}>
                      <ReminderStatusBadge status={item.reminderStatus} />
                      {item.lastReminderSentAt && (
                        <div style={{ fontSize: 10, color: 'var(--text-muted)', marginTop: 2 }}>
                          {new Date(item.lastReminderSentAt).toLocaleDateString('en-IN')}
                        </div>
                      )}
                    </td>
                    {canNotify && (
                      <td style={{ padding: '8px 8px', textAlign: 'center' }}>
                        <button
                          className="ck-btn ck-btn-ghost"
                          style={{ fontSize: 11, padding: '2px 8px' }}
                          disabled={!item.parentPhone}
                          onClick={() => handleSendOne(item)}
                        >
                          Remind
                        </button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination */}
        {totalPages > 1 && (
          <div style={{ display: 'flex', justifyContent: 'center', gap: 8, marginTop: 16 }}>
            <button className="ck-btn ck-btn-ghost" disabled={page === 0}
                    style={{ fontSize: 12 }} onClick={() => setPage(p => p - 1)}>
              ← Prev
            </button>
            <span style={{ fontSize: 12, alignSelf: 'center', color: 'var(--text-muted)' }}>
              {page + 1} / {totalPages}
            </span>
            <button className="ck-btn ck-btn-ghost" disabled={page >= totalPages - 1}
                    style={{ fontSize: 12 }} onClick={() => setPage(p => p + 1)}>
              Next →
            </button>
          </div>
        )}
      </CommandCenterDrawer>

      {/* Confirm reminder modal */}
      {confirmItems && (
        <ConfirmReminderModal
          selected={confirmItems}
          onClose={() => setConfirmItems(null)}
          onSent={handleAfterSent}
        />
      )}

      {/* Toast */}
      {toast && (
        <div style={{
          position: 'fixed', bottom: 24, left: '50%', transform: 'translateX(-50%)',
          background: '#1a6840', color: '#fff', padding: '10px 20px',
          borderRadius: 8, fontSize: 13, fontWeight: 600, zIndex: 600,
          boxShadow: '0 4px 16px rgba(0,0,0,0.2)',
        }}>
          {toast}
        </div>
      )}
    </>
  );
}
