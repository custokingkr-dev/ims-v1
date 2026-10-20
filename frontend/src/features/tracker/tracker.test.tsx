import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NotebookTracker } from './NotebookTracker';
import { OrderDetailEntry } from './OrderDetailEntry';
import { DraftImages } from './DraftImages';
import { parseTracker, trackerApi } from './api';
import { countsMismatch, slaLabel, wordCount } from './steps';
import type { OrderSummary, TrackerStep, TrackerView } from './types';

const auth = vi.hoisted(() => ({ user: { role: 'OPERATIONS', userId: 42 } as { role: string; userId: number; fullName?: string } | null }));
vi.mock('../../contexts/AuthContext', () => ({ useAuth: () => ({ user: auth.user }) }));
vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api')>();
  return { ...actual, trackerApi: {
    get: vi.fn(), saveDraft: vi.fn(), saveStageNote: vi.fn(), complete: vi.fn(), reopen: vi.fn(), signOff: vi.fn(), assignStep: vi.fn(),
    assignOrder: vi.fn(), designers: vi.fn(), operators: vi.fn(), addInternalNote: vi.fn(), draftImages: vi.fn(), uploadDraft: vi.fn(),
    deleteDraft: vi.fn(), draftBlob: vi.fn(), sendDesign: vi.fn(), approveDesign: vi.fn(), requestChanges: vi.fn(), setVendor: vi.fn(),
    setDesigner: vi.fn(), addFollowUp: vi.fn(), summary: vi.fn(), startTracker: vi.fn(),
  } };
});
vi.mock('../catalog/ProductOrderDetail', () => ({ ProductOrderDetail: () => <div>ordinary order detail</div> }));

const can = (over: Partial<TrackerStep['can']> = {}): TrackerStep['can'] => ({ canEditDetails: false, canEditStageNote: false, canComplete: false, canReopen: false, ...over });
const step = (n: number, title: string, over: Partial<TrackerStep> = {}): TrackerStep => ({
  step: n, title, customisedOnly: false, status: 'LOCKED', assigneeUserId: 42, startedAt: null, dueAt: null, overdue: false,
  sla: { value: 4, unit: 'HOURS', source: 'DEFAULT' }, completedAt: null, completedBy: null, data: {}, stageNote: null, version: 3, can: can(), ...over,
});
function tracker(over: Partial<TrackerView> = {}): TrackerView {
  return {
    orderId: 'CK-1', schoolId: 7, customised: false, orderedCount: 1000, placedAt: '2026-10-19T04:00:00Z', timeZone: 'Asia/Kolkata', schoolName: 'School Seven',
    instanceStatus: 'IN_PROGRESS', progress: { completed: 1, total: 3, currentStep: 5, currentTitle: 'Production Planning', finished: false },
    vendor: null, designer: null, design: { state: null, comment: null, at: null, by: null, approvedDraftRef: null, canManageImages: false },
    steps: [
      step(1, 'Order Details', { status: 'COMPLETED', completedAt: '2026-10-19T05:00:00Z', can: can({ canReopen: true }) }),
      step(5, 'Production Planning', { status: 'ACTIVE', dueAt: '2026-10-19T09:00:00Z', can: can({ canEditDetails: true, canEditStageNote: true, canComplete: true }) }),
      step(6, 'Board Procurement'),
    ],
    internalNotes: [], followUps: [], version: 1, ...over,
  };
}

const summary = (over: Partial<OrderSummary> = {}): OrderSummary => ({
  orderId: 'CK-1', schoolId: 7, schoolName: 'School Seven', status: 'ORDER_DETAILS', customised: false, requiredByDate: '2026-12-15', placedAt: '2026-10-19T04:00:00Z',
  schoolNotes: 'Please use green', orderedCount: 1000, trackerPending: false, schoolContactEmail: 'office@seven.test', schoolContactPhone: '+91 98765 43210',
  lines: [{ lineNo: 1, selections: { SIZE: { code: 'LONG', label: 'Long' }, RULING: { code: 'SINGLE_RULE', label: 'Single rule' } }, bookCount: 400, pageCount: 198 }], ...over,
});

const TITLES: Record<number, string> = {
  1: 'Order Details', 2: 'Designing Activity', 3: 'Head Approval', 4: 'CDR Conversion', 5: 'Production Planning', 6: 'Board Procurement',
  7: 'Printing Press', 8: 'Pre-Print Quality Gate', 9: 'Factory Handover', 10: 'Production Follow-up', 11: 'Validation Count', 12: 'Delivery & Invoicing',
};
/** A tracker whose only open step is the one given, with that step's work area in front. */
const onStep = (n: number, over: Partial<TrackerStep> = {}, rest: Partial<TrackerView> = {}) => tracker({
  progress: { completed: 0, total: 1, currentStep: n, currentTitle: TITLES[n], finished: false },
  steps: [step(n, TITLES[n], { status: 'ACTIVE', can: can({ canEditDetails: true, canEditStageNote: true, canComplete: true }), ...over })], ...rest,
});
const draft = (id: number, name: string, contentType = 'image/png') => ({ id, assetKind: 'DESIGN_DRAFT', contentType, originalFilename: name, sizeBytes: 10, uploadedAt: `2026-10-19T0${id}:00:00Z` });
const operator = (userId: number, fullName: string, catalogCategory: string | null = 'NOTEBOOKS') => ({ assignmentId: userId, userId, fullName, email: `op${userId}@x.test`, schoolId: 7, catalogCategory });

