import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field } from '../ui';
import { todayIso } from '../utils';
import { RegisterGrid } from './attendance/reports/RegisterGrid';
import { StudentHistory } from './attendance/reports/StudentHistory';
import { SectionSummary } from './attendance/reports/SectionSummary';
import { downloadReport } from './attendance/reports/download';
import type {
  AttendanceRegisterReport, AttendanceStudentHistory, AttendanceSummaryReport,
} from '../../../types/attendance';

interface Props { schoolScopedParams?: { schoolId: number }; }
type Tab = 'register' | 'student' | 'summary';
interface ClassOpt { id: string; name: string }
interface SectionOpt { id: string; name: string }
interface StudentOpt { id: number; name: string; admissionNo: string }

function monthIso(): string { return todayIso().slice(0, 7); }
function monthStart(m: string): string { return `${m}-01`; }
function errMessage(err: unknown, fallback: string): string {
  if (err instanceof Error && err.message) return err.message;
  return (err as { response?: { data?: { message?: string } } })?.response?.data?.message || fallback;
}

export function AttendanceReportsPanel({ schoolScopedParams }: Props) {
  const scoped = schoolScopedParams || {};
  const [tab, setTab] = useState<Tab>('register');
  const [classes, setClasses] = useState<ClassOpt[]>([]);
  const [sections, setSections] = useState<SectionOpt[]>([]);
  const [students, setStudents] = useState<StudentOpt[]>([]);
  const [classId, setClassId] = useState('');
  const [sectionId, setSectionId] = useState('');
  const [studentId, setStudentId] = useState('');
  const [month, setMonth] = useState(monthIso());
  const [from, setFrom] = useState(monthStart(monthIso()));
  const [to, setTo] = useState(todayIso());

  const [register, setRegister] = useState<AttendanceRegisterReport | null>(null);
  const [history, setHistory] = useState<AttendanceStudentHistory | null>(null);
  const [summary, setSummary] = useState<AttendanceSummaryReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [exporting, setExporting] = useState(false);

  useEffect(() => {
    void api.get<ClassOpt[]>('/classes', { params: scoped })
      .then((r) => setClasses(Array.isArray(r.data) ? r.data : []))
      .catch(() => setClasses([]));
  }, []);

  useEffect(() => {
    if (!classId) { setSections([]); return; }
    void api.get<SectionOpt[]>(`/classes/${encodeURIComponent(classId)}/sections`, { params: scoped })
      .then((r) => setSections(Array.isArray(r.data) ? r.data : []))
      .catch(() => setSections([]));
  }, [classId]);

  useEffect(() => {
    if (!classId || !sectionId) { setStudents([]); return; }
    void api.get<StudentOpt[]>(`/classes/${encodeURIComponent(classId)}/sections/${encodeURIComponent(sectionId)}/students`, { params: scoped })
      .then((r) => setStudents(Array.isArray(r.data) ? r.data : []))
      .catch(() => setStudents([]));
  }, [classId, sectionId]);

  const load = async () => {
    setError('');
    setLoading(true);
    try {
      if (tab === 'register') {
        if (!classId || !sectionId) { setRegister(null); return; }
        const r = await api.get<AttendanceRegisterReport>('/attendance/report/register', { params: { month, classId, sectionId, ...scoped } });
        setRegister(r.data);
      } else if (tab === 'student') {
        if (!studentId) { setHistory(null); return; }
        const r = await api.get<AttendanceStudentHistory>('/attendance/report/student', { params: { studentId, from, to, ...scoped } });
        setHistory(r.data);
      } else {
        const r = await api.get<AttendanceSummaryReport>('/attendance/report/summary', { params: { from, to, ...scoped } });
        setSummary(r.data);
      }
    } catch (err) {
      setError(errMessage(err, 'Failed to load report'));
    } finally {
      setLoading(false);
    }
  };

  const doExport = async (format: 'csv' | 'pdf') => {
    setError('');
    setExporting(true);
    try {
      if (tab === 'register') {
        await downloadReport('/attendance/report/register/export', { month, classId, sectionId, ...scoped }, format, `register-${month}.${format}`);
      } else if (tab === 'student') {
        await downloadReport('/attendance/report/student/export', { studentId, from, to, ...scoped }, format, `student-${studentId}-${from}_${to}.${format}`);
      } else {
        await downloadReport('/attendance/report/summary/export', { from, to, ...scoped }, format, `summary-${from}_${to}.${format}`);
      }
    } catch (err) {
      setError(errMessage(err, 'Export failed'));
    } finally {
      setExporting(false);
    }
  };

  const canExport = tab === 'register' ? !!register : tab === 'student' ? !!history : !!summary;

  return (
    <ModuleShell
      title="Attendance reports"
      subtitle="Register, per-student history, and section summary"
      actions={
        <span style={{ display: 'flex', gap: 8 }}>
          <button className="ck-btn ck-btn-ghost" disabled={!canExport || exporting} onClick={() => doExport('csv')}>Export CSV</button>
          <button className="ck-btn ck-btn-ghost" disabled={!canExport || exporting} onClick={() => doExport('pdf')}>Export PDF</button>
        </span>
      }
    >
      <div className="ck-att-tabs">
        {(['register', 'student', 'summary'] as Tab[]).map((t) => (
          <button key={t} type="button" className={`ck-att-tab${tab === t ? ' ck-att-tab--active' : ''}`}
            onClick={() => { setTab(t); setError(''); }}>
            {t === 'register' ? 'Register' : t === 'student' ? 'Student history' : 'Summary'}
          </button>
        ))}
      </div>

      {error && <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>!</span><div>{error}</div></div>}

      <div className="ck-att-report-controls">
        {(tab === 'register' || tab === 'student') && (
          <>
            <Field label="Class">
              <select value={classId} onChange={(e) => { setClassId(e.target.value); setSectionId(''); setStudentId(''); }}>
                <option value="">Select class</option>
                {classes.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </Field>
            <Field label="Section">
              <select value={sectionId} onChange={(e) => { setSectionId(e.target.value); setStudentId(''); }} disabled={!classId}>
                <option value="">Select section</option>
                {sections.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </Field>
          </>
        )}
        {tab === 'student' && (
          <Field label="Student">
            <select value={studentId} onChange={(e) => setStudentId(e.target.value)} disabled={!sectionId}>
              <option value="">Select student</option>
              {students.map((s) => <option key={s.id} value={s.id}>{s.name} ({s.admissionNo})</option>)}
            </select>
          </Field>
        )}
        {tab === 'register' && (
          <Field label="Month"><input type="month" value={month} onChange={(e) => setMonth(e.target.value)} /></Field>
        )}
        {(tab === 'student' || tab === 'summary') && (
          <>
            <Field label="From"><input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
            <Field label="To"><input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
          </>
        )}
        <button className="ck-btn ck-btn-b" onClick={load} disabled={loading}>{loading ? 'Loading…' : 'Run report'}</button>
      </div>

      {tab === 'register' && <RegisterGrid report={register} loading={loading} />}
      {tab === 'student' && <StudentHistory report={history} loading={loading} />}
      {tab === 'summary' && <SectionSummary report={summary} loading={loading} />}
    </ModuleShell>
  );
}
