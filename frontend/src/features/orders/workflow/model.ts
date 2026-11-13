import type { ChecklistSpec, FieldSpec, OrderWorkflowSummary, OrderWorkflowView, Stage, StepSpec, StepView, Tab, WorkflowTemplate } from './types';

/**
 * Pure rules the order workflow screen applies to what the server sends. Nothing here decides what may be saved or
 * completed: the server does, and says so in each step's `can` and `unmet`. These only turn the view and its template into
 * what the screen shows.
 */

const ALL_TABS: Tab[] = ['WORK', 'SCHOOL_VENDOR', 'CHECKLIST', 'ACTIVITY'];

/** A spec for a step the template does not describe (a drifted template): shown, never edited, so nothing is lost. */
function fallbackSpec(step: StepView): StepSpec {
  return {
    order: step.step, code: step.code, title: step.title, description: '', phase: step.phase, defaultSla: null, customisedOnly: step.customisedOnly,
    icon: '', tabs: ALL_TABS, blocks: [{ type: 'LEGACY_DETAILS', title: null, fields: null, checklist: null, save: null, categories: null, suggestedByItem: null }],
    fields: {}, checklists: {}, gates: [], signOff: null, superadminCompletes: false,
  };
}

/** The order's path: each step the server lists, with its template spec. */
export function stagesOf(view: OrderWorkflowView, template: WorkflowTemplate): Stage[] {
  return view.steps.map((step) => ({ view: step, spec: template.steps.find((spec) => spec.order === step.step) ?? fallbackSpec(step) }));
}

/** The stage the screen opens on: the active one, else the last (a finished order). */
export function defaultStageOrder(view: OrderWorkflowView): number {
  return view.progress.currentStep ?? view.steps[view.steps.length - 1]?.step ?? 1;
}

export function tabsOf(spec: StepSpec): Tab[] {
  const tabs = spec.tabs?.length ? spec.tabs : ALL_TABS;
  return ALL_TABS.filter((tab) => tabs.includes(tab));
}

export const TAB_LABELS: Record<Tab, string> = {
  WORK: 'Work area',
  SCHOOL_VENDOR: 'School & vendor',
  CHECKLIST: 'Checklist',
  ACTIVITY: 'Activity history',
};

/** A saved value as an input shows it. */
export function valueText(value: unknown): string {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return '';
}

/** What a typed value is sent as: blank clears (null), numbers are numbers, everything else is trimmed text. */
export function fieldValueForSave(spec: FieldSpec, text: string): unknown {
  const trimmed = text.trim();
  if (!trimmed) return null;
  if (spec.kind === 'WHOLE') return /^-?\d+$/.test(trimmed) ? Number(trimmed) : trimmed;
  if (spec.kind === 'NUMBER') return Number.isFinite(Number(trimmed)) ? Number(trimmed) : trimmed;
  return spec.kind === 'WORDS' || spec.kind === 'TEXT' ? text : trimmed;
}

/** The ticked item indexes a checklist has saved. */
export function checkedItems(data: Record<string, unknown>, key: string): number[] {
  const value = data[key];
  if (!Array.isArray(value)) return [];
  return [...new Set(value.filter((item): item is number => Number.isInteger(item)))].sort((left, right) => left - right);
}

export interface ChecklistProgress { done: number; total: number; percent: number; complete: boolean }

/** N of M ticked, counting the sign-off item as ticked only when the stage carries a sign-off. */
export function checklistProgress(spec: ChecklistSpec, checked: number[], signedOff: boolean): ChecklistProgress {
  const total = spec.items.length;
  const ticked = new Set(checked.filter((index) => index >= 0 && index < total && index !== spec.signOffItem));
  if (spec.signOffItem != null && signedOff) ticked.add(spec.signOffItem);
  const done = ticked.size;
  return { done, total, percent: total ? Math.round((done / total) * 100) : 0, complete: total > 0 && done === total };
}

/** The legacy fields of a step that hold a saved value: shown read-only under "Earlier details". */
export function legacyEntries(stage: Stage): Array<{ key: string; label: string; value: string }> {
  return Object.entries(stage.spec.fields)
    .filter(([key, spec]) => spec.legacy && valueText(stage.view.data[key]).trim())
    .map(([key, spec]) => ({ key, label: spec.label, value: valueText(stage.view.data[key]) }));
}

/** The first stage on the path whose template declares this step code, or that declares this field. */
export function stageWithField(stages: Stage[], field: string): Stage | undefined {
  return stages.find((stage) => field in stage.spec.fields);
}

export function stageByCode(stages: Stage[], code: string): Stage | undefined {
  return stages.find((stage) => stage.spec.code === code || stage.view.code === code);
}

export function initials(name: string | null | undefined): string {
  const words = (name ?? '').trim().split(/\s+/).filter(Boolean);
  if (!words.length) return '?';
  return (words.length === 1 ? words[0].slice(0, 2) : `${words[0][0]}${words[words.length - 1][0]}`).toUpperCase();
}

