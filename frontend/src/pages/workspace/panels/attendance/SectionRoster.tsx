import { CheckCheck, LockKeyhole, RotateCcw, Save, Search } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import type {
  SectionRegisterResponse,
  StudentEditRecord,
  EditableAttendanceStatus,
} from '../../../../types/attendance';
import { AttendancePagination } from './AttendancePagination';
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
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());

  const locked = register?.locked ?? false;
  const immutable = locked || readOnly;
  const list = records ?? [];
  const students = register?.students ?? [];
  const total = list.length;
  const present = list.filter((record) => record.status === 'PRESENT').length;
  const late = list.filter((record) => record.status === 'LATE').length;
  const leave = list.filter((record) => record.status === 'LEAVE').length;
  const absent = list.filter((record) => record.status === 'ABSENT').length;
  const unmarked = Math.max(0, total - present - late - leave - absent);
  const allMarked = total > 0 && list.every((record) => record.status !== null);
  const completionPercent = total > 0 ? Math.round(((total - unmarked) / total) * 100) : 0;
  const dirtyCount = list.filter((record) => {
    const original = students.find((student) => student.studentId === record.studentId);
    return original && (original.status !== record.status || (original.remarks || '') !== record.remarks);
  }).length;

  const filteredStudents = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    if (!normalized) return students;
    return students.filter((student) =>
      student.fullName.toLowerCase().includes(normalized)
      || student.admissionNo.toLowerCase().includes(normalized)
      || String(student.rollNo || '').toLowerCase().includes(normalized)
    );
  }, [query, students]);

  const pageCount = Math.max(1, Math.ceil(filteredStudents.length / pageSize));
  const safePage = Math.min(page, pageCount);
  const visibleStudents = filteredStudents.slice((safePage - 1) * pageSize, safePage * pageSize);
  const visibleIds = visibleStudents.map((student) => student.studentId);
  const allVisibleSelected = visibleIds.length > 0 && visibleIds.every((id) => selectedIds.has(id));

  useEffect(() => {
    setQuery('');
    setPage(1);
    setSelectedIds(new Set());
  }, [register?.sectionId]);

  useEffect(() => {
    setPage(1);
  }, [query, pageSize]);

  const setSelected = (studentId: number, selected: boolean) => {
    setSelectedIds((current) => {
      const next = new Set(current);
      if (selected) next.add(studentId);
      else next.delete(studentId);
      return next;
    });
  };

  const selectVisible = (selected: boolean) => {
    setSelectedIds((current) => {
      const next = new Set(current);
      visibleIds.forEach((id) => {
        if (selected) next.add(id);
        else next.delete(id);
      });
      return next;
    });
  };

  const markSelected = (status: Exclude<EditableAttendanceStatus, null>) => {
    selectedIds.forEach((studentId) => onStatusChange(studentId, status));
    setSelectedIds(new Set());
  };

  return (
    <div className="ck-att-roster">
      {locked && (
        <div className="ck-alert ck-alert-am">
          <span>i</span>
          <div>This section is submitted and locked.</div>
        </div>
      )}
      {!locked && readOnly && (
        <div className="ck-alert ck-alert-am">
          <span>i</span>
          <div>You have read-only access to this register.</div>
        </div>
      )}

      <div className="ck-att-register-card">
        <div className="ck-att-register-toolbar">
          <div className="ck-att-roster-title">
            <button type="button" className="ck-att-button ck-att-back" onClick={onBack}>Back to sections</button>
            <div>
              <strong>{register?.sectionName || 'Section register'}</strong>
              <span>
                {total} students · {completionPercent}% marked · P {present} · L {late} · Ex {leave} · A {absent}
              </span>
            </div>
            <span className={`ck-status ${locked ? 'sapproved' : readOnly ? 'sneutral' : allMarked ? 'sinfo' : 'spending'}`}>
              {locked ? 'Submitted' : readOnly ? 'Read-only' : allMarked ? 'Ready' : 'Draft'}
            </span>
          </div>
          <div className="ck-att-register-tools">
            <div className="ck-att-search">
              <Search size={16} />
              <input
                type="search"
                aria-label="Search this section"
                placeholder="Search this section"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
              />
            </div>
            {!immutable && (
              <div className="ck-att-register-actions">
                <button type="button" className="ck-att-button" onClick={onMarkAllPresent}>
                  <CheckCheck size={16} />
                  Mark all Present
                </button>
                <button
                  type="button"
                  className="ck-att-button"
                  disabled={unmarked === 0}
                  onClick={onMarkUnmarkedAbsent}
                >
                  Mark blanks Absent
                </button>
                <button type="button" className="ck-att-icon-button" aria-label="Reset changes" title="Reset changes" onClick={onReset}>
                  <RotateCcw size={16} />
                </button>
              </div>
            )}
          </div>
        </div>

        {selectedIds.size > 0 && !immutable && (
          <div className="ck-att-bulk-bar">
            <strong>{selectedIds.size} selected</strong>
            <div>
              <button type="button" onClick={() => markSelected('PRESENT')}>Present</button>
              <button type="button" onClick={() => markSelected('LATE')}>Late</button>
              <button type="button" onClick={() => markSelected('LEAVE')}>Leave</button>
              <button type="button" onClick={() => markSelected('ABSENT')}>Absent</button>
              <button type="button" onClick={() => setSelectedIds(new Set())}>Clear</button>
            </div>
          </div>
        )}

        {loading ? (
          <div className="ck-att-empty">Loading students...</div>
        ) : total === 0 ? (
          <div className="ck-att-empty">No students are enrolled in this section.</div>
        ) : filteredStudents.length === 0 ? (
          <div className="ck-att-empty">No students match your search.</div>
        ) : (
          <>
            <div className="ck-att-table-scroll">
              <table className="ck-att-roster-table">
                <thead>
                  <tr>
                    <th className="ck-att-check-cell">
                      {!immutable && (
                        <input
                          type="checkbox"
                          aria-label="Select students on this page"
                          checked={allVisibleSelected}
                          onChange={(event) => selectVisible(event.target.checked)}
                        />
                      )}
                    </th>
                    <th>Student</th>
                    <th>Roll</th>
                    <th>Attendance status</th>
                    <th>Remarks</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleStudents.map((student) => {
                    const record = list.find((item) => item.studentId === student.studentId);
                    return (
                      <StudentAttendanceRow
                        key={student.studentId}
                        student={student}
                        status={record?.status ?? null}
                        remarks={record?.remarks ?? ''}
                        locked={immutable}
                        selected={selectedIds.has(student.studentId)}
                        onSelectedChange={(selected) => setSelected(student.studentId, selected)}
                        onStatusChange={(status) => onStatusChange(student.studentId, status)}
                        onRemarksChange={(remarks) => onRemarksChange(student.studentId, remarks)}
                      />
                    );
                  })}
                </tbody>
              </table>
            </div>
            <AttendancePagination
              page={safePage}
              pageSize={pageSize}
              totalItems={filteredStudents.length}
              itemLabel="students"
              pageSizeOptions={[10, 20, 50]}
              onPageChange={setPage}
              onPageSizeChange={setPageSize}
            />
          </>
        )}
      </div>

      {!immutable && total > 0 && (
        <div className="ck-att-save-bar">
          <div className="ck-att-save-copy">
            <span className={dirtyCount > 0 ? 'ck-att-save-dot ck-att-save-dot--dirty' : 'ck-att-save-dot'} />
            <div>
              <strong>{dirtyCount > 0 ? `${dirtyCount} unsaved change${dirtyCount === 1 ? '' : 's'}` : 'Draft is up to date'}</strong>
              <span>{unmarked} unmarked · all students must be marked before submission</span>
            </div>
          </div>
          <div className="ck-att-save-actions">
            <button type="button" className="ck-att-button" onClick={onSave} disabled={saving === 'save'}>
              <Save size={16} />
              {saving === 'save' ? 'Saving...' : 'Save'}
            </button>
            <button type="button" className="ck-att-button ck-att-button--primary" onClick={onSubmit} disabled={saving === 'submit' || !allMarked}>
              <LockKeyhole size={16} />
              {saving === 'submit' ? 'Submitting...' : 'Submit Section'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
