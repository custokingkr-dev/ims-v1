import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { trackerApi } from '../../../features/tracker/api';
import { NotebookAdminPanel } from './NotebookAdminPanel';
import { NotebookWorklistPanel } from './NotebookWorklistPanel';
import { SaAllOrdersPanel } from './SaAllOrdersPanel';
import { catalogClient } from '../../../services/catalogApi';

// The test run loads no stylesheets, so the layout rule a time slot row relies on is read from its source.
const { readFileSync } = await vi.importActual<{ readFileSync: (path: string, encoding: string) => string }>('node:fs');
const { cwd } = await vi.importActual<{ cwd: () => string }>('node:process');
const trackerCss = readFileSync(`${cwd()}/src/features/tracker/tracker.css`, 'utf8');

const auth = vi.hoisted(() => ({ user: { role: 'OPERATIONS', userId: 42 } }));
vi.mock('../../../contexts/AuthContext', () => ({ useAuth: () => auth }));
vi.mock('../../../features/tracker/NotebookTracker', () => ({ NotebookTracker: ({ orderId }: { orderId: string }) => <div>tracker for {orderId}</div> }));
vi.mock('../../../features/tracker/OrderDetailEntry', () => ({ OrderDetailEntry: ({ orderId, trackerManaged }: { orderId: string; trackerManaged: boolean }) => <div>order {orderId} on tracker: {String(trackerManaged)}</div> }));
vi.mock('../../../features/catalog/useCategoryLabel', () => ({ useCategoryLabel: () => (code?: string | null) => String(code ?? '') }));
vi.mock('../../../services/catalogApi', () => ({ catalogClient: { getOrderPage: vi.fn(), getOrderSummary: vi.fn() } }));
vi.mock('../../../features/tracker/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../features/tracker/api')>();
  return { ...actual, trackerApi: { worklist: vi.fn(), operators: vi.fn(), operatorUsers: vi.fn(), createScopedOperator: vi.fn(), scopeOperator: vi.fn(), revokeAssignment: vi.fn(), schools: vi.fn(), categories: vi.fn(), designers: vi.fn(), createDesigner: vi.fn(), updateDesigner: vi.fn(), timeSlots: vi.fn(), setTimeSlot: vi.fn(), clearTimeSlot: vi.fn() } };
});

const UNAVAILABLE = 'The notebook order tracker is not available right now. Try again in a moment.';
const row = (orderId: string, over = {}) => ({ orderId, schoolId: 7, schoolName: 'School Seven', customised: true, orderedCount: 2500, placedAt: '2026-10-19T04:00:00Z',
  timeZone: 'Asia/Kolkata', status: 'PRINTING_PRESS', currentStep: 7, currentTitle: 'Printing Press', assigneeUserId: 42, dueAt: '2026-10-19T09:00:00Z', overdue: false, completed: 6, total: 12, ...over });
beforeEach(() => { vi.clearAllMocks(); auth.user = { role: 'OPERATIONS', userId: 42 }; vi.mocked(trackerApi.categories).mockResolvedValue([{ code: 'NOTEBOOKS', label: 'Notebooks' }, { code: 'TROPHIES', label: 'Trophies' }]); vi.mocked(trackerApi.operatorUsers).mockResolvedValue([{ userId: 12, fullName: 'Op Twelve', email: 'op12@x.test' }]); vi.mocked(trackerApi.schools).mockResolvedValue([{ id: 7, name: 'School Seven' }]); });
afterEach(cleanup);

it('lists my notebook orders soonest first, flags overdue ones and opens the tracker', async () => {
  vi.mocked(trackerApi.worklist).mockResolvedValue([row('CK-1', { overdue: true }), row('CK-2', { assigneeUserId: null })]);
  render(<NotebookWorklistPanel />);
  expect(await screen.findByText('CK-1')).toBeInTheDocument();
  expect(trackerApi.worklist).toHaveBeenCalledWith({ scope: 'mine', includeFinished: false });
  expect(screen.getAllByText('Printing press')).toHaveLength(2);
  expect(screen.getByText(/^Overdue · /)).toHaveClass('ck-trk-late');
  expect(screen.getByText(/unassigned/)).toBeInTheDocument();
  expect(screen.getAllByText('6 of 12')).toHaveLength(2);
  fireEvent.click(screen.getAllByRole('button', { name: 'Open' })[0]);
  expect(await screen.findByText('tracker for CK-1')).toBeInTheDocument();
});

