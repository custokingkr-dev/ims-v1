import { useEffect, useState } from 'react';
import api from '../../services/api';
import type { FormDefinition, FormOrderDetail, ProductCategory } from './types';

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}
function hasStrings(value: Record<string, unknown>, keys: string[]): boolean {
  return keys.every((key) => typeof value[key] === 'string');
}
function isCategory(value: unknown): value is ProductCategory {
  return isRecord(value) && hasStrings(value, ['code', 'label', 'description', 'emoji', 'orderType'])
    && typeof value.active === 'boolean' && typeof value.formEnabled === 'boolean' && typeof value.sortOrder === 'number';
}
export function parseProductCategories(value: unknown): ProductCategory[] {
  if (!Array.isArray(value) || !value.every(isCategory)) throw new Error('Catalog data could not be read. Please retry.');
  return value;
}
export function parseProductFormDefinition(value: unknown): FormDefinition {
  const valid = isRecord(value) && typeof value.enabled === 'boolean' && isCategory(value.category)
    && Array.isArray(value.groups) && value.groups.every((group: unknown) => isRecord(group)
      && hasStrings(group, ['code', 'label', 'categoryCode', 'selectionType']) && ['ORDER', 'LINE'].includes(String(group.scope))
      && typeof group.id === 'number' && typeof group.level === 'number' && typeof group.active === 'boolean' && typeof group.required === 'boolean'
      && Array.isArray(group.options) && group.options.every((option: unknown) => isRecord(option)
        && hasStrings(option, ['code', 'label']) && typeof option.id === 'number' && typeof option.groupId === 'number'
        && typeof option.active === 'boolean' && typeof option.sortOrder === 'number'
        && ['CONFIRMED', 'PENDING_SPEC'].includes(String(option.specStatus))
        && (option.specText == null || typeof option.specText === 'string')))
    && Array.isArray(value.rules) && value.rules.every((rule: unknown) => isRecord(rule)
      && typeof rule.ruleType === 'string' && typeof rule.priority === 'number'
      && isRecord(rule.matchOptions) && Object.values(rule.matchOptions).every((option) => typeof option === 'string')
      && isRecord(rule.params) && Object.values(rule.params).every((parameter) => typeof parameter === 'string' || typeof parameter === 'number')
      && (rule.message == null || typeof rule.message === 'string'))
    && Array.isArray(value.dependencies) && value.dependencies.every((dependency: unknown) => isRecord(dependency)
      && typeof dependency.parentOptionId === 'number' && typeof dependency.childOptionId === 'number' && typeof dependency.allowed === 'boolean');
  if (!valid) throw new Error('Notebook options could not be read. Please retry.');
  return value as unknown as FormDefinition;
}
function isSelections(value: unknown): boolean {
  return isRecord(value) && Object.values(value).every((selection) => typeof selection === 'string'
    || (isRecord(selection) && hasStrings(selection, ['code', 'label']) && (selection.spec == null || typeof selection.spec === 'string')));
}
export function parseFormOrderDetail(value: unknown): FormOrderDetail {
  if (!isRecord(value) || !isRecord(value.order) || !hasStrings(value.order, ['id', 'status'])
    || !isSelections(value.orderSelections) || !Array.isArray(value.lines) || !value.lines.every((line: unknown) => isRecord(line)
      && typeof line.id === 'number' && typeof line.bookCount === 'number' && typeof line.pageCount === 'number' && isSelections(line.optionSelections))
    || !Array.isArray(value.assets) || !value.assets.every((asset: unknown) => isRecord(asset)
      && hasStrings(asset, ['assetKind', 'originalFilename', 'contentType']) && typeof asset.id === 'number' && typeof asset.sizeBytes === 'number')
    || typeof value.version !== 'number' || typeof value.pricingStatus !== 'string') {
    throw new Error('Order details could not be read. Please refresh the order.');
  }
  parseProductFormDefinition(value.formDefinition);
  return value as unknown as FormOrderDetail;
}

export function errorMessage(error: unknown, fallback = 'Unable to save. Please try again.'): string {
  const data = (error as { response?: { data?: { message?: string; fieldErrors?: Record<string, string> } } })?.response?.data;
  return (typeof data?.message === 'string' ? data.message : '')
    || (isRecord(data?.fieldErrors) ? Object.values(data.fieldErrors).filter((value) => typeof value === 'string').join(' ') : '')
    || (error instanceof Error ? error.message : fallback);
}
export function fieldErrorsFrom(error: unknown): Record<string, string> {
  const value = (error as { response?: { data?: { fieldErrors?: unknown } } })?.response?.data?.fieldErrors;
  return isRecord(value) ? Object.fromEntries(Object.entries(value).filter((entry): entry is [string, string] => typeof entry[1] === 'string')) : {};
}
export async function getFormOrder(id: string): Promise<FormOrderDetail> {
  return parseFormOrderDetail((await api.get<unknown>(`/supply/orders/${encodeURIComponent(id)}/form`)).data);
}
// Passing null holds the fetch, so a panel can ask for whichever category the user opened
// rather than being wired to one category at build time.
export function useProductFormDefinition(categoryCode: string | null) {
  const [definition, setDefinition] = useState<FormDefinition | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let current = true;
    setLoading(true); setError(''); setDefinition(null);
    if (!categoryCode) { setLoading(false); return () => { current = false; }; }
    api.get<unknown>(`/supply/product-catalog/forms/${encodeURIComponent(categoryCode)}`).then(({ data }) => {
      const parsed = parseProductFormDefinition(data);
      if (current) setDefinition(parsed);
    }).catch((e: unknown) => { if (current) setError(errorMessage(e, 'Unable to load the order options for this category.')); })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [categoryCode, attempt]);
  return { definition, loading, error, retry: () => setAttempt((value) => value + 1) };
}
export function useProductCategories() {
  const [categories, setCategories] = useState<ProductCategory[] | null>(null);
  const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let active = true; setError(''); setCategories(null);
    api.get<unknown>('/supply/product-catalog/categories').then(({ data }) => { const parsed = parseProductCategories(data); if (active) setCategories(parsed); })
      .catch((e: unknown) => { if (active) setError(errorMessage(e, 'Unable to load catalog.')); });
    return () => { active = false; };
  }, [attempt]);
  return { categories, error, retry: () => setAttempt((current) => current + 1) };
}
