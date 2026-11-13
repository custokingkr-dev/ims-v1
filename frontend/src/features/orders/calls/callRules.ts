import type { CallContacted, CallLogEntry, CallLogRequest, CallOutcome, VendorSummary } from '../../../generated/orderWorkflowClient';
import { CALL_NOTES_MAX_WORDS, wordLimit } from '../shared/words';

/**
 * The call log's vocabulary and rules (design D35, section 4.6). The server enforces every rule; these mirror them so the
 * form explains a problem beside its field instead of after a refused save.
 */

export type Contacted = CallContacted;
export type Outcome = CallOutcome;

/** A person on the order: the school contact, the designer (with its directory id) or the factory contact. */
export interface Contact {
  name: string;
  phone?: string | null;
  /** The designer's id in the designer directory; sent with a call to the designer. */
  id?: number | null;
}

export interface CallContacts { school?: Contact; designer?: Contact; factory?: Contact }

export const CONTACTED_OPTIONS: ReadonlyArray<readonly [Contacted, string]> = [
  ['SCHOOL', 'School'],
  ['DESIGNER', 'Designer'],
  ['PRINTING_PRESS', 'Printing Press'],
  ['MANUFACTURING_FACTORY', 'Manufacturing Factory'],
  ['INTERNAL_APPROVER', 'Internal Approver'],
];

export const OUTCOME_OPTIONS: ReadonlyArray<readonly [Outcome, string]> = [
  ['CONNECTED', 'Connected'],
  ['NO_ANSWER', 'No Answer'],
  ['BUSY', 'Busy'],
  ['CALLBACK_REQUESTED', 'Callback Requested'],
  ['DELAY_REPORTED', 'Delay Reported'],
  ['CHANGES_REQUESTED', 'Changes Requested'],
  ['APPROVAL_RECEIVED', 'Approval Received'],
];

export function contactedLabel(code: Contacted): string {
  return CONTACTED_OPTIONS.find(([value]) => value === code)?.[1] ?? code;
}

export function outcomeLabel(code: Outcome): string {
  return OUTCOME_OPTIONS.find(([value]) => value === code)?.[1] ?? code;
}

export type OutcomeTone = 'success' | 'neutral' | 'warning' | 'danger';

const TONES: Record<Outcome, OutcomeTone> = {
  CONNECTED: 'success',
  APPROVAL_RECEIVED: 'success',
  NO_ANSWER: 'neutral',
  BUSY: 'neutral',
  CALLBACK_REQUESTED: 'warning',
  CHANGES_REQUESTED: 'warning',
  DELAY_REPORTED: 'danger',
};

export function outcomeTone(code: Outcome): OutcomeTone {
  return TONES[code] ?? 'neutral';
}

/** Notes are required for these outcomes [Ours]; a delay, a change or a callback means nothing without them. */
export function notesRequired(outcome: Outcome): boolean {
  return outcome === 'DELAY_REPORTED' || outcome === 'CHANGES_REQUESTED' || outcome === 'CALLBACK_REQUESTED';
}

export function followUpRequired(outcome: Outcome): boolean {
  return outcome === 'CALLBACK_REQUESTED';
}

/** workflow_call_logs.notes holds at most 1,000 characters, besides the 100-word limit. */
export const CALL_NOTES_MAX_CHARS = 1000;
export const CONTACT_LABEL_MAX_CHARS = 200;

/** Printing presses are the printer categories; every other selected vendor is a maker the factory side calls. */
const PRINTER_CATEGORIES = new Set(['OFFSET_PRINTERS', 'DIGITAL_PRINTERS', 'FLEX_PRINTERS', 'PHOTO_PAPER_PRINTERS']);

export const OTHER = 'other';

export interface WhoOption {
  key: string;
  /** The name sent as the call's contact label. */
  label: string;
  /** What the select shows: the name, then the role or contact person, then the phone. */
  text: string;
  vendorId?: number;
  designerId?: number;
}

function joined(...parts: Array<string | null | undefined>): string {
  return parts.map((part) => part?.trim()).filter(Boolean).join(' · ');
}

function contactOption(key: string, contact: Contact | undefined, role: string, designerId?: number | null): WhoOption[] {
  const name = contact?.name.trim();
  if (!name) return [];
  return [{ key, label: name, text: joined(name, role, contact?.phone), designerId: designerId ?? undefined }];
}

function vendorOption(vendor: VendorSummary): WhoOption {
  return { key: `vendor:${vendor.id}`, label: vendor.name, text: joined(vendor.name, vendor.contactPerson, vendor.phone), vendorId: vendor.id };
}

/**
 * Who can be picked for an entity: the school contact; the designer; the order's selected printers for Printing Press;
 * the factory contact and every other selected vendor (binders and makers) for Manufacturing Factory. "Someone else"
 * always comes last and asks for a typed name. A vendor in both kinds of category is offered under both.
 */
