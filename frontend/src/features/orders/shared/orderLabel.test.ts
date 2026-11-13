import { describe, expect, it } from 'vitest';
import { asOrderNumber, orderAccessibleName, orderHeadline, orderLabel, orderReference, parseOrderNumber } from './orderLabel';

const numbered = { orderId: 'CK-1015', orderNumber: 'NB-DPS_GUR-26-11-13-0042' };

describe('order labels (D14)', () => {
  it('heads with the order number and keeps the CK id as a reference beneath it', () => {
    expect(orderHeadline(numbered)).toBe('NB-DPS_GUR-26-11-13-0042');
    expect(orderReference(numbered)).toBe('Ref CK-1015');
    expect(orderLabel(numbered)).toEqual({ headline: 'NB-DPS_GUR-26-11-13-0042', reference: 'Ref CK-1015' });
  });

  it('falls back to the CK id, with no reference line, when there is no number', () => {
    for (const order of [{ orderId: 'CK-1016' }, { orderId: 'CK-1016', orderNumber: null }, { orderId: 'CK-1016', orderNumber: '  ' }]) {
      expect(orderLabel(order)).toEqual({ headline: 'CK-1016', reference: null });
    }
  });

  it('names the order once for assistive technology', () => {
    expect(orderAccessibleName(numbered)).toBe('Order NB-DPS_GUR-26-11-13-0042, reference CK-1015');
    expect(orderAccessibleName({ orderId: 'CK-1016' })).toBe('Order CK-1016');
  });
});

describe('order numbers (D11, D12)', () => {
  it('splits a number into item code, school code, placement date and serial', () => {
    expect(parseOrderNumber('NB-DPS_GUR-26-11-13-0042')).toEqual({
      itemCode: 'NB', schoolCode: 'DPS_GUR', placedOn: '2026-11-13', serial: 42,
    });
    expect(parseOrderNumber('BBK-SXS2_MUM-27-01-05-12345')).toEqual({
      itemCode: 'BBK', schoolCode: 'SXS2_MUM', placedOn: '2027-01-05', serial: 12345,
    });
  });

  it('rejects text that is not an order number', () => {
    for (const text of ['CK-1015', 'NB-2026-0042', 'NB-DPS-GUR-26-11-13-0042', 'NB-DPS_GUR-26-13-01-0042',
      'NB-DPS_GUR-26-02-30-0001', 'NB-DPS_GUR-26-11-13-042', 'nb-dps_gur-26-11-13-0042', '']) {
      expect(parseOrderNumber(text)).toBeNull();
    }
  });

  it('recognises a pasted number in any case and with surrounding space, for the by-number lookup', () => {
    expect(asOrderNumber('  nb-dps_gur-26-11-13-0042 ')).toBe('NB-DPS_GUR-26-11-13-0042');
    expect(asOrderNumber('CK-1015')).toBeNull();
    expect(asOrderNumber('delhi public')).toBeNull();
  });
});
