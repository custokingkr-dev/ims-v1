import api from './api';
import { createFeeClient } from '../generated/feeClient';

const feeClient = createFeeClient(api);
type FeeReportParameters = Parameters<typeof feeClient.getCollectionReport>[0];

// Preserve the panels' row-array boundary while using the canonical envelope.
export const getFeeCollectionRows = async (parameters: FeeReportParameters) => ({ data: (await feeClient.getCollectionReport(parameters)).content });
export const getFeeOverdueRows = async (parameters: FeeReportParameters) => ({ data: (await feeClient.getOverdueReport(parameters)).content });

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
  api.get<FeeStructureModel>('/fees/structure', { params: { academicYearId, schoolId } });

export const createFeeBand = (body: Record<string, unknown>) =>
  api.post<FeeBandModel>('/fees/bands', body);

export const updateFeeBand = (id: string, body: Record<string, unknown>) =>
  api.put<FeeBandModel>(`/fees/bands/${encodeURIComponent(id)}`, body);

export const createFeeItem = (body: Record<string, unknown>) =>
  api.post<FeeBandModel>('/fees/items', { ...body, name: body.name ?? body.itemName });

export const updateFeeItem = (id: string, body: Record<string, unknown>) =>
  api.put<FeeBandModel>(`/fees/items/${encodeURIComponent(id)}`, body);

export const deleteFeeItem = (id: string) =>
  api.delete(`/fees/items/${encodeURIComponent(id)}`);

export const saveFeeInstallments = (bandId: string, installments: FeeInstallment[]) =>
  api.put<FeeBandModel>(`/fees/bands/${encodeURIComponent(bandId)}/installments`, { installments });

export const publishFeeBand = (bandId: string) =>
  api.post<{ ok: boolean; band: FeeBandModel }>(`/fees/bands/${encodeURIComponent(bandId)}/publish`);

export const createFeeBandRevision = (bandId: string) =>
  api.post<FeeBandModel>(`/fees/bands/${encodeURIComponent(bandId)}/revision`);

export const getFeeConfigurationHealth = (academicYearId?: string, schoolId?: number) =>
  api.get<FeeConfigurationHealth>('/fees/structure/health', { params: { academicYearId, schoolId } });

export const getFeeDiscountRules = (academicYearId?: string, schoolId?: number) =>
  api.get<FeeDiscountRule[]>('/fees/structure/discount-rules', { params: { academicYearId, schoolId } });

export const saveFeeDiscountRule = (body: Record<string, unknown>) =>
  api.post<FeeDiscountRule>('/fees/structure/discount-rules', body);

export const assignFeePlan = (body: Record<string, unknown>) =>
  api.post('/fees/assignments', body);

export interface FeePaymentRequest {
  studentId: string | number;
  assignmentId: string;
  academicYearId: string;
  amount: number;
  mode: string;
  notes: string;
  paidAt: string;
  idempotencyKey: string;
  schoolId?: number;
}

export interface PendingFeePayment {
  request: FeePaymentRequest;
  studentName: string;
}

const pendingPaymentKey = (scope: string) => `ck_fee_payment_pending:${scope}`;

export function readPendingFeePayment(scope: string): PendingFeePayment | null {
  const stored = localStorage.getItem(pendingPaymentKey(scope));
  if (!stored) return null;
  try {
    const request = JSON.parse(stored)?.request as FeePaymentRequest | undefined;
    if (!request || !Number.isSafeInteger(Number(request.studentId)) || Number(request.studentId) <= 0
      || !Number.isSafeInteger(request.amount) || request.amount <= 0
      || !request.assignmentId || typeof request.assignmentId !== 'string'
      || !request.academicYearId || typeof request.academicYearId !== 'string'
      || typeof request.mode !== 'string' || !request.mode
      || typeof request.notes !== 'string'
      || typeof request.paidAt !== 'string' || !Number.isFinite(Date.parse(request.paidAt))
      || typeof request.idempotencyKey !== 'string' || !request.idempotencyKey.trim() || request.idempotencyKey.length > 128
      || (request.schoolId !== undefined && (!Number.isSafeInteger(request.schoolId) || request.schoolId <= 0))) {
      throw new Error('Invalid saved payment');
    }
    return { request, studentName: `Student #${request.studentId}` };
  } catch {
    throw new Error('The saved collection could not be read. Check the student ledger and receipts before clearing its recovery entry.');
  }
}

/** Rendering must remain available even if recovery storage is inaccessible or malformed. */
export function readFeePaymentRecovery(scope: string) {
  try { return { pending: readPendingFeePayment(scope), error: '' }; }
  catch (error) {
    return { pending: null, error: error instanceof Error && error.message.startsWith('The saved collection')
      ? error.message
      : 'Saved payment recovery is unavailable in this browser. Check storage access and the student ledger before continuing.' };
  }
}

export function clearFeePaymentRecovery(scope: string) {
  localStorage.removeItem(pendingPaymentKey(scope));
  if (localStorage.getItem(pendingPaymentKey(scope)) !== null) throw new Error('The saved recovery entry could not be cleared. Check browser storage access and try again.');
}

/** Persist before sending: a timeout or refreshed tab must reuse the same collection. */
export function prepareFeePayment(
  scope: string,
  body: Omit<FeePaymentRequest, 'paidAt' | 'idempotencyKey'>,
  studentName: string,
): PendingFeePayment {
  if (readPendingFeePayment(scope)) {
    throw new Error('Confirm the pending payment before starting another collection.');
  }
  if (!body.assignmentId || !body.academicYearId) throw new Error('Reload the student ledger to select a fee assignment and academic year.');
  const pending = { request: { ...body, paidAt: new Date().toISOString(), idempotencyKey: crypto.randomUUID() }, studentName };
  // Names are display-only. The exact request (including notes) is retained only
  // while confirmation is unresolved so replay can validate payload consistency.
  try { localStorage.setItem(pendingPaymentKey(scope), JSON.stringify({ request: pending.request })); }
  catch { throw new Error('This browser could not save payment recovery details. Allow browser storage before recording a payment.'); }
  return pending;
}

export const recordFeePayment = async (body: FeePaymentRequest) => ({
  data: await feeClient.recordPayment({ ...body, studentId: Number(body.studentId) }),
});

export async function confirmFeePayment(scope: string, pending: PendingFeePayment) {
  let result;
  try {
    result = await recordFeePayment(pending.request);
  } catch (error) {
    // Even a later 400/403 may follow an earlier committed write whose response
    // was lost (student or permissions may have changed). Retain every uncertain
    // request until confirmed, or explicitly cleared after checking the ledger.
    throw error;
  }
  // A cleanup error cannot turn an authoritative successful collection into a
  // failed-payment message. Keep the receipt and explicitly block a new request.
  let recoveryWarning = '';
  try {
    if (readPendingFeePayment(scope)?.request.idempotencyKey === pending.request.idempotencyKey) clearFeePaymentRecovery(scope);
  } catch {
    recoveryWarning = 'Payment is recorded. Its saved recovery entry could not be cleared. Check the receipt, then clear the recovery entry before another collection.';
  }
  return { ...result, recoveryWarning };
}
