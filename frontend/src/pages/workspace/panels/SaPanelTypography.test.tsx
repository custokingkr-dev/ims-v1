import { render, screen, cleanup, within, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import api from '../../../services/api';
import { fetchCommandCenterMetrics } from '../../../api/dashboardCommandCenterApi';
import { SaAllOrdersPanel } from './SaAllOrdersPanel';
import { SaInvoicesPanel } from './SaInvoicesPanel';
import { SaErpPanel } from './SaErpPanel';
import { SaOrderApprovalsPanel } from './SaOrderApprovalsPanel';

vi.mock('../../../services/api');
vi.mock('../../../api/dashboardCommandCenterApi');

const APPROVALS_PROPS = {
  loading: false, notice: null, savingId: '', rejectModalOrderId: null, rejectReason: '',
  onRefresh: () => {}, onApprove: () => {}, onOpenRejectModal: () => {},
  onCloseRejectModal: () => {}, onSetRejectReason: () => {}, onReject: () => {},
};

const ORDER = {
  id: 'CK-1042', schoolName: 'Green Valley School', category: 'NOTEBOOKS', totalAmount: 14000000,
  status: 'QUOTED', createdAt: '2026-09-21T10:00:00Z', pricingStatus: 'QUOTED',
};
const INVOICE = {
  id: 'INV-9', school: 'Green Valley School', orderRef: 'CK-1042', total: 6240000,
  status: 'Awaiting payment', issuedAt: '2026-09-21', description: 'Notebooks',
};

/**
 * Amounts of different magnitudes shared a column while set left-aligned in proportional
 * figures, so ₹1,40,000.00 and ₹62,400.00 lined up on their rupee signs and nothing else. A
 * money column is read by comparing magnitudes, which needs the digits to sit in a grid.
 */
describe('money columns are set as money', () => {
  afterEach(() => { vi.clearAllMocks(); cleanup(); });

  const CASES: [string, () => JSX.Element, RegExp, string][] = [
    ['all orders', () => <SaAllOrdersPanel onNewOrder={() => {}} />, /CK-1042/, '₹1,40,000.00'],
    ['invoices', () => <SaInvoicesPanel onBadgeChange={() => {}} />, /INV-9/, '₹62,400.00'],
  ];

  beforeEach(() => { vi.mocked(api.get).mockResolvedValue({ data: [ORDER, INVOICE] }); });

  for (const [name, Panel, rowName, amount] of CASES) {
    it(name, async () => {
      render(<Panel />);
      const row = await screen.findByRole('row', { name: rowName });
      const cell = within(row).getByText(amount).closest('td') as HTMLElement;
      expect(cell.className).toMatch(/\bck-num\b/);
    });
  }

  it('order approvals', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [] });
    render(<SaOrderApprovalsPanel orders={[ORDER]} {...APPROVALS_PROPS} />);
    const row = await screen.findByRole('row', { name: /CK-1042/ });
    const cell = within(row).getByText('₹1,40,000.00').closest('td') as HTMLElement;
    expect(cell.className).toMatch(/\bck-num\b/);
  });
});

describe('ERP activity uses the portal section heading', () => {
  afterEach(() => { vi.clearAllMocks(); cleanup(); });

  it('groups its metrics under the shared label rather than an invented one', async () => {
    // The panel wrote its own heading inline four times — 13px semibold in --ink2 — while the
    // application already defines .section-label for exactly this.
    vi.mocked(fetchCommandCenterMetrics).mockResolvedValue({
      fees: { defaulterCount: 4, totalOverdueAmountPaise: 500000, oldestDueDays: 12 },
      photography: { eventId: null, collectedAmount: 0, pendingAmount: 0, targetAmount: 0 },
      lifecycle: { pendingReviewCount: 2, longAbsenceCount: 1 },
      attendance: { sectionsBelowThresholdCount: 3, thresholdPercent: 75 },
      vendorDues: { catalogOrderCount: 5, catalogOrderTotalPaise: 0, firefightingCount: 1,
                    firefightingTotalPaise: 0, totalDuesPaise: 900000 },
      reorderSignals: { alertCount: 7 },
    });
    render(<SaErpPanel />);
    await waitFor(() => expect(screen.getByText('Fee collection').className).toMatch(/\bsection-label\b/));
    for (const heading of ['Attendance', 'Vendor dues']) {
      expect(screen.getByText(heading).className).toMatch(/\bsection-label\b/);
    }
  });
});

describe('the order filters offer words, not stored codes', () => {
  afterEach(() => { vi.clearAllMocks(); cleanup(); });

  it('names categories and statuses the way the table does', async () => {
    // The Category and Status dropdowns are built from the rows themselves and rendered the
    // raw value as the option text, so the filters still read REPORT_CARDS and
    // AWAITING_APPROVAL after the table beside them had been fixed.
    vi.mocked(api.get).mockResolvedValue({ data: [
      { id: 'CK-1042', schoolName: 'DPS', category: 'REPORT_CARDS', totalAmount: 100,
        status: 'AWAITING_APPROVAL', createdAt: '2026-09-21T10:00:00Z', pricingStatus: 'QUOTED' },
    ] });
    render(<SaAllOrdersPanel onNewOrder={() => {}} />);
    await waitFor(() => expect(screen.getByRole('row', { name: /CK-1042/ })).toBeInTheDocument());
    expect(screen.queryByRole('option', { name: 'REPORT_CARDS' })).toBeNull();
    expect(screen.queryByRole('option', { name: 'AWAITING_APPROVAL' })).toBeNull();
    expect(screen.getByRole('option', { name: 'Report cards' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Awaiting approval' })).toBeInTheDocument();
  });
});
