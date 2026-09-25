import { describe, expect, it } from 'vitest';
import { estimateLine, type EstimateRates } from './reportCardEstimate';

// The rates seeded with the SHEET_V1 estimate rule.
const rates: EstimateRates = {
  model: 'SHEET_V1', a4Base: 16, a5Base: 8, a4PerSignature: 14, a5PerSignature: 7,
  foldingFee: 250, band1Max: 100, band1: 80, band2Max: 200, band2: 150, band3: 200,
  printRate: 6, printMinUnits: 150,
};

describe('report card estimate', () => {
  it('follows the source sheet for an A4 card with inner pages', () => {
    // qty 100, 8 inner pages, folding yes:
    //   d7 = 16 * 100 = 1600
    //   d8 = ceil(8/4) = 2 signatures -> 14 * 100 * 2 = 2800
    //   d9 = 250 (folding)
    //   e9 = pages != 0, qty < 150 -> 150 * 6 = 900
    //   cost/card = (1600 + 2800 + 900) / 100 = 53
    const line = estimateLine(rates, { size: 'A4', pages: 8, folding: true, quantity: 100 });
    expect(line.d7).toBe(1600);
    expect(line.d8).toBe(2800);
    expect(line.d9).toBe(250);
    expect(line.e9).toBe(900);
    expect(line.costPerCard).toBe(53);
    expect(line.lineTotal).toBe(5300);
  });

  it('excludes D9 from the cost even though it is shown', () => {
    // D9 is reference only, per the prototype's own note. If it were included the cost would differ.
    const line = estimateLine(rates, { size: 'A5', pages: 0, folding: false, quantity: 200 });
    // d7 = 8 * 200 = 1600; d8 = ceil(0/4) = 0 signatures -> 0; d9 = band2 = 150 (qty <= 200)
    // pages === 0 so e9 falls back to d9 = 150; cost = (1600 + 0 + 150) / 200 = 8.75
    expect(line.d9).toBe(150);
    expect(line.e9).toBe(150);
    expect(line.costPerCard).toBeCloseTo(8.75, 5);
  });

  it('uses the quantity bands when folding is not required', () => {
    expect(estimateLine(rates, { size: 'A4', pages: 0, folding: false, quantity: 50 }).d9).toBe(80);
    expect(estimateLine(rates, { size: 'A4', pages: 0, folding: false, quantity: 200 }).d9).toBe(150);
    expect(estimateLine(rates, { size: 'A4', pages: 0, folding: false, quantity: 201 }).d9).toBe(200);
  });

  it('charges per unit once the print minimum is passed', () => {
    // qty >= printMinUnits bills qty * printRate rather than the flat minimum.
    expect(estimateLine(rates, { size: 'A4', pages: 4, folding: false, quantity: 150 }).e9).toBe(900);
    expect(estimateLine(rates, { size: 'A4', pages: 4, folding: false, quantity: 300 }).e9).toBe(1800);
  });

  it('never divides by zero', () => {
    expect(estimateLine(rates, { size: 'A4', pages: 4, folding: true, quantity: 0 }).costPerCard).toBe(0);
  });
});
