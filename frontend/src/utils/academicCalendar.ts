export interface FinancialYear {
  startYear: number;
  endYear: number;
  label: string;
  academicYearId: string;
}

const DEFAULT_FINANCIAL_YEAR_START_MONTH = 4;

function normalizeMonth(startMonth = DEFAULT_FINANCIAL_YEAR_START_MONTH): number {
  return Number.isInteger(startMonth) && startMonth >= 1 && startMonth <= 12
    ? startMonth
    : DEFAULT_FINANCIAL_YEAR_START_MONTH;
}

export function financialYearStartYear(date = new Date(), startMonth = DEFAULT_FINANCIAL_YEAR_START_MONTH): number {
  const normalizedStartMonth = normalizeMonth(startMonth);
  return date.getMonth() + 1 >= normalizedStartMonth ? date.getFullYear() : date.getFullYear() - 1;
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

export function currentFinancialYear(date = new Date(), startMonth = DEFAULT_FINANCIAL_YEAR_START_MONTH): FinancialYear {
  return financialYearForStartYear(financialYearStartYear(date, startMonth));
}

export function currentFinancialYearLabel(date = new Date(), startMonth = DEFAULT_FINANCIAL_YEAR_START_MONTH): string {
  return currentFinancialYear(date, startMonth).label;
}

export function currentAcademicYearId(date = new Date(), startMonth = DEFAULT_FINANCIAL_YEAR_START_MONTH): string {
  return currentFinancialYear(date, startMonth).academicYearId;
}

export function financialYearOptions(
  count = 4,
  date = new Date(),
  startOffset = 0,
  startMonth = DEFAULT_FINANCIAL_YEAR_START_MONTH
): string[] {
  const firstYear = financialYearStartYear(date, startMonth) + startOffset;
  return Array.from({ length: count }, (_, index) => financialYearForStartYear(firstYear + index).label);
}

export function financialYearHistoryOptions(
  count = 3,
  date = new Date(),
  startMonth = DEFAULT_FINANCIAL_YEAR_START_MONTH
): string[] {
  return financialYearOptions(count, date, 1 - count, startMonth);
}
