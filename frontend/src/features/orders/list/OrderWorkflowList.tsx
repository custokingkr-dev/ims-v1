import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ChevronDown, Columns3, List, RefreshCw, Search, SlidersHorizontal } from 'lucide-react';
import { useAuth } from '../../../contexts/AuthContext';
import { Modal } from '../../../components/Modal';
import Paginator from '../../../components/Paginator';
import { Field, ModuleShell } from '../../../pages/workspace/ui';
import { CategoryIcon } from '../../catalog/categoryIcons';
import { orderAccessibleName, orderLabel } from '../shared/orderLabel';
import { PHASES, groupByPhase, phaseLabel } from '../shared/phases';
import { clockOffset, dueSummary } from '../shared/slaFormat';
import { workflowApi, workflowError } from '../workflow/api';
import { NARROW, useMediaQuery, useServerClock } from '../workflow/hooks';
import { initials } from '../workflow/model';
import type { OperatorChoice, OrderWorkflowCounts, OrderWorkflowListItem, ProductCategory, SlaFilter, WorkflowTemplate } from '../workflow/types';
import {
  BOARD_BATCH, PAGE_SIZE, activeFilterCount, clearedFilters, readFilters, toQuery, writeFilters, type ListFilters,
} from './filters';
import { itemChoices, splitItems, type ItemChoice } from './items';
import '../workflow/workflow.css';

const SLA_OPTIONS: Array<[SlaFilter | '', string]> = [
  ['', 'Any'], ['OVERDUE', 'Overdue'], ['DUE_TODAY', 'Due today'], ['DUE_SOON', 'Due soon (within 4 h)'], ['ON_TRACK', 'On track'], ['NO_TIMER', 'No timer'],
];
const SORTS: Array<[ListFilters['sort'], string]> = [['due', 'Due (overdue first)'], ['placed', 'Placed (newest)'], ['number', 'Order number']];
const COLUMN_LIMIT = 50;

interface Loaded { items: OrderWorkflowListItem[]; total: number; counts: OrderWorkflowCounts; now: string }

/** Cities by school id, where the page knows them (superadmin's school list); the list rows carry only the name. */
type Cities = ReadonlyMap<number, string>;

/** "Delhi Public School · Gurugram" (section 4.2), or the name alone when the city is not known. */
function schoolText(item: OrderWorkflowListItem, cities: Cities): string {
  const name = item.schoolName ?? `School ${item.schoolId}`;
  const city = cities.get(item.schoolId);
  return city ? `${name} · ${city}` : name;
}

/** The schools an operator works at: school-wide assignments and the schools of item scopes ("7:NOTEBOOKS"). */
function operatorSchoolIds(user: { operatorSchools?: number[]; operatorScopes?: string[] } | null | undefined): number[] {
  const ids = new Set<number>(user?.operatorSchools ?? []);
  for (const scope of user?.operatorScopes ?? []) {
    const id = Number(scope.split(':')[0]);
    if (Number.isInteger(id) && id > 0) ids.add(id);
  }
  return [...ids].sort((left, right) => left - right);
}

