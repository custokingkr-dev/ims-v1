import { Download, Play } from 'lucide-react';
import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { todayIso } from '../utils';
import { RegisterGrid } from './attendance/reports/RegisterGrid';
import { StudentHistory } from './attendance/reports/StudentHistory';
import { SectionSummary } from './attendance/reports/SectionSummary';
import { downloadReport } from './attendance/reports/download';
import type {
  AttendanceRegisterReport,
  AttendanceStudentHistory,
  AttendanceSummaryReport,
} from '../../../types/attendance';

interface Props { schoolScopedParams?: { schoolId: number }; }
type Tab = 'register' | 'student' | 'summary';
interface ClassOpt { id: string; name: string }
interface SectionOpt { id: string; name: string }
interface StudentOpt { id: number; name: string; admissionNo: string }

function monthIso(): string { return todayIso().slice(0, 7); }
function monthStart(month: string): string { return `${month}-01`; }
function errMessage(err: unknown, fallback: string): string {
  if (err instanceof Error && err.message) return err.message;
  return (err as { response?: { data?: { message?: string } } })?.response?.data?.message || fallback;
}

export function AttendanceReportsPanel({ schoolScopedParams }: Props) {
  const scoped = schoolScopedParams || {};
  const [tab, setTab] = useState<Tab>('summary');
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
      .then((response) => setClasses(Array.isArray(response.data) ? response.data : []))
      .catch(() => setClasses([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!classId) {
      setSections([]);
      return;
    }
    void api.get<SectionOpt[]>(`/classes/${encodeURIComponent(classId)}/sections`, { params: scoped })
      .then((response) => setSections(Array.isArray(response.data) ? response.data : []))
      .catch(() => setSections([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [classId]);

  useEffect(() => {
    if (!classId || !sectionId) {
      setStudents([]);
      return;
    }
    void api.get<StudentOpt[]>(`/classes/${encodeURIComponent(classId)}/sections/${encodeURIComponent(sectionId)}/students`, { params: scoped })
      .then((response) => setStudents(Array.isArray(response.data) ? response.data : []))
      .catch(() => setStudents([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [classId, sectionId]);

  const load = async () => {
    setError('');
    setLoading(true);
    try {
      if (tab === 'register') {
        if (!classId || !sectionId) {
          setRegister(null);
          return;
        }
        const response = await api.get<AttendanceRegisterReport>('/attendance/report/register', {
          params: { month, classId, sectionId, ...scoped },
        });
        setRegister(response.data);
      } else if (tab === 'student') {
        if (!studentId) {
          setHistory(null);
          return;
        }
        const response = await api.get<AttendanceStudentHistory>('/attendance/report/student', {
          params: { studentId, from, to, ...scoped },
        });
        setHistory(response.data);
      } else {
        const response = await api.get<AttendanceSummaryReport>('/attendance/report/summary', {
          params: { from, to, ...scoped },
        });
        setSummary(response.data);
      }
    } catch (err) {
      setError(errMessage(err, 'Failed to load report'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
    // Load the default summary once; subsequent report runs stay explicit.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
  const canRun = tab === 'summary' || (tab === 'register' && !!classId && !!sectionId) || (tab === 'student' && !!studentId);

  return (
    <div className="ck-panel-stack">
      {error && <div className="ck-alert ck-alert-re"><span>!</span><div>{error}</div></div>}

      <div className="ck-att-report-toolbar">
        <div className="ck-att-report-tabs" role="tablist" aria-label="Attendance report type">
          {([
            ['summary', 'Section summary'],
            ['register', 'Monthly register'],
            ['student', 'Student history'],
          ] as Array<[Tab, string]>).map(([id, label]) => (
            <button
              key={id}
              type="button"
              role="tab"
              aria-selected={tab === id}
              className={tab === id ? 'active' : ''}
              onClick={() => { setTab(id); setError(''); }}
            >
              {label}
            </button>
          ))}
        </div>

        <div className="ck-att-report-filters">
          <div className="ck-att-report-filter-fields">
            {(tab === 'register' || tab === 'student') && (
              <>
                <label className="ck-att-filter-field">
                  <span>Class</span>
                  <select value={classId} onChange={(event) => {
                    setClassId(event.target.value);
                    setSectionId('');
                    setStudentId('');
                    setRegister(null);
                    setHistory(null);
                  }}>
                    <option value="">Select class</option>
                    {classes.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
                  </select>
                </label>
                <label className="ck-att-filter-field">
                  <span>Section</span>
                  <select value={sectionId} onChange={(event) => {
                    setSectionId(event.target.value);
                    setStudentId('');
                    setRegister(null);
                    setHistory(null);
                  }} disabled={!classId}>
                    <option value="">Select section</option>
                    {sections.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
                  </select>
                </label>
              </>
            )}
            {tab === 'student' && (
              <label className="ck-att-filter-field ck-att-filter-field--wide">
                <span>Student</span>
                <select value={studentId} onChange={(event) => {
                  setStudentId(event.target.value);
                  setHistory(null);
                }} disabled={!sectionId}>
                  <option value="">Select student</option>
                  {students.map((item) => <option key={item.id} value={item.id}>{item.name} ({item.admissionNo})</option>)}
                </select>
              </label>
            )}
            {tab === 'register' && (
              <label className="ck-att-filter-field">
                <span>Month</span>
                <input type="month" value={month} onChange={(event) => {
                  setMonth(event.target.value);
                  setRegister(null);
                }} />
              </label>
            )}
            {(tab === 'student' || tab === 'summary') && (
              <>
                <label className="ck-att-filter-field">
                  <span>From</span>
                  <input type="date" value={from} onChange={(event) => {
                    setFrom(event.target.value);
                    setSummary(null);
                    setHistory(null);
                  }} />
                </label>
                <label className="ck-att-filter-field">
                  <span>To</span>
                  <input type="date" value={to} onChange={(event) => {
                    setTo(event.target.value);
                    setSummary(null);
                    setHistory(null);
                  }} />
                </label>
              </>
            )}
          </div>
          <div className="ck-att-report-actions">
            <button type="button" className="ck-att-button ck-att-button--primary" onClick={load} disabled={loading || !canRun}>
              <Play size={15} />
              {loading ? 'Loading...' : 'Run report'}
            </button>
            <button type="button" className="ck-att-button" disabled={!canExport || exporting} onClick={() => doExport('csv')}>
              <Download size={15} />
              CSV
            </button>
            <button type="button" className="ck-att-button" disabled={!canExport || exporting} onClick={() => doExport('pdf')}>
              <Download size={15} />
              PDF
            </button>
          </div>
        </div>
      </div>

      {tab === 'register' && <RegisterGrid report={register} loading={loading} />}
      {tab === 'student' && <StudentHistory report={history} loading={loading} />}
      {tab === 'summary' && <SectionSummary report={summary} loading={loading} />}
    </div>
  );
}
