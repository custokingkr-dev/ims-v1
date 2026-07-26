import { useEffect, useState } from 'react';
import type { AttendanceStatus, AttendanceStudentHistory } from '../../../../../types/attendance';
import { AttendancePagination } from '../AttendancePagination';

const STATUS: Record<AttendanceStatus, { label: string; className: string }> = {
  PRESENT: { label: 'Present', className: 'sapproved' },
  LATE: { label: 'Late', className: 'spending' },
  LEAVE: { label: 'Leave', className: 'sinfo' },
  ABSENT: { label: 'Absent', className: 'srejected' },
};

export function StudentHistory({ report, loading }: { report: AttendanceStudentHistory | null; loading: boolean }) {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);

  useEffect(() => {
    setPage(1);
  }, [report?.student.studentId, report?.from, report?.to, pageSize]);

  if (loading) return <div className="ck-att-empty">Loading student history...</div>;
  if (!report) return <div className="ck-att-empty">Choose a student to view attendance history.</div>;
  if (report.days.length === 0) return <div className="ck-att-empty">No attendance was recorded in this date range.</div>;

  const pageCount = Math.max(1, Math.ceil(report.days.length / pageSize));
  const safePage = Math.min(page, pageCount);
  const visibleDays = report.days.slice((safePage - 1) * pageSize, safePage * pageSize);
  const initials = report.student.fullName.split(' ').filter(Boolean).map((part) => part[0]).join('').slice(0, 2).toUpperCase();

  return (
    <div className="ck-att-history-card">
      <aside className="ck-att-history-profile">
        <span className="ck-att-history-avatar">{initials || 'ST'}</span>
        <strong>{report.student.fullName}</strong>
        <span>{report.student.admissionNo} · Roll {report.student.rollNo || '-'}</span>
        <span>{report.student.sectionName}</span>
        <div className="ck-att-history-rate">{report.presentPercent}%</div>
        <small>Attendance in selected range</small>
        <div className="ck-att-history-breakdown">
          <div><span>Present</span><strong>{report.presentCount}</strong></div>
          <div><span>Late</span><strong>{report.lateCount}</strong></div>
          <div><span>Leave</span><strong>{report.leaveCount}</strong></div>
          <div><span>Absent</span><strong>{report.absentCount}</strong></div>
        </div>
      </aside>
      <div className="ck-att-history-list">
        <div className="ck-att-report-heading">
          <div><strong>Attendance history</strong><span>{report.from} to {report.to}</span></div>
          <span className="ck-status sinfo">{report.daysRecorded} days</span>
        </div>
        <div className="ck-att-table-scroll">
          <table className="ck-att-table">
            <thead><tr><th>Date</th><th>Day</th><th>Status</th><th>Remarks</th></tr></thead>
            <tbody>
              {visibleDays.map((day) => {
                const status = day.status ? STATUS[day.status] : null;
                return (
                  <tr key={day.date}>
                    <td><strong>{day.date}</strong></td>
                    <td>{day.weekday}</td>
                    <td>{status ? <span className={`ck-status ${status.className}`}>{status.label}</span> : '-'}</td>
                    <td>{day.remarks || '-'}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <AttendancePagination
          page={safePage}
          pageSize={pageSize}
          totalItems={report.days.length}
          itemLabel="days"
          onPageChange={setPage}
          onPageSizeChange={setPageSize}
        />
      </div>
    </div>
  );
}
