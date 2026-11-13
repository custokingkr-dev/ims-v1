import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
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
function LocationProbe() { const location = useLocation(); return <p data-testid="location">{location.pathname}{location.search}</p>; }
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

// Design D4: the free-text production workflow is retired. A workflow order points staff to its order workflow screen.
const flierOrder = (status: string, overrides: Record<string, unknown> = {}, pricingStatus = 'PENDING_PRICING') => {
  const base = savedNotebook(status, false);
  return {
    ...base, pricingStatus, formDefinition: { ...trophyDefinition, category: { ...trophyDefinition.category, code: 'FLIERS', label: 'Fliers' } }, orderSelections: {},
    order: { ...base.order, id: 'CK-1015', trackerManaged: true, orderNumber: 'FE-DPS_GUR-26-11-13-0042', ...overrides },
  };
};
const STEP = { order: 5, title: 'Production Planning', total: 9, completed: 4 };

it('heads an order with its number and keeps the CK id beneath it as a reference', async () => {
  vi.mocked(api.get).mockResolvedValue({ data: flierOrder('PRODUCTION_PLANNING', { workflowStep: STEP }) });
  render(<ProductOrderDetail orderId="CK-1015" onBack={vi.fn()} />);
  expect(await screen.findByRole('heading', { level: 1, name: 'FE-DPS_GUR-26-11-13-0042' })).toBeInTheDocument();
  expect(screen.getByText('Ref CK-1015')).toBeInTheDocument();
});

it('heads a draft or an uncoded order with its CK id alone', async () => {
  vi.mocked(api.get).mockResolvedValue({ data: legacy('PROCESSING', false) });
  render(<ProductOrderDetail orderId="ORD-42" onBack={vi.fn()} />);
  expect(await screen.findByRole('heading', { level: 1, name: 'Order ORD-42' })).toBeInTheDocument();
  expect(screen.queryByText(/^Ref /)).not.toBeInTheDocument();
});

it('shows staff where a workflow order is and links to its order workflow instead of a production workflow', async () => {
  vi.mocked(api.get).mockResolvedValue({ data: flierOrder('PRODUCTION_PLANNING', { workflowStep: STEP }) });
  render(<ProductOrderDetail orderId="CK-1015" onBack={vi.fn()} />);
  const section = await screen.findByRole('region', { name: 'Order workflow' });
  expect(section).toHaveTextContent('5 · Production Planning · 4 of 9 steps done');
  expect(within(section).getByRole('link', { name: /Open order workflow/ })).toHaveAttribute('href', '?panel=nb-work&order=CK-1015');
  expect(screen.getByText('5 · Production Planning', { selector: '.ck-status' })).toBeInTheDocument();
  expect(screen.queryByRole('region', { name: 'Production workflow' })).not.toBeInTheDocument();
  expect(api.get).not.toHaveBeenCalledWith('/catalog/orders/CK-1015/production-workflow');
  for (const name of ['Approve order', 'Approve design', 'Mark delivered']) expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
});

it('navigates to the order workflow inside the app when it has a router', async () => {
  vi.mocked(api.get).mockResolvedValue({ data: flierOrder('PRODUCTION_PLANNING', { workflowStep: STEP }) });
  render(<MemoryRouter initialEntries={['/dashboard?panel=sa-all-orders']}>
    <ProductOrderDetail orderId="CK-1015" onBack={vi.fn()} />
    <Routes><Route path="/dashboard" element={<LocationProbe />} /></Routes>
  </MemoryRouter>);
  fireEvent.click(await screen.findByRole('link', { name: /Open order workflow/ }));
  expect(await screen.findByTestId('location')).toHaveTextContent('/dashboard?panel=nb-work&order=CK-1015');
});

it('says a workflow order is completed once its workflow finishes', async () => {
  vi.mocked(api.get).mockResolvedValue({ data: flierOrder('COMPLETED', { workflowStep: { ...STEP, order: 9, title: 'Delivery & Invoicing', completed: 9 } }) });
  render(<ProductOrderDetail orderId="CK-1015" onBack={vi.fn()} />);
  expect(await screen.findByRole('region', { name: 'Order workflow' })).toHaveTextContent('Completed · 9 of 9 steps done');
});

it.each([['PRODUCTION_PLANNING', 'Processing'], ['COMPLETED', 'Delivered']])(
  'gives a school only the coarse status of a %s workflow order, and no link to the workflow', async (status, coarse) => {
    auth.role = 'ADMIN';
    vi.mocked(api.get).mockResolvedValue({ data: flierOrder(status, { workflowStep: STEP }) });
    render(<ProductOrderDetail orderId="CK-1015" onBack={vi.fn()} />);
    expect(await screen.findByText(coarse, { selector: '.ck-status' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Order workflow' })).not.toBeInTheDocument();
    expect(screen.queryByText(/Production Planning/)).not.toBeInTheDocument();
  });

it('keeps quoting a workflow order of a priced item until its workflow completes (D5)', async () => {
  vi.mocked(api.get).mockResolvedValue({ data: flierOrder('PRE_PRINT_QUALITY_CHECK', { workflowStep: STEP }) });
  vi.mocked(api.put).mockResolvedValue({ data: {} });
  const { unmount } = render(<ProductOrderDetail orderId="CK-1015" onBack={vi.fn()} />);
  expect(await screen.findByText('Pending pricing', { selector: '.ck-status' })).toBeInTheDocument();
  fireEvent.change(await screen.findByLabelText('Unit price for line 1'), { target: { value: '2' } });
  fireEvent.change(screen.getByLabelText('GST amount in rupees'), { target: { value: '0' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save quote' }));
  await waitFor(() => expect(api.put).toHaveBeenCalledWith('/supply/orders/CK-1015/quote', expect.objectContaining({ lines: [{ id: 101, unitPricePaise: 200 }] })));
  unmount();
  vi.mocked(api.get).mockResolvedValue({ data: flierOrder('COMPLETED', {}, 'QUOTED') });
  render(<ProductOrderDetail orderId="CK-1015" onBack={vi.fn()} />);
  expect(await screen.findByText('Quoted', { selector: '.ck-status' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Save quote' })).not.toBeInTheDocument();
});

it.each([
  ['a non-string order number', { orderNumber: 42 }],
  ['a malformed workflow step', { workflowStep: { order: '5' } }],
  ['a non-numeric schoolId', { schoolId: '7' }],
])('refuses order details with %s rather than guessing', async (_label, order) => {
  const detail = flierOrder('PRODUCTION_PLANNING');
  vi.mocked(api.get).mockResolvedValue({ data: { ...detail, order: { ...detail.order, ...order } } });
  render(<ProductOrderDetail orderId="CK-1015" onBack={vi.fn()} />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Order details could not be read. Please refresh the order.');
});
