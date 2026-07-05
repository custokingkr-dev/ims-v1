import type { StudentAttendanceRecord, EditableAttendanceStatus } from '../../../../types/attendance';

const PILLS: Array<{ code: Exclude<EditableAttendanceStatus, null>; label: string; mod: string }> = [
  { code: 'PRESENT', label: 'P', mod: 'present' },
  { code: 'LATE', label: 'L', mod: 'late' },
  { code: 'LEAVE', label: 'Ex', mod: 'leave' },
  { code: 'ABSENT', label: 'A', mod: 'absent' },
];

interface Props {
  student: StudentAttendanceRecord;
  status: EditableAttendanceStatus;
  remarks: string;
  locked: boolean;
  onStatusChange: (status: EditableAttendanceStatus) => void;
  onRemarksChange: (remarks: string) => void;
}

export function StudentAttendanceRow({
  student,
  status,
  remarks,
  locked,
  onStatusChange,
  onRemarksChange,
}: Props) {
  const initials = student.fullName
    .split(' ')
    .map((n) => n[0])
    .join('')
    .toUpperCase()
    .slice(0, 2);

  return (
    <div className="ck-att-row">
      <div className="ck-att-row-main">
        <div className="ck-user-avatar ck-att-avatar">{initials}</div>
        <div className="ck-att-row-info">
          <div className="ck-att-row-name">{student.fullName}</div>
          <div className="ck-att-row-meta">
            {student.admissionNo}
            {student.rollNo ? ` · Roll ${student.rollNo}` : ''}
          </div>
        </div>
        <div className="ck-att-pills" role="group" aria-label={`Attendance for ${student.fullName}`}>
          {PILLS.map((p) => {
            const active = status === p.code;
            const className = `ck-att-pill ck-att-pill--${p.mod}${active ? ' ck-att-pill--active' : ''}`;
            if (locked) {
              return (
                <span key={p.code} className={className} aria-pressed={active}>
                  {p.label}
                </span>
              );
            }
            return (
              <button
                key={p.code}
                type="button"
                className={className}
                aria-pressed={active}
                aria-label={p.code}
                onClick={() => onStatusChange(active ? null : p.code)}
              >
                {p.label}
              </button>
            );
          })}
        </div>
      </div>
      {!locked && (
        <input
          type="text"
          className="ck-att-remarks"
          placeholder="Remarks"
          value={remarks}
          onChange={(e) => onRemarksChange(e.target.value)}
        />
      )}
    </div>
  );
}
