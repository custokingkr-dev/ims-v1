import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NotebookWorklistPanel } from '../../../pages/workspace/panels/NotebookWorklistPanel';
import { clearWorkflowCaches, workflowApi } from '../workflow/api';
import type { OrderWorkflowListItem, OrderWorkflowPage, WorkflowTemplate } from '../workflow/types';
import { OrderWorkflowList } from './OrderWorkflowList';

const auth = vi.hoisted(() => ({ user: { role: 'OPERATIONS', userId: 42, fullName: 'Asha Operator', operatorSchools: [7], operatorScopes: ['8:FLIERS'] } as Record<string, unknown> }));
vi.mock('../../../contexts/AuthContext', () => ({ useAuth: () => ({ user: auth.user }) }));
vi.mock('../workflow', () => ({
  OrderDetailEntry: ({ orderId, onBack, backLabel }: { orderId: string; onBack: () => void; backLabel?: string }) => (
    <div><p>workflow of {orderId}</p><button type="button" onClick={onBack}>{backLabel}</button></div>
  ),
}));
vi.mock('../workflow/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../workflow/api')>();
  return { ...actual, workflowApi: { list: vi.fn(), templates: vi.fn(), categories: vi.fn(), operators: vi.fn(), schools: vi.fn(), orderIdByNumber: vi.fn() } };
});

const api = vi.mocked(workflowApi);
const NOW = '2026-11-13T06:30:00Z';
const HOUR = 3_600_000;
const at = (hours: number) => new Date(Date.parse(NOW) + hours * HOUR).toISOString();

function row(orderId: string, over: Partial<OrderWorkflowListItem> = {}): OrderWorkflowListItem {
  return {
    orderId, orderNumber: `NB-DPS_GUR-26-11-13-00${orderId.slice(-2)}`, definitionId: 'NOTEBOOK_ORDER_TRACKER', categoryCode: 'NOTEBOOKS', itemCode: 'NB',
    schoolId: 7, schoolName: 'Delhi Public School', customised: true, orderedCount: 15600, placedAt: at(-48), timeZone: 'Asia/Kolkata', status: 'PRODUCTION_PLANNING',
    phase: 'PLANNING', currentStep: 5, currentTitle: 'Production Planning', assigneeUserId: 42, assigneeName: 'Asha Operator', dueAt: at(3.005), overdue: false,
    slaState: 'DUE_SOON', completed: 4, total: 12, ...over,
  };
}
function page(items: OrderWorkflowListItem[], total = items.length): OrderWorkflowPage {
  return { items, total, counts: { open: 12, overdue: 3, dueToday: 4, unassigned: 2, mine: 5 }, now: NOW };
}
const template = (definitionId: WorkflowTemplate['definitionId'], steps: Array<[number, string, string]>): WorkflowTemplate => ({
  definitionId, title: definitionId, introMessage: '', steps: steps.map(([order, code, title]) => ({
    order, code, title, description: '', phase: 'INTAKE', defaultSla: null, customisedOnly: false, icon: '', tabs: [], blocks: [], fields: {}, checklists: {}, gates: [], signOff: null, superadminCompletes: false,
  })),
});

function Url() {
  const location = useLocation();
  return <output aria-label="URL">{location.search}</output>;
}
function renderList(url = '/dashboard?panel=nb-work', onOpen = vi.fn()) {
  render(
    <MemoryRouter initialEntries={[url]}>
      <Routes><Route path="/dashboard" element={<><OrderWorkflowList onOpen={onOpen} /><Url /></>} /></Routes>
    </MemoryRouter>,
  );
  return { onOpen };
}
const url = () => new URLSearchParams(screen.getByLabelText('URL').textContent ?? '');

beforeEach(() => {
  vi.clearAllMocks();
  clearWorkflowCaches();
  auth.user = { role: 'OPERATIONS', userId: 42, fullName: 'Asha Operator', operatorSchools: [7], operatorScopes: ['8:FLIERS'] };
  api.list.mockResolvedValue(page([row('CK-1015'), row('CK-1016', { orderNumber: null, assigneeUserId: null, assigneeName: null, dueAt: at(-53), overdue: true, slaState: 'OVERDUE' })]));
  api.templates.mockResolvedValue([template('NOTEBOOK_ORDER_TRACKER', [[1, 'ORDER_DETAILS', 'Order Details'], [8, 'PRINTING_PRESS', 'Printing Press']])]);
  api.categories.mockResolvedValue([]);
  api.operators.mockResolvedValue([{ userId: 51, fullName: 'Ravi Operator' }]);
  api.schools.mockResolvedValue([{ id: 7, name: 'Delhi Public School' }, { id: 8, name: 'Green Valley' }]);
});
afterEach(cleanup);

