import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NotebookTracker } from './NotebookTracker';
import { OrderDetailEntry } from './OrderDetailEntry';
import { DraftImages } from './DraftImages';
import { parseTracker, trackerApi } from './api';
import { countsMismatch, slaLabel, wordCount } from './steps';
import type { OrderSummary, TrackerStep, TrackerView } from './types';

const auth = vi.hoisted(() => ({ user: { role: 'OPERATIONS', userId: 42 } as { role: string; userId: number } | null }));
vi.mock('../../contexts/AuthContext', () => ({ useAuth: () => ({ user: auth.user }) }));
vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api')>();
  return { ...actual, trackerApi: {
    get: vi.fn(), saveDraft: vi.fn(), saveStageNote: vi.fn(), complete: vi.fn(), reopen: vi.fn(), signOff: vi.fn(), assignStep: vi.fn(),
    designers: vi.fn(), operators: vi.fn(), addInternalNote: vi.fn(), draftImages: vi.fn(), uploadDraft: vi.fn(), deleteDraft: vi.fn(),
    draftBlob: vi.fn(), sendDesign: vi.fn(), addFollowUp: vi.fn(), summary: vi.fn(), startTracker: vi.fn(),
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

beforeEach(() => {
  vi.clearAllMocks(); auth.user = { role: 'OPERATIONS', userId: 42 };
  vi.mocked(trackerApi.summary).mockResolvedValue(summary());
  vi.mocked(trackerApi.designers).mockResolvedValue([]); vi.mocked(trackerApi.operators).mockResolvedValue([]);
  vi.mocked(trackerApi.draftImages).mockResolvedValue([]);
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
    expect(screen.getByText('2026-12-15')).toBeInTheDocument();
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

  it('loads the tracker itself when none was given and reports a failure', async () => {
    vi.mocked(trackerApi.get).mockRejectedValue({ response: { data: { message: 'Order not found' } } });
    render(<NotebookTracker orderId="CK-404" onBack={vi.fn()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Order not found');
  });
});

describe('OrderDetailEntry', () => {
  it('opens the tracker for staff when the order has one', async () => {
    vi.mocked(trackerApi.get).mockResolvedValue(tracker());
    render(<OrderDetailEntry orderId="CK-1" onBack={vi.fn()} />);
    expect(await screen.findByText(/Step 5 · Production Planning/)).toBeInTheDocument();
    expect(screen.queryByText('ordinary order detail')).not.toBeInTheDocument();
  });
  it('falls back to the ordinary order for staff when there is no tracker', async () => {
    vi.mocked(trackerApi.get).mockRejectedValue({ response: { status: 404 } });
    render(<OrderDetailEntry orderId="CK-9" onBack={vi.fn()} />);
    expect(await screen.findByText('ordinary order detail')).toBeInTheDocument();
  });
  it('offers to start the tracker when placement could not, and opens it once started', async () => {
    vi.mocked(trackerApi.get).mockRejectedValueOnce({ response: { status: 404 } }).mockResolvedValue(tracker());
    vi.mocked(trackerApi.summary).mockResolvedValue(summary({ trackerPending: true }));
    vi.mocked(trackerApi.startTracker).mockResolvedValue({ trackerPending: false });
    render(<OrderDetailEntry orderId="CK-1" onBack={vi.fn()} />);
    expect(await screen.findByText('ordinary order detail')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Start tracker' }));
    expect(await screen.findByText(/Step 5 · Production Planning/)).toBeInTheDocument();
    expect(trackerApi.startTracker).toHaveBeenCalledWith('CK-1');
  });
  it('keeps the offer and says why when the tracker service is still unreachable', async () => {
    vi.mocked(trackerApi.get).mockRejectedValue({ response: { status: 404 } });
    vi.mocked(trackerApi.summary).mockResolvedValue(summary({ trackerPending: true }));
    vi.mocked(trackerApi.startTracker).mockResolvedValue({ trackerPending: true });
    render(<OrderDetailEntry orderId="CK-1" onBack={vi.fn()} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Start tracker' }));
    expect(await screen.findByText(/could not be reached/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Start tracker' })).toBeEnabled();
  });
  it('offers nothing for an order that is not waiting on a tracker', async () => {
    vi.mocked(trackerApi.get).mockRejectedValue({ response: { status: 404 } });
    vi.mocked(trackerApi.summary).mockRejectedValue({ response: { status: 400 } });
    render(<OrderDetailEntry orderId="ORD-9" onBack={vi.fn()} />);
    expect(await screen.findByText('ordinary order detail')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Start tracker' })).not.toBeInTheDocument();
  });
  it('never asks a school administrator for a tracker', async () => {
    auth.user = { role: 'SCHOOL_ADMIN', userId: 5 };
    render(<OrderDetailEntry orderId="CK-1" onBack={vi.fn()} />);
    expect(await screen.findByText('ordinary order detail')).toBeInTheDocument();
    expect(trackerApi.get).not.toHaveBeenCalled();
  });
});

describe('DraftImages', () => {
  const image = (id: number, name: string) => ({ id, contentType: 'image/png', originalFilename: name, sizeBytes: 10, uploadedAt: `2026-10-19T0${id}:00:00Z` });
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
});
