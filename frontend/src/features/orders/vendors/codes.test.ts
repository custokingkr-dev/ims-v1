import { describe, expect, it } from 'vitest';
import {
  ITEM_CODE_HINT, SCHOOL_ORDER_CODE_HINT, firstFreeSchoolOrderCode, isItemCode, isSchoolOrderCode, normaliseItemCode, normaliseSchoolOrderCode,
  schoolOrderCodeError, schoolOrderCodeWithSuffix, suggestSchoolOrderCode,
} from './codes';

describe('suggested school order code (D12)', () => {
  it('takes the initials of the name and the first three letters of the city', () => {
    expect(suggestSchoolOrderCode({ name: 'Delhi Public School', city: 'Gurugram' })).toBe('DPS_GUR');
    expect(suggestSchoolOrderCode({ name: "St. Xavier's School", city: 'Mumbai' })).toBe('SXS_MUM');
  });

  it('ignores OF, THE, AND, FOR and ampersands, in any case', () => {
    expect(suggestSchoolOrderCode({ name: 'The School of Arts & Science for Girls', city: 'Pune' })).toBe('SASG_PUN');
    expect(suggestSchoolOrderCode({ name: 'the Academy AND school', city: 'Pune' })).toBe('AS_PUN');
  });

  it('keeps the letters A-Z only, dropping accented ones as the server does, and stops at eight letters', () => {
    expect(suggestSchoolOrderCode({ name: 'École 2 Notre-Dame', city: 'Pondicherry' })).toBe('CN_PON');
    expect(suggestSchoolOrderCode({ name: 'A B C D E F G H I J', city: 'Agra' })).toBe('ABCDEFGH_AGR');
  });

  it('uses the first three letters of the first word that is not a stop word when the initials are too short', () => {
    expect(suggestSchoolOrderCode({ name: 'Springfield', city: 'Kochi' })).toBe('SPR_KOC');
    expect(suggestSchoolOrderCode({ name: 'The Heritage', city: 'Gurgaon' })).toBe('HER_GUR');
  });

  it('pads short initials with X, as SchoolOrderCode.derive does', () => {
    expect(suggestSchoolOrderCode({ name: 'X', city: 'Pune' })).toBe('XX_PUN');
    expect(suggestSchoolOrderCode({ name: '123', city: 'Pune' })).toBe('XX_PUN');
    expect(suggestSchoolOrderCode({ name: 'The', city: 'Pune' })).toBe('XX_PUN');
  });

  it('falls back from the city to the state, then to IN', () => {
    expect(suggestSchoolOrderCode({ name: 'Delhi Public School', city: ' ', state: 'Haryana' })).toBe('DPS_HAR');
    expect(suggestSchoolOrderCode({ name: 'Delhi Public School', city: '', state: '' })).toBe('DPS_IN');
    expect(suggestSchoolOrderCode({ name: 'Delhi Public School' })).toBe('DPS_IN');
  });

  it('suggests nothing until a name is typed', () => {
    expect(suggestSchoolOrderCode({ name: '', city: 'Pune' })).toBe('');
    expect(suggestSchoolOrderCode({ name: '   ', city: 'Pune' })).toBe('');
  });
});

describe('collision suffix (D12), as SchoolOrderCode.firstFree adds it', () => {
  it('keeps a free code, else takes the first free 2, 3 ... on the initials', () => {
    expect(firstFreeSchoolOrderCode('DPS_GUR', ['DS_HYD'])).toBe('DPS_GUR');
    expect(firstFreeSchoolOrderCode('DPS_GUR', ['DPS_GUR', null, 'dps2_gur'])).toBe('DPS3_GUR');
    expect(firstFreeSchoolOrderCode('', ['DPS_GUR'])).toBe('');
  });

  it('shortens the initials to keep them within eight characters', () => {
    expect(schoolOrderCodeWithSuffix('ABCDEFGH_AGR', 2)).toBe('ABCDEFG2_AGR');
    expect(schoolOrderCodeWithSuffix('ABCDEFGH_AGR', 12)).toBe('ABCDEF12_AGR');
    expect(isSchoolOrderCode(firstFreeSchoolOrderCode('ABCDEFGH_AGR', ['ABCDEFGH_AGR']))).toBe(true);
  });
});

describe('school order code rules', () => {
  it('accepts initials, an underscore and a location in capitals or digits', () => {
    for (const code of ['DPS_GUR', 'SXS2_MUM', 'AB_IN', 'ABCDEFGH_ABCDEF']) expect(isSchoolOrderCode(code)).toBe(true);
    for (const code of ['DPS-GUR', 'dps_gur', 'D_GUR', 'DPS_G', 'ABCDEFGHI_GUR', 'DPS_GURGAON', 'DPS GUR', '']) {
      expect(isSchoolOrderCode(code)).toBe(false);
    }
  });

  it('upper-cases and trims what is typed', () => {
    expect(normaliseSchoolOrderCode('  dps_gur ')).toBe('DPS_GUR');
  });

  it('allows blank when blank means "derive it", and explains a bad code', () => {
    expect(schoolOrderCodeError('', { allowBlank: true })).toBe('');
    expect(schoolOrderCodeError('', { allowBlank: false })).toBe('Enter an order code.');
    expect(schoolOrderCodeError('dps_gur', { allowBlank: false })).toBe('');
    expect(schoolOrderCodeError('DPS-GUR', { allowBlank: true })).toBe(SCHOOL_ORDER_CODE_HINT);
  });
});

describe('item codes (D8)', () => {
  it('are two to four capital letters', () => {
    for (const code of ['NB', 'FE', 'BBK', 'BCHX']) expect(isItemCode(code)).toBe(true);
    for (const code of ['N', 'NOTEB', 'nb', 'N1', 'N_B', '']) expect(isItemCode(code)).toBe(false);
    expect(normaliseItemCode(' bbk ')).toBe('BBK');
    expect(ITEM_CODE_HINT).toMatch(/2 to 4 capital letters/);
  });
});