/** "flier" → "fliers", "bill book" → "bill books", "flex" → "flexes", "notebook" → "notebooks". */
export function pluralise(noun: string): string {
  const word = noun.trim();
  if (!word) return 'items';
  if (/(s|x|z|ch|sh)$/i.test(word)) return `${word}es`;
  if (/[^aeiou]y$/i.test(word)) return `${word.slice(0, -1)}ies`;
  return `${word}s`;
}

export function capitalise(text: string): string {
  return text ? text[0].toUpperCase() + text.slice(1) : text;
}

const DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/;
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** A yyyy-mm-dd date as "18 Nov 2026", read as a calendar date (no time zone can shift it); null when it is not one. */
export function formatDay(value: string | null | undefined): string | null {
  const match = DATE_ONLY.exec(value ?? '');
  if (!match) return null;
  const month = Number(match[2]);
  if (month < 1 || month > 12) return null;
  return `${Number(match[3])} ${MONTHS[month - 1]} ${match[1]}`;
}

export interface CompletionEstimate { value: string; caption: string }

/**
 * The Estimated completion tile (section 4.3): a revised completion date saved during production follow-up wins, then the
 * planning target date, then the date the school asked for on its order.
 */
export function estimatedCompletion(view: OrderWorkflowView, summary: OrderWorkflowSummary | null): CompletionEstimate {
  const latest = (field: string) => [...view.steps].reverse().map((step) => formatDay(valueText(step.data[field]))).find(Boolean);
  const revised = latest('revisedCompletion');
  if (revised) return { value: revised, caption: 'Revised completion date' };
  const target = latest('targetCompletion');
  if (target) return { value: target, caption: 'Target completion date' };
  const required = formatDay(summary?.requiredByDate ?? null);
  if (required) return { value: required, caption: 'Required by (from order)' };
  return { value: '—', caption: 'No target date yet' };
}

/** Bytes as "18.4 MB" / "640 KB" / "12 B", in binary units like the server's limits. */
export function formatBytes(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1).replace(/\.0$/, '')} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${bytes} B`;
}

export function supersededOrCurrent<T extends { supersededAt?: string | null }>(files: T[]): { current: T[]; removed: T[] } {
  return { current: files.filter((file) => !file.supersededAt), removed: files.filter((file) => !!file.supersededAt) };
}

/**
 * The people this order names, by user id: its point of contact, each stage's assignee, completer, last saver and
 * sign-off, every note's author and the design decider, plus the signed-in user. Used to name someone the order's other
 * sources give only by id (a design file's uploader).
 */
export function peopleOf(view: OrderWorkflowView, me?: { userId: number; fullName: string }): Map<number, string> {
  const people = new Map<number, string>();
  const add = (id: number | null | undefined, name: string | null | undefined) => {
    if (id != null && name?.trim() && !people.has(id)) people.set(id, name.trim());
  };
  if (me) add(me.userId, me.fullName);
  add(view.assigneeUserId, view.assigneeName);
  add(view.design.decidedBy, view.design.decidedByName);
  for (const step of view.steps) {
    add(step.assigneeUserId, step.assigneeName);
    add(step.completedBy, step.completedByName);
    add(step.dataSavedBy, step.dataSavedByName);
    add(step.signOff?.by, step.signOff?.byName);
    for (const note of step.notes) add(note.authorUserId, note.authorName);
  }
  for (const note of view.internalNotes) add(note.authorUserId, note.authorName);
  return people;
}

/**
 * Who uploaded (or removed) a design file, by name. School-core lists the uploader and the remover as user ids; each is
 * named from `people`, else "User 42" like the order's other unnamed authors. Digits and a name already given are still
 * read, as an earlier contract typed the uploader as a string.
 */
export function uploaderName(uploadedBy: unknown, people: Map<number, string>): string | null {
  const id = typeof uploadedBy === 'number' ? uploadedBy : typeof uploadedBy === 'string' && /^\d+$/.test(uploadedBy.trim()) ? Number(uploadedBy.trim()) : null;
  if (id != null && Number.isSafeInteger(id)) return people.get(id) ?? `User ${id}`;
  return typeof uploadedBy === 'string' && uploadedBy.trim() ? uploadedBy.trim() : null;
}

/**
 * The client SOP's own name for a stage where it differs from the name on screen (D49, C5): screens use the prototype's
 * short names, and the SOP's appear with the stage description. Keyed by step code, the same on every template.
 */
const SOP_STAGE_NAMES: Record<string, string> = {
  ORDER_DETAILS: 'Order Details & Onboarding',
  PRINTING_PRESS: 'Printing Press Operations',
};

export function sopStageName(spec: Pick<StepSpec, 'code' | 'title'>): string | null {
  const name = SOP_STAGE_NAMES[spec.code];
  return name && name !== spec.title ? name : null;
}
