import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../../../services/api';
import { setupApi, type WorkflowTemplate } from '../../../features/orders/vendors/api';
import { NotebookAdminPanel } from './NotebookAdminPanel';

// The test run loads no stylesheets, so the layout rule a time slot row relies on is read from its source.
const { readFileSync } = await vi.importActual<{ readFileSync: (path: string, encoding: string) => string }>('node:fs');
const { cwd } = await vi.importActual<{ cwd: () => string }>('node:process');
const setupCss = readFileSync(`${cwd()}/src/features/orders/vendors/setup.css`, 'utf8');

const auth = vi.hoisted(() => ({ user: { role: 'SUPERADMIN', userId: 1, permissions: [] as string[] } as { role: string; userId: number; permissions: string[] } }));
vi.mock('../../../contexts/AuthContext', () => ({ useAuth: () => auth }));
vi.mock('../../../services/api', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() } }));
vi.mock('../../../features/orders/vendors/VendorDirectory', () => ({ VendorDirectory: () => <p>vendor directory</p> }));
vi.mock('../../../features/orders/vendors/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../features/orders/vendors/api')>();
  const mocked = Object.fromEntries(Object.keys(actual.setupApi).map((key) => [key, vi.fn()]));
  return { ...actual, setupApi: mocked };
});

const UNAVAILABLE = 'The order workflow service is not available right now. Try again in a moment.';
const INVITE = 'https://chat.whatsapp.com/AbCdEf12345';

function template(definitionId: WorkflowTemplate['definitionId'], steps: Array<[number, string, number | null, boolean?]>): WorkflowTemplate {
  return {
    definitionId, title: definitionId, introMessage: '',
    steps: steps.map(([order, title, hours, customisedOnly = false]) => ({
      order, code: `S${order}`, title, description: '', phase: 'INTAKE', defaultSla: hours === null ? null : { value: hours, unit: 'HOURS' },
      customisedOnly, icon: 'Circle', tabs: ['WORK'], blocks: [], fields: {}, checklists: {}, gates: [], signOff: null, superadminCompletes: false,
    })),
  };
}

const TEMPLATES = [
  template('NOTEBOOK_ORDER_TRACKER', [[1, 'Order Details', 2], [2, 'Designing Activity', 72, true], [7, 'Pre-Print Quality Check', null]]),
  template('FLIER_ORDER_WORKFLOW', [[1, 'Order Details', 2], [8, 'Final Approval', 4]]),
];

beforeEach(() => {
  vi.clearAllMocks();
  auth.user = { role: 'SUPERADMIN', userId: 1, permissions: [] };
  vi.mocked(setupApi.schools).mockResolvedValue([{ id: 7, name: 'School Seven' }]);
  vi.mocked(setupApi.operators).mockResolvedValue([]);
  vi.mocked(setupApi.operatorUsers).mockResolvedValue([{ userId: 12, fullName: 'Op Twelve', email: 'op12@x.test' }]);
  vi.mocked(setupApi.categories).mockResolvedValue([{ code: 'NOTEBOOKS', label: 'Notebooks' }, { code: 'FLIERS', label: 'Fliers' }]);
  vi.mocked(setupApi.designers).mockResolvedValue([]);
  vi.mocked(setupApi.templates).mockResolvedValue(TEMPLATES);
  vi.mocked(setupApi.timeSlots).mockResolvedValue([]);
  vi.mocked(setupApi.internalApprovalGroup).mockResolvedValue({ inviteUrl: null, updatedAt: null, updatedBy: null });
  vi.mocked(setupApi.schoolGroups).mockResolvedValue({});
});
afterEach(cleanup);

const tab = (name: string) => screen.getByRole('tab', { name });

