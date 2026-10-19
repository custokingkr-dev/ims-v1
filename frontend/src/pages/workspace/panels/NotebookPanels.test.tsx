import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import api from '../../../services/api';
import { trackerApi } from '../../../features/tracker/api';
import { NotebookAdminPanel } from './NotebookAdminPanel';
import { NotebookWorklistPanel } from './NotebookWorklistPanel';

vi.mock('../../../contexts/AuthContext', () => ({ useAuth: () => ({ user: { role: 'OPERATIONS', userId: 42 } }) }));
vi.mock('../../../services/api', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } }));
vi.mock('../../../features/tracker/NotebookTracker', () => ({ NotebookTracker: ({ orderId }: { orderId: string }) => <div>tracker for {orderId}</div> }));
vi.mock('../../../features/tracker/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../features/tracker/api')>();
  return { ...actual, trackerApi: { worklist: vi.fn(), operators: vi.fn(), operatorUsers: vi.fn(), createScopedOperator: vi.fn(), scopeOperator: vi.fn(), categories: vi.fn(), designers: vi.fn(), createDesigner: vi.fn(), updateDesigner: vi.fn(), timeSlots: vi.fn(), setTimeSlot: vi.fn(), clearTimeSlot: vi.fn() } };
});

const row = (orderId: string, over = {}) => ({ orderId, schoolId: 7, schoolName: 'School Seven', customised: true, orderedCount: 2500, placedAt: '2026-10-19T04:00:00Z',
  status: 'PRINTING_PRESS', currentStep: 7, currentTitle: 'Printing Press', assigneeUserId: 42, dueAt: '2026-10-19T09:00:00Z', overdue: false, completed: 6, total: 12, ...over });
beforeEach(() => { vi.clearAllMocks(); vi.mocked(trackerApi.categories).mockResolvedValue([{ code: 'NOTEBOOKS', label: 'Notebooks' }, { code: 'TROPHIES', label: 'Trophies' }]); vi.mocked(trackerApi.operatorUsers).mockResolvedValue([{ userId: 12, fullName: 'Op Twelve', email: 'op12@x.test' }]); vi.mocked(api.get).mockResolvedValue({ data: [{ id: 7, name: 'School Seven' }] }); });
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
  vi.mocked(trackerApi.worklist).mockRejectedValue({ response: { data: { message: 'Operations access required' } } });
  render(<NotebookWorklistPanel />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Operations access required');
});

it('scopes an operator to notebooks at a chosen school and revokes an assignment', async () => {
  vi.mocked(trackerApi.operators).mockResolvedValue([{ assignmentId: 9, userId: 3, fullName: 'Op Three', email: 'op3@x.test', schoolId: 7, catalogCategory: null }]);
  vi.mocked(api.delete).mockResolvedValue({ data: {} });
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
  fireEvent.click(screen.getByRole('button', { name: 'Revoke' }));
  await waitFor(() => expect(api.delete).toHaveBeenCalledWith('/rbac/users/3/roles/9'));
});

it('creates a notebook-only operator at a school and refreshes the lists', async () => {
  vi.mocked(trackerApi.operators).mockResolvedValue([]);
  vi.mocked(trackerApi.createScopedOperator).mockResolvedValue({ userId: 30 });
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
