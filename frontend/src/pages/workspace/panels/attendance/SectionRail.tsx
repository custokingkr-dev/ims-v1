import type { AttendanceDailySummarySection } from '../../../../types/attendance';

interface Props {
  sections: AttendanceDailySummarySection[];
  selectedSectionId: string | null;
  loading: boolean;
  onSelect: (section: AttendanceDailySummarySection) => void;
}

export function SectionRail({ sections, selectedSectionId, loading, onSelect }: Props) {
  if (loading) {
    return (
      <div className="ck-att-rail">
        <div className="ck-att-rail-head"><strong>Sections</strong><span>Loading...</span></div>
        <div className="ck-att-rail-list">
        {[1, 2, 3, 4].map((i) => (
          <div key={i} className="ck-att-rail-item" style={{ animationDelay: `${(i - 1) * 60}ms` }}>
            <div className="ck-skeleton ck-skeleton-title" />
            <div className="ck-skeleton ck-skeleton-text" style={{ width: '60%' }} />
          </div>
        ))}
        </div>
      </div>
    );
  }

  if (sections.length === 0) {
    return (
      <div className="ck-att-rail">
        <div className="ck-att-rail-head"><strong>Sections</strong><span>0 available</span></div>
        <div className="ck-att-empty">No sections found for this date.</div>
      </div>
    );
  }

  return (
    <div className="ck-att-rail" role="list">
      <div className="ck-att-rail-head">
        <strong>Sections</strong>
        <span>{sections.filter((section) => !section.locked).length} open</span>
      </div>
      <div className="ck-att-rail-list">
        {sections.map((section) => {
          const selected = section.sectionId === selectedSectionId;
          const marked =
            Number(section.presentCount || 0) +
            Number(section.lateCount || 0) +
            Number(section.leaveCount || 0) +
            Number(section.absentCount || 0);
          const unmarked = Math.max(0, Number(section.totalStudents || 0) - marked);
          const pending = section.status === 'Pending';
          const completion = section.totalStudents > 0 ? Math.round((marked / section.totalStudents) * 100) : 0;
          const statusClass =
            section.status === 'Submitted' ? 'sapproved' : section.status === 'Saved' ? 'spending' : 'sneutral';
          const className =
            'ck-att-rail-item' +
            (selected ? ' ck-att-rail-item--selected' : '') +
            (section.locked ? ' ck-att-rail-item--locked' : '');

          return (
            <div key={section.sectionId} role="listitem">
              <button
                type="button"
                className={className}
                aria-current={selected}
                onClick={() => onSelect(section)}
              >
                <div className="ck-att-rail-top">
                  <div>
                    <div className="ck-att-rail-name">{section.sectionName}</div>
                    <div className="ck-att-rail-teacher">{section.teacherName || 'No teacher assigned'}</div>
                  </div>
                  <span className={`ck-status ${statusClass}`}>{section.status}</span>
                </div>
                <div className="ck-att-rail-figures">
                  <span className="ck-att-rail-pct">
                    {pending && marked === 0 ? '--' : `${Math.round(section.presentPercent)}%`}
                  </span>
                  <span className="ck-att-rail-progress">{marked}/{section.totalStudents} marked</span>
                </div>
                <div className="ck-att-rail-progress-bar"><span style={{ width: `${completion}%` }} /></div>
                <div className="ck-att-counts">
                  P {section.presentCount} - L {section.lateCount} - Ex {section.leaveCount} - A {section.absentCount}
                  {unmarked > 0 ? ` - ${unmarked} blank` : ''}
                </div>
              </button>
            </div>
          );
        })}
      </div>
    </div>
  );
}
