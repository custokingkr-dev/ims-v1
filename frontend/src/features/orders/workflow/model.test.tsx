import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import api from '../../../services/api';
import { clearWorkflowCaches, gateFailures, isConflict, parseView, workflowApi, workflowError, WORKFLOW_UNAVAILABLE } from './api';
import { fieldError } from './blocks/FieldsBlock';
import { designFileName, designUsage, isCdr, uploadProblems } from './designFiles';
import {
  checklistProgress, estimatedCompletion, fieldValueForSave, formatBytes, formatDay, initials, legacyEntries, pluralise, stagesOf,
} from './model';
import { StageList, Stepper } from './Stepper';
import { makeSummary, makeView, normaliseTemplate } from './testFixtures';
import type { FieldSpec, WorkflowTemplate } from './types';

vi.mock('../../../services/api', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } }));
afterEach(cleanup);

const MIB = 1024 * 1024;
const spec = (kind: FieldSpec['kind'], over: Partial<FieldSpec> = {}): FieldSpec => ({ kind, label: 'Field', placeholder: null, min: null, max: null, choices: null, legacy: false, ...over });
const TEMPLATE: WorkflowTemplate = normaliseTemplate({
  definitionId: 'FLIER_ORDER_WORKFLOW', title: 'Flier', introMessage: 'Hi {Name}',
  steps: [
    { order: 1, code: 'ORDER_DETAILS', title: 'Order Details', phase: 'INTAKE', sla: { value: 2, unit: 'HOURS' }, tabs: ['WORK'], blocks: [],
      fields: { oldRef: { kind: 'TEXT', label: 'Old reference', legacy: true }, orderPdfLink: { kind: 'URL', label: 'PDF' } } },
    { order: 2, code: 'DESIGNING_ACTIVITY', title: 'Designing Activity', phase: 'INTAKE', customisedOnly: true, blocks: [] },
    { order: 5, code: 'PRODUCTION_PLANNING', title: 'Production Planning', phase: 'PLANNING', blocks: [], fields: { targetCompletion: { kind: 'DATE', label: 'Target' } } },
  ],
});

describe('screen rules', () => {
  it('pairs each step of the order\'s path with its template spec, and keeps a step the template lost', () => {
    const view = makeView(TEMPLATE, { customised: false, active: 5 });
    expect(view.steps.map((step) => step.step)).toEqual([1, 5]);
    const stages = stagesOf({ ...view, steps: [...view.steps, { ...view.steps[1], step: 9, code: 'GONE', title: 'Gone' }] }, TEMPLATE);
    expect(stages.map((stage) => stage.spec.title)).toEqual(['Order Details', 'Production Planning', 'Gone']);
    expect(stages[2].spec.blocks.map((block) => block.type)).toEqual(['LEGACY_DETAILS']);
  });

  it('shows saved legacy values read-only and nothing for empty ones', () => {
    const view = makeView(TEMPLATE, { active: 1, steps: { 1: { data: { oldRef: 'JOB-7', orderPdfLink: 'https://x.test' } } } });
    expect(legacyEntries(stagesOf(view, TEMPLATE)[0])).toEqual([{ key: 'oldRef', label: 'Old reference', value: 'JOB-7' }]);
  });

  it('turns typed text into what the server expects', () => {
    expect(fieldValueForSave(spec('WHOLE'), ' 42 ')).toBe(42);
    expect(fieldValueForSave(spec('NUMBER'), '2.5')).toBe(2.5);
    expect(fieldValueForSave(spec('URL'), ' https://x.test ')).toBe('https://x.test');
    expect(fieldValueForSave(spec('TEXT'), '   ')).toBeNull();
    expect(fieldValueForSave(spec('WORDS'), 'two words ')).toBe('two words ');
  });

  it('says what the server would refuse before it is sent', () => {
    expect(fieldError(spec('URL'), 'ftp://x')).toBe('Paste a full https:// link.');
    expect(fieldError(spec('URL'), `https://x.test/${'a'.repeat(1000)}`)).toBe('Use a link of at most 1,000 characters.');
    expect(fieldError(spec('WHOLE', { min: 0 }), '-1')).toBe('Enter 0 or more.');
    expect(fieldError(spec('WHOLE'), '1.5')).toBe('Enter a whole number.');
    expect(fieldError(spec('NUMBER', { max: 100 }), '101')).toBe('Enter 100 or less.');
    expect(fieldError(spec('TEXT', { max: 5 }), 'too long')).toBe('Use at most 5 characters.');
    expect(fieldError(spec('WORDS', { max: 2 }), 'one two three')).toBe('Use at most 2 words.');
    expect(fieldError(spec('CHOICE', { choices: [['A', 'a']] }), 'B')).toBe('Choose one of the options.');
    expect(fieldError(spec('DATE'), '13/11/2026')).toBe('Enter a date.');
    expect(fieldError(spec('URL'), '')).toBe('');
  });

  it('counts a checklist with its sign-off item ticked only by a sign-off', () => {
    const qc = { items: ['a', 'b', 'c', 'release'], signOffItem: 3, source: 'prototype' as const };
    expect(checklistProgress(qc, [0, 1, 2, 3], false)).toEqual({ done: 3, total: 4, percent: 75, complete: false });
    expect(checklistProgress(qc, [0, 1, 2], true)).toEqual({ done: 4, total: 4, percent: 100, complete: true });
  });

  it('estimates completion from a revised date, then the target, then the order', () => {
    const base = makeView(TEMPLATE, { active: 5 });
    expect(estimatedCompletion(base, makeSummary({ requiredByDate: '2026-12-15' }))).toEqual({ value: '15 Dec 2026', caption: 'Required by (from order)' });
    const planned = makeView(TEMPLATE, { active: 5, steps: { 5: { data: { targetCompletion: '2026-11-18' } } } });
    expect(estimatedCompletion(planned, null)).toEqual({ value: '18 Nov 2026', caption: 'Target completion date' });
    const revised = { ...planned, steps: planned.steps.map((step) => ({ ...step, data: { ...step.data, revisedCompletion: '2026-11-25' } })) };
    expect(estimatedCompletion(revised, null).caption).toBe('Revised completion date');
    expect(estimatedCompletion(base, makeSummary({ requiredByDate: null }))).toEqual({ value: '—', caption: 'No target date yet' });
    expect(formatDay('2026-13-01')).toBeNull();
  });

  it('names items, people and sizes', () => {
    expect(['notebook', 'flier', 'flex', 'bill book', 'diary', ''].map(pluralise)).toEqual(['notebooks', 'fliers', 'flexes', 'bill books', 'diaries', 'items']);
    expect([initials('Rajesh Kumar'), initials('Asha'), initials(null)]).toEqual(['RK', 'AS', '?']);
    expect([formatBytes(18.4 * MIB), formatBytes(640 * 1024), formatBytes(12)]).toEqual(['18.4 MB', '640 KB', '12 B']);
  });
});

