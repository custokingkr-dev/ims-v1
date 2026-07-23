import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { useAuth } from '../../../contexts/AuthContext';
import { usePermissions } from '../../../hooks/usePermissions';
import { ModuleShell, Field, Stat } from '../ui';
import { todayIso } from '../utils';
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
  const { can } = usePermissions();
  const role = String(user?.role || '').toUpperCase();
  const canManageAttendance = role === 'SUPERADMIN' || can('platform:admin') || can('attendance:manage');

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

  const load = async (d: string, clsId: string, secId: string) => {
    setLoading(true);
    setError('');
    try {
      const params: Record<string, string | number> = { date: d, ...scoped };
      if (clsId) params.classId = clsId;
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

  useEffect(() => {
    void load(date, classId, sectionId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [date, classId, sectionId]);

  const students = data?.students || [];
  const notifiable = students.filter((s) => s.hasContact && !s.alreadyQueued).length;
  const noContact = students.filter((s) => !s.hasContact).length;
  const alreadyQueued = students.filter((s) => s.alreadyQueued).length;

  const notify = async () => {
    if (!canManageAttendance) {
      setError('You need attendance:manage permission to queue absentee notifications.');
      setToast('');
      return;
    }
    setNotifying(true);
    setError('');
    setToast('');
    try {
      const body: Record<string, string | number> = { date, ...scoped };
      if (classId) body.classId = classId;
      if (sectionId) body.sectionId = sectionId;
      const res = await api.post<NotifyAbsenteesResponse>('/attendance/absentees/notify', body);
      const r = res.data;
      setToast(`Queued ${r.queued}; skipped ${r.skippedNoContact + r.skippedAlreadyQueued}.`);
      await load(date, classId, sectionId);
    } catch (err) {
      setError(errMessage(err, 'Could not queue notifications'));
    } finally {
      setNotifying(false);
    }
  };

  return (
    <ModuleShell
      title="Absentee Follow-up"
      subtitle={`${data?.totalAbsent ?? 0} absent - ${data?.queuedCount ?? 0} already queued`}
      actions={
        <div className="ck-att-header-actions">
          <span className={`ck-status ${canManageAttendance ? 'sapproved' : 'sneutral'}`}>
            {canManageAttendance ? 'Can notify' : 'Read-only'}
          </span>
          <button
            type="button"
            className="ck-btn ck-btn-g"
            disabled={!canManageAttendance || notifiable === 0 || notifying}
            onClick={notify}
            title={!canManageAttendance ? 'Attendance manage permission is required' : 'Queue WhatsApp reminders for notifiable absentees'}
          >
            {notifying ? 'Queuing...' : 'Notify parents'}
          </button>
        </div>
      }
    >
      <div className="ck-panel-stack">
        {toast && <div className="ck-alert ck-alert-g"><span>OK</span><div>{toast}</div></div>}
        {error && <div className="ck-alert ck-alert-re"><span>!</span><div>{error}</div></div>}
        {!canManageAttendance && (
          <div className="ck-alert ck-alert-am">
            <span>i</span>
            <div>You can review the absentee list. Notification queuing requires attendance:manage.</div>
          </div>
        )}

        <div className="ck-card ck-att-day-card">
          <div className="ck-att-filter-grid">
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
        </div>

        <div className="ck-stats ck-s4 ck-att-kpis">
          <Stat label="Absent" value={data?.totalAbsent ?? 0} sub={`${students.length} students in current filter`} pill="Exception list" tone={(data?.totalAbsent ?? 0) > 0 ? 'red' : 'green'} />
          <Stat label="Can notify" value={notifiable} sub="Has contact and not yet queued" pill="Ready" tone={notifiable > 0 ? 'orange' : 'green'} />
          <Stat label="No contact" value={noContact} sub="Needs parent data cleanup" pill="Action needed" tone={noContact > 0 ? 'red' : 'green'} />
          <Stat label="Queued" value={alreadyQueued} sub="Notification already queued" pill="Done" tone="blue" />
        </div>

        {loading ? (
          <div className="ck-att-empty">Loading absentees...</div>
        ) : !data || students.length === 0 ? (
          <div className="ck-alert ck-alert-am"><span>i</span><div>No absentees for this date.</div></div>
        ) : (
          <div className="ck-att-report-scroll">
            <table className="ck-att-table">
              <thead><tr><th>Student</th><th>Class-Section</th><th>Parent contact</th><th>Status</th></tr></thead>
              <tbody>
                {students.map((s) => (
                  <tr key={s.studentId}>
                    <td>{s.rollNo ? `${s.rollNo}. ` : ''}{s.fullName} <span className="ck-muted">({s.admissionNo})</span></td>
                    <td>{s.classSection}</td>
                    <td>{s.hasContact ? s.parentContact : <span className="ck-muted">No contact</span>}</td>
                    <td>{s.alreadyQueued ? <span className="ck-status sapproved">Queued</span> : <span className="ck-status sneutral">Not queued</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </ModuleShell>
  );
}
