export interface ProductCategory {
  code: string; label: string; emoji: string; description: string;
  orderType: string; formEnabled: boolean; sortOrder: number; active: boolean;
  // Only paged categories (notebooks) collect a printed-page count per line.
  paged?: boolean;
}
export interface ProductOption {
  id: number; groupId: number; code: string; label: string; specText: string | null;
  widthMm: number | null; heightMm: number | null; specStatus: 'CONFIRMED' | 'PENDING_SPEC';
  sortOrder: number; active: boolean;
}
export interface ProductGroup {
  id: number; categoryCode: string; code: string; label: string; level: number;
  selectionType: string; required: boolean; scope: 'ORDER' | 'LINE'; active: boolean; options: ProductOption[];
  // SELECT groups pick from `options`; the others capture a typed value stored on the line's
  // `attributes` rather than a selection. `unit` is a display suffix such as "ft" or "gsm".
  inputType?: 'SELECT' | 'TEXT' | 'INTEGER' | 'DECIMAL'; unit?: string;
}
export interface ProductRule {
  id?: number; categoryCode?: string; ruleType: string; targetField?: string | null;
  matchOptions: Record<string, string>; params: Record<string, string | number>;
  priority: number; message: string; active?: boolean;
}
export interface FormDefinition {
  enabled: boolean; category: ProductCategory; groups: ProductGroup[];
  dependencies: { parentOptionId: number; childOptionId: number; allowed: boolean }[]; rules: ProductRule[];
}
export interface FormLine { selections: Record<string, string>; bookCount: number; pageCount: number }
export interface FormInput { orderSelections: Record<string, string>; lines: FormLine[] }
export type AssetKind = 'DESIGN' | 'PRE_DELIVERY_PHOTO';
export interface OrderAsset {
  id: number; assetKind: AssetKind; contentType: string; sizeBytes: number; originalFilename: string;
  contentUrl: string; uploadedAt: string; supersededAt?: string | null;
}
export type SelectionSnapshot = Record<string, string | { code: string; label: string; spec?: string; specText?: string }>;
export interface SavedLine {
  id: number; lineNo: number; optionSelections: SelectionSnapshot;
  requestedBookCount: number; bookCount: number; requestedPageCount: number; pageCount: number;
  unitPricePaise: number | null; lineTotalPaise: number | null;
}
export interface FormOrderDetail {
  order: { id: string; schoolId?: number; status: string; requiredByDate?: string; notes?: string;
    subtotal: number; gst: number; totalAmount: number; approvedDesignAssetId?: number; [key: string]: unknown };
  formDefinition: FormDefinition; orderSelections: SelectionSnapshot; lines: SavedLine[]; assets: OrderAsset[];
  formVersion: number; pricingStatus: string; version: number; quantityRuleResults: unknown[]; approvedDesignAssetId?: number | null;
}
export function selectionCodes(snapshot: SelectionSnapshot): Record<string, string> {
  return Object.fromEntries(Object.entries(snapshot).map(([key, value]) => [key, typeof value === 'string' ? value : value.code]));
}
export function selectionLabel(snapshot: SelectionSnapshot, key: string): string {
  const value = snapshot[key];
  return typeof value === 'string' ? value : value?.label || '';
}
export function selectionSpec(snapshot: SelectionSnapshot, key: string): string {
  const value = snapshot[key];
  return typeof value === 'string' ? '' : value?.spec || value?.specText || '';
}
