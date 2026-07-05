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
  onStatusChange: (studentId: number, status: EditableAttendanceStatus) => void;
  onRemarksChange: (studentId: number, remarks: string) => void;
  onMarkAllPresent: () => void;
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
  onStatusChange,
  onRemarksChange,
  onMarkAllPresent,
  onReset,
  onSave,
  onSubmit,
  onBack,
}: Props) {
  const locked = register?.locked ?? false;
  const list = records ?? [];
  const total = list.length;
  const present = list.filter((r) => r.status === 'PRESENT').length;
  const late = list.filter((r) => r.status === 'LATE').length;
  const leave = list.filter((r) => r.status === 'LEAVE').length;
  const absent = list.filter((r) => r.status === 'ABSENT').length;
  const allMarked = total > 0 && list.every((r) => r.status !== null);

  const cells = [
    { label: 'Total', value: total },
    { label: 'Present', value: present },
    { label: 'Late', value: late },
    { label: 'Leave', value: leave },
    { label: 'Absent', value: absent },
  ];

  return (
    <div className="ck-att-roster">
      <div className="ck-att-roster-actions">
        <button type="button" className="ck-btn ck-btn-sm ck-btn-ghost ck-att-back" onClick={onBack}>
          ← Sections
        </button>
        {!locked && (
          <>
            <button type="button" className="ck-btn ck-btn-sm" onClick={onMarkAllPresent}>
              Mark all Present
            </button>
            <button type="button" className="ck-btn ck-btn-sm ck-btn-ghost" onClick={onReset}>
              Reset
            </button>
          </>
        )}
      </div>

      <div className="ck-att-summary">
        {cells.map((c) => (
          <div key={c.label} className="ck-att-summary-cell">
            <div className="ck-att-summary-label">{c.label}</div>
            <div className="ck-att-summary-value">{c.value}</div>
          </div>
        ))}
      </div>

      {locked && (
        <div className="ck-alert ck-alert-am">
          <span>🔒</span>
          <div>This attendance is locked and cannot be edited.</div>
        </div>
      )}

      {loading ? (
        <div style={{ textAlign: 'center', padding: '24px 0', color: 'var(--ink3)' }}>Loading students…</div>
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
                locked={locked}
                onStatusChange={(s) => onStatusChange(student.studentId, s)}
                onRemarksChange={(r) => onRemarksChange(student.studentId, r)}
              />
            );
          })}
        </div>
      )}

      {!locked && total > 0 && (
        <div className="ck-att-roster-footer">
          <button type="button" className="ck-btn ck-btn-b" onClick={onSave} disabled={saving === 'save'}>
            {saving === 'save' ? 'Saving…' : 'Save'}
          </button>
          <button
            type="button"
            className="ck-btn ck-btn-g"
            onClick={onSubmit}
            disabled={saving === 'submit' || !allMarked}
          >
            {saving === 'submit' ? 'Submitting…' : 'Submit Section'}
          </button>
        </div>
      )}
    </div>
  );
}
