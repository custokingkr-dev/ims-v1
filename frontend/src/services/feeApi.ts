import api from './api';

export interface FeeInstallment {
  id?: number;
  label: string;
  dueDate: string;
  sharePercent: number;
  sortOrder?: number;
}

export interface FeeItemModel {
  id: string;
  name: string;
  frequency: string;
  amount: number;
  optional: boolean;
}

export interface FeeBandModel {
  id: string;
  name: string;
  classFrom: number;
  classTo: number;
  discount: number;
  annualTotal: number;
  activeSchedules: string[];
  items: FeeItemModel[];
  installments: FeeInstallment[];
  assignmentCount: number;
  academicYearId: string;
  academicYear: string;
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  revision: number;
  publishedAt?: string | null;
  gracePeriodDays: number;
  lateFeeType: 'NONE' | 'FIXED' | 'DAILY';
  lateFeeAmount: number;
  lateFeeIntervalDays: number;
  supersedesBandId?: string | null;
}

export interface FeeStructureModel {
  academicYearId: string;
  academicYear: string;
  bands: FeeBandModel[];
}

export interface FeeConfigurationHealth {
  academicYearId: string;
  totalPlans: number;
  publishedPlans: number;
  draftPlans: number;
  missingFeeHeads: number;
  invalidInstallments: number;
  blockingIssues: number;
  ready: boolean;
}

export interface FeeDiscountRule {
  id: number;
  name: string;
  ruleType: string;
  percentage: number;
  priority: number;
  active: boolean;
  academicYearId: string;
}

export const getFeeStructure = (academicYearId?: string, schoolId?: number) =>
  api.get<FeeStructureModel>('/fee-structure', { params: { academicYearId, schoolId } });

export const createFeeBand = (body: Record<string, unknown>) =>
  api.post<FeeBandModel>('/fee-structure/band', body);

export const updateFeeBand = (id: string, body: Record<string, unknown>) =>
  api.put<FeeBandModel>(`/fee-structure/band/${encodeURIComponent(id)}`, body);

export const createFeeItem = (body: Record<string, unknown>) =>
  api.post<FeeBandModel>('/fee-structure/item', body);

export const updateFeeItem = (id: string, body: Record<string, unknown>) =>
  api.put<FeeBandModel>(`/fee-structure/item/${encodeURIComponent(id)}`, body);

export const deleteFeeItem = (id: string) =>
  api.delete(`/fee-structure/item/${encodeURIComponent(id)}`);

export const saveFeeInstallments = (bandId: string, installments: FeeInstallment[]) =>
  api.put<FeeBandModel>(`/fee-structure/band/${encodeURIComponent(bandId)}/installments`, { installments });

export const publishFeeBand = (bandId: string) =>
  api.post<{ ok: boolean; band: FeeBandModel }>(`/fee-structure/band/${encodeURIComponent(bandId)}/publish`);

export const createFeeBandRevision = (bandId: string) =>
  api.post<FeeBandModel>(`/fee-structure/band/${encodeURIComponent(bandId)}/revision`);

export const getFeeConfigurationHealth = (academicYearId?: string, schoolId?: number) =>
  api.get<FeeConfigurationHealth>('/fee-structure/health', { params: { academicYearId, schoolId } });

export const getFeeDiscountRules = (academicYearId?: string, schoolId?: number) =>
  api.get<FeeDiscountRule[]>('/fee-structure/discount-rules', { params: { academicYearId, schoolId } });

export const saveFeeDiscountRule = (body: Record<string, unknown>) =>
  api.post<FeeDiscountRule>('/fee-structure/discount-rules', body);

export const assignFeePlan = (body: Record<string, unknown>) =>
  api.post('/fee-assignments', body);

export const recordFeePayment = (body: Record<string, unknown>) =>
  api.post('/workspace/fees/record-payment', body);
