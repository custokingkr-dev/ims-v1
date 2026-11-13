import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../../services/api';
import { NotebookWorklistPanel } from '../../pages/workspace/panels/NotebookWorklistPanel';
import { NotebookAdminPanel } from '../../pages/workspace/panels/NotebookAdminPanel';
import { SaAllOrdersPanel } from '../../pages/workspace/panels/SaAllOrdersPanel';
import { clearWorkflowCaches } from './workflow/api';
import { NOW, makeSummary, makeView, normaliseTemplate } from './workflow/testFixtures';
import type { OrderWorkflowView } from './workflow/types';

/*
 * The order workflow screens wired together and run against the HTTP transport, nothing else replaced: the list, an
 * order's workflow page, the call log inside it, and Workflow set-up. Every request a screen sends must be an endpoint a
 * controller implements, as the route inventory generated from the Spring controllers lists it, so a screen calling a
 * path or method no service serves fails here instead of on dev.
 */

vi.mock('../../services/api', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() } }));
const auth = vi.hoisted(() => ({ user: { role: 'OPERATIONS', userId: 42, fullName: 'Asha Operator' } as Record<string, unknown> }));
vi.mock('../../contexts/AuthContext', () => ({ useAuth: () => ({ user: auth.user }) }));
vi.mock('../../hooks/usePermissions', () => ({ usePermissions: () => ({ can: () => auth.user.role === 'SUPERADMIN' }) }));

const { readFileSync } = await vi.importActual<{ readFileSync: (path: string, encoding: string) => string }>('node:fs');
const { cwd } = await vi.importActual<{ cwd: () => string }>('node:process');
const ROOT = `${cwd()}/..`;
const NB = normaliseTemplate(JSON.parse(readFileSync(`${ROOT}/services/operations-service/src/main/resources/workflow-templates/NOTEBOOK_ORDER_TRACKER.json`, 'utf8')));

interface InventoryEndpoint { method: string; path: string; classification: string }
const inventory = JSON.parse(readFileSync(`${ROOT}/services/api-gateway/api-route-inventory.json`, 'utf8')) as { endpoints: InventoryEndpoint[] };
/** Each canonical controller endpoint as the browser calls it: below /api/v1, path variables as one segment. */
const SERVED = inventory.endpoints
  .filter((endpoint) => endpoint.classification === 'canonical' && endpoint.path.startsWith('/api/v1/'))
  .map((endpoint) => ({
    method: endpoint.method.toUpperCase(),
    pattern: new RegExp(`^${endpoint.path.slice('/api/v1'.length).replace(/[.*+?^$()|[\]\\]/g, '\\$&').replace(/\\?\{[^/}]+\\?\}/g, '[^/]+')}$`),
  }));

type Method = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
interface Sent { method: Method; url: string; body?: unknown; params?: Record<string, unknown> }
type Reply = (request: Sent) => unknown;

const http = vi.mocked(api);
let sent: Sent[] = [];
let unserved: string[] = [];
let routes: Array<{ method: Method; path: string | RegExp; reply: Reply }> = [];

function on(method: Method, path: string | RegExp, reply: unknown) {
  routes.unshift({ method, path, reply: typeof reply === 'function' ? reply as Reply : () => reply });
}

function notFound(url: string): Error {
  return Object.assign(new Error(`Request failed with status code 404 for ${url}`), { isAxiosError: true, response: { status: 404, data: { message: 'Not found' } } });
}

async function handle(method: Method, url: string, body?: unknown, config?: { params?: Record<string, unknown> }) {
  const request: Sent = { method, url, body, params: config?.params };
  sent.push(request);
  if (!SERVED.some((endpoint) => endpoint.method === method && endpoint.pattern.test(url))) unserved.push(`${method} ${url}`);
  const route = routes.find((candidate) => candidate.method === method
    && (typeof candidate.path === 'string' ? candidate.path === url : candidate.path.test(url)));
  if (!route) throw notFound(url);
  return { data: await route.reply(request) };
}