describe('Workflow set-up shell (section 4.7)', () => {
  it('is titled for every item and opens on the vendor directory', async () => {
    render(<NotebookAdminPanel />);
    expect(screen.getByRole('heading', { name: 'Workflow set-up' })).toBeInTheDocument();
    expect(screen.getAllByRole('tab').map((item) => item.textContent)).toEqual(['Vendors', 'Designers', 'Time slots', 'Groups', 'Operators']);
    expect(screen.getByRole('tabpanel', { name: 'Vendors' })).toHaveTextContent('vendor directory');
    await waitFor(() => expect(setupApi.schools).toHaveBeenCalled());
  });

  it('moves between sections with the arrow keys, Home and End, and labels each panel by its tab', async () => {
    render(<NotebookAdminPanel />);
    expect(tab('Vendors')).toHaveAttribute('tabindex', '0');
    expect(tab('Designers')).toHaveAttribute('tabindex', '-1');
    fireEvent.keyDown(tab('Vendors'), { key: 'ArrowRight' });
    expect(tab('Designers')).toHaveAttribute('aria-selected', 'true');
    expect(tab('Designers')).toHaveFocus();
    expect(screen.getByRole('tabpanel', { name: 'Designers' })).toBeInTheDocument();
    fireEvent.keyDown(tab('Designers'), { key: 'End' });
    expect(tab('Operators')).toHaveFocus();
    fireEvent.keyDown(tab('Operators'), { key: 'ArrowRight' });
    expect(tab('Vendors')).toHaveFocus();
    fireEvent.keyDown(tab('Vendors'), { key: 'ArrowLeft' });
    expect(tab('Operators')).toHaveAttribute('aria-selected', 'true');
    fireEvent.keyDown(tab('Operators'), { key: 'Home' });
    expect(tab('Vendors')).toHaveFocus();
    expect(await screen.findByRole('tabpanel', { name: 'Vendors' })).toBeInTheDocument();
  });

  it('reports a school list that cannot load', async () => {
    vi.mocked(setupApi.schools).mockRejectedValue({ response: { status: 400, data: { message: 'Schools are unavailable' } } });
    render(<NotebookAdminPanel />);
    expect(await screen.findByText('Schools are unavailable')).toHaveAttribute('role', 'alert');
  });

  it('is shown to superadmin only: an operator sees why, and nothing is asked of the server', async () => {
    auth.user = { role: 'OPERATIONS', userId: 42, permissions: ['notebook-tracker:read', 'notebook-tracker:act'] };
    render(<NotebookAdminPanel />);
    expect(screen.getByRole('heading', { name: 'Workflow set-up' })).toBeInTheDocument();
    expect(screen.getByText(/Only a superadmin manages workflow set-up/)).toBeInTheDocument();
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
    expect(screen.queryByText('vendor directory')).not.toBeInTheDocument();
    await Promise.resolve();
    expect(setupApi.schools).not.toHaveBeenCalled();
  });
});

