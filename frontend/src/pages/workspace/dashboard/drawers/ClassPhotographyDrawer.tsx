import { useState, useEffect, useCallback, useId } from 'react';
import { CommandCenterDrawer } from '../components/CommandCenterDrawer';
import { fetchClassPhotographyPaymentStatus } from '../../../../api/dashboardCommandCenterApi';
import type {
  PhotoContributionItem,
  ClassPhotographyPaymentStatusResponse,
} from '../../../../types/dashboardCommandCenter';
import { usePermissions } from '../../../../hooks/usePermissions';

interface Props {
  open: boolean;
  onClose: () => void;
}

const PAGE_SIZE = 20;

function fmt(paise: number): string {
  return '₹' + (paise / 100).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function StatusBadge({ status }: { status: PhotoContributionItem['status'] }) {
  const cfg = {
    PAID:    { label: 'Paid',    bg: 'var(--ck-color-primary-soft)', color: 'var(--ck-color-primary)' },
    PARTIAL: { label: 'Partial', bg: 'var(--ck-color-warning-soft)', color: 'var(--ck-color-warning)' },
    PENDING: { label: 'Pending', bg: 'var(--ck-color-danger-soft)', color: 'var(--ck-color-danger)' },
  }[status] ?? { label: status, bg: 'var(--ck-status-neutral-bg)', color: 'var(--ck-status-neutral)' };
  return (
    <span style={{ fontSize: 11, fontWeight: 600, padding: '2px 7px', borderRadius: 10,
                   background: cfg.bg, color: cfg.color, whiteSpace: 'nowrap' }}>
      {cfg.label}
    </span>
  );
}

export function ClassPhotographyDrawer({ open, onClose }: Props) {
  const { can } = usePermissions();
  const canNotify = can('fee:collect');
  const unavailableId = useId();

  const [data, setData] = useState<ClassPhotographyPaymentStatusResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [page, setPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState('');

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
    if (open) { setPage(0); load(0, statusFilter); }
  }, [open]);

  function handleFilterChange(sf: string) {
    setStatusFilter(sf);
    setPage(0);
    load(0, sf);
  }

  function handlePageChange(pg: number) {
    setPage(pg);
    load(pg, statusFilter);
  }

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
              <div key={m.label} style={{ flex: '1 1 130px', background: 'var(--ck-bg-surface-raised)', borderRadius: 10,
                                          padding: '10px 14px', minWidth: 120 }}>
                <div style={{ fontSize: 11, color: 'var(--ck-text-muted)', fontWeight: 600 }}>{m.label}</div>
                <div style={{ fontSize: 18, fontWeight: 700,
                              color: m.hi ? 'var(--ck-color-primary)' : m.warn ? 'var(--ck-color-danger)' : 'var(--ck-text-primary)', marginTop: 2 }}>
                  {m.value}
                </div>
              </div>
            ))}
          </div>
        )}

        {data && !data.eventId && !loading && (
          <div style={{ textAlign: 'center', padding: '40px 0', color: 'var(--ck-text-muted)' }}>
            No active class photography event found.
          </div>
        )}

        {/* Filters */}
        {data && data.eventId && (
          <div style={{ display: 'flex', gap: 10, marginBottom: 16, flexWrap: 'wrap' }}>
            <select value={statusFilter} onChange={e => handleFilterChange(e.target.value)}
                    style={{ padding: '7px 12px', borderRadius: 8, border: '1px solid var(--ck-border-default)', fontSize: 13 }}>
              <option value="">All statuses</option>
              <option value="PENDING">Pending</option>
              <option value="PARTIAL">Partial</option>
              <option value="PAID">Paid</option>
            </select>

            {/* No service sends these reminders since the microservice cutover: the old endpoint is gone, and
                POST /reporting/event-contributions/reminders only records a reminder date without sending
                anything (docs/FRONTEND-AB-DECISIONS-2026-07-02.md). Say so rather than offer a send that fails. */}
            {canNotify && (
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                <button className="ck-btn" style={{ fontSize: 13 }} disabled aria-describedby={unavailableId}>
                  Send payment reminder
                </button>
                <span id={unavailableId} style={{ fontSize: 12, color: 'var(--ck-text-muted)' }}>
                  Sending payment reminders from the dashboard isn't available yet.
                </span>
              </span>
            )}
          </div>
        )}

        {/* Loading / error */}
        {loading && <div style={{ padding: 20, textAlign: 'center', color: 'var(--ck-text-muted)' }}>Loading…</div>}
        {error && <div className="ck-alert" style={{ marginBottom: 12 }}>{error}</div>}

        {/* Table */}
        {!loading && data && data.eventId && data.students.length > 0 && (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <thead>
                <tr style={{ background: 'var(--ck-bg-app)' }}>
                  <th style={{ padding: '8px 10px', textAlign: 'left', fontWeight: 600 }}>Student</th>
                  <th style={{ padding: '8px 10px', textAlign: 'left', fontWeight: 600 }}>Class</th>
                  <th style={{ padding: '8px 10px', textAlign: 'left', fontWeight: 600 }}>Parent Contact</th>
                  <th style={{ padding: '8px 10px', textAlign: 'right', fontWeight: 600 }}>Expected</th>
                  <th style={{ padding: '8px 10px', textAlign: 'right', fontWeight: 600 }}>Paid</th>
                  <th style={{ padding: '8px 10px', textAlign: 'right', fontWeight: 600 }}>Pending</th>
                  <th style={{ padding: '8px 10px', textAlign: 'center', fontWeight: 600 }}>Status</th>
                  <th style={{ padding: '8px 10px', textAlign: 'left', fontWeight: 600 }}>Last Reminder</th>
                </tr>
              </thead>
              <tbody>
                {data.students.map(s => (
                  <tr key={s.studentId} style={{ borderBottom: '1px solid var(--ck-border-subtle)' }}>
                    <td style={{ padding: '8px 10px' }}>
                      <div style={{ fontWeight: 600 }}>{s.studentName}</div>
                      <div style={{ fontSize: 11, color: 'var(--ck-text-muted)' }}>{s.admissionNo}</div>
                    </td>
                    <td style={{ padding: '8px 10px', color: 'var(--ck-text-secondary)' }}>{s.className} – {s.sectionName}</td>
                    <td style={{ padding: '8px 10px', color: 'var(--ck-text-secondary)' }}>{s.parentPhone ?? '—'}</td>
                    <td style={{ padding: '8px 10px', textAlign: 'right', color: 'var(--ck-text-primary)' }}>{fmt(s.expectedAmount)}</td>
                    <td style={{ padding: '8px 10px', textAlign: 'right', color: 'var(--ck-color-primary)' }}>{fmt(s.paidAmount)}</td>
                    <td style={{ padding: '8px 10px', textAlign: 'right',
                                 color: s.pendingAmount > 0 ? 'var(--ck-color-danger)' : 'var(--ck-color-primary)',
                                 fontWeight: s.pendingAmount > 0 ? 600 : 400 }}>
                      {fmt(s.pendingAmount)}
                    </td>
                    <td style={{ padding: '8px 10px', textAlign: 'center' }}>
                      <StatusBadge status={s.status} />
                    </td>
                    <td style={{ padding: '8px 10px', fontSize: 12, color: 'var(--ck-text-muted)' }}>
                      {s.lastReminderSentAt
                        ? new Date(s.lastReminderSentAt).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })
                        : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Empty state */}
        {!loading && data && data.eventId && data.students.length === 0 && (
          <div style={{ textAlign: 'center', padding: '40px 0', color: 'var(--ck-text-muted)' }}>
            No students match the selected filter.
          </div>
        )}

        {/* Pagination */}
        {totalPages > 1 && (
          <div style={{ display: 'flex', justifyContent: 'center', gap: 8, marginTop: 16 }}>
            <button className="ck-btn-ghost" disabled={page === 0}
                    onClick={() => handlePageChange(page - 1)}>← Prev</button>
            <span style={{ padding: '6px 12px', fontSize: 13, color: 'var(--ck-text-secondary)' }}>
              Page {page + 1} of {totalPages}
            </span>
            <button className="ck-btn-ghost" disabled={page >= totalPages - 1}
                    onClick={() => handlePageChange(page + 1)}>Next →</button>
          </div>
        )}
      </CommandCenterDrawer>
    </>
  );
}
