import { useState, useEffect, useCallback, useId } from 'react';
import { CommandCenterDrawer } from '../components/CommandCenterDrawer';
import { fetchFeeDefaulters } from '../../../../api/dashboardCommandCenterApi';
import type {
  FeeDefaulterItem,
  FeeDefaulterListResponse,
} from '../../../../types/dashboardCommandCenter';
import { usePermissions } from '../../../../hooks/usePermissions';

interface Props {
  open: boolean;
  onClose: () => void;
}

const PAGE_SIZE = 20;

function formatRupees(paise: number): string {
  return '₹' + (paise / 100).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function ReminderStatusBadge({ status }: { status: FeeDefaulterItem['reminderStatus'] }) {
  const cfg = {
    NOT_SENT: { label: 'Not sent', bg: 'var(--ck-status-neutral-bg)', color: 'var(--ck-status-neutral)' },
    SENT:     { label: 'Sent',     bg: 'var(--ck-color-primary-soft)', color: 'var(--ck-color-primary)' },
    PENDING:  { label: 'Pending',  bg: 'var(--ck-color-warning-soft)', color: 'var(--ck-color-warning)' },
    FAILED:   { label: 'Failed',   bg: 'var(--ck-color-danger-soft)', color: 'var(--ck-color-danger)' },
  }[status];
  return (
    <span style={{ fontSize: 11, fontWeight: 600, padding: '2px 7px', borderRadius: 10,
                   background: cfg.bg, color: cfg.color, whiteSpace: 'nowrap' }}>
      {cfg.label}
    </span>
  );
}

export function FeeDefaultersDrawer({ open, onClose }: Props) {
  const { can } = usePermissions();
  const remindersNoteId = useId();
  const [data, setData] = useState<FeeDefaulterListResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [page, setPage] = useState(0);

  // Filters
  const [classId, setClassId] = useState('');
  const [sectionId, setSectionId] = useState('');
  const [reminderStatusFilter, setReminderStatusFilter] = useState('');

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
  useEffect(() => { if (!open) setPage(0); }, [open]);

  const canNotify = can('fee:collect');
  const items = data?.items ?? [];
  const totalPages = data ? Math.ceil(data.totalElements / PAGE_SIZE) : 0;

  return (
    <CommandCenterDrawer
      title="Fee Defaulters"
      subtitle={data ? `${data.totalDefaulters} students · ${formatRupees(data.totalOverdueAmount)} overdue` : 'Loading…'}
      open={open}
      onClose={onClose}
    >
      {/* Parent messaging is not live: the reminder endpoints answer 409 FEE_REMINDERS_UNAVAILABLE, so the
          drawer lists defaulters without offering a send. */}
      <div id={remindersNoteId} className="ck-alert ck-alert-am" style={{ fontSize: 12, marginBottom: 14 }}>
        Reminders can't be sent yet: parent messaging isn't switched on.
      </div>

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
            <strong style={{ color: 'var(--ck-color-danger)' }}>{formatRupees(data.totalOverdueAmount)}</strong> total due
          </span>
          {data.oldestDueDays > 0 && (
            <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>
              Oldest due: <strong style={{ color: 'var(--ck-color-danger)' }}>{data.oldestDueDays}d ago</strong>
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
                <tr key={item.studentId} style={{ borderBottom: '1px solid var(--border)' }}>
                  <td style={{ padding: '8px 8px' }}>
                    <div style={{ fontWeight: 600 }}>{item.studentName}</div>
                    <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{item.admissionNo}</div>
                  </td>
                  <td style={{ padding: '8px 8px', color: 'var(--text-muted)' }}>
                    {item.className} – {item.sectionName}
                  </td>
                  <td style={{ padding: '8px 8px', textAlign: 'right', fontWeight: 700, color: 'var(--ck-color-danger)' }}>
                    {formatRupees(item.dueAmount)}
                  </td>
                  <td style={{ padding: '8px 8px', textAlign: 'right', color: item.daysOverdue > 30 ? 'var(--ck-color-danger)' : 'var(--text)' }}>
                    {item.daysOverdue}d
                  </td>
                  <td style={{ padding: '8px 8px' }}>
                    {item.parentPhone ? (
                      <div>
                        <div style={{ fontSize: 11 }}>{item.parentName ?? '—'}</div>
                        <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{item.parentPhone}</div>
                      </div>
                    ) : (
                      <span style={{ fontSize: 11, color: 'var(--ck-color-danger)' }}>No contact</span>
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
                        disabled
                        aria-describedby={remindersNoteId}
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
  );
}
