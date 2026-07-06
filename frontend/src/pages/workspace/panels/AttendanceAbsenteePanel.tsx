import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field } from '../ui';
import { todayIso } from '../utils';
import { useAuth } from '../../../contexts/AuthContext';
import type { AbsenteeListResponse, NotifyAbsenteesResponse } from '../../../types/attendance';

interface Props { schoolScopedParams?: { schoolId: number }; }
interface ClassOpt { id: string; name: string }
interface SectionOpt { id: string; name: string }

function errMessage(err: unknown, fallback: string): string {
  if (err instanceof Error && err.message) return err.message;
  return (err as { response?: { data?: { message?: string } } })?.response?.data?.message || fallback;
}

export function AttendanceAbsenteePanel({ schoolScopedParams }: Props) {
  const { user } = useAuth();
  const scoped = schoolScopedParams || {};
  const [date, setDate] = useState(todayIso());
  const [classes, setClasses] = useState<ClassOpt[]>([]);
  const [sections, setSections] = useState<SectionOpt[]>([]);
  const [classId, setClassId] = useState('');
  const [sectionId, setSectionId] = useState('');
  const [data, setData] = useState<AbsenteeListResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [notifying, setNotifying] = useState(false);
  const [error, setError] = useState('');
  const [toast, setToast] = useState('');

  useEffect(() => {
    void api.get<ClassOpt[]>('/classes', { params: scoped })
      .then((r) => setClasses(Array.isArray(r.data) ? r.data : []))
      .catch(() => setClasses([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!classId) { setSections([]); return; }
    void api.get<SectionOpt[]>(`/classes/${encodeURIComponent(classId)}/sections`, { params: scoped })
      .then((r) => setSections(Array.isArray(r.data) ? r.data : []))
      .catch(() => setSections([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [classId]);

  const load = async (d: string, secId: string) => {
    setLoading(true);
    setError('');
    try {
      const params: Record<string, any> = { date: d, ...scoped };
      if (secId) params.sectionId = secId;
      const res = await api.get<AbsenteeListResponse>('/attendance/absentees', { params });
      setData(res.data);
    } catch (err) {
      setError(errMessage(err, 'Failed to load absentees'));
      setData(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(date, sectionId); }, [date, sectionId]);

  const notifiable = (data?.students || []).filter((s) => s.hasContact && !s.alreadyQueued).length;

  const notify = async () => {
    setNotifying(true);
    setError('');
    setToast('');
    try {
      const body: Record<string, any> = { date, actorId: user?.userId, ...scoped };
      if (sectionId) body.sectionId = sectionId;
      const res = await api.post<NotifyAbsenteesResponse>('/attendance/absentees/notify', body);
      const r = res.data;
      setToast(`Queued ${r.queued} · skipped ${r.skippedNoContact + r.skippedAlreadyQueued} (no contact ${r.skippedNoContact}, already queued ${r.skippedAlreadyQueued})`);
      await load(date, sectionId);
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

      <div style={{ marginBottom: 16, display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        <Field label="Date"><input type="date" value={date} onChange={(e) => setDate(e.target.value)} /></Field>
        <Field label="Class">
          <select value={classId} onChange={(e) => { setClassId(e.target.value); setSectionId(''); }}>
            <option value="">All classes</option>
            {classes.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </Field>
        <Field label="Section">
          <select value={sectionId} onChange={(e) => setSectionId(e.target.value)} disabled={!classId}>
            <option value="">All sections</option>
            {sections.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </Field>
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
