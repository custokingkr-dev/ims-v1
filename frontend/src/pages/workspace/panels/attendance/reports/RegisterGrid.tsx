import type { AttendanceRegisterReport, AttendanceStatus } from '../../../../../types/attendance';

const LETTER: Record<AttendanceStatus, { text: string; cls: string }> = {
  PRESENT: { text: 'P', cls: 'ck-att-cell--present' },
  LATE: { text: 'L', cls: 'ck-att-cell--late' },
  LEAVE: { text: 'E', cls: 'ck-att-cell--leave' },
  ABSENT: { text: 'A', cls: 'ck-att-cell--absent' },
};

export function RegisterGrid({ report, loading }: { report: AttendanceRegisterReport | null; loading: boolean }) {
  if (loading) return <div style={{ padding: 24, color: 'var(--ink3)' }}>Loading register…</div>;
  if (!report || report.students.length === 0) {
    return <div className="ck-alert ck-alert-am"><span>i</span><div>No attendance recorded for this section and month.</div></div>;
  }
  return (
    <div className="ck-att-report-scroll">
      <table className="ck-att-grid">
        <thead>
          <tr>
            <th className="ck-att-grid-name-h">Student</th>
            {report.days.map((d) => <th key={d.date} title={d.weekday}>{d.dayOfMonth}</th>)}
            <th>P</th><th>L</th><th>E</th><th>A</th><th>%</th>
          </tr>
        </thead>
        <tbody>
          {report.students.map((s) => (
            <tr key={s.studentId}>
              <td className="ck-att-grid-name">{s.rollNo ? `${s.rollNo}. ` : ''}{s.fullName}</td>
              {s.cells.map((c) => {
                const l = c.status ? LETTER[c.status] : null;
                return <td key={c.date} className={l ? l.cls : ''}>{l ? l.text : ''}</td>;
              })}
              <td>{s.presentCount}</td><td>{s.lateCount}</td><td>{s.leaveCount}</td><td>{s.absentCount}</td>
              <td>{s.presentPercent}%</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <td className="ck-att-grid-name ck-att-grid-tot">Daily present</td>
            {report.dayTotals.map((dt) => <td key={dt.date}>{dt.presentCount + dt.lateCount}</td>)}
            <td>{report.totals.presentCount}</td><td>{report.totals.lateCount}</td>
            <td>{report.totals.leaveCount}</td><td>{report.totals.absentCount}</td>
            <td>{report.totals.presentPercent}%</td>
          </tr>
        </tfoot>
      </table>
    </div>
  );
}
