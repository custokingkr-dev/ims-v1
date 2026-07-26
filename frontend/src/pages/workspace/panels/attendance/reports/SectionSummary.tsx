import { useEffect, useState } from 'react';
import type { AttendanceSummaryReport } from '../../../../../types/attendance';
import { AttendancePagination } from '../AttendancePagination';

export function SectionSummary({ report, loading }: { report: AttendanceSummaryReport | null; loading: boolean }) {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);

  useEffect(() => {
    setPage(1);
  }, [report?.from, report?.to, pageSize]);

  if (loading) return <div className="ck-att-empty">Loading section summary...</div>;
  if (!report || report.sections.length === 0) {
    return <div className="ck-att-empty">No attendance was recorded in this date range.</div>;
  }

  const best = report.sections[0];
  const needsAttention = report.sections.filter((section) => section.presentPercent < 90).length;
  const recordedDays = Math.max(...report.sections.map((section) => section.daysRecorded), 0);
  const pageCount = Math.max(1, Math.ceil(report.sections.length / pageSize));
  const safePage = Math.min(page, pageCount);
  const visibleSections = report.sections.slice((safePage - 1) * pageSize, safePage * pageSize);
  const rankOffset = (safePage - 1) * pageSize;

  return (
    <>
      <div className="ck-att-kpi-band">
        <div className="ck-att-kpi"><div><span>Overall attendance</span><strong>{report.overall.presentPercent}%</strong><small>Selected date range</small></div></div>
        <div className="ck-att-kpi"><div><span>Recorded days</span><strong>{recordedDays}</strong><small>Maximum across sections</small></div></div>
        <div className="ck-att-kpi"><div><span>Best section</span><strong>{best.sectionName}</strong><small>{best.presentPercent}% attendance</small></div></div>
        <div className={`ck-att-kpi${needsAttention > 0 ? ' ck-att-kpi--alert' : ''}`}><div><span>Needs attention</span><strong>{needsAttention}</strong><small>Sections below 90%</small></div></div>
      </div>

      <div className="ck-att-report-card">
        <div className="ck-att-report-heading">
          <div>
            <strong>Section attendance</strong>
            <span>Ranked by attendance rate · Leave is excluded from the denominator</span>
          </div>
          <span className="ck-status sapproved">{report.sections.length} sections</span>
        </div>
        <div className="ck-att-table-scroll">
          <table className="ck-att-table">
            <thead>
              <tr>
                <th>Rank</th><th>Section</th><th>Teacher</th>
                <th className="num">Present</th><th className="num">Late</th>
                <th className="num">Leave</th><th className="num">Absent</th><th>Attendance</th>
              </tr>
            </thead>
            <tbody>
              {visibleSections.map((section, index) => (
                <tr key={section.sectionId}>
                  <td className="ck-att-rank">{String(rankOffset + index + 1).padStart(2, '0')}</td>
                  <td><strong>{section.sectionName}</strong><span className="ck-att-cell-sub">{section.daysRecorded} days recorded</span></td>
                  <td>{section.teacherName || '-'}</td>
                  <td className="num">{section.presentCount}</td>
                  <td className="num">{section.lateCount}</td>
                  <td className="num">{section.leaveCount}</td>
                  <td className="num">{section.absentCount}</td>
                  <td className="ck-att-rate-cell">
                    <strong>{section.presentPercent}%</strong>
                    <span><i style={{ width: `${Math.min(100, Math.max(0, section.presentPercent))}%` }} /></span>
                  </td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <td /><td><strong>Overall</strong></td><td />
                <td className="num"><strong>{report.overall.presentCount}</strong></td>
                <td className="num"><strong>{report.overall.lateCount}</strong></td>
                <td className="num"><strong>{report.overall.leaveCount}</strong></td>
                <td className="num"><strong>{report.overall.absentCount}</strong></td>
                <td><strong>{report.overall.presentPercent}%</strong></td>
              </tr>
            </tfoot>
          </table>
        </div>
        <AttendancePagination
          page={safePage}
          pageSize={pageSize}
          totalItems={report.sections.length}
          itemLabel="sections"
          onPageChange={setPage}
          onPageSizeChange={setPageSize}
        />
      </div>
    </>
  );
}
