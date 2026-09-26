import { render, screen, cleanup, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { SaErpPanel } from './SaErpPanel';
import { fetchCommandCenterMetrics } from '../../../api/dashboardCommandCenterApi';

vi.mock('../../../api/dashboardCommandCenterApi');

describe('SaErpPanel', () => {
  beforeEach(() => vi.spyOn(console, 'error').mockImplementation(() => {}));
  afterEach(() => { vi.restoreAllMocks(); cleanup(); });

  it('degrades to its own error state when the payload is not the expected shape', async () => {
    // Every section was dereferenced unguarded, so a response missing one of them threw during
    // render and took the entire application into the global error boundary — losing the nav,
    // the header and every other panel along with it.
    vi.mocked(fetchCommandCenterMetrics).mockResolvedValue('<!doctype html>' as never);
    render(<SaErpPanel />);
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByText('ERP activity')).toBeInTheDocument();
  });

  it('shows the metrics when the payload is whole', async () => {
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
    await waitFor(() => expect(screen.getByText('4')).toBeInTheDocument());
    expect(screen.getByText('7')).toBeInTheDocument();
  });
});