describe('design file checks', () => {
  it('measures usage and finds every problem with the chosen files at once', () => {
    expect(designUsage([{ sizeBytes: 10 * MIB }, { sizeBytes: 8.4 * MIB }]).label).toBe('2 of 4 files · 18.4 of 25 MB');
    expect(designUsage([{ sizeBytes: 100 }]).label).toBe('1 of 4 files · <0.1 of 25 MB');
    expect(uploadProblems([{ name: 'a.png', size: MIB }], [])).toEqual([]);
    expect(uploadProblems([{ name: 'a.webp', size: MIB }, { name: 'b.png', size: 30 * MIB }], [{ sizeBytes: 1 }, { sizeBytes: 1 }, { sizeBytes: 1 }])).toEqual([
      'a.webp: only PDF, CDR, PNG and JPEG files can be uploaded.',
      'An order can hold 4 design files; there is room for 1 more.',
      'Design files can total 25 MB per order; these would make 31 MB.',
    ]);
    expect(isCdr({ contentType: 'application/vnd.corel-draw', originalFilename: 'x' })).toBe(true);
    expect(isCdr({ contentType: 'application/octet-stream', originalFilename: 'cover.CDR' })).toBe(true);
  });

  it('names a CorelDRAW file with its extension, as school-core now stores it, when an older file lacks one', () => {
    const CDR = 'application/vnd.corel-draw';
    expect(designFileName({ id: 5, contentType: CDR, originalFilename: 'Final_Artwork' })).toBe('Final_Artwork.cdr');
    expect(designFileName({ id: 5, contentType: CDR, originalFilename: 'Final_Artwork.html' })).toBe('Final_Artwork.html.cdr');
    expect(designFileName({ id: 5, contentType: CDR, originalFilename: ' Final_Artwork. ' })).toBe('Final_Artwork.cdr');
    expect(designFileName({ id: 5, contentType: CDR, originalFilename: 'cover.CDR' })).toBe('cover.CDR');
    expect(designFileName({ id: 5, contentType: CDR, originalFilename: null })).toBe('Design file 5.cdr');
    expect(designFileName({ id: 6, contentType: 'application/pdf', originalFilename: 'proof' })).toBe('proof');
    expect(designFileName({ id: 7, contentType: 'image/png', originalFilename: '  ' })).toBe('Design file 7');
  });
});

