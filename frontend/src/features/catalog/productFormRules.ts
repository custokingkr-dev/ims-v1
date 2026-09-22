import type { FormInput, ProductRule } from './types';

export function matches(match: Record<string, string>, selections: Record<string, string>): boolean {
  return Object.entries(match).every(([key, value]) => selections[key] === value);
}
export function roundToMultiple(value: number, multiple: number, mode: string, minimum = 1): number {
  if (!Number.isSafeInteger(value) || value <= 0 || !Number.isSafeInteger(multiple) || multiple <= 0) return value;
  const remainder = value % multiple;
  const rounded = mode === 'UP' ? value + (remainder ? multiple - remainder : 0)
    : mode === 'DOWN' || remainder <= Math.floor(multiple / 2) ? value - remainder : value + multiple - remainder;
  return Math.max(minimum, rounded);
}
export function evaluateProductForm(rules: ProductRule[], input: FormInput) {
  const sorted = rules.filter((rule) => rule.active !== false).slice().sort((a, b) => a.priority - b.priority || (a.id || 0) - (b.id || 0));
  const fieldErrors: Record<string, string> = {};
  const lines = input.lines.map((line, index) => {
    const result = { ...line, selections: { ...line.selections }, requestedBookCount: line.bookCount, requestedPageCount: line.pageCount };
    if (!Number.isSafeInteger(line.bookCount) || line.bookCount <= 0) fieldErrors[`lines[${index}].bookCount`] = 'Enter a positive whole number of books.';
    if (!Number.isSafeInteger(line.pageCount) || line.pageCount <= 0) fieldErrors[`lines[${index}].pageCount`] = 'Enter a positive whole number of printed pages.';
    const applicable = sorted.filter((rule) => matches(rule.matchOptions, { ...line.selections, ...input.orderSelections }));
    for (const rule of applicable.filter((r) => r.ruleType === 'ROUND_TO_MULTIPLE')) {
      const field = rule.targetField === 'BOOK_COUNT' ? 'bookCount' : 'pageCount';
      result[field] = roundToMultiple(result[field], Number(rule.params.multiple), String(rule.params.mode), Number(rule.params.minimum || 1));
    }
    for (const rule of applicable.filter((r) => ['MIN_VALUE', 'MAX_VALUE'].includes(r.ruleType))) {
      const field = rule.targetField === 'BOOK_COUNT' ? 'bookCount' : 'pageCount';
      if ((rule.ruleType === 'MIN_VALUE' && result[field] < Number(rule.params.value)) || (rule.ruleType === 'MAX_VALUE' && result[field] > Number(rule.params.value))) {
        fieldErrors[`lines[${index}].${field}`] = rule.message || `Value must be ${rule.ruleType === 'MIN_VALUE' ? 'at least' : 'at most'} ${rule.params.value}.`;
      }
    }
    return result;
  });
  const quantityResults = sorted.filter((r) => r.ruleType === 'REQUIRE_QUANTITY_TOTAL').flatMap((rule) => {
    const matching = lines.filter((line) => matches(rule.matchOptions, { ...line.selections, ...input.orderSelections }));
    if (!matching.length && !matches(rule.matchOptions, input.orderSelections)) return [];
    const actualTotal = matching.reduce((total, line) => total + (Number.isFinite(line.bookCount) ? line.bookCount : 0), 0);
    const requiredTotal = Number(rule.params.value);
    return [{ ruleId: rule.id, actualTotal, requiredTotal, difference: requiredTotal - actualTotal, valid: actualTotal === requiredTotal }];
  });
  return { lines, quantityResults, fieldErrors };
}