beforeEach(() => {
  vi.clearAllMocks(); auth.user = { role: 'OPERATIONS', userId: 42 };
  vi.mocked(trackerApi.summary).mockResolvedValue(summary());
  vi.mocked(trackerApi.designers).mockResolvedValue([]); vi.mocked(trackerApi.operators).mockResolvedValue([]);
  vi.mocked(trackerApi.draftImages).mockResolvedValue([]);
  URL.createObjectURL = vi.fn((blob: Blob) => `blob:${blob.type}`); URL.revokeObjectURL = vi.fn();
});
afterEach(cleanup);

describe('tracker rules', () => {
  it('counts words the way the server does', () => {
    expect(wordCount('')).toBe(0); expect(wordCount('  one   two\nthree ')).toBe(3);
  });
  it('flags a count mismatch unless accepted = dispatch and accepted <= produced <= ordered', () => {
    expect(countsMismatch(100, { producedCount: 100, acceptedCount: 90, dispatchCount: 90 })).toBe(false);
    expect(countsMismatch(100, { producedCount: 100, acceptedCount: 90, dispatchCount: 80 })).toBe(true);
    expect(countsMismatch(100, { producedCount: 80, acceptedCount: 90, dispatchCount: 90 })).toBe(true);
    expect(countsMismatch(100, { producedCount: 120, acceptedCount: 90, dispatchCount: 90 })).toBe(true);
    expect(countsMismatch(100, { producedCount: 100 })).toBe(false); // nothing typed yet is not a mismatch
  });
  it('words a time slot', () => {
    expect(slaLabel(step(5, 'x', { sla: { value: 1, unit: 'WORKING_DAYS', source: 'DEFAULT' } }))).toBe('1 working day');
    expect(slaLabel(step(5, 'x', { sla: { value: 4, unit: 'HOURS', source: 'SCHOOL' } }))).toBe('4 hours');
    expect(slaLabel(step(8, 'x', { sla: null }))).toBe('No timer');
  });
  it('refuses a response that is not a tracker instead of rendering a blank screen', () => {
    expect(() => parseTracker({ orderId: 'CK-1' })).toThrow();
    expect(() => parseTracker(null)).toThrow();
    expect(parseTracker({ ...tracker(), internalNotes: undefined }).internalNotes).toEqual([]);
  });
});

