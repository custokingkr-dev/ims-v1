import { describe, expect, it } from 'vitest';
import type { VendorView } from '../../../generated/vendorDirectoryClient';
import { EMPTY_VENDOR_DRAFT, draftFromVendor, requestFromDraft, validateVendorDraft, vendorStatus } from './vendorRules';

const INVITE = 'https://chat.whatsapp.com/AbCdEf12345';
const valid = { ...EMPTY_VENDOR_DRAFT, name: 'Metro Offset Printers', categories: ['OFFSET_PRINTERS'], phone: '+91 98100 12345' };

function vendor(overrides: Partial<VendorView> = {}): VendorView {
  return {
    id: 4, name: 'Shree Board Traders', categories: ['BOARD_SUPPLIERS'], contactPerson: null, phone: null, altPhone: null,
    email: null, city: null, whatsappGroupUrl: null, notes: null, sample: true, active: true, updatedAt: '2026-11-13T05:00:00Z',
    updatedBy: null, ...overrides,
  };
}

describe('vendor form validation (D31)', () => {
  it('accepts a vendor with a name, a category and a phone', () => {
    expect(validateVendorDraft(valid, { sample: false })).toEqual({});
  });

  it('requires a name, at least one category and a phone', () => {
    expect(validateVendorDraft(EMPTY_VENDOR_DRAFT, { sample: false })).toEqual({
      name: 'Enter the vendor name.',
      categories: 'Choose at least one category.',
      phone: 'Enter a phone number.',
    });
  });

  it('lets a sample vendor stay without a phone until one is added', () => {
    expect(validateVendorDraft({ ...valid, phone: '' }, { sample: true })).toEqual({});
  });

  it('checks phones, email, invite and lengths the way the server does', () => {
    const errors = validateVendorDraft({
      ...valid, name: 'x'.repeat(201), phone: '12', altPhone: 'call me', email: 'not-an-email', whatsappGroupUrl: 'https://wa.me/123',
      notes: 'n'.repeat(501), contactPerson: 'c'.repeat(201), city: 'c'.repeat(101),
    }, { sample: false });
    expect(errors).toEqual({
      name: 'Use at most 200 characters.',
      phone: 'Enter a phone number, such as +91 98765 43210.',
      altPhone: 'Enter a phone number, such as +91 98765 43210.',
      email: 'Enter an email address like name@example.com.',
      whatsappGroupUrl: 'Paste a WhatsApp group invite link (https://chat.whatsapp.com/…)',
      notes: 'Use at most 500 characters.',
      contactPerson: 'Use at most 200 characters.',
      city: 'Use at most 100 characters.',
    });
  });

  it('accepts exactly the phones the server accepts: a leading +, at least six digits, at most 32 characters', () => {
    const phoneError = (phone: string) => validateVendorDraft({ ...valid, phone }, { sample: false }).phone;
    for (const ok of ['011 2345', '(011) 2345-6789', '+91 98100.12345', '9'.repeat(20)]) expect(phoneError(ok)).toBeUndefined();
    for (const bad of ['12345', '98100+12345', '+91 98100 12345 ext 4', '9'.repeat(33)]) expect(phoneError(bad)).toBe('Enter a phone number, such as +91 98765 43210.');
  });

  it('measures notes as the server stores them, without surrounding spaces', () => {
    expect(validateVendorDraft({ ...valid, notes: ` ${'n'.repeat(500)} ` }, { sample: false })).toEqual({});
  });
});

describe('vendor requests', () => {
  it('sends trimmed values, blanks as null, and the canonical invite', () => {
    expect(requestFromDraft({ ...valid, contactPerson: '  Ravi ', whatsappGroupUrl: ` HTTPS://Chat.WhatsApp.com/AbCdEf12345 `, city: ' ', active: false })).toEqual({
      name: 'Metro Offset Printers', categories: ['OFFSET_PRINTERS'], contactPerson: 'Ravi', phone: '+91 98100 12345', altPhone: null,
      email: null, city: null, whatsappGroupUrl: INVITE, notes: null, active: false,
    });
  });

  it('round-trips a saved vendor through the form', () => {
    const saved = vendor({ sample: false, phone: '9810012345', whatsappGroupUrl: INVITE, city: 'Delhi' });
    expect(requestFromDraft(draftFromVendor(saved))).toMatchObject({ name: saved.name, phone: '9810012345', whatsappGroupUrl: INVITE, city: 'Delhi', active: true });
  });
});

describe('vendor status (Status column)', () => {
  it('names inactive first, then sample, then active', () => {
    expect(vendorStatus(vendor({ active: false }))).toEqual({ label: 'Inactive', tone: 'neutral' });
    expect(vendorStatus(vendor())).toEqual({ label: 'Sample', tone: 'warning' });
    expect(vendorStatus(vendor({ sample: false }))).toEqual({ label: 'Active', tone: 'success' });
  });
});
