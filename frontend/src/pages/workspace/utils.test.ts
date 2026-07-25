import { describe, expect, it } from 'vitest';
import { formatAddress, formatPaise, paiseToRupeeInput } from './utils';

describe('workspace money utilities', () => {
  it('formats paise as rupees with two-decimal precision', () => {
    expect(formatPaise(185)).toBe('1.85');
    expect(formatPaise(125000)).toBe('1,250.00');
  });

  it('keeps two decimals for payment input autofill', () => {
    expect(paiseToRupeeInput(185)).toBe('1.85');
    expect(paiseToRupeeInput(125000)).toBe('1250.00');
  });

  it('formats structured and imported full addresses', () => {
    expect(formatAddress({
      houseNumber: '17-8-547',
      locality: 'Shah Colony',
      city: 'Hyderabad',
      state: 'Telangana',
    })).toBe('17-8-547, Shah Colony, Hyderabad, Telangana');
    expect(formatAddress({
      houseNumber: null,
      street: null,
      locality: null,
      city: null,
      state: null,
      pinCode: null,
      full: '17-8-547, Shah Colony, Hyderabad, Telangana, INDIA',
    })).toBe('17-8-547, Shah Colony, Hyderabad, Telangana, INDIA');
  });
});
