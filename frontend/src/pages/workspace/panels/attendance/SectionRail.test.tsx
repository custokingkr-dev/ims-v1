import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { afterEach, describe, it, expect, vi } from 'vitest';
import { SectionRail } from './SectionRail';
import type { AttendanceDailySummarySection } from '../../../../types/attendance';

afterEach(cleanup);

const sections: AttendanceDailySummarySection[] = [
  { sectionId: 's1', classId: 'c1', sectionName: 'Class 1-A', totalStudents: 4, presentCount: 1,
    lateCount: 1, leaveCount: 1, absentCount: 1, presentPercent: 66.7, teacherName: 'Ms Rao',
    status: 'Saved', locked: false },
  { sectionId: 's2', classId: 'c1', sectionName: 'Class 1-B', totalStudents: 3, presentCount: 3,
    lateCount: 0, leaveCount: 0, absentCount: 0, presentPercent: 100, teacherName: 'Mr Das',
    status: 'Submitted', locked: true },
];

describe('SectionRail', () => {
  it('renders the P·L·Ex·A counts and percent', () => {
    render(<SectionRail sections={sections} selectedSectionId={null} loading={false} onSelect={vi.fn()} />);
    expect(screen.getByText('P 1 · L 1 · Ex 1 · A 1')).toBeTruthy();
    expect(screen.getByText('67%')).toBeTruthy();
  });

  it('calls onSelect for an unlocked section but not a locked one', () => {
    const onSelect = vi.fn();
    render(<SectionRail sections={sections} selectedSectionId={null} loading={false} onSelect={onSelect} />);
    fireEvent.click(screen.getByText('Class 1-A'));
    expect(onSelect).toHaveBeenCalledWith(sections[0]);
    fireEvent.click(screen.getByText('Class 1-B'));
    expect(onSelect).toHaveBeenCalledTimes(1); // locked section is disabled
  });

  it('marks the selected item', () => {
    render(<SectionRail sections={sections} selectedSectionId="s1" loading={false} onSelect={vi.fn()} />);
    const selected = document.querySelector('.ck-att-rail-item--selected');
    expect(selected?.textContent).toContain('Class 1-A');
  });
});
