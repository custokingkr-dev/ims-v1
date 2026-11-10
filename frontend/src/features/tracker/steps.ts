import { selectionLabel, type SelectionSnapshot } from '../catalog/types';
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

export const PRINTING_CHECK_ITEMS = [
  'Impressions verified for each approved design',
  'Design sent for making plates',
  'Design plates received',
  'Printing schedule and expected completion recorded',
  'Printing completed',
  'Printed sheets received for lamination',
  'Lamination scheduled',
  'Lamination completed',
];

export const PRODUCTION_FOLLOW_UP_ITEMS = [
  'Factory confirmed receipt of the title',
  'Folding of title',
  'Ruled paper received',
  'Paper count completed',
  'Paper filling',
  'Pinning',
  'Cutting',
  'Packing',
];

export const PRODUCTION_PLANNING_ITEMS = [
  'Share order with binder',
  'Check board availability',
  'Check design status',
];

export const VENDOR_DIRECTORY = {
  board: [
    { id: 'board_shree', name: 'Shree Board Traders' },
    { id: 'board_national', name: 'National Boards Co.' },
    { id: 'board_prime', name: 'Prime Board Suppliers' },
  ],
  lamination: [
    { id: 'lam_glosspro', name: 'GlossPro Laminations' },
    { id: 'lam_crystal', name: 'Crystal Lamination Works' },
    { id: 'lam_brightfinish', name: 'BrightFinish Laminators' },
  ],
  binder: [
    { id: 'binder_perfect', name: 'Perfect Bindery' },
    { id: 'binder_classic', name: 'Classic Notebook Binding' },
    { id: 'binder_metro', name: 'Metro Binding House' },
  ],
  offset: [
    { id: 'offset_metro', name: 'Metro Offset Printers' },
    { id: 'offset_precision', name: 'Precision Print House' },
    { id: 'offset_cityline', name: 'Cityline Offset Works' },
  ],
} as const;

export type VendorCategory = keyof typeof VENDOR_DIRECTORY;
export const VENDOR_CATEGORY_LABELS: Record<VendorCategory, string> = {
  board: 'Board suppliers',
  lamination: 'Lamination vendors',
  binder: 'Binders',
  offset: 'Offset printers',
};

/** Client workflow visibility: planning selects; later stages see only vendors relevant to that stage. */
export function vendorCategoriesForStep(step: number): VendorCategory[] {
  if (step === 5) return ['board', 'lamination', 'binder', 'offset'];
  if (step === 6) return ['board', 'offset'];
  if (step === 7) return ['offset'];
  if (step === 8) return ['offset', 'lamination'];
  if (step >= 9 && step <= 12) return ['board', 'lamination', 'binder', 'offset'];
  return [];
}

export const SCHOOL_VERIFICATION: Array<[string, string]> = [
  ['NOT_REQUIRED_OR_PENDING', 'Not required / pending'], ['VERIFIED', 'Verified'], ['CHANGES_REQUIRED', 'Changes required'],
];
export const INVOICE_STATUS: Array<[string, string]> = [
  ['NOT_INITIATED', 'Not initiated'], ['INITIATED', 'Initiated'], ['SENT', 'Sent'], ['PAID', 'Paid'],
];

/** Work-area fields per step; the names are the ones the server accepts, nothing else is sent. */
export const STEP_FIELDS: Record<number, FieldSpec[]> = {
  3: [f('cdrFilename', 'CDR file name'), f('cdrLink', 'CDR file link'), f('cdrNotes', 'CDR notes', 'textarea')],
  5: [f('paperGsm', 'Paper GSM'), f('capacityConfirmed', 'Capacity confirmed'), f('productionStart', 'Production start', 'date'),
    f('targetCompletion', 'Target completion', 'date'), f('planningNotes', 'Planning notes', 'textarea')],
  6: [f('totalTitleUnits', 'Total title units', 'whole'), f('piecesPerBoard', 'Pieces per board', 'whole'), f('wastagePercent', 'Wastage %', 'number'),
    f('availableBoards', 'Available boards', 'whole'), f('procurementReference', 'Procurement reference')],
  7: [f('sampleLink', 'Print sample evidence link'), f('schoolVerification', 'School verification', 'choice', { choices: SCHOOL_VERIFICATION }),
    f('qcComments', 'QC reviewer comments', 'textarea')],
  8: [f('jobReference', 'Press job reference'), f('quantitySent', 'Quantity sent', 'whole'), f('expectedReturnDate', 'Expected return', 'date')],
  9: [f('factory', 'Making factory'), f('jobReference', 'Factory job reference'), f('titlesSent', 'Titles sent', 'whole'),
    f('titlesReceived', 'Titles received', 'whole'), f('factoryContact', 'Factory contact')],
  11: [f('producedCount', 'Produced', 'whole'), f('acceptedCount', 'Accepted', 'whole'), f('dispatchCount', 'Dispatch', 'whole'),
    f('discrepancyNote', 'Discrepancy note', 'textarea')],
  12: [f('transporter', 'Transporter / vehicle'), f('dispatchDate', 'Dispatch date', 'date'), f('lrNumber', 'Consignment / LR number'),
    f('invoiceNumber', 'Invoice number'), f('proofLink', 'Proof of delivery / invoice link'), f('invoiceStatus', 'Invoice status', 'choice', { choices: INVOICE_STATUS })],
};

