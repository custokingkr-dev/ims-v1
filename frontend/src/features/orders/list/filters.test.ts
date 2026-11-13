import { describe, expect, it } from 'vitest';
import { DEFAULT_FILTERS, activeFilterCount, clearedFilters, readFilters, toQuery, writeFilters } from './filters';
import { FALLBACK_ITEMS, itemChoices, splitItems } from './items';

describe('list filters in the URL', () => {
  it('round-trips every filter through the query, keeping keys that are not the list\'s', () => {
    const filters = {
      ...DEFAULT_FILTERS, q: 'dps', items: ['NB'], step: 'PRINTING_PRESS', assignee: 'unassigned', schoolId: 7, sla: 'OVERDUE' as const,
      includeFinished: true, view: 'board' as const, sort: 'placed' as const, page: 2,
    };
    const params = writeFilters(new URLSearchParams('panel=nb-work'), filters);
    expect(params.toString()).toBe('panel=nb-work&q=dps&item=NB&step=PRINTING_PRESS&assignee=unassigned&school=7&sla=OVERDUE&done=1&view=board&sort=placed&page=2');
    expect(readFilters(params)).toEqual(filters);
  });

  it('writes nothing for the defaults and drops cleared filters', () => {
    expect(writeFilters(new URLSearchParams('panel=nb-work&q=old&item=FE&page=3'), DEFAULT_FILTERS).toString()).toBe('panel=nb-work');
  });

  it('reads the Due today filter, which is not a row SLA state but a filter of its own', () => {
    expect(readFilters(new URLSearchParams('sla=DUE_TODAY')).sla).toBe('DUE_TODAY');
    expect(toQuery({ ...DEFAULT_FILTERS, sla: 'DUE_TODAY' }, 50, 0)).toEqual({ sla: 'DUE_TODAY', sort: 'due', limit: 50, offset: 0 });
  });

  it('ignores malformed values instead of sending them to the server', () => {
    const filters = readFilters(new URLSearchParams('item=nb,fe,TOOLONG1,x&phase=SOMEWHERE&step=bad code&assignee=bob&school=-4&sla=LATE&sort=random&page=-1&view=grid'));
    expect(filters).toEqual({ ...DEFAULT_FILTERS, items: ['NB', 'FE'] });
  });

  it('keeps a step only while exactly one item is chosen', () => {
    expect(readFilters(new URLSearchParams('item=NB,FE&step=PRINTING_PRESS')).step).toBe('');
    expect(writeFilters(new URLSearchParams(), { ...DEFAULT_FILTERS, items: ['NB', 'FE'], step: 'PRINTING_PRESS' }).has('step')).toBe(false);
  });
});

describe('the server query', () => {
  it('sends only the filters that narrow the list', () => {
    expect(toQuery(DEFAULT_FILTERS, 50, 0)).toEqual({ sort: 'due', limit: 50, offset: 0 });
    expect(toQuery({ ...DEFAULT_FILTERS, q: '  dps ', items: ['NB', 'FE'], phase: 'PRODUCTION', assignee: 'me', schoolId: 7, sla: 'DUE_SOON', includeFinished: true, sort: 'number' }, 100, 200))
      .toEqual({ q: 'dps', item: 'NB,FE', phase: 'PRODUCTION', assignee: 'me', schoolId: 7, sla: 'DUE_SOON', includeFinished: true, sort: 'number', limit: 100, offset: 200 });
  });

  it('counts active filters and clears them without changing the view', () => {
    const busy = { ...DEFAULT_FILTERS, q: 'x', items: ['NB'], sla: 'OVERDUE' as const, view: 'board' as const, sort: 'placed' as const, page: 3 };
    expect(activeFilterCount(busy)).toBe(3);
    expect(activeFilterCount(DEFAULT_FILTERS)).toBe(0);
    expect(clearedFilters(busy)).toEqual({ ...DEFAULT_FILTERS, view: 'board', sort: 'placed' });
  });
});

describe('item choices', () => {
  const category = (code: string, label: string, orderCode: string | null, workflowDefinition: string | null, sortOrder: number) => ({
    code, label, emoji: '', description: '', orderType: 'One-time', formEnabled: true, sortOrder, active: true, orderCode,
    workflowDefinition: workflowDefinition as never,
  });

  it('lists the catalogue\'s workflow items, SOP codes first, and puts the rest under More', () => {
    const choices = itemChoices([
      category('BELTS', 'Belts', 'BLT', 'GENERAL_ITEM_ORDER_WORKFLOW', 1),
      category('FLIERS', 'Fliers', 'FE', 'FLIER_ORDER_WORKFLOW', 5),
      category('NOTEBOOKS', 'Notebooks', 'NB', 'NOTEBOOK_ORDER_TRACKER', 9),
      category('TIES', 'Ties', 'TIE', null, 2),
      category('UNIFORMS', 'Uniforms', null, null, 3),
    ]);
    expect(choices.map((choice) => choice.code)).toEqual(['NB', 'FE', 'BLT']);
    expect(splitItems(choices)).toEqual({ primary: [choices[0], choices[1]], more: [choices[2]] });
  });

  it('falls back to the SOP items when the catalogue cannot be read', () => {
    expect(itemChoices(null)).toBe(FALLBACK_ITEMS);
    expect(itemChoices([])).toBe(FALLBACK_ITEMS);
  });
});
