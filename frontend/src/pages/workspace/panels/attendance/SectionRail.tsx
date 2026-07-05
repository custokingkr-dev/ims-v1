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
        {[1, 2, 3, 4].map((i) => (
          <div key={i} className="ck-att-rail-item" style={{ animationDelay: `${(i - 1) * 60}ms` }}>
            <div className="ck-skeleton ck-skeleton-title" />
            <div className="ck-skeleton ck-skeleton-text" style={{ width: '60%' }} />
          </div>
        ))}
      </div>
    );
  }

  if (sections.length === 0) {
    return (
      <div className="ck-att-rail">
        <div style={{ padding: '24px 8px', color: 'var(--ink3)', textAlign: 'center' }}>
          No sections found for this date.
        </div>
      </div>
    );
  }

  return (
    <div className="ck-att-rail" role="list">
      {sections.map((section) => {
        const selected = section.sectionId === selectedSectionId;
        const pending = section.status === 'Pending';
        const statusClass =
          section.status === 'Submitted' ? 'sapproved' : section.status === 'Saved' ? 'spending' : 'sneutral';
        const className =
          'ck-att-rail-item' +
          (selected ? ' ck-att-rail-item--selected' : '') +
          (section.locked ? ' ck-att-rail-item--locked' : '');
        return (
          <button
            key={section.sectionId}
            type="button"
            role="listitem"
            className={className}
            aria-current={selected}
            disabled={section.locked}
            onClick={() => !section.locked && onSelect(section)}
          >
            <div className="ck-att-rail-name">{section.sectionName}</div>
            <div className="ck-att-rail-teacher">{section.teacherName}</div>
            <div className="ck-att-rail-figures">
              <span className="ck-att-rail-pct">
                {pending ? '—' : `${Math.round(section.presentPercent)}%`}
              </span>
              <span className={`ck-status ${statusClass}`}>{section.status}</span>
            </div>
            <div className="ck-att-counts">
              {`P ${section.presentCount} · L ${section.lateCount} · Ex ${section.leaveCount} · A ${section.absentCount}`}
            </div>
          </button>
        );
      })}
    </div>
  );
}