describe('Time slots', () => {
  it('edits a template step\'s default after choosing the template', async () => {
    vi.mocked(setupApi.setDefaultSla).mockResolvedValue(TEMPLATES[1]);
    render(<NotebookAdminPanel />);
    fireEvent.click(tab('Time slots'));
    const defaults = await screen.findByRole('table', { name: 'Default time slots of the Notebook order workflow (12 steps)' });
    expect(within(defaults).getByRole('row', { name: /2\. Designing Activity/ })).toHaveTextContent('Customised orders only');
    expect(within(defaults).getByRole('row', { name: /2\. Designing Activity/ })).toHaveTextContent('72 hours · 24/7');
    expect(within(defaults).getByRole('row', { name: /7\. Pre-Print Quality Check/ })).toHaveTextContent('No timer');

    fireEvent.change(screen.getByRole('combobox', { name: 'Workflow template' }), { target: { value: 'FLIER_ORDER_WORKFLOW' } });
    const flier = screen.getByRole('table', { name: 'Default time slots of the Flier order workflow (9 steps)' });
    expect(within(flier).getAllByRole('row')).toHaveLength(3);
    const value = within(flier).getByRole('textbox', { name: 'Default value for step 8' });
    const save = within(flier).getByRole('button', { name: 'Save default for step 8' });
    fireEvent.change(value, { target: { value: '0' } });
    expect(save).toBeDisabled();
    fireEvent.change(value, { target: { value: '6' } });
    fireEvent.change(within(flier).getByRole('combobox', { name: 'Default unit for step 8' }), { target: { value: 'WORKING_DAYS' } });
    fireEvent.click(save);
    await waitFor(() => expect(setupApi.setDefaultSla).toHaveBeenCalledWith('FLIER_ORDER_WORKFLOW', 8, 6, 'WORKING_DAYS'));
    expect(await screen.findByText('Step 8 default saved.')).toBeInTheDocument();
    expect(setupApi.templates).toHaveBeenCalledTimes(2);
  });

  it('overrides a step at one school for the chosen template and returns an override to the default', async () => {
    vi.mocked(setupApi.timeSlots).mockResolvedValue([
      { step: 1, code: 'ORDER_DETAILS', title: 'Order Details', defaultSla: { value: 2, unit: 'HOURS' }, effective: { value: 2, unit: 'HOURS', source: 'DEFAULT' } },
      { step: 2, code: 'DESIGNING_ACTIVITY', title: 'Designing Activity', defaultSla: { value: 72, unit: 'HOURS' }, effective: { value: 3, unit: 'WORKING_DAYS', source: 'SCHOOL' } },
      { step: 7, code: 'PRE_PRINT_QUALITY_GATE', title: 'Pre-Print Quality Check', defaultSla: null, effective: null },
    ]);
    render(<NotebookAdminPanel />);
    fireEvent.click(tab('Time slots'));
    fireEvent.change(await screen.findByRole('combobox', { name: 'Workflow template' }), { target: { value: 'FLIER_ORDER_WORKFLOW' } });
    fireEvent.change(screen.getByRole('combobox', { name: 'School' }), { target: { value: '7' } });
    const school = await screen.findByRole('table', { name: 'Time slots at the chosen school' });
    expect(setupApi.timeSlots).toHaveBeenLastCalledWith(7, 'FLIER_ORDER_WORKFLOW');
    expect(within(school).getByText('School override')).toBeInTheDocument();
    expect(within(school).getByText('3 working days · Mon–Fri')).toBeInTheDocument();
    fireEvent.change(within(school).getByRole('textbox', { name: 'School value for step 1' }), { target: { value: '6' } });
    fireEvent.click(within(school).getByRole('button', { name: 'Save school for step 1' }));
    await waitFor(() => expect(setupApi.setTimeSlot).toHaveBeenCalledWith(7, 'FLIER_ORDER_WORKFLOW', 1, 6, 'HOURS'));
    fireEvent.click(within(school).getByRole('button', { name: 'Use the default for step 2' }));
    await waitFor(() => expect(setupApi.clearTimeSlot).toHaveBeenCalledWith(7, 'FLIER_ORDER_WORKFLOW', 2));
  });

  it('keeps a time slot\'s value, unit and Save on one wrapping line, and explains the clock', async () => {
    render(<NotebookAdminPanel />);
    fireEvent.click(tab('Time slots'));
    expect(screen.getByText(/Hour slots use continuous elapsed time, including weekends. Working-day slots run Monday to Friday./)).toBeInTheDocument();
    const value = await screen.findByRole('textbox', { name: 'Default value for step 1' });
    const line = value.parentElement!;
    expect(line).toHaveClass('ck-setup-slot');
    expect(screen.getByRole('combobox', { name: 'Default unit for step 1' }).parentElement).toBe(line);
    expect(screen.getByRole('button', { name: 'Save default for step 1' }).parentElement).toBe(line);
    expect(setupCss).toMatch(/\.ck-setup-slot\s*\{[^}]*display:\s*flex;[^}]*flex-wrap:\s*wrap;/);
  });

  it('reports templates and school slots that cannot load instead of showing them as empty', async () => {
    vi.mocked(setupApi.templates).mockRejectedValueOnce({ response: { status: 503 } });
    vi.mocked(setupApi.timeSlots).mockRejectedValue({ response: { status: 400, data: { message: 'Time slots are unavailable' } } });
    render(<NotebookAdminPanel />);
    fireEvent.click(tab('Time slots'));
    expect(await screen.findByText(UNAVAILABLE)).toHaveAttribute('role', 'alert');
    fireEvent.change(screen.getByRole('combobox', { name: 'School' }), { target: { value: '7' } });
    expect(await screen.findByText('Time slots are unavailable')).toHaveAttribute('role', 'alert');
    fireEvent.click(screen.getAllByRole('button', { name: /Retry/ })[0]);
    expect(await screen.findByRole('table', { name: /Default time slots/ })).toBeInTheDocument();
  });
});