describe('the order workflows list', () => {
  it('lists orders with number, reference, stage, due state and point of contact, soonest due first', async () => {
    const { onOpen } = renderList();
    const table = await screen.findByRole('table', { name: 'Orders in production' });
    expect(api.list).toHaveBeenCalledWith({ sort: 'due', limit: 50, offset: 0 });
    const first = within(table).getAllByRole('row')[1];
    expect(first).toHaveTextContent('NB-DPS_GUR-26-11-13-0015');
    expect(first).toHaveTextContent('Ref CK-1015');
    expect(first).toHaveTextContent('5 · Production Planning');
    expect(first).toHaveTextContent('4 of 12');
    expect(within(first).getByText('Due in 3h')).toHaveClass('is-warning');
    const second = within(table).getAllByRole('row')[2];
    expect(second).toHaveTextContent('CK-1016');
    expect(second).not.toHaveTextContent('Ref ');
    expect(within(second).getByText('Overdue 2d 5h')).toHaveClass('is-danger');
    expect(within(second).getByText('Unassigned')).toBeInTheDocument();
    fireEvent.click(within(first).getByRole('button', { name: 'Open Order NB-DPS_GUR-26-11-13-0015, reference CK-1015' }));
    expect(onOpen).toHaveBeenCalledWith('CK-1015');
    fireEvent.click(second);
    expect(onOpen).toHaveBeenLastCalledWith('CK-1016');
  });

  it('reads its filters from the URL and sends them to the server', async () => {
    renderList('/dashboard?panel=nb-work&q=dps&item=NB,FE&phase=PRODUCTION&assignee=unassigned&sla=OVERDUE&done=1&sort=placed&page=1');
    await screen.findByRole('table');
    expect(api.list).toHaveBeenCalledWith({ q: 'dps', item: 'NB,FE', phase: 'PRODUCTION', assignee: 'unassigned', sla: 'OVERDUE', includeFinished: true, sort: 'placed', limit: 50, offset: 50 });
    expect(screen.getByRole('searchbox', { name: 'Search' })).toHaveValue('dps');
    expect(screen.getByRole('button', { name: 'Notebooks (NB)' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Fliers (FE)' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByLabelText('SLA')).toHaveValue('OVERDUE');
    expect(screen.getByLabelText('Assignee')).toHaveValue('unassigned');
    expect(screen.getByLabelText('Stage')).toHaveValue('phase:PRODUCTION');
    expect(screen.getByRole('checkbox', { name: 'Include completed' })).toBeChecked();
  });

  it('writes each filter change back to the URL and starts again from the first page', async () => {
    renderList('/dashboard?panel=nb-work&page=2');
    await screen.findByRole('table');
    fireEvent.change(screen.getByLabelText('SLA'), { target: { value: 'DUE_SOON' } });
    await waitFor(() => expect(url().get('sla')).toBe('DUE_SOON'));
    expect(url().get('page')).toBeNull();
    expect(url().get('panel')).toBe('nb-work');
    await waitFor(() => expect(api.list).toHaveBeenLastCalledWith({ sla: 'DUE_SOON', sort: 'due', limit: 50, offset: 0 }));
    fireEvent.click(screen.getByRole('button', { name: 'Notebooks (NB)' }));
    await waitFor(() => expect(url().get('item')).toBe('NB'));
    // With exactly one item, Stage lists that item's steps.
    await waitFor(() => expect(within(screen.getByLabelText('Stage')).getByRole('option', { name: '8 · Printing Press' })).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText('Stage'), { target: { value: 'step:PRINTING_PRESS' } });
    await waitFor(() => expect(url().get('step')).toBe('PRINTING_PRESS'));
    await waitFor(() => expect(api.list).toHaveBeenLastCalledWith({ item: 'NB', step: 'PRINTING_PRESS', sla: 'DUE_SOON', sort: 'due', limit: 50, offset: 0 }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Include completed' }));
    await waitFor(() => expect(url().get('done')).toBe('1'));
  });

  it('searches on the server a moment after typing stops', async () => {
    renderList();
    await screen.findByRole('table');
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search' }), { target: { value: 'NB-DPS' } });
    await waitFor(() => expect(url().get('q')).toBe('NB-DPS'));
    await waitFor(() => expect(api.list).toHaveBeenLastCalledWith({ q: 'NB-DPS', sort: 'due', limit: 50, offset: 0 }));
  });

  it('applies a summary chip\'s filter, and offers Mine only to operators', async () => {
    renderList();
    await screen.findByRole('table');
    const chips = screen.getByRole('group', { name: 'Summary' });
    expect(within(chips).getByRole('button', { name: /Open 12/ })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(within(chips).getByRole('button', { name: /Overdue 3/ }));
    await waitFor(() => expect(url().get('sla')).toBe('OVERDUE'));
    fireEvent.click(within(chips).getByRole('button', { name: /Mine 5/ }));
    await waitFor(() => expect(url().get('assignee')).toBe('me'));
    // Due today lists what it counts: the server's DUE_TODAY filter (the school's own calendar day).
    const today = within(chips).getByRole('button', { name: /Due today 4/ });
    expect(today).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(today);
    await waitFor(() => expect(url().get('sla')).toBe('DUE_TODAY'));
    await waitFor(() => expect(api.list).toHaveBeenLastCalledWith({ assignee: 'me', sla: 'DUE_TODAY', sort: 'due', limit: 50, offset: 0 }));
    expect(today).toHaveAttribute('aria-pressed', 'true');
    expect(within(chips).getByRole('button', { name: /Overdue 3/ })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByLabelText('SLA')).toHaveValue('DUE_TODAY');
    // An operator at two schools may narrow by school; the names come from the orders they can see.
    expect(within(screen.getByLabelText('School')).getAllByRole('option').map((option) => option.textContent)).toEqual(['All schools', 'Delhi Public School', 'School 8']);
  });

  it('lets superadmin pick a named operator and any school, and has no Mine chip', async () => {
    auth.user = { role: 'SUPERADMIN', userId: 1, fullName: 'Super Admin' };
    renderList();
    await screen.findByRole('table');
    expect(screen.queryByRole('button', { name: /Mine/ })).not.toBeInTheDocument();
    await waitFor(() => expect(within(screen.getByLabelText('Assignee')).getByRole('option', { name: 'Ravi Operator' })).toBeInTheDocument());
    expect(within(screen.getByLabelText('Assignee')).queryByRole('option', { name: 'Me' })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('School'), { target: { value: '8' } });
    await waitFor(() => expect(url().get('school')).toBe('8'));
    await waitFor(() => expect(api.list).toHaveBeenLastCalledWith({ schoolId: 8, sort: 'due', limit: 50, offset: 0 }));
  });

  it('shows the board as five phase columns, each with its count and cards', async () => {
    api.list.mockResolvedValue(page([
      row('CK-1001', { phase: 'INTAKE', currentStep: 2, currentTitle: 'Designing Activity' }),
      row('CK-1002', { phase: 'PRODUCTION', currentStep: 8, currentTitle: 'Printing Press', assigneeName: 'Ravi Kumar' }),
      row('CK-1003', { phase: 'PRODUCTION', currentStep: 10, currentTitle: 'Production Follow-up' }),
    ]));
    renderList('/dashboard?panel=nb-work&view=board');
    const production = await screen.findByRole('region', { name: 'Production, 2 orders' });
    expect(api.list).toHaveBeenCalledWith({ sort: 'due', limit: 100, offset: 0 });
    const columns = ['Intake & design, 1 orders', 'Planning, 0 orders', 'Production, 2 orders', 'Verification, 0 orders', 'Delivery, 0 orders'];
    for (const name of columns) expect(screen.getByRole('region', { name })).toBeInTheDocument();
    const card = within(production).getByRole('button', { name: /CK-1002/ });
    expect(card).toHaveTextContent('Printing Press');
    expect(card).toHaveTextContent('RK');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'List' }));
    await waitFor(() => expect(url().get('view')).toBeNull());
  });

  it('says a column counts only the loaded orders while more match than the board has loaded', async () => {
    api.list.mockResolvedValue(page([
      row('CK-1002', { phase: 'PRODUCTION', currentStep: 8, currentTitle: 'Printing Press' }),
      row('CK-1003', { phase: 'PRODUCTION', currentStep: 10, currentTitle: 'Production Follow-up' }),
    ], 150));
    renderList('/dashboard?panel=nb-work&view=board');
    const production = await screen.findByRole('region', { name: 'Production, 2 orders loaded' });
    expect(within(production).getByText('2 loaded')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Load more orders (148 not shown)' })).toBeInTheDocument();
  });

  it("shows each school's city beside its name where the school list gives it", async () => {
    auth.user = { role: 'SUPERADMIN', userId: 1, fullName: 'Super Admin' };
    api.schools.mockResolvedValue([{ id: 7, name: 'Delhi Public School', city: 'Gurugram' }, { id: 8, name: 'Green Valley', city: null }]);
    renderList();
    const table = await screen.findByRole('table', { name: 'Orders in production' });
    await waitFor(() => expect(within(table).getAllByRole('row')[1]).toHaveTextContent('Delhi Public School · Gurugram'));
  });

  it('explains an empty list, and offers to clear filters that match nothing', async () => {
    api.list.mockResolvedValue(page([]));
    renderList();
    expect(await screen.findByText('No orders are in production.')).toBeInTheDocument();
    cleanup();
    renderList('/dashboard?panel=nb-work&sla=OVERDUE&q=zzz');
    expect(await screen.findByText('No orders match these filters.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
    await waitFor(() => expect(url().toString()).toBe('panel=nb-work'));
  });

  it('says plainly when the list cannot load, and retries', async () => {
    api.list.mockRejectedValueOnce({ response: { status: 503, data: { message: 'upstream' } } });
    renderList();
    expect(await screen.findByRole('alert')).toHaveTextContent('The order workflow service is not available right now.');
    fireEvent.click(screen.getByRole('button', { name: /Retry/ }));
    expect(await screen.findByRole('table')).toBeInTheDocument();
  });

  it('pages through long lists fifty at a time', async () => {
    api.list.mockResolvedValue(page([row('CK-1015')], 120));
    renderList();
    await screen.findByRole('table');
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => expect(url().get('page')).toBe('1'));
    await waitFor(() => expect(api.list).toHaveBeenLastCalledWith({ sort: 'due', limit: 50, offset: 50 }));
  });
});

describe('the order workflows panel', () => {
  function renderPanel(entry: string) {
    render(
      <MemoryRouter initialEntries={[entry]}>
        <Routes><Route path="/dashboard" element={<><NotebookWorklistPanel /><Url /></>} /></Routes>
      </MemoryRouter>,
    );
  }

  it('opens an order as a history entry, so going back returns to the list', async () => {
    renderPanel('/dashboard?panel=nb-work&sla=OVERDUE');
    fireEvent.click(await screen.findByRole('button', { name: /Open Order NB-DPS_GUR-26-11-13-0015/ }));
    expect(await screen.findByText('workflow of CK-1015')).toBeInTheDocument();
    expect(url().get('order')).toBe('CK-1015');
    expect(url().get('sla')).toBe('OVERDUE');
    fireEvent.click(screen.getByRole('button', { name: 'Order workflows' }));
    expect(await screen.findByRole('table')).toBeInTheDocument();
    expect(url().get('order')).toBeNull();
    expect(url().get('sla')).toBe('OVERDUE');
  });

  it('opens a deep-linked order, closes it in place, and ignores an order id that is not one', async () => {
    renderPanel('/dashboard?panel=nb-work&order=CK-1016');
    expect(await screen.findByText('workflow of CK-1016')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Order workflows' }));
    expect(await screen.findByRole('table')).toBeInTheDocument();
    expect(url().toString()).toBe('panel=nb-work');
    cleanup();
    renderPanel('/dashboard?panel=nb-work&order=..%2Fadmin');
    expect(await screen.findByRole('table')).toBeInTheDocument();
  });

  it('opens a link that names the order by its number, as typed or pasted, under its CK id', async () => {
    api.orderIdByNumber.mockResolvedValue('CK-1042');
    renderPanel('/dashboard?panel=nb-work&order=nb-dps_gur-26-11-13-0042');
    expect(await screen.findByText('workflow of CK-1042')).toBeInTheDocument();
    expect(api.orderIdByNumber).toHaveBeenCalledWith('NB-DPS_GUR-26-11-13-0042');
    expect(url().get('order')).toBe('CK-1042');
    fireEvent.click(screen.getByRole('button', { name: 'Order workflows' }));
    expect(await screen.findByRole('table')).toBeInTheDocument();
  });

  it('says when no order has the number in a link, and goes back to the list', async () => {
    api.orderIdByNumber.mockRejectedValue({ response: { status: 404, data: { message: 'Not found' } } });
    renderPanel('/dashboard?panel=nb-work&order=NB-DPS_GUR-26-11-13-0099');
    expect(await screen.findByRole('alert')).toHaveTextContent('No order has the number NB-DPS_GUR-26-11-13-0099.');
    fireEvent.click(screen.getByRole('button', { name: /Order workflows/ }));
    expect(await screen.findByRole('table')).toBeInTheDocument();
    expect(url().get('order')).toBeNull();
  });
});