it("shows each deadline in its school's time zone, whatever the browser's zone is", async () => {
  // 20:00 UTC on 19 October is already 01:30 on 20 October in Kolkata, and still 16:00 on the 19th in New York.
  vi.mocked(trackerApi.worklist).mockResolvedValue([
    row('CK-1', { dueAt: '2026-10-19T20:00:00Z', timeZone: 'Asia/Kolkata' }),
    row('CK-2', { dueAt: '2026-10-19T20:00:00Z', timeZone: 'America/New_York', overdue: true }),
  ]);
  render(<NotebookWorklistPanel />);
  const due = async (orderId: string) => within((await screen.findByText(orderId)).closest('tr')!).getAllByRole('cell')[4];
  expect(await due('CK-1')).toHaveTextContent(/^20 Oct 2026,\s1:30\sam$/);
  expect(await due('CK-2')).toHaveTextContent(/^Overdue · 19 Oct 2026,\s4:00\spm$/);
});

it('groups the worklist filters under a legend', async () => {
  vi.mocked(trackerApi.worklist).mockResolvedValue([]);
  render(<NotebookWorklistPanel />);
  const group = await screen.findByRole('group', { name: 'Show orders' });
  expect(within(group).getByRole('radio', { name: 'Assigned to me' })).toBeChecked();
  expect(within(group).getByRole('radio', { name: 'All' })).not.toBeChecked();
});

it('asks for every order or finished ones when the filters change, and explains an empty list', async () => {
  vi.mocked(trackerApi.worklist).mockResolvedValue([]);
  render(<NotebookWorklistPanel />);
  expect(await screen.findByText('No notebook orders assigned to you.')).toBeInTheDocument();
  fireEvent.click(screen.getByLabelText('All'));
  await waitFor(() => expect(trackerApi.worklist).toHaveBeenLastCalledWith({ scope: 'all', includeFinished: false }));
  fireEvent.click(screen.getByLabelText('Include completed'));
  await waitFor(() => expect(trackerApi.worklist).toHaveBeenLastCalledWith({ scope: 'all', includeFinished: true }));
});

it('shows the server reason when the worklist cannot load', async () => {
  vi.mocked(trackerApi.worklist).mockRejectedValue({ response: { status: 403, data: { message: 'Operations access required' } } });
  render(<NotebookWorklistPanel />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Operations access required');
});

it('says plainly that the tracker is not available when the service is down, and loads again on Refresh', async () => {
  vi.mocked(trackerApi.worklist).mockRejectedValueOnce({ response: { status: 502, data: { message: 'Gateway upstream error' } } }).mockResolvedValue([row('CK-1')]);
  render(<NotebookWorklistPanel />);
  expect(await screen.findByRole('alert')).toHaveTextContent(UNAVAILABLE);
  expect(screen.queryByText(/Gateway upstream error/)).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
  expect(await screen.findByText('CK-1')).toBeInTheDocument();
});

it("opens superadmin's worklist on every order, since superadmin is never the one assigned", async () => {
  auth.user = { role: 'SUPERADMIN', userId: 1 };
  vi.mocked(trackerApi.worklist).mockResolvedValue([row('CK-1', { assigneeUserId: 42 })]);
  render(<NotebookWorklistPanel />);
  expect(await screen.findByText('CK-1')).toBeInTheDocument();
  expect(trackerApi.worklist).toHaveBeenCalledWith({ scope: 'all', includeFinished: false });
  expect(trackerApi.worklist).not.toHaveBeenCalledWith(expect.objectContaining({ scope: 'mine' }));
  const group = screen.getByRole('group', { name: 'Show orders' });
  expect(within(group).getByRole('radio', { name: 'All' })).toBeChecked();
  fireEvent.click(within(group).getByRole('radio', { name: 'Assigned to me' }));
  await waitFor(() => expect(trackerApi.worklist).toHaveBeenLastCalledWith({ scope: 'mine', includeFinished: false }));
});

it('scopes an operator to notebooks at a chosen school and revokes an assignment', async () => {
  vi.mocked(trackerApi.operators).mockResolvedValue([{ assignmentId: 9, userId: 3, fullName: 'Op Three', email: 'op3@x.test', schoolId: 7, catalogCategory: null }]);
  vi.mocked(trackerApi.revokeAssignment).mockResolvedValue(undefined);
  render(<NotebookAdminPanel />);
  expect(await screen.findByText('Op Three')).toBeInTheDocument();
  expect(screen.getByText('School-wide')).toBeInTheDocument();
  const assign = screen.getByRole('button', { name: 'Assign scope' });
  expect(assign).toBeDisabled();
  fireEvent.change(await screen.findByLabelText('Operator'), { target: { value: '12' } });
  fireEvent.change(await screen.findByLabelText('School to scope'), { target: { value: '7' } });
  expect(assign).toBeDisabled();
  fireEvent.change(await screen.findByLabelText('Catalogue item'), { target: { value: 'TROPHIES' } });
  fireEvent.click(assign);
  await waitFor(() => expect(trackerApi.scopeOperator).toHaveBeenCalledWith(12, 7, 'TROPHIES'));
});

it('confirms a revoke in the page, sends nothing when cancelled, and revokes once confirmed', async () => {
  const confirm = vi.spyOn(window, 'confirm');
  vi.mocked(trackerApi.operators).mockResolvedValue([{ assignmentId: 9, userId: 3, fullName: 'Op Three', email: 'op3@x.test', schoolId: 7, catalogCategory: 'NOTEBOOKS' }]);
  vi.mocked(trackerApi.revokeAssignment).mockResolvedValue(undefined);
  render(<NotebookAdminPanel />);
  fireEvent.click(await screen.findByRole('button', { name: /^Revoke/ }));
  const dialog = screen.getByRole('dialog', { name: 'Revoke operator assignment' });
  expect(dialog).toHaveTextContent('Op Three');
  expect(dialog).toHaveTextContent('School Seven');
  fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(trackerApi.revokeAssignment).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: /^Revoke/ }));
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Revoke assignment' }));
  await waitFor(() => expect(trackerApi.revokeAssignment).toHaveBeenCalledWith(3, 9));
  expect(await screen.findByText('Assignment revoked.')).toBeInTheDocument();
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(confirm).not.toHaveBeenCalled();
  confirm.mockRestore();
});