describe('Groups', () => {
  it('saves the global internal approval group in its canonical form, and refuses anything but an invite', async () => {
    vi.mocked(setupApi.setInternalApprovalGroup).mockResolvedValue({ inviteUrl: INVITE, updatedAt: '2026-11-13T05:00:00Z', updatedBy: 1 });
    render(<NotebookAdminPanel />);
    fireEvent.click(tab('Groups'));
    const input = await screen.findByRole('textbox', { name: 'Internal approval group invite link' });
    expect(screen.getAllByText('Not set')).toHaveLength(1);
    fireEvent.change(input, { target: { value: 'https://wa.me/123' } });
    expect(screen.getByText(/Paste a WhatsApp group invite link/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save internal approval group link' })).toBeDisabled();
    fireEvent.change(input, { target: { value: ` HTTPS://Chat.WhatsApp.com/AbCdEf12345 ` } });
    fireEvent.click(screen.getByRole('button', { name: 'Save internal approval group link' }));
    await waitFor(() => expect(setupApi.setInternalApprovalGroup).toHaveBeenCalledWith(INVITE));
    expect(await screen.findByText('Internal approval group saved.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Open internal approval group/ })).toHaveAttribute('href', INVITE);
  });

  it('removes the global group when the link is emptied', async () => {
    vi.mocked(setupApi.internalApprovalGroup).mockResolvedValue({ inviteUrl: INVITE, updatedAt: null, updatedBy: null });
    vi.mocked(setupApi.setInternalApprovalGroup).mockResolvedValue({ inviteUrl: null, updatedAt: null, updatedBy: 1 });
    render(<NotebookAdminPanel />);
    fireEvent.click(tab('Groups'));
    fireEvent.change(await screen.findByRole('textbox', { name: 'Internal approval group invite link' }), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'Remove internal approval group link' }));
    await waitFor(() => expect(setupApi.setInternalApprovalGroup).toHaveBeenCalledWith(null));
    expect(await screen.findByText('Internal approval group removed.')).toBeInTheDocument();
  });

  it('edits a school\'s own group and approval group links', async () => {
    vi.mocked(setupApi.schoolGroups).mockResolvedValue({ SCHOOL_GROUP: INVITE });
    vi.mocked(setupApi.saveSchoolGroup).mockResolvedValue({ SCHOOL_GROUP: INVITE, INTERNAL_APPROVAL: 'https://chat.whatsapp.com/ZyXwVu98765' });
    render(<NotebookAdminPanel />);
    fireEvent.click(tab('Groups'));
    fireEvent.change(await screen.findByRole('combobox', { name: 'School' }), { target: { value: '7' } });
    expect(await screen.findByRole('link', { name: /Open school group/ })).toHaveAttribute('href', INVITE);
    fireEvent.change(screen.getByRole('textbox', { name: 'School internal approval group invite link' }), { target: { value: 'https://chat.whatsapp.com/ZyXwVu98765' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save school internal approval group link' }));
    await waitFor(() => expect(setupApi.saveSchoolGroup).toHaveBeenCalledWith(7, 'INTERNAL_APPROVAL', 'https://chat.whatsapp.com/ZyXwVu98765'));
    expect(await screen.findByText('School internal approval group link saved.')).toBeInTheDocument();
  });
});

describe('Designers and operators', () => {
  it('adds a designer and toggles one inactive', async () => {
    vi.mocked(setupApi.designers).mockResolvedValue([{ id: 1, name: 'Priya Singh', phone: '999', active: true }]);
    vi.mocked(setupApi.createDesigner).mockResolvedValue({ id: 2, name: 'New One', phone: '1', active: true });
    vi.mocked(setupApi.updateDesigner).mockResolvedValue({ id: 1, name: 'Priya Singh', phone: '999', active: false });
    render(<NotebookAdminPanel />);
    fireEvent.click(tab('Designers'));
    expect(await screen.findByText('Priya Singh')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Deactivate Priya Singh' }));
    await waitFor(() => expect(setupApi.updateDesigner).toHaveBeenCalledWith(1, { name: 'Priya Singh', phone: '999', active: false }));
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: ' New One ' } });
    fireEvent.change(screen.getByLabelText('Phone'), { target: { value: '1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add designer' }));
    await waitFor(() => expect(setupApi.createDesigner).toHaveBeenCalledWith('New One', '1'));
  });

  it('scopes an operator to an item at a school, and confirms a revoke before sending it', async () => {
    vi.mocked(setupApi.operators).mockResolvedValue([{ assignmentId: 9, userId: 3, fullName: 'Op Three', email: 'op3@x.test', schoolId: 7, catalogCategory: 'FLIERS' }]);
    vi.mocked(setupApi.revokeAssignment).mockResolvedValue(undefined);
    vi.mocked(setupApi.scopeOperator).mockResolvedValue(undefined);
    render(<NotebookAdminPanel />);
    fireEvent.click(tab('Operators'));
    expect(await screen.findByText('Op Three')).toBeInTheDocument();
    await waitFor(() => expect(within(screen.getByRole('row', { name: /Op Three/ })).getByText('Fliers')).toBeInTheDocument());
    const assign = screen.getByRole('button', { name: 'Assign scope' });
    fireEvent.change(await screen.findByLabelText('Operator'), { target: { value: '12' } });
    fireEvent.change(screen.getByLabelText('School to scope'), { target: { value: '7' } });
    expect(assign).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Catalogue item'), { target: { value: 'FLIERS' } });
    fireEvent.click(assign);
    await waitFor(() => expect(setupApi.scopeOperator).toHaveBeenCalledWith(12, 7, 'FLIERS'));

    fireEvent.click(screen.getByRole('button', { name: 'Revoke Op Three at School Seven' }));
    const dialog = screen.getByRole('dialog', { name: 'Revoke operator assignment' });
    expect(dialog).toHaveTextContent('Fliers orders at School Seven');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(setupApi.revokeAssignment).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Revoke Op Three at School Seven' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Revoke assignment' }));
    await waitFor(() => expect(setupApi.revokeAssignment).toHaveBeenCalledWith(3, 9));
    expect(await screen.findByText('Assignment revoked.')).toBeInTheDocument();
  });

  it('creates an item-specific operator and refreshes the lists', async () => {
    vi.mocked(setupApi.createScopedOperator).mockResolvedValue({ userId: 30, fullName: 'N', email: 'n@x.test', branchId: 7, branchName: 'School Seven' });
    render(<NotebookAdminPanel />);
    fireEvent.click(tab('Operators'));
    const create = await screen.findByRole('button', { name: 'Create operator' });
    expect(create).toBeDisabled();
    fireEvent.change(await screen.findByLabelText('School for new operator'), { target: { value: '7' } });
    fireEvent.change(await screen.findByLabelText('Catalogue item for new operator'), { target: { value: 'NOTEBOOKS' } });
    fireEvent.change(screen.getByLabelText('Full name'), { target: { value: ' New Op ' } });
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'new@x.test' } });
    fireEvent.change(screen.getByLabelText('Temporary password'), { target: { value: 'Temp#12345' } });
    fireEvent.click(create);
    await waitFor(() => expect(setupApi.createScopedOperator).toHaveBeenCalledWith(7, { fullName: 'New Op', email: 'new@x.test', temporaryPassword: 'Temp#12345' }, 'NOTEBOOKS'));
    expect(await screen.findByText('Operator created for that item at that school.')).toBeInTheDocument();
    expect(setupApi.operatorUsers).toHaveBeenCalledTimes(2);
  });

  it('says plainly that the service is down when a list fails on the server, and retries it', async () => {
    vi.mocked(setupApi.operators).mockRejectedValueOnce({ response: { status: 503, data: { message: 'Upstream connect error' } } })
      .mockResolvedValue([{ assignmentId: 9, userId: 3, fullName: 'Op Three', email: 'op3@x.test', schoolId: 7, catalogCategory: null }]);
    render(<NotebookAdminPanel />);
    fireEvent.click(tab('Operators'));
    expect(await screen.findByText(UNAVAILABLE)).toHaveAttribute('role', 'alert');
    expect(screen.queryByText(/Upstream connect error/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Retry/ }));
    expect(await screen.findByText('School-wide')).toBeInTheDocument();
  });
});

