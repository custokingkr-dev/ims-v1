import { DragEvent, useEffect, useRef, useState } from 'react';
import api from '../../../services/api';
import { useAuth } from '../../../contexts/AuthContext';
import { ModuleShell, Field } from '../ui';
import { formatLongDate, todayIso } from '../utils';

interface Props {
  onRefresh: () => Promise<void>;
  schoolScopedParams?: { schoolId: number };
}

export function AttendancePanel({ onRefresh, schoolScopedParams }: Props) {
  const { user } = useAuth();
  const [attendanceSummary, setAttendanceSummary] = useState<{ dateLabel: string; overallPercent: number; sections: { sectionName: string; totalStudents: number; presentPercent: number | null; teacherName?: string; status: string }[]; allSubmitted: boolean; nonWorkingDay: boolean }>({ dateLabel: '', overallPercent: 0, sections: [], allSubmitted: false, nonWorkingDay: false });
  const [attendanceFilters, setAttendanceFilters] = useState({ date: todayIso(), classId: '', sectionId: '' });
  const [attendanceClassOptions, setAttendanceClassOptions] = useState<{ id: string; name: string }[]>([]);
  const [attendanceSectionOptions, setAttendanceSectionOptions] = useState<{ id: string; name: string }[]>([]);
  const [attendanceSectionInfo, setAttendanceSectionInfo] = useState<{ totalEnrolled: number; existingRecord?: { presentPercent: number; presentCount: number } } | null>(null);
  const [attendancePresentCount, setAttendancePresentCount] = useState('');
  const [attendanceSaveError, setAttendanceSaveError] = useState('');
  const [attendanceToast, setAttendanceToast] = useState('');
  const [saving, setSaving] = useState('');

  const loadAttendanceSummary = async (dateValue: string) => {
    const res = await api.get('/attendance/daily-summary', { params: { date: dateValue || 'today', ...(schoolScopedParams || {}) } });
    setAttendanceSummary(res.data || { dateLabel: '', overallPercent: 0, sections: [], allSubmitted: false, nonWorkingDay: false });
  };

  const loadAttendanceSectionInfo = async (dateValue: string, classId: string, sectionId: string) => {
    if (!dateValue || !classId || !sectionId) { setAttendanceSectionInfo(null); setAttendancePresentCount(''); return; }
    const res = await api.get('/attendance/section-info', { params: { date: dateValue, classId, sectionId, ...(schoolScopedParams || {}) } });
    setAttendanceSectionInfo(res.data);
    const existing = res.data?.existingRecord;
    setAttendancePresentCount(existing?.presentCount != null ? String(existing.presentCount) : '');
  };

  useEffect(() => {
    api.get('/classes', { params: schoolScopedParams }).then((res) => setAttendanceClassOptions(res.data || [])).catch(() => {});
    void loadAttendanceSummary(attendanceFilters.date);
  }, []);

  const handleAttendanceDateChange = async (dateValue: string) => {
    setAttendanceFilters({ date: dateValue, classId: '', sectionId: '' });
    setAttendanceSectionOptions([]);
    setAttendanceSectionInfo(null);
    setAttendancePresentCount('');
    setAttendanceSaveError('');
    setAttendanceToast('');
    await loadAttendanceSummary(dateValue);
  };

  const handleAttendanceClassChange = async (classId: string) => {
    setAttendanceFilters((prev) => ({ ...prev, classId, sectionId: '' }));
    setAttendanceSectionInfo(null);
    setAttendancePresentCount('');
    setAttendanceSaveError('');
    setAttendanceToast('');
    if (!classId) { setAttendanceSectionOptions([]); return; }
    const res = await api.get(`/classes/${encodeURIComponent(classId)}/sections`, { params: schoolScopedParams });
    setAttendanceSectionOptions(res.data || []);
  };

  const handleAttendanceSectionChange = async (sectionId: string) => {
    const next = { ...attendanceFilters, sectionId };
    setAttendanceFilters(next);
    setAttendanceSaveError('');
    setAttendanceToast('');
    await loadAttendanceSectionInfo(next.date, next.classId, sectionId);
  };

  const saveAttendanceEntry = async () => {
    try {
      setSaving('attendance-save');
      setAttendanceSaveError('');
      const totalEnrolled = Number(attendanceSectionInfo?.totalEnrolled || 0);
      const presentCount = Number(attendancePresentCount || 0);
      const response = await api.post('/attendance/daily-entry', {
        date: attendanceFilters.date,
        classId: attendanceFilters.classId,
        sectionId: attendanceFilters.sectionId,
        academicYearId: 'ay_2024_25',
        totalEnrolled,
        presentCount,
        absentCount: Math.max(totalEnrolled - presentCount, 0),
        recordedBy: user?.userId || 'current-user',
      });
      const pct = Number((response.data as { presentPercent?: number })?.presentPercent || 0);
      setAttendanceToast(`Saved — ${String((response.data as { classSection?: string })?.classSection || '').replace('Class ', '')} · ${presentCount}/${totalEnrolled} present (${pct}%)`);
      await loadAttendanceSectionInfo(attendanceFilters.date, attendanceFilters.classId, attendanceFilters.sectionId);
      await loadAttendanceSummary(attendanceFilters.date);
      await onRefresh();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Could not save attendance.';
      setAttendanceSaveError(msg);
    } finally {
      setSaving('');
    }
  };

  const submitAttendanceDay = async () => {
    try {
      setSaving('attendance-submit-day');
      setAttendanceSaveError('');
      await api.post('/attendance/submit-day', { date: attendanceFilters.date });
      setAttendanceToast(`Submitted attendance for ${attendanceSummary?.dateLabel || attendanceFilters.date}.`);
      await loadAttendanceSummary(attendanceFilters.date);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : (err as { response?: { data?: { message?: string } } })?.response?.data?.message || 'Could not submit attendance day.';
      setAttendanceSaveError(msg);
    } finally {
      setSaving('');
    }
  };

  const totalEnrolled = Number(attendanceSectionInfo?.totalEnrolled || 0);
  const presentCount = attendancePresentCount === '' ? NaN : Number(attendancePresentCount);
  const validPresent = Number.isFinite(presentCount) && presentCount >= 0 && presentCount <= totalEnrolled;
  const presentPct = validPresent && totalEnrolled > 0 ? Math.round((presentCount / totalEnrolled) * 100) : null;
  const absentCount = validPresent ? Math.max(totalEnrolled - presentCount, 0) : totalEnrolled;

  return (
    <ModuleShell
      title="Attendance"
      subtitle={`Today · ${attendanceSummary?.dateLabel || formatLongDate(attendanceFilters.date)} · ${Number(attendanceSummary?.overallPercent || 0).toFixed(1)}% overall`}
      actions={<button className="ck-btn ck-btn-g" disabled={!attendanceSummary?.allSubmitted || saving === 'attendance-submit-day'} onClick={submitAttendanceDay}>Submit today's attendance</button>}
    >
      {attendanceSummary?.nonWorkingDay ? <div className="ck-alert ck-alert-am" style={{ marginBottom: 16 }}><span>⚠</span><div>This is a non-working day. Attendance can still be recorded manually.</div></div> : null}
      {attendanceToast ? <div className="ck-alert ck-alert-g" style={{ marginBottom: 16 }}><span>✓</span><div>{attendanceToast}</div></div> : null}
      {attendanceSaveError ? <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}><span>!</span><div>{attendanceSaveError}</div></div> : null}
      <div className="ck-class-grid" style={{ marginBottom: 16 }}>
        {(attendanceSummary?.sections || []).map((row, i) => {
          const pct = row.presentPercent == null ? null : Number(row.presentPercent);
          return <div className="ck-class-card" key={i}>
            <div className="ck-cc-grade">{row.sectionName}</div>
            <div className="ck-cc-sec">{row.totalStudents} students</div>
            <div className="ck-cc-count">{pct == null ? '—' : `${Math.round(pct)}%`}</div>
            <div className="ck-mini-progress" style={{ marginTop: 8, marginBottom: 8 }}><div className="ck-mini-progress-fill" style={{ width: `${pct || 0}%` }} /></div>
            <div className="ck-cc-cl">{row.teacherName}</div>
            <div style={{ marginTop: 8 }}><span className={`ck-status ${row.status === 'Submitted' ? 'sg' : 'sam'}`}>{row.status}</span></div>
          </div>;
        })}
      </div>

      <div className="ck-card" style={{ marginBottom: 16 }}>
        <div className="ck-form-body">
          <div className="ck-form-grid ck-fg-4">
            <Field label="Date"><input type="date" value={attendanceFilters.date} onChange={(e) => void handleAttendanceDateChange(e.target.value)} /></Field>
            <Field label="Class"><select value={attendanceFilters.classId} onChange={(e) => void handleAttendanceClassChange(e.target.value)}><option value="">Select class</option>{attendanceClassOptions.map((row) => <option key={row.id} value={row.id}>{row.name}</option>)}</select></Field>
            <Field label="Section" hint={!attendanceFilters.classId ? 'Select a class first' : undefined}><select disabled={!attendanceFilters.classId} value={attendanceFilters.sectionId} onChange={(e) => void handleAttendanceSectionChange(e.target.value)}><option value="">Select section</option>{attendanceSectionOptions.map((row) => <option key={row.id} value={row.id}>{row.name}</option>)}</select></Field>
            <Field label="Total students"><input value={attendanceSectionInfo?.totalEnrolled ?? ''} readOnly /></Field>
          </div>
        </div>
      </div>

      {!attendanceFilters.date || !attendanceFilters.classId || !attendanceFilters.sectionId ? <div className="ck-import-zone" style={{ marginBottom: 16 }}>Select date, class and section to load student count</div> : null}
      {attendanceSectionInfo && totalEnrolled === 0 ? <div className="ck-alert ck-alert-am" style={{ marginBottom: 16 }}><span>i</span><div>No students enrolled in this section.</div></div> : null}
      {attendanceSectionInfo?.existingRecord ? <div className="ts" style={{ marginBottom: 12 }}>Previously saved — {Math.round(Number(attendanceSectionInfo.existingRecord.presentPercent || 0))}% present. You can update below.</div> : null}

      {attendanceSectionInfo && totalEnrolled > 0 ? <div className="ck-two-col" style={{ gridTemplateColumns: '280px 1fr', marginBottom: 16 }}>
        <div className="ck-card">
          <div className="ck-form-head">Present %</div>
          <div className="ck-form-body" style={{ background: 'var(--color-background-secondary, var(--ck-bg))' }}>
            <div style={{ fontSize: 32, fontWeight: 500, color: '#085041', lineHeight: 1.1 }}>{presentPct == null ? '—' : `${presentPct}%`}</div>
            <div className="ts" style={{ marginTop: 6 }}>{presentPct == null ? 'enter count below' : `${presentCount} present · ${absentCount} absent`}</div>
          </div>
        </div>
        <div className="ck-card">
          <div className="ck-form-head">No. of students present</div>
          <div className="ck-form-body">
            <div className="ts" style={{ marginBottom: 8 }}>Enter how many out of {totalEnrolled} students are present today</div>
            <div style={{ display: 'flex', gap: 8, alignItems: 'stretch' }}>
              <input style={{ fontSize: 22, flex: 1 }} type="number" min={0} max={totalEnrolled} value={attendancePresentCount} onChange={(e) => { setAttendancePresentCount(e.target.value); setAttendanceSaveError(''); setAttendanceToast(''); }} />
              <div style={{ display: 'grid', gap: 6 }}>
                <button className="ck-btn ck-btn-ghost" type="button" onClick={() => setAttendancePresentCount(String(Math.min(totalEnrolled, (Number(attendancePresentCount || 0) + 1))))}>▲</button>
                <button className="ck-btn ck-btn-ghost" type="button" onClick={() => setAttendancePresentCount(String(Math.max(0, (Number(attendancePresentCount || 0) - 1))))}>▼</button>
              </div>
            </div>
            <div className="ck-mini-progress" style={{ marginTop: 10 }}><div className="ck-mini-progress-fill" style={{ width: `${presentPct || 0}%`, background: '#1D9E75' }} /></div>
            <div className="ts" style={{ marginTop: 6 }}>{presentPct == null ? '—' : `${presentPct}% present — ${absentCount} absent`}</div>
            {!Number.isFinite(presentCount) || validPresent ? <div className="ts" style={{ marginTop: 6, color: '#085041' }}>{presentPct == null ? '' : `${absentCount} students will be marked absent`}</div> : <div className="ts" style={{ marginTop: 6, color: '#A32D2D' }}>Cannot exceed total ({totalEnrolled})</div>}
            <div style={{ marginTop: 12 }}><button className="ck-btn ck-btn-g" disabled={!validPresent || saving === 'attendance-save'} onClick={saveAttendanceEntry}>Save attendance</button></div>
          </div>
        </div>
      </div> : null}
    </ModuleShell>
  );
}
