import { describe, expect, it } from 'vitest';
import {
  currentAcademicYearId,
  currentFinancialYearLabel,
  financialYearHistoryOptions,
  financialYearOptions,
} from './academicCalendar';

describe('academic calendar', () => {
  it('uses April as the financial-year boundary', () => {
    const date = new Date('2026-04-01T00:00:00');

    expect(currentFinancialYearLabel(date)).toBe('2026-27');
    expect(currentAcademicYearId(date)).toBe('ay_2026_27');
  });

  it('keeps January through March in the previous financial year', () => {
    const date = new Date('2027-03-31T00:00:00');

    expect(currentFinancialYearLabel(date)).toBe('2026-27');
    expect(currentAcademicYearId(date)).toBe('ay_2026_27');
  });

  it('builds current/future and history option lists from the same source', () => {
    const date = new Date('2026-07-12T00:00:00');

    expect(financialYearOptions(3, date)).toEqual(['2026-27', '2027-28', '2028-29']);
    expect(financialYearHistoryOptions(3, date)).toEqual(['2024-25', '2025-26', '2026-27']);
  });

  it('supports school-specific financial-year start months', () => {
    const date = new Date('2026-05-31T00:00:00');

    expect(currentFinancialYearLabel(date, 6)).toBe('2025-26');
    expect(currentAcademicYearId(date, 6)).toBe('ay_2025_26');
    expect(financialYearOptions(2, date, 0, 6)).toEqual(['2025-26', '2026-27']);
  });
});
