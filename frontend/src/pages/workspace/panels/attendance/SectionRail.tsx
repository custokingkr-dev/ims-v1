import { Search } from 'lucide-react';
import { useMemo, useState } from 'react';
import type { AttendanceDailySummarySection } from '../../../../types/attendance';

interface Props {
  sections: AttendanceDailySummarySection[];
  selectedSectionId: string | null;
  loading: boolean;
  onSelect: (section: AttendanceDailySummarySection) => void;
}

export function SectionRail({ sections, selectedSectionId, loading, onSelect }: Props) {
  const [query, setQuery] = useState('');
  const visibleSections = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    if (!normalized) return sections;

    return sections.filter((section) =>
      section.sectionName.toLowerCase().includes(normalized)
      || (section.teacherName || '').toLowerCase().includes(normalized)
      || section.status.toLowerCase().includes(normalized)
    );
  }, [query, sections]);

  if (loading) {
    return (
      <aside className="ck-att-rail" aria-label="Attendance sections">
        <div className="ck-att-rail-head"><strong>Sections</strong><span>Loading...</span></div>
        <div className="ck-att-rail-list">
          {[1, 2, 3, 4].map((i) => (
            <div key={i} className="ck-att-rail-item" style={{ animationDelay: `${(i - 1) * 60}ms` }}>
              <div className="ck-skeleton ck-skeleton-title" />
              <div className="ck-skeleton ck-skeleton-text" style={{ width: '60%' }} />
            </div>
          ))}
        </div>
      </aside>
    );
  }

  if (sections.length === 0) {
    return (
      <aside className="ck-att-rail" aria-label="Attendance sections">
        <div className="ck-att-rail-head"><strong>Sections</strong><span>0 available</span></div>
        <div className="ck-att-empty">No sections found for this date.</div>
      </aside>
    );
  }

  return (
    <aside className="ck-att-rail" aria-label="Attendance sections">
      <div className="ck-att-rail-head">
        <div>
          <strong>Sections</strong>
          <span>{sections.filter((section) => !section.locked).length} open</span>
        </div>
        <span>{sections.length} total</span>
      </div>
      <label className="ck-att-rail-search">
        <Search size={14} />
        <input
          type="search"
          aria-label="Filter sections"
          placeholder="Filter sections"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
      </label>
      <div className="ck-att-rail-list" role="list">
        {visibleSections.map((section) => {
          const selected = section.sectionId === selectedSectionId;
          const marked =
            Number(section.presentCount || 0) +
            Number(section.lateCount || 0) +
            Number(section.leaveCount || 0) +
            Number(section.absentCount || 0);
          const completion = section.totalStudents > 0 ? Math.round((marked / section.totalStudents) * 100) : 0;
          const presentPercent = section.status === 'Pending' && marked === 0
            ? '--'
            : `${Math.round(section.presentPercent)}%`;
          const className =
            'ck-att-rail-item'
            + (selected ? ' ck-att-rail-item--selected' : '')
            + (section.locked ? ' ck-att-rail-item--locked' : '');

          return (
            <div key={section.sectionId} role="listitem">
              <button
                type="button"
                className={className}
                aria-current={selected}
                title={section.teacherName ? `Teacher: ${section.teacherName}` : 'No teacher assigned'}
                onClick={() => onSelect(section)}
              >
                <div className="ck-att-rail-top">
                  <span className="ck-att-rail-name">{section.sectionName}</span>
                  <strong className="ck-att-rail-pct">{presentPercent}</strong>
                </div>
                <div className="ck-att-rail-meta">
                  {marked} of {section.totalStudents} marked <span aria-hidden="true">·</span> {section.status}
                </div>
                <div className="ck-att-rail-progress-bar" aria-hidden="true">
                  <span style={{ width: `${completion}%` }} />
                </div>
              </button>
            </div>
          );
        })}
        {visibleSections.length === 0 && (
          <div className="ck-att-rail-empty">No sections match your filter.</div>
        )}
      </div>
    </aside>
  );
}
