import type { TrackerStep } from './types';

export type FieldKind = 'text' | 'textarea' | 'date' | 'whole' | 'number' | 'choice';
export interface FieldSpec { key: string; label: string; kind: FieldKind; choices?: Array<[string, string]>; max?: number }

const f = (key: string, label: string, kind: FieldKind = 'text', extra: Partial<FieldSpec> = {}): FieldSpec => ({ key, label, kind, ...extra });

/** The prototype's eight pre-print checks, in order; the server stores the ticked positions. */
export const QC_ITEMS = [
  'School name, logo and spelling verified',
  'Latest approved artwork version confirmed',
  'Dimensions, bleed and safe margins checked',
  'Board material, GSM and print specifications verified',
  'Physical print sample inspected for defects',
  'School verification recorded where required',
  'Print quantity and title-wise breakup verified',
  'Authorised quality reviewer releases bulk printing',
];

export const SCHOOL_VERIFICATION: Array<[string, string]> = [
  ['NOT_REQUIRED_OR_PENDING', 'Not required / pending'], ['VERIFIED', 'Verified'], ['CHANGES_REQUIRED', 'Changes required'],
];
export const INVOICE_STATUS: Array<[string, string]> = [
  ['NOT_INITIATED', 'Not initiated'], ['INITIATED', 'Initiated'], ['SENT', 'Sent'], ['PAID', 'Paid'],
];

/** Work-area fields per step; the names are the ones the server accepts, nothing else is sent. */
export const STEP_FIELDS: Record<number, FieldSpec[]> = {
  4: [f('cdrFilename', 'CDR file name'), f('cdrLink', 'CDR file link'), f('cdrNotes', 'CDR notes', 'textarea')],
  5: [f('paperGsm', 'Paper GSM'), f('capacityConfirmed', 'Capacity confirmed'), f('productionStart', 'Production start', 'date'),
    f('targetCompletion', 'Target completion', 'date'), f('planningNotes', 'Planning notes', 'textarea')],
  6: [f('totalTitleUnits', 'Total title units', 'whole'), f('piecesPerBoard', 'Pieces per board', 'whole'), f('wastagePercent', 'Wastage %', 'number'),
    f('availableBoards', 'Available boards', 'whole'), f('procurementReference', 'Procurement reference')],
  7: [f('jobReference', 'Press job reference'), f('quantitySent', 'Quantity sent', 'whole'), f('expectedReturnDate', 'Expected return', 'date')],
  8: [f('sampleLink', 'Print sample evidence link'), f('schoolVerification', 'School verification', 'choice', { choices: SCHOOL_VERIFICATION }),
    f('qcComments', 'QC reviewer comments', 'textarea')],
  9: [f('factory', 'Making factory'), f('jobReference', 'Factory job reference'), f('titlesSent', 'Titles sent', 'whole'),
    f('titlesReceived', 'Titles received', 'whole'), f('factoryContact', 'Factory contact')],
  11: [f('producedCount', 'Produced', 'whole'), f('acceptedCount', 'Accepted', 'whole'), f('dispatchCount', 'Dispatch', 'whole'),
    f('discrepancyNote', 'Discrepancy note', 'textarea')],
  12: [f('transporter', 'Transporter / vehicle'), f('dispatchDate', 'Dispatch date', 'date'), f('lrNumber', 'Consignment / LR number'),
    f('invoiceNumber', 'Invoice number'), f('proofLink', 'Proof of delivery / invoice link'), f('invoiceStatus', 'Invoice status', 'choice', { choices: INVOICE_STATUS })],
};

/** Steps whose completion needs a superadmin sign-off (spec v4). */
export const SIGN_OFF_STEPS = new Set([8, 12]);

export function isSignedOff(step: TrackerStep): boolean {
  const sign = step.data.signOff;
  return typeof sign === 'object' && sign !== null;
}

export function wordCount(text: string): number {
  const trimmed = text.trim();
  return trimmed ? trimmed.split(/\s+/).length : 0;
}

/** Counts mismatch unless accepted = dispatch and accepted <= produced <= ordered (spec v4). */
export function countsMismatch(ordered: number, data: Record<string, unknown>): boolean {
  const n = (v: unknown) => (typeof v === 'number' ? v : Number(v));
  const produced = n(data.producedCount), accepted = n(data.acceptedCount), dispatch = n(data.dispatchCount);
  if ([produced, accepted, dispatch].some((v) => !Number.isFinite(v))) return false;
  return !(accepted === dispatch && accepted <= produced && produced <= ordered);
}

export function slaLabel(step: TrackerStep): string {
  if (!step.sla) return 'No timer';
  const { value, unit } = step.sla;
  const label = unit === 'HOURS' ? 'hour' : 'working day';
  return `${value} ${label}${value === 1 ? '' : 's'}`;
}
