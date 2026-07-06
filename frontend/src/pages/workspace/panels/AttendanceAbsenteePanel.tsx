import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field } from '../ui';
import { todayIso } from '../utils';
import { useAuth } from '../../../contexts/AuthContext';
import type { AbsenteeListResponse, NotifyAbsenteesResponse } from '../../../types/attendance';

interface Props { schoolScopedParams?: { schoolId: number }; }

function errMessage(err: unknown, fallback: string): string {
  if (err instanceof Error && err.message) return err.message;
  return (err as { response?: { data?: { message?: string } } })?.response?.data?.message || fallback;
}

export function AttendanceAbsenteePanel({ schoolScopedParams }: Props) {
  const { user } = useAuth();
  const scoped = schoolScopedParams || {};
  const [date, setDate] = useState(todayIso());
  const [data, setData] = useState<AbsenteeListResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [notifying, setNotifying] = useState(false);
  const [error, setError] = useState('');
  const [toast, setToast] = useState('');

  const load = async (d: string) => {
    setLoading(true);
    setError('');
    try {
      const res = await api.get<AbsenteeListResponse>('/attendance/absentees', { params: { date: d, ...scoped } });
      setData(res.data);
    } catch (err) {
      setError(errMessage(err, 'Failed to load absentees'));
      setData(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(date); }, [date]);

  const notifiable = (data?.students || []).filter((s) => s.hasContact && !s.alreadyQueued).length;

  const notify = async () => {
    setNotifying(true);
    setError('');
    setToast('');
    try {
      const res = await api.post<NotifyAbsenteesResponse>('/attendance/absentees/notify', { date, actorId: user?.userId, ...scoped });
      const r = res.data;
      setToast(`Queued ${r.queued} · skipped ${r.skippedNoContact + r.skippedAlreadyQueued} (no contact ${r.skippedNoContact}, already queued ${r.skippedAlreadyQueued})`);
      await load(date);
    } catch (err) {
      setError(errMessage(err, 'Could not queue notifications'));
    } finally {
      setNotifying(false);
    }
  };

  return (
    <ModuleShell
      title="Absentee notifications"
      subtitle={`${data?.totalAbsent ?? 0} absent · ${data?.queuedCount ?? 0} queued`}
      actions={
        <button className="ck-btn ck-btn-g" disabled={notifiable === 0 || notifying} onClick={notify}>
          {notifying ? 'Queuing…' : `Notify absent parents (WhatsApp)`}
        </button>
      }
    >
      {toast && <div className="ck-alert ck-alert-g" style={{ marginBottom: 16 }}><span>✓</span><div>{toast}</div></div>}
      {error && <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>!</span><div>{error}</div></div>}

      <div style={{ marginBottom: 16 }}>
        <Field label="Date"><input type="date" value={date} onChange={(e) => setDate(e.target.value)} /></Field>
      </div>

      {loading ? (
        <div style={{ padding: 24, color: 'var(--ink3)' }}>Loading absentees…</div>
      ) : !data || data.students.length === 0 ? (
        <div className="ck-alert ck-alert-am"><span>i</span><div>No absentees for this date.</div></div>
      ) : (
        <div className="ck-att-report-scroll">
          <table className="ck-att-table">
            <thead><tr><th>Student</th><th>Class-Section</th><th>Parent contact</th><th>Status</th></tr></thead>
            <tbody>
              {data.students.map((s) => (
                <tr key={s.studentId}>
                  <td>{s.rollNo ? `${s.rollNo}. ` : ''}{s.fullName} <span style={{ color: 'var(--ink3)' }}>({s.admissionNo})</span></td>
                  <td>{s.classSection}</td>
                  <td>{s.hasContact ? s.parentContact : <span style={{ color: 'var(--ink3)' }}>No contact</span>}</td>
                  <td>{s.alreadyQueued ? <span className="ck-status sapproved">Queued</span> : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </ModuleShell>
  );
}
