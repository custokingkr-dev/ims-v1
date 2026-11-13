import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { OrderDetailEntry } from './OrderDetailEntry';
import { OrderWorkflowPage } from './OrderWorkflowPage';
import { clearWorkflowCaches, workflowApi } from './api';
import { callsApi } from '../calls/api';
import { makeSummary, makeView, normaliseTemplate, type ViewOptions } from './testFixtures';
import type { DesignFile, OrderWorkflowView, VendorCategoryView, VendorView, WorkflowTemplate } from './types';

// The real template files, as operations-service loads them: the screens must render whatever they declare.
const { readFileSync } = await vi.importActual<{ readFileSync: (path: string, encoding: string) => string }>('node:fs');
const { cwd } = await vi.importActual<{ cwd: () => string }>('node:process');
const TEMPLATE_DIR = `${cwd()}/../services/operations-service/src/main/resources/workflow-templates`;
const load = (id: string) => normaliseTemplate(JSON.parse(readFileSync(`${TEMPLATE_DIR}/${id}.json`, 'utf8')));
const NB = load('NOTEBOOK_ORDER_TRACKER');
const FE = load('FLIER_ORDER_WORKFLOW');
const TEMPLATES: Record<string, WorkflowTemplate> = { NOTEBOOK_ORDER_TRACKER: NB, FLIER_ORDER_WORKFLOW: FE };

const auth = vi.hoisted(() => ({ user: { role: 'OPERATIONS', userId: 42, fullName: 'Asha Operator' } as { role: string; userId: number; fullName: string } | null }));
vi.mock('../../../contexts/AuthContext', () => ({ useAuth: () => ({ user: auth.user }) }));
// The School & vendor tab mounts the real call log; only its transport is replaced.
vi.mock('../calls/api', () => ({ callsApi: { list: vi.fn(), log: vi.fn() } }));
vi.mock('../../catalog/ProductOrderDetail', () => ({ ProductOrderDetail: ({ orderId }: { orderId: string }) => <div>ordinary order detail {orderId}</div> }));
vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api')>();
  const fn = () => vi.fn();
  return {
    ...actual,
    workflowApi: {
      list: fn(), templates: fn(), template: fn(), view: fn(), saveStepData: fn(), complete: fn(), reopen: fn(), signOff: fn(), approveDesign: fn(),
      requestChanges: fn(), assignOrder: fn(), assignStep: fn(), claim: fn(), setVendorSelections: fn(), setDesigner: fn(), setContacts: fn(), addNote: fn(),
      calls: fn(), activity: fn(), setSchoolGroup: fn(), designers: fn(), vendors: fn(), vendorCategories: fn(), summary: fn(), ensure: fn(),
      designFiles: fn(), uploadDesignFile: fn(), deleteDesignFile: fn(), designFileBlob: fn(), categories: fn(), operators: fn(), schools: fn(),
    },
  };
});

const MIB = 1024 * 1024;
const api = vi.mocked(workflowApi);
const category = (code: string, name: string, sortOrder: number): VendorCategoryView => ({ code, name, sortOrder, active: true, vendorCount: 1, updatedAt: null, updatedBy: null });
const vendor = (id: number, name: string, categories: string[], over: Partial<VendorView> = {}): VendorView => ({
  id, name, categories, contactPerson: 'Mohan', phone: '+91 90000 0000' + id, altPhone: null, email: null, city: 'Delhi',
  whatsappGroupUrl: `https://chat.whatsapp.com/Vendor${id}Group12`, notes: null, sample: false, active: true, updatedAt: '2026-11-01T00:00:00Z', updatedBy: 1, ...over,
});
const CATEGORIES = [
  category('BOARD_SUPPLIERS', 'Board Suppliers', 1), category('LAMINATION_VENDORS', 'Lamination Vendors', 2), category('BINDERS', 'Binders', 3),
  category('OFFSET_PRINTERS', 'Offset Printers', 4), category('DIGITAL_PRINTERS', 'Digital Printers', 5), category('FLEX_PRINTERS', 'Flex Printers', 6),
  category('PHOTO_PAPER_PRINTERS', 'Photo Paper Printers', 7),
];
const VENDORS = [
  vendor(1, 'Shree Board Traders', ['BOARD_SUPPLIERS']), vendor(2, 'GlossPro Laminations', ['LAMINATION_VENDORS']),
  vendor(3, 'Perfect Bindery', ['BINDERS']), vendor(4, 'Metro Offset Printers', ['OFFSET_PRINTERS', 'DIGITAL_PRINTERS'], { whatsappGroupUrl: null }),
];
const file = (id: number, name: string, sizeBytes = MIB, over: Partial<DesignFile> = {}): DesignFile => ({
  id, assetKind: 'DESIGN_DRAFT', contentType: name.endsWith('.pdf') ? 'application/pdf' : 'image/png', sizeBytes, originalFilename: name,
  // School-core lists the uploader as a user id, not a name.
  uploadedBy: 42, uploadedAt: `2026-11-1${id}T05:00:00Z`, ...over,
});

const nb = (options: ViewOptions = {}) => makeView(NB, options);
const fe = (options: ViewOptions = {}) => makeView(FE, { itemCode: 'FE', itemNoun: 'flier', categoryCode: 'FLIERS', orderNumber: 'FE-DPS_GUR-26-11-13-0007', ...options });

function renderPage(view: OrderWorkflowView) {
  const onBack = vi.fn();
  render(<OrderWorkflowPage orderId={view.orderId} initial={view} onBack={onBack} />);
  return { onBack };
}
const heading = (name: RegExp | string) => screen.findByRole('heading', { level: 3, name });
const stepper = () => screen.getByRole('navigation', { name: 'Workflow stages' });

beforeEach(() => {
  vi.clearAllMocks();
  clearWorkflowCaches();
  auth.user = { role: 'OPERATIONS', userId: 42, fullName: 'Asha Operator' };
  api.template.mockImplementation(async (id) => TEMPLATES[id]);
  api.summary.mockResolvedValue(makeSummary());
  api.designFiles.mockResolvedValue([]);
  api.calls.mockResolvedValue([]);
  vi.mocked(callsApi.list).mockResolvedValue([]);
  api.activity.mockResolvedValue({ entries: [], nextBefore: null });
  api.vendors.mockResolvedValue(VENDORS);
  api.vendorCategories.mockResolvedValue(CATEGORIES);
  api.designers.mockResolvedValue([]);
  api.operators.mockResolvedValue([{ userId: 51, fullName: 'Ravi Operator' }]);
  api.designFileBlob.mockResolvedValue(new Blob(['png'], { type: 'image/png' }));
  URL.createObjectURL = vi.fn(() => 'blob:design-preview');
  URL.revokeObjectURL = vi.fn();
});
afterEach(cleanup);

