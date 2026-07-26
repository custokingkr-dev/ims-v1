import { Phone, PhoneOff, Search, Send } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import api from '../../../services/api';
import { useAuth } from '../../../contexts/AuthContext';
import { usePermissions } from '../../../hooks/usePermissions';
import { todayIso } from '../utils';
import type { AttendanceExceptionListResponse, NotifyAbsenteesResponse } from '../../../types/attendance';
import { AttendancePagination } from './attendance/AttendancePagination';

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
  const [statusFilter, setStatusFilter] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [data, setData] = useState<AttendanceExceptionListResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [notifying, setNotifying] = useState(false);
  const [error, setError] = useState('');
  const [toast, setToast] = useState('');

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

  const load = async (selectedDate: string, selectedClass: string, selectedSection: string) => {
    setLoading(true);
    setError('');
    try {
      const params: Record<string, string | number> = { date: selectedDate, ...scoped };
      if (selectedClass) params.classId = selectedClass;
      if (selectedSection) params.sectionId = selectedSection;
      const response = await api.get<AttendanceExceptionListResponse>('/attendance/exceptions', { params });
      setData(response.data);
    } catch (err) {
      setError(errMessage(err, 'Failed to load attendance exceptions'));
      setData(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setPage(1);
    void load(date, classId, sectionId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [date, classId, sectionId]);

  useEffect(() => {
    setPage(1);
  }, [query, statusFilter, pageSize]);

  const students = data?.students || [];
  const absentStudents = students.filter((student) => student.status === 'ABSENT');
  const notifiable = absentStudents.filter((student) => student.hasContact && !student.alreadyQueued).length;
  const filteredStudents = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    return students.filter((student) => {
      if (statusFilter && student.status !== statusFilter) return false;
      if (!normalized) return true;
      return student.fullName.toLowerCase().includes(normalized)
        || student.admissionNo.toLowerCase().includes(normalized)
        || student.classSection.toLowerCase().includes(normalized)
        || student.parentContact.toLowerCase().includes(normalized)
        || student.remarks.toLowerCase().includes(normalized);
    });
  }, [query, statusFilter, students]);
  const pageCount = Math.max(1, Math.ceil(filteredStudents.length / pageSize));
  const safePage = Math.min(page, pageCount);
  const visibleStudents = filteredStudents.slice((safePage - 1) * pageSize, safePage * pageSize);

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
      const response = await api.post<NotifyAbsenteesResponse>('/attendance/absentees/notify', body);
      const result = response.data;
      setToast(`Queued ${result.queued}; skipped ${result.skippedNoContact + result.skippedAlreadyQueued}.`);
      await load(date, classId, sectionId);
    } catch (err) {
      setError(errMessage(err, 'Could not queue notifications'));
    } finally {
      setNotifying(false);
    }
  };

  return (
    <div className="ck-panel-stack">
      {toast && <div className="ck-alert ck-alert-g"><span>OK</span><div>{toast}</div></div>}
      {error && <div className="ck-alert ck-alert-re"><span>!</span><div>{error}</div></div>}
      {!canManageAttendance && (
        <div className="ck-alert ck-alert-am">
          <span>i</span>
          <div>You can review absentees. Notification queuing requires attendance:manage.</div>
        </div>
      )}

      <div className="ck-att-exception-toolbar">
        <label className="ck-att-filter-field">
          <span>Date</span>
          <input type="date" value={date} onChange={(event) => setDate(event.target.value)} />
        </label>
        <label className="ck-att-filter-field">
          <span>Class</span>
          <select value={classId} onChange={(event) => { setClassId(event.target.value); setSectionId(''); }}>
            <option value="">All classes</option>
            {classes.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
          </select>
        </label>
        <label className="ck-att-filter-field">
          <span>Section</span>
          <select value={sectionId} onChange={(event) => setSectionId(event.target.value)} disabled={!classId}>
            <option value="">All sections</option>
            {sections.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
          </select>
        </label>
        <label className="ck-att-filter-field">
          <span>Exception</span>
          <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
            <option value="">All exceptions</option>
            <option value="ABSENT">Absent</option>
            <option value="LATE">Late</option>
            <option value="LEAVE">Leave</option>
          </select>
        </label>
        <div className="ck-att-search">
          <Search size={16} />
          <input
            type="search"
            aria-label="Search absentees"
            placeholder="Search student, class, or contact"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>
        <button
          type="button"
          className="ck-att-button ck-att-button--primary"
          disabled={!canManageAttendance || notifiable === 0 || notifying}
          onClick={notify}
        >
          <Send size={16} />
          {notifying ? 'Queuing...' : `Notify eligible (${notifiable})`}
        </button>
      </div>

      <div className="ck-att-kpi-band">
        <div className="ck-att-kpi ck-att-kpi--alert"><div><span>Absent</span><strong>{data?.absentCount ?? 0}</strong><small>{notifiable} can be notified</small></div></div>
        <div className="ck-att-kpi"><div><span>Late arrivals</span><strong>{data?.lateCount ?? 0}</strong><small>Review arrival remarks</small></div></div>
        <div className="ck-att-kpi"><div><span>Excused leave</span><strong>{data?.leaveCount ?? 0}</strong><small>Excluded from percentage</small></div></div>
        <div className="ck-att-kpi"><div><span>Parents queued</span><strong>{data?.queuedCount ?? 0}</strong><small>No duplicate notification</small></div></div>
      </div>

      <div className="ck-att-report-card">
        <div className="ck-att-report-heading">
          <div><strong>Attendance exceptions</strong><span>{filteredStudents.length} students in the current view</span></div>
          <span className={`ck-status ${canManageAttendance ? 'sapproved' : 'sneutral'}`}>
            {canManageAttendance ? 'Can notify' : 'Read-only'}
          </span>
        </div>
        {loading ? (
          <div className="ck-att-empty">Loading attendance exceptions...</div>
        ) : !data || filteredStudents.length === 0 ? (
          <div className="ck-att-empty">{query || statusFilter ? 'No exceptions match your filters.' : 'No attendance exceptions for this date.'}</div>
        ) : (
          <>
            <div className="ck-att-table-scroll">
              <table className="ck-att-table">
                <thead>
                  <tr><th>Student</th><th>Class and section</th><th>Exception</th><th>Remarks</th><th>Parent contact</th><th>Notification</th></tr>
                </thead>
                <tbody>
                  {visibleStudents.map((student) => (
                    <tr key={student.studentId}>
                      <td>
                        <strong>{student.fullName}</strong>
                        <span className="ck-att-cell-sub">{student.admissionNo}{student.rollNo ? ` · Roll ${student.rollNo}` : ''}</span>
                      </td>
                      <td>{student.classSection}</td>
                      <td>
                        <span className={`ck-status ${student.status === 'ABSENT' ? 'srejected' : student.status === 'LATE' ? 'spending' : 'sinfo'}`}>
                          {student.status === 'ABSENT' ? 'Absent' : student.status === 'LATE' ? 'Late' : 'Leave'}
                        </span>
                      </td>
                      <td>{student.remarks || '-'}</td>
                      <td>
                        <span className="ck-att-contact">
                          {student.hasContact ? <Phone size={15} /> : <PhoneOff size={15} />}
                          {student.hasContact ? student.parentContact : 'Contact missing'}
                        </span>
                      </td>
                      <td>
                        {student.status !== 'ABSENT'
                          ? <span className="ck-status sneutral">Not required</span>
                          : student.alreadyQueued
                          ? <span className="ck-status sapproved">Queued</span>
                          : student.hasContact
                            ? <span className="ck-status spending">Ready</span>
                            : <span className="ck-status sneutral">Unavailable</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <AttendancePagination
              page={safePage}
              pageSize={pageSize}
              totalItems={filteredStudents.length}
              itemLabel="exceptions"
              onPageChange={setPage}
              onPageSizeChange={setPageSize}
            />
          </>
        )}
      </div>
    </div>
  );
}
