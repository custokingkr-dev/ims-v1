import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import api from '../../../services/api';
import { catalogClient } from '../../../services/catalogApi';
import { SaAllOrdersPanel } from './SaAllOrdersPanel';

vi.mock('../../../services/api', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() } }));
vi.mock('../../../services/catalogApi', () => ({ catalogClient: { getOrderPage: vi.fn(), getOrderSummary: vi.fn(), updateOrderStatus: vi.fn(), markDelivered: vi.fn() } }));
vi.mock('../../../features/catalog/useCategoryLabel', () => ({ useCategoryLabel: () => (code?: string | null) => String(code ?? '') }));
vi.mock('../../../features/orders/workflow', () => ({
  OrderDetailEntry: ({ orderId, trackerManaged }: { orderId: string; trackerManaged: boolean }) => <p>order {orderId} on a workflow: {String(trackerManaged)}</p>,
}));

const SUMMARY = { totalOrders: 3, pendingApproval: 0, approved: 0, rejected: 0, gmv: 0, activeOrders: 3, termSpend: 0, activeServices: 0, deliveredCount: 0 };

function order(id: string, overrides: Record<string, unknown> = {}) {
  return { id, category: 'FLIERS', schoolId: 7, schoolName: 'Delhi Public School', status: 'PROCESSING', totalAmount: 50000, pricingStatus: 'QUOTED',
    formVersion: 2, trackerManaged: false, placedAt: '2026-11-13', ...overrides };
}

const page = (content: unknown[]) => ({ content, page: 0, size: 200, totalElements: content.length, totalPages: 1 }) as never;

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(catalogClient.getOrderSummary).mockResolvedValue(SUMMARY as never);
});
afterEach(cleanup);

describe('All orders (section 4.8)', () => {
  it('heads each order with its number and keeps the CK id beneath, or the CK id alone when there is no number', async () => {
    vi.mocked(catalogClient.getOrderPage).mockResolvedValue(page([
      order('CK-1015', { orderNumber: 'FE-DPS_GUR-26-11-13-0042' }),
      order('CK-1016', { orderNumber: null, status: 'DRAFT' }),
    ]));
    render(<SaAllOrdersPanel onNewOrder={vi.fn()} />);
    const numbered = await screen.findByRole('row', { name: /FE-DPS_GUR-26-11-13-0042/ });
    expect(within(numbered).getByText('FE-DPS_GUR-26-11-13-0042')).toHaveClass('tb');
    expect(within(numbered).getByText('Ref CK-1015')).toBeInTheDocument();
    const plain = screen.getByRole('row', { name: /CK-1016/ });
    expect(within(plain).getByText('CK-1016')).toHaveClass('tb');
    expect(within(plain).queryByText(/^Ref /)).toBeNull();
  });

  it('searches on the server by order number, CK id or school name once typing pauses', async () => {
    vi.mocked(catalogClient.getOrderPage).mockResolvedValueOnce(page([order('CK-1015'), order('CK-1016')]))
      .mockResolvedValue(page([order('CK-1015', { orderNumber: 'FE-DPS_GUR-26-11-13-0042' })]));
    render(<SaAllOrdersPanel onNewOrder={vi.fn()} />);
    await screen.findByRole('row', { name: /CK-1016/ });
    expect(catalogClient.getOrderPage).toHaveBeenCalledWith({ page: 0, size: 200, q: undefined });
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search' }), { target: { value: '  fe-dps ' } });
    await waitFor(() => expect(catalogClient.getOrderPage).toHaveBeenLastCalledWith({ page: 0, size: 200, q: 'fe-dps' }));
    expect(await screen.findByText('FE-DPS_GUR-26-11-13-0042')).toBeInTheDocument();
    expect(screen.queryByRole('row', { name: /CK-1016/ })).toBeNull();
    expect(screen.getByRole('searchbox', { name: 'Search' })).toHaveAttribute('placeholder', 'Order number, school or old order ID');
  });

  it('says when nothing matches the search', async () => {
    vi.mocked(catalogClient.getOrderPage).mockResolvedValueOnce(page([order('CK-1015')])).mockResolvedValue(page([]));
    render(<SaAllOrdersPanel onNewOrder={vi.fn()} />);
    await screen.findByRole('row', { name: /CK-1015/ });
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search' }), { target: { value: 'XYZ' } });
    expect(await screen.findByText('No orders match “XYZ”.')).toBeInTheDocument();
  });

  it('shows where a workflow order is, keeps the quote of a priced item, and hides the amount of a notebook', async () => {
    vi.mocked(catalogClient.getOrderPage).mockResolvedValue(page([
      order('CK-1', { trackerManaged: true, status: 'PRODUCTION_PLANNING', workflowStep: { order: 5, title: 'Production Planning', total: 9, completed: 4 } }),
      order('CK-2', { category: 'NOTEBOOKS', trackerManaged: true, pricingStatus: 'NOT_APPLICABLE', totalAmount: 0, status: 'PRINTING_PRESS', workflowStep: null }),
    ]));
    render(<SaAllOrdersPanel onNewOrder={vi.fn()} />);
    const flier = await screen.findByRole('row', { name: /CK-1/ });
    expect(flier).toHaveTextContent('5 · Production Planning');
    expect(flier).toHaveTextContent('4 of 9 steps done');
    expect(flier).toHaveTextContent('₹500.00');
    const notebook = screen.getByRole('row', { name: /CK-2/ });
    expect(notebook).toHaveTextContent('Printing press');
    expect(within(notebook).getAllByRole('cell')[3]).toHaveTextContent(/^—$/);
  });

  it('opens a workflow order through the workflow entry, and any other placed form order as an ordinary one', async () => {
    vi.mocked(catalogClient.getOrderPage).mockResolvedValue(page([order('CK-7', { trackerManaged: true, status: 'ORDER_DETAILS' })]));
    render(<SaAllOrdersPanel onNewOrder={vi.fn()} />);
    fireEvent.click(within(await screen.findByRole('row', { name: /CK-7/ })).getByRole('button', { name: 'View' }));
    expect(screen.getByText('order CK-7 on a workflow: true')).toBeInTheDocument();
  });

  it('describes an invoice by the order number and keeps the CK id as its order reference', async () => {
    vi.mocked(catalogClient.getOrderPage).mockResolvedValue(page([
      order('CK-1015', { formVersion: 1, status: 'APPROVED', orderNumber: 'NB-DPS_GUR-26-11-13-0042', category: 'NOTEBOOKS' }),
    ]));
    vi.mocked(api.get).mockRejectedValue({ response: { status: 404 } });
    vi.mocked(api.post).mockResolvedValue({ data: { id: 'INV-1', orderRef: 'CK-1015', description: 'NB-DPS_GUR-26-11-13-0042' } });
    render(<SaAllOrdersPanel onNewOrder={vi.fn()} />);
    fireEvent.click(within(await screen.findByRole('row', { name: /NB-DPS_GUR/ })).getByRole('button', { name: 'Invoice' }));
    const dialog = await screen.findByRole('dialog', { name: 'Invoice' });
    expect(api.get).toHaveBeenCalledWith('/billing/sa/invoices/by-order/CK-1015');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Send to school' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/billing/sa/invoices', expect.objectContaining({
      orderRef: 'CK-1015', description: 'NB-DPS_GUR-26-11-13-0042',
    })));
  });
});