function ItemFilter({ choices, selected, onChange }: { choices: ItemChoice[]; selected: string[]; onChange: (items: string[]) => void }) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const { primary, more } = splitItems(choices);
  useEffect(() => {
    if (!open) return undefined;
    const close = (event: MouseEvent) => { if (!root.current?.contains(event.target as Node)) setOpen(false); };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);
  const toggle = (code: string) => onChange(selected.includes(code) ? selected.filter((item) => item !== code) : [...selected, code]);
  const moreSelected = more.filter((choice) => selected.includes(choice.code)).length;
  return (
    <fieldset className="ck-wfl-items">
      <legend>Item</legend>
      <div className="ck-wfl-item-row">
        <button type="button" className="ck-wfl-item" aria-pressed={selected.length === 0} onClick={() => onChange([])}>All</button>
        {primary.map((choice) => (
          <button key={choice.code} type="button" className="ck-wfl-item" aria-pressed={selected.includes(choice.code)} title={choice.label}
            aria-label={`${choice.label} (${choice.code})`} onClick={() => toggle(choice.code)}>{choice.code}</button>
        ))}
        {more.length > 0 && (
          <div className="ck-wfl-more" ref={root} onKeyDown={(event) => { if (event.key === 'Escape') setOpen(false); }}>
            <button type="button" className="ck-wfl-item" aria-expanded={open} aria-pressed={moreSelected > 0} onClick={() => setOpen((value) => !value)}>
              More{moreSelected ? ` (${moreSelected})` : ''} <ChevronDown size={12} aria-hidden="true" />
            </button>
            {open && (
              <div className="ck-wfl-more-list">
                {more.map((choice) => (
                  <label key={choice.code}><input type="checkbox" checked={selected.includes(choice.code)} onChange={() => toggle(choice.code)} /> {choice.code} · {choice.label}</label>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    </fieldset>
  );
}

interface FilterProps {
  filters: ListFilters;
  update: (patch: Partial<ListFilters>) => void;
  choices: ItemChoice[];
  templates: WorkflowTemplate[] | null;
  operators: OperatorChoice[] | null;
  schools: Array<{ id: number; name: string }>;
  superadmin: boolean;
}

/** Item, Stage, Assignee, School, SLA and Include completed: the toolbar on wide screens, a sheet below 768 px. */
function FilterControls({ filters, update, choices, templates, operators, schools, superadmin }: FilterProps) {
  const single = filters.items.length === 1 ? choices.find((choice) => choice.code === filters.items[0]) : undefined;
  const steps = single ? templates?.find((template) => template.definitionId === single.definitionId)?.steps ?? null : null;
  const stageValue = filters.step ? `step:${filters.step}` : filters.phase ? `phase:${filters.phase}` : '';
  const setStage = (value: string) => {
    const [kind, code] = value.split(':');
    update({ phase: kind === 'phase' ? code as ListFilters['phase'] : '', step: kind === 'step' ? code : '' });
  };
  return (
    <>
      <ItemFilter choices={choices} selected={filters.items} onChange={(items) => update({ items, step: items.length === 1 ? filters.step : '' })} />
      <Field label="Stage">
        <select value={stageValue} onChange={(e) => setStage(e.target.value)}>
          <option value="">Any stage</option>
          {steps
            ? steps.map((step) => <option key={step.code} value={`step:${step.code}`}>{step.order} · {step.title}</option>)
            : PHASES.map((phase) => <option key={phase} value={`phase:${phase}`}>{phaseLabel(phase)}</option>)}
        </select>
      </Field>
      <Field label="Assignee">
        <select value={filters.assignee} onChange={(e) => update({ assignee: e.target.value })}>
          <option value="">Anyone</option>
          {!superadmin && <option value="me">Me</option>}
          <option value="unassigned">Unassigned</option>
          {superadmin && (operators ?? []).map((operator) => <option key={operator.userId} value={String(operator.userId)}>{operator.fullName}</option>)}
        </select>
      </Field>
      {schools.length > 1 && (
        <Field label="School">
          <select value={filters.schoolId ?? ''} onChange={(e) => update({ schoolId: e.target.value ? Number(e.target.value) : null })}>
            <option value="">All schools</option>
            {schools.map((school) => <option key={school.id} value={school.id}>{school.name}</option>)}
          </select>
        </Field>
      )}
      <Field label="SLA">
        <select value={filters.sla} onChange={(e) => update({ sla: e.target.value as ListFilters['sla'] })}>
          {SLA_OPTIONS.map(([value, label]) => <option key={value || 'any'} value={value}>{label}</option>)}
        </select>
      </Field>
      <label className="ck-wfl-check"><input type="checkbox" checked={filters.includeFinished} onChange={(e) => update({ includeFinished: e.target.checked })} /> Include completed</label>
    </>
  );
}

function Due({ item, now }: { item: OrderWorkflowListItem; now: number }) {
  if (item.currentStep == null) return <span className="ck-wf-badge is-success">Completed</span>;
  const due = dueSummary(item.dueAt, now, item.timeZone);
  return <span className={`ck-wfl-due${due.tone === 'danger' || due.tone === 'warning' ? ` is-${due.tone}` : ''}`}>{due.text}</span>;
}

function StageCell({ item }: { item: OrderWorkflowListItem }) {
  const percent = item.total ? Math.round((item.completed / item.total) * 100) : 0;
  return (
    <div>
      <div>{item.currentStep != null ? `${item.currentStep} · ${item.currentTitle ?? item.status}` : 'Completed'}</div>
      <div className="ck-wfl-progress" role="img" aria-label={`${item.completed} of ${item.total} steps done`}><span style={{ width: `${percent}%` }} /></div>
      <div className="ck-wf-muted">{item.completed} of {item.total}</div>
    </div>
  );
}

function OrderTable({ items, now, cities, onOpen }: { items: OrderWorkflowListItem[]; now: number; cities: Cities; onOpen: (orderId: string) => void }) {
  return (
    <div className="ck-table-wrap">
      <table className="ck-table ck-wfl-table">
        <caption className="ck-sr-only">Orders in production</caption>
        <thead>
          <tr>
            <th scope="col">Order</th><th scope="col">Item</th><th scope="col">School</th><th scope="col" className="ck-num">Qty</th>
            <th scope="col">Stage</th><th scope="col">Due</th><th scope="col">Point of contact</th><th scope="col"><span className="ck-sr-only">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => {
            const label = orderLabel(item);
            return (
              <tr key={item.orderId} onClick={() => onOpen(item.orderId)}>
                <td data-label="Order"><span className="tb">{label.headline}</span>{label.reference && <span className="ck-wfl-ref">{label.reference}</span>}</td>
                <td data-label="Item"><span className="ck-wfl-itemcell"><CategoryIcon code={item.categoryCode} size={16} />{item.itemCode}</span></td>
                <td data-label="School">{schoolText(item, cities)}</td>
                <td data-label="Qty" className="ck-num">{item.orderedCount.toLocaleString('en-IN')}</td>
                <td data-label="Stage"><StageCell item={item} /></td>
                <td data-label="Due"><Due item={item} now={now} /></td>
                <td data-label="Point of contact">{item.assigneeName ?? (item.currentStep != null ? <span className="ck-wf-badge is-warning">Unassigned</span> : '—')}</td>
                <td>
                  <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" aria-label={`Open ${orderAccessibleName(item)}`}
                    onClick={(event) => { event.stopPropagation(); onOpen(item.orderId); }}>Open</button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/**
 * The board by phase. It holds the orders loaded so far (BOARD_BATCH at a time); while more match than are loaded
 * (`partial`), each column counts only its loaded orders and says so.
 */
function OrderBoard({ items, now, cities, partial, onOpen }: {
  items: OrderWorkflowListItem[]; now: number; cities: Cities; partial: boolean; onOpen: (orderId: string) => void;
}) {
  const columns = groupByPhase(items);
  const [shown, setShown] = useState<Record<string, number>>({});
  return (
    <div className="ck-pipeline ck-wfl-board">
      {PHASES.map((phase) => {
        const cards = columns[phase];
        const limit = shown[phase] ?? COLUMN_LIMIT;
        return (
          <section key={phase} className="ck-pipe-col" aria-label={`${phaseLabel(phase)}, ${cards.length} orders${partial ? ' loaded' : ''}`}>
            <header className="ck-pipe-head"><h3>{phaseLabel(phase)}</h3><span className="ck-wf-badge is-neutral">{partial ? `${cards.length} loaded` : cards.length}</span></header>
            <div className="ck-pipe-body">
              {cards.length === 0 && <p className="ck-wf-muted">No orders.</p>}
              {cards.slice(0, limit).map((item) => {
                const label = orderLabel(item);
                return (
                  <button key={item.orderId} type="button" className="ck-pipe-card" aria-label={`Open ${orderAccessibleName(item)}`} onClick={() => onOpen(item.orderId)}>
                    <span className="ck-wf-strong">{label.headline}</span>
                    <span className="ck-wf-muted">{schoolText(item, cities)}</span>
                    <span><span className="ck-wf-badge is-neutral">{item.itemCode}</span> {item.currentTitle ?? 'Completed'}</span>
                    <span className="ck-wfl-card-foot">
                      <Due item={item} now={now} />
                      <span className="ck-wfl-initials" title={item.assigneeName ?? 'Unassigned'}>
                        {item.assigneeName ? initials(item.assigneeName) : '—'}
                        <span className="ck-sr-only">{item.assigneeName ? `, point of contact ${item.assigneeName}` : ', unassigned'}</span>
                      </span>
                    </span>
                  </button>
                );
              })}
              {cards.length > limit && (
                <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" onClick={() => setShown((current) => ({ ...current, [phase]: limit + COLUMN_LIMIT }))}>
                  Show more ({cards.length - limit})
                </button>
              )}
            </div>
          </section>
        );
      })}
    </div>
  );
}

/**
 * Order workflows (section 4.2): every placed order moving through production that the user may see, as a list or a board
 * by phase, filtered and searched on the server. Filters live in the URL query.
 */
export function OrderWorkflowList({ onOpen }: { onOpen: (orderId: string) => void }) {
  const { user } = useAuth();
  const superadmin = user?.role === 'SUPERADMIN';
  const [params, setParams] = useSearchParams();
  const filters = useMemo(() => readFilters(params), [params]);
  const narrow = useMediaQuery(NARROW);
  const [search, setSearch] = useState(filters.q);
  const [data, setData] = useState<Loaded | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [categories, setCategories] = useState<ProductCategory[] | null>(null);
  const [templates, setTemplates] = useState<WorkflowTemplate[] | null>(null);
  const [operators, setOperators] = useState<OperatorChoice[] | null>(null);
  const [allSchools, setAllSchools] = useState<Array<{ id: number; name: string; city?: string | null }>>([]);
  const [sheet, setSheet] = useState(false);
  const request = useRef(0);

  const update = useCallback((patch: Partial<ListFilters>, keepPage = false) => {
    setParams((previous) => writeFilters(previous, { ...readFilters(previous), ...patch, ...(keepPage ? {} : { page: 0 }) }), { replace: true });
  }, [setParams]);

  // The search box follows the URL (Back, a pasted link) and writes to it a moment after typing stops.
  useEffect(() => { setSearch(filters.q); }, [filters.q]);
  useEffect(() => {
    if (search.trim() === filters.q.trim()) return undefined;
    const timer = window.setTimeout(() => update({ q: search }), 300);
    return () => window.clearTimeout(timer);
  }, [search, filters.q, update]);

  useEffect(() => {
    workflowApi.categories().then(setCategories, () => setCategories(null));
    workflowApi.templates().then(setTemplates, () => setTemplates(null));
    if (superadmin) {
      workflowApi.operators().then(setOperators, () => setOperators([]));
      workflowApi.schools().then(setAllSchools, () => setAllSchools([]));
    }
  }, [superadmin]);

  const queryKey = JSON.stringify(toQuery(filters, 0, 0));
  const board = filters.view === 'board';
  useEffect(() => {
    const id = ++request.current;
    setLoading(true); setError('');
    const limit = board ? BOARD_BATCH : PAGE_SIZE;
    const offset = board ? 0 : filters.page * PAGE_SIZE;
    workflowApi.list(toQuery(filters, limit, offset)).then((page) => {
      if (id !== request.current) return;
      setData({ items: page.items, total: page.total, counts: page.counts, now: page.now });
    }, (e: unknown) => {
      if (id !== request.current) return;
      setData(null); setError(workflowError(e, 'The orders could not be loaded.'));
    }).finally(() => { if (id === request.current) setLoading(false); });
    // queryKey stands for every filter that changes the server query.
  }, [queryKey, board, filters.page, attempt]); // eslint-disable-line react-hooks/exhaustive-deps

  const loadMore = async () => {
    if (!data) return;
    const id = ++request.current;
    setLoading(true);
    try {
      const page = await workflowApi.list(toQuery(filters, BOARD_BATCH, data.items.length));
      if (id === request.current) setData({ ...data, items: [...data.items, ...page.items], total: page.total, counts: page.counts, now: page.now });
    } catch (e) { if (id === request.current) setError(workflowError(e, 'More orders could not be loaded.')); } finally { if (id === request.current) setLoading(false); }
  };

  const offset = useMemo(() => (data ? clockOffset(data.now, Date.now()) : 0), [data]);
  const now = useServerClock(offset, 30_000);
  const choices = useMemo(() => itemChoices(categories), [categories]);
  const schools = useMemo(() => {
    if (superadmin) return allSchools;
    const names = new Map((data?.items ?? []).map((item) => [item.schoolId, item.schoolName ?? `School ${item.schoolId}`]));
    return operatorSchoolIds(user).map((id) => ({ id, name: names.get(id) ?? `School ${id}` }));
  }, [superadmin, allSchools, data, user]);
  const cities = useMemo<Cities>(() => new Map(allSchools.flatMap((school) => (school.city?.trim() ? [[school.id, school.city.trim()] as const] : []))), [allSchools]);
  const filterCount = activeFilterCount({ ...filters, q: '' });
  const counts = data?.counts;
  // Each chip applies the filter whose orders it counts.
  const chip = (key: string, label: string, value: number | undefined, sub: string, patch: Partial<ListFilters>, pressed: boolean) => (
    <button key={key} type="button" className="ck-stat" aria-pressed={pressed} onClick={() => update(patch)}>
      <div className="ck-stat-l">{label}</div><div className="ck-stat-v">{value ?? '—'}</div><div className="ck-stat-s">{sub}</div>
    </button>
  );
  const controls = <FilterControls filters={filters} update={update} choices={choices} templates={templates} operators={operators} schools={schools} superadmin={superadmin} />;
  const anyFilter = activeFilterCount(filters) > 0;

  return (
    <ModuleShell title="Order workflows" subtitle="Placed orders moving through production"
      actions={<button type="button" className="ck-btn ck-btn-ghost" disabled={loading} onClick={() => setAttempt((value) => value + 1)}><RefreshCw size={14} aria-hidden="true" /> Refresh</button>}>
      <div className="ck-wf ck-wfl">
        <div className="ck-wfl-chips" role="group" aria-label="Summary">
          {chip('open', 'Open', counts?.open, 'Orders in production', { sla: '', assignee: '', includeFinished: false }, !filters.sla && !filters.assignee && !filters.includeFinished)}
          {chip('overdue', 'Overdue', counts?.overdue, 'Current stage past due', { sla: 'OVERDUE' }, filters.sla === 'OVERDUE')}
          {chip('today', 'Due today', counts?.dueToday, 'Current stage due today', { sla: 'DUE_TODAY' }, filters.sla === 'DUE_TODAY')}
          {chip('unassigned', 'Unassigned', counts?.unassigned, 'No point of contact', { assignee: 'unassigned' }, filters.assignee === 'unassigned')}
          {!superadmin && chip('mine', 'Mine', counts?.mine, 'Assigned to you', { assignee: 'me' }, filters.assignee === 'me')}
        </div>

        <div className="ck-wfl-toolbar">
          <div className="ck-wfl-search">
            <Field label="Search">
              <input type="search" value={search} maxLength={100} placeholder="Search order number, school or old order ID" onChange={(e) => setSearch(e.target.value)} />
            </Field>
          </div>
          {narrow
            ? <button type="button" className="ck-btn ck-btn-ghost" onClick={() => setSheet(true)}><SlidersHorizontal size={14} aria-hidden="true" /> Filters ({filterCount})</button>
            : controls}
          <div className="ck-wfl-view" role="group" aria-label="View">
            <button type="button" aria-pressed={!board} onClick={() => update({ view: 'list' }, true)}><List size={14} aria-hidden="true" /> List</button>
            <button type="button" aria-pressed={board} onClick={() => update({ view: 'board' }, true)}><Columns3 size={14} aria-hidden="true" /> Board</button>
          </div>
        </div>

        {sheet && (
          <Modal title="Filters" onClose={() => setSheet(false)}
            footer={<>
              <button type="button" className="ck-btn ck-btn-ghost" onClick={() => update(clearedFilters(filters))}>Clear filters</button>
              <button type="button" className="ck-btn ck-btn-g" onClick={() => setSheet(false)}>Show orders</button>
            </>}>
            <div className="ck-wf-fields">{controls}</div>
          </Modal>
        )}

        {!board && data && data.items.length > 0 && (
          <div className="ck-wfl-bar">
            <span className="ck-wf-muted" role="status">{data.total.toLocaleString('en-IN')} order{data.total === 1 ? '' : 's'}</span>
            <label className="ck-wfl-check">Sort by
              <select value={filters.sort} onChange={(e) => update({ sort: e.target.value as ListFilters['sort'] })}>
                {SORTS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
              </select>
            </label>
          </div>
        )}

        {error && (
          <div className="ck-alert ck-alert-re" role="alert">
            <div>{error} <button type="button" className="ck-btn ck-btn-ghost ck-btn-sm" onClick={() => setAttempt((value) => value + 1)}><RefreshCw size={13} aria-hidden="true" /> Retry</button></div>
          </div>
        )}
        {!error && !data && <p className="ck-wf-muted" role="status"><Search size={14} aria-hidden="true" /> Loading orders…</p>}
        {data && data.items.length === 0 && (
          <div className="ck-wfl-empty">
            {anyFilter
              ? <><p>No orders match these filters.</p><button type="button" className="ck-btn ck-btn-ghost" onClick={() => update(clearedFilters(filters))}>Clear filters</button></>
              : <p>No orders are in production.</p>}
          </div>
        )}
        {data && data.items.length > 0 && (board
          ? (
            <>
              <OrderBoard items={data.items} now={now} cities={cities} partial={data.items.length < data.total} onOpen={onOpen} />
              {data.items.length < data.total && (
                <div className="ck-wf-block-actions"><button type="button" className="ck-btn ck-btn-ghost" disabled={loading} onClick={() => void loadMore()}>
                  Load more orders ({data.total - data.items.length} not shown)
                </button></div>
              )}
            </>
          )
          : (
            <>
              <OrderTable items={data.items} now={now} cities={cities} onOpen={onOpen} />
              <Paginator page={filters.page} totalPages={Math.max(1, Math.ceil(data.total / PAGE_SIZE))} onPageChange={(page) => update({ page }, true)} />
            </>
          ))}
      </div>
    </ModuleShell>
  );
}