const NB_ITEM = {
  orderId: 'CK-1015', orderNumber: 'NB-DPS_GUR-26-11-13-0042', definitionId: 'NOTEBOOK_ORDER_TRACKER', categoryCode: 'NOTEBOOKS', itemCode: 'NB',
  schoolId: 7, schoolName: 'Delhi Public School', customised: true, orderedCount: 15600, placedAt: '2026-11-11T06:30:00Z', timeZone: 'Asia/Kolkata',
  status: 'DESIGNING_ACTIVITY', phase: 'INTAKE', currentStep: 2, currentTitle: 'Designing Activity', assigneeUserId: 42, assigneeName: 'Asha Operator',
  dueAt: '2026-11-13T09:30:00Z', overdue: false, slaState: 'DUE_SOON', completed: 1, total: 12,
};
const CATEGORY = { code: 'NOTEBOOKS', label: 'Notebooks', emoji: '', description: 'School notebooks', orderType: 'Recurring', formEnabled: true, sortOrder: 1,
  active: true, orderCode: 'NB', itemNoun: 'notebook', workflowDefinition: 'NOTEBOOK_ORDER_TRACKER' };

function serveOrder(view: OrderWorkflowView) {
  let current = view;
  on('GET', '/workflows/item-orders', { items: [NB_ITEM], total: 1, counts: { open: 1, overdue: 0, dueToday: 1, unassigned: 0, mine: 1 }, now: NOW });
  on('GET', '/workflows/item-orders/templates', [NB]);
  on('GET', '/workflows/item-orders/templates/NOTEBOOK_ORDER_TRACKER', NB);
  on('GET', '/supply/product-catalog/categories', [CATEGORY]);
  on('GET', '/workflows/item-orders/CK-1015', () => current);
  on('GET', '/catalog/orders/CK-1015/tracker-summary', makeSummary());
  on('GET', '/catalog/orders/CK-1015/assets', []);
  on('GET', '/workflows/item-orders/CK-1015/calls', []);
  on('GET', '/workflows/item-orders/CK-1015/activity', { entries: [], nextBefore: null });
  on('GET', '/workflows/designers', [{ id: 3, name: 'Priya Singh', phone: '98100 00003', active: true }]);
  on('GET', '/workflows/vendors', []);
  on('GET', '/workflows/vendor-categories', []);
  return { replace: (next: OrderWorkflowView) => { current = next; } };
}

function renderWorkflows(entry = '/dashboard?panel=nb-work') {
  render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes><Route path="/dashboard" element={<NotebookWorklistPanel />} /></Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  clearWorkflowCaches();
  sent = []; unserved = []; routes = [];
  auth.user = { role: 'OPERATIONS', userId: 42, fullName: 'Asha Operator', operatorSchools: [7], operatorScopes: [] };
  http.get.mockImplementation(((url: string, config?: { params?: Record<string, unknown> }) => handle('GET', url, undefined, config)) as never);
  http.post.mockImplementation(((url: string, body?: unknown, config?: { params?: Record<string, unknown> }) => handle('POST', url, body, config)) as never);
  http.put.mockImplementation(((url: string, body?: unknown, config?: { params?: Record<string, unknown> }) => handle('PUT', url, body, config)) as never);
  http.patch.mockImplementation(((url: string, body?: unknown, config?: { params?: Record<string, unknown> }) => handle('PATCH', url, body, config)) as never);
  http.delete.mockImplementation(((url: string, config?: { params?: Record<string, unknown> }) => handle('DELETE', url, undefined, config)) as never);
  URL.createObjectURL = vi.fn(() => 'blob:design');
  URL.revokeObjectURL = vi.fn();
});
afterEach(() => {
  cleanup();
  // Checked after every case, so a request a later render sends is caught too.
  expect(unserved).toEqual([]);
});