it('reports set-up lists that cannot load instead of showing them as empty', async () => {
  vi.mocked(trackerApi.operators).mockRejectedValue({ response: { data: { message: 'Identity is unavailable' } } });
  vi.mocked(trackerApi.designers).mockRejectedValue({ response: { data: { message: 'Designers are unavailable' } } });
  vi.mocked(trackerApi.schools).mockResolvedValue([{ id: 7, name: 'School Seven' }]);
  vi.mocked(trackerApi.timeSlots).mockRejectedValue({ response: { data: { message: 'Time slots are unavailable' } } });
  render(<NotebookAdminPanel />);
  expect(await screen.findByText('Identity is unavailable')).toHaveAttribute('role', 'alert');
  expect(screen.queryByText('No operator assignments.')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('tab', { name: 'Designers' }));
  expect(await screen.findByText('Designers are unavailable')).toHaveAttribute('role', 'alert');
  expect(screen.queryByText('No designers yet.')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('tab', { name: 'Time slots' }));
  fireEvent.change(await screen.findByLabelText('School'), { target: { value: '7' } });
  expect(await screen.findByText('Time slots are unavailable')).toHaveAttribute('role', 'alert');
  expect(screen.queryByRole('table')).not.toBeInTheDocument();
});

it('says plainly that the tracker is not available when a set-up list fails on the server, and retries it', async () => {
  vi.mocked(trackerApi.operators).mockRejectedValueOnce({ response: { status: 503, data: { message: 'Upstream connect error' } } })
    .mockResolvedValue([{ assignmentId: 9, userId: 3, fullName: 'Op Three', email: 'op3@x.test', schoolId: 7, catalogCategory: null }]);
  render(<NotebookAdminPanel />);
  expect(await screen.findByText(UNAVAILABLE)).toHaveAttribute('role', 'alert');
  expect(screen.queryByText(/Upstream connect error/)).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /Retry/ }));
  expect(await screen.findByText('Op Three')).toBeInTheDocument();
});

it('reports a school list that cannot load', async () => {
  vi.mocked(trackerApi.operators).mockResolvedValue([]);
  vi.mocked(trackerApi.schools).mockRejectedValue({ response: { data: { message: 'Schools are unavailable' } } });
  render(<NotebookAdminPanel />);
  expect(await screen.findByText('Schools are unavailable')).toHaveAttribute('role', 'alert');
});