describe('the workflow client', () => {
  it('says a failing service plainly, and keeps a refusal\'s own words', () => {
    expect(workflowError({ response: { status: 502, data: { message: 'Gateway upstream error' } } })).toBe(WORKFLOW_UNAVAILABLE);
    expect(workflowError({ isAxiosError: true, request: {} })).toBe(WORKFLOW_UNAVAILABLE);
    expect(workflowError({ response: { status: 400, data: { fieldErrors: { a: 'Too long.' } } } })).toBe('Too long.');
    expect(workflowError(new Error('Odd'), 'fallback')).toBe('Odd');
    expect(workflowError({}, 'Fallback')).toBe('Fallback');
  });

  it('reads every unmet gate from a refused Complete, and tells conflicts apart', () => {
    const refused = { response: { status: 409, data: { code: 'GATE_UNMET', message: 'Not ready', unmet: [{ code: 'A', message: 'Do A' }, 'junk'] } } };
    expect(gateFailures(refused)).toEqual([{ code: 'A', message: 'Do A' }]);
    expect(isConflict(refused)).toBe(false);
    expect(isConflict({ response: { status: 409, data: { message: 'Changed' } } })).toBe(true);
    expect(gateFailures({ response: { status: 400 } })).toEqual([]);
  });

  it('refuses an order view it cannot read, and fills the optional lists it can', () => {
    expect(() => parseView({ orderId: 'CK-1' })).toThrow('The response was not understood.');
    const view = makeView(TEMPLATE, { active: 1 });
    const parsed = parseView({ ...view, internalNotes: undefined, steps: [{ ...view.steps[0], data: null, notes: undefined, unmet: undefined }] });
    expect(parsed.internalNotes).toEqual([]);
    expect(parsed.steps[0]).toMatchObject({ data: {}, notes: [], unmet: [] });
  });

  it('fetches each template once per page load, and asks again after a failure', async () => {
    clearWorkflowCaches();
    vi.mocked(api.get).mockRejectedValueOnce(new Error('down')).mockResolvedValue({ data: TEMPLATE });
    await expect(workflowApi.template('FLIER_ORDER_WORKFLOW')).rejects.toThrow('down');
    await expect(workflowApi.template('FLIER_ORDER_WORKFLOW')).resolves.toMatchObject({ definitionId: 'FLIER_ORDER_WORKFLOW' });
    await workflowApi.template('FLIER_ORDER_WORKFLOW');
    expect(api.get).toHaveBeenCalledTimes(2);
    expect(api.get).toHaveBeenLastCalledWith('/workflows/item-orders/templates/FLIER_ORDER_WORKFLOW');
  });

  it('lists only design files, oldest first, and only operators eligible for the order', async () => {
    vi.mocked(api.get).mockImplementation(async (url: string) => ({
      data: url.endsWith('/assets')
        ? [{ id: 2, assetKind: 'DESIGN_DRAFT', uploadedAt: '2026-11-13T02:00:00Z' }, { id: 1, assetKind: 'DESIGN', uploadedAt: '2026-11-13T00:00:00Z' }, { id: 3, assetKind: 'DESIGN_DRAFT', uploadedAt: '2026-11-13T01:00:00Z' }]
        : [
          { assignmentId: 1, userId: 5, fullName: 'Zed', email: 'z@x', schoolId: 7, catalogCategory: null },
          { assignmentId: 2, userId: 6, fullName: 'Amy', email: 'a@x', schoolId: 7, catalogCategory: 'FLIERS' },
          { assignmentId: 3, userId: 8, fullName: 'Bob', email: 'b@x', schoolId: 7, catalogCategory: 'NOTEBOOKS' },
          { assignmentId: 4, userId: 5, fullName: 'Zed', email: 'z@x', schoolId: 7, catalogCategory: 'FLIERS' },
        ],
    }) as never);
    expect((await workflowApi.designFiles('CK-1')).map((file) => file.id)).toEqual([3, 2]);
    expect(await workflowApi.operators(7, 'FLIERS')).toEqual([{ userId: 6, fullName: 'Amy' }, { userId: 5, fullName: 'Zed' }]);
  });
});

describe('stage navigation with little room', () => {
  const stages = stagesOf(makeView(TEMPLATE, { active: 2 }), TEMPLATE);

  it('collapses the stepper to one line with previous and next', () => {
    const onView = vi.fn();
    render(<Stepper stages={stages} viewing={2} onView={onView} narrow />);
    expect(screen.getByText(/Step 2 · Designing Activity/)).toHaveTextContent('(2 of 3)');
    fireEvent.click(screen.getByRole('button', { name: 'Next stage' }));
    expect(onView).toHaveBeenCalledWith(5);
    fireEvent.click(screen.getByRole('button', { name: 'Previous stage' }));
    expect(onView).toHaveBeenLastCalledWith(1);
  });

  it('turns the stage list into a select', () => {
    const onView = vi.fn();
    render(<StageList stages={stages} viewing={2} onView={onView} timeZone="Asia/Kolkata" now={Date.parse('2026-11-13T06:30:00Z')} compact />);
    const select = screen.getByLabelText('Stage');
    expect(select).toHaveValue('2');
    fireEvent.change(select, { target: { value: '5' } });
    expect(onView).toHaveBeenCalledWith(5);
  });
});
