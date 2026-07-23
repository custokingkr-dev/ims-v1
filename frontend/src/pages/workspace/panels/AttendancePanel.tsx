import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { useAuth } from '../../../contexts/AuthContext';
import { usePermissions } from '../../../hooks/usePermissions';
import { ModuleShell, Field, Stat } from '../ui';
import { todayIso } from '../utils';
import { SectionRail } from './attendance/SectionRail';
import { SectionRoster } from './attendance/SectionRoster';
import type {
  AttendanceDailySummaryResponse,
  AttendanceDailySummarySection,
  SectionRegisterResponse,
  StudentEditRecord,
  EditableAttendanceStatus,
} from '../../../types/attendance';

interface Props {
  onRefresh: () => Promise<void>;
  schoolScopedParams?: { schoolId: number };
}

const EMPTY_SUMMARY: AttendanceDailySummaryResponse = {
  date: '',
  dateLabel: '',
  overallPercent: 0,
  sections: [],
  allSubmitted: false,
  nonWorkingDay: false,
};

function errMessage(err: unknown, fallback: string): string {
  if (err instanceof Error && err.message) return err.message;
  return (err as { response?: { data?: { message?: string } } })?.response?.data?.message || fallback;
}

export function AttendancePanel({ onRefresh, schoolScopedParams }: Props) {
  const { user } = useAuth();
  const { can } = usePermissions();
  const role = String(user?.role || '').toUpperCase();
  const canManageAttendance = role === 'SUPERADMIN' || can('platform:admin') || can('attendance:manage');

  const [summary, setSummary] = useState<AttendanceDailySummaryResponse>(EMPTY_SUMMARY);
  const [currentDate, setCurrentDate] = useState(todayIso());

  const [selectedSection, setSelectedSection] = useState<AttendanceDailySummarySection | null>(null);
  const [register, setRegister] = useState<SectionRegisterResponse | null>(null);
  const [records, setRecords] = useState<StudentEditRecord[] | null>(null);

  const [summaryLoading, setSummaryLoading] = useState(true);
  const [rosterLoading, setRosterLoading] = useState(false);
  const [saving, setSaving] = useState<'' | 'save' | 'submit'>('');
  const [submittingDay, setSubmittingDay] = useState(false);
  const [toast, setToast] = useState('');
  const [error, setError] = useState('');

  const scoped = schoolScopedParams || {};
  const sections = summary.sections || [];
  const attendanceTotals = sections.reduce(
    (acc, section) => {
      const marked =
        Number(section.presentCount || 0) +
        Number(section.lateCount || 0) +
        Number(section.leaveCount || 0) +
        Number(section.absentCount || 0);
      acc.totalStudents += Number(section.totalStudents || 0);
      acc.marked += marked;
      acc.present += Number(section.presentCount || 0);
      acc.late += Number(section.lateCount || 0);
      acc.leave += Number(section.leaveCount || 0);
      acc.absent += Number(section.absentCount || 0);
      if (section.status === 'Submitted' || section.locked) acc.submitted += 1;
      if (section.status === 'Saved') acc.saved += 1;
      return acc;
    },
    { totalStudents: 0, marked: 0, present: 0, late: 0, leave: 0, absent: 0, submitted: 0, saved: 0 },
  );
  const unmarked = Math.max(0, attendanceTotals.totalStudents - attendanceTotals.marked);
  const pendingSections = Math.max(0, sections.length - attendanceTotals.submitted);
  const completionPercent = attendanceTotals.totalStudents > 0
    ? Math.round((attendanceTotals.marked / attendanceTotals.totalStudents) * 100)
    : 0;

  const ensureCanManage = (): boolean => {
    if (canManageAttendance) return true;
    setError('You need attendance:manage permission to change or submit attendance.');
    setToast('');
    return false;
  };

  const loadSummary = async (dateValue: string) => {
    setSummaryLoading(true);
    try {
      const res = await api.get<AttendanceDailySummaryResponse>('/attendance/daily-summary', {
        params: { date: dateValue || 'today', ...scoped },
      });
      setSummary(res.data || { ...EMPTY_SUMMARY, date: dateValue });
    } catch (err) {
      setError(errMessage(err, 'Failed to load attendance summary'));
    } finally {
      setSummaryLoading(false);
    }
  };

  useEffect(() => {
    void loadSummary(currentDate);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentDate]);

  useEffect(() => {
    if (!selectedSection && !summaryLoading && sections.length > 0) {
      void openSection(sections[0]);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [summary.sections, summaryLoading]);

  const handleDateChange = (dateValue: string) => {
    setCurrentDate(dateValue);
    setSelectedSection(null);
    setRegister(null);
    setRecords(null);
    setToast('');
    setError('');
  };

  const toRecords = (reg: SectionRegisterResponse): StudentEditRecord[] =>
    reg.students.map((s) => ({ studentId: s.studentId, status: s.status ?? null, remarks: s.remarks || '' }));

  const openSection = async (section: AttendanceDailySummarySection) => {
    setSelectedSection(section);
    setRosterLoading(true);
    setError('');
    setToast('');
    try {
      const res = await api.get<SectionRegisterResponse>('/attendance/section-register', {
        params: { date: currentDate, classId: section.classId, sectionId: section.sectionId, ...scoped },
      });
      setRegister(res.data);
      setRecords(res.data?.students ? toRecords(res.data) : []);
    } catch (err) {
      setError(errMessage(err, 'Failed to load section attendance'));
      setSelectedSection(null);
      setRegister(null);
      setRecords(null);
    } finally {
      setRosterLoading(false);
    }
  };

  const backToRail = () => {
    setSelectedSection(null);
    setRegister(null);
    setRecords(null);
    setError('');
  };

  const setStatus = (studentId: number, status: EditableAttendanceStatus) =>
    setRecords((prev) => {
      if (!canManageAttendance) return prev;
      return prev ? prev.map((r) => (r.studentId === studentId ? { ...r, status } : r)) : prev;
    });

  const setRemarks = (studentId: number, remarks: string) =>
    setRecords((prev) => {
      if (!canManageAttendance) return prev;
      return prev ? prev.map((r) => (r.studentId === studentId ? { ...r, remarks } : r)) : prev;
    });

  const markAllPresent = () => {
    if (!ensureCanManage()) return;
    setRecords((prev) => (prev ? prev.map((r) => ({ ...r, status: 'PRESENT' as const })) : prev));
    setToast('All students marked as present');
  };

  const markUnmarkedAbsent = () => {
    if (!ensureCanManage()) return;
    setRecords((prev) => (prev ? prev.map((r) => (r.status === null ? { ...r, status: 'ABSENT' as const } : r)) : prev));
    setToast('Unmarked students marked as absent');
  };

  const resetChanges = () => {
    if (!ensureCanManage()) return;
    if (register) {
      setRecords(toRecords(register));
      setToast('Changes reset');
    }
  };

  const putRegister = async (payload: StudentEditRecord[]) => {
    await api.put('/attendance/section-register', {
      date: currentDate,
      classId: selectedSection!.classId,
      sectionId: selectedSection!.sectionId,
      records: payload
        .filter((r) => r.status !== null)
        .map((r) => ({ studentId: r.studentId, status: r.status, remarks: r.remarks || '' })),
      ...scoped,
    });
  };

  const save = async () => {
    if (!selectedSection || !records) return;
    if (!ensureCanManage()) return;
    setSaving('save');
    setError('');
    setToast('');
    try {
      await putRegister(records);
      await openSection(selectedSection);
      await loadSummary(currentDate);
      await onRefresh();
      setToast('Attendance saved successfully');
    } catch (err) {
      setError(errMessage(err, 'Failed to save attendance'));
    } finally {
      setSaving('');
    }
  };

  const submitSection = async () => {
    if (!selectedSection || !records) return;
    if (!ensureCanManage()) return;
    if (records.some((r) => r.status === null)) {
      setError('Every student must be marked as Present, Late, Leave, or Absent before submitting.');
      return;
    }
    setSaving('submit');
    setError('');
    setToast('');
    try {
      await putRegister(records);
      await api.post('/attendance/submit-section', {
        date: currentDate,
        classId: selectedSection.classId,
        sectionId: selectedSection.sectionId,
        ...scoped,
      });
      setToast('Section attendance locked');
      backToRail();
      await loadSummary(currentDate);
      await onRefresh();
    } catch (err) {
      setError(errMessage(err, 'Failed to submit section'));
    } finally {
      setSaving('');
    }
  };

  const submitDay = async () => {
    if (!ensureCanManage()) return;
    setSubmittingDay(true);
    setError('');
    setToast('');
    try {
      await api.post('/attendance/submit-day', { date: currentDate, ...scoped });
      setToast(`Submitted attendance for ${summary.dateLabel || currentDate}`);
      await loadSummary(currentDate);
      await onRefresh();
    } catch (err) {
      setError(errMessage(err, 'Could not submit attendance day'));
    } finally {
      setSubmittingDay(false);
    }
  };

  return (
    <ModuleShell
      title="Attendance"
      subtitle={`${summary.dateLabel || currentDate} - ${Number(summary.overallPercent || 0).toFixed(1)}% present`}
      actions={
        <div className="ck-att-header-actions">
          <span className={`ck-status ${canManageAttendance ? 'sapproved' : 'sneutral'}`}>
            {canManageAttendance ? 'Write access' : 'Read-only'}
          </span>
          <button type="button" className="ck-btn ck-btn-ghost" onClick={() => handleDateChange(todayIso())}>
            Today
          </button>
          <button
            type="button"
            className="ck-btn ck-btn-g"
            disabled={!canManageAttendance || !summary.allSubmitted || submittingDay}
            onClick={submitDay}
            title={!canManageAttendance ? 'Attendance manage permission is required' : !summary.allSubmitted ? 'Submit all sections first' : 'Submit the school day'}
          >
            {submittingDay ? 'Submitting...' : 'Submit day'}
          </button>
        </div>
      }
    >
      <div className="ck-panel-stack">
        {summary.nonWorkingDay && (
          <div className="ck-alert ck-alert-am">
            <span>!</span>
            <div>This is a non-working day. Attendance can still be recorded.</div>
          </div>
        )}
        {!canManageAttendance && (
          <div className="ck-alert ck-alert-am">
            <span>i</span>
            <div>You can review attendance, reports, and absentees. Marking and submission require attendance:manage.</div>
          </div>
        )}
        {toast && (
          <div className="ck-alert ck-alert-g">
            <span>OK</span>
            <div>{toast}</div>
          </div>
        )}
        {error && (
          <div className="ck-alert ck-alert-re">
            <span>!</span>
            <div>{error}</div>
          </div>
        )}

        <div className="ck-card ck-att-day-card">
          <div className="ck-att-day-grid">
            <Field label="Attendance date">
              <input type="date" value={currentDate} onChange={(e) => handleDateChange(e.target.value)} />
            </Field>
            <div className="ck-att-day-note">
              <strong>
                {summary.allSubmitted
                  ? 'Ready for day submission'
                  : `${pendingSections} section${pendingSections === 1 ? '' : 's'} still open`}
              </strong>
              <span>
                {completionPercent}% of students marked across {sections.length} section{sections.length === 1 ? '' : 's'}.
              </span>
            </div>
          </div>
        </div>

        <div className="ck-stats ck-s4 ck-att-kpis">
          <Stat label="Sections" value={sections.length} sub={`${attendanceTotals.submitted} submitted, ${attendanceTotals.saved} saved`} pill={`${pendingSections} open`} tone={pendingSections > 0 ? 'orange' : 'green'} />
          <Stat label="Marked" value={`${completionPercent}%`} sub={`${attendanceTotals.marked} of ${attendanceTotals.totalStudents} students`} pill={`${unmarked} unmarked`} tone={unmarked > 0 ? 'orange' : 'green'} />
          <Stat label="Present today" value={attendanceTotals.present + attendanceTotals.late} sub={`${attendanceTotals.present} present, ${attendanceTotals.late} late`} pill={`${Number(summary.overallPercent || 0).toFixed(1)}%`} tone="blue" />
          <Stat label="Exceptions" value={attendanceTotals.absent + attendanceTotals.leave} sub={`${attendanceTotals.absent} absent, ${attendanceTotals.leave} excused`} pill="Follow up" tone={attendanceTotals.absent > 0 ? 'red' : 'green'} />
        </div>

        <div className={`ck-att-layout${selectedSection ? ' ck-att-layout--detail' : ''}`}>
          <SectionRail
            sections={sections}
            selectedSectionId={selectedSection?.sectionId ?? null}
            loading={summaryLoading}
            onSelect={openSection}
          />
          {selectedSection ? (
            <SectionRoster
              register={register}
              records={records}
              loading={rosterLoading}
              saving={saving}
              readOnly={!canManageAttendance}
              onStatusChange={setStatus}
              onRemarksChange={setRemarks}
              onMarkAllPresent={markAllPresent}
              onMarkUnmarkedAbsent={markUnmarkedAbsent}
              onReset={resetChanges}
              onSave={save}
              onSubmit={submitSection}
              onBack={backToRail}
            />
          ) : (
            <div className="ck-att-roster">
              <div className="ck-att-empty">Select a section to review or mark attendance.</div>
            </div>
          )}
        </div>
      </div>
    </ModuleShell>
  );
}