it('moves between set-up sections with the arrow keys and labels each panel by its tab', async () => {
  vi.mocked(trackerApi.operators).mockResolvedValue([]);
  vi.mocked(trackerApi.designers).mockResolvedValue([]);
  render(<NotebookAdminPanel />);
  const tab = (name: string) => screen.getByRole('tab', { name });
  expect(screen.getByRole('tabpanel', { name: 'Operators' })).toBeInTheDocument();
  expect(tab('Operators')).toHaveAttribute('tabindex', '0');
  expect(tab('Designers')).toHaveAttribute('tabindex', '-1');
  fireEvent.keyDown(tab('Operators'), { key: 'ArrowRight' });
  expect(tab('Designers')).toHaveAttribute('aria-selected', 'true');
  expect(tab('Designers')).toHaveFocus();
  expect(screen.getByRole('tabpanel', { name: 'Designers' })).toBeInTheDocument();
  fireEvent.keyDown(tab('Designers'), { key: 'End' });
  expect(tab('Time slots')).toHaveFocus();
  fireEvent.keyDown(tab('Time slots'), { key: 'ArrowRight' });
  expect(tab('Operators')).toHaveFocus();
  fireEvent.keyDown(tab('Operators'), { key: 'ArrowLeft' });
  expect(tab('Time slots')).toHaveAttribute('aria-selected', 'true');
  fireEvent.keyDown(tab('Time slots'), { key: 'Home' });
  expect(tab('Operators')).toHaveFocus();
  expect(await screen.findByRole('tabpanel', { name: 'Operators' })).toBeInTheDocument();
});

it('creates a notebook-only operator at a school and refreshes the lists', async () => {
  vi.mocked(trackerApi.operators).mockResolvedValue([]);
  vi.mocked(trackerApi.createScopedOperator).mockResolvedValue({ userId: 30, fullName: 'N', email: 'n@x.test', branchId: 7, branchName: 'School Seven' });
  render(<NotebookAdminPanel />);
  const create = await screen.findByRole('button', { name: 'Create operator' });
  expect(create).toBeDisabled();
  fireEvent.change(await screen.findByLabelText('School for new operator'), { target: { value: '7' } });
  fireEvent.change(await screen.findByLabelText('Catalogue item for new operator'), { target: { value: 'NOTEBOOKS' } });
  fireEvent.change(screen.getByLabelText('Full name'), { target: { value: ' New Op ' } });
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'new@x.test' } });
  fireEvent.change(screen.getByLabelText('Temporary password'), { target: { value: 'Temp#12345' } });
  fireEvent.click(create);
  await waitFor(() => expect(trackerApi.createScopedOperator).toHaveBeenCalledWith(7, { fullName: 'New Op', email: 'new@x.test', temporaryPassword: 'Temp#12345' }, 'NOTEBOOKS'));
  expect(await screen.findByText('Operator created for that item at that school.')).toBeInTheDocument();
  expect(trackerApi.operatorUsers).toHaveBeenCalledTimes(2);
});

it('saves a school time slot and returns an overridden one to the default', async () => {
  vi.mocked(trackerApi.operators).mockResolvedValue([]);
  vi.mocked(trackerApi.timeSlots).mockResolvedValue([
    { step: 1, title: 'Order Details', sla: { value: 4, unit: 'HOURS', source: 'DEFAULT' } },
    { step: 2, title: 'Designing Activity', sla: { value: 3, unit: 'WORKING_DAYS', source: 'SCHOOL' } },
    { step: 8, title: 'Pre-Print Quality Gate', sla: null },
  ]);
  render(<NotebookAdminPanel />);
  fireEvent.click(screen.getByRole('tab', { name: 'Time slots' }));
  fireEvent.change(await screen.findByLabelText('School'), { target: { value: '7' } });
  expect(await screen.findByText('School override')).toBeInTheDocument();
  expect(screen.getByText('No timer')).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('Value for step 1'), { target: { value: '6' } });
  fireEvent.click(screen.getAllByRole('button', { name: 'Save' })[0]);
  await waitFor(() => expect(trackerApi.setTimeSlot).toHaveBeenCalledWith(7, 1, 6, 'HOURS'));
  fireEvent.click(screen.getByRole('button', { name: 'Use default' }));
  await waitFor(() => expect(trackerApi.clearTimeSlot).toHaveBeenCalledWith(7, 2));
});

