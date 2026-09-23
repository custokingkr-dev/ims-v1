import { describe, expect, it, vi } from 'vitest';
import { evaluateProductForm } from './productFormRules';
import type { FormInput, ProductRule } from './types';

interface Fixture { name: string; rules: ProductRule[]; input: FormInput; expected: { pageCounts?: number[]; bookCounts?: number[]; actualTotal?: number; requiredTotal?: number; valid?: boolean; error?: string } }
const { readFileSync } = await vi.importActual<{ readFileSync: (path: string, encoding: string) => string }>('node:fs');
const { cwd } = await vi.importActual<{ cwd: () => string }>('node:process');
const fixtures: Fixture[] = JSON.parse(readFileSync(`${cwd()}/../contracts/catalog-form-rule-fixtures.json`, 'utf8'));
describe('shared product form rules', () => {
  it.each(fixtures)('$name', ({ rules, input, expected }) => {
    const result = evaluateProductForm(rules, input);
    if (expected.error) { expect(Object.keys(result.fieldErrors).some((key) => key.endsWith(expected.error!))).toBe(true); return; }
    expect(result.fieldErrors).toEqual({});
    expect(result.lines.map((line) => line.pageCount)).toEqual(expected.pageCounts);
    if (expected.bookCounts) expect(result.lines.map((line) => line.bookCount)).toEqual(expected.bookCounts);
    expect(result.quantityResults.every((total) => total.valid)).toBe(expected.valid);
    if (expected.actualTotal !== undefined) expect(result.quantityResults[0]).toMatchObject({ actualTotal: expected.actualTotal, requiredTotal: expected.requiredTotal });
    else expect(result.quantityResults).toEqual([]);
  });
});
