import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import api from '../../services/api';
import { ProductOrderDetail } from './ProductOrderDetail';
import { savedNotebook, trophyDefinition } from './catalogTestFixtures';
const auth = vi.hoisted(() => ({ role: 'SUPERADMIN' }));
vi.mock('../../services/api', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn() } }));
vi.mock('../../hooks/usePermissions', () => ({ usePermissions: () => ({ can: () => true }) }));
vi.mock('../../contexts/AuthContext', () => ({ useAuth: () => ({ user: auth }) }));
beforeEach(() => { vi.clearAllMocks(); auth.role = 'SUPERADMIN'; URL.createObjectURL = vi.fn(() => 'blob:artwork'); URL.revokeObjectURL = vi.fn(); });
afterEach(cleanup);
// The quote, approval and delivery screens serve every order except a notebook order on the order tracker, which the tracker runs.
const legacy = (...args: Parameters<typeof savedNotebook>) => {
  const detail = savedNotebook(...args);
  return { ...detail, formDefinition: { ...detail.formDefinition, category: { ...detail.formDefinition.category, code: 'UNIFORMS' } } };
};
it('shows trophy model/size and quantity instead of notebook fields for placed orders', async () => {
  const detail = { ...savedNotebook('PROCESSING', false), formDefinition: trophyDefinition, orderSelections: {},
    lines: [{ id: 101, lineNo: 1, optionSelections: { VARIANT: { code: 'T_WM001_B', label: 'WM001 / Size B' } }, requestedBookCount: 3, bookCount: 3, requestedPageCount: 1, pageCount: 1, unitPricePaise: null, lineTotalPaise: null }] };
  vi.mocked(api.get).mockResolvedValue({ data: detail });
  render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} />);
  expect(await screen.findByText('WM001 / Size B')).toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: 'Quantity' })).toBeInTheDocument();
  expect(screen.queryByRole('columnheader', { name: 'Printed pages' })).not.toBeInTheDocument();
  expect(screen.queryByRole('columnheader', { name: 'Ruling' })).not.toBeInTheDocument();
  expect(screen.getByText('No design approval required')).toBeInTheDocument();
});
it('renders a persisted HTTPS client design link safely in order details', async () => {
  const href = 'https://drive.google.com/file/d/example/view';
  const detail = { ...savedNotebook('PROCESSING', false), formDefinition: {
    ...trophyDefinition,
    groups: [...trophyDefinition.groups, { id: 99, categoryCode: 'TROPHIES', code: 'DESIGN_FILE_LINK',
      label: 'Design file link', level: 90, selectionType: 'SINGLE', required: false, scope: 'ORDER' as const,
      active: true, inputType: 'TEXT' as const, render: 'FIELD' as const, options: [] }],
  }, orderSelections: { DESIGN_FILE_LINK: href } };
  vi.mocked(api.get).mockResolvedValue({ data: detail });
  render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} />);
  const link = await screen.findByRole('link', { name: href });
  expect(link).toHaveAttribute('href', href);
  expect(link).toHaveAttribute('target', '_blank');
  expect(link).toHaveAttribute('rel', 'noopener noreferrer');
});
it('quotes persisted lines in paise, refreshes computed totals, and gates approval on the quote', async () => {
  let detail = legacy('PROCESSING', false);
  vi.mocked(api.get).mockImplementation(async () => ({ data: detail }));
  vi.mocked(api.put).mockImplementation(async () => {
    detail = { ...detail, version: 1, pricingStatus: 'QUOTED', order: { ...detail.order, subtotal: 4550000, gst: 12000, totalAmount: 4562000 }, lines: detail.lines.map((line) => ({ ...line, unitPricePaise: 4550, lineTotalPaise: 4550000 })) };
    return { data: detail };
  });
  render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} />);
  expect(await screen.findByRole('button', { name: 'Approve order' })).toBeDisabled();
  expect(screen.getByText('GST amount (Rs.)')).toBeInTheDocument();
  expect(screen.getByRole('row', { name: 'Order line 1' }).querySelector('[data-label="Line 1 - Size"]')).toHaveTextContent('Long');
  expect(screen.getAllByLabelText('Unit price for line 1')).toHaveLength(1);
  fireEvent.change(screen.getByLabelText('Unit price for line 1'), { target: { value: '45.50' } });
  fireEvent.change(screen.getByLabelText('GST amount in rupees'), { target: { value: '120' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save quote' }));
  await waitFor(() => expect(api.put).toHaveBeenCalledWith('/supply/orders/ORD-42/quote', { version: 0, lines: [{ id: 101, unitPricePaise: 4550 }], gstPaise: 12000 }));
  expect(await screen.findByText('Quote saved.')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Approve order' })).toBeEnabled();
  expect(screen.getByText('Rs. 45,620.00')).toBeInTheDocument();
});
const onTracker = (status: string) => { const detail = savedNotebook(status); return { ...detail, order: { ...detail.order, trackerManaged: true } }; };
it.each(['DESIGN_APPROVAL', 'PROCESSING', 'APPROVED'])('offers no quote, approval or delivery for a %s notebook order, which the tracker runs', async (status) => {
  const detail = onTracker(status);
  expect(detail.formDefinition.category.code).toBe('NOTEBOOKS');
  vi.mocked(api.get).mockResolvedValue({ data: detail });
  render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} />);
  expect(await screen.findByRole('row', { name: 'Order line 1' })).toBeInTheDocument();
  for (const name of ['Save quote', 'Approve order', 'Approve design', 'Mark delivered']) expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
  expect(screen.queryByText('Pending pricing')).not.toBeInTheDocument();
  expect(screen.queryByText('Quoted')).not.toBeInTheDocument();
  expect(screen.queryByText(/Design approval (pending|completed)/)).not.toBeInTheDocument();
  expect(screen.queryByLabelText('Unit price for line 1')).not.toBeInTheDocument();
});
// Spec v4: notebook orders on the tracker leave the delivery flow; their delivery proof is tracker step 12.
it.each(['ORDER_DETAILS', 'DELIVERY_INVOICING'])('asks the school for no pre-delivery photo on a %s notebook order the tracker runs', async (status) => {
  auth.role = 'ADMIN';
  vi.mocked(api.get).mockResolvedValue({ data: onTracker(status) });
  const { container } = render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} />);
  expect(await screen.findByRole('row', { name: 'Order line 1' })).toBeInTheDocument();
  expect(screen.queryByText('Pre-delivery photo')).not.toBeInTheDocument();
  expect(screen.queryByText('Required before delivery')).not.toBeInTheDocument();
  expect(screen.queryByText('Pre-delivery photo required')).not.toBeInTheDocument();
  expect(container.querySelector('input[type="file"]')).toBeNull();
});
// Owner decision D1: a customised notebook order placed before the tracker still needs its photo before delivery.
it('keeps the pre-delivery photo requirement and its upload for a notebook order placed before the tracker', async () => {
  auth.role = 'ADMIN';
  vi.mocked(api.get).mockResolvedValue({ data: { ...savedNotebook('APPROVED'), order: { ...savedNotebook('APPROVED').order, trackerManaged: false } } });
  const { container } = render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} />);
  expect(await screen.findByText('Pre-delivery photo')).toBeInTheDocument();
  expect(screen.getByText('Required before delivery')).toBeInTheDocument();
  expect(container.querySelector('input[type="file"]')).not.toBeNull();
});
// The host may have opened this screen from a stale list row; the loaded order is what says whether the tracker runs it.
it('tells its host when the loaded order turns out to be on the tracker, and stays quiet for any other order', async () => {
  const onTrackerManaged = vi.fn();
  vi.mocked(api.get).mockResolvedValue({ data: savedNotebook('PROCESSING') });
  const { unmount } = render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} onTrackerManaged={onTrackerManaged} />);
  expect(await screen.findByRole('row', { name: 'Order line 1' })).toBeInTheDocument();
  expect(onTrackerManaged).not.toHaveBeenCalled();
  unmount();
  vi.mocked(api.get).mockResolvedValue({ data: onTracker('ORDER_DETAILS') });
  render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} onTrackerManaged={onTrackerManaged} />);
  await waitFor(() => expect(onTrackerManaged).toHaveBeenCalledTimes(1));
});

