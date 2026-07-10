import { describe, expect, it } from 'vitest';
import { formatPaise, paiseToRupeeInput } from './utils';

describe('workspace money utilities', () => {
  it('formats paise as rupees with two-decimal precision', () => {
    expect(formatPaise(185)).toBe('1.85');
    expect(formatPaise(125000)).toBe('1,250.00');
  });

  it('keeps two decimals for payment input autofill', () => {
    expect(paiseToRupeeInput(185)).toBe('1.85');
    expect(paiseToRupeeInput(125000)).toBe('1250.00');
  });
});
