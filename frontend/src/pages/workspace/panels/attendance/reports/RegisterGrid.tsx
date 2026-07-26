import { useEffect, useState } from 'react';
import type { AttendanceRegisterReport, AttendanceStatus } from '../../../../../types/attendance';
import { AttendancePagination } from '../AttendancePagination';

const LETTER: Record<AttendanceStatus, { text: string; cls: string; title: string }> = {
  PRESENT: { text: 'P', cls: 'ck-att-cell--present', title: 'Present' },
  LATE: { text: 'L', cls: 'ck-att-cell--late', title: 'Late' },
  LEAVE: { text: 'E', cls: 'ck-att-cell--leave', title: 'Excused leave' },
  ABSENT: { text: 'A', cls: 'ck-att-cell--absent', title: 'Absent' },
};

export function RegisterGrid({ report, loading }: { report: AttendanceRegisterReport | null; loading: boolean }) {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);

  useEffect(() => {
    setPage(1);
  }, [report?.month, report?.sectionId, pageSize]);

  if (loading) return <div className="ck-att-empty">Loading monthly register...</div>;
  if (!report || report.students.length === 0) {
    return <div className="ck-att-empty">Choose a class and section to view the monthly register.</div>;
  }

  const pageCount = Math.max(1, Math.ceil(report.students.length / pageSize));
  const safePage = Math.min(page, pageCount);
  const visibleStudents = report.students.slice((safePage - 1) * pageSize, safePage * pageSize);

  return (
    <div className="ck-att-report-card">
      <div className="ck-att-report-heading">
        <div>
          <strong>{report.sectionName} · {report.monthLabel}</strong>
          <span>{report.teacherName || 'No class teacher assigned'} · Leave is excluded from attendance percentage</span>
        </div>
        <span className="ck-status sinfo">{report.totals.presentPercent}% attendance</span>
      </div>
      <div className="ck-att-report-scroll">
        <table className="ck-att-grid">
          <thead>
            <tr>
              <th className="ck-att-grid-name-h">Student</th>
              {report.days.map((day) => (
                <th key={day.date} className={day.nonWorkingDay ? 'ck-att-day-off' : ''} title={day.weekday}>
                  <span>{day.dayOfMonth}</span>
                  <small>{day.weekday.slice(0, 1)}</small>
                </th>
              ))}
              <th>P</th><th>L</th><th>E</th><th>A</th><th>%</th>
            </tr>
          </thead>
          <tbody>
            {visibleStudents.map((student) => (
              <tr key={student.studentId}>
                <td className="ck-att-grid-name">
                  <strong>{student.fullName}</strong>
                  <span>{student.rollNo ? `Roll ${student.rollNo}` : student.admissionNo}</span>
                </td>
                {student.cells.map((cell, index) => {
                  const letter = cell.status ? LETTER[cell.status] : null;
                  return (
                    <td
                      key={cell.date}
                      className={`${letter ? letter.cls : ''}${report.days[index]?.nonWorkingDay ? ' ck-att-day-off' : ''}`}
                      title={letter?.title}
                    >
                      {letter?.text || ''}
                    </td>
                  );
                })}
                <td>{student.presentCount}</td><td>{student.lateCount}</td>
                <td>{student.leaveCount}</td><td>{student.absentCount}</td>
                <td><strong>{student.presentPercent}%</strong></td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <td className="ck-att-grid-name ck-att-grid-tot">Daily attended</td>
              {report.dayTotals.map((total, index) => (
                <td key={total.date} className={report.days[index]?.nonWorkingDay ? 'ck-att-day-off' : ''}>
                  {total.presentCount + total.lateCount}
                </td>
              ))}
              <td>{report.totals.presentCount}</td><td>{report.totals.lateCount}</td>
              <td>{report.totals.leaveCount}</td><td>{report.totals.absentCount}</td>
              <td>{report.totals.presentPercent}%</td>
            </tr>
          </tfoot>
        </table>
      </div>
      <AttendancePagination
        page={safePage}
        pageSize={pageSize}
        totalItems={report.students.length}
        itemLabel="students"
        onPageChange={setPage}
        onPageSizeChange={setPageSize}
      />
    </div>
  );
}
