/**
 * The codes superadmin sets up for order numbers (design D8, D11, D12). An order number reads
 * `<ItemCode>-<SchoolOrderCode>-<YY-MM-DD>-<NNNN>`, for example NB-DPS_GUR-26-11-13-0042.
 *
 * The server validates and owns every code: it derives a school's code when none is given, adds a 2–9 suffix on a
 * collision, and refuses a duplicate. These helpers only prefill a suggestion and stop the screen from sending a code
 * the server would refuse.
 */

import type { WorkflowDefinitionId } from '../../../generated/orderWorkflowClient';

/** `<Initials>_<Location>`; no hyphen, so an order number splits cleanly on "-". */
const SCHOOL_ORDER_CODE = /^[A-Z0-9]{2,8}_[A-Z0-9]{2,6}$/;
/** A catalogue item's code, e.g. NB, FE, BBK. */
const ITEM_CODE = /^[A-Z]{2,4}$/;

export const SCHOOL_ORDER_CODE_HINT = 'Use initials, an underscore and a location, like DPS_GUR: 2 to 8 then 2 to 6 capital letters or digits.';
export const SCHOOL_ORDER_CODE_HELPER = 'Used in order numbers like NB-DPS_GUR-26-11-13-0001. Changing it affects only new orders.';
export const ITEM_CODE_HINT = 'Use 2 to 4 capital letters, like NB or BBK. Leave it blank for no order numbers.';

const STOP_WORDS = new Set(['OF', 'THE', 'AND', 'FOR']);
const MAX_INITIALS = 8;

/** A word's letters A-Z, upper case, everything else dropped (accented letters too), exactly as SchoolOrderCode does. */
function lettersOf(word: string): string {
  return word.toUpperCase().replace(/[^A-Z]/g, '');
}

function words(text: string | null | undefined): string[] {
  return (text ?? '').split(/\s+/).map(lettersOf).filter(Boolean);
}

/** SchoolOrderCode.derive's initials: stop words never count, and fewer than 2 initials fall back, then pad with X. */
function initialsOf(name: string): string {
  if (!name.trim()) return '';
  const kept = words(name).filter((word) => !STOP_WORDS.has(word));
  const initials = kept.map((word) => word[0]).join('').slice(0, MAX_INITIALS);
  const head = initials.length >= 2 ? initials : (kept[0] ?? '').slice(0, 3);
  return head.length >= 2 ? head : `${head}XX`.slice(0, 2);
}

function locationOf(place: string | null | undefined): string {
  return lettersOf(place ?? '').slice(0, 3);
}

export interface SchoolOrderCodeSource {
  name: string;
  city?: string | null;
  state?: string | null;
}

/**
 * The code the server would derive (before any collision suffix): the first letter of each word of the name, ignoring
 * OF, THE, AND, FOR and "&", up to 8 letters (the first 3 letters of the first such word when that gives fewer than 2,
 * padded with X), then "_" and the first 3 letters of the city, else of the state, else IN. Empty until a name is typed.
 */
export function suggestSchoolOrderCode({ name, city, state }: SchoolOrderCodeSource): string {
  const initials = initialsOf(name);
  const location = [locationOf(city), locationOf(state)].find((part) => part.length >= 2) ?? 'IN';
  const code = `${initials}_${location}`;
  return SCHOOL_ORDER_CODE.test(code) ? code : '';
}

/** The code with collision suffix `n` (2 or more) on its initials, kept within 8 characters, like the server's (DPS2_GUR). */
export function schoolOrderCodeWithSuffix(code: string, n: number): string {
  const separator = code.indexOf('_');
  if (separator < 0 || n < 2) return code;
  const suffix = String(n);
  return code.slice(0, Math.min(separator, MAX_INITIALS - suffix.length)) + suffix + code.slice(separator);
}

/**
 * The suggestion as the server would store it among these codes: itself when free, else the first free suffixed form
 * (DPS2_GUR, DPS3_GUR ...). Empty for an empty suggestion.
 */
export function firstFreeSchoolOrderCode(code: string, taken: Iterable<string | null | undefined>): string {
  if (!code) return code;
  const used = new Set([...taken].filter((item): item is string => !!item).map(normaliseSchoolOrderCode));
  let candidate = code;
  for (let n = 2; used.has(candidate) && n < 100; n += 1) candidate = schoolOrderCodeWithSuffix(code, n);
  return candidate;
}

export function normaliseSchoolOrderCode(text: string): string {
  return text.trim().toUpperCase();
}

export function isSchoolOrderCode(text: string): boolean {
  return SCHOOL_ORDER_CODE.test(text);
}

/** The inline error for a typed school order code, or '' when it may be sent. Blank means "derive it" where allowed. */
export function schoolOrderCodeError(text: string, { allowBlank }: { allowBlank: boolean }): string {
  const code = normaliseSchoolOrderCode(text);
  if (!code) return allowBlank ? '' : 'Enter an order code.';
  return isSchoolOrderCode(code) ? '' : SCHOOL_ORDER_CODE_HINT;
}

export function normaliseItemCode(text: string): string {
  return text.trim().toUpperCase();
}

export function isItemCode(text: string): boolean {
  return ITEM_CODE.test(text);
}

/** The five templates, in the order Workflow set-up lists them (design D8). */
export const WORKFLOW_DEFINITIONS: ReadonlyArray<{ id: WorkflowDefinitionId; label: string }> = [
  { id: 'NOTEBOOK_ORDER_TRACKER', label: 'Notebook order workflow (12 steps)' },
  { id: 'FLIER_ORDER_WORKFLOW', label: 'Flier order workflow (9 steps)' },
  { id: 'FLEX_ORDER_WORKFLOW', label: 'Flex order workflow (9 steps)' },
  { id: 'BILL_BOOK_ORDER_WORKFLOW', label: 'Bill book order workflow (9 steps)' },
  { id: 'GENERAL_ITEM_ORDER_WORKFLOW', label: 'General item order workflow (9 steps)' },
];

export function workflowDefinitionLabel(id: string | null | undefined): string {
  if (!id) return 'None';
  return WORKFLOW_DEFINITIONS.find((definition) => definition.id === id)?.label ?? id;
}
