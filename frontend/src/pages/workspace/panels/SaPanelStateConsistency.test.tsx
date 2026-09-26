import { render, screen, cleanup, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import api from '../../../services/api';
import { fetchCommandCenterMetrics } from '../../../api/dashboardCommandCenterApi';
import { SaAllOrdersPanel } from './SaAllOrdersPanel';
import { SaInvoicesPanel } from './SaInvoicesPanel';
import { SaRevenuePanel } from './SaRevenuePanel';
import { SaErpPanel } from './SaErpPanel';
import { SaOrderApprovalsPanel } from './SaOrderApprovalsPanel';

vi.mock('../../../services/api');
vi.mock('../../../api/dashboardCommandCenterApi');

/**
 * Waiting and having nothing to show are the same two moments in every panel, and they were
 * written four different ways: padding 16 against padding 24, left-aligned against centred,
 * default ink against --ink3, inside a card and outside one. Anchoring both on one class is
 * what keeps them from drifting apart again.
 */
const APPROVALS_PROPS = {
  loading: false, notice: null, savingId: '', rejectModalOrderId: null, rejectReason: '',
  onRefresh: () => {}, onApprove: () => {}, onOpenRejectModal: () => {},
  onCloseRejectModal: () => {}, onSetRejectReason: () => {}, onReject: () => {},
};

const LOADING: [string, () => JSX.Element][] = [
  ['all orders', () => <SaAllOrdersPanel onNewOrder={() => {}} />],
  ['invoices', () => <SaInvoicesPanel onBadgeChange={() => {}} />],
  ['revenue', () => <SaRevenuePanel />],
  ['ERP activity', () => <SaErpPanel />],
  ['order approvals', () => <SaOrderApprovalsPanel orders={[]} {...APPROVALS_PROPS} loading />],
];

const EMPTY: [string, () => JSX.Element, RegExp][] = [
  ['all orders', () => <SaAllOrdersPanel onNewOrder={() => {}} />, /No orders found/],
  ['invoices', () => <SaInvoicesPanel onBadgeChange={() => {}} />, /No invoices found/],
  ['order approvals', () => <SaOrderApprovalsPanel orders={[]} {...APPROVALS_PROPS} />,
   /No orders awaiting final approval/],
];

describe('superadmin panels state their waiting and empty moments the same way', () => {
  afterEach(() => { vi.clearAllMocks(); cleanup(); });

  describe('while loading', () => {
    beforeEach(() => {
      vi.mocked(api.get).mockReturnValue(new Promise(() => {}) as never);
      vi.mocked(fetchCommandCenterMetrics).mockReturnValue(new Promise(() => {}) as never);
    });

    for (const [name, Panel] of LOADING) {
      it(name, async () => {
        render(<Panel />);
        const message = await screen.findByText(/Loading/i);
        expect(message.className).toMatch(/\bck-panel-msg\b/);
      });
    }
  });

  describe('with nothing to show', () => {
    beforeEach(() => {
      vi.mocked(api.get).mockResolvedValue({ data: [] });
    });

    for (const [name, Panel, text] of EMPTY) {
      it(name, async () => {
        render(<Panel />);
        await waitFor(() => expect(screen.getByText(text).className).toMatch(/\bck-panel-msg\b/));
      });
    }
  });
});

describe('superadmin action cells stay inside their table', () => {
  afterEach(() => { vi.clearAllMocks(); cleanup(); });

  const ROWS: [string, () => JSX.Element, RegExp][] = [
    ['all orders', () => <SaAllOrdersPanel onNewOrder={() => {}} />, /CK-1042/],
    ['invoices', () => <SaInvoicesPanel onBadgeChange={() => {}} />, /INV-9/],
  ];

  beforeEach(() => {
    vi.mocked(api.get).mockResolvedValue({ data: [
      { id: 'CK-1042', schoolName: 'Green Valley School', category: 'NOTEBOOKS', totalAmount: 1400000,
        status: 'QUOTED', createdAt: '2026-09-21T10:00:00Z', pricingStatus: 'QUOTED' },
      { id: 'INV-9', school: 'Green Valley School', orderRef: 'CK-1042', total: 100000,
        status: 'Awaiting payment', issuedAt: '2026-09-21', description: 'Notebooks' },
    ] });
  });

  for (const [name, Panel, id] of ROWS) {
    it(name, async () => {
      // display:flex on a <td> takes the cell out of the table's column algorithm, so its
      // buttons were laid out independently of the column and overhung the card's right edge.
      render(<Panel />);
      const row = await screen.findByRole('row', { name: id });
      const view = within(row).getByRole('button', { name: /view/i });
      const cell = view.closest('td') as HTMLElement;
      expect(cell.style.display).not.toBe('flex');
    });
  }
});

