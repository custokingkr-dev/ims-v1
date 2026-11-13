import { describe, expect, it } from 'vitest';
import {
  EVIDENCE_LINK_MAX_CHARS, INVALID_INVITE_MESSAGE, fillIntroMessage, hasUnfilledPlaceholders, isWhatsAppInvite,
  normaliseInvite, safeEvidenceHref, safeInviteHref,
} from './invite';

const CODE = 'AbCdEf12345_-x';

describe('WhatsApp group invites (D34)', () => {
  it('accepts an invite however the scheme and host are cased, and answers with the canonical link', () => {
    expect(normaliseInvite(`https://chat.whatsapp.com/${CODE}`)).toBe(`https://chat.whatsapp.com/${CODE}`);
    expect(normaliseInvite(`  HTTPS://Chat.WhatsApp.com/${CODE}  `)).toBe(`https://chat.whatsapp.com/${CODE}`);
    expect(normaliseInvite(`https://chat.whatsapp.com:/${CODE}`)).toBe(`https://chat.whatsapp.com/${CODE}`);
  });

  it('keeps the case-sensitive invite code exactly as given', () => {
    expect(normaliseInvite('https://chat.whatsapp.com/ABCDEFGHIJ')).toBe('https://chat.whatsapp.com/ABCDEFGHIJ');
    expect(normaliseInvite('https://chat.whatsapp.com/abcdefghij')).toBe('https://chat.whatsapp.com/abcdefghij');
  });

  it('refuses everything the server refuses', () => {
    for (const text of [
      '', 'chat.whatsapp.com/AbCdEf12345', 'http://chat.whatsapp.com/AbCdEf12345', 'https://wa.me/AbCdEf12345',
      'https://chat.whatsapp.com/short', `https://chat.whatsapp.com/${'a'.repeat(65)}`, `https://chat.whatsapp.com/${CODE}?x=1`,
      `https://chat.whatsapp.com/${CODE}#x`, `https://chat.whatsapp.com:443/${CODE}`, `https://user@chat.whatsapp.com/${CODE}`,
      `https://chat.whatsapp.com/${CODE}/`, `https://chat.whatsapp.com.evil.test/${CODE}`, `javascript:alert(1)//chat.whatsapp.com/${CODE}`,
    ]) {
      expect(isWhatsAppInvite(text)).toBe(false);
      expect(normaliseInvite(text)).toBeNull();
    }
    expect(INVALID_INVITE_MESSAGE).toBe('Paste a WhatsApp group invite link (https://chat.whatsapp.com/…)');
  });

  it('only ever opens a canonical invite', () => {
    expect(safeInviteHref(`https://chat.whatsapp.com/${CODE}`)).toBe(`https://chat.whatsapp.com/${CODE}`);
    expect(safeInviteHref('https://example.test/phish')).toBeNull();
    expect(safeInviteHref(null)).toBeNull();
  });
});

describe('evidence links (D30)', () => {
  it('opens https links of at most 1,000 characters only', () => {
    expect(safeEvidenceHref('https://drive.google.com/drive/folders/abc')).toBe('https://drive.google.com/drive/folders/abc');
    expect(safeEvidenceHref('  https://contoso.sharepoint.com/sites/x  ')).toBe('https://contoso.sharepoint.com/sites/x');
    for (const text of ['http://drive.google.com/x', 'javascript:alert(1)', 'drive.google.com/x', 'https://', 'https:// spaced.test',
      `https://drive.test/${'a'.repeat(EVIDENCE_LINK_MAX_CHARS)}`, '', null, undefined]) {
      expect(safeEvidenceHref(text)).toBeNull();
    }
  });
});

describe('introduction message (D28)', () => {
  const NB = 'My name is {Name}, and I will be your point of contact for all notebook-related orders. Please feel free to reach out for any queries, updates, or follow-ups.';
  const GENERAL = 'My name is {Name}, and I will be handling your {Item} order. Please reach out for any queries, updates, or follow-ups.';

  it('fills {Name} with the trimmed name as it is typed', () => {
    expect(fillIntroMessage(NB, { name: '  Asha Rao ' })).toBe(NB.replace('{Name}', 'Asha Rao'));
    expect(hasUnfilledPlaceholders(fillIntroMessage(NB, { name: 'Asha' }))).toBe(false);
  });

  it('fills the General template\'s {Item} with the order\'s item noun', () => {
    expect(fillIntroMessage(GENERAL, { name: 'Asha', itemNoun: 'report card' }))
      .toBe('My name is Asha, and I will be handling your report card order. Please reach out for any queries, updates, or follow-ups.');
  });

  it('leaves a placeholder visible until there is something to put in it', () => {
    const filled = fillIntroMessage(GENERAL, { name: '   ' });
    expect(filled).toContain('{Name}');
    expect(filled).toContain('{Item}');
    expect(hasUnfilledPlaceholders(filled)).toBe(true);
  });

  it('inserts a typed name literally', () => {
    expect(fillIntroMessage('Hi {Name}, {Name}', { name: '$& $1' })).toBe('Hi $& $1, $& $1');
    expect(fillIntroMessage('{Name} / {Item}', { name: '{Item}', itemNoun: 'flex' })).toBe('{Item} / flex');
  });
});