/** Steps whose completion needs a superadmin sign-off (spec v4). */
export const SIGN_OFF_STEPS = new Set([7, 12]);
/** Steps the spec keeps as the prototype with a "TBD" note below the name: editable, saved as drafts, completed normally. */
export const TBD_STEPS = new Set([6]);

/** A step's state in words, so the progress bar never relies on colour or an icon alone. */
export function stepState(step: TrackerStep): 'completed' | 'overdue' | 'in progress' | 'locked' {
  if (step.status === 'COMPLETED') return 'completed';
  if (step.status === 'ACTIVE') return step.overdue ? 'overdue' : 'in progress';
  return 'locked';
}

/** A calendar day such as 2026-12-15 as "15 Dec 2026". It names a day, not an instant, so no time zone may move it. */
export function formatDay(isoDay: string): string {
  const day = new Date(`${isoDay.slice(0, 10)}T00:00:00Z`);
  return Number.isNaN(day.getTime()) ? isoDay : day.toLocaleDateString('en-IN', { timeZone: 'UTC', day: 'numeric', month: 'short', year: 'numeric' });
}

export interface SizeSummary { size: string; pages: number; books: number }

export interface NotebookCategoryQuantities { king: number; long: number; jumboKing: number; jumboLong: number; fa: number }
export interface TitleCardQuantities { king: number; long: number; jumboLong: number; fa: number }

/** Match the client's notebook categories against the selected values on each saved catalogue line. */
export function titleCardQuantities(lines: Array<{ selections: SelectionSnapshot; bookCount: number }>): TitleCardQuantities {
  const books: NotebookCategoryQuantities = { king: 0, long: 0, jumboKing: 0, jumboLong: 0, fa: 0 };
  for (const line of lines) {
    const text = Object.values(line.selections).map((value) => typeof value === 'string'
      ? value : `${value.code} ${value.label}`).join(' ').toUpperCase();
    const qty = Math.max(0, line.bookCount);
    if (/\bFA\b|FORMATIVE|ASSESSMENT/.test(text)) books.fa += qty;
    else if (text.includes('JUMBO') && text.includes('LONG')) books.jumboLong += qty;
    else if (text.includes('JUMBO') && text.includes('KING')) books.jumboKing += qty;
    else if (/\bKING\b/.test(text)) books.king += qty;
    else if (/\bLONG\b/.test(text)) books.long += qty;
  }
  return titleCardQuantitiesFromCounts(books);
}

export function titleCardQuantitiesFromCounts(books: NotebookCategoryQuantities): TitleCardQuantities {
  const sheets = (count: number, perSheet: number, extra: number) => count > 0 ? Math.ceil(count / perSheet) + extra : 0;
  return {
    king: sheets(books.king + books.jumboKing, 8, 13),
    long: sheets(books.long, 8, 13),
    jumboLong: sheets(books.jumboLong, 4, 25),
    fa: sheets(books.fa, 4, 25),
  };
}

/** The order's lines by size and page count: the rulings of one size and length collapse into one entry (spec v4 header). */
export function sizeSummary(lines: Array<{ selections: SelectionSnapshot; bookCount: number; pageCount: number }>): SizeSummary[] {
  const groups = new Map<string, SizeSummary>();
  for (const line of lines) {
    const size = selectionLabel(line.selections, 'SIZE') || 'Size not given';
    const key = `${size}|${line.pageCount}`;
    const group = groups.get(key) ?? { size, pages: line.pageCount, books: 0 };
    group.books += line.bookCount;
    groups.set(key, group);
  }
  return [...groups.values()];
}

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
