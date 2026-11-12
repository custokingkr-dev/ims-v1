import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { NotebookTrackerClient } from '../../generated/notebookTrackerClient';
import api from '../../services/api';
import { formatSchoolDateTime } from '../../utils/schoolLocalization';
import { ProductOrderWorkflow, type ProductionStage, type ProductionWorkflowView } from './ProductOrderWorkflow';

vi.mock('../../services/api', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn() } }));
/**
 * The WhatsApp links must go through the generated, contract-checked client. These spies wrap the real generated client,
 * which still sends its requests through the mocked api above, so the URL assertions below check the wire path too.
 */
const generated = vi.hoisted(() => ({ client: undefined as NotebookTrackerClient | undefined }));
vi.mock('../../generated/notebookTrackerClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../generated/notebookTrackerClient')>();
  return {
    ...actual,
    createNotebookTrackerClient: (http: Parameters<typeof actual.createNotebookTrackerClient>[0]) => {
      const client = actual.createNotebookTrackerClient(http);
      vi.spyOn(client, 'getSchoolWhatsAppGroupLinks');
      vi.spyOn(client, 'saveSchoolWhatsAppGroupLink');
      generated.client = client;
      return client;
    },
  };
});
const contract = () => {
  if (!generated.client) throw new Error('The panel never created the generated notebook tracker client');
  return generated.client;
};
const { readFileSync } = await vi.importActual<{ readFileSync: (path: string, encoding: string) => string }>('node:fs');
const { cwd } = await vi.importActual<{ cwd: () => string }>('node:process');

beforeEach(() => { vi.clearAllMocks(); });
afterEach(cleanup);

const WORKFLOW = '/catalog/orders/CK-42/production-workflow';
const LINKS = '/workflows/school-whatsapp-groups/7';
const SCHOOL_INVITE = 'https://chat.whatsapp.com/SchoolGroup123';
const VENDOR_INVITE = 'https://chat.whatsapp.com/VendorGroup456';
const COMPLETED_AT = '2026-11-10T09:30:00Z';
const STAGES = [
  ['ORDER_DETAILS', 'Order Details'], ['DESIGN_ACTIVITY', 'Designing Activity'], ['CDR_CONVERSION', 'CDR Conversion'],
  ['HEAD_APPROVAL', 'Head Approval'], ['PRODUCTION_PLANNING', 'Production Planning'], ['PRE_PRINT_QUALITY_CHECK', 'Pre-Print Quality Check'],
  ['PRODUCTION_FOLLOW_UP', 'Production Follow-up'], ['FINAL_VERIFICATION', 'Final Verification'], ['DELIVERY_INVOICING', 'Delivery & Invoicing'],
] as const;

/** A workflow whose stage at `active` is in progress; everything before it is completed with a recorded update. */
function view(active: number, overrides: Partial<ProductionWorkflowView> = {}, stages: Record<string, Partial<ProductionStage>> = {}): ProductionWorkflowView {
  return {
    orderId: 'CK-42', category: 'BROCHURES', status: active >= STAGES.length ? 'COMPLETED' : 'ACTIVE',
    currentStage: STAGES[Math.min(active, STAGES.length - 1)][0], version: 3, canEdit: true, canComplete: true,
    stages: STAGES.map(([code, title], index) => ({
      code, order: index + 1, title,
      status: index < active ? 'COMPLETED' : index === active ? 'ACTIVE' : 'LOCKED',
      data: index < active ? { details: `${title} recorded` } : {}, note: null,
      completedAt: index < active ? COMPLETED_AT : null, ...stages[code],
    })),
    ...overrides,
  };
}
/** What a school-side viewer receives: the same progress with every stage's data and note redacted. */
const redacted = (workflow: ProductionWorkflowView): ProductionWorkflowView => ({
  ...workflow, canEdit: false, canComplete: false, stages: workflow.stages.map((stage) => ({ ...stage, data: {}, note: null })),
});
const httpError = (status: number, message: string) => Object.assign(new Error(`Request failed with status code ${status}`),
  { response: { status, data: { message } } });

