/**
 * The report card cost estimate, transcribed from the 2026-09-25 prototype, which attributes the
 * rates to a source sheet. Every rate comes from the seeded COST_ESTIMATE rule rather than from
 * this file, so a corrected rate needs no release.
 *
 * This is the only form that shows a school a price, and it is an estimate: nothing here is typed
 * by the school, and the Custoking quote remains the price of record.
 */
export interface EstimateRates {
  model: string;
  a4Base: number; a5Base: number;
  a4PerSignature: number; a5PerSignature: number;
  foldingFee: number;
  band1Max: number; band1: number;
  band2Max: number; band2: number;
  band3: number;
  printRate: number; printMinUnits: number;
}

export interface EstimateInput { size: string; pages: number; folding: boolean; quantity: number }
export interface EstimatedLine { d7: number; d8: number; d9: number; e9: number; costPerCard: number; lineTotal: number }

/** Inner pages are imposed in signatures of four, so nine pages still costs three signatures. */
const signatures = (pages: number) => Math.ceil(pages / 4);

export function estimateLine(rates: EstimateRates, input: EstimateInput): EstimatedLine {
  const { size, pages, folding, quantity } = input;
  const d7 = (size === 'A4' ? rates.a4Base : size === 'A5' ? rates.a5Base : 0) * quantity;
  const perSignature = size === 'A4' ? rates.a4PerSignature : size === 'A5' ? rates.a5PerSignature : 0;
  const d8 = perSignature * quantity * signatures(pages);
  const d9 = folding ? rates.foldingFee
    : quantity <= rates.band1Max ? rates.band1
      : quantity <= rates.band2Max ? rates.band2
        : rates.band3;
  // With no inner pages there is nothing to print, so this falls back to D9 rather than charging
  // the print minimum.
  const e9 = pages !== 0
    ? (quantity < rates.printMinUnits ? rates.printMinUnits * rates.printRate : quantity * rates.printRate)
    : d9;
  // D9 is shown for reference and deliberately excluded from the total, per the prototype's note.
  const costPerCard = quantity > 0 ? (d7 + d8 + e9) / quantity : 0;
  return { d7, d8, d9, e9, costPerCard, lineTotal: costPerCard * quantity };
}

/** Reads the rates off a seeded COST_ESTIMATE rule, or null when a category has no estimate. */
export function estimateRatesFrom(rules: { ruleType: string; params: Record<string, string | number> }[]): EstimateRates | null {
  const rule = rules.find((r) => r.ruleType === 'COST_ESTIMATE');
  if (!rule) return null;
  const number = (key: string) => Number(rule.params[key]);
  const rates = {
    model: String(rule.params.model),
    a4Base: number('a4Base'), a5Base: number('a5Base'),
    a4PerSignature: number('a4PerSignature'), a5PerSignature: number('a5PerSignature'),
    foldingFee: number('foldingFee'),
    band1Max: number('band1Max'), band1: number('band1'),
    band2Max: number('band2Max'), band2: number('band2'),
    band3: number('band3'),
    printRate: number('printRate'), printMinUnits: number('printMinUnits'),
  };
  // A missing or malformed rate would silently quote zero, so show nothing instead.
  const complete = Object.entries(rates).every(([key, value]) => key === 'model' || Number.isFinite(value));
  return complete && rates.model === 'SHEET_V1' ? rates : null;
}
