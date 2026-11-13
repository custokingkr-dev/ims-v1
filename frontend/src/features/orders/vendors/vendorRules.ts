import type { VendorRequest, VendorView } from '../../../generated/vendorDirectoryClient';
import { INVALID_INVITE_MESSAGE, normaliseInvite } from '../shared/invite';

/**
 * The vendor directory's form rules (design D31, section 4.5). The server validates every vendor; these mirror its limits
 * (workflow_vendors columns) so a mistake is shown beside its field instead of as a refused save.
 */

export interface VendorDraft {
  name: string;
  categories: string[];
  contactPerson: string;
  phone: string;
  altPhone: string;
  email: string;
  city: string;
  whatsappGroupUrl: string;
  notes: string;
  active: boolean;
}

export type VendorField = Exclude<keyof VendorDraft, 'active'>;
export type VendorErrors = Partial<Record<VendorField, string>>;

export const EMPTY_VENDOR_DRAFT: VendorDraft = {
  name: '', categories: [], contactPerson: '', phone: '', altPhone: '', email: '', city: '', whatsappGroupUrl: '', notes: '', active: true,
};

export const VENDOR_LIMITS = { name: 200, contactPerson: 200, phone: 32, email: 254, city: 100, notes: 500 } as const;

/** VendorDirectoryService.phone: an optional leading +, then digits, spaces, brackets, dots and hyphens. */
const PHONE_CHARACTERS = /^\+?[0-9 ().-]+$/;
const PHONE_MIN_DIGITS = 6;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PHONE_MESSAGE = 'Enter a phone number, such as +91 98765 43210.';

function phoneError(text: string): string {
  const digits = text.replace(/\D/g, '').length;
  return text.length > VENDOR_LIMITS.phone || !PHONE_CHARACTERS.test(text) || digits < PHONE_MIN_DIGITS ? PHONE_MESSAGE : '';
}

function tooLong(text: string, max: number): string {
  return text.length > max ? `Use at most ${max} characters.` : '';
}

/**
 * Every problem with the draft, keyed by field; empty when it may be saved. A sample vendor (seeded from the prototype,
 * with no phone) may be edited without adding one; every other vendor needs a phone, because operators call it.
 */
export function validateVendorDraft(draft: VendorDraft, { sample }: { sample: boolean }): VendorErrors {
  const errors: VendorErrors = {};
  const set = (field: VendorField, message: string) => { if (message) errors[field] = message; };
  const name = draft.name.trim();
  set('name', !name ? 'Enter the vendor name.' : tooLong(name, VENDOR_LIMITS.name));
  set('categories', draft.categories.length === 0 ? 'Choose at least one category.' : '');
  const phone = draft.phone.trim();
  set('phone', !phone ? (sample ? '' : 'Enter a phone number.') : phoneError(phone));
  const altPhone = draft.altPhone.trim();
  set('altPhone', altPhone ? phoneError(altPhone) : '');
  const email = draft.email.trim();
  set('email', email && (!EMAIL.test(email) || email.length > VENDOR_LIMITS.email) ? 'Enter an email address like name@example.com.' : '');
  const invite = draft.whatsappGroupUrl.trim();
  set('whatsappGroupUrl', invite && !normaliseInvite(invite) ? INVALID_INVITE_MESSAGE : '');
  set('notes', tooLong(draft.notes.trim(), VENDOR_LIMITS.notes));
  set('contactPerson', tooLong(draft.contactPerson.trim(), VENDOR_LIMITS.contactPerson));
  set('city', tooLong(draft.city.trim(), VENDOR_LIMITS.city));
  return errors;
}

const blankToNull = (text: string) => (text.trim() ? text.trim() : null);

/** The PUT/POST body: every field sent, trimmed, blanks as null, the invite in its one canonical spelling. */
export function requestFromDraft(draft: VendorDraft): VendorRequest {
  const invite = draft.whatsappGroupUrl.trim();
  return {
    name: draft.name.trim(),
    categories: [...draft.categories],
    contactPerson: blankToNull(draft.contactPerson),
    phone: blankToNull(draft.phone),
    altPhone: blankToNull(draft.altPhone),
    email: blankToNull(draft.email),
    city: blankToNull(draft.city),
    whatsappGroupUrl: invite ? normaliseInvite(invite) : null,
    notes: blankToNull(draft.notes),
    active: draft.active,
  };
}

export function draftFromVendor(vendor: VendorView): VendorDraft {
  return {
    name: vendor.name,
    categories: [...vendor.categories],
    contactPerson: vendor.contactPerson ?? '',
    phone: vendor.phone ?? '',
    altPhone: vendor.altPhone ?? '',
    email: vendor.email ?? '',
    city: vendor.city ?? '',
    whatsappGroupUrl: vendor.whatsappGroupUrl ?? '',
    notes: vendor.notes ?? '',
    active: vendor.active,
  };
}

export type StatusTone = 'success' | 'warning' | 'neutral';

/** The Status column: Inactive, Sample (seeded, not yet a real supplier) or Active, always as words. */
export function vendorStatus(vendor: Pick<VendorView, 'active' | 'sample'>): { label: string; tone: StatusTone } {
  if (!vendor.active) return { label: 'Inactive', tone: 'neutral' };
  if (vendor.sample) return { label: 'Sample', tone: 'warning' };
  return { label: 'Active', tone: 'success' };
}