type Handler = unknown | (() => unknown);
/** Routes GETs by URL; a handler that throws becomes a rejected request, and an unknown URL fails the test loudly. */
function serve(routes: Record<string, Handler>) {
  vi.mocked(api.get).mockImplementation(async (url: string) => {
    if (!(url in routes)) throw new Error(`Unexpected GET ${url}`);
    const handler = routes[url];
    return { data: typeof handler === 'function' ? (handler as () => unknown)() : handler };
  });
}
const getsTo = (url: string) => vi.mocked(api.get).mock.calls.filter(([called]) => called === url).length;
const renderPanel = (props: Partial<Parameters<typeof ProductOrderWorkflow>[0]> = {}) =>
  render(<ProductOrderWorkflow orderId="CK-42" schoolId={7} staff superadmin={false} {...props} />);
const detailsBox = () => screen.getByRole('textbox', { name: 'Production update' }) as HTMLTextAreaElement;
const noteBox = () => screen.getByRole('textbox', { name: 'Stage note' }) as HTMLTextAreaElement;

describe('stage editing follows the server', () => {
  it('completes the active stage by its stable code at the loaded version', async () => {
    serve({ [WORKFLOW]: view(5), [LINKS]: {} });
    vi.mocked(api.post).mockResolvedValue({ data: view(6, { version: 4 }) });
    renderPanel();
    expect(await screen.findByRole('heading', { name: 'Pre-Print Quality Check' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Complete stage' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith(`${WORKFLOW}/stages/PRE_PRINT_QUALITY_CHECK/complete`, { expectedVersion: 3 }));
    expect(api.put).not.toHaveBeenCalled();
    expect(await screen.findByRole('heading', { name: 'Production Follow-up' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Stage completed.');
  });

  it('shows no editor when the server says the caller cannot edit, whatever their role', async () => {
    serve({ [WORKFLOW]: view(5, { canEdit: false, canComplete: false }), [LINKS]: {} });
    renderPanel({ superadmin: true });
    expect(await screen.findByText('5 / 9 stages')).toBeInTheDocument();
    expect(screen.getByText('This workflow is read-only.')).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Production update' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save update' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Complete stage' })).not.toBeInTheDocument();
    // The active stage is still readable, with its status.
    expect(screen.getByRole('heading', { name: 'Pre-Print Quality Check' })).toBeInTheDocument();
    expect(screen.getAllByText('In progress').length).toBeGreaterThan(0);
  });

  it('lets an operator update Head Approval but leaves completing it to a superadmin', async () => {
    serve({ [WORKFLOW]: view(3, { canComplete: false }), [LINKS]: {} });
    renderPanel();
    expect(await screen.findByRole('heading', { name: 'Head Approval' })).toBeInTheDocument();
    expect(screen.getByText('A superadmin completes this stage.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Complete stage' })).not.toBeInTheDocument();
    fireEvent.change(detailsBox(), { target: { value: 'Sent to head for sign-off' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save update' }));
    await waitFor(() => expect(api.put).toHaveBeenCalledWith(`${WORKFLOW}/stages/HEAD_APPROVAL`,
      { data: { details: 'Sent to head for sign-off' }, note: null, expectedVersion: 3 }));
  });

  it('offers no completion without edit rights, even if canComplete is set', async () => {
    serve({ [WORKFLOW]: view(5, { canEdit: false, canComplete: true }), [LINKS]: {} });
    renderPanel();
    expect(await screen.findByText('5 / 9 stages')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Complete stage' })).not.toBeInTheDocument();
  });
});

describe('unsaved text survives failures', () => {
  it('keeps typed text after a failed save, shows the server reason, and retries at the refreshed version', async () => {
    let current = view(5);
    serve({ [WORKFLOW]: () => current, [LINKS]: {} });
    vi.mocked(api.put).mockRejectedValueOnce(httpError(409, 'Workflow changed; refresh and try again'));
    renderPanel();
    await screen.findByRole('heading', { name: 'Pre-Print Quality Check' });
    fireEvent.change(detailsBox(), { target: { value: 'Proof checked, two typos' } });
    // Someone else saved a note on the same stage meanwhile; the untouched note field follows the server.
    current = view(5, { version: 4 }, { PRE_PRINT_QUALITY_CHECK: { data: { details: 'their update' }, note: 'their note' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save update' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Workflow changed; refresh and try again');
    await waitFor(() => expect(getsTo(WORKFLOW)).toBe(2));
    await waitFor(() => expect(noteBox().value).toBe('their note'));
    expect(detailsBox().value).toBe('Proof checked, two typos');

    vi.mocked(api.put).mockResolvedValueOnce({ data: view(5, { version: 5 }, { PRE_PRINT_QUALITY_CHECK: { data: { details: 'Proof checked, two typos' }, note: 'their note' } }) });
    fireEvent.click(screen.getByRole('button', { name: 'Save update' }));
    await waitFor(() => expect(api.put).toHaveBeenLastCalledWith(`${WORKFLOW}/stages/PRE_PRINT_QUALITY_CHECK`,
      { data: { details: 'Proof checked, two typos' }, note: 'their note', expectedVersion: 4 }));
    expect(await screen.findByText('Stage update saved.')).toBeInTheDocument();
  });

  it('keeps typed text after a failed completion too', async () => {
    serve({ [WORKFLOW]: () => view(5), [LINKS]: {} });
    vi.mocked(api.put).mockResolvedValueOnce({ data: view(5, { version: 4 }, { PRE_PRINT_QUALITY_CHECK: { data: { details: 'All good' } } }) });
    vi.mocked(api.post).mockRejectedValueOnce(httpError(500, 'Server unavailable'));
    renderPanel();
    await screen.findByRole('heading', { name: 'Pre-Print Quality Check' });
    fireEvent.change(noteBox(), { target: { value: 'Ask vendor for a proof' } });
    fireEvent.change(detailsBox(), { target: { value: 'All good' } });
    fireEvent.click(screen.getByRole('button', { name: 'Complete stage' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Server unavailable');
    expect(detailsBox().value).toBe('All good');
    expect(noteBox().value).toBe('Ask vendor for a proof');
  });

  it('saves unsaved text before completing, then completes at the version the save returned', async () => {
    serve({ [WORKFLOW]: view(5), [LINKS]: {} });
    vi.mocked(api.put).mockResolvedValueOnce({ data: view(5, { version: 4 }, { PRE_PRINT_QUALITY_CHECK: { data: { details: 'Ready' } } }) });
    vi.mocked(api.post).mockResolvedValueOnce({ data: view(6, { version: 5 }) });
    renderPanel();
    await screen.findByRole('heading', { name: 'Pre-Print Quality Check' });
    fireEvent.change(detailsBox(), { target: { value: 'Ready' } });
    expect(screen.getByText('Completing the stage saves this update first.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Complete stage' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith(`${WORKFLOW}/stages/PRE_PRINT_QUALITY_CHECK/complete`, { expectedVersion: 4 }));
    expect(api.put).toHaveBeenCalledWith(`${WORKFLOW}/stages/PRE_PRINT_QUALITY_CHECK`, { data: { details: 'Ready' }, note: null, expectedVersion: 3 });
    expect(vi.mocked(api.put).mock.invocationCallOrder[0]).toBeLessThan(vi.mocked(api.post).mock.invocationCallOrder[0]);
  });

  it('shows what the server saved after a successful save and has nothing left to save', async () => {
    serve({ [WORKFLOW]: view(5), [LINKS]: {} });
    vi.mocked(api.put).mockResolvedValueOnce({ data: view(5, { version: 4 }, { PRE_PRINT_QUALITY_CHECK: { data: { details: 'Trimmed' }, note: 'Kept' } }) });
    renderPanel();
    await screen.findByRole('heading', { name: 'Pre-Print Quality Check' });
    expect(screen.getByRole('button', { name: 'Save update' })).toBeDisabled();
    fireEvent.change(detailsBox(), { target: { value: '  Trimmed  ' } });
    fireEvent.change(noteBox(), { target: { value: 'Kept' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save update' }));
    expect(await screen.findByText('Stage update saved.')).toBeInTheDocument();
    expect(detailsBox().value).toBe('Trimmed');
    expect(screen.getByRole('button', { name: 'Save update' })).toBeDisabled();
  });

  it('drops the draft when the refresh after a failure shows the stage was completed elsewhere', async () => {
    let current = view(5);
    serve({ [WORKFLOW]: () => current, [LINKS]: {} });
    vi.mocked(api.put).mockRejectedValueOnce(httpError(409, 'Workflow changed; refresh and try again'));
    renderPanel();
    await screen.findByRole('heading', { name: 'Pre-Print Quality Check' });
    fireEvent.change(detailsBox(), { target: { value: 'Late text' } });
    current = view(6, { version: 6 });
    fireEvent.click(screen.getByRole('button', { name: 'Save update' }));
    expect(await screen.findByRole('heading', { name: 'Production Follow-up' })).toBeInTheDocument();
    expect(detailsBox().value).toBe('');
    expect(screen.getByRole('button', { name: /Production Follow-up/ })).toHaveAttribute('aria-current', 'step');
  });

  it('refetches when the parent order changes, keeping text typed for the same stage', async () => {
    serve({ [WORKFLOW]: view(5), [LINKS]: {} });
    const { rerender } = renderPanel({ refreshKey: 'PROCESSING|1|0' });
    await screen.findByRole('heading', { name: 'Pre-Print Quality Check' });
    fireEvent.change(detailsBox(), { target: { value: 'Still typing' } });
    rerender(<ProductOrderWorkflow orderId="CK-42" schoolId={7} staff superadmin={false} refreshKey="APPROVED|2|0" />);
    await waitFor(() => expect(getsTo(WORKFLOW)).toBe(2));
    expect(detailsBox().value).toBe('Still typing');
  });
});

describe('loading', () => {
  it('shows the server reason and a retry instead of loading forever', async () => {
    let fail = true;
    serve({
      [WORKFLOW]: () => { if (fail) throw httpError(409, 'Production workflow is available for placed orders only'); return view(5); },
      [LINKS]: {},
    });
    renderPanel();
    const region = screen.getByRole('region', { name: 'Production workflow' });
    expect(await within(region).findByText('Production workflow is available for placed orders only')).toBeInTheDocument();
    expect(screen.queryByText('Loading production stages…')).not.toBeInTheDocument();
    fail = false;
    fireEvent.click(within(region).getAllByRole('button', { name: 'Retry' })[0]);
    expect(await screen.findByRole('heading', { name: 'Pre-Print Quality Check' })).toBeInTheDocument();
    expect(screen.queryByText('Production workflow is available for placed orders only')).not.toBeInTheDocument();
  });

  it('ignores a slower, older workflow response that lands after a newer one', async () => {
    let resolveSlow: (value: { data: unknown }) => void = () => undefined;
    let calls = 0;
    vi.mocked(api.get).mockImplementation((url: string) => {
      if (url === LINKS) return Promise.resolve({ data: {} });
      calls += 1;
      return calls === 1 ? Promise.resolve({ data: view(5) }) : new Promise((resolve) => { resolveSlow = resolve; });
    });
    vi.mocked(api.post).mockResolvedValueOnce({ data: view(6, { version: 4 }) });
    const { rerender } = renderPanel({ refreshKey: 'a' });
    await screen.findByRole('heading', { name: 'Pre-Print Quality Check' });
    rerender(<ProductOrderWorkflow orderId="CK-42" schoolId={7} staff superadmin={false} refreshKey="b" />);
    await waitFor(() => expect(calls).toBe(2));
    fireEvent.click(screen.getByRole('button', { name: 'Complete stage' }));
    expect(await screen.findByRole('heading', { name: 'Production Follow-up' })).toBeInTheDocument();
    await act(async () => { resolveSlow({ data: view(5, { version: 3 }) }); });
    expect(screen.getByRole('heading', { name: 'Production Follow-up' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Production Follow-up/ })).toHaveAttribute('aria-current', 'step');
  });
});

describe('stage selection', () => {
  it('marks the selected stage button and shows a completed stage read-only with its update, note and completion time', async () => {
    serve({ [WORKFLOW]: view(5, {}, { ORDER_DETAILS: { note: 'Brief confirmed with school' } }), [LINKS]: {} });
    renderPanel();
    await screen.findByRole('heading', { name: 'Pre-Print Quality Check' });
    const first = screen.getByRole('button', { name: /Order Details/ });
    fireEvent.click(first);
    expect(first).toHaveAttribute('aria-current', 'step');
    expect(first.closest('li')).toHaveClass('ck-product-workflow-selected');
    expect(screen.getByRole('button', { name: /Pre-Print Quality Check/ })).not.toHaveAttribute('aria-current');
    expect(screen.getByRole('heading', { name: 'Order Details' })).toBeInTheDocument();
    expect(screen.getByText('Order Details recorded')).toBeInTheDocument();
    expect(screen.getByText('Brief confirmed with school')).toBeInTheDocument();
    expect(screen.getByText(`Completed ${formatSchoolDateTime(COMPLETED_AT)}`)).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Production update' })).not.toBeInTheDocument();
    // Going back to the active stage returns to the editor.
    fireEvent.click(screen.getByRole('button', { name: /Pre-Print Quality Check/ }));
    expect(screen.getByRole('textbox', { name: 'Production update' })).toBeInTheDocument();
  });

  it('shows a school-side viewer only progress, statuses and completion times', async () => {
    serve({ [WORKFLOW]: redacted(view(5)) });
    renderPanel({ staff: false });
    expect(await screen.findByText('5 / 9 stages')).toBeInTheDocument();
    expect(screen.getByText('The production team updates these stages as your order moves forward.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Order Details/ }));
    expect(screen.getByText(`Completed ${formatSchoolDateTime(COMPLETED_AT)}`)).toBeInTheDocument();
    expect(screen.queryByText('Production update')).not.toBeInTheDocument();
    expect(screen.queryByText('Stage note')).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save update' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Complete stage' })).not.toBeInTheDocument();
  });

  it('shows the last stage of a finished workflow instead of an empty panel', async () => {
    serve({ [WORKFLOW]: view(9, { canEdit: false, canComplete: false }), [LINKS]: {} });
    renderPanel();
    expect(await screen.findByRole('heading', { name: 'Delivery & Invoicing' })).toBeInTheDocument();
    expect(screen.getByText('Completed', { selector: '.ck-status' })).toBeInTheDocument();
  });

  it('highlights the selected stage through its button, where aria-current actually is', () => {
    const css = readFileSync(`${cwd()}/src/features/catalog/product-form.css`, 'utf8');
    expect(css).toContain('.ck-product-workflow-stage > button[aria-current="step"] {');
    expect(css).toContain('.ck-product-workflow-selected {');
    expect(css).not.toContain('.ck-product-workflow-stage[aria-current');
  });
});

describe('WhatsApp groups', () => {
  it('are not loaded or shown to school-side viewers', async () => {
    serve({ [WORKFLOW]: redacted(view(5)) });
    renderPanel({ staff: false });
    await screen.findByText('5 / 9 stages');
    expect(screen.queryByRole('region', { name: 'WhatsApp groups' })).not.toBeInTheDocument();
    expect(getsTo(LINKS)).toBe(0);
    expect(contract().getSchoolWhatsAppGroupLinks).not.toHaveBeenCalled();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('gives an operator the school links, with only validated invites clickable and no editing', async () => {
    serve({ [WORKFLOW]: view(5), [LINKS]: { SCHOOL_GROUP: SCHOOL_INVITE, INTERNAL_APPROVAL: 'https://evil.example/join', PRODUCT_VENDOR: VENDOR_INVITE, VENDOR_7: SCHOOL_INVITE } });
    renderPanel();
    const region = await screen.findByRole('region', { name: 'WhatsApp groups' });
    expect(await within(region).findByRole('link', { name: 'Open school group' })).toHaveAttribute('href', SCHOOL_INVITE);
    expect(contract().getSchoolWhatsAppGroupLinks).toHaveBeenCalledExactlyOnceWith({ schoolId: 7 });
    expect(within(region).getByRole('link', { name: 'Open vendor group' })).toHaveAttribute('href', VENDOR_INVITE);
    expect(within(region).getByRole('link', { name: 'Open school group' })).toHaveAttribute('rel', 'noopener noreferrer');
    expect(within(region).getAllByRole('link')).toHaveLength(2);
    expect(within(region).getByText('Internal team')).toBeInTheDocument();
    expect(within(region).getByText('Not set')).toBeInTheDocument();
    expect(within(region).queryByRole('textbox')).not.toBeInTheDocument();
    expect(within(region).queryByRole('button', { name: /Save|Remove/ })).not.toBeInTheDocument();
  });

  it('lets a superadmin save a link without reloading the stages or losing a stage draft', async () => {
    serve({ [WORKFLOW]: view(5), [LINKS]: {} });
    vi.mocked(api.put).mockImplementation(async (url: string) => {
      if (url === `${LINKS}/SCHOOL_GROUP`) return { data: { SCHOOL_GROUP: SCHOOL_INVITE } };
      throw new Error(`Unexpected PUT ${url}`);
    });
    renderPanel({ superadmin: true });
    await screen.findByRole('heading', { name: 'Pre-Print Quality Check' });
    fireEvent.change(detailsBox(), { target: { value: 'Unsaved stage text' } });
    const region = screen.getByRole('region', { name: 'WhatsApp groups' });
    const input = await within(region).findByRole('textbox', { name: 'School group invite URL' });
    fireEvent.change(input, { target: { value: ` ${SCHOOL_INVITE} ` } });
    fireEvent.click(within(region).getByRole('button', { name: 'Save school group link' }));
    expect(await within(region).findByRole('link', { name: 'Open school group' })).toHaveAttribute('href', SCHOOL_INVITE);
    expect(contract().saveSchoolWhatsAppGroupLink).toHaveBeenCalledExactlyOnceWith({ schoolId: 7, target: 'SCHOOL_GROUP' }, { url: SCHOOL_INVITE });
    expect(api.put).toHaveBeenCalledWith(`${LINKS}/SCHOOL_GROUP`, { url: SCHOOL_INVITE });
    expect(within(region).getByRole('status')).toHaveTextContent('School group link saved.');
    expect(getsTo(WORKFLOW)).toBe(1);
    expect(detailsBox().value).toBe('Unsaved stage text');
  });

  it('shows the link exactly as the server stored it, not as it was typed', async () => {
    serve({ [WORKFLOW]: view(5), [LINKS]: {} });
    // operations-service stores each invite in one canonical form, whatever case or empty port was pasted.
    vi.mocked(api.put).mockResolvedValueOnce({ data: { PRODUCT_VENDOR: VENDOR_INVITE } });
    renderPanel({ superadmin: true });
    const region = await screen.findByRole('region', { name: 'WhatsApp groups' });
    const input = await within(region).findByRole('textbox', { name: 'Vendor group invite URL' });
    const pasted = 'Https://Chat.WhatsApp.com:/VendorGroup456';
    fireEvent.change(input, { target: { value: pasted } });
    fireEvent.click(within(region).getByRole('button', { name: 'Save vendor group link' }));
    expect(await within(region).findByRole('link', { name: 'Open vendor group' })).toHaveAttribute('href', VENDOR_INVITE);
    expect(contract().saveSchoolWhatsAppGroupLink).toHaveBeenCalledExactlyOnceWith({ schoolId: 7, target: 'PRODUCT_VENDOR' }, { url: pasted });
    expect(input).toHaveValue(VENDOR_INVITE);
    expect(within(region).getByRole('status')).toHaveTextContent('Vendor group link saved.');
    // The field now holds the stored link, so there is nothing left to save.
    expect(within(region).getByRole('button', { name: 'Save vendor group link' })).toBeDisabled();
  });

  it('still checks what the generated client returns, on load and after a save', async () => {
    let links: unknown = { SCHOOL_GROUP: 42 };
    serve({ [WORKFLOW]: view(5), [LINKS]: () => links });
    vi.mocked(api.put).mockResolvedValueOnce({ data: ['not', 'links'] });
    renderPanel({ superadmin: true });
    const region = await screen.findByRole('region', { name: 'WhatsApp groups' });
    expect(await within(region).findByRole('alert')).toHaveTextContent('WhatsApp group links could not be read.');
    expect(within(region).queryByRole('textbox')).not.toBeInTheDocument();
    links = {};
    fireEvent.click(within(region).getByRole('button', { name: 'Retry' }));
    fireEvent.change(await within(region).findByRole('textbox', { name: 'School group invite URL' }), { target: { value: SCHOOL_INVITE } });
    fireEvent.click(within(region).getByRole('button', { name: 'Save school group link' }));
    expect(await within(region).findByRole('alert')).toHaveTextContent('WhatsApp group links could not be read.');
    expect(within(region).queryByRole('link')).not.toBeInTheDocument();
    expect(within(region).queryByRole('status')).not.toBeInTheDocument();
  });

  it('lets a superadmin remove a link by clearing it', async () => {
    serve({ [WORKFLOW]: view(5), [LINKS]: { SCHOOL_GROUP: SCHOOL_INVITE } });
    vi.mocked(api.put).mockResolvedValueOnce({ data: {} });
    renderPanel({ superadmin: true });
    const region = await screen.findByRole('region', { name: 'WhatsApp groups' });
    fireEvent.change(await within(region).findByRole('textbox', { name: 'School group invite URL' }), { target: { value: '' } });
    fireEvent.click(within(region).getByRole('button', { name: 'Remove school group link' }));
    await waitFor(() => expect(api.put).toHaveBeenCalledWith(`${LINKS}/SCHOOL_GROUP`, { url: '' }));
    expect(contract().saveSchoolWhatsAppGroupLink).toHaveBeenCalledExactlyOnceWith({ schoolId: 7, target: 'SCHOOL_GROUP' }, { url: '' });
    expect(await within(region).findByText('School group link removed.')).toBeInTheDocument();
    expect(within(region).queryByRole('link')).not.toBeInTheDocument();
  });

  it('refuses links the server would reject before sending them', async () => {
    serve({ [WORKFLOW]: view(5), [LINKS]: {} });
    renderPanel({ superadmin: true });
    const region = await screen.findByRole('region', { name: 'WhatsApp groups' });
    const input = await within(region).findByRole('textbox', { name: 'Vendor group invite URL' });
    const save = () => within(region).getByRole('button', { name: 'Save vendor group link' });
    // Every entry here is refused by operations-service (java.net.URI). The second group is what the browser's URL
    // parser silently normalises into a valid-looking invite, so a `new URL()` check would wrongly enable Save.
    for (const bad of ['http://chat.whatsapp.com/VendorGroup456', 'https://chat.whatsapp.com/short', 'https://chat.whatsapp.com/VendorGroup456?x=1',
      'https://user@chat.whatsapp.com/VendorGroup456', 'https://chat.whatsapp.com:8443/VendorGroup456', 'https://evil.example/VendorGroup456',
      'https://chat.whatsapp.com:443/VendorGroup456', 'https://@chat.whatsapp.com/VendorGroup456', 'https://:@chat.whatsapp.com/VendorGroup456',
      'https://chat.whatsapp.com/VendorGroup456?', 'https://chat.whatsapp.com/VendorGroup456#', 'https://chat.whatsapp.com/./VendorGroup456',
      'https:chat.whatsapp.com/VendorGroup456', 'https:///chat.whatsapp.com/VendorGroup456', 'https://chat.whatsapp.com\\VendorGroup456',
      'https://chat%2Ewhatsapp.com/VendorGroup456']) {
      fireEvent.change(input, { target: { value: bad } });
      expect(input, bad).toHaveAttribute('aria-invalid', 'true');
      expect(save(), bad).toBeDisabled();
    }
    expect(within(region).getByText(/Enter a chat.whatsapp.com group invite link/)).toBeInTheDocument();
    // The server compares scheme and host ignoring case, so a phone's auto-capitalised paste is still accepted.
    for (const good of [VENDOR_INVITE, 'Https://Chat.WhatsApp.com/VendorGroup456']) {
      fireEvent.change(input, { target: { value: good } });
      expect(input, good).not.toHaveAttribute('aria-invalid');
      expect(save(), good).toBeEnabled();
    }
    expect(api.put).not.toHaveBeenCalled();
  });

  it('names each link row and its button after the group it changes', async () => {
    serve({ [WORKFLOW]: view(5), [LINKS]: { SCHOOL_GROUP: SCHOOL_INVITE } });
    renderPanel({ superadmin: true });
    const region = await screen.findByRole('region', { name: 'WhatsApp groups' });
    expect(await within(region).findAllByRole('group')).toHaveLength(3);
    expect(within(region).getByRole('group', { name: 'Internal team' })).toBeInTheDocument();
    const vendor = within(region).getByRole('group', { name: 'Vendor group' });
    expect(within(vendor).getByRole('textbox', { name: 'Vendor group invite URL' })).toBeInTheDocument();
    expect(within(vendor).getByRole('button', { name: 'Save vendor group link' })).toBeInTheDocument();
    const school = within(region).getByRole('group', { name: 'School group' });
    fireEvent.change(within(school).getByRole('textbox', { name: 'School group invite URL' }), { target: { value: '' } });
    expect(within(school).getByRole('button', { name: 'Remove school group link' })).toHaveTextContent('Remove');
    const names = within(region).getAllByRole('button').map((button) => button.getAttribute('aria-label'));
    expect(names).toEqual(['Remove school group link', 'Save internal team link', 'Save vendor group link']);
  });

  it('shows a link failure inside the WhatsApp section without blocking the stages', async () => {
    serve({ [WORKFLOW]: view(5), [LINKS]: () => { throw httpError(403, 'You are not assigned to this school'); } });
    renderPanel();
    const region = await screen.findByRole('region', { name: 'WhatsApp groups' });
    expect(await within(region).findByRole('alert')).toHaveTextContent('You are not assigned to this school');
    expect(await screen.findByRole('heading', { name: 'Pre-Print Quality Check' })).toBeInTheDocument();
    expect(detailsBox()).toBeEnabled();
  });

  it('shows the server reason when a superadmin save fails, inside the WhatsApp section', async () => {
    serve({ [WORKFLOW]: view(5), [LINKS]: {} });
    vi.mocked(api.put).mockRejectedValueOnce(httpError(400, 'Enter a valid WhatsApp group invite URL'));
    renderPanel({ superadmin: true });
    const region = await screen.findByRole('region', { name: 'WhatsApp groups' });
    fireEvent.change(await within(region).findByRole('textbox', { name: 'Internal team invite URL' }), { target: { value: SCHOOL_INVITE } });
    fireEvent.click(within(region).getByRole('button', { name: 'Save internal team link' }));
    expect(await within(region).findByRole('alert')).toHaveTextContent('Enter a valid WhatsApp group invite URL');
    expect(screen.getByRole('region', { name: 'Production workflow' }).querySelector(':scope > .ck-alert')).toBeNull();
  });
});
