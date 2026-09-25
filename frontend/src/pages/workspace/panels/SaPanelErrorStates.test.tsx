import { render, screen, cleanup, within, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import api from '../../../services/api';
import { fetchCommandCenterMetrics } from '../../../api/dashboardCommandCenterApi';
import { SaAllOrdersPanel } from './SaAllOrdersPanel';
import { SaInvoicesPanel } from './SaInvoicesPanel';
import { SaRevenuePanel } from './SaRevenuePanel';
import { SaErpPanel } from './SaErpPanel';

vi.mock('../../../services/api');
vi.mock('../../../api/dashboardCommandCenterApi');

/**
 * A failed load used to end in one of three different shapes across these panels: a bare
 * sentence in a card, an alert with no way out, and an alert with a Retry beside it. A reader
 * who has just hit a failure should not have to learn which panel they are standing in to know
 * whether they can try again.
 */
const PANELS: [string, () => JSX.Element][] = [
  ['all orders', () => <SaAllOrdersPanel onNewOrder={() => {}} />],
  ['invoices', () => <SaInvoicesPanel onBadgeChange={() => {}} />],
  ['revenue', () => <SaRevenuePanel />],
  ['ERP activity', () => <SaErpPanel />],
];

describe('superadmin panels on a failed load', () => {
  beforeEach(() => {
    vi.mocked(api.get).mockRejectedValue({ response: { data: { message: 'Upstream unavailable.' } } });
    vi.mocked(fetchCommandCenterMetrics).mockRejectedValue({ response: { data: { message: 'Upstream unavailable.' } } });
  });
  afterEach(() => { vi.clearAllMocks(); cleanup(); });

  for (const [name, Panel] of PANELS) {
    it(`${name} raises a real alert carrying the reason`, async () => {
      render(<Panel />);
      const alert = await screen.findByRole('alert');
      expect(within(alert).getByText(/Upstream unavailable\./)).toBeInTheDocument();
    });

    it(`${name} offers a retry rather than a dead end`, async () => {
      render(<Panel />);
      const alert = await screen.findByRole('alert');
      const retry = within(alert).getByRole('button', { name: /retry/i });
      const before = vi.mocked(api.get).mock.calls.length
        + vi.mocked(fetchCommandCenterMetrics).mock.calls.length;
      fireEvent.click(retry);
      await waitFor(() => expect(
        vi.mocked(api.get).mock.calls.length + vi.mocked(fetchCommandCenterMetrics).mock.calls.length,
      ).toBeGreaterThan(before));
    });
  }
});

describe('superadmin tables do not pad rows with filler', () => {
  afterEach(() => { vi.clearAllMocks(); cleanup(); });

  it('does not repeat the category under the order id', async () => {
    // description and title do not exist on a catalog order row, so the secondary line could
    // only ever fall through to the category the next column already shows.
    vi.mocked(api.get).mockResolvedValue({ data: [
      { id: 'CK-1042', schoolName: 'Green Valley School', category: 'NOTEBOOKS', totalAmount: 1400000,
        status: 'QUOTED', createdAt: '2026-09-21T10:00:00Z', pricingStatus: 'QUOTED' },
    ] });
    render(<SaAllOrdersPanel onNewOrder={() => {}} />);
    const row = await screen.findByRole('row', { name: /CK-1042/ });
    const idCell = within(row).getByText('CK-1042').closest('td') as HTMLElement;
    expect(idCell.querySelector('.ts')).toBeNull();
  });

  it('leaves an invoice without a description blank rather than labelling it "Invoice"', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [
      { id: 'INV-9', school: 'Green Valley School', orderRef: 'CK-1042', total: 100000,
        status: 'Awaiting payment', issuedAt: '2026-09-21', description: '' },
    ] });
    render(<SaInvoicesPanel onBadgeChange={() => {}} />);
    const row = await screen.findByRole('row', { name: /INV-9/ });
    expect(within(row).queryByText('Invoice')).toBeNull();
  });

  it('does not put a permanently disabled control in every invoice row', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [
      { id: 'INV-9', school: 'Green Valley School', orderRef: 'CK-1042', total: 100000,
        status: 'Awaiting payment', issuedAt: '2026-09-21', description: 'Notebooks, term 1' },
    ] });
    render(<SaInvoicesPanel onBadgeChange={() => {}} />);
    const row = await screen.findByRole('row', { name: /INV-9/ });
    const dead = within(row).getAllByRole('button').filter((b) => (b as HTMLButtonElement).disabled);
    expect(dead).toEqual([]);
  });
});