describe('set-up responses the screen cannot read', () => {
  it('rejects a non-list or non-object from every guarded call instead of showing an empty list', async () => {
    const actual = await vi.importActual<typeof import('../../../features/orders/vendors/api')>('../../../features/orders/vendors/api');
    vi.mocked(api.get).mockResolvedValue({ data: {} });
    vi.mocked(api.put).mockResolvedValue({ data: [] });
    const NOT_UNDERSTOOD = 'The response was not understood.';
    await expect(actual.setupApi.vendors()).rejects.toThrow(NOT_UNDERSTOOD);
    await expect(actual.setupApi.vendorCategories()).rejects.toThrow(NOT_UNDERSTOOD);
    await expect(actual.setupApi.designers()).rejects.toThrow(NOT_UNDERSTOOD);
    await expect(actual.setupApi.templates()).rejects.toThrow(NOT_UNDERSTOOD);
    await expect(actual.setupApi.timeSlots(7, 'NOTEBOOK_ORDER_TRACKER')).rejects.toThrow(NOT_UNDERSTOOD);
    await expect(actual.setupApi.operators()).rejects.toThrow(NOT_UNDERSTOOD);
    await expect(actual.setupApi.operatorUsers()).rejects.toThrow(NOT_UNDERSTOOD);
    await expect(actual.setupApi.schools()).rejects.toThrow(NOT_UNDERSTOOD);
    await expect(actual.setupApi.categories()).rejects.toThrow();
    await expect(actual.setupApi.setInternalApprovalGroup(null)).rejects.toThrow(NOT_UNDERSTOOD);
    vi.mocked(api.get).mockResolvedValue({ data: { SCHOOL_GROUP: 7 } });
    await expect(actual.setupApi.schoolGroups(7)).rejects.toThrow(NOT_UNDERSTOOD);
  });

  it('shows the not-understood state with Retry for each list a tab reads, and stays standing', async () => {
    // The real calls run against a transport that answers `{}` where a list belongs, as a misbehaving gateway can.
    const actual = await vi.importActual<typeof import('../../../features/orders/vendors/api')>('../../../features/orders/vendors/api');
    vi.mocked(api.get).mockResolvedValue({ data: {} });
    for (const key of ['schools', 'operators', 'operatorUsers', 'designers'] as const) {
      vi.mocked(setupApi[key]).mockImplementation(actual.setupApi[key] as never);
    }
    render(<NotebookAdminPanel />);
    fireEvent.click(tab('Operators'));
    // Schools, assignments and operators each fail on their own, with a Retry each.
    await waitFor(() => expect(screen.getAllByText('The response was not understood.')).toHaveLength(3));
    expect(screen.getAllByRole('button', { name: /Retry/ })).toHaveLength(3);
    expect(screen.queryByText('No operator assignments.')).not.toBeInTheDocument();
    fireEvent.click(tab('Designers'));
    await waitFor(() => expect(screen.getAllByText('The response was not understood.')).toHaveLength(2));
    expect(screen.queryByText('No designers yet.')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Workflow set-up' })).toBeInTheDocument();
  });
});