it('tells its host once an order placed from this screen lands on the tracker', async () => {
  const onTrackerManaged = vi.fn();
  let detail = savedNotebook('DRAFT', false);
  vi.mocked(api.get).mockImplementation(async () => ({ data: detail }));
  vi.mocked(api.patch).mockImplementation(async () => ({ data: detail }));
  vi.mocked(api.post).mockImplementation(async (url) => {
    if (String(url).endsWith('/place')) detail = { ...savedNotebook('ORDER_DETAILS', false), order: { ...savedNotebook('ORDER_DETAILS', false).order, trackerManaged: true } };
    return { data: {} };
  });
  render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} onTrackerManaged={onTrackerManaged} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Place order' }));
  await waitFor(() => expect(api.post).toHaveBeenCalledWith('/catalog/orders/ORD-42/place'));
  await waitFor(() => expect(onTrackerManaged).toHaveBeenCalled());
});

// Owner decision D1: a notebook order placed before the tracker keeps the quote, approve and deliver flow it started in.
it('keeps quoting, design approval, order approval and delivery for a notebook order placed before the tracker', async () => {
  const quoted = { ...savedNotebook('PROCESSING'), pricingStatus: 'QUOTED', approvedDesignAssetId: 1,
    assets: [{ id: 1, assetKind: 'DESIGN' as const, contentType: 'image/png', sizeBytes: 100, originalFilename: 'cover.png', contentUrl: '/supply/orders/ORD-42/assets/1/content', uploadedAt: '2026-09-15T12:00:00Z' }] };
  vi.mocked(api.get).mockImplementation(async (url) => ({ data: String(url).endsWith('/content') ? new Blob(['image'], { type: 'image/png' }) : quoted }));
  const { unmount } = render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} />);
  expect(await screen.findByRole('button', { name: 'Save quote' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Approve order' })).toBeEnabled();
  expect(screen.getByText('Design approval completed')).toBeInTheDocument();
  unmount();
  vi.mocked(api.get).mockImplementation(async (url) => ({ data: String(url).endsWith('/content') ? new Blob(['image'], { type: 'image/png' }) : { ...quoted, order: { ...quoted.order, status: 'DESIGN_APPROVAL' } } }));
  const { unmount: unmountDesign } = render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} />);
  expect(await screen.findByRole('button', { name: 'Approve design' })).toBeInTheDocument();
  unmountDesign();
  vi.mocked(api.get).mockResolvedValue({ data: { ...savedNotebook('APPROVED', false), order: { ...savedNotebook('APPROVED', false).order, trackerManaged: false } } });
  render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} />);
  expect(await screen.findByRole('button', { name: 'Mark delivered' })).toBeEnabled();
});
it('hides design and quote actions from school administrators, while keeping uploaded artwork readable', async () => {
  auth.role = 'ADMIN';
  const detail = savedNotebook('DESIGN_APPROVAL');
  detail.assets = [{ id: 1, assetKind: 'DESIGN', contentType: 'image/png', sizeBytes: 100, originalFilename: 'cover.png', contentUrl: '/supply/orders/ORD-42/assets/1/content', uploadedAt: '2026-09-15T12:00:00Z' }];
  vi.mocked(api.get).mockImplementation(async (url) => ({ data: String(url).endsWith('/content') ? new Blob(['image'], { type: 'image/png' }) : detail }));
  render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} />);
  expect(await screen.findByText('cover.png')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Approve design' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Save quote' })).not.toBeInTheDocument();
  expect(await screen.findByAltText('Design artwork')).toHaveAttribute('src', 'blob:artwork');
  expect(api.get).toHaveBeenCalledWith('/supply/orders/ORD-42/assets/1/content', { responseType: 'blob' });
});
it('uploads the later pre-delivery photo on the same order and enables delivery after refresh', async () => {
  let detail = legacy('APPROVED');
  vi.mocked(api.get).mockImplementation(async (url) => ({ data: String(url).endsWith('/content') ? new Blob(['photo'], { type: 'image/png' }) : detail }));
  vi.mocked(api.post).mockImplementation(async () => { detail = { ...detail, version: 1, assets: [{ id: 5, assetKind: 'PRE_DELIVERY_PHOTO', contentType: 'image/png', sizeBytes: 10, originalFilename: 'delivery.png', contentUrl: '/supply/orders/ORD-42/assets/5/content', uploadedAt: '2026-09-15T12:00:00Z' }] }; return { data: detail.assets[0] }; });
  const { container } = render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} />);
  expect(await screen.findByRole('button', { name: 'Mark delivered' })).toBeDisabled();
  const file = new File(['photo'], 'delivery.png', { type: 'image/png' });
  fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [file] } });
  expect(await screen.findByText('Attachment saved.')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Mark delivered' })).toBeEnabled();
  expect(api.post).toHaveBeenCalledWith('/supply/orders/ORD-42/assets', expect.any(FormData));
  const body = vi.mocked(api.post).mock.calls[0][1] as FormData;
  expect(body.get('assetKind')).toBe('PRE_DELIVERY_PHOTO');
});