describe('the notebook workflow page', () => {
  it('shows the order number, school, quantities, progress and every stage of the template', async () => {
    renderPage(nb({ active: 5 }));
    expect(await heading('Step 5: Production Planning')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'NB-DPS_GUR-26-11-13-0042' })).toBeInTheDocument();
    expect(screen.getByText('Ref CK-1015')).toBeInTheDocument();
    expect(await screen.findByText('Gurugram')).toBeInTheDocument();
    expect(screen.getByText('King size').nextSibling).toHaveTextContent('8,600');
    expect(screen.getByText('Total notebooks').nextSibling).toHaveTextContent('15,600');
    expect(screen.getByText('4 of 12 steps')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: '33% complete' })).toBeInTheDocument();
    expect(within(stepper()).getAllByRole('listitem')).toHaveLength(12);
    const list = screen.getByRole('navigation', { name: 'Order workflow stages' });
    expect(within(list).getByRole('button', { name: /Delivery & Invoicing/ })).toHaveTextContent('Pending');
    expect(within(list).getByRole('button', { name: /Order Details/ })).toHaveTextContent(/Completed · /);
    // The title card table is the server's calculation, not the prototype's zeros.
    const table = await screen.findByRole('table');
    expect(within(table).getByRole('row', { name: /Long 72\*112 4,000 513/ })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Production planning checklist' })).toHaveTextContent('0/3 complete');
  });

  it('marks the current step for assistive technology and opens any stage from the stepper, locked ones included', async () => {
    renderPage(nb({ active: 7 }));
    await heading('Step 7: Pre-Print Quality Check');
    const items = within(stepper()).getAllByRole('listitem');
    expect(items[6]).toHaveAttribute('aria-current', 'step');
    expect(items.filter((item) => item.getAttribute('aria-current'))).toHaveLength(1);
    fireEvent.click(within(stepper()).getByRole('button', { name: 'Step 8: Printing Press, locked' }));
    expect(await heading('Step 8: Printing Press')).toBeInTheDocument();
    expect(within(stepper()).getByRole('button', { name: 'Step 8: Printing Press, locked, viewing' })).toBeInTheDocument();
  });

  it('keeps a locked stage read-only: bulk printing cannot be ticked before the pre-print check completes', async () => {
    renderPage(nb({ active: 7 }));
    await heading('Step 7: Pre-Print Quality Check');
    fireEvent.click(within(stepper()).getByRole('button', { name: /Step 8: Printing Press/ }));
    await heading('Step 8: Printing Press');
    expect(screen.getByText('Opens for editing when Pre-Print Quality Check is completed.')).toBeInTheDocument();
    const item = screen.getByRole('checkbox', { name: 'VERIFIED THE IMPRESSIONS FOR EACH DESIGN' });
    expect(item).toBeDisabled();
    expect(screen.getByRole('button', { name: /Complete stage & continue/ })).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Save expected completion' })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Expected completion')).toHaveAttribute('readonly');
  });

  it('disables Complete and says what is still needed, from the server', async () => {
    renderPage(nb({ active: 7, steps: { 7: { unmet: [{ code: 'FIELDS_PRESENT', message: 'Save the print sample evidence link' }, { code: 'SIGNED_OFF', message: 'Record the reviewer sign-off' }] } } }));
    await heading('Step 7: Pre-Print Quality Check');
    const complete = screen.getByRole('button', { name: /Complete stage & continue/ });
    expect(complete).toBeDisabled();
    expect(complete).toHaveAccessibleDescription('Still needed: Save the print sample evidence link · Record the reviewer sign-off');
    const left = screen.getByText("What's left to complete").parentElement as HTMLElement;
    expect(within(left).getAllByRole('listitem').map((item) => item.textContent)).toEqual(['Save the print sample evidence link', 'Record the reviewer sign-off']);
    expect(screen.getByText('Bulk printing blocked until all checks and reviewer sign-off are complete.')).toBeInTheDocument();
    expect(screen.getByText('A superadmin records this sign-off.')).toBeInTheDocument();
  });

  it('completes the stage, moves on to the next one and says so', async () => {
    api.complete.mockResolvedValue(nb({ active: 4 }));
    renderPage(nb({ active: 3 }));
    await heading('Step 3: CDR Conversion');
    fireEvent.click(screen.getByRole('button', { name: /Complete stage & continue/ }));
    await waitFor(() => expect(api.complete).toHaveBeenCalledWith('CK-1015', 3, 3));
    expect(await heading('Step 4: Head Approval')).toBeInTheDocument();
    expect(await screen.findByText('CDR Conversion completed')).toBeInTheDocument();
  });

  it('saves typed fields first when completing, then completes on the saved version', async () => {
    const saved = nb({ active: 3, steps: { 3: { version: 4, data: { cdrNotes: 'Fonts outlined' } } } });
    api.saveStepData.mockResolvedValue(saved);
    api.complete.mockResolvedValue(nb({ active: 4 }));
    renderPage(nb({ active: 3 }));
    await heading('Step 3: CDR Conversion');
    fireEvent.change(screen.getByLabelText('Conversion comments'), { target: { value: 'Fonts outlined' } });
    expect(screen.getByText('Your unsaved changes on this stage are saved when you complete it.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Complete stage & continue/ }));
    await waitFor(() => expect(api.complete).toHaveBeenCalledWith('CK-1015', 3, 4));
    expect(api.saveStepData).toHaveBeenCalledWith('CK-1015', 3, { cdrNotes: 'Fonts outlined' }, 3);
  });

  it('lists every unmet gate when the server refuses Complete', async () => {
    api.complete.mockRejectedValue({ response: { status: 409, data: { code: 'GATE_UNMET', message: 'This stage is not ready.', unmet: [{ code: 'ALL_CHECKED', message: 'Tick every conversion check' }] } } });
    api.view.mockResolvedValue(nb({ active: 3, steps: { 3: { unmet: [{ code: 'ALL_CHECKED', message: 'Tick every conversion check' }] } } }));
    renderPage(nb({ active: 3 }));
    await heading('Step 3: CDR Conversion');
    fireEvent.click(screen.getByRole('button', { name: /Complete stage & continue/ }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('This stage is not ready.');
    expect(alert).toHaveTextContent('Tick every conversion check');
    expect(await screen.findByRole('heading', { level: 3, name: 'Step 3: CDR Conversion' })).toBeInTheDocument();
  });

  it('saves only the fields its card changed, and refuses a link that is not https', async () => {
    api.saveStepData.mockResolvedValue(nb({ active: 3, steps: { 3: { data: { cdrLink: 'https://drive.example/cdr' } } } }));
    renderPage(nb({ active: 3, steps: { 3: { data: { cdrNotes: 'Kept as saved' } } } }));
    await heading('Step 3: CDR Conversion');
    const save = screen.getByRole('button', { name: 'Save CDR details' });
    expect(save).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Controlled storage link'), { target: { value: 'http://drive.example/cdr' } });
    expect(screen.getByText('Paste a full https:// link.')).toBeInTheDocument();
    expect(save).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Controlled storage link'), { target: { value: 'https://drive.example/cdr' } });
    fireEvent.click(save);
    await waitFor(() => expect(api.saveStepData).toHaveBeenCalledWith('CK-1015', 3, { cdrLink: 'https://drive.example/cdr' }, 3));
    expect(await screen.findByText('Details saved')).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: /Open.*Controlled storage link/ })).toHaveAttribute('href', 'https://drive.example/cdr');
  });

  it('saves a checklist tick at once, as a partial save of that list only, with a toast', async () => {
    api.saveStepData.mockResolvedValue(nb({ active: 8, steps: { 8: { data: { pressChecks: [0, 2] }, version: 4 } } }));
    renderPage(nb({ active: 8, steps: { 8: { data: { pressChecks: [2] } } } }));
    await heading('Step 8: Printing Press');
    const list = screen.getByRole('group', { name: 'Printing & Lamination Checklist' });
    expect(within(list).getByText('1/8 complete')).toBeInTheDocument();
    expect(within(list).getByRole('progressbar')).toHaveAttribute('aria-valuenow', '13');
    fireEvent.click(within(list).getByRole('checkbox', { name: 'VERIFIED THE IMPRESSIONS FOR EACH DESIGN' }));
    await waitFor(() => expect(api.saveStepData).toHaveBeenCalledWith('CK-1015', 8, { pressChecks: [0, 2] }, 3));
    expect(await screen.findByText('Printing & Lamination Checklist updated')).toBeInTheDocument();
    expect(within(screen.getByRole('group', { name: 'Printing & Lamination Checklist' })).getByText('2/8 complete')).toBeInTheDocument();
  });

  it('leaves the bulk-printing release to the reviewer sign-off and records it after saving typed evidence', async () => {
    auth.user = { role: 'SUPERADMIN', userId: 1, fullName: 'Super Admin' };
    const afterSave = nb({ active: 7, superadmin: true, steps: { 7: { version: 9, data: { checks: [0, 1], qcComments: 'Spelling fixed' } } } });
    api.saveStepData.mockResolvedValue(afterSave);
    api.signOff.mockResolvedValue(nb({ active: 7, superadmin: true, steps: { 7: { signOff: { by: 1, byName: 'Super Admin', at: '2026-11-13T06:00:00Z' } } } }));
    renderPage(nb({ active: 7, superadmin: true, steps: { 7: { data: { checks: [0, 1] } } } }));
    await heading('Step 7: Pre-Print Quality Check');
    const release = screen.getByRole('checkbox', { name: 'Authorised quality reviewer releases bulk printing' });
    expect(release).toBeDisabled();
    expect(release).not.toBeChecked();
    fireEvent.change(screen.getByLabelText('QC reviewer comments'), { target: { value: 'Spelling fixed' } });
    fireEvent.click(screen.getByRole('button', { name: 'Record reviewer sign-off' }));
    await waitFor(() => expect(api.signOff).toHaveBeenCalledWith('CK-1015', 7, 9));
    expect(api.saveStepData).toHaveBeenCalledWith('CK-1015', 7, { qcComments: 'Spelling fixed' }, 3);
    expect(await screen.findByText(/Signed off by Super Admin · 13 Nov, 11:30 am/)).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Authorised quality reviewer releases bulk printing' })).toBeChecked();
    expect(screen.getByText('Ticked by reviewer sign-off')).toBeInTheDocument();
  });

  it('keeps Approve disabled until a CDR link is saved, and requires a comment', async () => {
    auth.user = { role: 'SUPERADMIN', userId: 1, fullName: 'Super Admin' };
    const { unmount } = render(<OrderWorkflowPage orderId="CK-1015" initial={nb({ active: 4, superadmin: true })} onBack={vi.fn()} />);
    await heading('Step 4: Head Approval');
    expect(screen.getByText('No CDR link saved yet. Go back to CDR Conversion and save the link first.')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Approval comments'), { target: { value: 'Looks right' } });
    expect(screen.getByRole('button', { name: 'Approve design' })).toBeDisabled();
    unmount();

    api.approveDesign.mockResolvedValue(nb({ active: 5, superadmin: true }));
    renderPage(nb({ active: 4, superadmin: true, steps: { 3: { data: { cdrLink: 'https://drive.example/cdr-v2' }, dataSavedAt: '2026-11-13T04:30:00Z', dataSavedByName: 'Asha Operator' } } }));
    await heading('Step 4: Head Approval');
    expect(screen.getByRole('link', { name: /https:\/\/drive\.example\/cdr-v2/ })).toBeInTheDocument();
    expect(screen.getByText('Saved 13 Nov, 10:00 am by Asha Operator')).toBeInTheDocument();
    const approve = screen.getByRole('button', { name: 'Approve design' });
    expect(approve).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Approval comments'), { target: { value: 'Looks right' } });
    fireEvent.click(approve);
    await waitFor(() => expect(api.approveDesign).toHaveBeenCalledWith('CK-1015', 'Looks right', 3));
  });

  it('confirms that requesting changes reopens Designing Activity', async () => {
    auth.user = { role: 'SUPERADMIN', userId: 1, fullName: 'Super Admin' };
    api.requestChanges.mockResolvedValue(nb({ active: 2, superadmin: true }));
    renderPage(nb({ active: 4, superadmin: true }));
    await heading('Step 4: Head Approval');
    fireEvent.change(screen.getByLabelText('Approval comments'), { target: { value: 'Logo too small' } });
    fireEvent.click(screen.getByRole('button', { name: 'Request changes' }));
    const dialog = screen.getByRole('dialog', { name: 'Request changes?' });
    expect(dialog).toHaveAccessibleDescription('This reopens Designing Activity.');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Request changes' }));
    await waitFor(() => expect(api.requestChanges).toHaveBeenCalledWith('CK-1015', 'Logo too small', 3));
  });

  it('tells a superadmin why there is no decision form on a completed or unreached Head Approval', async () => {
    auth.user = { role: 'SUPERADMIN', userId: 1, fullName: 'Super Admin' };
    const closed = { ...nb({ superadmin: true }).can, canApproveDesign: false };
    const { unmount } = render(<OrderWorkflowPage orderId="CK-1015" initial={nb({ active: 5, superadmin: true, view: { can: closed } })} onBack={vi.fn()} />);
    await heading('Step 5: Production Planning');
    fireEvent.click(within(stepper()).getByRole('button', { name: /Step 4: Head Approval/ }));
    await heading('Step 4: Head Approval');
    expect(screen.getByText('Head Approval is completed. Reopen it to record a new decision.')).toBeInTheDocument();
    expect(screen.queryByText('Only a superadmin approves the design.')).not.toBeInTheDocument();
    unmount();

    renderPage(nb({ active: 2, superadmin: true, view: { can: closed } }));
    await heading('Step 2: Designing Activity');
    fireEvent.click(within(stepper()).getByRole('button', { name: /Step 4: Head Approval/ }));
    await heading('Step 4: Head Approval');
    expect(screen.getByText('The design can be approved once Head Approval is in progress.')).toBeInTheDocument();
  });

  it('caps the reason for sending the order back at 100 words, as the reopen it makes does', async () => {
    auth.user = { role: 'SUPERADMIN', userId: 1, fullName: 'Super Admin' };
    renderPage(nb({ active: 7, superadmin: true, steps: { 7: { data: { schoolVerification: 'CHANGES_REQUIRED' } } } }));
    await heading('Step 7: Pre-Print Quality Check');
    fireEvent.click(screen.getByRole('button', { name: /Send back to Designing Activity/ }));
    const dialog = screen.getByRole('dialog', { name: 'Send back to Designing Activity?' });
    const reason = within(dialog).getByLabelText('Reason');
    fireEvent.change(reason, { target: { value: 'Logo too small' } });
    expect(reason).toHaveAccessibleDescription('3/100 words');
    fireEvent.change(reason, { target: { value: 'word '.repeat(101) } });
    expect(within(dialog).getByText('Use at most 100 words.')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Send back' })).toBeDisabled();
    expect(reason).toHaveAttribute('maxLength', '1000');
  });

  it('tells an operator that only a superadmin approves, and shows the decision once made', async () => {
    renderPage(nb({ active: 5, view: { design: { state: 'APPROVED', comment: 'Approved as sent', decidedAt: '2026-11-13T05:00:00Z', decidedBy: 1, decidedByName: 'Super Admin', approvedCdrLink: 'https://drive.example/v1' } } }));
    await heading('Step 5: Production Planning');
    fireEvent.click(within(stepper()).getByRole('button', { name: /Step 4: Head Approval/ }));
    await heading('Step 4: Head Approval');
    expect(screen.getByText('Only a superadmin approves the design.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve design' })).not.toBeInTheDocument();
    expect(screen.getByText(/Approved by Super Admin · 13 Nov, 10:30 am/)).toBeInTheDocument();
    expect(screen.getByText('The CDR link changed after this approval, so the design needs approving again.')).toBeInTheDocument();
  });
});

