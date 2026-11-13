import type { DefinitionId, ProductCategory } from '../workflow/types';

/** One item the list can filter by: its code (NB), its catalogue category (NOTEBOOKS), name and template. */
export interface ItemChoice {
  code: string;
  category: string;
  label: string;
  definitionId: DefinitionId;
}

/** The SOP's item codes, in the SOP's order: shown as chips; any other workflow item goes under "More". */
export const PRIMARY_ITEM_CODES = ['NB', 'FE', 'FX', 'BBK', 'CF', 'FB', 'RC', 'BCH'];

/** What the catalogue says before it has loaded, or if it cannot be read (design D8). */
export const FALLBACK_ITEMS: ItemChoice[] = [
  { code: 'NB', category: 'NOTEBOOKS', label: 'Notebooks', definitionId: 'NOTEBOOK_ORDER_TRACKER' },
  { code: 'FE', category: 'FLIERS', label: 'Fliers', definitionId: 'FLIER_ORDER_WORKFLOW' },
  { code: 'FX', category: 'FLEX', label: 'Flex', definitionId: 'FLEX_ORDER_WORKFLOW' },
  { code: 'BBK', category: 'BILLBOOKS', label: 'Bill books', definitionId: 'BILL_BOOK_ORDER_WORKFLOW' },
  { code: 'CF', category: 'CERTIFICATES', label: 'Certificates', definitionId: 'GENERAL_ITEM_ORDER_WORKFLOW' },
  { code: 'FB', category: 'FLUTE_BOARDS', label: 'Flute boards', definitionId: 'GENERAL_ITEM_ORDER_WORKFLOW' },
  { code: 'RC', category: 'REPORT_CARDS', label: 'Report cards', definitionId: 'GENERAL_ITEM_ORDER_WORKFLOW' },
  { code: 'BCH', category: 'BROCHURES', label: 'Brochures', definitionId: 'GENERAL_ITEM_ORDER_WORKFLOW' },
  { code: 'BLT', category: 'BELTS', label: 'Belts', definitionId: 'GENERAL_ITEM_ORDER_WORKFLOW' },
  { code: 'STD', category: 'STANDEES', label: 'Standees', definitionId: 'GENERAL_ITEM_ORDER_WORKFLOW' },
];

/** Every catalogue category that runs a workflow and has an item code, SOP items first, then by the catalogue's order. */
export function itemChoices(categories: ProductCategory[] | null): ItemChoice[] {
  if (!categories) return FALLBACK_ITEMS;
  const choices = categories
    .filter((category) => category.workflowDefinition && category.orderCode)
    .sort((left, right) => (left.sortOrder ?? 0) - (right.sortOrder ?? 0))
    .map((category) => ({
      code: String(category.orderCode),
      category: category.code,
      label: category.label,
      definitionId: category.workflowDefinition as DefinitionId,
    }));
  if (!choices.length) return FALLBACK_ITEMS;
  const rank = (code: string) => {
    const index = PRIMARY_ITEM_CODES.indexOf(code);
    return index < 0 ? PRIMARY_ITEM_CODES.length : index;
  };
  return choices.sort((left, right) => rank(left.code) - rank(right.code));
}

export function splitItems(choices: ItemChoice[]): { primary: ItemChoice[]; more: ItemChoice[] } {
  return {
    primary: choices.filter((choice) => PRIMARY_ITEM_CODES.includes(choice.code)),
    more: choices.filter((choice) => !PRIMARY_ITEM_CODES.includes(choice.code)),
  };
}