// The production workflow: the server says which orders have one (order.productionWorkflowAvailable) and who may act on it.
const WORKFLOW_URL = '/catalog/orders/ORD-42/production-workflow';
const LINKS_URL = '/workflows/school-whatsapp-groups/7';
const productionView = (canEdit: boolean) => ({
  orderId: 'ORD-42', category: 'TROPHIES', status: 'ACTIVE', currentStage: 'ORDER_DETAILS', version: 0, canEdit, canComplete: canEdit,
  stages: [
    { code: 'ORDER_DETAILS', order: 1, title: 'Order Details', status: 'ACTIVE', data: canEdit ? { details: 'Brief received' } : {}, note: null, completedAt: null },
    { code: 'DESIGN_ACTIVITY', order: 2, title: 'Designing Activity', status: 'LOCKED', data: {}, note: null, completedAt: null },
  ],
});
const trophyOrder = (status: string, productionWorkflowAvailable?: boolean) => {
  const base = savedNotebook(status, false);
  return { ...base, formDefinition: trophyDefinition, orderSelections: {}, order: { ...base.order, productionWorkflowAvailable } };
};
function routeGets(detail: () => unknown, workflow: () => unknown = () => productionView(true)) {
  vi.mocked(api.get).mockImplementation(async (url) => {
    if (url === '/supply/orders/ORD-42/form') return { data: detail() };
    if (url === WORKFLOW_URL) return { data: workflow() };
    if (url === LINKS_URL) return { data: {} };
    throw new Error(`Unexpected GET ${url}`);
  });
}
const workflowGets = () => vi.mocked(api.get).mock.calls.filter(([url]) => url === WORKFLOW_URL).length;