export function whoOptions(contacted: Contacted, contacts: CallContacts, vendors: readonly VendorSummary[]): WhoOption[] {
  const other: WhoOption = { key: OTHER, label: '', text: 'Someone else' };
  const printers = vendors.filter((vendor) => vendor.categories.some((code) => PRINTER_CATEGORIES.has(code)));
  const makers = vendors.filter((vendor) => vendor.categories.some((code) => !PRINTER_CATEGORIES.has(code)));
  switch (contacted) {
    case 'SCHOOL': return [...contactOption('school', contacts.school, 'School contact'), other];
    case 'DESIGNER': return [...contactOption('designer', contacts.designer, 'Designer', contacts.designer?.id), other];
    case 'PRINTING_PRESS': return [...printers.map(vendorOption), other];
    case 'MANUFACTURING_FACTORY': return [...contactOption('factory', contacts.factory, 'Factory contact'), ...makers.map(vendorOption), other];
    default: return [other];
  }
}

export function defaultWho(contacted: Contacted, contacts: CallContacts, vendors: readonly VendorSummary[]): string {
  return whoOptions(contacted, contacts, vendors)[0].key;
}

export interface CallDraft {
  contacted: Contacted;
  /** A WhoOption key. */
  who: string;
  otherName: string;
  outcome: Outcome;
  notes: string;
  /** A datetime-local value (yyyy-mm-ddThh:mm, the browser's zone), used only for Callback Requested. */
  nextFollowUpAt: string;
}

export function emptyCallDraft(): CallDraft {
  return { contacted: 'SCHOOL', who: OTHER, otherName: '', outcome: 'CONNECTED', notes: '', nextFollowUpAt: '' };
}

export type CallErrors = Partial<Record<'otherName' | 'notes' | 'nextFollowUpAt', string>>;

export function validateCall(draft: CallDraft, now: number): CallErrors {
  const errors: CallErrors = {};
  if (draft.who === OTHER) {
    const name = draft.otherName.trim();
    if (!name) errors.otherName = 'Enter who you spoke to.';
    else if (name.length > CONTACT_LABEL_MAX_CHARS) errors.otherName = `Use at most ${CONTACT_LABEL_MAX_CHARS} characters.`;
  }
  const limit = wordLimit(draft.notes, CALL_NOTES_MAX_WORDS);
  if (!draft.notes.trim() && notesRequired(draft.outcome)) errors.notes = `Add call notes for ${outcomeLabel(draft.outcome)}.`;
  else if (limit.over) errors.notes = `Keep call notes to ${CALL_NOTES_MAX_WORDS} words (${limit.label}).`;
  else if (draft.notes.trim().length > CALL_NOTES_MAX_CHARS) errors.notes = `Keep call notes to ${CALL_NOTES_MAX_CHARS} characters.`;
  if (followUpRequired(draft.outcome)) {
    const at = draft.nextFollowUpAt ? new Date(draft.nextFollowUpAt).getTime() : Number.NaN;
    if (!draft.nextFollowUpAt || Number.isNaN(at)) errors.nextFollowUpAt = 'Choose when to follow up.';
    else if (at <= now) errors.nextFollowUpAt = 'Choose a follow-up time later than now.';
  }
  return errors;
}

/** The POST body: the picked person (by id where the order knows one), the current stage, and only what the outcome uses. */
export function callRequest(draft: CallDraft, { contacts, vendors, currentStep }: {
  contacts: CallContacts; vendors: readonly VendorSummary[]; currentStep: number;
}): CallLogRequest {
  const option = whoOptions(draft.contacted, contacts, vendors).find((item) => item.key === draft.who);
  const label = !option || option.key === OTHER ? draft.otherName.trim() : option.label;
  const notes = draft.notes.trim();
  return {
    contacted: draft.contacted,
    outcome: draft.outcome,
    contactLabel: label || null,
    vendorId: option?.vendorId ?? null,
    designerId: option?.designerId ?? null,
    stepOrder: currentStep > 0 ? currentStep : null,
    notes: notes || null,
    nextFollowUpAt: followUpRequired(draft.outcome) && draft.nextFollowUpAt ? new Date(draft.nextFollowUpAt).toISOString() : null,
  };
}

/** Who a logged call was with: its label, else the vendor or designer it names, else nothing. */
export function whoLabel(entry: Pick<CallLogEntry, 'contactLabel' | 'vendorId' | 'designerId'>, contacts: CallContacts, vendors: readonly VendorSummary[]): string {
  if (entry.contactLabel?.trim()) return entry.contactLabel.trim();
  if (entry.vendorId != null) return vendors.find((vendor) => vendor.id === entry.vendorId)?.name ?? '';
  if (entry.designerId != null && contacts.designer?.id === entry.designerId) return contacts.designer.name;
  return '';
}