describe('NotebookTracker', () => {
  it('shows progress, locks later steps and opens the active step', () => {
    render(<NotebookTracker orderId="CK-1" initial={tracker()} onBack={vi.fn()} />);
    expect(screen.getByText(/Step 5 · Production Planning/)).toBeInTheDocument();
    expect(screen.getByText(/1 of 3 done/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /6\. Board Procurement/ })).toBeDisabled();
    expect(screen.getByRole('region', { name: 'Production Planning work area' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /5\. Production Planning/ })).toHaveAttribute('aria-current', 'step');
  });

  it('shows the order lines, delivery date, school notes and the onboarding contact', async () => {
    render(<NotebookTracker orderId="CK-1" initial={tracker()} onBack={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: /1\. Order Details/ }));
    const lines = await screen.findByRole('table', { name: 'Order lines' });
    expect(within(lines).getByText('Long')).toBeInTheDocument();
    expect(within(lines).getByText('Single rule')).toBeInTheDocument();
    expect(within(lines).getByText('198')).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Order Details work area' })).getByText('15 Dec 2026')).toBeInTheDocument();
    expect(screen.getByText('Please use green')).toBeInTheDocument();
    expect(screen.getByText(/office@seven.test · \+91 98765 43210/)).toBeInTheDocument();
  });

  it('says so when the school gave no contact at onboarding, and reports a summary that cannot load', async () => {
    vi.mocked(trackerApi.summary).mockResolvedValue(summary({ schoolContactEmail: null, schoolContactPhone: null, schoolNotes: null }));
    const { unmount } = render(<NotebookTracker orderId="CK-1" initial={tracker()} onBack={vi.fn()} />);
    expect(await screen.findByText(/no contact given at onboarding/)).toBeInTheDocument();
    expect(screen.queryByText('School feedback')).not.toBeInTheDocument();
    unmount();
    vi.mocked(trackerApi.summary).mockRejectedValue({ response: { data: { message: 'Order summary unavailable' } } });
    render(<NotebookTracker orderId="CK-1" initial={tracker()} onBack={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: /1\. Order Details/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Order summary unavailable');
  });

  it('gives the operator save, stage note and complete, but no reopen or sign-off', () => {
    render(<NotebookTracker orderId="CK-1" initial={tracker()} onBack={vi.fn()} />);
    for (const name of [/Save draft/, 'Save stage note', /Complete step/]) expect(screen.getByRole('button', { name })).toBeEnabled();
    expect(screen.queryByRole('button', { name: /Reopen step/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Sign off/ })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Assigned to')).not.toBeInTheDocument();
  });

  it('lets superadmin reopen a completed step but never write its stage note', async () => {
    auth.user = { role: 'SUPERADMIN', userId: 1 };
    render(<NotebookTracker orderId="CK-1" initial={tracker()} onBack={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: /1\. Order Details/ }));
    expect(screen.getByRole('button', { name: /Reopen step/ })).toBeInTheDocument();
    expect(screen.getByLabelText(/Stage note/)).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Save stage note' })).not.toBeInTheDocument();
    await waitFor(() => expect(trackerApi.operators).toHaveBeenCalled());
  });

  it('completes a step with the version it was loaded at and shows the new state', async () => {
    const done = tracker({ progress: { completed: 2, total: 3, currentStep: 6, currentTitle: 'Board Procurement', finished: false } });
    vi.mocked(trackerApi.complete).mockResolvedValue(done);
    const changed = vi.fn();
    render(<NotebookTracker orderId="CK-1" initial={tracker()} onBack={vi.fn()} onChanged={changed} />);
    fireEvent.click(screen.getByRole('button', { name: /Complete step/ }));
    await waitFor(() => expect(trackerApi.complete).toHaveBeenCalledWith('CK-1', 5, 3));
    expect(await screen.findByText(/2 of 3 done/)).toBeInTheDocument();
    expect(changed).toHaveBeenCalled();
  });

  it('shows the server reason when a save is refused, and keeps what was typed', async () => {
    vi.mocked(trackerApi.saveDraft).mockRejectedValue({ response: { data: { code: 'INVALID_STEP_DATA', message: 'paperGsm is limited to 50 characters' } } });
    render(<NotebookTracker orderId="CK-1" initial={tracker()} onBack={vi.fn()} />);
    fireEvent.change(screen.getByLabelText('Paper GSM'), { target: { value: '300' } });
    fireEvent.click(screen.getByRole('button', { name: /Save draft/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('paperGsm is limited to 50 characters');
    expect(screen.getByLabelText('Paper GSM')).toHaveValue('300');
    expect(trackerApi.saveDraft).toHaveBeenCalledWith('CK-1', 5, { paperGsm: '300' }, 3);
  });

  it('sends numbers as numbers and leaves blank fields out of a draft', async () => {
    vi.mocked(trackerApi.saveDraft).mockResolvedValue(tracker());
    const t = tracker({ steps: [step(6, 'Board Procurement', { status: 'ACTIVE', can: can({ canEditDetails: true }) })], progress: { completed: 0, total: 1, currentStep: 6, currentTitle: 'Board Procurement', finished: false } });
    render(<NotebookTracker orderId="CK-1" initial={t} onBack={vi.fn()} />);
    fireEvent.change(screen.getByLabelText('Total title units'), { target: { value: '12' } });
    fireEvent.change(screen.getByLabelText('Wastage %'), { target: { value: '2.5' } });
    fireEvent.click(screen.getByRole('button', { name: /Save draft/ }));
    await waitFor(() => expect(trackerApi.saveDraft).toHaveBeenCalledWith('CK-1', 6, { totalTitleUnits: 12, wastagePercent: 2.5 }, 3));
  });

  it('refuses a stage note over 100 words before it is sent', () => {
    render(<NotebookTracker orderId="CK-1" initial={tracker()} onBack={vi.fn()} />);
    fireEvent.change(screen.getByLabelText(/Stage note/), { target: { value: Array(101).fill('word').join(' ') } });
    expect(screen.getByRole('alert')).toHaveTextContent('limited to 100 words');
    expect(screen.getByRole('button', { name: 'Save stage note' })).toBeDisabled();
  });

  it('marks an overdue active step red and a finished one with its completion time', () => {
    render(<NotebookTracker orderId="CK-1" initial={tracker({ steps: [
      step(1, 'Order Details', { status: 'COMPLETED', completedAt: '2026-10-19T05:00:00Z' }),
      step(5, 'Production Planning', { status: 'ACTIVE', overdue: true, dueAt: '2026-10-19T09:00:00Z' }),
    ] })} onBack={vi.fn()} />);
    expect(screen.getByText(/Overdue · was due/)).toHaveClass('ck-trk-late');
    expect(screen.getByText(/^Done /)).toBeInTheDocument();
  });

  it('warns of a count mismatch on step 11 and asks for a discrepancy note', () => {
    const t = tracker({ orderedCount: 100, progress: { completed: 0, total: 1, currentStep: 11, currentTitle: 'Validation Count', finished: false },
      steps: [step(11, 'Validation Count', { status: 'ACTIVE', data: { producedCount: 100, acceptedCount: 90, dispatchCount: 80 }, can: can({ canEditDetails: true, canComplete: true }) })] });
    render(<NotebookTracker orderId="CK-1" initial={t} onBack={vi.fn()} />);
    expect(screen.getByRole('alert')).toHaveTextContent('do not match');
    fireEvent.change(screen.getByLabelText('Dispatch'), { target: { value: '90' } });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows the eight optional pre-print checks and sends the ticked positions', async () => {
    vi.mocked(trackerApi.saveDraft).mockResolvedValue(tracker());
    const t = tracker({ progress: { completed: 0, total: 1, currentStep: 8, currentTitle: 'Pre-Print Quality Gate', finished: false },
      steps: [step(8, 'Pre-Print Quality Gate', { status: 'ACTIVE', sla: null, can: can({ canEditDetails: true }) })] });
    render(<NotebookTracker orderId="CK-1" initial={t} onBack={vi.fn()} />);
    expect(screen.getAllByRole('checkbox')).toHaveLength(8);
    fireEvent.click(screen.getByLabelText(/Latest approved artwork/)); fireEvent.click(screen.getByLabelText(/School name, logo/));
    fireEvent.click(screen.getByRole('button', { name: /Save draft/ }));
    await waitFor(() => expect(trackerApi.saveDraft).toHaveBeenCalledWith('CK-1', 8, { checks: [0, 1] }, 3));
  });

  it('offers sign-off to superadmin only, and only until it is given', async () => {
    const t = tracker({ progress: { completed: 0, total: 1, currentStep: 12, currentTitle: 'Delivery & Invoicing', finished: false },
      steps: [step(12, 'Delivery & Invoicing', { status: 'ACTIVE', can: can({ canEditDetails: true }) })] });
    const { unmount } = render(<NotebookTracker orderId="CK-1" initial={t} onBack={vi.fn()} />);
    expect(screen.queryByRole('button', { name: /Sign off/ })).not.toBeInTheDocument();
    unmount();
    auth.user = { role: 'SUPERADMIN', userId: 1 };
    vi.mocked(trackerApi.signOff).mockResolvedValue(tracker({ steps: [step(12, 'Delivery & Invoicing', { status: 'ACTIVE', data: { signOff: { by: 1, at: 'x' } } })] }));
    render(<NotebookTracker orderId="CK-1" initial={t} onBack={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: /Sign off/ }));
    await waitFor(() => expect(trackerApi.signOff).toHaveBeenCalledWith('CK-1', 12));
    expect(await screen.findByText('Signed off', { selector: 'span' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Sign off/ })).not.toBeInTheDocument();
  });

  it('adds an internal note and clears the box', async () => {
    vi.mocked(trackerApi.addInternalNote).mockResolvedValue(tracker({ internalNotes: [{ id: 1, authorUserId: 42, body: 'Called the vendor', createdAt: '2026-10-19T05:00:00Z' }] }));
    render(<NotebookTracker orderId="CK-1" initial={tracker()} onBack={vi.fn()} />);
    fireEvent.change(screen.getByLabelText(/Add a note/), { target: { value: 'Called the vendor' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add note' }));
    expect(await screen.findByText('Called the vendor')).toBeInTheDocument();
    expect(screen.getByLabelText(/Add a note/)).toHaveValue('');
  });

  it('loads the tracker itself when none was given, reports a failure and retries', async () => {
    vi.mocked(trackerApi.get).mockRejectedValueOnce({ response: { data: { message: 'Order not found' } } }).mockResolvedValue(tracker());
    render(<NotebookTracker orderId="CK-404" onBack={vi.fn()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Order not found');
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText(/Step 5 · Production Planning/)).toBeInTheDocument();
    expect(trackerApi.get).toHaveBeenCalledTimes(2);
  });

  it('runs a wholesale order through nine steps, without the design steps', () => {
    const path = [1, 5, 6, 7, 8, 9, 10, 11, 12];
    render(<NotebookTracker orderId="CK-1" onBack={vi.fn()} initial={tracker({ customised: false,
      progress: { completed: 0, total: 9, currentStep: 1, currentTitle: 'Order Details', finished: false },
      steps: path.map((n, i) => step(n, TITLES[n], { status: i === 0 ? 'ACTIVE' : 'LOCKED' })) })} />);
    expect(within(screen.getByRole('list', { name: 'Order progress' })).getAllByRole('listitem')).toHaveLength(9);
    expect(within(screen.getByRole('navigation', { name: 'Stages' })).getAllByRole('button')).toHaveLength(9);
    expect(screen.getByText(/0 of 9 done/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Designing Activity/ })).not.toBeInTheDocument();
  });

  it('names every step and its state in the progress bar, not just a number', () => {
    render(<NotebookTracker orderId="CK-1" onBack={vi.fn()} initial={tracker({ steps: [
      step(1, 'Order Details', { status: 'COMPLETED', completedAt: '2026-10-19T05:00:00Z' }),
      step(5, 'Production Planning', { status: 'ACTIVE', overdue: true }),
      step(6, 'Board Procurement'),
    ] })} />);
    const items = within(screen.getByRole('list', { name: 'Order progress' })).getAllByRole('listitem');
    expect(items[0]).toHaveTextContent('Step 1, Order Details: completed');
    expect(items[1]).toHaveTextContent('Step 5, Production Planning: overdue');
    expect(items[2]).toHaveTextContent('Step 6, Board Procurement: locked');
  });

  it('puts the delivery date and a summary of sizes and pages in the header', async () => {
    vi.mocked(trackerApi.summary).mockResolvedValue(summary({ lines: [
      { lineNo: 1, selections: { SIZE: { code: 'LONG', label: 'Long' }, RULING: { code: 'SINGLE_RULE', label: 'Single rule' } }, bookCount: 400, pageCount: 196 },
      { lineNo: 2, selections: { SIZE: { code: 'LONG', label: 'Long' }, RULING: { code: 'FOUR_LINE', label: 'Four line' } }, bookCount: 300, pageCount: 196 },
      { lineNo: 3, selections: { SIZE: { code: 'KING', label: 'King' }, RULING: { code: 'SINGLE_RULE', label: 'Single rule' } }, bookCount: 300, pageCount: 98 },
    ] }));
    render(<NotebookTracker orderId="CK-1" initial={tracker()} onBack={vi.fn()} />);
    const head = screen.getByRole('group', { name: 'Order CK-1' });
    expect(await within(head).findByText('15 Dec 2026')).toBeInTheDocument();
    expect(within(head).getByText('Deliver by')).toBeInTheDocument();
    expect(within(head).getByText('Long · 196 pages · 700 books')).toBeInTheDocument();
    expect(within(head).getByText('King · 98 pages · 300 books')).toBeInTheDocument();
  });

  it('shows who wrote each internal note', async () => {
    auth.user = { role: 'SUPERADMIN', userId: 1 };
    vi.mocked(trackerApi.operators).mockResolvedValue([operator(12, 'Op Twelve')]);
    const at = '2026-10-19T05:00:00Z';
    render(<NotebookTracker orderId="CK-1" onBack={vi.fn()} initial={tracker({ internalNotes: [
      { id: 1, authorUserId: 12, body: 'Vendor called back', createdAt: at },
      { id: 2, authorUserId: 1, body: 'Checked the artwork', createdAt: at },
      { id: 3, authorUserId: 99, body: 'Old note', createdAt: at },
    ] })} />);
    const notes = screen.getByRole('list', { name: 'Internal notes' });
    expect(await within(notes).findByText(/Op Twelve ·/)).toBeInTheDocument();
    expect(within(notes).getByText(/You ·/)).toBeInTheDocument();
    expect(within(notes).getByText(/User 99 ·/)).toBeInTheDocument();
  });

  it('notes below its name that Board Procurement is still to be decided, and still completes it', () => {
    render(<NotebookTracker orderId="CK-1" initial={onStep(6)} onBack={vi.fn()} />);
    const area = screen.getByRole('region', { name: 'Board Procurement work area' });
    expect(within(area).getByText(/TBD/)).toBeInTheDocument();
    expect(within(area).getByRole('button', { name: /Complete step/ })).toBeEnabled();
  });

  it('offers superadmin to give a whole unassigned order to one operator', async () => {
    auth.user = { role: 'SUPERADMIN', userId: 1 };
    vi.mocked(trackerApi.operators).mockResolvedValue([operator(12, 'Op Twelve'), operator(13, 'Op Elsewhere', 'TROPHIES')]);
    vi.mocked(trackerApi.assignOrder).mockResolvedValue(tracker());
    const unassigned = tracker({ steps: tracker().steps.map((s) => ({ ...s, assigneeUserId: null })) });
    render(<NotebookTracker orderId="CK-1" initial={unassigned} onBack={vi.fn()} />);
    const assignment = screen.getByRole('region', { name: 'Assignment' });
    expect(within(assignment).getByText(/not assigned/)).toBeInTheDocument();
    const select = within(assignment).getByLabelText('Assign order to');
    await within(select).findByRole('option', { name: 'Op Twelve' });
    expect(within(select).queryByRole('option', { name: 'Op Elsewhere' })).not.toBeInTheDocument();
    expect(within(assignment).getByRole('button', { name: 'Assign order' })).toBeDisabled();
    fireEvent.change(select, { target: { value: '12' } });
    fireEvent.click(within(assignment).getByRole('button', { name: 'Assign order' }));
    await waitFor(() => expect(trackerApi.assignOrder).toHaveBeenCalledWith('CK-1', 12));
  });

  it('says why the order cannot be assigned when the operator list does not load', async () => {
    auth.user = { role: 'SUPERADMIN', userId: 1 };
    vi.mocked(trackerApi.operators).mockRejectedValue({ response: { data: { message: 'Identity is unavailable' } } });
    render(<NotebookTracker orderId="CK-1" initial={tracker()} onBack={vi.fn()} />);
    const assignment = screen.getByRole('region', { name: 'Assignment' });
    expect(await within(assignment).findByRole('alert')).toHaveTextContent('Identity is unavailable');
  });

  it('gives operators no order assignment', () => {
    render(<NotebookTracker orderId="CK-1" initial={tracker({ steps: tracker().steps.map((s) => ({ ...s, assigneeUserId: null })) })} onBack={vi.fn()} />);
    expect(screen.queryByRole('button', { name: 'Assign order' })).not.toBeInTheDocument();
  });
});

describe('design and vendor steps', () => {
  const vendor = { name: 'Sharma Press', phone: '+91 90000 11111' };
  const designStep = (rest: Partial<TrackerView> = {}) => onStep(2, {}, { customised: true, vendor, designer: { id: 3, name: 'Priya Singh', phone: '98100 22222' },
    design: { state: 'NOT_SENT', comment: null, at: null, by: null, approvedDraftRef: null, canManageImages: true }, ...rest });

  it('shows the vendor and designer cards on the design step, not on the factory handover', async () => {
    vi.mocked(trackerApi.designers).mockResolvedValue([{ id: 3, name: 'Priya Singh', phone: '98100 22222', active: true }]);
    const { unmount } = render(<NotebookTracker orderId="CK-1" initial={designStep()} onBack={vi.fn()} />);
    const area = screen.getByRole('region', { name: 'Designing Activity work area' });
    const vendorCard = within(area).getByRole('region', { name: 'Vendor' });
    expect(within(vendorCard).getByText('Sharma Press')).toBeInTheDocument();
    expect(within(vendorCard).getByText('+91 90000 11111')).toBeInTheDocument();
    const designerCard = within(area).getByRole('region', { name: 'Designer' });
    expect(within(designerCard).getByText('Priya Singh')).toBeInTheDocument();
    expect(within(designerCard).getByText('98100 22222')).toBeInTheDocument();
    unmount();
    render(<NotebookTracker orderId="CK-1" initial={onStep(9, {}, { vendor })} onBack={vi.fn()} />);
    expect(within(screen.getByRole('region', { name: 'Factory Handover work area' })).queryByText(/Sharma Press/)).not.toBeInTheDocument();
  });

  it('lets superadmin set the vendor on the design step', async () => {
    auth.user = { role: 'SUPERADMIN', userId: 1 };
    vi.mocked(trackerApi.setVendor).mockResolvedValue(designStep());
    render(<NotebookTracker orderId="CK-1" initial={designStep({ vendor: null })} onBack={vi.fn()} />);
    const card = within(screen.getByRole('region', { name: 'Designing Activity work area' })).getByRole('region', { name: 'Vendor' });
    fireEvent.change(within(card).getByLabelText('Vendor name'), { target: { value: ' Sharma Press ' } });
    fireEvent.change(within(card).getByLabelText('Vendor phone'), { target: { value: '+91 90000 11111' } });
    fireEvent.click(within(card).getByRole('button', { name: 'Save vendor' }));
    await waitFor(() => expect(trackerApi.setVendor).toHaveBeenCalledWith('CK-1', 'Sharma Press', '+91 90000 11111'));
  });

  it('shows the printing vendor and its contact on the printing press step', () => {
    render(<NotebookTracker orderId="CK-1" initial={onStep(7, {}, { vendor })} onBack={vi.fn()} />);
    const area = screen.getByRole('region', { name: 'Printing Press work area' });
    const card = within(area).getByRole('region', { name: 'Printing vendor' });
    expect(within(card).getByText('Sharma Press')).toBeInTheDocument();
    expect(within(card).getByText('Contact')).toBeInTheDocument();
    expect(within(card).getByText('+91 90000 11111')).toBeInTheDocument();
    for (const label of ['Press job reference', 'Quantity sent', 'Expected return']) expect(within(area).getByLabelText(label)).toBeInTheDocument();
  });

  it('previews the newest image on the design step and never downloads a PDF for a thumbnail', async () => {
    vi.mocked(trackerApi.draftImages).mockResolvedValue([draft(1, 'first.png'), draft(2, 'latest.webp', 'image/webp'), draft(3, 'proof.pdf', 'application/pdf')]);
    vi.mocked(trackerApi.draftBlob).mockImplementation(async (_order, id) => new Blob(['x'], { type: id === 2 ? 'image/webp' : 'image/png' }));
    render(<NotebookTracker orderId="CK-1" initial={designStep()} onBack={vi.fn()} />);
    const preview = await screen.findByRole('img', { name: 'Current design preview: latest.webp' });
    await waitFor(() => expect(preview).toHaveAttribute('src', 'blob:image/webp'));
    expect(screen.getByText('proof.pdf')).toBeInTheDocument();
    expect(trackerApi.draftBlob).toHaveBeenCalledWith('CK-1', 1);
    expect(trackerApi.draftBlob).toHaveBeenCalledWith('CK-1', 2);
    expect(trackerApi.draftBlob).not.toHaveBeenCalledWith('CK-1', 3);
  });

  it('lets superadmin choose one draft on head approval and approve it', async () => {
    auth.user = { role: 'SUPERADMIN', userId: 1 };
    vi.mocked(trackerApi.draftImages).mockResolvedValue([draft(1, 'v1.png'), draft(2, 'v2.png')]);
    vi.mocked(trackerApi.draftBlob).mockRejectedValue(new Error('no preview'));
    vi.mocked(trackerApi.approveDesign).mockResolvedValue(tracker());
    render(<NotebookTracker orderId="CK-1" onBack={vi.fn()} initial={onStep(3, {}, { customised: true,
      design: { state: 'SENT', comment: null, at: null, by: null, approvedDraftRef: null, canManageImages: false } })} />);
    const approve = screen.getByRole('button', { name: /Approve selected draft/ });
    expect(approve).toBeDisabled();
    const versions = await screen.findByRole('group', { name: 'Design version to approve' });
    fireEvent.click(await within(versions).findByRole('radio', { name: 'v2.png' }));
    fireEvent.click(approve);
    await waitFor(() => expect(trackerApi.approveDesign).toHaveBeenCalledWith('CK-1', 2, undefined));
  });
});

describe('OrderDetailEntry', () => {
  it('opens the tracker for staff when the order is on the tracker', async () => {
    vi.mocked(trackerApi.get).mockResolvedValue(tracker());
    render(<OrderDetailEntry orderId="CK-1" trackerManaged onBack={vi.fn()} />);
    expect(await screen.findByText(/Step 5 · Production Planning/)).toBeInTheDocument();
    expect(screen.queryByText('ordinary order detail')).not.toBeInTheDocument();
  });
  it('keeps the ordinary order screen for any order not on the tracker, and never asks the tracker about it', async () => {
    auth.user = { role: 'SUPERADMIN', userId: 1 };
    render(<OrderDetailEntry orderId="ORD-9" trackerManaged={false} onBack={vi.fn()} />);
    expect(await screen.findByText('ordinary order detail')).toBeInTheDocument();
    expect(trackerApi.get).not.toHaveBeenCalled();
    expect(trackerApi.summary).not.toHaveBeenCalled();
  });
  it('says why the tracker could not load and offers Retry, never falling back to the old order screen', async () => {
    vi.mocked(trackerApi.get).mockRejectedValueOnce({ response: { status: 503, data: { message: 'Tracker service unavailable' } } }).mockResolvedValue(tracker());
    render(<OrderDetailEntry orderId="CK-1" trackerManaged onBack={vi.fn()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Tracker service unavailable');
    expect(screen.queryByText('ordinary order detail')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText(/Step 5 · Production Planning/)).toBeInTheDocument();
    expect(trackerApi.get).toHaveBeenCalledTimes(2);
  });
  it('treats a missing tracker that is not waiting to be created as an error too', async () => {
    vi.mocked(trackerApi.get).mockRejectedValue({ response: { status: 404, data: { message: 'Tracker not found' } } });
    render(<OrderDetailEntry orderId="CK-1" trackerManaged onBack={vi.fn()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Tracker not found');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.queryByText('ordinary order detail')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Start tracker' })).not.toBeInTheDocument();
  });
  it('offers to start the tracker when placement could not, and opens it once started', async () => {
    vi.mocked(trackerApi.get).mockRejectedValueOnce({ response: { status: 404 } }).mockResolvedValue(tracker());
    vi.mocked(trackerApi.summary).mockResolvedValue(summary({ trackerPending: true }));
    vi.mocked(trackerApi.startTracker).mockResolvedValue({ trackerPending: false });
    render(<OrderDetailEntry orderId="CK-1" trackerManaged onBack={vi.fn()} />);
    expect(await screen.findByText('ordinary order detail')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Start tracker' }));
    expect(await screen.findByText(/Step 5 · Production Planning/)).toBeInTheDocument();
    expect(trackerApi.startTracker).toHaveBeenCalledWith('CK-1');
  });
  it('keeps the offer and says why when the tracker service is still unreachable', async () => {
    vi.mocked(trackerApi.get).mockRejectedValue({ response: { status: 404 } });
    vi.mocked(trackerApi.summary).mockResolvedValue(summary({ trackerPending: true }));
    vi.mocked(trackerApi.startTracker).mockResolvedValue({ trackerPending: true });
    render(<OrderDetailEntry orderId="CK-1" trackerManaged onBack={vi.fn()} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Start tracker' }));
    expect(await screen.findByText(/could not be reached/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Start tracker' })).toBeEnabled();
  });
  it('never asks a school administrator for a tracker', async () => {
    auth.user = { role: 'SCHOOL_ADMIN', userId: 5 };
    render(<OrderDetailEntry orderId="CK-1" trackerManaged onBack={vi.fn()} />);
    expect(await screen.findByText('ordinary order detail')).toBeInTheDocument();
    expect(trackerApi.get).not.toHaveBeenCalled();
  });
});

describe('DraftImages', () => {
  const image = (id: number, name: string) => draft(id, name);
  it('lists the drafts with a count of the six allowed and shows the server message for a seventh', async () => {
    vi.mocked(trackerApi.draftImages).mockResolvedValue([1, 2, 3, 4, 5, 6].map((i) => image(i, `v${i}.png`)));
    vi.mocked(trackerApi.draftBlob).mockRejectedValue(new Error('no preview'));
    vi.mocked(trackerApi.uploadDraft).mockRejectedValue({ response: { data: { message: 'No more images allowed. Contact IT.' } } });
    render(<DraftImages orderId="CK-1" canManage />);
    expect(await screen.findByText('6 of 6')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Upload design draft'), { target: { files: [new File(['x'], 'v7.png', { type: 'image/png' })] } });
    expect(await screen.findByRole('alert')).toHaveTextContent('No more images allowed. Contact IT.');
    expect(screen.getByText('Latest: v6.png')).toBeInTheDocument();
  });
  it('deletes a draft and reloads the list', async () => {
    vi.mocked(trackerApi.draftImages).mockResolvedValueOnce([image(1, 'a.png'), image(2, 'b.png')]).mockResolvedValue([image(2, 'b.png')]);
    vi.mocked(trackerApi.draftBlob).mockRejectedValue(new Error('no preview'));
    vi.mocked(trackerApi.deleteDraft).mockResolvedValue(undefined);
    render(<DraftImages orderId="CK-1" canManage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Delete a.png' }));
    await waitFor(() => expect(trackerApi.deleteDraft).toHaveBeenCalledWith('CK-1', 1));
    expect(await screen.findByText('1 of 6')).toBeInTheDocument();
  });
  it('gives a viewer who cannot manage no upload or delete controls', async () => {
    vi.mocked(trackerApi.draftImages).mockResolvedValue([image(1, 'a.png')]);
    vi.mocked(trackerApi.draftBlob).mockRejectedValue(new Error('no preview'));
    render(<DraftImages orderId="CK-1" canManage={false} />);
    const section = await screen.findByRole('region', { name: 'Design drafts' });
    expect(within(section).queryByRole('button', { name: /Upload draft/ })).not.toBeInTheDocument();
    expect(within(section).queryByRole('button', { name: /Delete/ })).not.toBeInTheDocument();
  });
  it('shows thumbnails as pictures rather than buttons that do nothing, and a PDF as a file chip', async () => {
    vi.mocked(trackerApi.draftImages).mockResolvedValue([image(1, 'a.png'), draft(2, 'b.pdf', 'application/pdf')]);
    vi.mocked(trackerApi.draftBlob).mockResolvedValue(new Blob(['x'], { type: 'image/png' }));
    render(<DraftImages orderId="CK-1" canManage={false} />);
    const section = await screen.findByRole('region', { name: 'Design drafts' });
    expect(await within(section).findByRole('img', { name: 'a.png' })).toHaveAttribute('src', 'blob:image/png');
    expect(within(section).getByText('PDF')).toBeInTheDocument();
    expect(within(section).queryByRole('button')).not.toBeInTheDocument();
    expect(within(section).queryByRole('radio')).not.toBeInTheDocument();
    expect(trackerApi.draftBlob).toHaveBeenCalledTimes(1);
  });
  it('does not download a draft again when the list reloads, and releases a deleted one', async () => {
    vi.mocked(trackerApi.draftImages).mockResolvedValueOnce([image(1, 'a.png'), image(2, 'b.png')]).mockResolvedValue([image(2, 'b.png')]);
    vi.mocked(trackerApi.draftBlob).mockResolvedValue(new Blob(['x'], { type: 'image/png' }));
    vi.mocked(trackerApi.deleteDraft).mockResolvedValue(undefined);
    render(<DraftImages orderId="CK-1" canManage />);
    expect(await screen.findByRole('img', { name: 'a.png' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Delete a.png' }));
    expect(await screen.findByText('1 of 6')).toBeInTheDocument();
    expect(trackerApi.draftBlob).toHaveBeenCalledTimes(2);
    await waitFor(() => expect(URL.revokeObjectURL).toHaveBeenCalledTimes(1));
  });
});
