import type { FormDefinition, FormOrderDetail, ProductOption } from './types';
const options = (groupId: number, values: [string, string][]): ProductOption[] => values.map(([code, label], i) => ({ id: groupId * 100 + i, groupId, code, label, specText: '', widthMm: null, heightMm: null, specStatus: ['KING', 'DRAWING_BOOK'].includes(code) ? 'PENDING_SPEC' : 'CONFIRMED', sortOrder: i, active: true }));
export const notebookDefinition: FormDefinition = {
  enabled: true, category: { code: 'NOTEBOOKS', label: 'Notebooks', emoji: '', description: 'Notebook orders', orderType: 'Recurring', formEnabled: true, active: true, sortOrder: 2 }, dependencies: [],
  groups: [
    { id: 1, categoryCode: 'NOTEBOOKS', code: 'CUSTOMIZATION', label: 'Category', render: 'SEGMENTED', scope: 'ORDER', level: 1, required: true, active: true, selectionType: 'SINGLE', options: options(1, [['CUSTOMIZED', 'Custom'], ['NON_CUSTOMIZED', 'Wholesale']]) },
    { id: 2, categoryCode: 'NOTEBOOKS', code: 'SIZE', label: 'Size', render: 'SELECT', scope: 'LINE', level: 2, required: true, active: true, selectionType: 'SINGLE', options: options(2, [['LONG', 'Long'], ['JUMBO_LONG', 'Jumbo Long'], ['KING', 'King'], ['JUMBO_KING', 'Jumbo King'], ['FA_A4', 'FA / A4 notebook'], ['DRAWING_BOOK', 'Drawing book']]) },
    { id: 3, categoryCode: 'NOTEBOOKS', code: 'RULING', label: 'Ruling', render: 'MATRIX', scope: 'LINE', level: 3, required: true, active: true, selectionType: 'SINGLE', options: options(3, Array.from({ length: 15 }, (_, i) => [i === 0 ? 'SINGLE_RULE' : `RULE_${i}`, i === 0 ? 'Single rule' : `Ruling ${i}`])) },
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

/** A non-paged category whose lines carry typed measurements rather than only selections. */
export const flexDefinition: FormDefinition = {
  enabled: true, dependencies: [], rules: [],
  category: { code: 'FLEX', label: 'Flex', emoji: '', description: 'Flex signage', orderType: 'One-time', formEnabled: true, paged: false, active: true, sortOrder: 10 },
  groups: [
    { id: 11, categoryCode: 'FLEX', code: 'FLEX_TYPE', label: 'Type of flex', render: 'SEGMENTED', scope: 'LINE', level: 1, required: true, active: true, selectionType: 'SINGLE', inputType: 'SELECT', unit: '', options: options(11, [['STAR', 'Star flex'], ['NORMAL', 'Normal flex']]) },
    { id: 12, categoryCode: 'FLEX', code: 'LENGTH_FT', label: 'Length', render: 'FIELD', scope: 'LINE', level: 2, required: true, active: true, selectionType: 'SINGLE', inputType: 'DECIMAL', unit: 'ft', options: [] },
    { id: 13, categoryCode: 'FLEX', code: 'BREADTH_FT', label: 'Breadth', render: 'FIELD', scope: 'LINE', level: 3, required: true, active: true, selectionType: 'SINGLE', inputType: 'DECIMAL', unit: 'ft', options: [] },
  ],
};

/** Ties: a line-scoped segmented choice plus a length matrix, with no order-scope group at all. */
export const tieDefinition: FormDefinition = {
  enabled: true, dependencies: [], rules: [],
  category: { code: 'TIES', label: 'Ties', emoji: '', description: 'School ties', orderType: 'Recurring', formEnabled: true, paged: false, notesEnabled: false, active: true, sortOrder: 12 },
  groups: [
    { id: 21, categoryCode: 'TIES', code: 'TIE_TYPE', label: 'Tie type', render: 'SEGMENTED', scope: 'LINE', level: 1, required: true, active: true, selectionType: 'SINGLE', options: options(21, [['SATIN_WITH_LOGO', 'Satin Tie with logo'], ['SATIN', 'Satin Tie'], ['CLOTH', 'Cloth Tie'], ['READYMADE', 'Readymade ties']]) },
    { id: 22, categoryCode: 'TIES', code: 'LENGTH', label: 'Length', render: 'MATRIX', scope: 'LINE', level: 2, required: true, active: true, selectionType: 'SINGLE', options: options(22, [['LEN_10', '10 inch'], ['LEN_11', '11 inch'], ['LEN_12', '12 inch'], ['LEN_14', '14 inch'], ['LEN_16', '16 inch'], ['LONG_TIE', 'Long Tie']]) },
  ],
};

/** Report cards: three segmented choices, a minimum of 50, and the only estimate in the catalog. */
export const reportCardDefinition: FormDefinition = {
  enabled: true, dependencies: [],
  category: { code: 'REPORT_CARDS', label: 'Report Cards', emoji: '', description: 'Report cards', orderType: 'Recurring', formEnabled: true, paged: false, notesEnabled: false, active: true, sortOrder: 14 },
  groups: [
    { id: 31, categoryCode: 'REPORT_CARDS', code: 'SIZE', label: 'After folding size', render: 'SEGMENTED', scope: 'LINE', level: 1, required: true, active: true, selectionType: 'SINGLE', options: options(31, [['A4', 'A4'], ['A5', 'A5']]) },
    { id: 32, categoryCode: 'REPORT_CARDS', code: 'INNER_PAGES', label: 'Inner pages (multiple of 4)', render: 'SEGMENTED', scope: 'LINE', level: 2, required: true, active: true, selectionType: 'SINGLE', options: options(32, [['P0', '0'], ['P4', '4'], ['P8', '8']]) },
    { id: 33, categoryCode: 'REPORT_CARDS', code: 'FOLDING', label: 'Folding required', render: 'SEGMENTED', scope: 'LINE', level: 3, required: true, active: true, selectionType: 'SINGLE', options: options(33, [['YES', 'Yes'], ['NO', 'No']]) },
  ],
  rules: [
    { id: 41, categoryCode: 'REPORT_CARDS', ruleType: 'MIN_VALUE', targetField: 'BOOK_COUNT', matchOptions: {}, params: { value: 50 }, priority: 10, message: 'Minimum order is 50 report cards per line', active: true },
    { id: 42, categoryCode: 'REPORT_CARDS', ruleType: 'COST_ESTIMATE', matchOptions: {}, priority: 90, message: 'Estimate only.', active: true,
      params: { model: 'SHEET_V1', a4Base: 16, a5Base: 8, a4PerSignature: 14, a5PerSignature: 7, foldingFee: 250, band1Max: 100, band1: 80, band2Max: 200, band2: 150, band3: 200, printRate: 6, printMinUnits: 150 } },
  ],
};