describe('order details and the introduction message', () => {
  it('fills {Name} as you type, copies the message and records it when ticked', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    api.saveStepData.mockResolvedValue(nb({ active: 1, steps: { 1: { data: { introChecks: [0] } } } }));
    renderPage(nb({ active: 1 }));
    await heading('Step 1: Order Details');
    const message = screen.getByLabelText('Message template') as HTMLTextAreaElement;
    expect(message.value).toBe('My name is Asha Operator, and I will be your point of contact for all notebook-related orders. Please feel free to reach out for any queries, updates, or follow-ups.');
    fireEvent.change(screen.getByLabelText('Your name'), { target: { value: 'Ravi' } });
    expect(message.value).toMatch(/^My name is Ravi, and I will be/);
    fireEvent.click(screen.getByRole('button', { name: /Copy message/ }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(message.value));
    expect(await screen.findByText('Message copied')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Open Delhi Public School Group Chat/ })).toHaveAttribute('href', 'https://chat.whatsapp.com/SchoolGroup1234');
    fireEvent.click(screen.getByRole('checkbox', { name: 'Introduction message sent in the school group' }));
    // D28: the tick records the final text, in the field the template declares for it.
    await waitFor(() => expect(api.saveStepData).toHaveBeenCalledWith('CK-1015', 1, { introChecks: [0], introText: message.value }, 3));
    expect(message).toHaveAttribute('maxLength', '1000');
  });

  it('records the message in the field the introduction card names, whatever its key', async () => {
    // The card's `fields` names the one TEXT field the tick saves into; a stage field that only mentions "intro" is not it.
    const custom: WorkflowTemplate = {
      ...NB,
      steps: NB.steps.map((step) => (step.order !== 1 ? step : {
        ...step,
        fields: {
          ...Object.fromEntries(Object.entries(step.fields).filter(([key]) => key !== 'introText')),
          introNotes: { kind: 'TEXT', label: 'Intro notes', placeholder: null, min: null, max: 4000, choices: null, legacy: false },
          messageAsSent: { kind: 'TEXT', label: 'Message as sent', placeholder: null, min: null, max: 500, choices: null, legacy: false },
        },
        blocks: step.blocks.map((block) => (block.type === 'INTRO_MESSAGE' ? { ...block, fields: ['messageAsSent'] } : block)),
      })),
    };
    api.template.mockImplementation(async () => custom);
    api.saveStepData.mockResolvedValue(makeView(custom, { active: 1, steps: { 1: { data: { introChecks: [0] } } } }));
    renderPage(makeView(custom, { active: 1 }));
    await heading('Step 1: Order Details');
    const message = screen.getByLabelText('Message template') as HTMLTextAreaElement;
    expect(message).toHaveAttribute('maxLength', '500');
    fireEvent.click(screen.getByRole('checkbox', { name: 'Introduction message sent in the school group' }));
    await waitFor(() => expect(api.saveStepData).toHaveBeenCalledWith('CK-1015', 1, { introChecks: [0], messageAsSent: message.value }, 3));
  });

  it('keeps a hand-edited message until reset, and falls back to selecting it when the clipboard is blocked', async () => {
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: vi.fn().mockRejectedValue(new Error('blocked')) }, configurable: true });
    renderPage(nb({ active: 1 }));
    await heading('Step 1: Order Details');
    const message = screen.getByLabelText('Message template') as HTMLTextAreaElement;
    fireEvent.change(message, { target: { value: 'Hello from {Name}' } });
    fireEvent.change(screen.getByLabelText('Your name'), { target: { value: 'Ravi' } });
    expect(message.value).toBe('Hello from {Name}');
    fireEvent.click(screen.getByRole('button', { name: /Copy message/ }));
    expect(await screen.findByText('Message selected. Press Ctrl+C to copy it.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Reset to template' }));
    expect(message.value).toMatch(/^My name is Ravi,/);
  });

  it("gives a stage's SOP name with its description where the SOP names it differently (D49)", async () => {
    renderPage(nb({ active: 8 }));
    await heading('Step 8: Printing Press');
    const described = screen.getAllByTitle('SOP name: Printing Press Operations');
    expect(described).toHaveLength(2);
    expect(described[0]).toHaveTextContent('Handover approved artwork to the press and track quantities. SOP name: Printing Press Operations.');
    fireEvent.click(within(stepper()).getByRole('button', { name: /Step 1: Order Details/ }));
    await heading('Step 1: Order Details');
    expect(screen.getAllByTitle('SOP name: Order Details & Onboarding')).toHaveLength(2);
    fireEvent.click(within(stepper()).getByRole('button', { name: /Step 5: Production Planning/ }));
    await heading('Step 5: Production Planning');
    expect(screen.queryAllByTitle(/^SOP name:/)).toHaveLength(0);
  });

  it('shows the recorded introduction message with its sender, not the unfilled template', async () => {
    api.activity.mockResolvedValue({ entries: [
      { id: 'action:4', kind: 'ACTION', action: 'INTRO_SENT', stepOrder: 1, actorUserId: 42, actorName: 'Asha Operator', at: '2026-11-13T05:00:00Z',
        text: 'My name is {Name}, and I will be your point of contact for all notebook-related orders.', details: {}, count: 1 },
    ], nextBefore: null });
    renderPage(nb({ active: 2 }));
    await heading('Step 2: Designing Activity');
    fireEvent.click(screen.getByRole('tab', { name: 'Activity history' }));
    expect(await screen.findByText('Sent the introduction message')).toBeInTheDocument();
    expect(screen.getByText('My name is Asha Operator, and I will be your point of contact for all notebook-related orders.')).toBeInTheDocument();
    expect(screen.queryByText(/\{Name\}/)).not.toBeInTheDocument();
  });

  it('shows three tabs on Order Details and moves between tabs with the arrow keys', async () => {
    renderPage(nb({ active: 1 }));
    await heading('Step 1: Order Details');
    const tabs = within(screen.getByRole('tablist', { name: 'Stage sections' })).getAllByRole('tab');
    expect(tabs.map((tab) => tab.textContent)).toEqual(['Work area', 'Checklist', 'Activity history']);
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
    fireEvent.keyDown(tabs[0], { key: 'ArrowRight' });
    expect(screen.getByRole('tab', { name: 'Checklist' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tabpanel')).toHaveAccessibleName('Checklist');
    fireEvent.keyDown(screen.getByRole('tab', { name: 'Checklist' }), { key: 'End' });
    expect(screen.getByRole('tab', { name: 'Activity history' })).toHaveAttribute('aria-selected', 'true');
  });
});

describe('design files', () => {
  const choose = (files: File[]) => fireEvent.change(screen.getByLabelText('Choose design files'), { target: { files } });
  const sized = (name: string, size: number) => { const value = new File(['x'], name); Object.defineProperty(value, 'size', { value: size }); return value; };

  it('refuses a fifth file before uploading anything', async () => {
    api.designFiles.mockResolvedValue([file(1, 'v1.pdf'), file(2, 'v2.png'), file(3, 'v3.png'), file(4, 'v4.png')]);
    renderPage(nb({ active: 2 }));
    await heading('Step 2: Designing Activity');
    expect(await screen.findByText('4 of 4 files · 4 of 25 MB')).toBeInTheDocument();
    choose([sized('v5.png', MIB)]);
    expect(screen.getByRole('alert')).toHaveTextContent('This order already has 4 design files. Remove one before uploading another.');
    expect(screen.getByRole('button', { name: 'Upload selected files' })).toBeDisabled();
    expect(api.uploadDesignFile).not.toHaveBeenCalled();
  });

  it('refuses files that would take the order over 25 MB in total, and WEBP files', async () => {
    api.designFiles.mockResolvedValue([file(1, 'v1.pdf', 20 * MIB)]);
    renderPage(nb({ active: 2 }));
    await heading('Step 2: Designing Activity');
    await screen.findByRole('list', { name: /Design files/ });
    choose([sized('poster.png', 6 * MIB)]);
    expect(screen.getByRole('alert')).toHaveTextContent('Design files can total 25 MB per order; these would make 26 MB.');
    choose([sized('art.webp', MIB)]);
    expect(screen.getByRole('alert')).toHaveTextContent('art.webp: only PDF, CDR, PNG and JPEG files can be uploaded.');
    expect(api.uploadDesignFile).not.toHaveBeenCalled();
  });

  it('uploads chosen files one by one and lists them, with the server refusal shown verbatim, and lets the stage complete', async () => {
    const gate = { code: 'DESIGN_FILES_PRESENT', message: 'Upload at least one design file' };
    api.designFiles.mockResolvedValueOnce([]).mockResolvedValue([file(1, 'logo.png')]);
    api.uploadDesignFile.mockResolvedValueOnce(undefined).mockRejectedValueOnce({ response: { status: 409, data: { message: 'Design files can total 25 MB per order.' } } });
    // The server works the gate out when it builds the view, so the view read after the upload no longer lists it.
    api.view.mockResolvedValue(nb({ active: 2 }));
    renderPage(nb({ active: 2, steps: { 2: { unmet: [gate] } } }));
    await heading('Step 2: Designing Activity');
    const complete = screen.getByRole('button', { name: /Complete stage & continue/ });
    expect(complete).toBeDisabled();
    expect(complete).toHaveAccessibleDescription('Still needed: Upload at least one design file');
    choose([sized('logo.png', MIB), sized('cover.CDR', MIB)]);
    fireEvent.click(screen.getByRole('button', { name: 'Upload selected files' }));
    await waitFor(() => expect(api.uploadDesignFile).toHaveBeenCalledTimes(2));
    expect(await screen.findByRole('alert')).toHaveTextContent('cover.CDR: Design files can total 25 MB per order.');
    expect(await screen.findByText('Design file uploaded')).toBeInTheDocument();
    const listed = await screen.findByRole('list', { name: 'Design files, 1 of 4' });
    expect(within(listed).getByRole('button', { name: 'Preview logo.png' })).toBeInTheDocument();
    expect(within(listed).getByRole('button', { name: 'Remove logo.png' })).toBeInTheDocument();
    expect(api.view).toHaveBeenCalledWith('CK-1015');
    await waitFor(() => expect(screen.getByRole('button', { name: /Complete stage & continue/ })).toBeEnabled());
    expect(screen.queryByText("What's left to complete")).not.toBeInTheDocument();
  });

  it("names each file's uploader, which school-core gives as a user id", async () => {
    api.designFiles.mockResolvedValue([file(1, 'v1.pdf'), file(2, 'v2.png', MIB, { uploadedBy: 77 })]);
    renderPage(nb({ active: 2 }));
    await heading('Step 2: Designing Activity');
    const listed = await screen.findByRole('list', { name: 'Design files, 2 of 4' });
    expect(within(listed).getByText(/^1 MB · Asha Operator · /)).toBeInTheDocument();
    expect(within(listed).getByText(/^1 MB · User 77 · /)).toBeInTheDocument();
    expect(within(listed).queryByText(/ · 42 · /)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: 'Activity history' }));
    const revisions = await screen.findByRole('region', { name: 'Design revision history' });
    expect(within(revisions).getByRole('row', { name: /v1 v1\.pdf .* · Asha Operator Current/ })).toBeInTheDocument();
  });

  it("removes a file and reads the order again, since the files decide the stage's gate", async () => {
    api.designFiles.mockResolvedValueOnce([file(1, 'v1.pdf')]).mockResolvedValue([]);
    api.deleteDesignFile.mockResolvedValue(undefined);
    api.view.mockResolvedValue(nb({ active: 2, steps: { 2: { unmet: [{ code: 'DESIGN_FILES_PRESENT', message: 'Upload at least one design file' }] } } }));
    renderPage(nb({ active: 2 }));
    await heading('Step 2: Designing Activity');
    fireEvent.click(await screen.findByRole('button', { name: 'Remove v1.pdf' }));
    expect(await screen.findByText('v1.pdf removed')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: /Complete stage & continue/ })).toBeDisabled());
    expect(screen.getByText("What's left to complete")).toBeInTheDocument();
  });
});

