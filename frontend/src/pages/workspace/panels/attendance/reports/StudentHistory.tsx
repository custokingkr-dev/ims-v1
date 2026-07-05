import type { AttendanceStudentHistory } from '../../../../../types/attendance';

export function StudentHistory({ report, loading }: { report: AttendanceStudentHistory | null; loading: boolean }) {
  if (loading) return <div style={{ padding: 24, color: 'var(--ink3)' }}>Loading history…</div>;
  if (!report) return <div className="ck-alert ck-alert-am"><span>i</span><div>Pick a student to see their attendance.</div></div>;
  if (report.days.length === 0) {
    return <div className="ck-alert ck-alert-am"><span>i</span><div>No attendance recorded in this range.</div></div>;
  }
  return (
    <div>
      <div className="ck-att-summary" style={{ marginBottom: 16 }}>
        {[
          { label: 'Present%', value: `${report.presentPercent}%` },
          { label: 'Present', value: report.presentCount },
          { label: 'Late', value: report.lateCount },
          { label: 'Leave', value: report.leaveCount },
          { label: 'Absent', value: report.absentCount },
        ].map((c) => (
          <div key={c.label} className="ck-att-summary-cell">
            <div className="ck-att-summary-label">{c.label}</div>
            <div className="ck-att-summary-value">{c.value}</div>
          </div>
        ))}
      </div>
      <div className="ck-att-report-scroll">
        <table className="ck-att-table">
          <thead><tr><th>Date</th><th>Day</th><th>Status</th><th>Remarks</th></tr></thead>
          <tbody>
            {report.days.map((d) => (
              <tr key={d.date}><td>{d.date}</td><td>{d.weekday}</td><td>{d.status ?? ''}</td><td>{d.remarks}</td></tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
