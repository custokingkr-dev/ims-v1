import type { AttendanceSummaryReport } from '../../../../../types/attendance';

export function SectionSummary({ report, loading }: { report: AttendanceSummaryReport | null; loading: boolean }) {
  if (loading) return <div style={{ padding: 24, color: 'var(--ink3)' }}>Loading summary…</div>;
  if (!report || report.sections.length === 0) {
    return <div className="ck-alert ck-alert-am"><span>i</span><div>No attendance recorded in this range.</div></div>;
  }
  return (
    <div className="ck-att-report-scroll">
      <table className="ck-att-table">
        <thead>
          <tr>
            <th>Section</th><th>Teacher</th>
            <th className="num">P</th><th className="num">L</th><th className="num">E</th><th className="num">A</th>
            <th className="num">Present%</th><th className="num">Days</th>
          </tr>
        </thead>
        <tbody>
          {report.sections.map((s) => (
            <tr key={s.sectionId}>
              <td>{s.sectionName}</td><td>{s.teacherName}</td>
              <td className="num">{s.presentCount}</td><td className="num">{s.lateCount}</td>
              <td className="num">{s.leaveCount}</td><td className="num">{s.absentCount}</td>
              <td className="num">{s.presentPercent}%</td><td className="num">{s.daysRecorded}</td>
            </tr>
          ))}
          <tr>
            <td><strong>Overall</strong></td><td />
            <td className="num"><strong>{report.overall.presentCount}</strong></td>
            <td className="num"><strong>{report.overall.lateCount}</strong></td>
            <td className="num"><strong>{report.overall.leaveCount}</strong></td>
            <td className="num"><strong>{report.overall.absentCount}</strong></td>
            <td className="num"><strong>{report.overall.presentPercent}%</strong></td><td />
          </tr>
        </tbody>
      </table>
    </div>
  );
}