describe('vendors', () => {
  it('lets the planning assignee select vendors per category and save them for later stages', async () => {
    api.setVendorSelections.mockResolvedValue(nb({ active: 5, view: { vendorSelections: { BOARD_SUPPLIERS: [1] } } }));
    renderPage(nb({ active: 5 }));
    await heading('Step 5: Production Planning');
    const selection = screen.getByRole('region', { name: 'Select Vendors for This Order' });
    const boards = await within(selection).findByRole('group', { name: 'Board Suppliers' });
    fireEvent.click(within(boards).getByRole('checkbox', { name: 'Shree Board Traders' }));
    fireEvent.click(within(selection).getByRole('button', { name: 'Save vendor selections' }));
    await waitFor(() => expect(api.setVendorSelections).toHaveBeenCalledWith('CK-1015', { BOARD_SUPPLIERS: [1] }, 5));
    expect(within(selection).getByText(/Selected vendors will be filtered in Board Procurement, Pre-Print Quality Check, Printing Press and subsequent stages\./)).toBeInTheDocument();
    const directory = screen.getByRole('region', { name: 'Supplier Contact Groups' });
    expect(within(directory).getByRole('link', { name: /Open group chat.*Shree Board Traders/ })).toHaveAttribute('href', 'https://chat.whatsapp.com/Vendor1Group12');
    expect(within(directory).getByRole('button', { name: /Open group chat.*no group chat saved/ })).toBeDisabled();
  });

  it('keeps unsaved vendor ticks when another card on the stage saves', async () => {
    api.saveStepData.mockResolvedValue(nb({ active: 5, steps: { 5: { data: { planningChecks: [1] }, version: 4 } }, view: { version: 6 } }));
    renderPage(nb({ active: 5 }));
    await heading('Step 5: Production Planning');
    const selection = screen.getByRole('region', { name: 'Select Vendors for This Order' });
    const boards = await within(selection).findByRole('group', { name: 'Board Suppliers' });
    fireEvent.click(within(boards).getByRole('checkbox', { name: 'Shree Board Traders' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'BOARDS AVAILABILITY CHECKED' }));
    await waitFor(() => expect(api.saveStepData).toHaveBeenCalledWith('CK-1015', 5, { planningChecks: [1] }, 3));
    await waitFor(() => expect(screen.getByRole('group', { name: 'Production planning checklist' })).toHaveTextContent('1/3 complete'));
    expect(within(boards).getByRole('checkbox', { name: 'Shree Board Traders' })).toBeChecked();
    expect(within(selection).getByRole('button', { name: 'Save vendor selections' })).toBeEnabled();
  });

  it('shows only the selected vendors on later stages, with the warning where none are selected', async () => {
    const selected = { id: 3, name: 'Perfect Bindery', categories: ['BINDERS'], contactPerson: 'Mohan', phone: '+91 90000 00003', city: 'Delhi', whatsappGroupUrl: null, sample: false, active: false };
    renderPage(nb({ active: 9, view: { vendorSelections: { BINDERS: [3] }, groups: { school: { url: null, source: 'NONE' }, internalApproval: { url: null, source: 'NONE' }, vendors: [selected] } } }));
    await heading('Step 9: Factory Handover');
    const groups = screen.getByRole('region', { name: 'Vendor Contact Groups' });
    expect(within(groups).getByText('Perfect Bindery')).toBeInTheDocument();
    expect(within(groups).getByText('Inactive')).toBeInTheDocument();
    expect(within(groups).getByRole('link', { name: /\+91 90000 00003/ })).toHaveAttribute('href', 'tel:+919000000003');
    expect(await within(groups).findByText('No Lamination Vendors selected for this order. Select them in Production Planning to show their group chats here.')).toBeInTheDocument();
  });
});

