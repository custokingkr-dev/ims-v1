import { render, screen, cleanup, within, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { SaOrderApprovalsPanel } from './SaOrderApprovalsPanel';
import api from '../../../services/api';

vi.mock('../../../services/api');

const NOOP = {
  loading: false, notice: null, savingId: '',
  rejectModalOrderId: null, rejectReason: '',
  onRefresh: () => {}, onApprove: () => {}, onOpenRejectModal: () => {},
  onCloseRejectModal: () => {}, onSetRejectReason: () => {}, onReject: () => {},
};

/** The catalogue is the only source of a category's human label. */
function catalogue() {
  vi.mocked(api.get).mockResolvedValue({ data: [
    { code: 'NOTEBOOKS', label: 'Notebooks', emoji: '', description: '', orderType: 'Recurring',
      formEnabled: true, active: true, sortOrder: 1 },
  ] });
}

function rowFor(overrides: Record<string, unknown> = {}) {
  return {
    id: 'CK-1042', schoolName: 'Green Valley School', category: 'REPORT_CARDS',
    totalAmount: 14000000, status: 'PENDING_APPROVAL', pricingStatus: 'QUOTED',
    createdAt: '2026-09-21T10:00:00Z', placedAt: null, ...overrides,
  };
}

describe('SaOrderApprovalsPanel table', () => {
  afterEach(cleanup);

  it('reads the category as words, never as the stored constant', async () => {
    catalogue();
    render(<SaOrderApprovalsPanel orders={[rowFor()]} {...NOOP} />);
    const row = await screen.findByRole('row', { name: /CK-1042/ });
    expect(within(row).queryByText('REPORT_CARDS')).toBeNull();
    expect(within(row).getByText('Report cards')).toBeInTheDocument();
  });

  it('prefers the catalogue label over humanising the code', async () => {
    catalogue();
    render(<SaOrderApprovalsPanel orders={[rowFor({ category: 'NOTEBOOKS' })]} {...NOOP} />);
    await waitFor(() => expect(screen.getByText('Notebooks')).toBeInTheDocument());
  });

  it('dates an order by when it was created when it has not been placed yet', async () => {
    catalogue();
    render(<SaOrderApprovalsPanel orders={[rowFor()]} {...NOOP} />);
    const row = await screen.findByRole('row', { name: /CK-1042/ });
    // placedAt is null on an order still awaiting pricing, and createdAt always exists;
    // falling through to an em dash hid the age of every such order.
    expect(within(row).getByText('21 September 2026')).toBeInTheDocument();
  });

  it('does not print the category twice in one row', async () => {
    catalogue();
    render(<SaOrderApprovalsPanel orders={[rowFor({ category: 'NOTEBOOKS' })]} {...NOOP} />);
    const row = await screen.findByRole('row', { name: /CK-1042/ });
    expect(within(row).getAllByText('Notebooks')).toHaveLength(1);
  });

  it('leaves the secondary line out rather than filling it with a dash', async () => {
    catalogue();
    render(<SaOrderApprovalsPanel orders={[rowFor({ estimatedDelivery: null })]} {...NOOP} />);
    const row = await screen.findByRole('row', { name: /CK-1042/ });
    expect(within(row).queryByText('—')).toBeNull();
  });
});
