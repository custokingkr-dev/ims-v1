import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AdminOrdersPanel } from './AdminOrdersPanel';

vi.mock('../../../services/api');

const STAGES = ['Draft', 'Submitted', 'Approval', 'Processing', 'Delivered'];

const PROPS = {
  stats: { activeOrders: 3, termSpend: 1250000, activeServices: 0, deliveredCount: 0 },
  loading: false, notice: null,
  onNewOrder: vi.fn(), onMarkDesignApproved: vi.fn(), onReorder: vi.fn(),
};

function rowFor(overrides: Record<string, unknown>) {
  return {
    id: 'CK-1', category: 'NOTEBOOKS', orderData: '{"items":[{"name":"Long / 198 printed pages","qty":400}]}',
    totalAmount: 0, pricingStatus: 'NOT_APPLICABLE', status: 'HEAD_APPROVAL', formVersion: 2, trackerManaged: true,
    placedAt: '2026-10-20', ...overrides,
  };
}

/** Amount and Workflow are the fourth and fifth columns of the school's order list. */
function cellsOf(id: string) {
  const row = screen.getByRole('row', { name: new RegExp(id) });
  const cells = within(row).getAllByRole('cell');
  return { amount: cells[3], workflow: cells[4] };
}

describe('AdminOrdersPanel, the school admin\'s order list', () => {
  afterEach(cleanup);

  it('shows a notebook order on the tracker without a price or the old pipeline, only its tracker step', () => {
    render(<AdminOrdersPanel {...PROPS} orders={[rowFor({}), rowFor({ id: 'CK-2', status: 'COMPLETED' })]} />);
    const { amount, workflow } = cellsOf('CK-1');
    expect(amount).toHaveTextContent(/^—$/);
    expect(amount).not.toHaveTextContent('₹');
    expect(workflow).toHaveTextContent('Head approval');
    for (const stage of STAGES) expect(within(workflow).queryByText(stage)).toBeNull();
    expect(within(workflow).queryByText('✓')).toBeNull();
    expect(cellsOf('CK-2').workflow).toHaveTextContent('Completed');
    expect(cellsOf('CK-2').amount).toHaveTextContent(/^—$/);
  });

  it('keeps the price and the five-stage pipeline for a notebook order placed before the tracker (D1)', () => {
    render(<AdminOrdersPanel {...PROPS} orders={[rowFor({ id: 'CK-3', status: 'DESIGN_APPROVED_PROCESSING', trackerManaged: false,
      totalAmount: 120000, pricingStatus: 'QUOTED' })]} />);
    const { amount, workflow } = cellsOf('CK-3');
    expect(amount).toHaveTextContent('₹1,200.00');
    for (const stage of STAGES) expect(within(workflow).getByText(stage)).toBeInTheDocument();
    expect(within(workflow).getAllByText('✓')).toHaveLength(2);
  });

  it('keeps the price and the five-stage pipeline for every other category', () => {
    render(<AdminOrdersPanel {...PROPS} orders={[rowFor({ id: 'ORD-4', category: 'UNIFORMS', status: 'PROCESSING', formVersion: 1,
      trackerManaged: false, totalAmount: 5000, pricingStatus: undefined, orderData: '{"items":[{"name":"Shirt","qty":5}]}' }),
    rowFor({ id: 'ORD-5', category: 'TIES', status: 'PROCESSING', trackerManaged: undefined, totalAmount: 0, pricingStatus: 'PENDING_PRICING' })]} />);
    expect(cellsOf('ORD-4').amount).toHaveTextContent('₹50.00');
    for (const stage of STAGES) expect(within(cellsOf('ORD-4').workflow).getByText(stage)).toBeInTheDocument();
    expect(cellsOf('ORD-5').amount).toHaveTextContent('Pending pricing');
    for (const stage of STAGES) expect(within(cellsOf('ORD-5').workflow).getByText(stage)).toBeInTheDocument();
  });

  it('leaves the term spend as the server sums it: a tracker order carries no amount, so it adds nothing', () => {
    render(<AdminOrdersPanel {...PROPS} orders={[rowFor({})]} />);
    expect(screen.getByText('Term spend').closest('.ck-stat')).toHaveTextContent('₹12,500.00');
  });
});
