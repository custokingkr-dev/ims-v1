export interface FinancialYear {
  startYear: number;
  endYear: number;
  label: string;
  academicYearId: string;
}

export function financialYearStartYear(date = new Date()): number {
  return date.getMonth() + 1 >= 4 ? date.getFullYear() : date.getFullYear() - 1;
}

export function financialYearForStartYear(startYear: number): FinancialYear {
  const endYear = startYear + 1;
  const suffix = String(endYear).slice(-2);
  return {
    startYear,
    endYear,
    label: `${startYear}-${suffix}`,
    academicYearId: `ay_${startYear}_${suffix}`,
  };
}

export function currentFinancialYear(date = new Date()): FinancialYear {
  return financialYearForStartYear(financialYearStartYear(date));
}

export function currentFinancialYearLabel(date = new Date()): string {
  return currentFinancialYear(date).label;
}

export function currentAcademicYearId(date = new Date()): string {
  return currentFinancialYear(date).academicYearId;
}

export function financialYearOptions(count = 4, date = new Date(), startOffset = 0): string[] {
  const firstYear = financialYearStartYear(date) + startOffset;
  return Array.from({ length: count }, (_, index) => financialYearForStartYear(firstYear + index).label);
}

export function financialYearHistoryOptions(count = 3, date = new Date()): string[] {
  return financialYearOptions(count, date, 1 - count);
}
