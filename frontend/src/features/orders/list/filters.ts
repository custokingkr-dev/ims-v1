import { isPhase } from '../shared/phases';
import type { ListQuery, Phase, SlaFilter } from '../workflow/types';

/**
 * The order list's filters (section 4.2). They live in the URL query, so a filtered list can be bookmarked, shared and
 * restored by the browser's Back button. Only these keys belong to the list; the workspace drops them when it leaves the
 * order workflows panel.
 */
export interface ListFilters {
  q: string;
  /** Item codes, e.g. NB and FE; empty means every item. */
  items: string[];
  phase: Phase | '';
  /** A step code; only offered when exactly one item is chosen. */
  step: string;
  /** '' (anyone), 'me', 'unassigned' or a user id. */
  assignee: string;
  schoolId: number | null;
  /** A row SLA state, or DUE_TODAY (the "Due today" chip). */
  sla: SlaFilter | '';
  includeFinished: boolean;
  view: 'list' | 'board';
  sort: 'due' | 'placed' | 'number';
  /** 0-based page of the list view. */
  page: number;
}

export const PAGE_SIZE = 50;
/** The board loads this many orders per request (the endpoint's maximum). */
export const BOARD_BATCH = 100;

/** Every URL key the order workflow panel owns, the open order included. */
export const ORDER_WORKFLOW_URL_KEYS = ['order', 'q', 'item', 'phase', 'step', 'assignee', 'school', 'sla', 'done', 'view', 'sort', 'page'] as const;

export const DEFAULT_FILTERS: ListFilters = {
  q: '', items: [], phase: '', step: '', assignee: '', schoolId: null, sla: '', includeFinished: false, view: 'list', sort: 'due', page: 0,
};

const ITEM_CODE = /^[A-Z]{2,4}$/;
const STEP_CODE = /^[A-Z][A-Z0-9_]{1,63}$/;
const ASSIGNEE = /^(me|unassigned|[1-9][0-9]{0,18})$/;
const SLA_FILTERS: SlaFilter[] = ['OVERDUE', 'DUE_SOON', 'ON_TRACK', 'NO_TIMER', 'DUE_TODAY'];

/** The filters a URL asks for; anything malformed is ignored rather than sent to the server. */
export function readFilters(params: URLSearchParams): ListFilters {
  const items = [...new Set((params.get('item') ?? '').split(',').map((code) => code.trim().toUpperCase()).filter((code) => ITEM_CODE.test(code)))];
  const phase = params.get('phase') ?? '';
  const step = (params.get('step') ?? '').toUpperCase();
  const assignee = params.get('assignee') ?? '';
  const school = Number(params.get('school'));
  const sla = (params.get('sla') ?? '') as SlaFilter;
  const sort = params.get('sort');
  const page = Number(params.get('page'));
  return {
    q: (params.get('q') ?? '').slice(0, 100),
    items,
    phase: isPhase(phase) ? phase : '',
    step: items.length === 1 && STEP_CODE.test(step) ? step : '',
    assignee: ASSIGNEE.test(assignee) ? assignee : '',
    schoolId: Number.isInteger(school) && school > 0 ? school : null,
    sla: SLA_FILTERS.includes(sla) ? sla : '',
    includeFinished: params.get('done') === '1',
    view: params.get('view') === 'board' ? 'board' : 'list',
    sort: sort === 'placed' || sort === 'number' ? sort : 'due',
    page: Number.isInteger(page) && page > 0 ? page : 0,
  };
}

/** The URL query for these filters, keeping every key that is not the list's (the workspace's panel, for one). */
export function writeFilters(previous: URLSearchParams, filters: ListFilters): URLSearchParams {
  const next = new URLSearchParams(previous);
  const set = (key: string, value: string | null) => { if (value) next.set(key, value); else next.delete(key); };
  set('q', filters.q.trim() || null);
  set('item', filters.items.length ? filters.items.join(',') : null);
  set('phase', filters.phase || null);
  set('step', filters.items.length === 1 ? filters.step || null : null);
  set('assignee', filters.assignee || null);
  set('school', filters.schoolId ? String(filters.schoolId) : null);
  set('sla', filters.sla || null);
  set('done', filters.includeFinished ? '1' : null);
  set('view', filters.view === 'board' ? 'board' : null);
  set('sort', filters.sort === 'due' ? null : filters.sort);
  set('page', filters.page > 0 ? String(filters.page) : null);
  return next;
}

/** The server query for these filters and a slice of the results. */
export function toQuery(filters: ListFilters, limit: number, offset: number): ListQuery {
  const query: ListQuery = { sort: filters.sort, limit, offset };
  if (filters.q.trim()) query.q = filters.q.trim();
  if (filters.items.length) query.item = filters.items.join(',');
  if (filters.phase) query.phase = filters.phase;
  if (filters.step && filters.items.length === 1) query.step = filters.step;
  if (filters.assignee) query.assignee = filters.assignee;
  if (filters.schoolId) query.schoolId = filters.schoolId;
  if (filters.sla) query.sla = filters.sla;
  if (filters.includeFinished) query.includeFinished = true;
  return query;
}

/** How many filters narrow the list (the search included), for "Filters (n)" and the empty state. */
export function activeFilterCount(filters: ListFilters): number {
  return [filters.q.trim(), filters.items.length, filters.phase || filters.step, filters.assignee, filters.schoolId, filters.sla, filters.includeFinished]
    .filter(Boolean).length;
}

/** Filters reset, keeping how the list is shown. */
export function clearedFilters(filters: ListFilters): ListFilters {
  return { ...DEFAULT_FILTERS, view: filters.view, sort: filters.sort };
}
