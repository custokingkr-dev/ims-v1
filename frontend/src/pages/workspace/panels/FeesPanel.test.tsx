import { render, screen, waitFor, cleanup } from '@testing-library/react';
import { afterEach, describe, it, expect, vi, beforeEach } from 'vitest';
import { FeesPanel } from './FeesPanel';
import api from '../../../services/api';

vi.mock('../../../services/api');
vi.mock('../../../contexts/AuthContext', () => ({ useAuth: () => ({ user: { branchId: 1 } }) }));
vi.mock('../../../hooks/usePermissions', () => ({ usePermissions: () => ({ can: () => false }) }));

afterEach(cleanup);

describe('FeesPanel summary cards', () => {
  beforeEach(() => {
    vi.mocked(api.get).mockReset();
    vi.mocked(api.get).mockImplementation((url: string) => {
      if (url === '/fees/dashboard/module') {
        return Promise.resolve({ data: { summary: { collected: 0, target: 5500000 }, records: [] } });
      }
      if (url === '/fees/dashboard/overdue-count') {
        return Promise.resolve({ data: { count: 1 } });
      }
      return Promise.resolve({ data: [] }); // /classes etc.
    });
  });

  it('populates the summary cards from /fees/dashboard/module, not the stubbed workspace summary', async () => {
    // workspace.fees.summary is deliberately all-zero (the real /workspace stub); the panel
    // must ignore it and use the real school-core summary instead.
    render(<FeesPanel workspace={{ school: { meta: '2026-27' }, fees: { summary: { collected: 0, target: 0, outstanding: 0, overdueCount: 0, progressPercent: 0 } } }} onRefresh={vi.fn()} />);

    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/fees/dashboard/module', { params: { schoolId: 1 } }));
    expect(api.get).toHaveBeenCalledWith('/fees/dashboard/overdue-count', { params: { schoolId: 1 } });

    // target 5,500,000 paise -> ₹55,000 shown on the Outstanding / Annual Target cards.
    await waitFor(() => expect(screen.getAllByText(/55,000/).length).toBeGreaterThan(0));
  });
});
