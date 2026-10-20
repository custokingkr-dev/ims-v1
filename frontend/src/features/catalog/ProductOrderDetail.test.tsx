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
