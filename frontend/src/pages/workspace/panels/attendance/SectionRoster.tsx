import type {
  SectionRegisterResponse,
  StudentEditRecord,
  EditableAttendanceStatus,
} from '../../../../types/attendance';
import { StudentAttendanceRow } from './StudentAttendanceRow';

interface Props {
  register: SectionRegisterResponse | null;
  records: StudentEditRecord[] | null;
  loading: boolean;
  saving: '' | 'save' | 'submit';
  readOnly?: boolean;
  onStatusChange: (studentId: number, status: EditableAttendanceStatus) => void;
  onRemarksChange: (studentId: number, remarks: string) => void;
  onMarkAllPresent: () => void;
  onMarkUnmarkedAbsent: () => void;
  onReset: () => void;
  onSave: () => void;
  onSubmit: () => void;
  onBack: () => void;
}

export function SectionRoster({
  register,
  records,
  loading,
  saving,
  readOnly = false,
  onStatusChange,
  onRemarksChange,
  onMarkAllPresent,
  onMarkUnmarkedAbsent,
  onReset,
  onSave,
  onSubmit,
  onBack,
}: Props) {
  const locked = register?.locked ?? false;
  const immutable = locked || readOnly;
  const list = records ?? [];
  const total = list.length;
  const present = list.filter((r) => r.status === 'PRESENT').length;
  const late = list.filter((r) => r.status === 'LATE').length;
  const leave = list.filter((r) => r.status === 'LEAVE').length;
  const absent = list.filter((r) => r.status === 'ABSENT').length;
  const unmarked = Math.max(0, total - present - late - leave - absent);
  const allMarked = total > 0 && list.every((r) => r.status !== null);
  const completionPercent = total > 0 ? Math.round(((total - unmarked) / total) * 100) : 0;

  const cells = [
    { label: 'Total', value: total },
    { label: 'Present', value: present },
    { label: 'Late', value: late },
    { label: 'Leave', value: leave },
    { label: 'Absent', value: absent },
    { label: 'Unmarked', value: unmarked },
  ];

  return (
    <div className="ck-att-roster">
      <div className="ck-att-roster-head">
        <div>
          <div className="ck-att-roster-title">{register?.sectionName || 'Section roster'}</div>
          <div className="ck-att-roster-meta">
            {total} students - {completionPercent}% marked
          </div>
        </div>
        <span className={`ck-status ${locked ? 'sapproved' : readOnly ? 'sneutral' : allMarked ? 'sinfo' : 'spending'}`}>
          {locked ? 'Submitted' : readOnly ? 'Read-only' : allMarked ? 'Ready' : 'Draft'}
        </span>
      </div>

      <div className="ck-att-roster-actions">
        <button type="button" className="ck-btn ck-btn-sm ck-btn-ghost ck-att-back" onClick={onBack}>
          Back to sections
        </button>
        {!immutable && (
          <>
            <button type="button" className="ck-btn ck-btn-sm" onClick={onMarkAllPresent}>
              Mark all Present
            </button>
            <button type="button" className="ck-btn ck-btn-sm ck-btn-ghost" onClick={onMarkUnmarkedAbsent} disabled={unmarked === 0}>
              Mark blank Absent
            </button>
            <button type="button" className="ck-btn ck-btn-sm ck-btn-ghost" onClick={onReset}>
              Reset
            </button>
          </>
        )}
      </div>

      <div className="ck-att-summary">
        {cells.map((c) => (
          <div key={c.label} className={`ck-att-summary-cell${c.label === 'Unmarked' && c.value > 0 ? ' ck-att-summary-cell--warn' : ''}`}>
            <div className="ck-att-summary-label">{c.label}</div>
            <div className="ck-att-summary-value">{c.value}</div>
          </div>
        ))}
      </div>

      {locked && (
        <div className="ck-alert ck-alert-am">
          <span>i</span>
          <div>This attendance is locked and cannot be edited.</div>
        </div>
      )}
      {!locked && readOnly && (
        <div className="ck-alert ck-alert-am">
          <span>i</span>
          <div>You have read-only attendance access for this section.</div>
        </div>
      )}

      {loading ? (
        <div className="ck-att-empty">Loading students...</div>
      ) : total === 0 ? (
        <div className="ck-alert ck-alert-am">
          <span>i</span>
          <div>No students enrolled in this section.</div>
        </div>
      ) : (
        <div className="ck-att-rows">
          {(register?.students ?? []).map((student) => {
            const record = list.find((r) => r.studentId === student.studentId);
            return (
              <StudentAttendanceRow
                key={student.studentId}
                student={student}
                status={record?.status ?? null}
                remarks={record?.remarks ?? ''}
                locked={immutable}
                onStatusChange={(s) => onStatusChange(student.studentId, s)}
                onRemarksChange={(r) => onRemarksChange(student.studentId, r)}
              />
            );
          })}
        </div>
      )}

      {!immutable && total > 0 && (
        <div className="ck-att-roster-footer">
          <button type="button" className="ck-btn ck-btn-b" onClick={onSave} disabled={saving === 'save'}>
            {saving === 'save' ? 'Saving...' : 'Save'}
          </button>
          <button
            type="button"
            className="ck-btn ck-btn-g"
            onClick={onSubmit}
            disabled={saving === 'submit' || !allMarked}
          >
            {saving === 'submit' ? 'Submitting...' : 'Submit Section'}
          </button>
        </div>
      )}
    </div>
  );
}
