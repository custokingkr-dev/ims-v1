import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { ModuleShell, Field } from '../ui';
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
  }, [currentDate]);

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
    setRecords((prev) => (prev ? prev.map((r) => (r.studentId === studentId ? { ...r, status } : r)) : prev));

  const setRemarks = (studentId: number, remarks: string) =>
    setRecords((prev) => (prev ? prev.map((r) => (r.studentId === studentId ? { ...r, remarks } : r)) : prev));

  const markAllPresent = () => {
    setRecords((prev) => (prev ? prev.map((r) => ({ ...r, status: 'PRESENT' as const })) : prev));
    setToast('All students marked as present');
  };

  const resetChanges = () => {
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
    if (records.some((r) => r.status === null)) {
      setError('Every student must be marked (Present, Late, Leave or Absent) before submitting');
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
    setSubmittingDay(true);
    setError('');
    setToast('');
    try {
      await api.post('/attendance/submit-day', { date: currentDate, ...scoped });
      setToast(`Submitted attendance for ${summary.dateLabel}`);
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
      subtitle={`${summary.dateLabel || '—'} · ${Number(summary.overallPercent || 0).toFixed(1)}% overall`}
      actions={
        <button
          className="ck-btn ck-btn-g"
          disabled={!summary.allSubmitted || submittingDay}
          onClick={submitDay}
        >
          Submit today's attendance
        </button>
      }
    >
      {summary.nonWorkingDay && (
        <div className="ck-alert ck-alert-am" style={{ marginBottom: 16 }}>
          <span>⚠</span>
          <div>This is a non-working day. Attendance can still be recorded.</div>
        </div>
      )}
      {toast && (
        <div className="ck-alert ck-alert-g" style={{ marginBottom: 16 }}>
          <span>✓</span>
          <div>{toast}</div>
        </div>
      )}
      {error && (
        <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}>
          <span>!</span>
          <div>{error}</div>
        </div>
      )}

      <div style={{ marginBottom: 16 }}>
        <Field label="Date">
          <input type="date" value={currentDate} onChange={(e) => handleDateChange(e.target.value)} />
        </Field>
      </div>

      <div className={`ck-att-layout${selectedSection ? ' ck-att-layout--detail' : ''}`}>
        <SectionRail
          sections={summary.sections || []}
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
            onStatusChange={setStatus}
            onRemarksChange={setRemarks}
            onMarkAllPresent={markAllPresent}
            onReset={resetChanges}
            onSave={save}
            onSubmit={submitSection}
            onBack={backToRail}
          />
        ) : (
          <div className="ck-att-roster">
            <div style={{ padding: '32px 0', textAlign: 'center', color: 'var(--ink3)' }}>
              Select a section to mark attendance.
            </div>
          </div>
        )}
      </div>
    </ModuleShell>
  );
}
