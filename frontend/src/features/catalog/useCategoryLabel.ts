import { useProductCategories } from './api';

/**
 * Order tables printed the stored catalogue code — REPORT_CARDS, BILLBOOKS — straight into the
 * Category column. The catalogue already carries each code's label, so read it from there and
 * humanise anything it does not know, rather than hardcoding a list that drifts as new
 * categories are seeded. Shared so the all-orders and approvals tables cannot disagree.
 */
export function useCategoryLabel(): (code?: string | null) => string {
  const catalog = useProductCategories();
  return (code) => {
    const key = String(code ?? '').trim();
    if (!key) return '—';
    const known = catalog.categories?.find((category) => category.code === key);
    if (known) return known.label;
    const words = key.replace(/_/g, ' ').toLowerCase();
    return words.charAt(0).toUpperCase() + words.slice(1);
  };
}
