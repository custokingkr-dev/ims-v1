import type { StudentAttendanceRecord, EditableAttendanceStatus } from '../../../../types/attendance';
import { StudentPhotoAvatar } from '../../../../features/students';

const STATUSES: Array<{
  code: Exclude<EditableAttendanceStatus, null>;
  label: string;
  title: string;
  mod: string;
}> = [
  { code: 'PRESENT', label: 'P', title: 'Present', mod: 'present' },
  { code: 'LATE', label: 'L', title: 'Late', mod: 'late' },
  { code: 'LEAVE', label: 'Ex', title: 'Excused leave', mod: 'leave' },
  { code: 'ABSENT', label: 'A', title: 'Absent', mod: 'absent' },
];

interface Props {
  student: StudentAttendanceRecord;
  status: EditableAttendanceStatus;
  remarks: string;
  locked: boolean;
  selected?: boolean;
  onSelectedChange?: (selected: boolean) => void;
  onStatusChange: (status: EditableAttendanceStatus) => void;
  onRemarksChange: (remarks: string) => void;
}

export function StudentAttendanceRow({
  student,
  status,
  remarks,
  locked,
  selected = false,
  onSelectedChange,
  onStatusChange,
  onRemarksChange,
}: Props) {
  return (
    <tr className={status ? `ck-att-student-row ck-att-student-row--${status.toLowerCase()}` : 'ck-att-student-row'}>
      <td className="ck-att-check-cell">
        {!locked && (
          <input
            type="checkbox"
            aria-label={`Select ${student.fullName}`}
            checked={selected}
            onChange={(event) => onSelectedChange?.(event.target.checked)}
          />
        )}
      </td>
      <td>
        <div className="ck-att-student-cell">
          <StudentPhotoAvatar
            photoUrl={student.photoUrl}
            name={student.fullName}
            className="ck-att-avatar"
            fallbackClassName="ck-att-avatar"
          />
          <div>
            <strong>{student.fullName}</strong>
            <span>{student.admissionNo}</span>
          </div>
        </div>
      </td>
      <td className="ck-att-roll">{student.rollNo || '-'}</td>
      <td>
        <div className="ck-att-pills" role="group" aria-label={`Attendance for ${student.fullName}`}>
          {STATUSES.map((item) => {
            const active = status === item.code;
            const className = `ck-att-pill ck-att-pill--${item.mod}${active ? ' ck-att-pill--active' : ''}`;
            if (locked) {
              return (
                <span key={item.code} className={className} aria-pressed={active} title={item.title}>
                  {item.label}
                </span>
              );
            }
            return (
              <button
                key={item.code}
                type="button"
                className={className}
                aria-pressed={active}
                aria-label={item.code}
                title={item.title}
                onClick={() => onStatusChange(active ? null : item.code)}
              >
                {item.label}
              </button>
            );
          })}
        </div>
      </td>
      <td>
        {locked ? (
          <span className="ck-att-remark-readonly">{remarks || '-'}</span>
        ) : (
          <input
            type="text"
            className="ck-att-remarks"
            aria-label={`Remarks for ${student.fullName}`}
            placeholder="Add remark"
            value={remarks}
            onChange={(event) => onRemarksChange(event.target.value)}
          />
        )}
      </td>
    </tr>
  );
}
