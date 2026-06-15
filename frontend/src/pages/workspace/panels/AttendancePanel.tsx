import { useEffect, useState } from 'react';
import api from '../../../services/api';
import { useAuth } from '../../../contexts/AuthContext';
import { ModuleShell, Field } from '../ui';
import { todayIso } from '../utils';
import type { 
  AttendanceDailySummaryResponse, 
  AttendanceDailySummarySection,
  SectionRegisterResponse, 
  StudentAttendanceRecord 
} from '../../../types/attendance';

interface Props {
  onRefresh: () => Promise<void>;
  schoolScopedParams?: { schoolId: number };
}

export function AttendancePanel({ onRefresh, schoolScopedParams }: Props) {
  const { user } = useAuth();

  // Summary state
  const [attendanceSummary, setAttendanceSummary] = useState<AttendanceDailySummaryResponse>({
    dateLabel: '',
    overallPercent: 0,
    sections: [],
    allSubmitted: false,
    nonWorkingDay: false,
    date: '',
  });
  const [currentDate, setCurrentDate] = useState(todayIso());

  // Drawer state
  const [showDrawer, setShowDrawer] = useState(false);
  const [drawerData, setDrawerData] = useState<SectionRegisterResponse | null>(null);
  const [selectedSection, setSelectedSection] = useState<AttendanceDailySummarySection | null>(null);

  // Student attendance state (local edits)
  const [studentRecords, setStudentRecords] = useState<Array<{
    studentId: number;
    status: 'PRESENT' | 'ABSENT' | null;
    remarks: string;
  }> | null>(null);

  // UI state
  const [summaryLoading, setSummaryLoading] = useState(true);
  const [loadingDrawer, setLoadingDrawer] = useState(false);
  const [saving, setSaving] = useState('');
  const [toast, setToast] = useState('');
  const [error, setError] = useState('');

  const loadAttendanceSummary = async (dateValue: string) => {
    setSummaryLoading(true);
    try {
      const res = await api.get<AttendanceDailySummaryResponse>('/attendance/daily-summary', {
        params: { date: dateValue || 'today', ...(schoolScopedParams || {}) },
      });
      setAttendanceSummary(res.data || {
        dateLabel: '',
        overallPercent: 0,
        sections: [],
        allSubmitted: false,
        nonWorkingDay: false,
        date: dateValue,
      });
    } catch (err) {
      console.error('Failed to load attendance summary', err);
    } finally {
      setSummaryLoading(false);
    }
  };

  useEffect(() => {
    void loadAttendanceSummary(currentDate);
  }, [currentDate]);

  const handleDateChange = (dateValue: string) => {
    setCurrentDate(dateValue);
    setShowDrawer(false);
    setToast('');
    setError('');
  };

  /**
   * Open drawer for a section - load full register.
   */
  const openSectionDrawer = async (section: AttendanceDailySummarySection) => {
    try {
      setLoadingDrawer(true);
      setError('');
      setToast('');
      setSelectedSection(section);

      const res = await api.get<SectionRegisterResponse>('/attendance/section-register', {
        params: {
          date: currentDate,
          classId: section.classId,
          sectionId: section.sectionId,
        },
      });

      setDrawerData(res.data);

      // Initialize student records from API
      if (res.data?.students) {
        setStudentRecords(
          res.data.students.map((s) => ({
            studentId: s.studentId,
            status: (s.status as 'PRESENT' | 'ABSENT' | null) || null,
            remarks: s.remarks || '',
          }))
        );
      }

      setShowDrawer(true);
    } catch (err: unknown) {
      const msg =
        err instanceof Error
          ? err.message
          : (err as { response?: { data?: { message?: string } } })?.response?.data?.message ||
            'Failed to load section attendance';
      setError(msg);
      setShowDrawer(false);
    } finally {
      setLoadingDrawer(false);
    }
  };

  const closeSectionDrawer = () => {
    setShowDrawer(false);
    setDrawerData(null);
    setSelectedSection(null);
    setStudentRecords(null);
    setError('');
    setToast('');
  };

  /**
   * Update a single student's attendance status.
   */
  const updateStudentStatus = (
    studentId: number,
    status: 'PRESENT' | 'ABSENT' | null,
    remarks?: string
  ) => {
    setStudentRecords((prev) => {
      if (!prev) return prev;
      return prev.map((r) =>
        r.studentId === studentId ? { ...r, status, remarks: remarks ?? r.remarks } : r
      );
    });
  };

  /**
   * Mark all students as present.
   */
  const markAllPresent = () => {
    setStudentRecords((prev) => (prev ? prev.map((r) => ({ ...r, status: 'PRESENT' })) : prev));
    setToast('All students marked as present');
  };

  /**
   * Mark all students as absent.
   */
  const markAllAbsent = () => {
    setStudentRecords((prev) => (prev ? prev.map((r) => ({ ...r, status: 'ABSENT' })) : prev));
    setToast('All students marked as absent');
  };

  /**
   * Reset to original values.
   */
  const resetChanges = () => {
    if (drawerData?.students) {
      setStudentRecords(
        drawerData.students.map((s) => ({
          studentId: s.studentId,
          status: (s.status as 'PRESENT' | 'ABSENT' | null) || null,
          remarks: s.remarks || '',
        }))
      );
      setToast('Changes reset');
    }
  };

  /**
   * Save attendance records to backend.
   */
  const saveAttendanceRecords = async () => {
    if (!selectedSection || !studentRecords) return;

    try {
      setSaving('save');
      setError('');
      setToast('');

      // Filter out unmarked students (optional - adjust per requirements)
      const recordsToSave = studentRecords
        .filter((r) => r.status !== null)
        .map((r) => ({
          studentId: r.studentId,
          status: r.status as 'PRESENT' | 'ABSENT',
          remarks: r.remarks || '',
        }));

      await api.put<SectionRegisterResponse>('/attendance/section-register', {
        date: currentDate,
        classId: selectedSection.classId,
        sectionId: selectedSection.sectionId,
        records: recordsToSave,
      });

      setToast('Attendance saved successfully');

      // Reload drawer and summary
      await openSectionDrawer(selectedSection);
      await loadAttendanceSummary(currentDate);
      await onRefresh();
    } catch (err: unknown) {
      const msg =
        err instanceof Error
          ? err.message
          : (err as { response?: { data?: { message?: string } } })?.response?.data?.message ||
            'Failed to save attendance';
      setError(msg);
    } finally {
      setSaving('');
    }
  };

  /**
   * Submit section (lock it).
   */
  const submitSection = async () => {
    if (!selectedSection) return;

    if (
      !studentRecords ||
      studentRecords.some((r) => r.status === null)
    ) {
      setError('All students must have a status (Present or Absent) before submitting');
      return;
    }

    try {
      setSaving('submit');
      setError('');
      setToast('');

      // Save first to ensure attendance_daily row exists before locking
      await api.put('/attendance/section-register', {
        date: currentDate,
        classId: selectedSection.classId,
        sectionId: selectedSection.sectionId,
        records: studentRecords.map((r) => ({
          studentId: r.studentId,
          status: r.status as 'PRESENT' | 'ABSENT',
          remarks: r.remarks || '',
        })),
      });

      await api.post('/attendance/submit-section', {
        date: currentDate,
        classId: selectedSection.classId,
        sectionId: selectedSection.sectionId,
      });

      setToast('Section attendance locked');

      // Close drawer and reload
      closeSectionDrawer();
      await loadAttendanceSummary(currentDate);
      await onRefresh();
    } catch (err: unknown) {
      const msg =
        err instanceof Error
          ? err.message
          : (err as { response?: { data?: { message?: string } } })?.response?.data?.message ||
            'Failed to submit section';
      setError(msg);
    } finally {
      setSaving('');
    }
  };

  /**
   * Submit entire day (lock all sections).
   */
  const submitAttendanceDay = async () => {
    try {
      setSaving('submit-day');
      setError('');
      setToast('');

      await api.post('/attendance/submit-day', { date: currentDate });
      setToast(`Submitted attendance for ${attendanceSummary.dateLabel}`);

      await loadAttendanceSummary(currentDate);
      await onRefresh();
    } catch (err: unknown) {
      const msg =
        err instanceof Error
          ? err.message
          : (err as { response?: { data?: { message?: string } } })?.response?.data?.message ||
            'Could not submit attendance day';
      setError(msg);
    } finally {
      setSaving('');
    }
  };

  // Calculate stats from local student records
  const presentCount = studentRecords?.filter((r) => r.status === 'PRESENT').length || 0;
  const absentCount = studentRecords?.filter((r) => r.status === 'ABSENT').length || 0;
  const markedCount = presentCount + absentCount;
  const totalCount = studentRecords?.length || 0;
  const presentPercent =
    totalCount > 0 ? Math.round((presentCount / totalCount) * 100) : 0;

  return (
    <ModuleShell
      title="Attendance"
      subtitle={`Today · ${attendanceSummary.dateLabel} · ${Number(
        attendanceSummary.overallPercent || 0
      ).toFixed(1)}% overall`}
      actions={
        <button
          className="ck-btn ck-btn-g"
          disabled={!attendanceSummary.allSubmitted || saving === 'submit-day'}
          onClick={submitAttendanceDay}
        >
          Submit today's attendance
        </button>
      }
    >
      {attendanceSummary.nonWorkingDay ? (
        <div className="ck-alert ck-alert-am" style={{ marginBottom: 16 }}>
          <span>⚠</span>
          <div>This is a non-working day. Attendance can still be recorded.</div>
        </div>
      ) : null}

      {toast ? (
        <div className="ck-alert ck-alert-g" style={{ marginBottom: 16 }}>
          <span>✓</span>
          <div>{toast}</div>
        </div>
      ) : null}

      {error ? (
        <div className="ck-alert ck-alert-re" style={{ marginBottom: 16 }}>
          <span>!</span>
          <div>{error}</div>
        </div>
      ) : null}

      <div style={{ marginBottom: 16 }}>
        <Field label="Date">
          <input
            type="date"
            value={currentDate}
            onChange={(e) => handleDateChange(e.target.value)}
          />
        </Field>
      </div>

      {/* Grid of clickable class cards */}
      {summaryLoading ? (
        <div className="ck-class-grid" style={{ marginBottom: 16 }}>
          {[1,2,3,4,5,6].map(i => (
            <div key={i} className="ck-class-card" style={{ animationDelay: `${(i-1)*60}ms` }}>
              <div className="ck-skeleton ck-skeleton-title" style={{ marginBottom: 8 }} />
              <div className="ck-skeleton ck-skeleton-text" style={{ width: '60%', marginBottom: 12 }} />
              <div className="ck-skeleton" style={{ height: 32, borderRadius: 6, marginBottom: 8 }} />
              <div className="ck-skeleton" style={{ height: 6, borderRadius: 3 }} />
            </div>
          ))}
        </div>
      ) : (
        <div className="ck-class-grid" style={{ marginBottom: 16 }}>
          {(attendanceSummary.sections || []).map((section, idx) => {
            const pct = section.presentPercent ?? 0;
            const statusClass = section.status === 'Submitted' ? 'sapproved' : section.status === 'Saved' ? 'spending' : 'sneutral';

            return (
              <div
                key={idx}
                className="ck-class-card"
                style={{
                  cursor: section.locked ? 'not-allowed' : 'pointer',
                  opacity: section.locked ? 0.7 : 1,
                }}
                onClick={() => !section.locked && openSectionDrawer(section)}
              >
                <div className="ck-cc-grade">{section.sectionName}</div>
                <div className="ck-cc-sec">{section.totalStudents} students</div>
                <div className="ck-cc-count">{pct === 0 && section.status === 'Pending' ? '—' : `${Math.round(pct)}%`}</div>
                <div className="ck-mini-progress" style={{ marginTop: 8, marginBottom: 8 }}>
                  <div className="ck-mini-progress-fill" style={{ width: `${pct}%` }} />
                </div>
                <div className="ck-cc-cl">{section.teacherName}</div>
                <div style={{ marginTop: 8 }}>
                  <span className={`ck-status ${statusClass}`}>{section.status}</span>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* Drawer for student attendance marking */}
      {showDrawer && (
        <div
          style={{
            position: 'fixed',
            top: 0,
            right: 0,
            bottom: 0,
            width: 'min(100%, 600px)',
            backgroundColor: '#fff',
            boxShadow: '-2px 0 12px rgba(0,0,0,0.15)',
            zIndex: 1000,
            display: 'flex',
            flexDirection: 'column',
            overflow: 'hidden',
          }}
        >
          {/* Drawer header */}
          <div className="ck-drawer-header">
            <div>
              <div className="ck-drawer-title">{drawerData?.sectionName} Attendance</div>
              <div className="ck-drawer-subtitle">{drawerData?.date}</div>
            </div>
            <button className="ck-drawer-close" onClick={closeSectionDrawer}>✕</button>
          </div>

          {/* Drawer content */}
          <div style={{ flex: 1, overflowY: 'auto', padding: '16px' }}>
            {loadingDrawer ? (
              <div style={{ textAlign: 'center', padding: '32px 0', color: '#666' }}>
                Loading students...
              </div>
            ) : drawerData?.students && drawerData.students.length === 0 ? (
              <div className="ck-alert ck-alert-am">
                <span>i</span>
                <div>No students enrolled in this section.</div>
              </div>
            ) : (
              <>
                {/* Summary stats */}
                <div className="ck-stats ck-s3" style={{ marginBottom: 16 }}>
                  <div className="ck-metric-card ck-mc-blue">
                    <div className="ck-metric-card-label">Total</div>
                    <div className="ck-metric-card-value">{totalCount}</div>
                  </div>
                  <div className="ck-metric-card ck-mc-green">
                    <div className="ck-metric-card-label">Present</div>
                    <div className="ck-metric-card-value">{presentCount}</div>
                    <div className="ck-metric-card-sub">{presentPercent}%</div>
                  </div>
                  <div className="ck-metric-card ck-mc-red">
                    <div className="ck-metric-card-label">Absent</div>
                    <div className="ck-metric-card-value">{absentCount}</div>
                  </div>
                </div>

                {/* Quick actions */}
                <div
                  style={{
                    display: 'flex',
                    gap: 8,
                    marginBottom: 16,
                    flexWrap: 'wrap',
                  }}
                >
                  <button
                    className="ck-btn ck-btn-sm"
                    onClick={markAllPresent}
                    disabled={drawerData?.locked}
                  >
                    Mark all Present
                  </button>
                  <button
                    className="ck-btn ck-btn-sm"
                    onClick={markAllAbsent}
                    disabled={drawerData?.locked}
                  >
                    Mark all Absent
                  </button>
                  <button
                    className="ck-btn ck-btn-sm ck-btn-ghost"
                    onClick={resetChanges}
                    disabled={drawerData?.locked}
                  >
                    Reset
                  </button>
                </div>

                {drawerData?.locked && (
                  <div className="ck-alert ck-alert-am" style={{ marginBottom: 16 }}>
                    <span>🔒</span>
                    <div>This attendance is locked and cannot be edited.</div>
                  </div>
                )}

                {/* Student list */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {drawerData?.students?.map((originalStudent) => {
                    const record = studentRecords?.find((r) => r.studentId === originalStudent.studentId);
                    const status = record?.status || null;

                    return (
                      <div
                        key={originalStudent.studentId}
                        className="ck-card"
                        style={{ padding: '12px' }}
                      >
                        <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
                          {/* Avatar / Initials */}
                          <div className="ck-user-avatar" style={{ width: 36, height: 36, fontSize: 12 }}>
                            {originalStudent.fullName.split(' ').map(n => n[0]).join('').toUpperCase().slice(0, 2)}
                          </div>

                          {/* Student info */}
                          <div style={{ flex: 1, minWidth: 0 }}>
                            <div style={{ fontWeight: 500, fontSize: 13 }}>
                              {originalStudent.fullName}
                            </div>
                            <div style={{ fontSize: 11, color: '#666', marginTop: 2 }}>
                              {originalStudent.admissionNo}
                              {originalStudent.rollNo && ` · Roll ${originalStudent.rollNo}`}
                            </div>
                          </div>

                          {/* Status toggle */}
                          <div style={{ display: 'flex', gap: 4, flexShrink: 0 }}>
                            <button
                              className={`ck-btn ck-btn-sm ${
                                status === 'PRESENT' ? 'ck-btn-g' : 'ck-btn-ghost'
                              }`}
                              onClick={() =>
                                updateStudentStatus(
                                  originalStudent.studentId,
                                  status === 'PRESENT' ? null : 'PRESENT'
                                )
                              }
                              disabled={drawerData?.locked}
                            >
                              ✓
                            </button>
                            <button
                              className={`ck-btn ck-btn-sm ${
                                status === 'ABSENT' ? 'ck-btn-re' : 'ck-btn-ghost'
                              }`}
                              onClick={() =>
                                updateStudentStatus(
                                  originalStudent.studentId,
                                  status === 'ABSENT' ? null : 'ABSENT'
                                )
                              }
                              disabled={drawerData?.locked}
                            >
                              ✕
                            </button>
                          </div>
                        </div>

                        {/* Remarks field */}
                        {record && !drawerData?.locked && (
                          <input
                            type="text"
                            placeholder="Remarks"
                            value={record.remarks}
                            onChange={(e) =>
                              updateStudentStatus(
                                originalStudent.studentId,
                                record.status,
                                e.target.value
                              )
                            }
                            style={{
                              marginTop: 8,
                              padding: '6px',
                              fontSize: 11,
                              width: '100%',
                              border: '1px solid #d0d0d0',
                              borderRadius: 3,
                            }}
                          />
                        )}
                      </div>
                    );
                  })}
                </div>
              </>
            )}
          </div>

          {/* Drawer footer / action buttons */}
          {!drawerData?.locked && (
            <div
              style={{
                padding: '12px',
                borderTop: '1px solid #e0e0e0',
                display: 'flex',
                gap: 8,
                justifyContent: 'flex-end',
              }}
            >
              <button
                className="ck-btn ck-btn-ghost"
                onClick={closeSectionDrawer}
                disabled={saving !== ''}
              >
                Cancel
              </button>
              <button
                className="ck-btn ck-btn-b"
                onClick={saveAttendanceRecords}
                disabled={saving === 'save'}
              >
                {saving === 'save' ? 'Saving...' : 'Save'}
              </button>
              <button
                className="ck-btn ck-btn-g"
                onClick={submitSection}
                disabled={saving === 'submit' || markedCount !== totalCount}
              >
                {saving === 'submit' ? 'Submitting...' : 'Submit Section'}
              </button>
            </div>
          )}
        </div>
      )}

      {/* Overlay for drawer */}
      {showDrawer && (
        <div
          style={{
            position: 'fixed',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            backgroundColor: 'rgba(0,0,0,0.2)',
            zIndex: 999,
          }}
          onClick={closeSectionDrawer}
        />
      )}
    </ModuleShell>
  );
}