describe('the flier workflow', () => {
  it('renders the nine-step flier template with its own quantities and suggested vendor categories', async () => {
    api.summary.mockResolvedValue(makeSummary({ itemCode: 'FE', category: 'FLIERS', titleCards: null, quantities: { total: 5000, lines: [{ lineNo: 1, label: 'A4 flier', details: '130 GSM gloss', quantity: 5000 }] } }));
    renderPage(fe({ active: 5 }));
    await heading('Step 5: Production Planning');
    expect(within(stepper()).getAllByRole('listitem')).toHaveLength(9);
    expect(within(stepper()).getByRole('button', { name: /Step 8: Final Approval/ })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'FE-DPS_GUR-26-11-13-0007' })).toBeInTheDocument();
    expect(await screen.findByText('Total fliers')).toBeInTheDocument();
    const quantities = screen.getByRole('region', { name: 'Flier quantities' });
    expect(within(quantities).getByRole('row', { name: 'A4 flier 130 GSM gloss 5,000' })).toBeInTheDocument();
    const selection = screen.getByRole('region', { name: 'Select Vendors for This Order' });
    const suggested = await within(selection).findAllByRole('group');
    expect(suggested.slice(0, 3).map((group) => group.querySelector('legend')?.textContent?.split(' ·')[0].trim())).toEqual(['Digital Printers', 'Offset Printers', 'Photo Paper Printers']);
    expect(within(selection).getByText('Other categories (4)')).toBeInTheDocument();
  });

  it("lists the item's suggested categories first and open on Supplier Contact Groups as well (D33)", async () => {
    renderPage(fe({ active: 5 }));
    await heading('Step 5: Production Planning');
    const directory = screen.getByRole('region', { name: 'Supplier Contact Groups' });
    await within(directory).findAllByText('Metro Offset Printers');
    const open = within(directory).getAllByRole('heading', { level: 5 }).filter((node) => !node.closest('details'));
    expect(open.map((node) => node.textContent)).toEqual(['Digital Printers', 'Offset Printers', 'Photo Paper Printers']);
    expect(within(directory).getByText('Other categories (4)')).toBeInTheDocument();
  });

  it('asks for a final validation note and an internal approval on Final Approval', async () => {
    renderPage(fe({ active: 8 }));
    await heading('Step 8: Final Approval');
    expect(screen.getByRole('checkbox', { name: 'Finished items checked against approved specifications' })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Final validation notes'), { target: { value: 'word '.repeat(101) } });
    expect(screen.getByText('Use at most 100 words.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save validation notes' })).toBeDisabled();
    expect(screen.getByText('Get internal team approval in the Internal Approval Group, then record it here.')).toBeInTheDocument();
  });
});