it.each([['DELIVERED', false], ['REJECTED', false], ['PROCESSING', undefined]] as const)(
  'never mounts or requests the production workflow for a %s order the server has not made available (%s)', async (status, available) => {
    routeGets(() => trophyOrder(status, available));
    render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} />);
    expect(await screen.findByRole('row', { name: 'Order line 1' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Production workflow' })).not.toBeInTheDocument();
    expect(workflowGets()).toBe(0);
  });

it('mounts the production workflow for whatever category the server makes available, with the school WhatsApp groups for a superadmin', async () => {
  routeGets(() => trophyOrder('PROCESSING', true));
  render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} />);
  expect(await screen.findByRole('region', { name: 'Production workflow' })).toBeInTheDocument();
  expect(await screen.findByRole('textbox', { name: 'Production update' })).toHaveValue('Brief received');
  expect(await screen.findByRole('textbox', { name: 'School group invite URL' })).toBeInTheDocument();
  expect(api.get).toHaveBeenCalledWith(LINKS_URL);
});

it('gives a school administrator a read-only workflow and no WhatsApp groups, whatever local permissions say', async () => {
  auth.role = 'ADMIN';
  routeGets(() => trophyOrder('PROCESSING', true), () => productionView(false));
  render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} />);
  expect(await screen.findByText('0 / 2 stages')).toBeInTheDocument();
  expect(screen.queryByRole('textbox', { name: 'Production update' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Complete stage' })).not.toBeInTheDocument();
  expect(screen.queryByRole('region', { name: 'WhatsApp groups' })).not.toBeInTheDocument();
  expect(api.get).not.toHaveBeenCalledWith(LINKS_URL);
});

it('refetches the production workflow when the order changes underneath it', async () => {
  let detail = trophyOrder('PROCESSING', true);
  routeGets(() => detail);
  vi.mocked(api.put).mockImplementation(async (url) => {
    if (url !== '/supply/orders/ORD-42/quote') throw new Error(`Unexpected PUT ${url}`);
    detail = { ...detail, version: 1, pricingStatus: 'QUOTED' };
    return { data: detail };
  });
  render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} />);
  await screen.findByRole('textbox', { name: 'Production update' });
  expect(workflowGets()).toBe(1);
  fireEvent.change(screen.getByLabelText('Unit price for line 1'), { target: { value: '45' } });
  fireEvent.change(screen.getByLabelText('GST amount in rupees'), { target: { value: '0' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save quote' }));
  expect(await screen.findByText('Quote saved.')).toBeInTheDocument();
  await waitFor(() => expect(workflowGets()).toBe(2));
});

it('refetches the production workflow on Refresh order, and drops it once the server no longer offers it', async () => {
  let detail = trophyOrder('PROCESSING', true);
  routeGets(() => detail);
  render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} />);
  await screen.findByRole('textbox', { name: 'Production update' });
  fireEvent.click(screen.getByRole('button', { name: 'Refresh order' }));
  await waitFor(() => expect(workflowGets()).toBe(2));
  detail = { ...trophyOrder('CANCELLED', false), version: 2 };
  fireEvent.click(screen.getByRole('button', { name: 'Refresh order' }));
  await waitFor(() => expect(screen.queryByRole('region', { name: 'Production workflow' })).not.toBeInTheDocument());
  expect(workflowGets()).toBe(2);
});

it.each([
  ['a non-boolean productionWorkflowAvailable', { productionWorkflowAvailable: 'yes' }],
  ['a non-numeric schoolId', { schoolId: '7' }],
])('refuses order details with %s rather than guessing', async (_label, order) => {
  const detail = trophyOrder('PROCESSING');
  vi.mocked(api.get).mockResolvedValue({ data: { ...detail, order: { ...detail.order, ...order } } });
  render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Order details could not be read. Please refresh the order.');
  expect(screen.queryByRole('region', { name: 'Production workflow' })).not.toBeInTheDocument();
});
