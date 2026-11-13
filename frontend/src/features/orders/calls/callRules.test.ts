import { describe, expect, it } from 'vitest';
import type { VendorSummary } from '../../../generated/orderWorkflowClient';
import {
  CALL_NOTES_MAX_CHARS, CONTACTED_OPTIONS, OUTCOME_OPTIONS, OTHER, callRequest, defaultWho, emptyCallDraft, followUpRequired, notesRequired,
  outcomeTone, validateCall, whoLabel, whoOptions, type CallDraft,
} from './callRules';

const NOW = Date.parse('2026-11-13T06:30:00Z');

function vendor(id: number, name: string, categories: string[], overrides: Partial<VendorSummary> = {}): VendorSummary {
  return { id, name, categories, contactPerson: null, phone: null, city: null, whatsappGroupUrl: null, sample: false, active: true, ...overrides };
}

const VENDORS = [
  vendor(1, 'Metro Offset Printers', ['OFFSET_PRINTERS'], { contactPerson: 'Ravi', phone: '98100 12345' }),
  vendor(2, 'Perfect Bindery', ['BINDERS']),
  vendor(3, 'Print & Bind Co', ['DIGITAL_PRINTERS', 'BILL_BOOK_MAKERS']),
];
const CONTACTS = {
  school: { name: 'Rajesh Kumar', phone: '98111 22233' },
  designer: { id: 7, name: 'Priya Singh', phone: '98222 33344' },
  factory: { name: 'Sharma Binding Works', phone: '98333 44455' },
};

describe('call vocabulary (D35)', () => {
  it('offers the SOP entities and outcomes in the SOP order', () => {
    expect(CONTACTED_OPTIONS.map(([, label]) => label)).toEqual(['School', 'Designer', 'Printing Press', 'Manufacturing Factory', 'Internal Approver']);
    expect(OUTCOME_OPTIONS.map(([, label]) => label)).toEqual([
      'Connected', 'No Answer', 'Busy', 'Callback Requested', 'Delay Reported', 'Changes Requested', 'Approval Received',
    ]);
  });

  it('colours outcomes by what they mean', () => {
    expect(outcomeTone('CONNECTED')).toBe('success');
    expect(outcomeTone('APPROVAL_RECEIVED')).toBe('success');
    expect(outcomeTone('NO_ANSWER')).toBe('neutral');
    expect(outcomeTone('BUSY')).toBe('neutral');
    expect(outcomeTone('CALLBACK_REQUESTED')).toBe('warning');
    expect(outcomeTone('CHANGES_REQUESTED')).toBe('warning');
    expect(outcomeTone('DELAY_REPORTED')).toBe('danger');
  });

  it('needs notes for delays, change requests and callbacks, and a follow-up time only for callbacks', () => {
    expect(OUTCOME_OPTIONS.filter(([code]) => notesRequired(code)).map(([code]) => code)).toEqual(['CALLBACK_REQUESTED', 'DELAY_REPORTED', 'CHANGES_REQUESTED']);
    expect(OUTCOME_OPTIONS.filter(([code]) => followUpRequired(code)).map(([code]) => code)).toEqual(['CALLBACK_REQUESTED']);
  });
});

describe('who was called', () => {
  it('builds the choices from the entity: the order contacts, the selected printers, or the makers and the factory contact', () => {
    const labels = (contacted: Parameters<typeof whoOptions>[0]) => whoOptions(contacted, CONTACTS, VENDORS).map((option) => option.text);
    expect(labels('SCHOOL')).toEqual(['Rajesh Kumar · School contact · 98111 22233', 'Someone else']);
    expect(labels('DESIGNER')).toEqual(['Priya Singh · Designer · 98222 33344', 'Someone else']);
    expect(labels('PRINTING_PRESS')).toEqual(['Metro Offset Printers · Ravi · 98100 12345', 'Print & Bind Co', 'Someone else']);
    expect(labels('MANUFACTURING_FACTORY')).toEqual(['Sharma Binding Works · Factory contact · 98333 44455', 'Perfect Bindery', 'Print & Bind Co', 'Someone else']);
    expect(labels('INTERNAL_APPROVER')).toEqual(['Someone else']);
  });

  it('leaves out contacts the order does not have, and defaults to the first real choice', () => {
    expect(whoOptions('SCHOOL', {}, []).map((option) => option.key)).toEqual([OTHER]);
    expect(defaultWho('SCHOOL', CONTACTS, VENDORS)).toBe('school');
    expect(defaultWho('INTERNAL_APPROVER', CONTACTS, VENDORS)).toBe(OTHER);
  });
});