it("keeps a time slot's value, unit and Save on one line, with every control still labelled", async () => {
  vi.mocked(trackerApi.operators).mockResolvedValue([]);
  vi.mocked(trackerApi.timeSlots).mockResolvedValue([
    { step: 1, title: 'Order Details', sla: { value: 4, unit: 'HOURS', source: 'DEFAULT' } },
    { step: 2, title: 'Designing Activity', sla: { value: 3, unit: 'WORKING_DAYS', source: 'SCHOOL' } },
  ]);
  render(<NotebookAdminPanel />);
  fireEvent.click(screen.getByRole('tab', { name: 'Time slots' }));
  fireEvent.change(await screen.findByLabelText('School'), { target: { value: '7' } });
  for (const step of [1, 2]) {
    const value = await screen.findByLabelText(`Value for step ${step}`);
    const unit = screen.getByLabelText(`Unit for step ${step}`);
    const save = within(value.closest('tr')!).getByRole('button', { name: 'Save' });
    // One wrapping line inside the cell: the cell stays a table cell, and the select no longer takes the full width.
    const line = value.parentElement!;
    expect(line.tagName).toBe('DIV');
    expect(line).toHaveClass('ck-trk-slot-edit');
    expect(unit.parentElement).toBe(line);
    expect(save.parentElement).toBe(line);
    expect(unit).toHaveClass('ck-trk-slot-unit');
  }
  expect(within(screen.getByLabelText('Value for step 2').closest('tr')!).getByRole('button', { name: 'Use default' })).toBeInTheDocument();
  expect(document.querySelectorAll('td.ck-trk-actions')).toHaveLength(0);
  expect(trackerCss).toMatch(/\.ck-trk-slot-edit\s*\{[^}]*display:\s*flex;[^}]*flex-wrap:\s*wrap;/);
  expect(trackerCss).toMatch(/\.ck-trk \.ck-trk-slot-unit\s*\{[^}]*width:\s*auto;/);
});

it('adds a designer and toggles one inactive', async () => {
  vi.mocked(trackerApi.operators).mockResolvedValue([]);
  vi.mocked(trackerApi.designers).mockResolvedValue([{ id: 1, name: 'Priya Singh', phone: '999', active: true }]);
  vi.mocked(trackerApi.createDesigner).mockResolvedValue({ id: 2, name: 'New One', phone: '1', active: true });
  render(<NotebookAdminPanel />);
  fireEvent.click(screen.getByRole('tab', { name: 'Designers' }));
  expect(await screen.findByText('Priya Singh')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }));
  await waitFor(() => expect(trackerApi.updateDesigner).toHaveBeenCalledWith(1, { name: 'Priya Singh', phone: '999', active: false }));
  fireEvent.change(screen.getByLabelText('Name'), { target: { value: ' New One ' } });
  fireEvent.change(screen.getByLabelText('Phone'), { target: { value: '1' } });
  fireEvent.click(screen.getByRole('button', { name: 'Add designer' }));
  await waitFor(() => expect(trackerApi.createDesigner).toHaveBeenCalledWith('New One', '1'));
});

it('opens an order on the tracker as a tracker order, and any other placed form order as an ordinary one', async () => {
  const order = (id: string, over = {}) => ({ id, schoolId: 7, schoolName: 'School Seven', category: 'NOTEBOOKS', status: 'ORDER_DETAILS', formVersion: 2, totalAmount: 0, ...over });
  vi.mocked(catalogClient.getOrderPage).mockResolvedValue({ content: [order('CK-7', { trackerManaged: true }), order('ORD-8', { status: 'PROCESSING' })], page: 0, size: 200, totalElements: 2, totalPages: 1 });
  vi.mocked(catalogClient.getOrderSummary).mockResolvedValue({ totalOrders: 2, pendingApproval: 0, approved: 0, rejected: 0, gmv: 0, activeOrders: 2, termSpend: 0, activeServices: 0, deliveredCount: 0 });
  const { unmount } = render(<SaAllOrdersPanel onNewOrder={vi.fn()} />);
  fireEvent.click(within((await screen.findByText('CK-7')).closest('tr')!).getByRole('button', { name: 'View' }));
  expect(await screen.findByText('order CK-7 on tracker: true')).toBeInTheDocument();
  unmount();
  render(<SaAllOrdersPanel onNewOrder={vi.fn()} />);
  fireEvent.click(within((await screen.findByText('ORD-8')).closest('tr')!).getByRole('button', { name: 'View' }));
  expect(await screen.findByText('order ORD-8 on tracker: false')).toBeInTheDocument();
});
