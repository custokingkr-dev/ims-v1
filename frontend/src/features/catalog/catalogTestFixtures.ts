import type { FormDefinition, FormOrderDetail, ProductOption } from './types';
const options = (groupId: number, values: [string, string][]): ProductOption[] => values.map(([code, label], i) => ({ id: groupId * 100 + i, groupId, code, label, specText: '', widthMm: null, heightMm: null, specStatus: ['KING', 'DRAWING_BOOK'].includes(code) ? 'PENDING_SPEC' : 'CONFIRMED', sortOrder: i, active: true }));
export const notebookDefinition: FormDefinition = {
  enabled: true, category: { code: 'NOTEBOOKS', label: 'Notebooks', emoji: '', description: 'Notebook orders', orderType: 'Recurring', formEnabled: true, active: true, sortOrder: 2 }, dependencies: [],
  groups: [
    { id: 1, categoryCode: 'NOTEBOOKS', code: 'CUSTOMIZATION', label: 'Customization', scope: 'ORDER', level: 1, required: true, active: true, selectionType: 'SINGLE', options: options(1, [['CUSTOMIZED', 'Customized'], ['NON_CUSTOMIZED', 'Non-customized']]) },
    { id: 2, categoryCode: 'NOTEBOOKS', code: 'SIZE', label: 'Size', scope: 'LINE', level: 2, required: true, active: true, selectionType: 'SINGLE', options: options(2, [['LONG', 'Long'], ['JUMBO_LONG', 'Jumbo Long'], ['KING', 'King'], ['JUMBO_KING', 'Jumbo King'], ['FA_A4', 'FA / A4 notebook'], ['DRAWING_BOOK', 'Drawing book']]) },
    { id: 3, categoryCode: 'NOTEBOOKS', code: 'RULING', label: 'Ruling', scope: 'LINE', level: 3, required: true, active: true, selectionType: 'SINGLE', options: options(3, Array.from({ length: 15 }, (_, i) => [i === 0 ? 'SINGLE_RULE' : `RULE_${i}`, i === 0 ? 'Single rule' : `Ruling ${i}`])) },
  ],
  rules: [
    { id: 1, categoryCode: 'NOTEBOOKS', ruleType: 'REQUIRE_QUANTITY_TOTAL', targetField: 'BOOK_COUNT', matchOptions: { CUSTOMIZATION: 'CUSTOMIZED' }, params: { value: 1000, comparison: 'EQ', scope: 'ORDER', stage: 'ON_PLACE' }, priority: 1, message: '', active: true },
    { id: 2, categoryCode: 'NOTEBOOKS', ruleType: 'ROUND_TO_MULTIPLE', targetField: 'PAGE_COUNT', matchOptions: {}, params: { multiple: 7, mode: 'NEAREST', minimum: 7 }, priority: 2, message: '', active: true },
    { id: 3, categoryCode: 'NOTEBOOKS', ruleType: 'REQUIRE_ASSET', matchOptions: { CUSTOMIZATION: 'CUSTOMIZED' }, params: { assetKind: 'DESIGN', stage: 'ON_PLACE' }, priority: 3, message: '', active: true },
    { id: 4, categoryCode: 'NOTEBOOKS', ruleType: 'REQUIRE_ASSET', matchOptions: { CUSTOMIZATION: 'CUSTOMIZED' }, params: { assetKind: 'PRE_DELIVERY_PHOTO', stage: 'BEFORE_DELIVERY' }, priority: 4, message: '', active: true },
  ],
};
export function savedNotebook(status = 'DRAFT', customized = true): FormOrderDetail {
  return { order: { id: 'ORD-42', schoolId: 7, status, subtotal: 0, gst: 0, totalAmount: 0 }, formDefinition: notebookDefinition, formVersion: 2, pricingStatus: 'PENDING_PRICING', version: 0,
    orderSelections: { CUSTOMIZATION: { code: customized ? 'CUSTOMIZED' : 'NON_CUSTOMIZED', label: customized ? 'Customized' : 'Non-customized' } },
    lines: [{ id: 101, lineNo: 1, optionSelections: { SIZE: { code: 'LONG', label: 'Long' }, RULING: { code: 'SINGLE_RULE', label: 'Single rule' } }, requestedBookCount: 1000, bookCount: 1000, requestedPageCount: 198, pageCount: 196, unitPricePaise: null, lineTotalPaise: null }], assets: [], quantityRuleResults: [] };
}