describe('roles, tabs and the sidebar', () => {
  it('offers an unassigned order to an eligible operator, and assignment only to superadmin', async () => {
    api.claim.mockResolvedValue(nb({ active: 5 }));
    renderPage(nb({ active: 5, view: { assigneeUserId: null, assigneeName: null, can: { ...nb().can, canClaim: true } } }));
    await heading('Step 5: Production Planning');
    expect(screen.getByText('Unassigned')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Assign…' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'More stage actions' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Take this order' }));
    await waitFor(() => expect(api.claim).toHaveBeenCalledWith('CK-1015'));
    expect(await screen.findByText('You are now the point of contact')).toBeInTheDocument();
  });

  it('lets superadmin assign the order and reopen a stage with a reason', async () => {
    auth.user = { role: 'SUPERADMIN', userId: 1, fullName: 'Super Admin' };
    api.assignOrder.mockResolvedValue(nb({ active: 5, superadmin: true }));
    api.reopen.mockResolvedValue(nb({ active: 3, superadmin: true }));
    renderPage(nb({ active: 5, superadmin: true }));
    await heading('Step 5: Production Planning');
    fireEvent.click(screen.getByRole('button', { name: 'Assign…' }));
    fireEvent.change(await screen.findByLabelText('Operator'), { target: { value: '51' } });
    fireEvent.click(screen.getByRole('button', { name: 'Assign order' }));
    await waitFor(() => expect(api.assignOrder).toHaveBeenCalledWith('CK-1015', 51));
    expect(api.operators).toHaveBeenCalledWith(7, 'NOTEBOOKS');

    fireEvent.click(within(stepper()).getByRole('button', { name: /Step 3: CDR Conversion/ }));
    await heading('Step 3: CDR Conversion');
    fireEvent.click(screen.getByRole('button', { name: 'More stage actions' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Reopen stage…' }));
    const dialog = screen.getByRole('dialog', { name: 'Reopen CDR Conversion?' });
    expect(within(dialog).getByRole('button', { name: 'Reopen stage' })).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText('Reason'), { target: { value: 'Wrong file' } });
    expect(within(dialog).getByText('2/100 words')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Reopen stage' }));
    await waitFor(() => expect(api.reopen).toHaveBeenCalledWith('CK-1015', 3, 'Wrong file', 3));
  });

  it('opens the call form preset to school feedback from the sidebar', async () => {
    api.calls.mockResolvedValue([{ id: 1, stepOrder: 2, contacted: 'SCHOOL', outcome: 'CHANGES_REQUESTED', contactLabel: 'Rajesh Kumar', vendorId: null, designerId: null,
      notes: 'Make the background green.', nextFollowUpAt: null, createdBy: 42, createdByName: 'Asha Operator', createdAt: '2026-11-12T08:00:00Z' }]);
    renderPage(nb({ active: 1 }));
    await heading('Step 1: Order Details');
    const feedback = screen.getByRole('complementary', { name: 'Order details' });
    expect(await within(feedback).findByText('Make the background green.')).toBeInTheDocument();
    expect(within(feedback).getByText('Please make the logo bigger.')).toBeInTheDocument();
    fireEvent.click(within(feedback).getByRole('button', { name: /Add school feedback/ }));
    expect(await heading('Step 2: Designing Activity')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'School & vendor' })).toHaveAttribute('aria-selected', 'true');
    const form = await screen.findByRole('form', { name: 'Log a call or discussion' });
    expect(within(form).getByRole('combobox', { name: 'Contacted' })).toHaveValue('SCHOOL');
    expect(within(form).getByRole('combobox', { name: 'Who' })).toHaveValue('school');
    expect(within(form).getByRole('combobox', { name: 'Outcome' })).toHaveValue('CHANGES_REQUESTED');
    expect(within(form).getByRole('textbox', { name: 'Call notes *' })).toHaveFocus();
    expect(screen.getByRole('button', { name: 'Save contacts' })).toBeDisabled();
  });

  it('logs a call against the current stage while another stage is viewed (D35)', async () => {
    vi.mocked(callsApi.log).mockResolvedValue({ id: 6, stepOrder: 5, contacted: 'SCHOOL', outcome: 'CONNECTED', contactLabel: 'Rajesh Kumar', vendorId: null,
      designerId: null, notes: null, nextFollowUpAt: null, createdBy: 42, createdByName: 'Asha Operator', createdAt: '2026-11-13T06:20:00Z' });
    api.view.mockResolvedValue(nb({ active: 5 }));
    renderPage(nb({ active: 5 }));
    await heading('Step 5: Production Planning');
    fireEvent.click(within(stepper()).getByRole('button', { name: /Step 2: Designing Activity/ }));
    await heading('Step 2: Designing Activity');
    fireEvent.click(screen.getByRole('tab', { name: 'School & vendor' }));
    const form = await screen.findByRole('form', { name: 'Log a call or discussion' });
    fireEvent.click(within(form).getByRole('button', { name: 'Log call' }));
    await waitFor(() => expect(callsApi.log).toHaveBeenCalledWith('CK-1015', expect.objectContaining({ stepOrder: 5 })));
  });

  it('keeps typed, unsaved contacts when a call is logged and the order is read again', async () => {
    vi.mocked(callsApi.log).mockResolvedValue({ id: 7, stepOrder: 2, contacted: 'SCHOOL', outcome: 'CONNECTED', contactLabel: 'Rajesh Kumar', vendorId: null,
      designerId: null, notes: null, nextFollowUpAt: null, createdBy: 42, createdByName: 'Asha Operator', createdAt: '2026-11-13T06:20:00Z' });
    api.view.mockResolvedValue(nb({ active: 2, view: { version: 6 } }));
    renderPage(nb({ active: 2 }));
    await heading('Step 2: Designing Activity');
    fireEvent.click(screen.getByRole('tab', { name: 'School & vendor' }));
    fireEvent.change(screen.getByLabelText('School contact name'), { target: { value: 'Typed Name' } });
    const form = await screen.findByRole('form', { name: 'Log a call or discussion' });
    fireEvent.click(within(form).getByRole('button', { name: 'Log call' }));
    await waitFor(() => expect(api.view).toHaveBeenCalledWith('CK-1015'));
    expect(await screen.findByText('Call logged')).toBeInTheDocument();
    expect(screen.getByLabelText('School contact name')).toHaveValue('Typed Name');
    expect(screen.getByRole('button', { name: 'Save contacts' })).toBeEnabled();
  });

  it('takes no stage notes on a stage not reached yet', async () => {
    renderPage(nb({ active: 5 }));
    await heading('Step 5: Production Planning');
    expect(screen.getByLabelText('Stage note')).toBeInTheDocument();
    fireEvent.click(within(stepper()).getByRole('button', { name: /Step 8: Printing Press/ }));
    await heading('Step 8: Printing Press');
    const notes = screen.getByRole('region', { name: 'Stage notes & next action' });
    expect(within(notes).queryByLabelText('Stage note')).not.toBeInTheDocument();
    expect(within(notes).queryByRole('button', { name: 'Save activity note' })).not.toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Internal Notes' })).getByLabelText('Internal note')).toBeInTheDocument();
  });

  it('reads the order again on Refresh, so changes made elsewhere appear', async () => {
    api.view.mockResolvedValue(nb({ active: 6 }));
    renderPage(nb({ active: 5 }));
    await heading('Step 5: Production Planning');
    expect(screen.getByText('4 of 12 steps')).toBeInTheDocument();
    await waitFor(() => expect(api.summary).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(api.view).toHaveBeenCalledWith('CK-1015'));
    expect(await screen.findByText('5 of 12 steps')).toBeInTheDocument();
    expect(await heading('Step 6: Board Procurement')).toBeInTheDocument();
    await waitFor(() => expect(api.summary).toHaveBeenCalledTimes(2));
  });

  it('previews an earlier WEBP design file as a picture, and names other files by their type', async () => {
    api.designFiles.mockResolvedValue([file(1, 'old.webp', MIB, { contentType: 'image/webp' })]);
    renderPage(nb({ active: 3 }));
    await heading('Step 3: CDR Conversion');
    const preview = screen.getByRole('region', { name: 'Current Design Preview' });
    expect(await within(preview).findByRole('img', { name: 'Current design: old.webp' })).toBeInTheDocument();
    expect(within(preview).queryByText('PDF')).not.toBeInTheDocument();
    cleanup();

    api.designFiles.mockResolvedValue([file(1, 'brief.cdr', MIB, { contentType: 'application/vnd.corel-draw' }), file(2, 'proof.pdf')]);
    renderPage(nb({ active: 3 }));
    await heading('Step 3: CDR Conversion');
    const tile = screen.getByRole('region', { name: 'Current Design Preview' });
    expect(await within(tile).findByText('PDF')).toBeInTheDocument();
    expect(within(tile).getByRole('button', { name: 'View file' })).toBeInTheDocument();
  });

  it('logs a call against the current stage, keeps the confirmation, and refreshes the order and its school feedback', async () => {
    const logged = { id: 5, stepOrder: 2, contacted: 'SCHOOL' as const, outcome: 'CHANGES_REQUESTED' as const, contactLabel: 'Rajesh Kumar', vendorId: null,
      designerId: null, notes: 'Logo on the left', nextFollowUpAt: null, createdBy: 42, createdByName: 'Asha Operator', createdAt: '2026-11-13T06:20:00Z' };
    vi.mocked(callsApi.log).mockResolvedValue(logged);
    api.view.mockResolvedValue(nb({ active: 2 }));
    renderPage(nb({ active: 2 }));
    await heading('Step 2: Designing Activity');
    const feedback = screen.getByRole('complementary', { name: 'Order details' });
    fireEvent.click(within(feedback).getByRole('button', { name: /Add school feedback/ }));
    const form = await screen.findByRole('form', { name: 'Log a call or discussion' });
    expect(api.calls).toHaveBeenCalledTimes(1);
    api.calls.mockResolvedValue([logged]);
    vi.mocked(callsApi.list).mockResolvedValue([logged]);
    fireEvent.change(within(form).getByRole('textbox', { name: 'Call notes *' }), { target: { value: 'Logo on the left' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Log call' }));

    await waitFor(() => expect(callsApi.log).toHaveBeenCalledWith('CK-1015', {
      contacted: 'SCHOOL', outcome: 'CHANGES_REQUESTED', contactLabel: 'Rajesh Kumar', vendorId: null, designerId: null, stepOrder: 2,
      notes: 'Logo on the left', nextFollowUpAt: null,
    }));
    expect(await screen.findByText('Call logged')).toBeInTheDocument();
    await waitFor(() => expect(api.view).toHaveBeenCalledWith('CK-1015'));
    expect(await within(feedback).findByText('Logo on the left')).toBeInTheDocument();
    expect(await within(screen.getByRole('list', { name: 'Logged calls' })).findByText('Logo on the left')).toBeInTheDocument();
    // The form is ready for the next call, back on its defaults.
    expect(within(screen.getByRole('form', { name: 'Log a call or discussion' })).getByRole('combobox', { name: 'Outcome' })).toHaveValue('CONNECTED');
    expect(screen.getByText('Call logged')).toBeInTheDocument();
  });

  it('opens Activity history for the full call history', async () => {
    const calls = Array.from({ length: 11 }, (_, index) => ({
      id: index + 1, stepOrder: 2, contacted: 'SCHOOL' as const, outcome: 'CONNECTED' as const, contactLabel: 'Rajesh Kumar', vendorId: null, designerId: null,
      notes: null, nextFollowUpAt: null, createdBy: 42, createdByName: 'Asha Operator', createdAt: new Date(Date.UTC(2026, 10, 1 + index, 6)).toISOString(),
    }));
    vi.mocked(callsApi.list).mockResolvedValue(calls);
    renderPage(nb({ active: 2 }));
    await heading('Step 2: Designing Activity');
    fireEvent.click(screen.getByRole('tab', { name: 'School & vendor' }));
    await screen.findByRole('list', { name: 'Logged calls' });
    fireEvent.click(screen.getByRole('button', { name: 'Show all' }));
    expect(screen.getByRole('tab', { name: 'Activity history' })).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByRole('region', { name: 'Order activity history' })).toBeInTheDocument();
    expect(api.activity).toHaveBeenCalledWith('CK-1015', { step: undefined });
  });

  it('saves an internal note, and lists the upcoming actions the server derived', async () => {
    api.addNote.mockResolvedValue({ id: 9, stepOrder: null, authorUserId: 42, authorName: 'Asha Operator', body: 'Call the binder', createdAt: '2026-11-13T06:00:00Z' });
    api.view.mockResolvedValue(nb({ active: 5, view: { internalNotes: [{ id: 9, stepOrder: null, authorUserId: 42, authorName: 'Asha Operator', body: 'Call the binder', createdAt: '2026-11-13T06:00:00Z' }] } }));
    renderPage(nb({ active: 5, view: { upcomingActions: [
      { kind: 'LAST_COMPLETED', title: 'Head Approval', stepOrder: 4, dueAt: null, dueState: 'NONE', done: true },
      { kind: 'STAGE_DEADLINE', title: 'Production Planning due', stepOrder: 5, dueAt: '2026-11-13T09:30:00Z', dueState: 'DUE_TODAY', done: false },
    ] } }));
    await heading('Step 5: Production Planning');
    const upcoming = screen.getByRole('region', { name: 'Upcoming Actions' });
    expect(within(upcoming).getByText('Live list')).toBeInTheDocument();
    expect(within(upcoming).getByText('Due today')).toHaveClass('is-warning');
    expect(within(upcoming).getByText('Completed')).toBeInTheDocument();
    const notes = screen.getByRole('region', { name: 'Internal Notes' });
    fireEvent.change(within(notes).getByLabelText('Internal note'), { target: { value: 'Call the binder' } });
    expect(within(notes).getByText('3/100 words')).toBeInTheDocument();
    fireEvent.click(within(notes).getByRole('button', { name: 'Save note' }));
    await waitFor(() => expect(api.addNote).toHaveBeenCalledWith('CK-1015', 'Call the binder'));
    expect(await within(notes).findByText('Call the binder')).toBeInTheDocument();
  });

  it('saves a stage note against the stage', async () => {
    api.addNote.mockResolvedValue({ id: 3, stepOrder: 5, authorUserId: 42, authorName: 'Asha Operator', body: 'Boards arrive Monday', createdAt: '2026-11-13T06:00:00Z' });
    api.view.mockResolvedValue(nb({ active: 5 }));
    renderPage(nb({ active: 5 }));
    await heading('Step 5: Production Planning');
    fireEvent.change(screen.getByLabelText('Stage note'), { target: { value: 'Boards arrive Monday' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save activity note' }));
    await waitFor(() => expect(api.addNote).toHaveBeenCalledWith('CK-1015', 'Boards arrive Monday', 5));
    expect(await screen.findByText('Activity note saved')).toBeInTheDocument();
  });

  it('gathers every checklist of the order, read-only, with a way to each stage', async () => {
    renderPage(nb({ active: 8, steps: { 7: { data: { checks: [0, 1, 2, 3, 4, 5, 6] }, signOff: { by: 1, byName: 'Super Admin', at: '2026-11-13T05:00:00Z' } } } }));
    await heading('Step 8: Printing Press');
    fireEvent.click(screen.getByRole('tab', { name: 'Checklist' }));
    const gate = screen.getByRole('region', { name: 'Step 7: Mandatory pre-print quality gate' });
    expect(within(gate).getByText('8/8 complete')).toBeInTheDocument();
    expect(within(gate).queryByRole('checkbox')).not.toBeInTheDocument();
    fireEvent.click(within(gate).getByRole('button', { name: /Go to stage/ }));
    expect(await heading('Step 7: Pre-Print Quality Check')).toBeInTheDocument();
  });

  it('names who removed a design file, from the id school-core records with the removal (D40)', async () => {
    api.designFiles.mockResolvedValue([
      file(1, 'v1.pdf', MIB, { supersededAt: '2026-11-12T06:00:00Z', supersededBy: 42 }),
      file(2, 'v2.png', MIB, { uploadedBy: 77, supersededAt: '2026-11-12T07:00:00Z', supersededBy: 77 }),
      file(3, 'v3.png', MIB, { supersededAt: '2026-11-12T08:00:00Z', supersededBy: null }),
    ]);
    renderPage(nb({ active: 3 }));
    await heading('Step 3: CDR Conversion');
    fireEvent.click(screen.getByRole('tab', { name: 'Activity history' }));
    const entry = async (title: string) => (await screen.findByText(title)).closest('li') as HTMLElement;
    expect(within(await entry('Removed design file v1.pdf')).getByText(/^Asha Operator · /)).toBeInTheDocument();
    expect(within(await entry('Removed design file v2.png')).getByText(/^User 77 · /)).toBeInTheDocument();
    // A removal recorded before school-core kept the remover names no one.
    expect(within(await entry('Removed design file v3.png')).queryByText(/^(Asha Operator|User \d+|Someone) · /)).not.toBeInTheDocument();
    expect(within(await entry('Uploaded design file v3.png')).getByText(/^Asha Operator · /)).toBeInTheDocument();
  });

  it('shows the activity timeline, filtered to the stage on request, and the design revisions', async () => {
    api.designFiles.mockResolvedValue([file(1, 'v1.pdf', MIB, { supersededAt: '2026-11-12T06:00:00Z' }), file(2, 'v2.png')]);
    api.activity.mockResolvedValue({ entries: [
      { id: 'action:1', kind: 'ACTION', action: 'STEP_DATA_SAVED', stepOrder: 3, actorUserId: 42, actorName: 'Asha Operator', at: '2026-11-13T05:00:00Z', text: null, details: { fields: ['cdrLink'] }, count: 3 },
      { id: 'call:2', kind: 'CALL', action: null, stepOrder: 2, actorUserId: 42, actorName: 'Asha Operator', at: '2026-11-13T04:00:00Z', text: 'Wants green', details: { contacted: 'SCHOOL', outcome: 'CHANGES_REQUESTED', contactLabel: 'Rajesh Kumar' }, count: 1 },
    ], nextBefore: null });
    renderPage(nb({ active: 3 }));
    await heading('Step 3: CDR Conversion');
    fireEvent.click(screen.getByRole('tab', { name: 'Activity history' }));
    expect(await screen.findByText('Saved Controlled storage link (3 saves)')).toBeInTheDocument();
    expect(screen.getByText('Call · School · Rajesh Kumar · Changes Requested')).toBeInTheDocument();
    expect(screen.getByText('Uploaded design file v2.png')).toBeInTheDocument();
    expect(screen.getByText('Removed design file v1.pdf')).toBeInTheDocument();
    const revisions = screen.getByRole('region', { name: 'Design revision history' });
    expect(within(revisions).getByRole('row', { name: /v1 v1\.pdf .* Removed/ })).toBeInTheDocument();
    expect(within(revisions).getByRole('row', { name: /v2 v2\.png .* Current/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('radio', { name: 'This stage' }));
    await waitFor(() => expect(api.activity).toHaveBeenLastCalledWith('CK-1015', { step: 3 }));
  });

  it('reloads after a conflict and says why', async () => {
    api.saveStepData.mockRejectedValue({ response: { status: 409, data: { code: 'STALE', message: 'Someone else changed this stage.' } } });
    api.view.mockResolvedValue(nb({ active: 3, steps: { 3: { version: 7, data: { cdrNotes: 'Their notes' } } } }));
    renderPage(nb({ active: 3 }));
    await heading('Step 3: CDR Conversion');
    fireEvent.click(screen.getByRole('checkbox', { name: 'Fonts outlined' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Someone else changed this stage. The order has been reloaded with the latest changes.');
    expect(await screen.findByDisplayValue('Their notes')).toBeInTheDocument();
  });
});

describe('layout by the window (section 4.3)', () => {
  /** A window this many pixels wide, as matchMedia answers the page's max-width queries. */
  function withWindow(width: number) {
    vi.stubGlobal('matchMedia', (query: string) => {
      const limit = Number(/max-width:\s*(\d+)px/.exec(query)?.[1]);
      return { matches: Number.isFinite(limit) && width <= limit, media: query, onchange: null, addEventListener() {}, removeEventListener() {},
        addListener() {}, removeListener() {}, dispatchEvent: () => false };
    });
  }
  afterEach(() => vi.unstubAllGlobals());

  it('keeps the stage list beside the stage from a 1024 px window, whatever room the navigation leaves', async () => {
    withWindow(1024);
    renderPage(nb({ active: 5 }));
    await heading('Step 5: Production Planning');
    expect(screen.getByRole('navigation', { name: 'Order workflow stages' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Stage')).not.toBeInTheDocument();
    expect(within(stepper()).getAllByRole('listitem')).toHaveLength(12);
  });

  it('makes the stage list a select below 1024 px with every step still on the stepper, and folds the stepper to one line below 768 px', async () => {
    withWindow(1023);
    renderPage(nb({ active: 5 }));
    await heading('Step 5: Production Planning');
    expect(await screen.findByLabelText('Stage')).toHaveValue('5');
    expect(screen.queryByRole('navigation', { name: 'Order workflow stages' })).not.toBeInTheDocument();
    expect(within(stepper()).getAllByRole('listitem')).toHaveLength(12);
    expect(within(stepper()).getByRole('button', { name: /Step 5: Production Planning, in progress, viewing/ })).toBeInTheDocument();
    cleanup();

    withWindow(768);
    renderPage(nb({ active: 5 }));
    await heading('Step 5: Production Planning');
    expect(within(stepper()).getAllByRole('listitem')).toHaveLength(12);
    cleanup();

    withWindow(767);
    renderPage(nb({ active: 5 }));
    await heading('Step 5: Production Planning');
    await waitFor(() => expect(within(stepper()).queryByRole('list')).not.toBeInTheDocument());
    expect(within(stepper()).getByText(/Step 5 · Production Planning/)).toHaveTextContent('(5 of 12)');
    expect(within(stepper()).getByRole('button', { name: 'Next stage' })).toBeEnabled();
    expect(screen.getByLabelText('Stage')).toHaveValue('5');
  });
});

describe('the order entry for staff', () => {
  it('opens a workflow order as its workflow', async () => {
    api.view.mockResolvedValue(nb({ active: 5 }));
    render(<OrderDetailEntry orderId="CK-1015" trackerManaged onBack={vi.fn()} />);
    expect(await heading('Step 5: Production Planning')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /Back to orders/ })[0]).toBeInTheDocument();
  });

  it('keeps any other order on the ordinary order detail, and school users always there', async () => {
    render(<OrderDetailEntry orderId="CK-2" trackerManaged={false} onBack={vi.fn()} />);
    expect(screen.getByText('ordinary order detail CK-2')).toBeInTheDocument();
    cleanup();
    auth.user = { role: 'ADMIN', userId: 9, fullName: 'School Admin' };
    render(<OrderDetailEntry orderId="CK-3" trackerManaged onBack={vi.fn()} />);
    expect(screen.getByText('ordinary order detail CK-3')).toBeInTheDocument();
    expect(api.view).not.toHaveBeenCalled();
  });

  it('offers to start a workflow that is still being set up, then opens it', async () => {
    api.view.mockRejectedValueOnce({ response: { status: 404, data: { message: 'Not found' } } }).mockResolvedValue(nb({ active: 1 }));
    api.summary.mockResolvedValue(makeSummary({ trackerPending: true }));
    api.ensure.mockResolvedValue({ trackerPending: false });
    render(<OrderDetailEntry orderId="CK-1015" trackerManaged onBack={vi.fn()} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Start workflow' }));
    await waitFor(() => expect(api.ensure).toHaveBeenCalledWith('CK-1015'));
    expect(await heading('Step 1: Order Details')).toBeInTheDocument();
  });

  it('says plainly when the workflow service is down, and retries', async () => {
    api.view.mockRejectedValueOnce({ response: { status: 502, data: { message: 'Gateway upstream error' } } }).mockResolvedValue(nb({ active: 1 }));
    render(<OrderDetailEntry orderId="CK-1015" trackerManaged onBack={vi.fn()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('The order workflow service is not available right now. Try again in a moment.');
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Retry/ })); });
    expect(await heading('Step 1: Order Details')).toBeInTheDocument();
  });
});