describe('logging a call', () => {
  const base: CallDraft = { ...emptyCallDraft(), contacted: 'SCHOOL', who: 'school', outcome: 'CONNECTED' };

  it('accepts a plain connected call with no notes', () => {
    expect(validateCall(base, NOW)).toEqual({});
  });

  it('requires notes for a delay, a typed name for someone else, and a future follow-up for a callback', () => {
    expect(validateCall({ ...base, outcome: 'DELAY_REPORTED' }, NOW)).toEqual({ notes: 'Add call notes for Delay Reported.' });
    expect(validateCall({ ...base, who: OTHER, otherName: '  ' }, NOW)).toEqual({ otherName: 'Enter who you spoke to.' });
    expect(validateCall({ ...base, outcome: 'CALLBACK_REQUESTED', notes: 'Call back after lunch' }, NOW))
      .toEqual({ nextFollowUpAt: 'Choose when to follow up.' });
    expect(validateCall({ ...base, outcome: 'CALLBACK_REQUESTED', notes: 'x', nextFollowUpAt: '2026-11-13T10:00' }, Date.parse('2026-11-14T00:00:00Z')))
      .toEqual({ nextFollowUpAt: 'Choose a follow-up time later than now.' });
  });

  it('holds notes to 100 words and the stored character limit', () => {
    const words = (count: number) => Array.from({ length: count }, () => 'word').join(' ');
    expect(validateCall({ ...base, notes: words(100) }, NOW)).toEqual({});
    expect(validateCall({ ...base, notes: words(101) }, NOW)).toEqual({ notes: 'Keep call notes to 100 words (101/100 words).' });
    expect(validateCall({ ...base, notes: 'x'.repeat(CALL_NOTES_MAX_CHARS + 1) }, NOW)).toEqual({ notes: `Keep call notes to ${CALL_NOTES_MAX_CHARS} characters.` });
    expect(validateCall({ ...base, who: OTHER, otherName: 'n'.repeat(201) }, NOW)).toEqual({ otherName: 'Use at most 200 characters.' });
  });

  it('sends the picked person, the stage and only what the outcome uses', () => {
    const options = { contacts: CONTACTS, vendors: VENDORS, currentStep: 6 };
    expect(callRequest({ ...base, notes: '  Spoke about proofs ' }, options)).toEqual({
      contacted: 'SCHOOL', outcome: 'CONNECTED', contactLabel: 'Rajesh Kumar', vendorId: null, designerId: null, stepOrder: 6,
      notes: 'Spoke about proofs', nextFollowUpAt: null,
    });
    expect(callRequest({ ...base, contacted: 'DESIGNER', who: 'designer' }, options)).toMatchObject({ contactLabel: 'Priya Singh', designerId: 7 });
    expect(callRequest({ ...base, contacted: 'PRINTING_PRESS', who: 'vendor:1', nextFollowUpAt: '2026-11-14T10:00' }, options))
      .toMatchObject({ contactLabel: 'Metro Offset Printers', vendorId: 1, notes: null, nextFollowUpAt: null });
    const callback = callRequest({ ...base, contacted: 'INTERNAL_APPROVER', who: OTHER, otherName: ' Anita ', outcome: 'CALLBACK_REQUESTED', notes: 'later', nextFollowUpAt: '2026-11-14T10:00' }, options);
    expect(callback).toMatchObject({ contactLabel: 'Anita', vendorId: null, designerId: null });
    expect(callback.nextFollowUpAt).toBe(new Date('2026-11-14T10:00').toISOString());
  });

  it('names who a logged call was with, from its label or the order\'s vendors and designer', () => {
    expect(whoLabel({ contactLabel: 'Rajesh Kumar', vendorId: null, designerId: null }, CONTACTS, VENDORS)).toBe('Rajesh Kumar');
    expect(whoLabel({ contactLabel: null, vendorId: 2, designerId: null }, CONTACTS, VENDORS)).toBe('Perfect Bindery');
    expect(whoLabel({ contactLabel: null, vendorId: null, designerId: 7 }, CONTACTS, VENDORS)).toBe('Priya Singh');
    expect(whoLabel({ contactLabel: null, vendorId: 99, designerId: null }, CONTACTS, VENDORS)).toBe('');
  });
});