describe('order workflows, list to order and back, against the served endpoints', () => {
  it('opens an order from the list, logs a call from the School & vendor tab, reads its activity and returns to the list', async () => {
    const order = serveOrder(makeView(NB, { active: 2 }));
    renderWorkflows();
    fireEvent.click(await screen.findByRole('button', { name: 'Open Order NB-DPS_GUR-26-11-13-0042, reference CK-1015' }));

    expect(await screen.findByRole('heading', { level: 3, name: 'Step 2: Designing Activity' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'NB-DPS_GUR-26-11-13-0042' })).toBeInTheDocument();
    const list = sent.find((request) => request.url === '/workflows/item-orders');
    expect(list?.params).toMatchObject({ sort: 'due', limit: 50, offset: 0 });

    fireEvent.click(screen.getByRole('tab', { name: 'School & vendor' }));
    const form = await screen.findByRole('form', { name: 'Log a call or discussion' });
    expect(await screen.findByText('No calls logged yet.')).toBeInTheDocument();
    const logged = { id: 11, stepOrder: 2, contacted: 'SCHOOL', outcome: 'CALLBACK_REQUESTED', contactLabel: 'Rajesh Kumar', vendorId: null, designerId: null,
      notes: 'Call back after the assembly', nextFollowUpAt: '2099-01-02T10:30:00.000Z', createdBy: 42, createdByName: 'Asha Operator', createdAt: NOW };
    on('POST', '/workflows/item-orders/CK-1015/calls', ({ body }: Sent) => ({ ...logged, ...(body as object) }));
    on('GET', '/workflows/item-orders/CK-1015/calls', [logged]);
    order.replace(makeView(NB, { active: 2, view: { upcomingActions: [
      { kind: 'CALLBACK', title: 'Call back Rajesh Kumar', stepOrder: 2, dueAt: '2099-01-02T10:30:00Z', dueState: 'LATER', done: false },
    ] } }));
    fireEvent.change(within(form).getByRole('combobox', { name: 'Outcome' }), { target: { value: 'CALLBACK_REQUESTED' } });
    fireEvent.change(within(form).getByRole('textbox', { name: 'Call notes *' }), { target: { value: 'Call back after the assembly' } });
    fireEvent.change(within(form).getByLabelText('Next follow-up *'), { target: { value: '2099-01-02T16:00' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Log call' }));

    expect(await screen.findByText('Call logged')).toBeInTheDocument();
    const post = sent.find((request) => request.method === 'POST');
    expect(post).toEqual({ method: 'POST', url: '/workflows/item-orders/CK-1015/calls', params: undefined, body: {
      contacted: 'SCHOOL', outcome: 'CALLBACK_REQUESTED', contactLabel: 'Rajesh Kumar', vendorId: null, designerId: null, stepOrder: 2,
      notes: 'Call back after the assembly', nextFollowUpAt: new Date('2099-01-02T16:00').toISOString(),
    } });
    // The order is re-read, so the follow-up joins Upcoming Actions.
    const upcoming = screen.getByRole('region', { name: 'Upcoming Actions' });
    expect(await within(upcoming).findByText('Call back Rajesh Kumar')).toBeInTheDocument();
    expect(await within(screen.getByRole('list', { name: 'Logged calls' })).findByText('Call back after the assembly')).toBeInTheDocument();

    on('GET', '/workflows/item-orders/CK-1015/activity', { entries: [{
      id: 'call:11', kind: 'CALL', at: NOW, stepOrder: 2, actorUserId: 42, actorName: 'Asha Operator', action: 'CALLBACK_REQUESTED',
      text: 'Call back after the assembly', details: { contacted: 'SCHOOL', contactLabel: 'Rajesh Kumar' }, count: 1,
    }], nextBefore: null });
    fireEvent.click(screen.getByRole('tab', { name: 'Activity history' }));
    const history = await screen.findByRole('region', { name: 'Order activity history' });
    expect(await within(history).findByText('Call back after the assembly')).toBeInTheDocument();

    fireEvent.click(screen.getAllByRole('button', { name: /Order workflows/ })[0]);
    expect(await screen.findByRole('heading', { name: 'Order workflows' })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Open Order NB-DPS_GUR-26-11-13-0042, reference CK-1015' })).toBeInTheDocument();
  }, 20000);

  it('saves a stage field as a partial save with the step version, then completes the stage with the version the save returned', async () => {
    const view = makeView(NB, { active: 3 });
    serveOrder(view);
    const saved = makeView(NB, { active: 3, steps: { 3: { data: { cdrLink: 'https://drive.example.test/cdr' }, version: 4 } } });
    on('PUT', '/workflows/item-orders/CK-1015/steps/3/data', saved);
    on('POST', '/workflows/item-orders/CK-1015/steps/3/complete', makeView(NB, { active: 4 }));
    renderWorkflows('/dashboard?panel=nb-work&order=CK-1015');
    await screen.findByRole('heading', { level: 3, name: 'Step 3: CDR Conversion' });
    const cdr = NB.steps.find((step) => step.order === 3)?.fields.cdrLink;
    expect(cdr).toBeDefined();
    fireEvent.change(screen.getByLabelText(new RegExp(cdr!.label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))), { target: { value: 'https://drive.example.test/cdr' } });
    const block = NB.steps.find((step) => step.order === 3)!.blocks.find((item) => item.fields?.includes('cdrLink'))!;
    fireEvent.click(screen.getByRole('button', { name: block.save ?? 'Save' }));
    await waitFor(() => expect(sent.some((request) => request.method === 'PUT')).toBe(true));
    expect(sent.find((request) => request.method === 'PUT')).toMatchObject({
      url: '/workflows/item-orders/CK-1015/steps/3/data', body: { data: { cdrLink: 'https://drive.example.test/cdr' }, expectedVersion: 3 },
    });
    expect(await screen.findByRole('link', { name: /Open/ })).toHaveAttribute('href', 'https://drive.example.test/cdr');

    fireEvent.click(screen.getByRole('button', { name: /Complete stage & continue/ }));
    expect(await screen.findByRole('heading', { level: 3, name: 'Step 4: Head Approval' })).toBeInTheDocument();
    expect(sent.find((request) => request.method === 'POST')).toEqual({
      method: 'POST', url: '/workflows/item-orders/CK-1015/steps/3/complete', body: { expectedVersion: 4 }, params: undefined,
    });
    expect(screen.getByText('CDR Conversion completed')).toBeInTheDocument();
  }, 15000);

  it('opens an order by its number through the by-number lookup, then by its CK id', async () => {
    serveOrder(makeView(NB, { active: 2 }));
    on('GET', '/catalog/orders/by-number/NB-DPS_GUR-26-11-13-0042', { id: 'CK-1015', orderNumber: 'NB-DPS_GUR-26-11-13-0042' });
    renderWorkflows('/dashboard?panel=nb-work&order=NB-DPS_GUR-26-11-13-0042');
    expect(await screen.findByRole('heading', { level: 3, name: 'Step 2: Designing Activity' })).toBeInTheDocument();
    expect(sent.map((request) => request.url)).toContain('/workflows/item-orders/CK-1015');
  });
});

describe('All orders opens a workflow order on its workflow', () => {
  it('opens a tracker-managed order from All orders on the workflow screen, and Back returns to All orders', async () => {
    auth.user = { role: 'SUPERADMIN', userId: 1, fullName: 'Super Admin' };
    serveOrder(makeView(NB, { active: 2, superadmin: true }));
    on('GET', '/catalog/orders/page', { content: [{ id: 'CK-1015', orderNumber: 'NB-DPS_GUR-26-11-13-0042', category: 'NOTEBOOKS', schoolId: 7,
      schoolName: 'Delhi Public School', status: 'DESIGNING_ACTIVITY', totalAmount: 0, pricingStatus: 'NOT_APPLICABLE', formVersion: 2, trackerManaged: true,
      placedAt: '2026-11-11' }], page: 0, size: 200, totalElements: 1, totalPages: 1 });
    on('GET', '/catalog/orders/summary', { totalOrders: 1, pendingApproval: 0, approved: 0, rejected: 0, gmv: 0, activeOrders: 1, termSpend: 0, activeServices: 0, deliveredCount: 0 });
    on('GET', '/rbac/operators', []);
    on('GET', '/sa/schools', [{ id: 7, name: 'Delhi Public School' }]);
    render(<MemoryRouter><SaAllOrdersPanel onNewOrder={vi.fn()} /></MemoryRouter>);
    const row = await screen.findByRole('row', { name: /NB-DPS_GUR-26-11-13-0042/ });
    expect(within(row).getByText('Ref CK-1015')).toBeInTheDocument();
    fireEvent.click(within(row).getByRole('button', { name: 'View' }));
    expect(await screen.findByRole('heading', { level: 3, name: 'Step 2: Designing Activity' })).toBeInTheDocument();
    expect(sent.some((request) => request.url === '/workflows/item-orders/CK-1015')).toBe(true);
    fireEvent.click(screen.getAllByRole('button', { name: /Back to orders/ })[0]);
    expect(await screen.findByRole('row', { name: /NB-DPS_GUR-26-11-13-0042/ })).toBeInTheDocument();
  }, 15000);
});

describe('Workflow set-up against the served endpoints', () => {
  it('loads every tab from endpoints the services implement', async () => {
    auth.user = { role: 'SUPERADMIN', userId: 1, fullName: 'Super Admin' };
    on('GET', '/sa/schools', [{ id: 7, name: 'Delhi Public School', city: 'Gurugram' }]);
    on('GET', '/workflows/vendor-categories', [{ code: 'BINDERS', name: 'Binders', sortOrder: 3, active: true, vendorCount: 1, updatedAt: null, updatedBy: null }]);
    on('GET', '/workflows/vendors', [{ id: 3, name: 'Perfect Bindery', categories: ['BINDERS'], contactPerson: 'Mohan', phone: '+91 90000 00003', altPhone: null,
      email: null, city: 'Delhi', whatsappGroupUrl: null, notes: null, sample: false, active: true, updatedAt: NOW, updatedBy: 1 }]);
    on('GET', '/workflows/designers', [{ id: 3, name: 'Priya Singh', phone: '98100 00003', active: true }]);
    on('GET', '/workflows/item-orders/templates', [NB]);
    on('GET', /^\/workflows\/time-slots\/7$/, NB.steps.map((step) => ({ step: step.order, code: step.code, title: step.title, defaultSla: step.defaultSla,
      effective: step.defaultSla ? { ...step.defaultSla, source: 'DEFAULT' } : null })));
    on('GET', '/workflows/settings/internal-approval-group', { inviteUrl: null, updatedAt: null, updatedBy: null });
    on('GET', '/workflows/school-whatsapp-groups/7', {});
    on('GET', '/rbac/operators', []);
    on('GET', '/users', []);
    on('GET', '/supply/product-catalog/categories', [CATEGORY]);
    render(<MemoryRouter><NotebookAdminPanel /></MemoryRouter>);
    expect(await screen.findByText('Perfect Bindery')).toBeInTheDocument();
    for (const name of ['Designers', 'Time slots', 'Groups', 'Operators']) {
      fireEvent.click(screen.getByRole('tab', { name }));
      expect(screen.getByRole('tab', { name })).toHaveAttribute('aria-selected', 'true');
    }
    fireEvent.click(screen.getByRole('tab', { name: 'Time slots' }));
    expect(await screen.findByRole('table', { name: /Default time slots/ })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('School'), { target: { value: '7' } });
    expect(await screen.findByRole('table', { name: 'Time slots at the chosen school' })).toBeInTheDocument();
    const slots = sent.find((request) => request.url === '/workflows/time-slots/7');
    expect(slots?.params).toEqual({ definitionId: 'NOTEBOOK_ORDER_TRACKER' });
    await waitFor(() => expect(sent.length).toBeGreaterThan(5));
  }, 15000);
});
